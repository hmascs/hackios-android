/* SPDX-License-Identifier: GPL-2.0-or-later */
#include "eagl-guest-client.h"
#include "eagl-pixel-transfer.h"
#include "eagl-guest-timing.h"
#include <string.h>
#include <assert.h>
#include <pthread.h>
#include <sched.h>
#include <stdatomic.h>

extern void glPixelStorei(unsigned, int);
extern void glGetVertexAttribiv(unsigned, unsigned, int *);
extern void glEnableVertexAttribArray(unsigned);
extern void glDisableVertexAttribArray(unsigned);
extern void glDrawArrays(unsigned, int, int);
extern void glDrawElements(unsigned, int, unsigned, const void *);
extern void glUniform2fv(int, int, const float *);
extern void glGetIntegerv(unsigned, int *);
extern unsigned glGetError(void);
extern void glTexImage2D(unsigned, int, int, int, int, int, unsigned, unsigned, const void *);

/* The mocked transport replaces eagl-guest-client.c, including its timing storage. */
struct hios_call_timing hios_call_timing;
static struct hios_eagl_client client = {.fd = 1};
static atomic_uint active;

int hios_eagl_call(struct hios_eagl_client *c, uint32_t slot, uint32_t op,
                   const void *data, size_t bytes, void *reply, size_t capacity,
                   uint32_t *status, size_t *received)
{
    assert(c == &client && slot == 1);
    assert(atomic_fetch_add(&active, 1) == 0);
    sched_yield(); /* Encourage context switches while transaction is active. */
    ++c->seq;
    *status = EA_OK;
    if (op == EA_TEXTURE_BASE) {
        static const unsigned char padded[] = {53, 192, 19, 71, 201, 62, 99, 3};
        static const unsigned char overlapping[] = {53, 192, 19, 71, 19, 71, 201, 62};
        const unsigned char *expected = c->seq == 2003 ? overlapping : padded;
        const unsigned char *p = data;
        assert(bytes == 12 + sizeof(padded) && capacity == 0);
        assert(hios_get32(p) == 2 && hios_get32(p + 4) == 2 && hios_get32(p + 8) == HIOS_LA8);
        assert(memcmp(p + 12, expected, sizeof(padded)) == 0);
        *received = 0;
    } else if (op == EA_TEXTURE_MIP) {
        const unsigned char *p = data;
        assert(bytes == 18 && capacity == 0);
        assert(hios_get32(p) == 1 && hios_get32(p + 4) == 1 && hios_get32(p + 8) == HIOS_LA8);
        assert(hios_get32(p + 12) == 1 && p[16] == 53 && p[17] == 192);
        *received = 0;
    } else if (op == EA_UNIFORM_VECTOR) {
        const unsigned char *p = data;
        assert(bytes == 28 && capacity == 0);
        const uint32_t expect[] = {7, 2, 2, 0x3e000000, 0x3e800000, 0x3f200000, 0x3f000000};
        for (unsigned i = 0; i < sizeof expect / sizeof *expect; ++i)
            assert(hios_get32(p + i * 4) == expect[i]);
        *received = 0;
    } else if (op == EA_DRAW_NO_ARRAYS) {
        const unsigned char *p = data;
        assert(bytes == 12 && capacity == 0);
        assert(hios_get32(p) == 4 && hios_get32(p + 4) == 7 && hios_get32(p + 8) == 4);
        *received = 0;
    } else {
        assert(op == EA_glGetError && bytes == 0 && capacity == 4);
        hios_put32(reply, 0);
        *received = 4;
    }
    assert(atomic_fetch_sub(&active, 1) == 1);
    return 1;
}

static void *render_thread(void *unused)
{
    (void)unused;
    hios_eagl_bind_thread(&client, 1);
    int row = -1, align = -1;
    glGetIntegerv(0x0cf2, &row);
    glGetIntegerv(0x0cf5, &align);
    assert(row == 1152 && align == 1);
    int array = -1;
    glGetVertexAttribiv(5, 0x8622, &array);
    assert(array == 1);
    glGetVertexAttribiv(6, 0x8622, &array);
    assert(array == 0);
    /* Second row starts at the unpack stride, not after the first four bytes. */
    static const unsigned char la[1152 * 2 + 4] = {
        53, 192, 19, 71, 0xff, 0xee, [1152 * 2] = 201, 62, 99, 3,
    };
    glTexImage2D(0x0de1, 0, 0x190a, 2, 2, 0, 0x190a, 0x1401, la);
    for (unsigned i = 0; i < 1000; ++i) assert(glGetError() == 0);
    return NULL;
}

int main(void)
{
    pthread_t thread;
    hios_eagl_bind_thread(&client, 1);
    glPixelStorei(0x0cf2, 1152);
    glPixelStorei(0x0cf5, 1);
    glEnableVertexAttribArray(5);
    glEnableVertexAttribArray(6);
    glDisableVertexAttribArray(6);
    assert(pthread_create(&thread, NULL, render_thread, NULL) == 0);
    render_thread(NULL);
    assert(pthread_join(thread, NULL) == 0);
    assert(client.seq == 2002);
    /* GL unpack row length controls the row step, not the copied width. */
    static const unsigned char la[] = {53, 192, 19, 71, 201, 62};
    glPixelStorei(0x0cf2, 1);
    glTexImage2D(0x0de1, 0, 0x190a, 2, 2, 0, 0x190a, 0x1401, la);
    assert(client.seq == 2003);
    glTexImage2D(0x0de1, 1, 0x190a, 1, 1, 0, 0x190a, 0x1401, la);
    assert(client.seq == 2004);
    const float uv[] = {.125f, .25f, .625f, .5f};
    glUniform2fv(7, 2, uv);
    assert(client.seq == 2005);
    /* Count zero is a guest GL no-op: it must not pack, allocate or submit. */
    glDrawArrays(4, 7, 0);
    glDrawElements(4, 0, 0x1403, NULL);
    assert(client.seq == 2005);
    glDisableVertexAttribArray(5);
    glDrawArrays(4, 7, 4);
    assert(client.seq == 2006);
}
