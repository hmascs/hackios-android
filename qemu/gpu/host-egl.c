/* SPDX-License-Identifier: GPL-2.0-or-later */
#include "host-renderer.h"
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>

struct Renderer {
    EGLDisplay display;
    EGLSurface surface;
    EGLContext context;
    GLuint program, buffer, vao;
    GLint color;
    unsigned width, height;
    int readable;
    char name[160];
};

static void close_renderer(void *opaque)
{
    struct Renderer *r = opaque;
    if (!r) return;
    if (r->display != EGL_NO_DISPLAY) {
        if (eglGetCurrentContext() == r->context && r->context != EGL_NO_CONTEXT) {
            glDeleteProgram(r->program);
            glDeleteBuffers(1, &r->buffer);
            glDeleteVertexArrays(1, &r->vao);
            eglMakeCurrent(r->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        }
        if (r->surface != EGL_NO_SURFACE) eglDestroySurface(r->display, r->surface);
        if (r->context != EGL_NO_CONTEXT) eglDestroyContext(r->display, r->context);
        eglTerminate(r->display);
    }
    free(r);
}

static int set_current(struct Renderer *r)
{
    return r && (eglGetCurrentContext() == r->context ||
        eglMakeCurrent(r->display, r->surface, r->surface, r->context));
}

static GLuint shader(GLenum type, const char *source)
{
    GLuint id = glCreateShader(type);
    GLint ok = 0;
    if (id) {
        glShaderSource(id, 1, &source, NULL);
        glCompileShader(id);
        glGetShaderiv(id, GL_COMPILE_STATUS, &ok);
        if (!ok) glDeleteShader(id);
    }
    return ok ? id : 0;
}

static int init_pipeline(struct Renderer *r)
{
    GLuint vs = shader(GL_VERTEX_SHADER, "#version 300 es\nlayout(location=0) in vec2 xy;\n"
                       "void main(){ gl_Position=vec4(xy,0.0,1.0); }");
    GLuint fs = shader(GL_FRAGMENT_SHADER, "#version 300 es\nprecision highp float;\n"
                       "uniform vec4 color; out vec4 pixel;\nvoid main(){ pixel=color; }");
    GLint linked = 0;
    if (vs && fs && (r->program = glCreateProgram())) {
        glAttachShader(r->program, vs);
        glAttachShader(r->program, fs);
        glLinkProgram(r->program);
        glGetProgramiv(r->program, GL_LINK_STATUS, &linked);
    }
    if (vs) glDeleteShader(vs);
    if (fs) glDeleteShader(fs);
    if (!linked) return 0;
    r->color = glGetUniformLocation(r->program, "color");
    glGenVertexArrays(1, &r->vao);
    glGenBuffers(1, &r->buffer);
    glBindVertexArray(r->vao);
    glBindBuffer(GL_ARRAY_BUFFER, r->buffer);
    glBufferData(GL_ARRAY_BUFFER, 6 * sizeof(float), NULL, GL_STREAM_DRAW);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, NULL);
    glEnableVertexAttribArray(0);
    glViewport(0, 0, (GLsizei)r->width, (GLsizei)r->height);
    return r->color >= 0 && r->vao && r->buffer && glGetError() == GL_NO_ERROR;
}

/* One live instance per process is supported initially: eglTerminate is display-wide. */
static struct Renderer *live;

static void *open_renderer(unsigned w, unsigned h, char *error, size_t cap)
{
    const char *stage = "invalid size (expected up to 4096x4096 and 4 Mi pixels)";
    struct Renderer *r = NULL;
    EGLint count = 0;
    EGLConfig config;
    const EGLint config_attrs[] = {EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, 0x40,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE};
    const EGLint context_attrs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    if (!w || !h || w > 4096 || h > 4096 || w * h > 4194304) goto fail;
    stage = "renderer already open";
    if (live) goto fail;
    stage = "allocate renderer";
    if (!(r = calloc(1, sizeof(*r)))) goto fail;
    r->width = w;
    r->height = h;
    r->context = EGL_NO_CONTEXT;
    r->surface = EGL_NO_SURFACE;
    stage = "initialize display";
    r->display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (r->display == EGL_NO_DISPLAY || !eglInitialize(r->display, NULL, NULL)) goto fail;
    stage = "choose GLES3 pbuffer config";
    if (!eglBindAPI(EGL_OPENGL_ES_API) || !eglChooseConfig(r->display, config_attrs, &config, 1, &count) || !count) goto fail;
    stage = "create GLES3 context";
    r->context = eglCreateContext(r->display, config, EGL_NO_CONTEXT, context_attrs);
    if (r->context == EGL_NO_CONTEXT) goto fail;
    stage = "create pbuffer";
    const EGLint pbuffer_attrs[] = {EGL_WIDTH, (EGLint)w, EGL_HEIGHT, (EGLint)h, EGL_NONE};
    r->surface = eglCreatePbufferSurface(r->display, config, pbuffer_attrs);
    if (r->surface == EGL_NO_SURFACE || !set_current(r)) goto fail;
    stage = "initialize GLES pipeline";
    const GLubyte *name = glGetString(GL_RENDERER);
    if (!name || !init_pipeline(r)) goto fail;
    snprintf(r->name, sizeof r->name, "%s", (const char *)name);
    live = r;
    return r;
fail:
    if (error && cap) snprintf(error, cap, "%s (EGL 0x%x)", stage, eglGetError());
    close_renderer(r);
    return NULL;
}

static int triangle(void *opaque, const float xy[6], const float color[4])
{
    struct Renderer *r = opaque;
    if (!r || !xy || !color) return 0;
    for (int i = 0; i < 6; ++i) if (!isfinite(xy[i])) return 0;
    for (int i = 0; i < 4; ++i) if (!isfinite(color[i])) return 0;
    r->readable = 0;
    if (!set_current(r)) return 0;
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT);
    glUseProgram(r->program);
    glUniform4fv(r->color, 1, color);
    glBindVertexArray(r->vao);
    glBindBuffer(GL_ARRAY_BUFFER, r->buffer);
    glBufferSubData(GL_ARRAY_BUFFER, 0, 6 * sizeof(float), xy);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    return r->readable = glGetError() == GL_NO_ERROR;
}

static int read_rgba(void *opaque, void *output, size_t bytes)
{
    struct Renderer *r = opaque;
    if (!r || !r->readable || !output || bytes < (size_t)r->width * r->height * 4 || !set_current(r)) return 0;
    glReadPixels(0, 0, (GLsizei)r->width, (GLsizei)r->height, GL_RGBA, GL_UNSIGNED_BYTE, output);
    return glGetError() == GL_NO_ERROR;
}

static const char *renderer_name(void *opaque)
{
    return opaque ? ((struct Renderer *)opaque)->name : NULL;
}

static void renderer_close(void *opaque)
{
    if (opaque && opaque == live) {
        live = NULL;
        close_renderer(opaque);
    }
}

const struct hios_renderer_v1 *hios_renderer_get(uint32_t abi)
{
    static const struct hios_renderer_v1 ops = { HIOS_RENDERER_ABI, sizeof ops, "egl-gles3-pbuffer",
        open_renderer, renderer_name, triangle, read_rgba, renderer_close };
    return abi == HIOS_RENDERER_ABI ? &ops : NULL;
}
