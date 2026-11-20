/* SPDX-License-Identifier: GPL-2.0-or-later */
#include "triangle-command.h"
#include <assert.h>
#include <dlfcn.h>
#include <math.h>
#include <stdio.h>
#include <string.h>

enum { SIZE = 64 };
int main(int argc, char **argv)
{
    if (argc != 2) return 2;
    void *module = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!module) { fprintf(stderr, "%s\n", dlerror()); return 1; }
    const struct hios_renderer_v1 *(*get)(uint32_t) = dlsym(module, "hios_renderer_get");
    assert(get && !get(0) && !get(2));
    const struct hios_renderer_v1 *api = get(HIOS_RENDERER_ABI);
    assert(api && api->size == sizeof *api);
    char err[256];
    assert(!api->open(0, 64, err, sizeof err));
    assert(!api->open(4096, 4096, err, sizeof err));
    for (int run = 0; run < 2; run++) {
        void *r = api->open(SIZE, SIZE, err, sizeof err);
        if (!r) { fprintf(stderr, "%s: %s\n", api->id, err); return 1; }
        assert(!api->open(SIZE, SIZE, err, sizeof err));
        assert(api->name(r));
        printf("%s: %s\n", api->id, api->name(r));
        float verts[6] = {-.8f, -.8f, .8f, -.8f, 0, .8f};
        float invalid[6] = {NAN};
        const float rgba[4] = {1, 0, 1, 1};
        unsigned char out[SIZE * SIZE * 4];
        assert(!api->read_rgba(r, out, sizeof out));
        assert(!api->triangle(r, invalid, rgba));
        uint8_t cmd[HIOS_TRIANGLE_COMMAND_SIZE] = { 'H', 'G', 'L', '1', 1, 0, 48, 0 };
        for (unsigned i = 0; i < 10; i++) {
            const float f = i < 6 ? verts[i] : rgba[i - 6];
            uint32_t bits;
            memcpy(&bits, &f, 4);
            for (unsigned j = 0; j < 4; j++) cmd[8 + 4 * i + j] = (uint8_t)(bits >> (8 * j));
        }
        assert(!hios_dispatch_triangle(api, r, cmd, sizeof cmd - 1));
        cmd[4] = 2;
        assert(!hios_dispatch_triangle(api, r, cmd, sizeof cmd));
        cmd[4] = 1;
        assert(hios_dispatch_triangle(api, r, cmd, sizeof cmd));
        assert(!api->read_rgba(r, out, sizeof out - 1));
        assert(api->read_rgba(r, out, sizeof out));
        const unsigned char magenta[4] = {255, 0, 255, 255}, black[4] = {0, 0, 0, 255};
        assert(!memcmp(out + (32 * SIZE + 32) * 4, magenta, 4));
        assert(!memcmp(out, black, 4));
        /* Draw again on the same image. Asymmetric triangle checks origin, and
         * its green pixels and cleared center detect stale image data. */
        const float lower[6] = {-.8f, -.9f, .8f, -.9f, 0, -.2f};
        const float green[4] = {0, 1, 0, 1};
        const unsigned char green8[4] = {0, 255, 0, 255};
        assert(api->triangle(r, lower, green));
        assert(api->read_rgba(r, out, sizeof out));
        assert(!memcmp(out + (12 * SIZE + 32) * 4, green8, 4));
        assert(!memcmp(out + (51 * SIZE + 32) * 4, black, 4));
        assert(!memcmp(out + (32 * SIZE + 32) * 4, black, 4));
        api->close(r);
    }
    assert(!dlclose(module));
    puts("Draw, repeated readback, RGBA origin, command bounds, ABI negotiation and reopen passed");
    return 0;
}
