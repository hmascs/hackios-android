/* SPDX-License-Identifier: GPL-2.0-or-later */
#include "triangle-command.h"
#include <float.h>
#include <math.h>
#include <string.h>

_Static_assert(sizeof(float) == 4 && FLT_RADIX == 2 && FLT_MANT_DIG == 24 && FLT_MAX_EXP == 128,
               "binary32 float required");

int hios_dispatch_triangle(const struct hios_renderer_v1 *ops, void *renderer,
                           const void *command, size_t size)
{
    const uint8_t *src = command;
    float values[10];
    if (!ops || ops->abi != HIOS_RENDERER_ABI || ops->size < sizeof *ops || !ops->triangle ||
        !renderer || !src || size != HIOS_TRIANGLE_COMMAND_SIZE ||
        memcmp(src, "HGL1\x01\x00\x30\x00", 8)) return 0;
    for (unsigned i = 0; i < 10; i++) {
        const uint8_t *p = src + 8 + i * 4;
        uint32_t bits = p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
        memcpy(&values[i], &bits, sizeof bits);
        if (!isfinite(values[i])) return 0;
    }
    return ops->triangle(renderer, values, values + 6);
}
