/* SPDX-License-Identifier: GPL-2.0-or-later */
#include "eagl-guest-client.h"

extern void *malloc(size_t);
extern void free(void *);
extern void *memcpy(void *, const void *, size_t);
#define SOURCE_MAX (256u * 1024u)

static size_t bounded_length(const char *p, size_t limit)
{
    size_t n = 0;
    while (n < limit && p[n]) ++n;
    return n;
}

void glShaderSource(unsigned shader, int count, const char *const *strings, const int *lengths)
{
    if (count < 1 || count > 1024 || !strings) return;
    unsigned char *request = malloc(SOURCE_MAX + 4);
    if (!request) return;
    hios_put32(request, shader);
    size_t used = 4;
    for (int i = 0; i < count; ++i) {
        if (!strings[i]) goto done;
        size_t left = SOURCE_MAX + 3 - used;
        size_t n = lengths && lengths[i] >= 0 ? (size_t)lengths[i] : bounded_length(strings[i], left + 1);
        if (n > left) goto done;
        memcpy(request + used, strings[i], n);
        used += n;
    }
    request[used++] = 0;
    size_t received;
    hios_eagl_current_call(EA_SHADER_SOURCE, request, used, NULL, 0, &received);
done:
    free(request);
}

static void get_iv(uint32_t op, unsigned object, unsigned name, int *result)
{
    if (!result) return;
    unsigned char request[8], reply[4];
    hios_put32(request, object);
    hios_put32(request + 4, name);
    size_t received;
    if (hios_eagl_current_call(op, request, sizeof request, reply, sizeof reply, &received) && received == sizeof reply)
        *result = (int32_t)hios_get32(reply);
}

void glGetShaderiv(unsigned shader, unsigned name, int *result) { get_iv(EA_SHADER_IV, shader, name, result); }
void glGetProgramiv(unsigned program, unsigned name, int *result) { get_iv(EA_PROGRAM_IV, program, name, result); }

static void info_log(unsigned id, unsigned kind, int capacity, int *length, char *out)
{
    if (capacity < 0 || (capacity && !out)) return;
    unsigned char request[8], reply[2048];
    size_t got;
    hios_put32(request, id);
    hios_put32(request + 4, kind);
    if (!hios_eagl_current_call(EA_INFO_LOG, request, sizeof request, reply, sizeof reply, &got) || !got || reply[got - 1]) return;
    size_t n = got - 1;
    if (!capacity) n = 0;
    else {
        if (n >= (size_t)capacity) n = (size_t)capacity - 1;
        memcpy(out, reply, n);
        out[n] = 0;
    }
    if (length) *length = (int)n;
}

void glGetShaderInfoLog(unsigned shader, int capacity, int *length, char *out)
{ info_log(shader, 1, capacity, length, out); }
void glGetProgramInfoLog(unsigned program, int capacity, int *length, char *out)
{ info_log(program, 2, capacity, length, out); }

static int named(uint32_t op, unsigned program, unsigned index, const char *name)
{
    unsigned char request[8 + 1024], reply[4];
    if (!name) return -1;
    size_t n = bounded_length(name, 1024), got;
    if (!n || n == 1024) return -1;
    size_t off = op == EA_BIND_ATTRIB ? 8 : 4;
    hios_put32(request, program);
    if (off == 8) hios_put32(request + 4, index);
    memcpy(request + off, name, n + 1);
    size_t expected = off == 8 ? 0 : sizeof reply;
    if (!hios_eagl_current_call(op, request, off + n + 1, reply, expected, &got) || got != expected) return -1;
    return expected ? (int32_t)hios_get32(reply) : 0;
}

int glGetUniformLocation(unsigned program, const char *name) { return named(EA_UNIFORM_LOCATION, program, 0, name); }
void glBindAttribLocation(unsigned program, unsigned index, const char *name) { (void)named(EA_BIND_ATTRIB, program, index, name); }
