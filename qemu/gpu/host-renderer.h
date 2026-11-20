/* SPDX-License-Identifier: GPL-2.0-or-later */
#ifndef HACKIOS_HOST_RENDERER_H
#define HACKIOS_HOST_RENDERER_H

#include <stddef.h>
#include <stdint.h>

#define HIOS_RENDERER_ABI 1u

/* Host process ABI, not guest MMIO or a wire format. Each module owns its
 * graphics context/device and supports one live instance on one owner thread.
 * The EGL module must be the only owner of its EGLDisplay in the process. */
struct hios_renderer_v1 {
    uint32_t abi;
    size_t size;
    const char *id;
    void *(*open)(unsigned width, unsigned height, char *error, size_t error_size);
    const char *(*name)(void *renderer);
    /* vertices: three clip-space xy pairs, color: linear RGBA. */
    int (*triangle)(void *renderer, const float vertices[6], const float color[4]);
    /* Tightly packed RGBA8, origin at bottom left. No implicit Y flip. */
    int (*read_rgba)(void *renderer, void *output, size_t bytes);
    void (*close)(void *renderer);
};

const struct hios_renderer_v1 *hios_renderer_get(uint32_t abi);
#endif
