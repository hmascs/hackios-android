/* SPDX-License-Identifier: GPL-2.0-or-later */
#include "eagl-client-arrays.h"
#include <string.h>

#define HEADER 24u
#define DESC 20u
#define MAX_ITEMS 65536u

static uint32_t little(const unsigned char *p, unsigned n)
{
    uint32_t v = 0;
    for (unsigned i = 0; i < n; ++i) v |= (uint32_t)p[i] << (8 * i);
    return v;
}

static void put(unsigned char *p, uint32_t v, unsigned n)
{
    for (unsigned i = 0; i < n; ++i) p[i] = (unsigned char)(v >> (8 * i));
}

static unsigned index_size(uint32_t type)
{
    switch (type) {
    case 0x1401: return 1; /* GL_UNSIGNED_BYTE */
    case 0x1403: return 2; /* GL_UNSIGNED_SHORT */
    case 0x1405: return 4; /* GL_UNSIGNED_INT */
    default: return 0;
    }
}

static unsigned array_size(uint32_t components, uint32_t type)
{
    if (components < 1 || components > 4) return 0;
    switch (type) {
    case 0x1400: case 0x1401: return components; /* BYTE */
    case 0x1402: case 0x1403: case 0x140b: return components * 2; /* SHORT/HALF_FLOAT */
    case 0x1406: return components * 4; /* FLOAT */
    default: return 0;
    }
}

static int draw_shape(uint32_t mode, uint32_t count)
{
    /* GL_POINTS through GL_TRIANGLE_FAN; GL itself discards incomplete tails. */
    return mode <= 6 && count && count <= MAX_ITEMS;
}

static int encode(void *out, size_t capacity, size_t *written, uint32_t mode,
                  uint32_t count, uint32_t index_type, const unsigned char *idx, size_t index_bytes,
                  uint32_t seq_first, const struct hios_client_array *arrays, size_t nr_arrays)
{
    if (!written) return 0;
    *written = 0;
    unsigned isize = index_size(index_type);
    if (!out || !arrays || nr_arrays < 1 || nr_arrays > HIOS_ARRAY_SLOTS ||
        !draw_shape(mode, count) || !isize || index_bytes != (size_t)count * isize) return 0;
    /* Primitive restart tokens are not fully specified by this wire format;
     * require the caller to have fixed primitive restart disabled. */
    uint32_t first, last;
    if (!idx) {
        if (seq_first > UINT32_MAX - (count - 1)) return 0;
        first = seq_first;
        last = seq_first + count - 1;
    } else {
        first = UINT32_MAX;
        last = 0;
        for (uint32_t i = 0; i < count; ++i) {
            uint32_t v = little(idx + (size_t)i * isize, isize);
            if (first > v) first = v;
            if (last < v) last = v;
        }
    }
    /* Preserve the original vertex indices, including gl_VertexID. HAA1 has
     * no base field, so its staged VBO must retain slots 0..last. Unreferenced
     * prefix slots are zero-filled below: never read guest memory before the
     * first referenced vertex. Large bases fail within the existing vertex/
     * packet budgets instead of silently rebasing or truncating an index. */
    if (last >= MAX_ITEMS) return 0;
    uint32_t nr_vertices = last + 1, used = 0;
    size_t total = HEADER + DESC * nr_arrays + index_bytes;
    for (size_t i = 0; i < nr_arrays; ++i) {
        const struct hios_client_array *a = &arrays[i];
        unsigned packed = array_size(a->components, a->type);
        size_t stride = a->stride ? a->stride : packed;
        if (!packed || !a->data || a->slot >= HIOS_ARRAY_SLOTS || ((used >> a->slot) & 1) ||
            a->normalized > 1 || stride < packed || a->bytes < packed || last > (a->bytes - packed) / stride)
            return 0;
        used |= 1u << a->slot;
        total += (size_t)packed * nr_vertices;
        if (total > HIOS_ARRAY_LIMIT) return 0;
    }
    if (total > capacity) return 0;
    unsigned char *p = out;
    put(p, HIOS_ARRAY_FORMAT, 4);
    put(p + 4, mode, 4);
    put(p + 8, count, 4);
    put(p + 12, index_type, 4);
    put(p + 16, nr_vertices, 4);
    put(p + 20, (uint32_t)nr_arrays, 4);
    unsigned char *pixels = p + HEADER + DESC * nr_arrays;
    for (uint32_t i = 0; i < count; ++i)
        put(pixels + (size_t)i * isize, idx ? little(idx + (size_t)i * isize, isize) : seq_first + i, isize);
    pixels += index_bytes;
    for (size_t i = 0; i < nr_arrays; ++i) {
        const struct hios_client_array *a = &arrays[i];
        unsigned packed = array_size(a->components, a->type);
        size_t stride = a->stride ? a->stride : packed;
        unsigned char *desc = p + HEADER + i * DESC;
        put(desc, a->slot, 4);
        put(desc + 4, a->components, 4);
        put(desc + 8, a->type, 4);
        put(desc + 12, a->normalized, 4);
        put(desc + 16, packed * nr_vertices, 4);
        const unsigned char *src = a->data;
        const size_t prefix = (size_t)first * packed;
        memset(pixels, 0, prefix);
        pixels += prefix;
        for (uint32_t j = first; j < nr_vertices; ++j) {
            memcpy(pixels, src + (size_t)j * stride, packed);
            pixels += packed;
        }
    }
    *written = total;
    return 1;
}

int hios_array_encode(void *out, size_t capacity, size_t *written, uint32_t mode,
                      uint32_t count, uint32_t index_type, const void *indices, size_t index_bytes,
                      const struct hios_client_array *arrays, size_t nr_arrays)
{
    if (!indices) {
        if (written) *written = 0;
        return 0;
    }
    return encode(out, capacity, written, mode, count, index_type, indices, index_bytes, 0, arrays, nr_arrays);
}

int hios_array_encode_sequential(void *out, size_t capacity, size_t *written, uint32_t mode,
                                 uint32_t first, uint32_t count,
                                 const struct hios_client_array *arrays, size_t nr_arrays)
{
    return encode(out, capacity, written, mode, count, 0x1403, NULL, (size_t)count * 2, first, arrays, nr_arrays);
}

int hios_array_decode(const void *packet, size_t bytes, struct hios_array_draw *draw)
{
    if (!packet || !draw || bytes < HEADER || bytes > HIOS_ARRAY_LIMIT) return 0;
    const unsigned char *p = packet;
    if (little(p, 4) != HIOS_ARRAY_FORMAT) return 0;
    struct hios_array_draw result = {0};
    result.mode = little(p + 4, 4);
    result.count = little(p + 8, 4);
    result.index_type = little(p + 12, 4);
    result.vertex_count = little(p + 16, 4);
    result.nr_arrays = little(p + 20, 4);
    unsigned isize = index_size(result.index_type);
    if (!draw_shape(result.mode, result.count) || !isize || result.vertex_count < 1 ||
        result.vertex_count > MAX_ITEMS || !result.nr_arrays || result.nr_arrays > HIOS_ARRAY_SLOTS) return 0;
    size_t pos = HEADER + DESC * result.nr_arrays;
    size_t idx_bytes = (size_t)result.count * isize;
    if (pos > bytes || idx_bytes > bytes - pos) return 0;
    result.indices = p + pos;
    for (uint32_t i = 0; i < result.count; ++i)
        if (little(result.indices + (size_t)i * isize, isize) >= result.vertex_count) return 0;
    pos += idx_bytes;
    uint32_t used = 0;
    for (uint32_t i = 0; i < result.nr_arrays; ++i) {
        struct hios_client_array *a = &result.arrays[i];
        const unsigned char *desc = p + HEADER + i * DESC;
        a->slot = little(desc, 4);
        a->components = little(desc + 4, 4);
        a->type = little(desc + 8, 4);
        a->normalized = little(desc + 12, 4);
        unsigned packed = array_size(a->components, a->type);
        a->bytes = little(desc + 16, 4);
        if (!packed || a->normalized > 1 || a->slot >= HIOS_ARRAY_SLOTS || ((used >> a->slot) & 1) ||
            a->bytes != (size_t)packed * result.vertex_count || a->bytes > bytes - pos) return 0;
        used |= 1u << a->slot;
        a->stride = packed;
        a->data = p + pos;
        pos += a->bytes;
    }
    if (pos != bytes) return 0;
    *draw = result;
    return 1;
}
