/* SPDX-License-Identifier: GPL-2.0-or-later */
/* Fixed-work parallel CPU benchmark inside iOS. Not a GPU or FPS benchmark. */
#include <stdint.h>
#include <stddef.h>

typedef struct _opaque_pthread *pthread_t;
extern int pthread_create(pthread_t *, const void *, void *(*)(void *), void *);
extern int pthread_join(pthread_t, void **);
extern int usleep(unsigned);
extern uint64_t mach_absolute_time(void);
struct timebase { uint32_t numer, denom; };
extern int mach_timebase_info(struct timebase *);
extern int printf(const char *, ...);
extern void *malloc(size_t);

/* Six independent RGBA tiles. This includes memory reads/writes and per-channel
 * alpha math, using a small fixed working set rather than measuring idle tasks. */
enum { WORKERS = 6, PIXELS = 1 << 17, PASSES = 32 };
struct worker { uint32_t *dst, *src; uint64_t digest; };
static struct worker workers[WORKERS];
static unsigned ready, start;

static void *run(void *opaque)
{
    struct worker *w = opaque;
    __atomic_add_fetch(&ready, 1, __ATOMIC_RELEASE);
    while (!__atomic_load_n(&start, __ATOMIC_ACQUIRE)) usleep(1000);
    for (unsigned pass = 0; pass < PASSES; ++pass) {
        for (size_t p = 0; p < PIXELS; ++p) {
            uint32_t a = w->src[p], b = w->dst[p];
            uint32_t alpha = a >> 24, inverse = 256 - alpha;
            uint32_t rb = ((a & 0x00ff00ffu) * alpha + (b & 0x00ff00ffu) * inverse) >> 8;
            uint32_t g = ((a & 0x0000ff00u) * alpha + (b & 0x0000ff00u) * inverse) >> 8;
            w->dst[p] = 0xff000000u | (rb & 0x00ff00ffu) | (g & 0x0000ff00u);
        }
    }
    uint64_t h = UINT64_C(0xcbf29ce484222325);
    for (size_t p = 0; p < PIXELS; ++p) h = (h ^ w->dst[p]) * UINT64_C(0x100000001b3);
    w->digest = h;
    return NULL;
}

int main(void)
{
    struct timebase tb;
    if (mach_timebase_info(&tb) || !tb.numer || !tb.denom) return 1;
    pthread_t threads[WORKERS];
    uint32_t seed = 45821;
    for (unsigned i = 0; i < WORKERS; ++i) {
        struct worker *w = &workers[i];
        w->src = malloc(PIXELS * sizeof(uint32_t));
        w->dst = malloc(PIXELS * sizeof(uint32_t));
        if (!w->src || !w->dst) return 1;
        for (size_t p = 0; p < PIXELS; ++p) {
            seed = seed * 1664525u + 1013904223u;
            w->src[p] = seed;
            w->dst[p] = ~seed;
        }
    }
    for (unsigned i = 0; i < WORKERS; ++i)
        if (pthread_create(&threads[i], NULL, run, &workers[i])) return 1;
    while (__atomic_load_n(&ready, __ATOMIC_ACQUIRE) != WORKERS) usleep(1000);
    uint64_t t = mach_absolute_time();
    __atomic_store_n(&start, 1, __ATOMIC_RELEASE);
    for (unsigned i = 0; i < WORKERS; ++i) if (pthread_join(threads[i], NULL)) return 1;
    t = mach_absolute_time() - t;
    uint64_t h = 0;
    for (unsigned i = 0; i < WORKERS; ++i) h = (h << 1) ^ workers[i].digest;
    printf("hackios-cpu: workers=%u pixels=%u passes=%u wall_us=%llu digest=%016llx\n",
           (unsigned)WORKERS, (unsigned)PIXELS, (unsigned)PASSES,
           (unsigned long long)(t * tb.numer / tb.denom / 1000), (unsigned long long)h);
    return 0;
}
