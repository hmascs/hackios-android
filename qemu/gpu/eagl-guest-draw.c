/* SPDX-License-Identifier: GPL-2.0-or-later */
#include "eagl-client-arrays.h"
#include "eagl-guest-client.h"

extern void *malloc(size_t);
extern void free(void *);
extern _Noreturn void abort(void);
extern int snprintf(char *, size_t, const char *, ...);
extern long write(int, const void *, size_t);

static _Noreturn void draw_failed(const char *stage, unsigned mode, int first, int count,
                                  unsigned type, unsigned mask)
{
    char line[192];
    int n = snprintf(line, sizeof line,
        "hackios-eagl: draw %s mode=%#x first=%d count=%d index_type=%#x enabled=%#x\n",
        stage, mode, first, count, type, mask);
    if (n > 0 && (size_t)n < sizeof line) (void)write(2, line, (size_t)n);
    abort();
}

/* The single experimental context keeps attributes across thread handoff.
 * Client pointers must remain valid until draw returns, just as in OSMesa. */
static struct hios_client_array attributes[HIOS_ARRAY_SLOTS];
static uint32_t enabled;

void glVertexAttribPointer(unsigned index, int size, unsigned type, unsigned char normalized,
                           int stride, const void *pointer)
{
    if (index >= HIOS_ARRAY_SLOTS || size < 1 || size > 4 || stride < 0) abort();
    hios_eagl_lock();
    attributes[index] = (struct hios_client_array){index, (uint32_t)size, type, !!normalized,
        pointer, HIOS_ARRAY_LIMIT, (size_t)stride};
    hios_eagl_unlock();
}

void glEnableVertexAttribArray(unsigned index)
{
    if (index >= HIOS_ARRAY_SLOTS) abort();
    hios_eagl_lock();
    enabled |= UINT32_C(1) << index;
    hios_eagl_unlock();
}

void glDisableVertexAttribArray(unsigned index)
{
    if (index >= HIOS_ARRAY_SLOTS) abort();
    hios_eagl_lock();
    enabled &= ~(UINT32_C(1) << index);
    hios_eagl_unlock();
}

void glGetVertexAttribiv(unsigned index, unsigned name, int *value)
{
    /* Redwood temporarily enables arrays for its own downsample shader and
     * restores the guest state. Host staging VAOs do not hold that state. */
    if (!value || index >= HIOS_ARRAY_SLOTS || name != 0x8622) abort(); /* GL_VERTEX_ATTRIB_ARRAY_ENABLED */
    hios_eagl_lock();
    *value = (int)((enabled >> index) & 1u);
    hios_eagl_unlock();
}

static void submit(unsigned mode, int first, int count, unsigned type, const void *indices, int indexed)
{
    if (!count) return;
    if (mode > 6 || first < 0 || count < 0 || count > 65536 || (indexed && !indices))
        draw_failed("geometry", mode, first, count, type, 0);
    size_t index_size = type == 0x1401 ? 1 : type == 0x1403 ? 2 : type == 0x1405 ? 4 : 0;
    if (indexed && !index_size) draw_failed("index format", mode, first, count, type, 0);
    struct hios_client_array arrays[HIOS_ARRAY_SLOTS];
    size_t n = 0, bytes, received;
    hios_eagl_lock();
    uint32_t mask = enabled;
    for (unsigned i = 0; i < HIOS_ARRAY_SLOTS; ++i)
        if ((mask >> i) & 1) arrays[n++] = attributes[i];
    hios_eagl_unlock();
    if (!n && !indexed) {
        unsigned char packet[12];
        hios_put32(packet, mode);
        hios_put32(packet + 4, (uint32_t)first);
        hios_put32(packet + 8, (uint32_t)count);
        if (!hios_eagl_current_call(EA_DRAW_NO_ARRAYS, packet, sizeof packet, NULL, 0, &received) || received)
            draw_failed("remote procedural draw", mode, first, count, type, mask);
        return;
    }
    void *packet = malloc(HIOS_ARRAY_LIMIT);
    if (!packet) draw_failed("allocation", mode, first, count, type, mask);
    int encoded = indexed ? hios_array_encode(packet, HIOS_ARRAY_LIMIT, &bytes, mode, (uint32_t)count,
        type, indices, (size_t)count * index_size, arrays, n) :
        hios_array_encode_sequential(packet, HIOS_ARRAY_LIMIT, &bytes, mode, (uint32_t)first,
        (uint32_t)count, arrays, n);
    int ok = encoded && hios_eagl_current_call(EA_DRAW_ARRAYS, packet, bytes, NULL, 0, &received) && !received;
    free(packet);
    if (!ok) draw_failed(encoded ? "remote arrays" : "client array packing", mode, first, count, type, mask);
}

void glDrawArrays(unsigned mode, int first, int count) { submit(mode, first, count, 0, NULL, 0); }
void glDrawElements(unsigned mode, int count, unsigned type, const void *indices)
{ submit(mode, 0, count, type, indices, 1); }
