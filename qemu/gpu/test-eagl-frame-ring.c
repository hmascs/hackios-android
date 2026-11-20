/* SPDX-License-Identifier: GPL-2.0-or-later */
#define _POSIX_C_SOURCE 200809L
#include <assert.h>
#include <stdlib.h>
#include <sys/wait.h>

struct frame_ring;
static void checkpoint(struct frame_ring *ring);
#define HIOS_RING_WRITE_CHECKPOINT(ring) checkpoint(ring)
#include "eagl-frame-ring.h"

static int checkpoint_on;
static int reached[2], resume_writer[2];
static void checkpoint(struct frame_ring *ring)
{
    uint64_t guard = __atomic_load_n((uint64_t *)(ring->map + 40), __ATOMIC_ACQUIRE);
    uint64_t count = __atomic_load_n((uint64_t *)(ring->map + 24), __ATOMIC_ACQUIRE);
    assert(guard == count * 2 + 1);
    if (!checkpoint_on) return;
    char token = 'b';
    assert(write(reached[1], &token, 1) == 1);
    assert(read(resume_writer[0], &token, 1) == 1);
}

static void close_ring(struct frame_ring *ring)
{
    if (ring->map) assert(munmap(ring->map, ring->size) == 0);
    ring->map = NULL;
}

int main(void)
{
    char path[] = "/tmp/hios-ring-test-XXXXXX";
    int fd = mkstemp(path);
    assert(fd >= 0);
    const uint32_t width = 2, height = 3, stride = 12;
    const size_t length = RING_HEADER + height * stride;
    assert(ftruncate(fd, (off_t)length) == 0);
    unsigned char *file = mmap(NULL, length, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    assert(file != MAP_FAILED);
    close(fd);
    memset(file, 0, length);
    memcpy(file, RING_MAGIC, 4);
    hios_put32(file + 4, RING_VERSION);
    hios_put32(file + 8, width);
    hios_put32(file + 12, height);
    hios_put32(file + 16, stride);
    hios_put32(file + 20, RING_FORMAT_BGRA8888);
    memset(file + RING_HEADER, 0xa5, height * stride);

    struct frame_ring ring = {0};
    assert(ring_open(&ring, path));
    unsigned char pixels[width * height * 4];
    for (size_t i = 0; i < sizeof pixels; ++i) pixels[i] = (unsigned char)i;
    ring_publish(&ring, pixels, width, height);
    assert(ring.seq == 1);
    assert(__atomic_load_n((uint64_t *)(file + 24), __ATOMIC_ACQUIRE) == 1);
    assert(__atomic_load_n((uint64_t *)(file + 40), __ATOMIC_ACQUIRE) == 2);
    for (uint32_t y = 0; y < height; ++y) {
        assert(!memcmp(file + RING_HEADER + y * stride, pixels + y * width * 4, width * 4));
        for (size_t x = width * 4; x < stride; ++x) assert(file[RING_HEADER + y * stride + x] == 0xa5);
    }
    ring_publish(&ring, pixels, width - 1, height);
    assert(ring.seq == 1); /* Offscreen geometry must not mutate the panel slot. */

    assert(pipe(reached) == 0 && pipe(resume_writer) == 0);
    pid_t child = fork();
    assert(child >= 0);
    if (!child) {
        close(reached[0]); close(resume_writer[1]);
        checkpoint_on = 1;
        memset(pixels, 0x5b, sizeof pixels);
        ring_publish(&ring, pixels, width, height);
        _exit(0);
    }
    close(reached[1]); close(resume_writer[0]);
    char token;
    assert(read(reached[0], &token, 1) == 1);
    /* Producer is blocked in the real write path after invalidation, with the
     * old publication count still present. A v1 seq-only reader accepts here. */
    assert(__atomic_load_n((uint64_t *)(file + 24), __ATOMIC_ACQUIRE) == 1);
    assert(__atomic_load_n((uint64_t *)(file + 40), __ATOMIC_ACQUIRE) == 3);
    assert(file[RING_HEADER] == 0);
    assert(write(resume_writer[1], &token, 1) == 1);
    int status;
    assert(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    assert(__atomic_load_n((uint64_t *)(file + 24), __ATOMIC_ACQUIRE) == 2);
    assert(__atomic_load_n((uint64_t *)(file + 40), __ATOMIC_ACQUIRE) == 4);
    for (uint32_t y = 0; y < height; ++y) {
        for (size_t x = 0; x < width * 4; ++x) assert(file[RING_HEADER + y * stride + x] == 0x5b);
        for (size_t x = width * 4; x < stride; ++x) assert(file[RING_HEADER + y * stride + x] == 0xa5);
    }
    close(reached[0]); close(resume_writer[1]);
    close_ring(&ring);

    hios_put32(file + 16, width * 4);
    assert(ring_open(&ring, path));
    memset(pixels, 0x6c, sizeof pixels);
    assert(ring_publish(&ring, pixels, width, height));
    assert(!memcmp(file + RING_HEADER, pixels, sizeof pixels));
    assert(ring.seq == 3);
    close_ring(&ring);

    memcpy(file, "HFRM", 4); hios_put32(file + 4, 1);
    assert(!ring_open(&ring, path) && !ring.map);
    memcpy(file, RING_MAGIC, 4); hios_put32(file + 4, 99);
    assert(!ring_open(&ring, path));
    hios_put32(file + 4, RING_VERSION);
    __atomic_store_n((uint64_t *)(file + 40), 7, __ATOMIC_RELEASE);
    assert(!ring_open(&ring, path)); /* Crash left a partial overwrite. */
    __atomic_store_n((uint64_t *)(file + 40), 6, __ATOMIC_RELEASE);
    hios_put32(file + 16, 4);
    assert(!ring_open(&ring, path)); /* A stride smaller than one row. */
    assert(munmap(file, length) == 0);
    assert(unlink(path) == 0);
    puts("HFR2 writer: packed/padded pixels, publication count, cross-process guard, legacy/corrupt rejection passed");
    return 0;
}
