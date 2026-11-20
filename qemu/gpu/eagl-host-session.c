/* SPDX-License-Identifier: GPL-2.0-or-later */
/* One private ordered GLES stream. Only the worker thread touches EGL/GL. */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include "eagl-client-arrays.h"
#include "eagl-pixel-transfer.h"
#include "eagl-wire.h"
#include <EGL/egl.h>
#include <GLES3/gl31.h>
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <time.h>
#include <inttypes.h>
#include <unistd.h>

struct context {
    EGLContext egl;
    EGLSurface surface;
    unsigned draws_since_read;
    int batch_negotiated;
    GLsync fences[64];
    uint32_t ids[64], next_fence;
};
struct worker {
    EGLDisplay display;
    EGLConfig config;
    struct context contexts[HIOS_EAGL_CONTEXTS];
    const char *expected;
    uint32_t current;
    unsigned completed_reads;
};

/* 0 is clean EOF only between complete messages; partial EOF is an error. */
static int transfer(int fd, void *data, size_t bytes, int outbound)
{
    unsigned char *p = data;
    for (size_t at = 0; at < bytes;) {
        ssize_t n = outbound ? write(fd, p + at, bytes - at) : read(fd, p + at, bytes - at);
        if (n > 0) { at += (size_t)n; continue; }
        if (n < 0 && errno == EINTR) continue;
        return n == 0 && at == 0 && !outbound ? 0 : -1;
    }
    return 1;
}

/* Per-readback split for HACKIOS_EAGL_PROFILE_READBACK (see main()). */
static uint64_t pro_readback_us, pro_convert_us;

static uint64_t monotonic_us(void)
{
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) return 0;
    return (uint64_t)ts.tv_sec * UINT64_C(1000000) + (uint64_t)ts.tv_nsec / 1000;
}

/* Shared display protocol; independently tested without a GL driver. */
#include "eagl-frame-ring.h"
#include "eagl-host-texture-state.h"
#include "eagl-batch.h"

static struct frame_ring frame_ring;

static int send_reply(uint32_t seq, uint32_t op, uint32_t status, void *data, size_t bytes)
{
    unsigned char h[HIOS_EAGL_HEADER];
    hios_put32(h, HIOS_EAGL_REPLY);
    hios_put32(h + 4, seq);
    hios_put32(h + 8, op);
    hios_put32(h + 12, status);
    hios_put32(h + 16, (uint32_t)bytes);
    return transfer(STDOUT_FILENO, h, sizeof(h), 1) == 1 &&
           transfer(STDOUT_FILENO, data, bytes, 1) == 1;
}

/*
 * Delta readback (HIOS_EAGL_READ_DELTA): the packed frame from the last fully
 * sent reply for a guest reconstruction token; only differing bands cross slirp.
 * The guest reconstructs that private frame and copies all valid pixels to
 * the live IOSurface, which can also be written by guest CPU code.
 * Sending every byte costs ~1.7 s for 10.9 MB, and without GSO or a larger MTU
 * the only lever is fewer bytes.
 *
 * The state is per guest base token: CoreAnimation keeps (at least) two
 * IOSurfaces in rotation. The tuple is {opaque token, stride, width, height};
 * the worker only compares the token, never dereferences it. Old clients used
 * the surface VA; clients with a private reconstruction cache use a fresh token
 * whenever their base is absent or invalid, guaranteeing a first FULL reply.
 * The live surface is not the delta base: CPU writes must not contaminate it.
 *
 * A readback without DELTA does not say which surface it wrote, so it drops
 * every base frame; the next delta for any surface then goes whole.
 */
#define HIOS_EAGL_DELTA_SURFACES 3u
struct read_delta_surface {
    uint64_t key;       /* the guest's surface handle, compared only */
    uint32_t stride, width, height;
    uint32_t convert;   /* the SWAP/DEST_ROWS its base frame was converted with */
    size_t size;        /* bytes of that base frame, 0 until one was committed */
    unsigned char *frame;   /* private reconstruction base from the last sent reply */
    uint64_t used;      /* last use, for the LRU choice */
    int valid;
};

static struct read_delta_surface read_delta[HIOS_EAGL_DELTA_SURFACES];
static uint64_t read_delta_clock;
/* The frame being built this readback (one worker thread); it becomes the
 * surface's base only after its whole reply went out. */
static unsigned char *delta_frame;
static size_t delta_frame_capacity;
static int delta_pending = -1;
static size_t delta_pending_size;
static uint32_t delta_pending_convert;

static void delta_forget_all(void)
{
    for (unsigned i = 0; i < HIOS_EAGL_DELTA_SURFACES; ++i) read_delta[i].valid = 0;
    delta_pending = -1;
}

/* The slot for this surface, evicting the least recently used one. The evicted
 * buffer is kept for the next frame that takes the slot; only `valid` is
 * dropped, so a stale frame can never be compared with. */
static struct read_delta_surface *delta_for_surface(uint64_t key, uint32_t stride,
                                                    uint32_t width, uint32_t height)
{
    struct read_delta_surface *unused = NULL, *oldest = NULL;
    for (unsigned i = 0; i < HIOS_EAGL_DELTA_SURFACES; ++i) {
        struct read_delta_surface *s = &read_delta[i];
        if (s->key == key && s->stride == stride && s->width == width && s->height == height) {
            s->used = ++read_delta_clock;
            return s;
        }
        if (!s->frame) unused = s;
        if (!oldest || s->used < oldest->used) oldest = s;
    }
    struct read_delta_surface *s = unused ? unused : oldest;
    s->key = key;
    s->stride = stride;
    s->width = width;
    s->height = height;
    s->valid = 0;
    s->used = ++read_delta_clock;
    return s;
}

/* Where the readback's converted frame is built, at most one per worker. */
static int delta_scratch(size_t size)
{
    if (size <= delta_frame_capacity) return 1;
    unsigned char *grown = realloc(delta_frame, size);
    if (!grown) return 0;
    delta_frame = grown;
    delta_frame_capacity = size;
    return 1;
}

/* Only a reply that reached the guest whole leaves that surface holding the
 * frame; after a failed send its next readback must go whole. Called once the
 * reply has been written, never before. */
static void delta_commit(int sent)
{
    if (delta_pending < 0) return;
    struct read_delta_surface *s = &read_delta[delta_pending];
    delta_pending = -1;
    if (!sent) {
        s->valid = 0;
        return;
    }
    if (s->size != delta_pending_size) {
        free(s->frame);
        s->frame = malloc(delta_pending_size);
        if (!s->frame) {
            s->size = 0;
            s->valid = 0;
            return;
        }
        s->size = delta_pending_size;
    }
    memcpy(s->frame, delta_frame, delta_pending_size);
    s->convert = delta_pending_convert;
    s->valid = 1;
    s->used = ++read_delta_clock;
}

static int init_display(struct worker *w)
{
    const EGLint attrs[] = {EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, 0x40,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 24, EGL_STENCIL_SIZE, 8, EGL_NONE};
    EGLint count = 0;
    w->display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    return w->display != EGL_NO_DISPLAY && eglInitialize(w->display, NULL, NULL) &&
        eglBindAPI(EGL_OPENGL_ES_API) && eglChooseConfig(w->display, attrs, &w->config, 1, &count) && count == 1;
}

static int close_context(struct worker *w, uint32_t slot)
{
    struct context *c = &w->contexts[slot - 1];
    if (w->current == slot) {
        if (!eglMakeCurrent(w->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT)) return 0;
        w->current = 0;
    }
    hios_texture_drop_context(slot);
    if (c->surface != EGL_NO_SURFACE) eglDestroySurface(w->display, c->surface);
    if (c->egl != EGL_NO_CONTEXT) eglDestroyContext(w->display, c->egl);
    *c = (struct context){.egl = EGL_NO_CONTEXT, .surface = EGL_NO_SURFACE};
    return 1;
}

static int set_context(struct worker *w, uint32_t slot)
{
    struct context *c = &w->contexts[slot - 1];
    if (c->egl == EGL_NO_CONTEXT || c->surface == EGL_NO_SURFACE) return 0;
    if (w->current != slot) {
        if (!eglMakeCurrent(w->display, c->surface, c->surface, c->egl)) return 0;
        w->current = slot;
    }
    hios_texture_select_context(slot);
    return 1;
}

static int open_context(struct worker *w, uint32_t slot)
{
    const EGLint ctx[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    const EGLint surf[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE};
    struct context *c = &w->contexts[slot - 1];
    c->egl = eglCreateContext(w->display, w->config, EGL_NO_CONTEXT, ctx);
    if (c->egl != EGL_NO_CONTEXT) c->surface = eglCreatePbufferSurface(w->display, w->config, surf);
    if (set_context(w, slot)) {
        const char *name = (const char *)glGetString(GL_RENDERER);
        if (name && strstr(name, w->expected)) {
            fprintf(stderr, "EAGL worker: context=%u renderer=%s\n", slot, name);
            return 1;
        }
        fprintf(stderr, "EAGL worker: unexpected renderer %s\n", name ? name : "<null>");
    }
    close_context(w, slot);
    return 0;
}

static GLfloat bits_float(uint32_t u)
{
    GLfloat value;
    _Static_assert(sizeof(value) == sizeof(u), "IEEE binary32 GLfloat required");
    memcpy(&value, &u, sizeof(value));
    return value;
}

static int valid_name(const unsigned char *p, size_t bytes, size_t max)
{
    return bytes > 1 && bytes <= max && p[bytes - 1] == 0 && !memchr(p, 0, bytes - 1);
}

/* glGetIntegerv can write variable-size arrays. Allow only verified queries. */
static unsigned integer_arity(GLenum query)
{
    switch (query) {
    case GL_VIEWPORT: case GL_SCISSOR_BOX: return 4;
    case GL_MAX_VIEWPORT_DIMS: return 2;
    case GL_ACTIVE_TEXTURE: case GL_CURRENT_PROGRAM: case GL_FRAMEBUFFER_BINDING:
    case GL_READ_FRAMEBUFFER_BINDING: case GL_RENDERBUFFER_BINDING:
    case GL_TEXTURE_BINDING_2D: case GL_PACK_ALIGNMENT: case GL_PACK_ROW_LENGTH:
    case GL_UNPACK_ALIGNMENT: case GL_UNPACK_ROW_LENGTH:
    case GL_MAX_TEXTURE_SIZE: case GL_MAX_TEXTURE_IMAGE_UNITS: case GL_MAX_VERTEX_ATTRIBS:
    case GL_BLEND_SRC_RGB: case GL_BLEND_DST_RGB: case GL_BLEND_SRC_ALPHA: case GL_BLEND_DST_ALPHA:
    case GL_MAX_RENDERBUFFER_SIZE: case GL_RED_BITS: case GL_GREEN_BITS: case GL_BLUE_BITS:
    case GL_ALPHA_BITS: case GL_DEPTH_BITS: case GL_STENCIL_BITS: case GL_SUBPIXEL_BITS: return 1;
    default: return 0;
    }
}

static uint32_t dispatch(struct worker *w, uint32_t slot, uint32_t op, const unsigned char *data,
                         size_t bytes, unsigned char *small, unsigned char **out, size_t *len)
{
    #define U(i) hios_get32(data + 4u * (i))
    #define I(i) ((int32_t)U(i))
    #define F(i) bits_float(U(i))
    #define REQ(n) do { if (bytes != (size_t)(n)) return EA_BAD_COMMAND; } while (0)
    #define RETURN_WORD(x) do { hios_put32(small, (uint32_t)(x)); *len = 4; return EA_OK; } while (0)
    if (slot < 1 || slot > HIOS_EAGL_CONTEXTS) return EA_NO_CONTEXT;
    struct context *c = &w->contexts[slot - 1];
    if (op == EA_OPEN) {
        REQ(4);
        if (U(0) != 3 || c->egl != EGL_NO_CONTEXT) return EA_BAD_COMMAND;
        return open_context(w, slot) ? EA_OK : EA_DEVICE_ERROR;
    }
    if (c->egl == EGL_NO_CONTEXT) return EA_NO_CONTEXT;
    if (op == EA_CLOSE) {
        REQ(0);
        return close_context(w, slot) ? EA_OK : EA_DEVICE_ERROR;
    }
    if (!set_context(w, slot)) return EA_DEVICE_ERROR;
    if (op == EA_BATCH_CAPS) {
        REQ(4);
        if (U(0)!=HIOS_BATCH_TAG) return EA_BAD_COMMAND;
        hios_put32(small,HIOS_BATCH_TAG);hios_put32(small+4,HIOS_BATCH_BYTES);
        hios_put32(small+8,HIOS_BATCH_COMMANDS);hios_put32(small+12,HIOS_BATCH_FEATURES);
        *len=16;c->batch_negotiated=1;return EA_OK;
    }
    if (op == EA_BATCH_EXEC) {
        /* Pure complete shape validation happens before ANY child GL command.
         * An invalid later child never leaves an earlier child executed. */
        if (!c->batch_negotiated || !hios_batch_packet_valid(data,bytes)) return EA_DEVICE_ERROR;
        uint32_t commands=U(1);size_t at=8;
        for(uint32_t n=0;n<commands;n++) {
            uint32_t child=hios_get32(data+at),count=hios_get32(data+at+4);at+=8;
            unsigned char *child_out=small;size_t child_len=0;
            uint32_t status=dispatch(w,slot,child,data+at,count,small,&child_out,&child_len);
            if (child_out!=small) free(child_out);
            if (status!=EA_OK || child_len) return EA_DEVICE_ERROR;
            at+=count;
        }
        *len=0;return EA_OK;
    }
    if (op == EA_glTexParameteri) {
        REQ(12);
        return hios_texture_parameteri(U(0), U(1), I(2)) ? EA_OK : EA_BAD_COMMAND;
    }
    switch (op) {
    case EA_FENCE: {
        REQ(8);
        if (U(0) != GL_SYNC_GPU_COMMANDS_COMPLETE || U(1) || c->next_fence == UINT32_MAX)
            return EA_BAD_COMMAND;
        for (unsigned i = 0; i < 64; ++i) {
            if (c->fences[i]) continue;
            c->fences[i] = glFenceSync(U(0), U(1));
            if (!c->fences[i]) return EA_DEVICE_ERROR;
            c->ids[i] = ++c->next_fence;
            RETURN_WORD(c->ids[i]);
        }
        return EA_BAD_COMMAND;
    }
    case EA_CLIENT_WAIT: case EA_DELETE_FENCE: {
        REQ(op == EA_CLIENT_WAIT ? 16 : 4);
        for (unsigned i = 0; i < 64; ++i) {
            if (!c->fences[i] || c->ids[i] != U(0)) continue;
            if (op == EA_CLIENT_WAIT) {
                GLuint64 timeout = (GLuint64)U(3) << 32 | U(2);
                if (U(1) & ~GL_SYNC_FLUSH_COMMANDS_BIT || timeout > UINT64_C(1000000000))
                    return EA_BAD_COMMAND;
                RETURN_WORD(glClientWaitSync(c->fences[i], U(1), timeout));
            }
            glDeleteSync(c->fences[i]);
            c->fences[i] = NULL;
            return EA_OK;
        }
        return EA_BAD_COMMAND;
    }
    case EA_STRING: {
        REQ(4);
        GLenum what = U(0);
        if (what != GL_RENDERER && what != GL_VERSION && what != GL_VENDOR &&
            what != GL_SHADING_LANGUAGE_VERSION && what != GL_EXTENSIONS) return EA_BAD_COMMAND;
        /* Do not advertise host extensions the bridge can't forward. */
        const char *value = what == GL_EXTENSIONS ? "" : (const char *)glGetString(what);
        if (!value || strlen(value) > 2047) return EA_DEVICE_ERROR;
        *len = strlen(value) + 1;
        memcpy(small, value, *len);
        return EA_OK;
    }
    case EA_SHADER_SOURCE: {
        if (bytes < 6 || !valid_name(data + 4, bytes - 4, 256 * 1024)) return EA_BAD_COMMAND;
        const GLchar *source = (const char *)data + 4;
        glShaderSource(U(0), 1, &source, NULL);
        return EA_OK;
    }
    case EA_SHADER_IV: case EA_PROGRAM_IV: {
        REQ(8);
        GLint value = 0;
        if (op == EA_SHADER_IV) glGetShaderiv(U(0), U(1), &value);
        else glGetProgramiv(U(0), U(1), &value);
        RETURN_WORD(value);
    }
    case EA_INFO_LOG: {
        REQ(8);
        GLsizei size = 0;
        if (U(1) == 1) glGetShaderInfoLog(U(0), 2048, &size, (GLchar *)small);
        else if (U(1) == 2) glGetProgramInfoLog(U(0), 2048, &size, (GLchar *)small);
        else return EA_BAD_COMMAND;
        if (size < 0 || size >= 2048) return EA_DEVICE_ERROR;
        small[size] = 0;
        *len = (size_t)size + 1;
        return EA_OK;
    }
    case EA_BIND_ATTRIB:
        if (bytes < 10 || !valid_name(data + 8, bytes - 8, 1024)) return EA_BAD_COMMAND;
        glBindAttribLocation(U(0), U(1), (const GLchar *)data + 8);
        return EA_OK;
    case EA_UNIFORM_LOCATION:
        if (bytes < 6 || !valid_name(data + 4, bytes - 4, 1024)) return EA_BAD_COMMAND;
        RETURN_WORD(glGetUniformLocation(U(0), (const GLchar *)data + 4));
    case EA_CREATE_OBJECTS: case EA_DELETE_OBJECTS: {
        if (bytes < 8 || U(1) > 64) return EA_BAD_COMMAND;
        GLsizei n = (GLsizei)U(1);
        GLuint ids[64] = {0};
        if (op == EA_CREATE_OBJECTS) {
            REQ(8);
            if (U(0) == EA_TEXTURE) glGenTextures(n, ids);
            else if (U(0) == EA_FBO) glGenFramebuffers(n, ids);
            else if (U(0) == EA_RBO) glGenRenderbuffers(n, ids);
            else return EA_BAD_COMMAND;
            for (GLsizei i = 0; i < n; ++i) hios_put32(small + 4u * i, ids[i]);
            *len = (size_t)n * 4;
        } else {
            REQ(8 + (size_t)n * 4);
            for (GLsizei i = 0; i < n; ++i) ids[i] = U(2 + i);
            if (U(0) == EA_TEXTURE) {
                glDeleteTextures(n, ids);
                hios_texture_delete((unsigned)n, ids);
            }
            else if (U(0) == EA_FBO) glDeleteFramebuffers(n, ids);
            else if (U(0) == EA_RBO) glDeleteRenderbuffers(n, ids);
            else return EA_BAD_COMMAND;
        }
        return EA_OK;
    }
    case EA_UNIFORM_VECTOR: {
        /* location, number of tuples, floats per tuple (1–4 or 16 matrix), values */
        if (bytes < 12 || U(1) > 64 || (!U(2) || (U(2) > 4 && U(2) != 16))) return EA_BAD_COMMAND;
        size_t n = (size_t)U(1) * U(2);
        REQ(12 + n * 4);
        GLfloat values[64 * 16];
        for (size_t i = 0; i < n; ++i) values[i] = F(3 + i);
        switch (U(2)) {
        case 1: glUniform1fv(I(0), I(1), values); break;
        case 2: glUniform2fv(I(0), I(1), values); break;
        case 3: glUniform3fv(I(0), I(1), values); break;
        case 4: glUniform4fv(I(0), I(1), values); break;
        default: glUniformMatrix4fv(I(0), I(1), GL_FALSE, values); break;
        }
        return EA_OK;
    }
    case EA_TEXTURE_BASE:
        if (bytes < 12) return EA_BAD_COMMAND;
        return hios_gles_upload_texture(bytes > 12 ? data + 12 : NULL, bytes - 12,
                                       U(0), U(1), (enum hios_pixel_format)U(2)) ? EA_OK : EA_BAD_COMMAND;
    case EA_TEXTURE_MIP:
        if (bytes < 16 || !U(3)) return EA_BAD_COMMAND;
        return hios_gles_upload_texture_level(bytes > 16 ? data + 16 : NULL, bytes - 16,
                                              U(0), U(1), (enum hios_pixel_format)U(2), U(3)) ? EA_OK : EA_BAD_COMMAND;
    case EA_TEXTURE_LEVEL_QUERY: {
        REQ(12);
        if (U(0) != GL_TEXTURE_2D || U(2) > HIOS_TEXTURE_MAX_LEVEL ||
            (U(1) != GL_TEXTURE_WIDTH && U(1) != GL_TEXTURE_HEIGHT && U(1) != GL_TEXTURE_INTERNAL_FORMAT))
            return EA_BAD_COMMAND;
        /* The internal format is the actual ES backing format for the level. */
        GLint major = 0, minor = 0, value = 0;
        glGetIntegerv(GL_MAJOR_VERSION, &major);
        glGetIntegerv(GL_MINOR_VERSION, &minor);
        if (major < 3 || (major == 3 && minor < 1)) return EA_BAD_COMMAND;
        glGetTexLevelParameteriv(U(0), I(2), U(1), &value);
        RETURN_WORD(value);
    }
    case EA_TEXTURE_QUERY: {
        REQ(8);
        if (U(0) != GL_TEXTURE_2D) return EA_BAD_COMMAND;
        GLint value = 0;
        if (hios_texture_is_swizzle(U(1))) {
            if (!hios_texture_parameter_query(U(0), U(1), &value)) return EA_BAD_COMMAND;
            RETURN_WORD(value);
        }
        if (U(1) != GL_TEXTURE_WRAP_S && U(1) != GL_TEXTURE_WRAP_T &&
            U(1) != GL_TEXTURE_MAG_FILTER && U(1) != GL_TEXTURE_MIN_FILTER)
            return EA_BAD_COMMAND;
        glGetTexParameteriv(U(0), U(1), &value);
        RETURN_WORD(value);
    }
    case EA_DRAW_ARRAYS:
        if (!hios_gles_draw_client_arrays(data, bytes)) return EA_BAD_COMMAND;
        ++c->draws_since_read;
        return EA_OK;
    case EA_DRAW_NO_ARRAYS:
        REQ(12);
        if (U(0) > GL_TRIANGLE_FAN || !U(2) || U(2) > 65536 || U(1) > INT32_MAX - (U(2) - 1))
            return EA_BAD_COMMAND;
        /* Staged draws restore the default VAO; no client data is bound here. */
        glDrawArrays(U(0), I(1), I(2));
        ++c->draws_since_read;
        return EA_OK;
    case EA_READ_RGBA: {
        if (bytes != 8 && bytes != HIOS_EAGL_READ_REQUEST &&
            bytes != HIOS_EAGL_READ_DELTA_REQUEST) return EA_BAD_COMMAND;
        uint32_t flags = bytes == 8 ? 0 : U(2);
        if (flags & ~(HIOS_EAGL_READ_SWAP | HIOS_EAGL_READ_DEST_ROWS | HIOS_EAGL_READ_NO_REPLY |
                      HIOS_EAGL_READ_DELTA))
            return EA_BAD_COMMAND;
        /* A band goes into the guest's BGRA surface unchanged, and a NO_REPLY
         * readback gives the guest no frame a delta could be applied to. */
        if ((flags & HIOS_EAGL_READ_DELTA) &&
            (!(flags & HIOS_EAGL_READ_SWAP) || (flags & HIOS_EAGL_READ_NO_REPLY)))
            return EA_BAD_COMMAND;
        const uint32_t width = U(0), height = U(1);
        const uint32_t convert = flags & (HIOS_EAGL_READ_SWAP | HIOS_EAGL_READ_DEST_ROWS);
        const int delta = (flags & HIOS_EAGL_READ_DELTA) != 0;
        /* The surface a delta is about. A twelve-byte DELTA request predates
         * the handle and gets key zero: one shared bucket, the best that can
         * be done without it. */
        uint64_t key = 0;
        uint32_t stride = 0;
        if (bytes == HIOS_EAGL_READ_DELTA_REQUEST) {
            key = (uint64_t)U(4) << 32 | U(3);
            stride = U(5);
        }
        size_t n = hios_pixel_packed_size(width, height, HIOS_RGBA8);
        if (!n) return EA_BAD_COMMAND;
        struct read_delta_surface *surface = NULL;
        if (delta) {
            if (!delta_scratch(n)) return EA_DEVICE_ERROR;
            surface = delta_for_surface(key, stride, width, height);
        } else {
            /* A reply without DELTA does not say which surface it wrote, so no
             * base frame can be trusted afterwards. */
            delta_forget_all();
        }
        unsigned char *rgba = calloc(n, 1);
        if (!rgba) return EA_DEVICE_ERROR;
        /* Experimental frames fail closed on any outstanding GL error.
         * Completion logging must not count a failed read of a zeroed buffer. */
        uint64_t began = monotonic_us();
        if (glGetError() != GL_NO_ERROR || !hios_gles_read_frame_rgba(rgba, n, width, height) ||
            glGetError() != GL_NO_ERROR) {
            free(rgba);
            return EA_DEVICE_ERROR;
        }
        uint64_t ready = monotonic_us();
        pro_readback_us = ready - began;
        pro_convert_us = 0;
        if (convert) {
            /* The guest is an emulated ARM; converting here is one native pass
             * over the frame instead of millions of byte operations under TCG.
             * With DELTA the result is also the frame this surface is compared
             * with, so it is built in the shared scratch, not a temporary. */
            unsigned char *converted = delta ? delta_frame : calloc(n, 1);
            if (!converted) {
                free(rgba);
                return EA_DEVICE_ERROR;
            }
            hios_pixels_convert(converted, rgba, width, height, flags & HIOS_EAGL_READ_SWAP,
                                flags & HIOS_EAGL_READ_DEST_ROWS);
            pro_convert_us = monotonic_us() - ready;
            free(rgba);
            rgba = converted;
        }
        /*
         * A6：每一帧都发布到帧环，**不管来宾要不要像素**。
         *
         * App 的显示来源是帧环而不是 screendump，这两件事是独立的：帧环解决「App 怎么拿到帧」
         * （省掉 QEMU 写 8 MB PPM + App 逐像素解码，且不占 QEMU 主循环），NO_REPLY 解决
         * 「像素要不要回给来宾」。把它们绑在一起是错的设计——一旦来宾不要像素，App 就没帧可显示。
         */
        int ring_published = ring_publish(&frame_ring, rgba, width, height);
        if ((flags & HIOS_EAGL_READ_NO_REPLY) && ring_published) {
            /* 来宾明确说了不要像素（已在上面发布到帧环）：只回头，不回字节。
             * 这条路径目前**默认关闭**——跳过回写会让 IOSurface 变成旧内容，而 CA 下一帧会把它
             * 当纹理上传，于是新画的图标被旧内容盖掉、没画过的地方是黑块（0.31.4 真机实测）。
             * 重新启用前必须先解决「像素怎么廉价地回到 surface」，见 docs/gl-frame-ring.md。 */
            free(rgba);
            fprintf(stderr, "EAGL readback: context=%u read=%u draws_submitted=%u size=%ux%u flags=%u ring=%"
                    PRIu64 "\n", slot, ++w->completed_reads, c->draws_since_read, U(0), U(1), flags,
                    frame_ring.seq);
            c->draws_since_read = 0;
            return EA_OK;
        }
        if (!delta) {
            *out = rgba;
            *len = n;
            fprintf(stderr, "EAGL readback: context=%u read=%u draws_submitted=%u size=%ux%u flags=%u\n",
                    slot, ++w->completed_reads, c->draws_since_read, width, height, flags);
            c->draws_since_read = 0;
            return EA_OK;
        }
        /*
         * Compare band by band: a memcmp over a 72 KB band is cheap next to
         * sending the band, and 16 rows keeps a small change (a clock digit,
         * a cursor) from pulling in much more than it touched.
         */
        const size_t row = (size_t)width * 4;
        const unsigned bands = (unsigned)((height + HIOS_EAGL_DELTA_BAND_ROWS - 1) /
                                          HIOS_EAGL_DELTA_BAND_ROWS);
        /* The packed-size cap is 4096 rows, so this cannot happen; it keeps the
         * band bitmap below out of reach of a future larger limit. */
        if (bands > HIOS_EAGL_DELTA_BANDS) return EA_BAD_COMMAND;
        const unsigned char *base = surface->frame;
        unsigned char *cur = rgba;
        unsigned char changed[HIOS_EAGL_DELTA_BANDS] = {0};
        unsigned changed_rows = 0, changed_bands = 0;
        /* The slot already matches key, stride and size; only a different
         * conversion (a flip) makes its bytes incomparable. */
        const int same = surface->valid && surface->size == n && surface->convert == convert;
        if (same) {
            for (unsigned b = 0; b < bands; ++b) {
                const uint32_t y = b * HIOS_EAGL_DELTA_BAND_ROWS;
                const uint32_t rows = height - y < HIOS_EAGL_DELTA_BAND_ROWS
                    ? height - y : HIOS_EAGL_DELTA_BAND_ROWS;
                changed[b] = memcmp(cur + (size_t)y * row, base + (size_t)y * row,
                                    (size_t)rows * row) != 0;
                if (changed[b]) {
                    changed_rows += rows;
                    ++changed_bands;
                }
            }
        }
        /* A row table costs eight bytes a band, so past half the frame the
         * whole picture is the smaller reply. The cap check is for the absurd
         * end: a band reply may never outgrow the wire's one-payload limit. */
        const size_t part_len = HIOS_EAGL_DELTA_HEADER + (size_t)changed_bands * HIOS_EAGL_DELTA_BAND +
            (size_t)changed_rows * row;
        const int part = same && (size_t)changed_rows * 2 <= height && part_len <= HIOS_EAGL_MAX;
        size_t reply_len;
        unsigned char *reply;
        if (part) {
            reply = malloc(part_len);
            if (!reply) {
                surface->valid = 0;   /* (rgba is the shared scratch) */
                return EA_DEVICE_ERROR;
            }
            memcpy(reply, HIOS_EAGL_DELTA_PART, 4);
            hios_put32(reply + 4, changed_bands);
            size_t entry = HIOS_EAGL_DELTA_HEADER;
            size_t pixels = HIOS_EAGL_DELTA_HEADER + (size_t)changed_bands * HIOS_EAGL_DELTA_BAND;
            for (unsigned b = 0; b < bands; ++b) {
                if (!changed[b]) continue;
                const uint32_t y = b * HIOS_EAGL_DELTA_BAND_ROWS;
                const uint32_t rows = height - y < HIOS_EAGL_DELTA_BAND_ROWS
                    ? height - y : HIOS_EAGL_DELTA_BAND_ROWS;
                hios_put32(reply + entry, y);
                hios_put32(reply + entry + 4, rows);
                memcpy(reply + pixels, cur + (size_t)y * row, (size_t)rows * row);
                entry += HIOS_EAGL_DELTA_BAND;
                pixels += (size_t)rows * row;
            }
            reply_len = part_len;
        } else {
            reply_len = n + 4;
            reply = malloc(reply_len);
            if (!reply) {
                surface->valid = 0;
                return EA_DEVICE_ERROR;
            }
            memcpy(reply, HIOS_EAGL_DELTA_FULL, 4);
            memcpy(reply + 4, cur, n);
        }
        /* The frame only becomes that surface's base once this whole reply is
         * out (main calls delta_commit); until then the guest's surface may not
         * hold it. */
        delta_pending = (int)(surface - read_delta);
        delta_pending_size = n;
        delta_pending_convert = convert;
        *out = reply;
        *len = reply_len;
        fprintf(stderr, "EAGL readback: context=%u read=%u draws_submitted=%u size=%ux%u flags=%u "
                "surface=%08x delta=%u/%u bands=%u wire=%u\n", slot, ++w->completed_reads,
                c->draws_since_read, width, height, flags, (unsigned)(uint32_t)key,
                part ? changed_rows : height, height, part ? changed_bands : bands,
                (unsigned)reply_len);
        c->draws_since_read = 0;
        return EA_OK;
    }
    case EA_FLOAT_STATE: {
        REQ(4);
        if (U(0) != GL_COLOR_CLEAR_VALUE) return EA_BAD_COMMAND;
        GLfloat values[4] = {0};
        glGetFloatv(U(0), values);
        for (unsigned i = 0; i < 4; ++i) {
            uint32_t bits;
            memcpy(&bits, &values[i], sizeof bits);
            hios_put32(small + 4u * i, bits);
        }
        *len = 16;
        return EA_OK;
    }
    case EA_FRAMEBUFFER_ATTACHMENT: {
        REQ(12);
        GLint value = 0;
        glGetFramebufferAttachmentParameteriv(U(0), U(1), U(2), &value);
        RETURN_WORD(value);
    }
    case EA_INTEGER: {
        REQ(4);
        GLint values[4] = {0};
        unsigned n = integer_arity(U(0));
        if (!n) {
            fprintf(stderr, "EAGL worker: unsupported glGetIntegerv(0x%x) context=%u\n", U(0), slot);
            return EA_BAD_COMMAND;
        }
        glGetIntegerv(U(0), values);
        for (unsigned i = 0; i < n; ++i) hios_put32(small + 4 * i, (uint32_t)values[i]);
        *len = n * 4;
        return EA_OK;
    }
#define GL_VOID(id, name, n, ...) case id: REQ(4u * (n)); name(__VA_ARGS__); return EA_OK;
#define GL_UINT(id, name, n, ...) case id: REQ(4u * (n)); RETURN_WORD(name(__VA_ARGS__));
#include "eagl-scalars.def"
#undef GL_VOID
#undef GL_UINT
    default: return EA_BAD_COMMAND;
    }
    #undef U
    #undef I
    #undef F
    #undef REQ
    #undef RETURN_WORD
}

/* A fresh socket in an app-private directory; never unlink someone else's stale path. */
static int attach_unix(const char *path)
{
    struct sockaddr_un addr = {.sun_family = AF_UNIX};
    size_t n = strlen(path);
    if (path[0] != '/' || n >= sizeof(addr.sun_path) || strpbrk(path, "\r\n")) {
        errno = EINVAL;
        perror("EAGL worker: invalid Unix socket path");
        return 0;
    }
    memcpy(addr.sun_path, path, n + 1);
    int server = socket(AF_UNIX, SOCK_STREAM, 0);
    if (server < 0) { perror("EAGL worker: socket"); return 0; }
    mode_t old = umask(0177);
    int bound = bind(server, (const struct sockaddr *)&addr, offsetof(struct sockaddr_un, sun_path) + n + 1);
    int bind_error = errno;
    umask(old);
    if (bound < 0) {
        errno = bind_error;
        perror("EAGL worker: bind");
        close(server);
        return 0;
    }
    if (listen(server, 1) < 0) {
        perror("EAGL worker: listen");
        close(server);
        unlink(path);
        return 0;
    }
    fprintf(stderr, "EAGL worker: listening on %s\n", path);
    int peer;
    do { peer = accept(server, NULL, NULL); } while (peer < 0 && errno == EINTR);
    if (peer < 0) perror("EAGL worker: accept");
    close(server);
    if (unlink(path) < 0 && errno != ENOENT) perror("EAGL worker: unlink socket");
    if (peer < 0) return 0;
#ifdef SO_PEERCRED
    struct ucred cred;
    socklen_t size = sizeof(cred);
    if (getsockopt(peer, SOL_SOCKET, SO_PEERCRED, &cred, &size) < 0 ||
            size != sizeof(cred) || cred.uid != geteuid()) {
        fprintf(stderr, "EAGL worker: refused Unix peer UID\n");
        close(peer);
        return 0;
    }
#endif
    int ok = dup2(peer, STDIN_FILENO) >= 0 && dup2(peer, STDOUT_FILENO) >= 0;
    if (!ok) perror("EAGL worker: dup2 Unix stream");
    if (peer > STDOUT_FILENO) close(peer);
    return ok;
}

static int self_test(struct worker *w)
{
    /* Verify renderer identity and real render-to-readback in the installed process. */
    if (!open_context(w, 1)) return 1;
    unsigned char rgba[4] = {0};
    glClearColor(0.25f, 0.5f, 0.75f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    glFinish();
    glReadPixels(0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
    int ok = glGetError() == GL_NO_ERROR && abs(rgba[0] - 64) <= 1 &&
        abs(rgba[1] - 128) <= 1 && abs(rgba[2] - 191) <= 1 && rgba[3] == 255;
    fprintf(stderr, "EAGL worker: self-test %s rgba=%u,%u,%u,%u\n", ok ? "passed" : "failed",
        (unsigned)rgba[0], (unsigned)rgba[1], (unsigned)rgba[2], (unsigned)rgba[3]);
    close_context(w, 1);
    return ok ? 0 : 1;
}

int main(int argc, char **argv)
{
    const char *listen_path = NULL, *ring_path = NULL;
    int smoke = 0;
    for (int i = 2; i < argc; ++i) {
        if (!strcmp(argv[i], "--listen-unix") && i + 1 < argc) {
            listen_path = argv[++i];
        } else if (!strcmp(argv[i], "--frame-ring") && i + 1 < argc) {
            ring_path = argv[++i];
        } else if (!strcmp(argv[i], "--self-test")) {
            smoke = 1;
        } else {
            listen_path = (const char *)-1;   /* (an argument this program does not know) */
            break;
        }
    }
    if (argc < 2 || !argv[1][0] || listen_path == (const char *)-1) {
        fprintf(stderr, "usage: hackios-zink-session RENDERER [--listen-unix ABS_PATH] "
                "[--frame-ring ABS_PATH | --self-test]\n");
        return 2;
    }
    signal(SIGPIPE, SIG_IGN);
    /* Optional display side: a ring that cannot be opened changes nothing else. */
    if (ring_path) (void)ring_open(&frame_ring, ring_path);
    if (listen_path && !attach_unix(listen_path)) return 1;
    struct worker w = {.display = EGL_NO_DISPLAY, .expected = argv[1]};
    for (unsigned i = 0; i < HIOS_EAGL_CONTEXTS; ++i) {
        w.contexts[i].egl = EGL_NO_CONTEXT;
        w.contexts[i].surface = EGL_NO_SURFACE;
    }
    if (!init_display(&w)) {
        fprintf(stderr, "EAGL worker: EGL initialization failed: 0x%x\n", eglGetError());
        return 1;
    }
    if (smoke) {
        int result = self_test(&w);
        eglTerminate(w.display);
        return result;
    }
    int result = 1;
    const char *profile = getenv("HACKIOS_EAGL_PROFILE_READBACK");
    const int profile_readback = profile && !strcmp(profile, "1");
    uint32_t expected_seq = 1;
    unsigned char *data = NULL;
    size_t capacity = 0;
    for (;;) {
        unsigned char h[HIOS_EAGL_HEADER], small[2048];
        if (transfer(STDIN_FILENO, h, sizeof(h), 0) != 1) break;
        uint32_t seq = hios_get32(h + 4), op = hios_get32(h + 8), slot = hios_get32(h + 12);
        size_t n = hios_get32(h + 16);
        if (hios_get32(h) != HIOS_EAGL_REQUEST || seq != expected_seq++ || !seq || n > HIOS_EAGL_MAX) break;
        if (n > capacity) {
            unsigned char *new_data = realloc(data, n);
            if (!new_data) break;
            data = new_data;
            capacity = n;
        }
        if (transfer(STDIN_FILENO, data, n, 0) != 1) break;
        if (op == EA_END_SESSION && !slot && !n) {
            result = send_reply(seq, op, EA_OK, NULL, 0) ? 0 : 1;
            break;
        }
        size_t len = 0;
        unsigned char *out = small;
        const int measuring = profile_readback && op == EA_READ_RGBA;
        uint64_t measured_start = measuring ? monotonic_us() : 0;
        uint32_t status = dispatch(&w, slot, op, data, n, small, &out, &len);
        uint64_t measured_ready = measured_start && status == EA_OK ? monotonic_us() : 0;
        if (status != EA_OK)
            fprintf(stderr, "EAGL worker: rejected seq=%u context=%u op=%u bytes=%zu status=%u\n",
                    seq, slot, op, n, status);
        int sent = send_reply(seq, op, status, out, len);
        if (op == EA_READ_RGBA) delta_commit(sent);
        if (measured_ready >= measured_start && measured_ready && sent) {
            uint64_t measured_sent = monotonic_us();
            if (measured_sent >= measured_ready)
                fprintf(stderr, "EAGL readback timing: seq=%u read=%u bytes=%zu host_dispatch_us=%" PRIu64
                        " socket_write_us=%" PRIu64 " gl_readback_us=%" PRIu64 " convert_us=%" PRIu64 "\n",
                        seq, w.completed_reads, len, measured_ready - measured_start,
                        measured_sent - measured_ready, pro_readback_us, pro_convert_us);
        }
        if (out != small) free(out);
        if (!sent || status == EA_DEVICE_ERROR) break;
    }
    free(data);
    for (unsigned i = 1; i <= HIOS_EAGL_CONTEXTS; ++i) close_context(&w, i);
    eglTerminate(w.display);
    return result;
}
