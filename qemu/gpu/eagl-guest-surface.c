/* SPDX-License-Identifier: GPL-2.0-or-later */
#include "eagl-guest-client.h"
#include "eagl-guest-timing.h"
#include "eagl-pixel-transfer.h"
#include <string.h>
#include <pthread.h>

extern void *malloc(size_t);
extern void free(void *);

/*
 * The production path is the delta readback (HIOS_EAGL_READ_DELTA): the
 * worker keeps the frame the surface already holds and, when only part of it
 * changed, sends just those row bands. Sending the whole 10.9 MB frame over
 * slirp costs ~1.7 s; the per-byte cost is fixed (no GSO, MTU 1500), so the
 * only lever is fewer bytes.
 *
 * The reply is either 'FULL' + the whole picture or 'DELT' + a row table +
 * those bands; both are already swapped (and flipped, when asked), so the
 * guest reconstructs that complete picture in a private packed shadow, then
 * copies EVERY valid pixel to the live IOSurface on each successful reply.
 * Requests carry an opaque token for that private base, never a claim about
 * the current contents of live RAM. CPU writes and reused live storage cannot
 * leave unchanged bands stale: the final full copy restores the whole image.
 * Shadows are bounded independently of the reply scratch; failure to allocate
 * one requests a fresh token and FULL, preserving ordinary full-copy semantics.
 *
 * The measurement variants below are kept for A/B runs (HIOS_GL_SKIP_AFTER):
 *
 *   v0  the original path: 10.9 MB malloc, one socket read, byte-at-a-time
 *       channel swap in the guest
 *   v1  the same wire, a reused buffer and the swap eight bytes at a time
 *   v2  the host converts to BGRA in destination row order; the guest only
 *       copies rows into the IOSurface
 *   v3  as v2, but the socket is read straight into the IOSurface mapping
 *       when its stride is exactly width * 4 (no guest copy at all)
 *   v4  v2 with HIOS_EAGL_READ_NO_REPLY, falling back to v2 if the worker
 *       has no frame ring
 *
 * All of them produce the same bytes; test-eagl-gl-variants.c checks the
 * pixel helpers off the VM and test-eagl-guest-surface.c the reply handling.
 */
#define HIOS_GL_VARIANTS 4
/* Not a measurement variant: the default readback, see above. */
#define HIOS_GL_DELTA 5u
/*
 * 跳过回写（NO_REPLY）现在**默认关闭**：0 = 不跳，读回照常把像素写进 IOSurface。
 *
 * 为什么关掉（0.31.4 真机实测）：跳过之后画面出现「图标一个一个冒出、软件界面绘制闪烁、
 * 不知名黑块」。机制是 CA 的工作方式——它把合成结果回写进 IOSurface，下一帧再把这个
 * IOSurface 当纹理上传（日志里的 `textures refilled from IOSurfaces`）。不回写 ⇒ 上传的
 * 一直是旧内容 ⇒ 新画的部分被旧内容盖掉，没画过的地方是未初始化，看起来就是黑块。
 *
 * 所以**像素必须继续同步到 surface**。这条路要重新启用，前提是先有「廉价回写」：
 * 差分回写（只发变化的行带）或宿主侧影子纹理（surface 内容只在宿主 GPU 上，
 * 上传时绑定影子、回写变 no-op），见 docs/gl-frame-ring.md。
 *
 * 置为 >0 就是实验档：从第 N 次读回起尝试跳，worker 没有环时按回复自协商退回（见下）。
 */
#define HIOS_GL_SKIP_AFTER 0u

static unsigned next_variant;
static unsigned char *scratch;
static size_t scratch_capacity;
static unsigned readbacks;
static uint64_t last_end_us;
/* 0: not asked yet, 1: the worker takes the pixels (ring is open), -1: it does not. */
static int skip_accepted;

/* Serialize bridge-owned state only. MiniIOSurfaceLock is a seed notification,
 * not an exclusion lock against other guest CPU writers: callers must arrange
 * ownership; the exact checks below are not an atomic snapshot of racing RAM. */
static pthread_mutex_t surface_lock = PTHREAD_MUTEX_INITIALIZER;
#define HIOS_SURFACE_SHADOW_SLOTS 3u
#ifndef HIOS_SURFACE_SHADOW_BUDGET
#define HIOS_SURFACE_SHADOW_BUDGET (64u * 1024u * 1024u)
#endif
struct surface_shadow {
    void *base;
    size_t stride, bytes;
    uint32_t width, height;
    int invert, valid;
    unsigned char *pixels; /* tightly packed, only meaningful pixel bytes */
    uint64_t token, used;
};
static struct surface_shadow shadows[HIOS_SURFACE_SHADOW_SLOTS];
static size_t shadow_bytes;
static uint64_t shadow_clock, next_token;

static void shadow_release(struct surface_shadow *s)
{
    if (s->pixels) {
        free(s->pixels);
        shadow_bytes -= s->bytes;
    }
    s->pixels = NULL;
    s->bytes = 0;
    s->valid = 0;
}

static void shadows_forget(void)
{
    for (unsigned i = 0; i < HIOS_SURFACE_SHADOW_SLOTS; ++i) shadows[i].valid = 0;
}

static struct surface_shadow *shadow_for(void *base, size_t stride, uint32_t width,
                                         uint32_t height, int invert, size_t bytes)
{
    struct surface_shadow *s = NULL, *oldest = &shadows[0];
    for (unsigned i = 0; i < HIOS_SURFACE_SHADOW_SLOTS; ++i) {
        struct surface_shadow *candidate = &shadows[i];
        if (candidate->base == base && candidate->stride == stride &&
            candidate->width == width && candidate->height == height && candidate->invert == invert) {
            s = candidate;
            break;
        }
        if (candidate->used < oldest->used) oldest = candidate;
    }
    if (!s) {
        s = oldest;
        shadow_release(s);
        s->base = base;
        s->stride = stride;
        s->width = width;
        s->height = height;
        s->invert = invert;
    }
    s->used = ++shadow_clock;
    if (!s->pixels && bytes <= (size_t)HIOS_SURFACE_SHADOW_BUDGET) {
        /* Evict only enough least-recently-used storage to stay under budget. */
        while (shadow_bytes > (size_t)HIOS_SURFACE_SHADOW_BUDGET - bytes) {
            struct surface_shadow *victim = NULL;
            for (unsigned i = 0; i < HIOS_SURFACE_SHADOW_SLOTS; ++i) {
                struct surface_shadow *p = &shadows[i];
                if (p != s && p->pixels && (!victim || p->used < victim->used)) victim = p;
            }
            if (!victim) break;
            shadow_release(victim);
        }
        if (shadow_bytes <= (size_t)HIOS_SURFACE_SHADOW_BUDGET - bytes) {
            s->pixels = malloc(bytes);
            if (s->pixels) {
                s->bytes = bytes;
                shadow_bytes += bytes;
            }
        }
    }
    return s;
}

/* Reused, so a frame never allocates: the reply can be the whole frame plus a
 * row table for every band. */
static int reserve_scratch(size_t need)
{
    if (need <= scratch_capacity) return 1;
    unsigned char *grown = malloc(need);
    if (!grown) return 0;
    free(scratch);
    scratch = grown;
    scratch_capacity = need;
    return 1;
}

/* Apply one DELTA reply body to the locked surface. The rows were converted by
 * the worker (BGRA, destination order), so nothing here swaps or flips: row y
 * of a band belongs at offset y * stride. */
static int apply_reply(void *base, size_t capacity, size_t stride,
                       uint32_t width, uint32_t height,
                       const unsigned char *body, size_t size)
{
    size_t bytes = hios_pixel_packed_size(width, height, HIOS_RGBA8);
    size_t row = (size_t)width * 4;
    if (!bytes || !body || size < 4) return 0;
    if (!memcmp(body, HIOS_EAGL_DELTA_FULL, 4)) {
        return size == bytes + 4 &&
            hios_pixel_copy_rows(base, capacity, stride, body + 4, bytes, width, height);
    }
    if (memcmp(body, HIOS_EAGL_DELTA_PART, 4) || size < HIOS_EAGL_DELTA_HEADER) return 0;
    size_t bands = hios_get32(body + 4);
    if (bands > HIOS_EAGL_DELTA_BANDS ||
        size < HIOS_EAGL_DELTA_HEADER + bands * HIOS_EAGL_DELTA_BAND) return 0;
    /* Check the whole table before writing one row: a malformed reply must not
     * leave the surface half updated, because the next delta assumes it holds
     * exactly what the worker sent. */
    const unsigned char *table = body + HIOS_EAGL_DELTA_HEADER;
    size_t pixels = 0, previous = 0;
    for (size_t i = 0; i < bands; ++i) {
        size_t y = hios_get32(table + i * HIOS_EAGL_DELTA_BAND);
        size_t rows = hios_get32(table + i * HIOS_EAGL_DELTA_BAND + 4);
        if (!rows || rows > HIOS_EAGL_DELTA_BAND_ROWS || y < previous || y + rows > height) return 0;
        previous = y + rows;
        pixels += rows * row;
    }
    if (size != HIOS_EAGL_DELTA_HEADER + bands * HIOS_EAGL_DELTA_BAND + pixels) return 0;
    const unsigned char *from = table + bands * HIOS_EAGL_DELTA_BAND;
    for (size_t i = 0; i < bands; ++i) {
        size_t y = hios_get32(table + i * HIOS_EAGL_DELTA_BAND);
        size_t rows = hios_get32(table + i * HIOS_EAGL_DELTA_BAND + 4);
        /* Wire rows are tightly packed; IOSurface rows need not be. A single
         * rows*row copy would put row 1 into row 0's padding and shift every
         * subsequent row. All table/payload checks above finish before writes. */
        for (size_t r = 0; r < rows; ++r) {
            memcpy((unsigned char *)base + (y + r) * stride, from + r * row, row);
        }
        from += rows * row;
    }
    return 1;
}

/* Call while the destination IOSurface is locked and its render target is
 * attached to the remote read framebuffer. Unlock/publish only after success. */
static int read_surface_locked(void *base, size_t capacity, size_t stride,
                                uint32_t width, uint32_t height, int invert,
                                uint64_t began, uint64_t mutex_wait_us)
{
    size_t bytes = hios_pixel_packed_size(width, height, HIOS_RGBA8);
    if (!base || !bytes || (invert != 0 && invert != 1)) return 0;
    size_t row = (size_t)width * 4;
    if (stride < row || capacity < row || height - 1 > (capacity - row) / stride) return 0;
    /* Ask from HIOS_GL_SKIP_AFTER on. Once a worker has refused (no ring), stop
     * asking and fall back to the best ordinary path: the worker converts, the
     * guest only copies rows (variant 2), not the byte-wise one it started with. */
#if HIOS_GL_SKIP_AFTER
    const unsigned variant = readbacks < HIOS_GL_SKIP_AFTER ? HIOS_GL_DELTA
        : skip_accepted < 0 ? 2u : 4u;
#else
    const unsigned variant = HIOS_GL_DELTA;
#endif
    (void)next_variant;
    const uint64_t gap = last_end_us && began >= last_end_us ? began - last_end_us : 0;
    const char *path = "buffer", *forced_full_reason = "none";
    uint64_t alloc_us = 0, unpack_us = 0, shadow_prepare_us = 0, shadow_compose_us = 0, full_copy_us = 0;
    size_t full_copy_bytes = 0;
    int ok = 0;
    unsigned char request[HIOS_EAGL_READ_DELTA_REQUEST];
    size_t request_size = HIOS_EAGL_READ_REQUEST;
    size_t received = 0;
    unsigned char *rgba = NULL;

    hios_put32(request, width);
    hios_put32(request + 4, height);
    if (variant == HIOS_GL_DELTA) {
        /* The host token identifies only our private reconstructed base. The
         * live surface can be changed by a CPU writer at any time; every
         * successful transaction ends with an ordinary full-pixel writeback. */
        const size_t bands = (height + HIOS_EAGL_DELTA_BAND_ROWS - 1) / HIOS_EAGL_DELTA_BAND_ROWS;
        if (!reserve_scratch(bytes + HIOS_EAGL_DELTA_HEADER + bands * HIOS_EAGL_DELTA_BAND)) return 0;
        const uint64_t prepare_start = hios_now_us();
        struct surface_shadow *shadow = shadow_for(base, stride, width, height, invert, bytes);
        const int matched = shadow->valid && shadow->pixels;
        shadow_prepare_us = hios_now_us() - prepare_start;
        if (!matched) {
            forced_full_reason = !shadow->pixels ? "no_shadow" : "unproven_base";
            shadow->valid = 0;
            /* Never wrap/reuse a token while the worker may retain that key. */
            if (next_token == UINT64_MAX) return 0;
            shadow->token = ++next_token;
        }
        hios_put32(request + 8, HIOS_EAGL_READ_SWAP |
            (invert ? HIOS_EAGL_READ_DEST_ROWS : 0u) | HIOS_EAGL_READ_DELTA);
        hios_put32(request + 12, (uint32_t)shadow->token);
        hios_put32(request + 16, (uint32_t)(shadow->token >> 32));
        hios_put32(request + 20, (uint32_t)stride);
        request_size = HIOS_EAGL_READ_DELTA_REQUEST;
        path = "delta";
        ok = hios_eagl_current_call(EA_READ_RGBA, request, request_size,
                                    scratch, scratch_capacity, &received);
        if (ok) {
            const uint64_t before = hios_now_us();
            /* A fresh token has no proven base. Refuse an unsolicited DELT,
             * even a zero-band one, instead of keeping arbitrary live pixels. */
            ok = matched || (received >= 4 && !memcmp(scratch, HIOS_EAGL_DELTA_FULL, 4));
            if (ok && shadow->pixels) {
                const uint64_t compose_start = hios_now_us();
                /* Reconstruct ONLY from the host reply and our private prior
                 * base. apply_reply validates the entire message before writes. */
                ok = apply_reply(shadow->pixels, shadow->bytes, row, width, height, scratch, received);
                shadow_compose_us = hios_now_us() - compose_start;
                if (ok) {
                    const uint64_t copy_start = hios_now_us();
                    ok = hios_pixel_copy_rows(base, capacity, stride, shadow->pixels, bytes, width, height);
                    full_copy_us = hios_now_us() - copy_start;
                    if (ok) {
                        full_copy_bytes = bytes;
                        shadow->valid = 1;
                    }
                }
            } else if (ok) {
                /* No private storage: a fresh FULL uses ordinary full writeback.
                 * It may not leave a reusable private delta base. */
                const uint64_t copy_start = hios_now_us();
                ok = apply_reply(base, capacity, stride, width, height, scratch, received);
                full_copy_us = hios_now_us() - copy_start;
                if (ok) full_copy_bytes = bytes;
            }
            unpack_us = hios_now_us() - before;
        }
        if (!ok) shadow->valid = 0;
    } else if (variant == 4) {
        shadows_forget();
        /* Ask, but have somewhere to put the answer in case this worker has no
         * ring: it then replies with the whole picture, and that readback is
         * served exactly like variant 2 (the worker already swapped and flipped
         * it, so only the rows have to be copied into the surface). */
        hios_put32(request + 8,
            HIOS_EAGL_READ_SWAP | HIOS_EAGL_READ_NO_REPLY | (invert ? HIOS_EAGL_READ_DEST_ROWS : 0u));
        path = "skip";
        if (!reserve_scratch(bytes)) return 0;
        rgba = scratch;
        ok = hios_eagl_current_call(EA_READ_RGBA, request, request_size, rgba, bytes, &received);
        if (ok && received == 0) {
            skip_accepted = 1;
        } else if (ok && received == bytes) {
            skip_accepted = -1;
            path = "skip-refused";
            const uint64_t before = hios_now_us();
            ok = hios_pixel_copy_rows(base, capacity, stride, rgba, bytes, width, height);
            unpack_us = hios_now_us() - before;
        } else {
            ok = 0;
        }
    } else if (variant == 3) {
        shadows_forget();
        /* The reply's rows go straight into the IOSurface mapping, one row at
         * a time: surfaces here have a padded stride (4544 or 4608), so there
         * is no single contiguous read that could be done. */
        hios_put32(request + 8, HIOS_EAGL_READ_SWAP | (invert ? HIOS_EAGL_READ_DEST_ROWS : 0u));
        path = "scatter";
        hios_eagl_current_scatter(base, stride, row);
        ok = hios_eagl_current_call(EA_READ_RGBA, request, request_size, NULL, 0, &received) &&
            received == bytes;
    } else {
        shadows_forget();
        hios_put32(request + 8,
            variant >= 2 ? (HIOS_EAGL_READ_SWAP | (invert ? HIOS_EAGL_READ_DEST_ROWS : 0u)) : 0u);
        if (variant == 0) {
            uint64_t before = hios_now_us();
            rgba = malloc(bytes);
            alloc_us = hios_now_us() - before;
        } else {
            if (!reserve_scratch(bytes)) return 0;
        }
        if (variant != 0) rgba = scratch;
        if (!rgba) return 0;
        ok = hios_eagl_current_call(EA_READ_RGBA, request, request_size, rgba, bytes, &received) &&
            received == bytes;
        if (ok) {
            uint64_t before = hios_now_us();
            if (variant == 0) ok = hios_pixel_unpack_bgra(base, capacity, stride, rgba, bytes, width, height, invert);
            else if (variant == 1) ok = hios_pixel_unpack_bgra_words(base, capacity, stride, rgba, bytes, width, height, invert);
            else ok = hios_pixel_copy_rows(base, capacity, stride, rgba, bytes, width, height);
            unpack_us = hios_now_us() - before;
        }
        if (variant == 0) free(rgba);
    }
    if (!ok) return 0;
    const uint64_t ended = hios_now_us();
    last_end_us = ended;
    ++readbacks;
    char line[512];
    int n = snprintf(line, sizeof line,
        "hackios-gl: out %u v%u %s bytes=%u stride=%u row=%u inv=%d gap=%llu malloc=%llu send=%llu hdr=%llu "
        "body=%llu unpack=%llu total=%llu shadow_prepare_us=%llu shadow_compose_us=%llu "
        "full_copy_us=%llu full_copy_bytes=%llu mutex_wait_us=%llu forced_full=%s batch_drain_us=%llu\n",
        readbacks, variant, path, (unsigned)bytes, (unsigned)stride, (unsigned)row, invert,
        (unsigned long long)gap, (unsigned long long)alloc_us,
        (unsigned long long)hios_call_timing.send_us, (unsigned long long)hios_call_timing.header_us,
        (unsigned long long)hios_call_timing.body_us, (unsigned long long)unpack_us,
        (unsigned long long)(ended - began), (unsigned long long)shadow_prepare_us,
        (unsigned long long)shadow_compose_us, (unsigned long long)full_copy_us,
        (unsigned long long)full_copy_bytes, (unsigned long long)mutex_wait_us, forced_full_reason,
        (unsigned long long)hios_call_timing.batch_drain_us);
    if (n > 0) hios_log(line, (size_t)n < sizeof line ? n : (int)sizeof line - 1);
    return 1;
}

int hios_eagl_read_surface(void *base, size_t capacity, size_t stride,
                           uint32_t width, uint32_t height, int invert)
{
    const uint64_t began = hios_now_us(); /* includes mutex wait and shadow work */
    if (pthread_mutex_lock(&surface_lock)) return 0;
    const uint64_t acquired = hios_now_us();
    int ok = read_surface_locked(base, capacity, stride, width, height, invert,
                                 began, acquired >= began ? acquired - began : 0);
    pthread_mutex_unlock(&surface_lock);
    return ok;
}
