/* SPDX-License-Identifier: GPL-2.0-or-later */
#include "eagl-guest-client.h"
extern _Noreturn void abort(void);
int hios_eagl_pixel_store_value(unsigned name, int *value);

const unsigned char *glGetString(unsigned name)
{
    static _Thread_local unsigned char values[5][2048];
    unsigned index;
    switch (name) {
    case 0x1f00: index = 0; break; /* GL_VENDOR */
    case 0x1f01: index = 1; break;
    case 0x1f02: index = 2; break;
    case 0x1f03: index = 3; break;
    case 0x8b8c: index = 4; break;
    default: return NULL;
    }
    unsigned char req[4];
    size_t n;
    hios_put32(req, name);
    if (!hios_eagl_current_call(EA_STRING, req, sizeof req, values[index], sizeof values[index], &n) ||
        !n || values[index][n - 1]) abort();
    return values[index];
}

void glGetIntegerv(unsigned name, int *out)
{
    unsigned char req[4], reply[16];
    size_t n;
    unsigned words = name == 0x0ba2 || name == 0x0c10 ? 4 : name == 0x0d3a ? 2 : 1;
    if (!out) abort();
    if (hios_eagl_pixel_store_value(name, out)) return;
    hios_put32(req, name);
    if (!hios_eagl_current_call(EA_INTEGER, req, sizeof req, reply, sizeof reply, &n) || n != words * 4) abort();
    for (unsigned i = 0; i < words; ++i) out[i] = (int32_t)hios_get32(reply + 4 * i);
}

void glGetFloatv(unsigned name, float *out)
{
    unsigned char request[4], reply[16];
    size_t n;
    if (!out || name != 0x0c22) abort(); /* GL_COLOR_CLEAR_VALUE */
    hios_put32(request, name);
    if (!hios_eagl_current_call(EA_FLOAT_STATE, request, sizeof request, reply, sizeof reply, &n) || n != sizeof reply)
        abort();
    for (unsigned i = 0; i < 4; ++i) {
        uint32_t bits = hios_get32(reply + i * 4);
        _Static_assert(sizeof(bits) == sizeof(*out), "IEEE binary32 float required");
        __builtin_memcpy(out + i, &bits, sizeof bits);
    }
}

void glGetFramebufferAttachmentParameteriv(unsigned target, unsigned attachment, unsigned name, int *out)
{
    unsigned char request[12], reply[4];
    size_t n;
    if (!out) abort();
    hios_put32(request, target);
    hios_put32(request + 4, attachment);
    hios_put32(request + 8, name);
    if (!hios_eagl_current_call(EA_FRAMEBUFFER_ATTACHMENT, request, sizeof request, reply, sizeof reply, &n) ||
        n != sizeof reply) abort();
    *out = (int32_t)hios_get32(reply);
}

static void texture_query(unsigned op, unsigned target, int level, unsigned name, int *out)
{
    unsigned char request[12], reply[4];
    size_t n;
    if (!out) abort();
    hios_put32(request, target);
    hios_put32(request + 4, name);
    hios_put32(request + 8, (uint32_t)level);
    if (!hios_eagl_current_call(op, request, op == EA_TEXTURE_LEVEL_QUERY ? 12 : 8,
                                reply, sizeof reply, &n) || n != sizeof reply) abort();
    *out = (int32_t)hios_get32(reply);
}

void glGetTexLevelParameteriv(unsigned target, int level, unsigned name, int *out)
{ texture_query(EA_TEXTURE_LEVEL_QUERY, target, level, name, out); }

void glGetTexParameteriv(unsigned target, unsigned name, int *out)
{ texture_query(EA_TEXTURE_QUERY, target, 0, name, out); }

/* Debug-only metadata does not affect rendering or guest-visible pixels. */
void glLabelObjectEXT(unsigned type, unsigned object, int length, const char *label)
{ (void)type; (void)object; (void)length; (void)label; }
