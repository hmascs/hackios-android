/* SPDX-License-Identifier: GPL-2.0-or-later */
#ifndef HIOS_EAGL_WIRE_H
#define HIOS_EAGL_WIRE_H
#include <stddef.h>
#include <stdint.h>

/* Protocol v1 framing; fields are byte-encoded little endian, no pointers.
 * Requests and replies are ordered; each has one bounded payload. */
#define HIOS_EAGL_REQUEST UINT32_C(0x31514748) /* HGQ1 */
#define HIOS_EAGL_REPLY   UINT32_C(0x31524748) /* HGR1 */
#define HIOS_EAGL_HEADER  20u
#define HIOS_EAGL_MAX     (32u * 1024 * 1024 + 32u)
#define HIOS_EAGL_CONTEXTS 8u

enum hios_eagl_op {
    EA_OPEN = 1, EA_CLOSE, EA_STRING, EA_SHADER_SOURCE, EA_SHADER_IV,
    EA_PROGRAM_IV, EA_INFO_LOG, EA_BIND_ATTRIB, EA_UNIFORM_LOCATION,
    EA_CREATE_OBJECTS, EA_DELETE_OBJECTS, EA_UNIFORM_VECTOR, EA_TEXTURE_BASE,
    EA_DRAW_ARRAYS, EA_READ_RGBA, EA_INTEGER, EA_END_SESSION,
    EA_FENCE, EA_CLIENT_WAIT, EA_DELETE_FENCE, EA_FRAMEBUFFER_ATTACHMENT, EA_FLOAT_STATE,
    EA_TEXTURE_LEVEL_QUERY, EA_TEXTURE_QUERY, EA_TEXTURE_MIP, EA_DRAW_NO_ARRAYS,
    EA_BATCH_CAPS = 27, EA_BATCH_EXEC = 28, /* experimental, explicit HTB1 negotiation */
#define GL_VOID(id, name, n, ...) EA_##name = id,
#define GL_UINT(id, name, n, ...) EA_##name = id,
#include "eagl-scalars.def"
#undef GL_VOID
#undef GL_UINT
};
/* EA_READ_RGBA request: {width, height, flags}. A request of the original
 * eight bytes means flags 0. With HIOS_EAGL_READ_SWAP the reply's pixels are
 * BGRA, and with HIOS_EAGL_READ_DEST_ROWS they are in destination (top-down
 * IOSurface) row order already, so the guest need not convert them at all. */
#define HIOS_EAGL_READ_REQUEST   12u   /* {width, height, flags} */
#define HIOS_EAGL_READ_SWAP      UINT32_C(1)
#define HIOS_EAGL_READ_DEST_ROWS UINT32_C(2)
/* The frame goes to the App's ring instead of back to the guest: the worker
 * still reads and converts it, and the reply carries no pixels. */
#define HIOS_EAGL_READ_NO_REPLY  UINT32_C(4)
/* The guest already holds the frame the worker sent last, so the reply only
 * needs to say what changed: it starts with a four-byte marker, then either
 *   'FULL' + width * height * 4 bytes (the whole picture), or
 *   'DELT' + u32 bandCount + bandCount * {u32 y, u32 rows} + the pixels of
 *          those bands, top to bottom, width * 4 bytes a row.
 * The rows are converted exactly as SWAP/DEST_ROWS ask, so the guest copies
 * them into their surface rows as they are; a surface row may be longer than
 * width * 4 (the panel ones are 4544 or 4608 bytes for a 4500-byte row), which
 * only matters to the guest's stride.
 *
 * A delta is relative to one surface, and CoreAnimation keeps two in rotation,
 * so a DELTA request also names the surface it is about: {width, height, flags,
 * u32 keyLo, u32 keyHi, u32 stride}. The key is opaque to the worker -- the
 * guest sends the IOSurface's base address, the worker compares it and never
 * dereferences it -- and with the stride and the size it identifies the
 * surface. The worker keeps one base frame per surface (a few, LRU) and
 * answers FULL only when it has no trustworthy frame for that surface, when
 * the conversion differs, or when more than half the rows changed. DELTA needs
 * SWAP (a band is written into a BGRA surface unchanged) and excludes NO_REPLY
 * (the guest would have no frame to apply a delta to). A request without DELTA
 * is answered with the plain pixels, exactly as before, and invalidates the
 * worker's base frames because it does not say which surface it wrote. */
#define HIOS_EAGL_READ_DELTA_REQUEST 24u  /* {width, height, flags, key, stride} */
#define HIOS_EAGL_READ_DELTA     UINT32_C(8)
#define HIOS_EAGL_DELTA_FULL     "FULL"
#define HIOS_EAGL_DELTA_PART     "DELT"
#define HIOS_EAGL_DELTA_BAND_ROWS 16u
#define HIOS_EAGL_DELTA_HEADER   8u    /* marker + band count */
#define HIOS_EAGL_DELTA_BAND     8u    /* {y, rows} */
/* 4096 rows (the packed-size cap) at 16 rows a band. */
#define HIOS_EAGL_DELTA_BANDS    256u

enum hios_eagl_kind { EA_TEXTURE = 1, EA_FBO, EA_RBO };
enum hios_eagl_status { EA_OK = 0, EA_BAD_COMMAND, EA_NO_CONTEXT, EA_DEVICE_ERROR };

static inline uint32_t hios_get32(const unsigned char *p)
{
    return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24;
}
static inline void hios_put32(unsigned char *p, uint32_t v)
{
    for (unsigned i = 0; i < 4; ++i) p[i] = (unsigned char)(v >> (i * 8));
}
#endif
