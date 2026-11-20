/* SPDX-License-Identifier: GPL-2.0-or-later */
#include "eagl-guest-client.h"
#include "eagl-guest-timing.h"
#include "eagl-batch.h"
#ifdef HIOS_XNU_DIRECT_IO
/* For the isolated iOS cross-build without an SDK. Darwin POSIX ABI. */
extern long read(int, void *, size_t);
extern long write(int, const void *, size_t);
extern int *__error(void);
#define errno (*__error())
#define EINTR 4
#else
#include <errno.h>
#include <unistd.h>
#endif

static int send_exact(int fd, const void *buf, size_t len)
{
    const unsigned char *p = buf;
    while (len) {
        long n = write(fd, p, len);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return 0;
        p += n;
        len -= (size_t)n;
    }
    return 1;
}

static int read_exact(int fd, void *buf, size_t len)
{
    unsigned char *p = buf;
    while (len) {
        long n = read(fd, p, len);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return 0;
        p += n;
        len -= (size_t)n;
    }
    return 1;
}

struct hios_call_timing hios_call_timing;

static int raw_call(struct hios_eagl_client *c, uint32_t context, uint32_t op,
                   const void *data, size_t len, void *reply, size_t capacity,
                   uint32_t *status, size_t *received)
{
    if (!c) return 0; /* reject NULL before any dereference */
    const int scatter = c->scatter_base != NULL && op == EA_READ_RGBA;
    if (c->failed || c->fd < 0 || c->seq == UINT32_MAX || len > HIOS_EAGL_MAX ||
        (!data && len) || (!reply && capacity && !scatter) || !status || !received) return 0;
    *received = 0;
    const int timed = op == EA_READ_RGBA || op == EA_TEXTURE_BASE || op == EA_TEXTURE_MIP;
    uint64_t t0 = 0, t1 = 0, t2 = 0, t3 = 0;
    if (timed) t0 = hios_now_us();
    unsigned char h[HIOS_EAGL_HEADER];
    hios_put32(h, HIOS_EAGL_REQUEST);
    hios_put32(h + 4, ++c->seq);
    hios_put32(h + 8, op);
    hios_put32(h + 12, context);
    hios_put32(h + 16, (uint32_t)len);
    if (!send_exact(c->fd, h, sizeof(h)) || !send_exact(c->fd, data, len)) goto fail;
    if (timed) t1 = hios_now_us();
    if (!read_exact(c->fd, h, sizeof(h))) goto fail;
    if (timed) t2 = hios_now_us();
    uint32_t code = hios_get32(h + 12), n = hios_get32(h + 16);
    if (hios_get32(h) != HIOS_EAGL_REPLY || hios_get32(h + 4) != c->seq || hios_get32(h + 8) != op ||
        code > EA_DEVICE_ERROR || n > HIOS_EAGL_MAX || (n > capacity && !scatter) || (code && n)) goto fail;
    if (scatter && n) {
        if (!c->scatter_row || n % c->scatter_row) goto fail;
        for (size_t y = 0; y < n / c->scatter_row; ++y)
            if (!read_exact(c->fd, c->scatter_base + y * c->scatter_stride, c->scatter_row)) goto fail;
        c->scatter_base = NULL;
    } else if (!read_exact(c->fd, reply, n)) {
        goto fail;
    }
    if (timed) {
        t3 = hios_now_us();
        hios_call_timing.send_us = t1 - t0;
        hios_call_timing.header_us = t2 - t1;
        hios_call_timing.body_us = t3 - t2;
        hios_call_timing.total_us = t3 - t0;
        hios_call_timing.reply_bytes = n;
    }
    *status = code;
    *received = n;
    if (code == EA_DEVICE_ERROR) c->failed = 1;
    return 1;
fail:
    c->failed = 1;
    return 0;
}

static void batch_poison(struct hios_eagl_client *c)
{
    c->failed=1;
    c->batch_count=0;
    c->batch_bytes=0; /* ambiguous partial I/O is never replayed */
}
enum drain_reason { DRAIN_CONTEXT, DRAIN_CAPACITY, DRAIN_BARRIER, DRAIN_CLOSE, DRAIN_BINDING };
static int drain_batch(struct hios_eagl_client *c,enum drain_reason reason)
{
    if (!c->batch_count) return !c->failed;
    hios_put32(c->batch_data,HIOS_BATCH_TAG);
    hios_put32(c->batch_data+4,c->batch_count);
    uint32_t status=EA_DEVICE_ERROR;size_t received=0;
    uint64_t began=hios_now_us();
    int ok=raw_call(c,c->batch_context,EA_BATCH_EXEC,c->batch_data,c->batch_bytes,NULL,0,&status,&received);
    c->batch_drain_us+=hios_now_us()-began;
    ++c->batch_drains;++c->batch_reason_counts[reason];
    c->batch_flushed_bytes+=c->batch_bytes;
    c->batch_count=0;c->batch_bytes=0;
    if (!ok || status!=EA_OK || received) { batch_poison(c);return 0; }
    return 1;
}
static int negotiate_batch(struct hios_eagl_client *c,uint32_t context)
{
    if (!c->batch_requested || c->batch_disabled || !context || context>HIOS_EAGL_CONTEXTS) return 0;
    uint32_t bit=1u<<(context-1);
    if (c->batch_slots&bit) return 1;
    unsigned char request[4],reply[16];hios_put32(request,HIOS_BATCH_TAG);
    uint32_t status=EA_DEVICE_ERROR;size_t n=0;
    if (!raw_call(c,context,EA_BATCH_CAPS,request,sizeof request,reply,sizeof reply,&status,&n)) {batch_poison(c);return -1;}
    if (status==EA_BAD_COMMAND && n==0) {
        c->batch_disabled=1;
        const char message[]="hackios-batch: requested=1 negotiated=0 legacy_worker_fallback=1\n";
        hios_log(message,(int)sizeof message-1);
        return 0; /* capability rejection is not an application GL error */
    }
    if (status!=EA_OK || n!=16 || hios_get32(reply)!=HIOS_BATCH_TAG ||
        !hios_get32(reply+4) || hios_get32(reply+4)>HIOS_BATCH_BYTES ||
        !hios_get32(reply+8) || hios_get32(reply+8)>HIOS_BATCH_COMMANDS ||
        hios_get32(reply+12)!=HIOS_BATCH_FEATURES) {batch_poison(c);return -1;}
    c->batch_limit_bytes=hios_get32(reply+4);c->batch_limit_commands=hios_get32(reply+8);
    c->batch_slots|=bit;
    const char message[]="hackios-batch: requested=1 negotiated=1 version=HTB1\n";
    hios_log(message,(int)sizeof message-1);
    return 1;
}
int hios_eagl_call(struct hios_eagl_client *c,uint32_t context,uint32_t op,
                  const void *data,size_t len,void *reply,size_t capacity,
                  uint32_t *status,size_t *received)
{
    if (!c || c->failed || c->fd<0 || !status || !received || (!data && len) || len>HIOS_EAGL_MAX) return 0;
    *received=0;*status=EA_DEVICE_ERROR;
    const int timed=op==EA_READ_RGBA || op==EA_TEXTURE_BASE || op==EA_TEXTURE_MIP;
    const uint64_t entered=timed?hios_now_us():0,drain_before=c->batch_drain_us;
    if (c->batch_count && (context!=c->batch_context || !c->batch_requested))
        if (!drain_batch(c,DRAIN_CONTEXT)) return 0;
    if (op==HIOS_EAGL_LOCAL_DRAIN) {
        if (!drain_batch(c,DRAIN_BINDING)) return 0;
        *status=EA_OK;return 1;
    }
    if (c->batch_requested && hios_batchable(op) && !capacity &&
        hios_batch_command_valid(op,data,len)) {
        int enabled=negotiate_batch(c,context);
        if (enabled<0) return 0;
        if (enabled && len<=c->batch_limit_bytes && len+16u<=c->batch_limit_bytes) {
            if (c->batch_count && (c->batch_count>=c->batch_limit_commands ||
                len+8u>c->batch_limit_bytes-c->batch_bytes)) if (!drain_batch(c,DRAIN_CAPACITY)) return 0;
            if (!c->batch_count) {c->batch_bytes=8;c->batch_context=context;}
            hios_put32(c->batch_data+c->batch_bytes,op);
            hios_put32(c->batch_data+c->batch_bytes+4,(uint32_t)len);
            if (len) __builtin_memcpy(c->batch_data+c->batch_bytes+8,data,len);
            c->batch_bytes+=8u+len;++c->batch_count;++c->batch_commands;
            c->batch_queued_bytes+=len;
            *status=EA_OK;return 1;
        }
    }
    /* Every non-allowlisted operation, or an oversized draw, is a barrier. */
    if (!drain_batch(c,(op==EA_CLOSE || op==EA_END_SESSION)?DRAIN_CLOSE:DRAIN_BARRIER)) return 0;
    int ok=raw_call(c,context,op,data,len,reply,capacity,status,received);
    if (!ok || *status==EA_DEVICE_ERROR) batch_poison(c);
    if (op==EA_CLOSE && ok && *status==EA_OK && context && context<=HIOS_EAGL_CONTEXTS)
        c->batch_slots&=~(1u<<(context-1));
    if (op==EA_READ_RGBA || op==EA_TEXTURE_BASE || op==EA_TEXTURE_MIP) {
        hios_call_timing.batch_drain_us=c->batch_drain_us-drain_before;
        hios_call_timing.total_us=hios_now_us()-entered;
    }
    if (c->batch_requested && (op==EA_READ_RGBA || op==EA_CLOSE || op==EA_END_SESSION)) {
        uint64_t receipt=++c->batch_receipts;
        if (receipt<=3 || receipt%60==0 || op==EA_CLOSE || op==EA_END_SESSION) {
            char line[512];
            int n=snprintf(line,sizeof line,"hackios-batch: version=HTB1 requested=1 negotiated=%u seq=%u queued_commands=%llu flushed_batches=%llu queued_payload_bytes=%llu flushed_packet_bytes=%llu drain_us_total=%llu reason_context=%llu reason_capacity=%llu reason_barrier=%llu reason_close=%llu reason_binding=%llu failed=%d\n",
                (unsigned)(!c->batch_disabled && (c->batch_slots || c->batch_drains)),c->seq,
                (unsigned long long)c->batch_commands,(unsigned long long)c->batch_drains,
                (unsigned long long)c->batch_queued_bytes,(unsigned long long)c->batch_flushed_bytes,
                (unsigned long long)c->batch_drain_us,(unsigned long long)c->batch_reason_counts[DRAIN_CONTEXT],
                (unsigned long long)c->batch_reason_counts[DRAIN_CAPACITY],(unsigned long long)c->batch_reason_counts[DRAIN_BARRIER],
                (unsigned long long)c->batch_reason_counts[DRAIN_CLOSE],(unsigned long long)c->batch_reason_counts[DRAIN_BINDING],c->failed);
            if(n>0) hios_log(line,(size_t)n<sizeof line?n:(int)sizeof line-1);
        }
    }
    return ok;
}

