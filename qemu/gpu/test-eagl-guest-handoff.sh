#!/usr/bin/env bash
# Test guest wire ordering and texture packing without booting Darwin.
set -euo pipefail
root=$(cd -- "$(dirname -- "$0")" && pwd)
if [[ $# != 1 || ! -r $1/eagl-guest-generated.h ]]; then
    printf 'usage: %s REDWOOD_BUILD_OUTPUT\n' "$0" >&2
    exit 2
fi
out=$(mktemp -d "${TMPDIR:-/tmp}/hios-eagl-guest.XXXXXX")
# Discard the unused Darwin context initialization (which calls __error).
"${CC:-cc}" -std=c11 -O2 -Wall -Wextra -Werror -ffunction-sections \
    -Wl,--gc-sections -pthread -I "$1" \
    "$root/test-eagl-guest-handoff.c" "$root/eagl-guest-context.c" \
    "$root/eagl-guest-scalars.c" "$root/eagl-guest-query.c" \
    "$root/eagl-guest-textures.c" "$root/eagl-guest-uniforms.c" \
    "$root/eagl-pixel-transfer.c" "$root/eagl-guest-draw.c" \
    "$root/eagl-client-arrays.c" -o "$out/guest-handoff"
"$out/guest-handoff"
printf 'Guest thread handoff, LA8 mip and vec2 uniform payloads passed\n'
