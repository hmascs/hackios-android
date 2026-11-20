/* SPDX-License-Identifier: GPL-2.0-or-later */
#ifndef HACKIOS_TRIANGLE_COMMAND_H
#define HACKIOS_TRIANGLE_COMMAND_H

#include "host-renderer.h"

/* Smoke-test protocol, not a general GLES ABI. Packed little-endian bytes:
 * ["HGL1"][u16 opcode=1][u16 size=48][6 f32 clip XY][4 f32 RGBA].
 * transport supplies the already-owned renderer; it never passes guest pointers. */
#define HIOS_TRIANGLE_COMMAND_SIZE 48u

/* Must be called by the EGL-owning worker, on a stable copy of the command. */
int hios_dispatch_triangle(const struct hios_renderer_v1 *ops, void *renderer,
                           const void *command, size_t size);
#endif
