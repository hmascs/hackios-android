/* SPDX-License-Identifier: GPL-2.0-or-later */
#include "eagl-pixel-transfer.h"
#include <string.h>

static size_t pixel_width(enum hios_pixel_format format)
{
    switch (format) {
    case HIOS_RGBA8: case HIOS_BGRA8: return 4;
    case HIOS_ALPHA8: return 1;
    case HIOS_LA8: return 2;
    case HIOS_RGBA16F: return 8;
    default: return 0;
    }
}

size_t hios_pixel_packed_size(uint32_t width, uint32_t height, enum hios_pixel_format format)
{
    if (!width || !height || width > 4096 || height > 4096) return 0;
    size_t size = (size_t)width * height * pixel_width(format);
    return size <= HIOS_PIXEL_MAX_BYTES ? size : 0;
}

static int room(size_t capacity, size_t stride, size_t row, uint32_t height)
{
    return stride >= row && capacity >= row && (height - 1) <= (capacity - row) / stride;
}

static void swap_red_blue(unsigned char *dest, const unsigned char *src, uint32_t width)
{
    for (uint32_t x = 0; x < width; ++x) {
        unsigned char red = src[0];
        dest[0] = src[2];
        dest[1] = src[1];
        dest[2] = red;
        dest[3] = src[3];
        dest += 4;
        src += 4;
    }
}

/* The same channel swap, one 32-bit pixel at a time: two masked shifts and an
 * or instead of four byte loads and four byte stores per pixel. The guest is
 * an emulated ARM, so this is a per-instruction saving on 2.7 M pixels. */
static void swap_red_blue_words(unsigned char *dest, const unsigned char *src, size_t bytes)
{
    for (size_t at = 0; at + 4 <= bytes; at += 4) {
        uint32_t value, swapped;
        memcpy(&value, src + at, 4);
        swapped = (value & UINT32_C(0xFF00FF00)) | ((value & UINT32_C(0xFF)) << 16) |
                  ((value >> 16) & UINT32_C(0xFF));
        memcpy(dest + at, &swapped, 4);
    }
}

int hios_pixel_pack(void *out, size_t capacity, const void *iosurface, size_t source_size,
                    size_t stride, uint32_t width, uint32_t height, enum hios_pixel_format format)
{
    size_t total = hios_pixel_packed_size(width, height, format);
    if (!out || !iosurface || !total || capacity < total) return 0;
    size_t row = total / height;
    /* GL_UNPACK_ROW_LENGTH sets the source step independently of the
     * requested rectangle width. Reads can overlap; destination rows cannot. */
    if (!stride || source_size < row || height - 1 > (source_size - row) / stride) return 0;
    const unsigned char *source = iosurface;
    unsigned char *dest = out;
    for (uint32_t y = 0; y < height; ++y) {
        if (format == HIOS_BGRA8) swap_red_blue_words(dest + y * row, source + y * stride, row);
        else memcpy(dest + y * row, source + y * stride, row);
    }
    return 1;
}

int hios_pixel_unpack_bgra(void *iosurface, size_t capacity, size_t stride,
                           const void *rgba, size_t size, uint32_t width, uint32_t height, int invert)
{
    size_t total = hios_pixel_packed_size(width, height, HIOS_RGBA8);
    if (!total || !iosurface || !rgba || size != total || (invert != 0 && invert != 1)) return 0;
    size_t row = total / height;
    if (!room(capacity, stride, row, height)) return 0;
    unsigned char *dest = iosurface;
    const unsigned char *source = rgba;
    for (uint32_t y = 0; y < height; ++y) {
        uint32_t out_y = invert ? height - 1 - y : y;
        swap_red_blue(dest + out_y * stride, source + y * row, width);
    }
    return 1;
}

/* Same result as hios_pixel_unpack_bgra, word at a time (see above). */
int hios_pixel_unpack_bgra_words(void *iosurface, size_t capacity, size_t stride,
                                 const void *rgba, size_t size, uint32_t width, uint32_t height, int invert)
{
    size_t total = hios_pixel_packed_size(width, height, HIOS_RGBA8);
    if (!total || !iosurface || !rgba || size != total || (invert != 0 && invert != 1)) return 0;
    size_t row = total / height;
    if (!room(capacity, stride, row, height)) return 0;
    unsigned char *dest = iosurface;
    const unsigned char *source = rgba;
    for (uint32_t y = 0; y < height; ++y) {
        uint32_t out_y = invert ? height - 1 - y : y;
        swap_red_blue_words(dest + out_y * stride, source + y * row, row);
    }
    return 1;
}

/* Rows already converted and ordered by the host: row y of `wire` belongs at
 * offset y * stride of the IOSurface. */
int hios_pixel_copy_rows(void *iosurface, size_t capacity, size_t stride,
                         const void *wire, size_t size, uint32_t width, uint32_t height)
{
    size_t total = hios_pixel_packed_size(width, height, HIOS_RGBA8);
    if (!total || !iosurface || !wire || size != total) return 0;
    size_t row = total / height;
    if (!room(capacity, stride, row, height)) return 0;
    unsigned char *dest = iosurface;
    const unsigned char *source = wire;
    for (uint32_t y = 0; y < height; ++y) memcpy(dest + y * stride, source + y * row, row);
    return 1;
}

/* Host side: the reply payload for one readback, from glReadPixels' RGBA,
 * bottom-up buffer to BGRA in destination row order. Native code, so this is
 * one pass over 10.9 MB instead of 2.7 M byte operations in the guest. */
void hios_pixels_convert(void *out, const void *rgba, uint32_t width, uint32_t height,
                         int swap, int flip)
{
    size_t row = (size_t)width * 4;
    unsigned char *dest = out;
    const unsigned char *source = rgba;
    for (uint32_t y = 0; y < height; ++y) {
        const unsigned char *from = source + (size_t)(flip ? height - 1 - y : y) * row;
        if (swap) swap_red_blue_words(dest + (size_t)y * row, from, row);
        else memcpy(dest + (size_t)y * row, from, row);
    }
}
