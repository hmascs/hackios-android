/* SPDX-License-Identifier: GPL-2.0-or-later */
#ifndef HIOS_EAGL_GUEST_CLIENT_H
#define HIOS_EAGL_GUEST_CLIENT_H
#include "eagl-wire.h"

/* The bridge owns this private socket and serializes calls. A protocol/IO
 * violation poisons the stream; it cannot be resumed with partial GL state. */
struct hios_eagl_client {
    int fd;
    uint32_t seq;
    int failed;
    /* Measurement build: when `scatter_base` is set, the next readback's body
     * is read row by row into that mapping (the IOSurface) instead of one
     * contiguous reply buffer. Cleared by the call that uses it. */
    unsigned char *scatter_base;
    size_t scatter_stride, scatter_row;
    /* Experimental, default off; guarded by the existing remote stream lock.
     * Payloads are owned deep copies. Context changes must drain first. */
    int batch_requested, batch_disabled;
    uint32_t batch_slots, batch_context, batch_count, batch_limit_bytes, batch_limit_commands;
    size_t batch_bytes;
    uint64_t batch_drains, batch_commands, batch_drain_us;
    uint64_t batch_queued_bytes, batch_flushed_bytes, batch_reason_counts[5], batch_receipts;
    unsigned char batch_data[65536];
};
int hios_eagl_call(struct hios_eagl_client *, uint32_t context, uint32_t op,
                   const void *data, size_t bytes, void *reply, size_t capacity,
                   uint32_t *status, size_t *received);
#define HIOS_EAGL_LOCAL_DRAIN UINT32_MAX /* local barrier, never emitted on wire */
static inline int hios_eagl_drain(struct hios_eagl_client *c)
{
    if (!c || c->failed) return 0;
    if (!c->batch_count) return 1;
    uint32_t status=EA_DEVICE_ERROR;
    size_t received=0;
    return hios_eagl_call(c,c->batch_context,HIOS_EAGL_LOCAL_DRAIN,NULL,0,NULL,0,&status,&received) && status==EA_OK && !received;
}
/* Serialize access to the guest bridge's one process-wide context. */
void hios_eagl_lock(void);
void hios_eagl_unlock(void);
/* Thread binding does not own the client or the context slot. */
int hios_eagl_bind_thread(struct hios_eagl_client *, uint32_t context);
int hios_eagl_current_call(uint32_t op, const void *data, size_t bytes,
                           void *reply, size_t capacity, size_t *received);
/* Measurement build: the next hios_eagl_current_call(EA_READ_RGBA) reads its
 * reply rows straight into BASE (row y at BASE + y * stride). */
void hios_eagl_current_scatter(void *base, size_t stride, size_t row);
#endif
