/* SPDX-License-Identifier: GPL-2.0-or-later */
#include "eagl-guest-client.h"
#include <pthread.h>

static pthread_mutex_t remote_lock = PTHREAD_MUTEX_INITIALIZER;
void hios_eagl_lock(void) { pthread_mutex_lock(&remote_lock); }
void hios_eagl_unlock(void) { pthread_mutex_unlock(&remote_lock); }

typedef unsigned GLenum, GLuint, GLbitfield;
typedef int GLint, GLsizei;
typedef float GLfloat, GLclampf;
typedef unsigned char GLboolean;

/* Binding is local to each guest thread; the host EGL context and wire
 * sequence are shared and all request/response pairs are serialized. */
static _Thread_local struct hios_eagl_client *client;
static _Thread_local uint32_t slot;
static _Thread_local uint32_t local_error;

int hios_eagl_bind_thread(struct hios_eagl_client *c, uint32_t context)
{
    hios_eagl_lock();
    int drained = !client || hios_eagl_drain(client);
    if (!drained && c) {
        local_error=0x0502;
        hios_eagl_unlock();
        return 0; /* preserve failed old binding; no false successful handoff */
    }
    client = c;
    slot = context;
    local_error = drained ? 0 : 0x0502;
    hios_eagl_unlock();
    return drained;
}

int hios_eagl_current_call(uint32_t op, const void *data, size_t bytes,
                           void *reply, size_t capacity, size_t *received)
{
    uint32_t status;
    hios_eagl_lock();
    int ok = slot && hios_eagl_call(client, slot, op, data, bytes, reply, capacity, &status, received) && status == EA_OK;
    hios_eagl_unlock();
    if (!ok) local_error = 0x0502;
    return ok;
}

void hios_eagl_current_scatter(void *base, size_t stride, size_t row)
{
    hios_eagl_lock();
    if (client) {
        client->scatter_base = base;
        client->scatter_stride = stride;
        client->scatter_row = row;
    }
    hios_eagl_unlock();
}

static uint32_t guest_scalar(uint32_t op, const uint32_t *args, size_t count, int returns_word)
{
    if (op == EA_glGetError && local_error) {
        hios_eagl_lock();
        if (client && !hios_eagl_drain(client)) local_error=0x0502;
        hios_eagl_unlock();
        uint32_t error = local_error;
        local_error = 0;
        return error;
    }
    unsigned char request[20], reply[4];
    for (size_t i = 0; i < count; ++i) hios_put32(request + i * 4, args[i]);
    size_t got, expected = returns_word ? 4 : 0;
    if (!hios_eagl_current_call(op, count ? request : NULL, count * 4,
                                returns_word ? reply : NULL, expected, &got) || got != expected) {
        if (op == EA_glGetError) return 0x0502; /* GL_INVALID_OPERATION */
        local_error = 0x0502;
        return 0;
    }
    return returns_word ? hios_get32(reply) : 0;
}

static uint32_t word(uint32_t v) { return v; }
static uint32_t fbits(float v)
{
    uint32_t u;
    _Static_assert(sizeof(u) == sizeof(v), "binary32 required");
    __builtin_memcpy(&u, &v, sizeof(u));
    return u;
}
#include "eagl-guest-generated.h"
