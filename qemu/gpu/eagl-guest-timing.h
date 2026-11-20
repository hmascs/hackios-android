/* SPDX-License-Identifier: GPL-2.0-or-later */
/* Guest-side timing for the remote GLES bridge (measurement only).
 *
 * Every readback and texture upload of this library logs one line to the
 * guest console (fd 2, the same channel eagl.c uses for "redwood-gl:"), split
 * into the parts that the 1.18 s "copying out" is made of:
 *
 *   send    request and payload written to the socket
 *   hdr     reply header read back
 *   body    reply payload read (the 10.9 MB frame, or nothing for an upload)
 *   unpack  guest-side conversion into the IOSurface (or pixel packing)
 *
 * The clock is CLOCK_MONOTONIC, which the guest kernel backs with the same
 * timebase eagl.c uses (mach_absolute_time); values are microseconds.
 */
#ifndef HIOS_EAGL_GUEST_TIMING_H
#define HIOS_EAGL_GUEST_TIMING_H
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <time.h>
#include <unistd.h>

struct hios_call_timing {
    uint64_t send_us, header_us, body_us, total_us;
    uint64_t batch_drain_us; /* experimental: pending batch inside inclusive call total */
    size_t reply_bytes;
};
extern struct hios_call_timing hios_call_timing;

static inline uint64_t hios_now_us(void)
{
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) return 0;
    return (uint64_t)ts.tv_sec * UINT64_C(1000000) + (uint64_t)ts.tv_nsec / 1000u;
}

static inline void hios_log(const char *line, int length)
{
    if (length > 0) (void)write(2, line, (size_t)length);
}
#endif
