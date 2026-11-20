/* SPDX-License-Identifier: GPL-2.0-or-later */
#include "triangle-command.h"
#include <stdio.h>
#include <string.h>

/* This worker owns the graphics backend; stdout is binary protocol, diagnostics
 * go to stderr. The launcher imposes the deadline on this single draw. */
int main(int argc, char **argv)
{
    const struct hios_renderer_v1 *api = hios_renderer_get(HIOS_RENDERER_ABI);
    if (argc < 2 || argc > 3 || !api || strcmp(argv[1], api->id)) {
        fprintf(stderr, "expected worker backend %s (this binary: %s)\n",
                argc >= 2 ? argv[1] : "<not specified>", api ? api->id : "<no ABI>");
        return 2;
    }
    enum { SIDE = 64 };
    uint8_t command[HIOS_TRIANGLE_COMMAND_SIZE], reply[16] = {'H', 'G', 'R', '1', 1};
    uint8_t pixels[SIDE * SIDE * 4], ack[4];
    char error[256];
    if (fread(command, 1, sizeof command, stdin) != sizeof command) {
        fputs("short guest command\n", stderr);
        return 1;
    }
    void *renderer = api->open(SIDE, SIDE, error, sizeof error);
    if (!renderer) {
        fprintf(stderr, "%s setup failed: %s\n", api->id, error);
    } else {
        const char *name = api->name(renderer);
        int expected = name && (argc == 2 || strstr(name, argv[2]));
        fprintf(stderr, "host renderer (%s): %s\n", api->id, name ? name : "<unknown>");
        if (!expected) fprintf(stderr, "required renderer substring not found: %s\n",
                               argc == 3 ? argv[2] : "<renderer missing>");
        if (expected && hios_dispatch_triangle(api, renderer, command, sizeof command) &&
            api->read_rgba(renderer, pixels, sizeof pixels)) {
            reply[4] = 0;
            memcpy(reply + 8, pixels + (32 * SIDE + 32) * 4, 4);
            memcpy(reply + 12, pixels, 4);
        }
        api->close(renderer);
    }
    if (fwrite(reply, 1, sizeof reply, stdout) != sizeof reply || fflush(stdout)) return 1;
    if (fread(ack, 1, sizeof ack, stdin) != sizeof ack || memcmp(ack, "HGOK", sizeof ack) || reply[4]) {
        fputs("guest did not accept rendered pixels\n", stderr);
        return 1;
    }
    fputs("guest acknowledged the rendered triangle and background pixels\n", stderr);
    return 0;
}
