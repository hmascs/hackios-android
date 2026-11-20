/* SPDX-License-Identifier: GPL-2.0-or-later */
#include "eagl-client-arrays.h"
#include "eagl-pixel-transfer.h"
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <assert.h>
#include <stdio.h>
#include <string.h>

static GLuint compile(GLenum type, const char *source)
{
    GLuint shader = glCreateShader(type);
    GLint ok = GL_FALSE;
    assert(shader);
    glShaderSource(shader, 1, &source, NULL);
    glCompileShader(shader);
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[1024] = {0};
        glGetShaderInfoLog(shader, sizeof(log), NULL, log);
        fprintf(stderr, "shader compile failed: %s\n", log);
    }
    assert(ok);
    return shader;
}

static void check_original_vertex_id(void)
{
    glPixelStorei(GL_PACK_ALIGNMENT, 4);
    glPixelStorei(GL_PACK_ROW_LENGTH, 0);
    glPixelStorei(GL_PACK_SKIP_PIXELS, 0);
    glPixelStorei(GL_PACK_SKIP_ROWS, 0);
    float positions[10][2] = {{0}};
    positions[7][0]=-.8f; positions[7][1]=-.8f;
    positions[8][0]= .8f; positions[8][1]=-.8f;
    positions[9][0]= 0.f; positions[9][1]= .8f;
    struct hios_client_array array={0,2,GL_FLOAT,0,positions,sizeof positions,sizeof positions[0]};
    const uint16_t indices[]={7,8,9};
    unsigned char packet[256], pixel[4];
    size_t bytes=0;
    GLuint vs=compile(GL_VERTEX_SHADER,
        "#version 300 es\nlayout(location=0) in vec2 p; flat out int valid_id;\n"
        "void main(){int i=gl_VertexID-7; valid_id=(i>=0 && i<3)?1:0; gl_Position=vec4(p,0,1);}\n");
    GLuint fs=compile(GL_FRAGMENT_SHADER,
        "#version 300 es\nprecision highp float; flat in int valid_id; out vec4 c;\n"
        "void main(){c=valid_id==1?vec4(0,1,0,1):vec4(1,0,0,1);}\n");
    GLuint program=glCreateProgram();
    GLint linked=0;
    glAttachShader(program,vs); glAttachShader(program,fs); glLinkProgram(program);
    glGetProgramiv(program,GL_LINK_STATUS,&linked); assert(linked);
    glUseProgram(program);
    for (unsigned indexed=0; indexed<2; ++indexed) {
        int ok=indexed ? hios_array_encode(packet,sizeof packet,&bytes,GL_TRIANGLES,3,
                                           GL_UNSIGNED_SHORT,indices,sizeof indices,&array,1)
                       : hios_array_encode_sequential(packet,sizeof packet,&bytes,GL_TRIANGLES,7,3,&array,1);
        assert(ok);
        glClearColor(0,0,0,1); glClear(GL_COLOR_BUFFER_BIT);
        assert(hios_gles_draw_client_arrays(packet,bytes));
        glReadPixels(16,16,1,1,GL_RGBA,GL_UNSIGNED_BYTE,pixel);
        assert(!memcmp(pixel,(unsigned char[]){0,255,0,255},4));
        assert(glGetError()==GL_NO_ERROR);
    }
    glDeleteShader(vs); glDeleteShader(fs); glDeleteProgram(program);
}

int main(int argc, char **argv)
{
    /* Interleaved client arrays; nonzero first index, descriptor order not
     * coupled to attribute slots. The first unused vertex is never copied. */
    struct vertex { float pos[2]; unsigned char rgba[4]; } vertices[] = {
        {{99, 99}, {0, 0, 0, 0}}, {{-.8f, -.8f}, {17, 155, 230, 255}},
        {{.8f, -.8f}, {17, 155, 230, 255}}, {{0, .8f}, {17, 155, 230, 255}},
    };
    const unsigned char indices[] = {1, 0, 2, 0, 3, 0};
    struct hios_client_array arrays[] = {
        {1, 4, GL_UNSIGNED_BYTE, 1, vertices[0].rgba, sizeof(vertices) - offsetof(struct vertex, rgba), sizeof(struct vertex)},
        {0, 2, GL_FLOAT, 0, vertices[0].pos, sizeof(vertices), sizeof(struct vertex)},
    };
    unsigned char packet[256], mutated[256];
    size_t length = 0;
    struct hios_array_draw draw;
    assert(!hios_array_encode(packet, sizeof(packet), &length, GL_TRIANGLES, 3, GL_UNSIGNED_SHORT,
                             indices, sizeof(indices) - 1, arrays, 2));
    arrays[0].bytes--;
    assert(!hios_array_encode(packet, sizeof(packet), &length, GL_TRIANGLES, 3, GL_UNSIGNED_SHORT,
                             indices, sizeof(indices), arrays, 2));
    arrays[0].bytes++;
    assert(hios_array_encode(packet, sizeof(packet), &length, GL_TRIANGLES, 3, GL_UNSIGNED_SHORT,
                             indices, sizeof(indices), arrays, 2));
    assert(length == 24 + 40 + 6 + 48);
    assert(hios_array_decode(packet, length, &draw));
    assert(draw.vertex_count == 4 && draw.indices[0] == 1 && draw.indices[4] == 3);
    assert(!memcmp(draw.arrays[0].data, (unsigned char[]){0,0,0,0}, 4));
    assert(!memcmp((const unsigned char *)draw.arrays[0].data + 4, vertices[1].rgba, 4));
    size_t sequential = 0;
    assert(!hios_array_encode_sequential(mutated, sizeof(mutated), &sequential, GL_TRIANGLES, UINT32_MAX, 3, arrays, 2));
    assert(hios_array_encode_sequential(mutated, sizeof(mutated), &sequential, GL_TRIANGLES, 1, 3, arrays, 2));
    assert(sequential == length && !memcmp(packet, mutated, length));
    assert(hios_array_encode_sequential(mutated, sizeof(mutated), &sequential, GL_LINES, 1, 2, arrays, 2));
    assert(hios_array_decode(mutated, sequential, &draw) && draw.mode == GL_LINES && draw.count == 2);
    for (size_t i = 0; i < length; ++i) assert(!hios_array_decode(packet, i, &draw));
    assert(!hios_array_decode(packet, length + 1, &draw));
    memcpy(mutated, packet, length);
    mutated[44] = 1; /* duplicate attribute slot */
    assert(!hios_array_decode(mutated, length, &draw));
    memcpy(mutated, packet, length);
    mutated[64] = 4; /* index past the vertex data */
    assert(!hios_array_decode(mutated, length, &draw));

    const EGLint config_attrs[] = {EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, 0x40,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE};
    const EGLint ctx_attrs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    const EGLint surf_attrs[] = {EGL_WIDTH, 32, EGL_HEIGHT, 32, EGL_NONE};
    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    EGLConfig config;
    EGLint n = 0;
    assert(display != EGL_NO_DISPLAY && eglInitialize(display, NULL, NULL) && eglBindAPI(EGL_OPENGL_ES_API));
    assert(eglChooseConfig(display, config_attrs, &config, 1, &n) && n == 1);
    EGLContext ctx = eglCreateContext(display, config, EGL_NO_CONTEXT, ctx_attrs);
    EGLSurface surface = eglCreatePbufferSurface(display, config, surf_attrs);
    assert(ctx != EGL_NO_CONTEXT && surface != EGL_NO_SURFACE && eglMakeCurrent(display, surface, surface, ctx));
    const char *renderer = (const char *)glGetString(GL_RENDERER);
    assert(renderer && (argc == 1 || (argc == 2 && strstr(renderer, argv[1]))));
    GLuint vs = compile(GL_VERTEX_SHADER, "#version 300 es\nlayout(location=0) in vec2 pos; layout(location=1) in vec4 tint;\n"
                        "out vec4 rgb; void main() { gl_Position=vec4(pos,0.0,1.0); rgb=tint; }\n");
    GLuint fs = compile(GL_FRAGMENT_SHADER, "#version 300 es\nprecision highp float; in vec4 rgb; out vec4 frag;\n"
                        "uniform int textured; uniform sampler2D tex;\n"
                        "void main() { frag=textured>0 ? texture(tex,vec2(.25,.25)) : rgb; }\n");
    GLuint program = glCreateProgram();
    GLint linked = GL_FALSE;
    glAttachShader(program, vs);
    glAttachShader(program, fs);
    glLinkProgram(program);
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    assert(linked);
    glUseProgram(program);
    GLuint vao, vbo;
    glGenVertexArrays(1, &vao);
    glGenBuffers(1, &vbo);
    glBindVertexArray(vao);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glViewport(0, 0, 32, 32);
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT);
    assert(!hios_gles_draw_client_arrays(mutated, length));
    assert(hios_gles_draw_client_arrays(packet, length));
    GLsync fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    assert(fence);
    GLenum settled = glClientWaitSync(fence, GL_SYNC_FLUSH_COMMANDS_BIT, 1000000000);
    assert(settled == GL_ALREADY_SIGNALED || settled == GL_CONDITION_SATISFIED);
    glDeleteSync(fence);
    unsigned char sample[4];
    glReadPixels(16, 16, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, sample);
    assert(!memcmp(sample, vertices[1].rgba, 4));
    glReadPixels(0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, sample);
    assert(!memcmp(sample, (unsigned char[]){0, 0, 0, 255}, 4));
    GLint observed = 0;
    glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &observed);
    assert(observed == (GLint)vao);
    glGetIntegerv(GL_ARRAY_BUFFER_BINDING, &observed);
    assert(observed == (GLint)vbo);
    assert(glGetError() == GL_NO_ERROR);

    GLuint texture;
    glGenTextures(1, &texture);
    glBindTexture(GL_TEXTURE_2D, texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glUniform1i(glGetUniformLocation(program, "textured"), 1);
    glUniform1i(glGetUniformLocation(program, "tex"), 0);
    const unsigned char padded_bgra[] = {
        99, 33, 19, 200,   15, 82, 63, 255,    199, 199, 199,
        63, 25, 44, 244,   31, 71, 17, 250,
    };
    unsigned char packed[16];
    assert(!hios_pixel_pack(packed, sizeof(packed), padded_bgra, sizeof(padded_bgra) - 1, 11, 2, 2, HIOS_BGRA8));
    assert(hios_pixel_pack(packed, sizeof(packed), padded_bgra, sizeof(padded_bgra), 11, 2, 2, HIOS_BGRA8));
    glPixelStorei(GL_UNPACK_ROW_LENGTH, 30);
    glPixelStorei(GL_UNPACK_SKIP_ROWS, 3);
    assert(!hios_gles_upload_texture(packed, sizeof(packed) - 1, 2, 2, HIOS_BGRA8));
    assert(hios_gles_upload_texture(packed, sizeof(packed), 2, 2, HIOS_BGRA8));
    glGetIntegerv(GL_UNPACK_ROW_LENGTH, &observed);
    assert(observed == 30);
    glGetIntegerv(GL_UNPACK_SKIP_ROWS, &observed);
    assert(observed == 3);
    assert(hios_gles_draw_client_arrays(packet, length));
    assert(hios_gles_read_frame_rgba(sample, sizeof(sample), 1, 1));
    assert(!memcmp(sample, (unsigned char[]){0, 0, 0, 255}, 4));
    unsigned char frame[32 * 32 * 4], surface_data[32 * (32 * 4 + 8)];
    glPixelStorei(GL_PACK_ALIGNMENT, 8);
    glPixelStorei(GL_PACK_SKIP_PIXELS, 3);
    assert(hios_gles_read_frame_rgba(frame, sizeof(frame), 32, 32));
    glGetIntegerv(GL_PACK_SKIP_PIXELS, &observed);
    assert(observed == 3);
    memset(surface_data, 0xbb, sizeof(surface_data));
    assert(hios_pixel_unpack_bgra(surface_data, sizeof(surface_data), 136, frame, sizeof(frame), 32, 32, 1));
    assert(!memcmp(&surface_data[15 * 136 + 16 * 4], padded_bgra, 4));
    assert(surface_data[15 * 136 + 128] == 0xbb);

    unsigned char alpha[] = {203, 19, 7, 9};
    assert(hios_gles_upload_texture(alpha, sizeof(alpha), 2, 2, HIOS_ALPHA8));
    assert(hios_gles_draw_client_arrays(packet, length));
    assert(hios_gles_read_frame_rgba(frame, sizeof(frame), 32, 32));
    assert(!memcmp(&frame[(16 * 32 + 16) * 4], (unsigned char[]){0, 0, 0, 203}, 4));
    const unsigned char half[] = {0x00, 0x3c, 0, 0, 0, 0, 0x00, 0x3c}; /* (1,0,0,1) */
    assert(hios_gles_upload_texture(half, sizeof(half), 1, 1, HIOS_RGBA16F));
    assert(hios_gles_draw_client_arrays(packet, length));
    assert(hios_gles_read_frame_rgba(frame, sizeof(frame), 32, 32));
    assert(!memcmp(&frame[(16 * 32 + 16) * 4], (unsigned char[]){255, 0, 0, 255}, 4));
    assert(glGetError() == GL_NO_ERROR);
    check_original_vertex_id();
    printf("EAGL array/pixel worker: %s; original gl_VertexID, GPU draw, BGRA/Alpha8/RGBA16F and readback validated\n", renderer);
    eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroyContext(display, ctx);
    eglDestroySurface(display, surface);
    eglTerminate(display);
    return 0;
}
