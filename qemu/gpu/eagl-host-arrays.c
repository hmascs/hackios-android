/* SPDX-License-Identifier: GPL-2.0-or-later */
#include "eagl-client-arrays.h"
#include <GLES3/gl3.h>

/* Replay on a worker thread with its EGL context current. The caller owns
 * program, uniforms, framebuffer and textures. Do not use on draws whose
 * shaders use gl_VertexID (indices were rebased) or use nonzero divisors. */
int hios_gles_draw_client_arrays(const void *packet, size_t bytes)
{
    struct hios_array_draw draw;
    if (!hios_array_decode(packet, bytes, &draw)) return 0;
    GLint old_vao = 0, old_vbo = 0, max_attrs = 0;
    glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &old_vao);
    glGetIntegerv(GL_ARRAY_BUFFER_BINDING, &old_vbo);
    glGetIntegerv(GL_MAX_VERTEX_ATTRIBS, &max_attrs);
    if (max_attrs <= 0 || glIsEnabled(GL_PRIMITIVE_RESTART_FIXED_INDEX)) return 0;
    for (uint32_t i = 0; i < draw.nr_arrays; ++i)
        if (draw.arrays[i].slot >= (uint32_t)max_attrs) return 0;

    GLuint vao = 0, buffers[HIOS_ARRAY_SLOTS + 1] = {0};
    const GLsizei count = (GLsizei)draw.nr_arrays + 1;
    glGenVertexArrays(1, &vao);
    glGenBuffers(count, buffers);
    int ok = vao != 0;
    for (GLsizei i = 0; i < count; ++i) ok &= buffers[i] != 0;
    if (ok) {
        glBindVertexArray(vao);
        for (uint32_t i = 0; i < draw.nr_arrays && ok; ++i) {
            const struct hios_client_array *a = &draw.arrays[i];
            glBindBuffer(GL_ARRAY_BUFFER, buffers[i]);
            glBufferData(GL_ARRAY_BUFFER, (GLsizeiptr)a->bytes, a->data, GL_STREAM_DRAW);
            GLint allocated = 0;
            glGetBufferParameteriv(GL_ARRAY_BUFFER, GL_BUFFER_SIZE, &allocated);
            ok = allocated == (GLint)a->bytes;
            if (!ok) break;
            glVertexAttribPointer(a->slot, (GLint)a->components, a->type, (GLboolean)a->normalized, 0, NULL);
            glEnableVertexAttribArray(a->slot);
        }
        if (ok) {
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, buffers[count - 1]);
            GLint allocated = 0;
            GLint elem_size = draw.index_type == GL_UNSIGNED_BYTE ? 1 : draw.index_type == GL_UNSIGNED_SHORT ? 2 : 4;
            GLint bytes_needed = elem_size * (GLint)draw.count;
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, bytes_needed, draw.indices, GL_STREAM_DRAW);
            glGetBufferParameteriv(GL_ELEMENT_ARRAY_BUFFER, GL_BUFFER_SIZE, &allocated);
            ok = allocated == bytes_needed;
            if (ok) glDrawElements(draw.mode, (GLsizei)draw.count, draw.index_type, NULL);
        }
        glBindVertexArray((GLuint)old_vao);
        glBindBuffer(GL_ARRAY_BUFFER, (GLuint)old_vbo);
    }
    glDeleteVertexArrays(1, &vao);
    glDeleteBuffers(count, buffers);
    /* GL errors are deliberately not consumed: surface them through the
     * bridge's normal GL error handling, preserving ordering. */
    return ok;
}
