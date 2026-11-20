/* SPDX-License-Identifier: GPL-2.0-or-later */
#include "eagl-guest-client.h"
#include "eagl-guest-timing.h"
#include "eagl-pixel-transfer.h"
extern void *malloc(size_t);
extern void free(void *);
extern _Noreturn void abort(void);

static unsigned row_length, alignment = 4, pack_row_length, pack_alignment = 4;

int hios_eagl_pixel_store_value(unsigned name, int *value)
{
    int ok = 1;
    hios_eagl_lock();
    switch (name) {
    case 0x0cf2: *value = (int)row_length; break;
    case 0x0cf5: *value = (int)alignment; break;
    case 0x0d02: *value = (int)pack_row_length; break;
    case 0x0d05: *value = (int)pack_alignment; break;
    default: ok = 0; break;
    }
    hios_eagl_unlock();
    return ok;
}

void glPixelStorei(unsigned name, int value)
{
    hios_eagl_lock();
    if (name == 0x0cf2 && value >= 0) row_length = (unsigned)value;
    else if (name == 0x0cf5 && (value == 1 || value == 2 || value == 4 || value == 8)) alignment = (unsigned)value;
    else if (name == 0x0d02 && value >= 0) pack_row_length = (unsigned)value;
    else if (name == 0x0d05 && (value == 1 || value == 2 || value == 4 || value == 8)) pack_alignment = (unsigned)value;
    else abort();
    hios_eagl_unlock();
}

void glTexImage2D(unsigned target, int level, int internal, int width, int height,
                  int border, unsigned format, unsigned type, const void *pixels)
{
    const char *stage = "geometry";
    unsigned row = 0, align = 0;
    if (target != 0x0de1 || (unsigned)level > HIOS_TEXTURE_MAX_LEVEL || border || width <= 0 || height <= 0)
        goto failed;
    stage = "format";
    enum hios_pixel_format wire;
    unsigned unit;
    if (type == 0x1401 && (internal == 0x1908 || internal == 0x8058 || internal == 0x80e1) &&
        (format == 0x1908 || format == 0x80e1)) { wire = format == 0x80e1 ? HIOS_BGRA8 : HIOS_RGBA8; unit = 4; }
    else if (type == 0x1401 && internal == 0x1906 && format == 0x1906) { wire = HIOS_ALPHA8; unit = 1; }
    else if (type == 0x1401 && internal == 0x190a && format == 0x190a) { wire = HIOS_LA8; unit = 2; }
    else if ((type == 0x140b || type == 0x8d61) && internal == 0x881a && format == 0x1908) { wire = HIOS_RGBA16F; unit = 8; }
    else goto failed;
    stage = "packed size";
    size_t packed = hios_pixel_packed_size((unsigned)width, (unsigned)height, wire);
    if (!packed) goto failed;
    hios_eagl_lock();
    row = row_length ? row_length : (unsigned)width;
    align = alignment;
    hios_eagl_unlock();
    stage = "unpack state";
    if (pixels && row > 16384) goto failed;
    size_t n = pixels ? packed : 0;
    size_t stride = pixels ? (((size_t)row * unit + align - 1) & ~(size_t)(align - 1)) : 0;
    stage = "allocation";
    size_t header = level ? 16 : 12;
    unsigned char *request = malloc(header + n);
    if (!request) goto failed;
    hios_put32(request, (unsigned)width);
    hios_put32(request + 4, (unsigned)height);
    hios_put32(request + 8, wire);
    if (level) hios_put32(request + 12, (unsigned)level);
    /* GLES client pointers must cover the current unpack span. */
    size_t span = (size_t)(height - 1) * stride + (size_t)width * unit;
    size_t received;
    uint64_t pack_us = 0, began = 0;
    int ok = 1;
    if (pixels) began = hios_now_us();
    if (pixels) ok = hios_pixel_pack(request + header, n, pixels, span, stride, (unsigned)width,
                                     (unsigned)height, wire);
    if (pixels) pack_us = hios_now_us() - began;
    if (ok) {
        stage = "remote reply";
        ok = hios_eagl_current_call(level ? EA_TEXTURE_MIP : EA_TEXTURE_BASE,
                                    request, header + n, NULL, 0, &received) && !received;
    } else stage = "pixel pack";
    if (ok && pixels) {
        static unsigned uploads;
        char timing[288];
        int length = snprintf(timing, sizeof timing,
            "hackios-gl: tex %u level=%d %ux%u bytes=%u pack=%llu send=%llu hdr=%llu total=%llu\n",
            ++uploads, level, width, height, (unsigned)n, (unsigned long long)pack_us,
            (unsigned long long)hios_call_timing.send_us, (unsigned long long)hios_call_timing.header_us,
            (unsigned long long)hios_call_timing.total_us);
        hios_log(timing, length);
    }
    free(request);
    if (ok) return;
failed:;
    char line[224];
    int length = snprintf(line, sizeof line,
        "hackios-eagl: glTexImage2D %s: target=%#x level=%d internal=%#x size=%dx%d border=%d format=%#x type=%#x unpack_row=%u align=%u\n",
        stage, target, level, (unsigned)internal, width, height, border, format, type, row, align);
    if (length > 0 && (size_t)length < sizeof line) (void)write(2, line, (size_t)length);
    abort();
}

static void objects(unsigned op, unsigned kind, int count, const unsigned *input, unsigned *output)
{
    if (count < 0 || count > 64 || (count && (op == EA_CREATE_OBJECTS ? !output : !input))) abort();
    unsigned char request[8 + 64 * 4], reply[64 * 4];
    size_t n = (size_t)count * 4, received;
    hios_put32(request, kind);
    hios_put32(request + 4, (unsigned)count);
    if (input) for (int i = 0; i < count; ++i) hios_put32(request + 8 + i * 4, input[i]);
    if (!hios_eagl_current_call(op, request, 8 + (input ? n : 0), reply, output ? n : 0, &received) ||
        received != (output ? n : 0)) abort();
    if (output) for (int i = 0; i < count; ++i) output[i] = hios_get32(reply + i * 4);
}

#define OBJECTS(suffix, kind) \
void glGen##suffix(int n, unsigned *out) { objects(EA_CREATE_OBJECTS, kind, n, NULL, out); } \
void glDelete##suffix(int n, const unsigned *in) { objects(EA_DELETE_OBJECTS, kind, n, in, NULL); }
OBJECTS(Textures, EA_TEXTURE)
OBJECTS(Framebuffers, EA_FBO)
OBJECTS(Renderbuffers, EA_RBO)
