/* SPDX-License-Identifier: GPL-2.0-or-later */
#include "eagl-pixel-transfer.h"
#include <GLES3/gl3.h>
#include "eagl-host-texture-state.h"
#include <string.h>

/* Explicit logical state; never infer user intent from the composed GL values.
 * Bounded static storage avoids allocator failure. Exhaustion is fail-closed. */
#ifndef HIOS_TEXTURE_STATE_LIMIT
#define HIOS_TEXTURE_STATE_LIMIT 4096u
#endif
struct texture_state {
    uint32_t context;
    GLuint texture;
    GLint user[4];
    enum hios_pixel_format format;
    int live, have_base;
};
static struct texture_state texture_states[HIOS_TEXTURE_STATE_LIMIT];
static uint32_t texture_context;
static const GLenum swizzle_names[4] = {GL_TEXTURE_SWIZZLE_R, GL_TEXTURE_SWIZZLE_G,
                                        GL_TEXTURE_SWIZZLE_B, GL_TEXTURE_SWIZZLE_A};
static const GLint identity[4] = {GL_RED, GL_GREEN, GL_BLUE, GL_ALPHA};

void hios_texture_select_context(uint32_t slot) { texture_context = slot; }
void hios_texture_drop_context(uint32_t slot)
{
    for (size_t i = 0; i < HIOS_TEXTURE_STATE_LIMIT; ++i)
        if (texture_states[i].live && texture_states[i].context == slot)
            memset(&texture_states[i], 0, sizeof texture_states[i]);
}
void hios_texture_delete(unsigned count, const unsigned *names)
{
    for (size_t i = 0; i < HIOS_TEXTURE_STATE_LIMIT; ++i) {
        struct texture_state *s = &texture_states[i];
        if (!s->live || s->context != texture_context) continue;
        for (unsigned j = 0; j < count; ++j)
            if (s->texture == names[j]) { memset(s, 0, sizeof *s); break; }
    }
}
static struct texture_state *bound_state(void)
{
    GLint binding = 0;
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &binding);
    if (!binding) return NULL; /* this bridge does not support texture-zero upload/state */
    struct texture_state *unused = NULL;
    for (size_t i = 0; i < HIOS_TEXTURE_STATE_LIMIT; ++i) {
        struct texture_state *s = &texture_states[i];
        if (s->live && s->context == texture_context && s->texture == (GLuint)binding) return s;
        if (!s->live && !unused) unused = s;
    }
    if (!unused) return NULL;
    *unused = (struct texture_state){.context = texture_context, .texture = (GLuint)binding,
                                    .format = HIOS_RGBA8, .live = 1};
    memcpy(unused->user, identity, sizeof identity);
    return unused;
}
int hios_texture_is_swizzle(unsigned name)
{
    return name == GL_TEXTURE_SWIZZLE_R || name == GL_TEXTURE_SWIZZLE_G ||
           name == GL_TEXTURE_SWIZZLE_B || name == GL_TEXTURE_SWIZZLE_A;
}
static int swizzle_index(GLenum name)
{
    for (int i = 0; i < 4; ++i) if (swizzle_names[i] == name) return i;
    return -1;
}
static int component_index(GLint value)
{
    for (int i = 0; i < 4; ++i) if (identity[i] == value) return i;
    return -1;
}
static void apply_swizzle(const struct texture_state *s)
{
    GLint mapping[4] = {GL_RED, GL_GREEN, GL_BLUE, GL_ALPHA};
    if (s->format == HIOS_ALPHA8) {
        mapping[0] = mapping[1] = mapping[2] = GL_ZERO;
        mapping[3] = GL_RED;
    } else if (s->format == HIOS_LA8) {
        mapping[1] = mapping[2] = GL_RED;
        mapping[3] = GL_GREEN;
    }
    for (int i = 0; i < 4; ++i) {
        int component = component_index(s->user[i]);
        glTexParameteri(GL_TEXTURE_2D, swizzle_names[i], component < 0 ? s->user[i] : mapping[component]);
    }
}
int hios_texture_parameteri(unsigned target, unsigned name, int value)
{
    if (!hios_texture_is_swizzle(name) || target != GL_TEXTURE_2D) {
        glTexParameteri(target, name, value);
        return 1;
    }
    /* Preserve native GL_INVALID_ENUM behavior, but do not commit invalid user
     * state or consume unrelated pending GL errors with glGetError(). */
    if (component_index(value) < 0 && value != GL_ZERO && value != GL_ONE) {
        glTexParameteri(target, name, value);
        return 1;
    }
    struct texture_state *s = bound_state();
    if (!s) return 0;
    s->user[swizzle_index(name)] = value;
    apply_swizzle(s);
    return 1;
}
int hios_texture_parameter_query(unsigned target, unsigned name, int *value)
{
    if (target != GL_TEXTURE_2D || !hios_texture_is_swizzle(name) || !value) return 0;
    struct texture_state *s = bound_state();
    if (!s) return 0;
    *value = s->user[swizzle_index(name)];
    return 1;
}


struct pixel_store {
    GLenum name;
    GLint saved, temporary;
};

static void pixel_store_begin(struct pixel_store *state, size_t n)
{
    for (size_t i = 0; i < n; ++i) {
        glGetIntegerv(state[i].name, &state[i].saved);
        if (state[i].saved != state[i].temporary) glPixelStorei(state[i].name, state[i].temporary);
    }
}

static void pixel_store_end(const struct pixel_store *state, size_t n)
{
    for (size_t i = 0; i < n; ++i)
        if (state[i].saved != state[i].temporary) glPixelStorei(state[i].name, state[i].saved);
}

int hios_gles_upload_texture_level(const void *packed, size_t bytes, uint32_t w, uint32_t h,
                                   enum hios_pixel_format format, unsigned level)
{
    size_t expected = hios_pixel_packed_size(w, h, format);
    if (!expected || level > HIOS_TEXTURE_MAX_LEVEL || (packed ? bytes != expected : bytes != 0)) return 0;
    GLint pbo = 0, binding = 0, maximum = 0;
    glGetIntegerv(GL_PIXEL_UNPACK_BUFFER_BINDING, &pbo);
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &binding);
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maximum);
    if (pbo || !binding || maximum <= 0) return 0;
    uint32_t limit = (uint32_t)maximum >> level;
    if (w > limit || h > limit) return 0;
    /* The logical base format owns legacy channel mapping. User swizzles may
     * make the physical alpha selector ONE/ZERO, so querying that selector
     * cannot establish mip compatibility. */
    struct texture_state *texture = bound_state();
    if (!texture) return 0;
    if (level && ((format == HIOS_ALPHA8 || format == HIOS_LA8) ||
                  texture->format == HIOS_ALPHA8 || texture->format == HIOS_LA8) &&
        (!texture->have_base || texture->format != format)) return 0;

    const int alpha = format == HIOS_ALPHA8;
    const int la = format == HIOS_LA8;
    GLint storage = alpha ? GL_R8 : la ? GL_RG8 : format == HIOS_RGBA16F ? GL_RGBA16F : GL_RGBA8;
    GLenum data_format = alpha ? GL_RED : la ? GL_RG : GL_RGBA;
    GLenum type = format == HIOS_RGBA16F ? GL_HALF_FLOAT : GL_UNSIGNED_BYTE;
    struct pixel_store state[] = {{GL_UNPACK_ALIGNMENT, 0, 1}, {GL_UNPACK_ROW_LENGTH, 0, 0},
        {GL_UNPACK_SKIP_ROWS, 0, 0}, {GL_UNPACK_SKIP_PIXELS, 0, 0}};
    size_t n = sizeof(state) / sizeof(state[0]);
    pixel_store_begin(state, n);
    glTexImage2D(GL_TEXTURE_2D, (GLint)level, storage, (GLsizei)w, (GLsizei)h, 0, data_format, type, packed);
    pixel_store_end(state, n);
    if (level) return 1;
    /* Keep explicit user state across format/base reallocations. Composition
     * restores identity storage on legacy->RGBA without deleting user intent. */
    texture->format = format;
    texture->have_base = 1;
    apply_swizzle(texture);
    return 1; /* normal GL error handling belongs to the caller, in queue order */
}

int hios_gles_upload_texture(const void *packed, size_t bytes, uint32_t w, uint32_t h, enum hios_pixel_format format)
{
    return hios_gles_upload_texture_level(packed, bytes, w, h, format, 0);
}

int hios_gles_read_frame_rgba(void *out, size_t capacity, uint32_t w, uint32_t h)
{
    size_t expected = hios_pixel_packed_size(w, h, HIOS_RGBA8);
    if (!out || !expected || capacity < expected) return 0;
    GLint pbo = 0;
    glGetIntegerv(GL_PIXEL_PACK_BUFFER_BINDING, &pbo);
    if (pbo || glCheckFramebufferStatus(GL_READ_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) return 0;
    struct pixel_store state[] = {{GL_PACK_ALIGNMENT, 0, 1}, {GL_PACK_ROW_LENGTH, 0, 0},
        {GL_PACK_SKIP_ROWS, 0, 0}, {GL_PACK_SKIP_PIXELS, 0, 0}};
    size_t n = sizeof(state) / sizeof(state[0]);
    pixel_store_begin(state, n);
    glReadPixels(0, 0, (GLsizei)w, (GLsizei)h, GL_RGBA, GL_UNSIGNED_BYTE, out);
    pixel_store_end(state, n);
    return 1;
}
