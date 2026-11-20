/* SPDX-License-Identifier: GPL-2.0-or-later */
#ifndef HIOS_EAGL_PIXEL_TRANSFER_H
#define HIOS_EAGL_PIXEL_TRANSFER_H
#include <stdint.h>
#include <stddef.h>

/* IOSurface rows may have padding. These checked copies strip it on upload
 * and leave it intact on readback. No GL objects or addresses cross the wire. */
enum hios_pixel_format { HIOS_RGBA8 = 1, HIOS_BGRA8, HIOS_ALPHA8, HIOS_RGBA16F, HIOS_LA8 };
#define HIOS_PIXEL_MAX_BYTES (32u * 1024u * 1024u)
#define HIOS_TEXTURE_MAX_LEVEL 14u
size_t hios_pixel_packed_size(uint32_t width, uint32_t height, enum hios_pixel_format);
int hios_pixel_pack(void *out, size_t capacity, const void *iosurface, size_t source_size,
                    size_t source_stride, uint32_t width, uint32_t height, enum hios_pixel_format);
int hios_pixel_unpack_bgra(void *iosurface, size_t capacity, size_t stride,
                           const void *rgba, size_t size, uint32_t width, uint32_t height, int invert);
/* Measurement variants: same result as hios_pixel_unpack_bgra. */
int hios_pixel_unpack_bgra_words(void *iosurface, size_t capacity, size_t stride,
                                 const void *rgba, size_t size, uint32_t width, uint32_t height, int invert);
int hios_pixel_copy_rows(void *iosurface, size_t capacity, size_t stride,
                         const void *wire, size_t size, uint32_t width, uint32_t height);

/* Host side: BGRA (swap) and/or destination row order (flip) for a reply. */
void hios_pixels_convert(void *out, const void *rgba, uint32_t width, uint32_t height,
                         int swap, int flip);

/* Guest: destination must remain locked throughout the readback. */
int hios_eagl_read_surface(void *base, size_t capacity, size_t stride,
                           uint32_t width, uint32_t height, int invert);

/* Host EGL thread only. Upload to the bound 2D texture, read from the bound
 * framebuffer. Requires no PBO and an RGBA8 framebuffer for readback. */
int hios_gles_upload_texture(const void *packed, size_t bytes, uint32_t w, uint32_t h,
                             enum hios_pixel_format format);
int hios_gles_upload_texture_level(const void *packed, size_t bytes, uint32_t w, uint32_t h,
                                   enum hios_pixel_format format, unsigned level);
int hios_gles_read_frame_rgba(void *out, size_t capacity, uint32_t w, uint32_t h);
#endif
