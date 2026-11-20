/* SPDX-License-Identifier: GPL-2.0-or-later */
#ifndef HIOS_FRAME_RING_H
#define HIOS_FRAME_RING_H

#include "eagl-wire.h"
#include <inttypes.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

/* HFR2 is deliberately a different magic: the v1 writer ignored version, so
 * changing only that integer would let it overwrite pixels without a guard.
 * An incompatible ring disables only this display shortcut, never guest I/O.
 * The geometry is fixed for the session; non-panel readbacks are not published.
 */
#define RING_MAGIC "HFR2"
#define RING_VERSION 2u
#define RING_FORMAT_BGRA8888 1u
#define RING_HEADER 64u
#define RING_MAX_EDGE 16384u
#define RING_SEQ_OFFSET 24u
#define RING_GUARD_OFFSET 40u

struct frame_ring {
    unsigned char *map;
    size_t size;
    uint32_t width, height, stride, format;
    uint64_t seq; /* Complete publications, not the odd/even guard. */
};

static int ring_open(struct frame_ring *ring, const char *path)
{
    int fd = open(path, O_RDWR);
    if (fd < 0) return 0;
    struct stat info;
    if (fstat(fd, &info) != 0 || info.st_size < (off_t)RING_HEADER) {
        close(fd);
        return 0;
    }
    void *map = mmap(NULL, (size_t)info.st_size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    close(fd);
    if (map == MAP_FAILED) return 0;
    ring->map = map;
    ring->size = (size_t)info.st_size;
    if (memcmp(ring->map, RING_MAGIC, 4) != 0 || hios_get32(ring->map + 4) != RING_VERSION) {
        fprintf(stderr, "EAGL worker: incompatible frame ring; HFR2 v2 required, ring disabled\n");
        goto invalid;
    }
    ring->width = hios_get32(ring->map + 8);
    ring->height = hios_get32(ring->map + 12);
    ring->stride = hios_get32(ring->map + 16);
    ring->format = hios_get32(ring->map + 20);
    if (!ring->width || ring->width > RING_MAX_EDGE ||
        !ring->height || ring->height > RING_MAX_EDGE ||
        ring->stride < ring->width * 4u || ring->stride > RING_MAX_EDGE * 4u ||
        (size_t)RING_HEADER + (size_t)ring->stride * ring->height > ring->size ||
        ring->format != RING_FORMAT_BGRA8888) {
        fprintf(stderr, "EAGL worker: frame ring geometry/format invalid, ring disabled\n");
        goto invalid;
    }
    ring->seq = __atomic_load_n((uint64_t *)(ring->map + RING_SEQ_OFFSET), __ATOMIC_ACQUIRE);
    uint64_t guard = __atomic_load_n((uint64_t *)(ring->map + RING_GUARD_OFFSET), __ATOMIC_ACQUIRE);
    /* A new session creates a fresh file. Do not resume a file left half-written
     * by a crashed producer, or pretend it contains a complete publication. */
    if (ring->seq >= UINT64_MAX / 2 || guard != ring->seq * 2) {
        fprintf(stderr, "EAGL worker: interrupted or exhausted frame ring, ring disabled\n");
        goto invalid;
    }
    fprintf(stderr, "EAGL worker: HFR2 frame ring %ux%u stride %u, seq=%" PRIu64 "\n",
            ring->width, ring->height, ring->stride, ring->seq);
    return 1;
invalid:
    munmap(ring->map, ring->size);
    ring->map = NULL;
    return 0;
}

/* Only the test build has a checkpoint. It can stop a producer after the
 * invalidation but before any pixel write, without a sleep/race-based test. */
#ifndef HIOS_RING_WRITE_CHECKPOINT
#define HIOS_RING_WRITE_CHECKPOINT(ring) ((void)0)
#endif

static int ring_publish(struct frame_ring *ring, const unsigned char *pixels,
                         uint32_t width, uint32_t height)
{
    if (!ring->map || !pixels || width != ring->width || height != ring->height ||
        ring->seq >= UINT64_MAX / 2 - 1) return 0;
    uint64_t next = ring->seq + 1;
    uint64_t *guard = (uint64_t *)(ring->map + RING_GUARD_OFFSET);
    /* The acquire half prevents later pixel stores from moving ahead of this
     * invalidation; release alone on an odd store would not provide that order. */
    (void)__atomic_exchange_n(guard, next * 2 - 1, __ATOMIC_ACQ_REL);
    HIOS_RING_WRITE_CHECKPOINT(ring);
    size_t row = (size_t)width * 4;
    unsigned char *dest = ring->map + RING_HEADER;
    if (ring->stride == row) {
        memcpy(dest, pixels, row * height);
    } else {
        for (uint32_t y = 0; y < height; ++y)
            memcpy(dest + (size_t)y * ring->stride, pixels + (size_t)y * row, row);
    }
    struct timespec now;
    uint64_t stamp = 0;
    if (clock_gettime(CLOCK_MONOTONIC, &now) == 0)
        stamp = (uint64_t)now.tv_sec * UINT64_C(1000000000) + (uint64_t)now.tv_nsec;
    memcpy(ring->map + 32, &stamp, 8);
    ring->seq = next;
    __atomic_store_n((uint64_t *)(ring->map + RING_SEQ_OFFSET), next, __ATOMIC_RELEASE);
    __atomic_store_n(guard, next * 2, __ATOMIC_RELEASE);
    return 1;
}
#endif
