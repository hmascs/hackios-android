/* SPDX-License-Identifier: GPL-2.0-or-later */
#include "eagl-guest-client.h"

extern _Noreturn void abort(void);

static void submit_uniform(int location, int count, unsigned arity, const float *values)
{
    if (location == -1 || count == 0) return;
    if (count < 0 || count > 64 || !values) abort();
    unsigned char request[12 + 64 * 16 * 4];
    size_t received, n = (size_t)count * arity;
    hios_put32(request, (uint32_t)location);
    hios_put32(request + 4, (uint32_t)count);
    hios_put32(request + 8, arity);
    for (size_t i = 0; i < n; ++i) {
        uint32_t bits;
        _Static_assert(sizeof(bits) == sizeof(*values), "IEEE binary32 GL floats required");
        __builtin_memcpy(&bits, values + i, sizeof bits);
        hios_put32(request + 12 + i * 4, bits);
    }
    if (!hios_eagl_current_call(EA_UNIFORM_VECTOR, request, 12 + n * 4, NULL, 0, &received) || received) abort();
}

void glUniform1fv(int location, int count, const float *value)
{
    submit_uniform(location, count, 1, value);
}

void glUniform3fv(int location, int count, const float *value)
{
    submit_uniform(location, count, 3, value);
}

void glUniform2fv(int location, int count, const float *value)
{
    submit_uniform(location, count, 2, value);
}

void glUniform4fv(int location, int count, const float *value)
{
    submit_uniform(location, count, 4, value);
}

void glUniformMatrix4fv(int location, int count, unsigned char transpose, const float *value)
{
    if (transpose) abort(); /* GLES accepts only GL_FALSE */
    submit_uniform(location, count, 16, value);
}
