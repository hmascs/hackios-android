/* SPDX-License-Identifier: GPL-2.0-or-later */
/* Off-VM check of what the guest puts on the wire and does with a readback
 * reply. Every readback asks for a delta and names its surface (base address,
 * stride, size); the reply is either the marked whole frame or the changed
 * bands, and a reply is copied into the surface without swapping or flipping.
 *
 * The mock worker answers exactly like eagl-host-session.c: 'FULL' + pixels,
 * 'DELT' + table + pixels, or a malformed table that must be refused whole.
 */
#include "eagl-guest-client.h"
#include "eagl-guest-timing.h"
#include "eagl-pixel-transfer.h"
#include <assert.h>
#include <stdlib.h>
#include <pthread.h>
#include <string.h>

/* Normally owned by eagl-guest-scalars.c; the surface code only logs it. */
struct hios_call_timing hios_call_timing;

static const uint32_t swap = HIOS_EAGL_READ_SWAP, dest_rows = HIOS_EAGL_READ_DEST_ROWS;
static int calls, truncated;
static uint32_t request_flags, request_stride;
static uint64_t request_key;
static uint32_t expected_width = 1, expected_height = 2;
static const unsigned char *next_body;
static size_t next_size;
static int model_worker, model_fail_call, model_unsolicited_delta;
static unsigned char *model_cpu_write;
static unsigned char model_cpu_value;
static struct { uint64_t key, used; } model_cache[3];
static uint64_t model_clock;
static unsigned model_full, model_delta;
static const unsigned char model_pixels[] = {1, 2, 3, 4, 5, 6, 7, 8};

/* 1x2 pixels as the worker converts them: BGRA, destination row order. */
static const unsigned char full_body[] = {'F', 'U', 'L', 'L', 1, 2, 3, 4, 5, 6, 7, 8};
static const unsigned char other_body[] = {'F', 'U', 'L', 'L', 7, 6, 5, 8, 3, 2, 1, 4};
static const unsigned char band_body[] = {
    'D', 'E', 'L', 'T', 1, 0, 0, 0,   /* one band */
    0, 0, 0, 0, 1, 0, 0, 0,           /* y = 0, rows = 1 */
    9, 9, 9, 9,
};
static const unsigned char bad_body[] = {
    'D', 'E', 'L', 'T', 1, 0, 0, 0,
    2, 0, 0, 0, 1, 0, 0, 0,           /* y = 2 in a 2-row frame */
    9, 9, 9, 9,
};

int hios_eagl_current_call(uint32_t op, const void *data, size_t bytes,
                           void *reply, size_t capacity, size_t *received)
{
    const unsigned char *words = data;

    assert(op == EA_READ_RGBA && bytes == HIOS_EAGL_READ_DELTA_REQUEST);
    assert(hios_get32(words) == expected_width && hios_get32(words + 4) == expected_height);
    request_flags = hios_get32(words + 8);
    request_key = (uint64_t)hios_get32(words + 16) << 32 | hios_get32(words + 12);
    request_stride = hios_get32(words + 20);
    ++calls;
    if (model_worker) {
        unsigned char *out = reply;
        assert(capacity >= 12);
        if (model_cpu_write) {
            *model_cpu_write = model_cpu_value; /* CPU writer while reply is in flight. */
            model_cpu_write = NULL;
        }
        if (model_fail_call) {
            model_fail_call = 0;
            return 0;
        }
        if (model_unsolicited_delta) {
            model_unsolicited_delta = 0;
            memcpy(out, HIOS_EAGL_DELTA_PART, 4);
            hios_put32(out + 4, 0);
            *received = 8;
            return 1;
        }
        unsigned hit = 3, oldest = 0;
        for (unsigned i = 0; i < 3; ++i) {
            if (model_cache[i].key == request_key) hit = i;
            if (model_cache[i].used < model_cache[oldest].used) oldest = i;
        }
        if (hit == 3) {
            memcpy(out, HIOS_EAGL_DELTA_FULL, 4);
            memcpy(out + 4, model_pixels, sizeof model_pixels);
            *received = 12;
            model_cache[oldest].key = request_key;
            model_cache[oldest].used = ++model_clock;
            ++model_full;
        } else {
            memcpy(out, HIOS_EAGL_DELTA_PART, 4);
            hios_put32(out + 4, 0);
            *received = 8;
            model_cache[hit].used = ++model_clock;
            ++model_delta;
        }
        return 1;
    }
    size_t size = next_size - (size_t)truncated;
    assert(capacity >= size);
    *received = size;
    memcpy(reply, next_body, size);
    return 1;
}

/* Variant 3 is the only caller; not reached here. */
void hios_eagl_current_scatter(void *base, size_t stride, size_t row)
{
    (void)base;
    (void)stride;
    (void)row;
}

static void assert_model_surface(const unsigned char *surface)
{
    assert(!memcmp(surface, model_pixels, 4));
    assert(!memcmp(surface + 6, model_pixels + 4, 4));
    assert(surface[4] == 0xee && surface[5] == 0xee && surface[10] == 0xee && surface[11] == 0xee);
}

static void test_live_base(void)
{
    expected_width = 1;
    expected_height = 2;
    model_worker = 1;
    memset(model_cache, 0, sizeof model_cache);
    model_clock = 0;
    model_full = model_delta = 0;
    unsigned char surface[12];
    memset(surface, 0xee, sizeof surface);
    assert(hios_eagl_read_surface(surface, 10, 6, 1, 2, 0));
    assert_model_surface(surface);
    uint64_t first_key = request_key;
    assert(first_key);
    surface[9] ^= 0x55; /* CPU write to final valid pixel byte, no seed involved. */
    assert(hios_eagl_read_surface(surface, 10, 6, 1, 2, 0));
    assert_model_surface(surface);
#ifdef HIOS_TEST_NO_SHADOW
    assert(request_key != first_key);
#else
    assert(request_key == first_key); /* private base survives live CPU writes */
#endif
    uint64_t second_key = request_key;
    memset(surface, 0xee, sizeof surface); /* same VA/shape, new contents */
    surface[0] = 0x77;
    assert(hios_eagl_read_surface(surface, 10, 6, 1, 2, 0));
    assert_model_surface(surface);
#ifdef HIOS_TEST_NO_SHADOW
    assert(request_key != second_key);
#else
    assert(request_key == second_key); /* live VA reuse does not affect private pixels */
#endif
    uint64_t stable_key = request_key;
    assert(hios_eagl_read_surface(surface, 10, 6, 1, 2, 0));
    assert_model_surface(surface);
#ifdef HIOS_TEST_NO_SHADOW
    assert(request_key != stable_key && model_full == 4 && model_delta == 0);
#else
    assert(request_key == stable_key && model_full == 1 && model_delta == 3);
#endif
    /* Padding is not a meaningful pixel: changes there neither change key nor
     * get overwritten. The last row's padding is beyond supplied capacity. */
    surface[4] = 0x31;
    assert(hios_eagl_read_surface(surface, 10, 6, 1, 2, 0));
    assert(surface[4] == 0x31 && surface[5] == 0xee && surface[10] == 0xee && surface[11] == 0xee);
#ifndef HIOS_TEST_NO_SHADOW
    assert(request_key == stable_key);
#endif
    /* A CPU writer during the network call touches a band absent from the
     * zero-band reply. Complete shadow->live writeback must still repair it. */
    surface[4] = 0xee;
    model_cpu_write = surface + 7;
    model_cpu_value = 0x91;
    assert(hios_eagl_read_surface(surface, 10, 6, 1, 2, 0));
    assert_model_surface(surface);
    /* A failed transaction invalidates the private base. Force an unsolicited
     * zero-band DELT on the subsequent fresh key and refuse it without writes. */
    unsigned char modified[12];
    model_fail_call = 1;
    memcpy(modified, surface, sizeof modified);
    assert(!hios_eagl_read_surface(surface, 10, 6, 1, 2, 0));
    assert(!memcmp(surface, modified, sizeof modified));
    surface[9] ^= 0x41;
    memcpy(modified, surface, sizeof modified);
    model_unsolicited_delta = 1;
    assert(!hios_eagl_read_surface(surface, 10, 6, 1, 2, 0));
    assert(!memcmp(surface, modified, sizeof modified));
    uint64_t refused_key = request_key;
    assert(hios_eagl_read_surface(surface, 10, 6, 1, 2, 0));
    assert(request_key != refused_key);
    assert(!memcmp(surface, model_pixels, 4) && !memcmp(surface + 6, model_pixels + 4, 4));
    stable_key = request_key;
    model_fail_call = 1;
    memcpy(modified, surface, sizeof modified);
    assert(!hios_eagl_read_surface(surface, 10, 6, 1, 2, 0));
    assert(!memcmp(surface, modified, sizeof modified));
    assert(hios_eagl_read_surface(surface, 10, 6, 1, 2, 0));
    assert(request_key != stable_key); /* failed stream never advances a shadow */
    /* More surfaces than the LRU permits: evicted entries get fresh FULL keys. */
    unsigned char rotating[4][12];
    uint64_t rotation_keys[4];
    for (unsigned s = 0; s < 4; ++s) {
        memset(rotating[s], 0xee, sizeof rotating[s]);
        assert(hios_eagl_read_surface(rotating[s], 10, 6, 1, 2, 0));
        assert_model_surface(rotating[s]);
        rotation_keys[s] = request_key;
    }
    assert(hios_eagl_read_surface(rotating[0], 10, 6, 1, 2, 0));
    assert(request_key != rotation_keys[0]);
    assert_model_surface(rotating[0]);
    /* Conversion identity is part of the local proof, even with identical VA. */
    stable_key = request_key;
    assert(hios_eagl_read_surface(rotating[0], 10, 6, 1, 2, 1));
    assert(request_key != stable_key);
    assert_model_surface(rotating[0]);
    model_worker = 0;
}

static void *concurrent_surface(void *unused)
{
    (void)unused;
    unsigned char surface[12];
    memset(surface, 0xee, sizeof surface);
    for (unsigned i = 0; i < 20; ++i) {
        surface[0] ^= 0x61;
        assert(hios_eagl_read_surface(surface, 10, 6, 1, 2, 0));
        assert_model_surface(surface);
    }
    return NULL;
}

static void test_concurrent_readbacks(void)
{
    model_worker = 1;
    expected_width = 1;
    expected_height = 2;
    pthread_t a, b;
    assert(!pthread_create(&a, NULL, concurrent_surface, NULL));
    assert(!pthread_create(&b, NULL, concurrent_surface, NULL));
    assert(!pthread_join(a, NULL));
    assert(!pthread_join(b, NULL));
    model_worker = 0;
}

/* Production-sized rows with both real padded strides. Alternate surfaces, and
 * compare every byte (including padding and untouched rows) after each reply. */
static void test_padded_multiline_bands(void)
{
    enum { WIDTH = 1125, HEIGHT = 35, ROW = WIDTH * 4, GUARD = 32 };
    const size_t strides[] = {4544, 4608};
    unsigned char *surfaces[2], *expected[2];
    unsigned char *whole = malloc(4 + (size_t)HEIGHT * ROW);
    /* Two bands with untouched rows between them; the second is a short tail. */
    const size_t delta_size = HIOS_EAGL_DELTA_HEADER + 2 * HIOS_EAGL_DELTA_BAND + 19 * ROW;
    unsigned char *delta = malloc(delta_size);
    assert(whole && delta);
    expected_width = WIDTH;
    expected_height = HEIGHT;
    for (unsigned s = 0; s < 2; ++s) {
        size_t size = strides[s] * HEIGHT + GUARD;
        surfaces[s] = malloc(size);
        expected[s] = malloc(size);
        assert(surfaces[s] && expected[s]);
        memset(surfaces[s], 0xee, size);
        memset(expected[s], 0xee, size);
        memcpy(whole, HIOS_EAGL_DELTA_FULL, 4);
        for (unsigned y = 0; y < HEIGHT; ++y) {
            for (unsigned x = 0; x < ROW; ++x) {
                unsigned char value = (unsigned char)(s * 71 + y * 13 + x);
                whole[4 + (size_t)y * ROW + x] = value;
                expected[s][(size_t)y * strides[s] + x] = value;
            }
        }
        next_body = whole;
        next_size = 4 + (size_t)HEIGHT * ROW;
        assert(hios_eagl_read_surface(surfaces[s], strides[s] * HEIGHT,
                                      strides[s], WIDTH, HEIGHT, 0));
        assert(!memcmp(surfaces[s], expected[s], size));
    }
    for (unsigned pass = 0; pass < 4; ++pass) {
        unsigned s = pass % 2;
        size_t size = strides[s] * HEIGHT + GUARD;
        memcpy(delta, HIOS_EAGL_DELTA_PART, 4);
        hios_put32(delta + 4, 2);
        hios_put32(delta + 8, 0);
        hios_put32(delta + 12, 16);
        hios_put32(delta + 16, 32);
        hios_put32(delta + 20, 3);
        size_t at = 24;
        for (unsigned y = 0; y < HEIGHT; ++y) {
            if (y >= 16 && y < 32) continue;
            for (unsigned x = 0; x < ROW; ++x) {
                unsigned char value = (unsigned char)(pass * 59 + y * 17 + x * 3);
                delta[at++] = value;
                expected[s][(size_t)y * strides[s] + x] = value;
            }
        }
        assert(at == delta_size);
        next_body = delta;
        next_size = delta_size;
        assert(hios_eagl_read_surface(surfaces[s], strides[s] * HEIGHT,
                                      strides[s], WIDTH, HEIGHT, 0));
        assert(!memcmp(surfaces[s], expected[s], size));
        assert(request_key != 0);
        assert(request_stride == strides[s]);

        /* The first band is valid but the second is out of bounds. Validate
         * the entire table before applying even that first band's new bytes. */
        delta[24] ^= 0xff;
        hios_put32(delta + 16, HEIGHT - 1);
        assert(!hios_eagl_read_surface(surfaces[s], strides[s] * HEIGHT,
                                       strides[s], WIDTH, HEIGHT, 0));
        assert(!memcmp(surfaces[s], expected[s], size));
        hios_put32(delta + 16, 32);
        truncated = 1;
        assert(!hios_eagl_read_surface(surfaces[s], strides[s] * HEIGHT,
                                       strides[s], WIDTH, HEIGHT, 0));
        assert(!memcmp(surfaces[s], expected[s], size));
        truncated = 0;
        /* A failed transaction invalidates the shadow. The next fresh token
         * must rebuild from FULL before a zero-band DELT can be accepted. */
        memcpy(whole, HIOS_EAGL_DELTA_FULL, 4);
        for (unsigned y = 0; y < HEIGHT; ++y)
            memcpy(whole + 4 + (size_t)y * ROW, expected[s] + (size_t)y * strides[s], ROW);
        next_body = whole;
        next_size = 4 + (size_t)HEIGHT * ROW;
        assert(hios_eagl_read_surface(surfaces[s], strides[s] * HEIGHT,
                                      strides[s], WIDTH, HEIGHT, 0));
        assert(!memcmp(surfaces[s], expected[s], size));

        /* A legal zero-band reply leaves all pixels and padding unchanged. */
        next_body = delta;
        hios_put32(delta + 4, 0);
        next_size = HIOS_EAGL_DELTA_HEADER;
        assert(hios_eagl_read_surface(surfaces[s], strides[s] * HEIGHT,
                                      strides[s], WIDTH, HEIGHT, 0));
        assert(!memcmp(surfaces[s], expected[s], size));
    }
    for (unsigned s = 0; s < 2; ++s) {
        free(surfaces[s]);
        free(expected[s]);
    }
    free(whole);
    free(delta);
}

int main(int argc, char **argv)
{
    if (argc == 2 && !strcmp(argv[1], "--live-base-only")) {
        test_live_base();
        test_concurrent_readbacks();
        return 0;
    }
    /* row 0, the stride padding, row 1, its padding */
    const unsigned char whole[] = {1, 2, 3, 4, 0xee, 0xee, 5, 6, 7, 8, 0xee, 0xee};
    const unsigned char band[] = {9, 9, 9, 9, 0xee, 0xee, 5, 6, 7, 8, 0xee, 0xee};
    const unsigned char other[] = {7, 6, 5, 8, 0xee, 0xee, 3, 2, 1, 4, 0xee, 0xee};
    unsigned char buffer[12], second[12], empty[12];

    memset(empty, 0xee, sizeof empty);
    memcpy(buffer, empty, sizeof buffer);
    /* A destination that cannot hold the picture is refused before any call. */
    assert(!hios_eagl_read_surface(buffer, 9, 6, 1, 2, 1) && calls == 0);
    /* A short reply is a failure and must leave the surface as it was. */
    next_body = full_body;
    next_size = sizeof full_body;
    truncated = 1;
    assert(!hios_eagl_read_surface(buffer, sizeof buffer, 6, 1, 2, 1));
    assert(!memcmp(buffer, empty, sizeof buffer));
    /* The request says which surface this is: its address, its size and its
     * padded stride (here 6 for a 4-byte row, like 4544 for 4500 on the panel). */
    assert(calls == 1 && request_flags == (swap | dest_rows | HIOS_EAGL_READ_DELTA));
    assert(request_key != 0 && request_stride == 6);
    truncated = 0;
    /* A whole frame with the marker. */
    assert(hios_eagl_read_surface(buffer, sizeof buffer, 6, 1, 2, 1));
    assert(!memcmp(buffer, whole, sizeof buffer));
    assert(calls == 2);
    /* Bands only: row 0 comes from the reply, row 1 is left as it was. */
    next_body = band_body;
    next_size = sizeof band_body;
    assert(hios_eagl_read_surface(buffer, sizeof buffer, 6, 1, 2, 1));
    assert(!memcmp(buffer, band, sizeof buffer));
    assert(calls == 3 && request_flags == (swap | dest_rows | HIOS_EAGL_READ_DELTA));
    /* A band outside the frame is refused, and the surface is left alone: the
     * next delta would otherwise assume bytes that never arrived. */
    next_body = bad_body;
    next_size = sizeof bad_body;
    assert(!hios_eagl_read_surface(buffer, sizeof buffer, 6, 1, 2, 1));
    assert(!memcmp(buffer, band, sizeof buffer));
    assert(calls == 4);
    /* Another surface is just another key: still one delta request, and its
     * own whole frame comes back because the worker has no base for it. */
    next_body = other_body;
    next_size = sizeof other_body;
    memset(second, 0xee, sizeof second);
    assert(hios_eagl_read_surface(second, sizeof second, 6, 1, 2, 1));
    assert(!memcmp(second, other, sizeof second));
    assert(calls == 5 && request_key != 0);
    test_padded_multiline_bands();
    test_live_base();
    test_concurrent_readbacks();
}
