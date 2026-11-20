/* SPDX-License-Identifier: GPL-2.0-or-later */
/* Off-VM check that the four readback variants of eagl-guest-surface.c put
 * the same bytes into the IOSurface. Builds eagl-pixel-transfer.c alone; no
 * Darwin API is involved in the pixel paths. */
#include "eagl-pixel-transfer.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static uint32_t seed = 0x12345678;
static unsigned char random_byte(void)
{
    seed = seed * UINT32_C(1664525) + UINT32_C(1013904223);
    return (unsigned char)(seed >> 24);
}

/* What a full-frame readback used to do before the delta protocol: one
 * contiguous wire buffer, RGBA rows bottom-up, byte-at-a-time channel swap and
 * optional row flip. */
static void reference(unsigned char *surface, size_t stride, const unsigned char *wire,
                      uint32_t width, uint32_t height, int invert, size_t row)
{
    for (uint32_t y = 0; y < height; ++y) {
        uint32_t out_y = invert ? height - 1 - y : y;
        const unsigned char *from = wire + (size_t)y * row;
        unsigned char *to = surface + (size_t)out_y * stride;
        for (uint32_t x = 0; x < width; ++x) {
            unsigned char red = from[0];
            to[0] = from[2];
            to[1] = from[1];
            to[2] = red;
            to[3] = from[3];
            from += 4;
            to += 4;
        }
    }
}

static void trial(uint32_t width, uint32_t height, size_t stride, int invert)
{
    size_t row = (size_t)width * 4;
    size_t bytes = row * height;
    size_t capacity = stride * (height - 1) + row;
    unsigned char *wire = malloc(bytes), *converted = malloc(bytes);
    unsigned char *want = malloc(capacity), *got = malloc(capacity);
    assert(wire && converted && want && got);
    for (size_t i = 0; i < bytes; ++i) wire[i] = random_byte();
    memset(want, 0x5a, capacity);
    reference(want, stride, wire, width, height, invert, row);

    /* v0: the same function the original build used. */
    memset(got, 0x5a, capacity);
    assert(hios_pixel_unpack_bgra(got, capacity, stride, wire, bytes, width, height, invert));
    assert(!memcmp(want, got, capacity));

    /* v1: eight bytes at a time. */
    memset(got, 0x5a, capacity);
    assert(hios_pixel_unpack_bgra_words(got, capacity, stride, wire, bytes, width, height, invert));
    assert(!memcmp(want, got, capacity));

    /* v2: the host converts to BGRA in destination row order. */
    hios_pixels_convert(converted, wire, width, height, 1, invert);
    memset(got, 0x5a, capacity);
    assert(hios_pixel_copy_rows(got, capacity, stride, converted, bytes, width, height));
    assert(!memcmp(want, got, capacity));

    /* v3: the same reply rows read straight into the mapping. */
    memset(got, 0x5a, capacity);
    for (uint32_t y = 0; y < height; ++y) memcpy(got + (size_t)y * stride, converted + (size_t)y * row, row);
    assert(!memcmp(want, got, capacity));

    printf("  %ux%u stride=%zu invert=%d: v0=v1=v2=v3 (%zu bytes)\n", width, height, stride, invert, bytes);
    free(wire); free(converted); free(want); free(got);
}

int main(void)
{
    trial(4, 3, 16, 0);
    trial(5, 3, 20, 0);        /* odd pixel count per row */
    trial(5, 3, 24, 1);        /* padded stride and rows flipped */
    trial(1125, 2436, 4500, 0);  /* the panel geometry with no padding */
    trial(1125, 2436, 4544, 1);  /* the surface stride the guest really has */
    puts("pixel variants agree");
    return 0;
}
