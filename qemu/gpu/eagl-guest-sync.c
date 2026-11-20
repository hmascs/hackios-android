/* SPDX-License-Identifier: GPL-2.0-or-later */
#include "eagl-guest-client.h"

/* Guest handles are opaque tokens, not dereferenceable pointers. The current
 * Redwood adapter has only one context per process. */
struct __GLsync;
struct __GLsync *glFenceSync(unsigned condition, unsigned flags)
{
    unsigned char req[8], reply[4];
    size_t n;
    hios_put32(req, condition);
    hios_put32(req + 4, flags);
    if (!hios_eagl_current_call(EA_FENCE, req, sizeof req, reply, sizeof reply, &n) || n != sizeof reply)
        return NULL;
    return (struct __GLsync *)(uintptr_t)hios_get32(reply);
}

unsigned glClientWaitSync(struct __GLsync *sync, unsigned flags, uint64_t timeout)
{
    unsigned char req[16], reply[4];
    size_t n;
    uintptr_t id = (uintptr_t)sync;
    if (!id || id > UINT32_MAX) return 0x911d; /* GL_WAIT_FAILED */
    hios_put32(req, (uint32_t)id);
    hios_put32(req + 4, flags);
    hios_put32(req + 8, (uint32_t)timeout);
    hios_put32(req + 12, (uint32_t)(timeout >> 32));
    if (!hios_eagl_current_call(EA_CLIENT_WAIT, req, sizeof req, reply, sizeof reply, &n) || n != sizeof reply)
        return 0x911d;
    return hios_get32(reply);
}

void glDeleteSync(struct __GLsync *sync)
{
    unsigned char req[4];
    size_t n;
    uintptr_t id = (uintptr_t)sync;
    if (!id || id > UINT32_MAX) return;
    hios_put32(req, (uint32_t)id);
    (void)hios_eagl_current_call(EA_DELETE_FENCE, req, sizeof req, NULL, 0, &n);
}
