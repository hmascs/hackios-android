/* SPDX-License-Identifier: GPL-2.0-or-later */
#ifndef HIOS_EAGL_CLIENT_ARRAYS_H
#define HIOS_EAGL_CLIENT_ARRAYS_H
#include <stddef.h>
#include <stdint.h>

/* CoreAnimation's client arrays are guest addresses. Read only the referenced
 * min..max span, never transmit a pointer. HAA1 preserves original indices by
 * staging a bounded zero prefix; this retains gl_VertexID with client-memory
 * attributes. Mixed VBO/integer/divisor state is not covered by this format. */
#define HIOS_ARRAY_SLOTS 16u
#define HIOS_ARRAY_LIMIT (4u * 1024u * 1024u)
#define HIOS_ARRAY_FORMAT UINT32_C(0x31414148) /* HAA1 (little endian) */

struct hios_client_array {
    uint32_t slot, components, type, normalized;
    const void *data;
    size_t bytes, stride;
};

struct hios_array_draw {
    uint32_t mode, count, index_type, vertex_count, nr_arrays;
    const unsigned char *indices;
    struct hios_client_array arrays[HIOS_ARRAY_SLOTS];
};

/* Array sizes from the calling bridge must describe readable memory. Packet
 * functions enforce format, overlap-independent indices, and resource bounds. */
int hios_array_encode(void *out, size_t capacity, size_t *written, uint32_t mode,
                      uint32_t count, uint32_t index_type, const void *indices, size_t index_bytes,
                      const struct hios_client_array *arrays, size_t nr_arrays);
/* Sequential client-memory draw, preserving first and gl_VertexID. Returns 0
 * with *written=0 for invalid/zero count or when the original index range plus
 * its zero prefix exceeds the 65536-vertex or HIOS_ARRAY_LIMIT byte budget;
 * the caller must report the failure, never retry with rebased indices. */
int hios_array_encode_sequential(void *out, size_t capacity, size_t *written, uint32_t mode,
                                 uint32_t first, uint32_t count,
                                 const struct hios_client_array *arrays, size_t nr_arrays);
int hios_array_decode(const void *packet, size_t bytes, struct hios_array_draw *draw);

/* Host EGL thread only. Uses current shader, textures and framebuffer. The
 * caller must exclude nonzero divisors; original vertex indices are retained. */
int hios_gles_draw_client_arrays(const void *packet, size_t bytes);
#endif
