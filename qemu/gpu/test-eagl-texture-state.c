/* SPDX-License-Identifier: GPL-2.0-or-later */
/* CPU GL-state mock. No GPU: bounded metadata/lifetime/error invariants only. */
#include "eagl-pixel-transfer.h"
#include "eagl-host-texture-state.h"
#include <GLES3/gl3.h>
#include <assert.h>
#include <stdio.h>
#include <string.h>

static GLint binding = 1, error_code;
static GLint physical[4] = {GL_RED, GL_GREEN, GL_BLUE, GL_ALPHA};
static unsigned image_calls, swizzle_calls;
static int component(GLint value)
{ return value == GL_RED || value == GL_GREEN || value == GL_BLUE || value == GL_ALPHA || value == GL_ZERO || value == GL_ONE; }
GL_APICALL void GL_APIENTRY glGetIntegerv(GLenum name, GLint *value)
{
    if (name == GL_TEXTURE_BINDING_2D) *value = binding;
    else if (name == GL_MAX_TEXTURE_SIZE) *value = 4096;
    else if (name == GL_UNPACK_ALIGNMENT || name == GL_PACK_ALIGNMENT) *value = 4;
    else *value = 0;
}
GL_APICALL void GL_APIENTRY glTexParameteri(GLenum target, GLenum name, GLint value)
{
    if (target != GL_TEXTURE_2D || !component(value)) { error_code = GL_INVALID_ENUM; return; }
    if (name >= GL_TEXTURE_SWIZZLE_R && name <= GL_TEXTURE_SWIZZLE_A) {
        physical[name - GL_TEXTURE_SWIZZLE_R] = value;
        ++swizzle_calls;
    }
}
GL_APICALL void GL_APIENTRY glPixelStorei(GLenum name, GLint value) { (void)name; (void)value; }
GL_APICALL void GL_APIENTRY glTexImage2D(GLenum target, GLint level, GLint internal,
    GLsizei width, GLsizei height, GLint border, GLenum format, GLenum type, const void *pixels)
{
    (void)target; (void)level; (void)internal; (void)width; (void)height;
    (void)border; (void)format; (void)type; (void)pixels;
    ++image_calls;
}
GL_APICALL GLenum GL_APIENTRY glCheckFramebufferStatus(GLenum target) { (void)target; return GL_FRAMEBUFFER_COMPLETE; }
GL_APICALL void GL_APIENTRY glReadPixels(GLint x, GLint y, GLsizei w, GLsizei h, GLenum f, GLenum t, void *p)
{ (void)x; (void)y; (void)w; (void)h; (void)f; (void)t; (void)p; }
static GLint query(GLenum name)
{
    GLint value = -1;
    assert(hios_texture_parameter_query(GL_TEXTURE_2D, name, &value));
    return value;
}
#ifndef HIOS_TEXTURE_STATE_LIMIT
#define HIOS_TEXTURE_STATE_LIMIT 4096u
#endif
int main(int argc, char **argv)
{
    if (argc == 2 && !strcmp(argv[1], "--capacity-only")) {
        hios_texture_select_context(7);
        for (unsigned i = 1; i <= HIOS_TEXTURE_STATE_LIMIT; ++i) {
            binding = (GLint)i;
            assert(hios_texture_parameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_R, GL_BLUE));
        }
        binding = (GLint)HIOS_TEXTURE_STATE_LIMIT + 1;
        unsigned before = swizzle_calls;
        assert(!hios_texture_parameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_R, GL_RED));
        assert(swizzle_calls == before);
        binding = 1;
        assert(query(GL_TEXTURE_SWIZZLE_R) == GL_BLUE);
        hios_texture_drop_context(7);
        binding = (GLint)HIOS_TEXTURE_STATE_LIMIT + 1;
        assert(query(GL_TEXTURE_SWIZZLE_R) == GL_RED);
        puts("Texture state CPU: actual configured metadata capacity fail-closed and release passed");
        return 0;
    }
    /* Build with HIOS_TEXTURE_STATE_LIMIT=2 to exercise hard capacity failure. */
    hios_texture_select_context(1);
    assert(hios_texture_parameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_R, GL_BLUE));
    assert(query(GL_TEXTURE_SWIZZLE_R) == GL_BLUE);
    GLint unchanged = 1234;
    assert(!hios_texture_parameter_query(GL_TEXTURE_2D, 0xdead, &unchanged));
    assert(unchanged == 1234 && query(GL_TEXTURE_SWIZZLE_R) == GL_BLUE);
    const unsigned char rgba[4] = {17, 63, 211, 97};
    assert(hios_gles_upload_texture(rgba, 4, 1, 1, HIOS_RGBA8));
    assert(physical[0] == GL_BLUE);
    unsigned before = swizzle_calls;
    assert(hios_texture_parameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_R, 0xdead));
    assert(error_code == GL_INVALID_ENUM && swizzle_calls == before);
    assert(query(GL_TEXTURE_SWIZZLE_R) == GL_BLUE);
    /* An unrelated pending app error remains pending across metadata work. */
    error_code = GL_INVALID_VALUE;
    assert(hios_texture_parameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_A, GL_ONE));
    assert(error_code == GL_INVALID_VALUE);
    const unsigned char la[2] = {33, 66};
    assert(hios_gles_upload_texture(la, 2, 1, 1, HIOS_LA8));
    assert(physical[0] == GL_RED && physical[3] == GL_ONE);
    assert(hios_gles_upload_texture_level(la, 2, 1, 1, HIOS_LA8, 1));
    before = image_calls;
    assert(!hios_gles_upload_texture_level(rgba, 4, 1, 1, HIOS_RGBA8, 1));
    assert(image_calls == before); /* incompatible legacy mip rejected before GL */
    /* Null base allocation remains legal and preserves logical selectors. */
    assert(hios_gles_upload_texture(NULL, 0, 1, 1, HIOS_RGBA8));
    assert(query(GL_TEXTURE_SWIZZLE_R) == GL_BLUE && physical[0] == GL_BLUE);
    binding = 0;
    before = swizzle_calls;
    assert(!hios_texture_parameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_R, GL_RED));
    assert(!hios_texture_parameter_query(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_R, &error_code));
    assert(!hios_gles_upload_texture(rgba, 4, 1, 1, HIOS_RGBA8));
    assert(swizzle_calls == before);
    /* Same numeric name in another unshared context is independent. */
    binding = 1;
    hios_texture_select_context(2);
    assert(query(GL_TEXTURE_SWIZZLE_R) == GL_RED);
    assert(hios_texture_parameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_R, GL_GREEN));
    hios_texture_select_context(1);
    assert(query(GL_TEXTURE_SWIZZLE_R) == GL_BLUE);
    binding = 2;
    before = swizzle_calls;
    assert(!hios_texture_parameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_R, GL_ALPHA));
    assert(!hios_gles_upload_texture(rgba, 4, 1, 1, HIOS_RGBA8));
    assert(swizzle_calls == before); /* cap failure never guesses/evicts live state */
    const unsigned id = 1;
    hios_texture_delete(1, &id);
    binding = 1;
    assert(query(GL_TEXTURE_SWIZZLE_R) == GL_RED); /* deleted name starts fresh */
    hios_texture_drop_context(2);
    hios_texture_select_context(2);
    assert(query(GL_TEXTURE_SWIZZLE_R) == GL_RED); /* reopened namespace starts fresh */
    puts("Texture state CPU: composition, errors, null image, capacity and namespaces passed");
    return 0;
}
