#!/usr/bin/env bash
# Off-VM check of the guest's readback reply handling: no Darwin, no socket.
set -euo pipefail
root=$(cd -- "$(dirname -- "$0")" && pwd)
out=$(mktemp -d "${TMPDIR:-/tmp}/hios-eagl-surface.XXXXXX")
# -D_GNU_SOURCE: the guest's timing header uses clock_gettime, which Darwin
# declares unconditionally and POSIX hides behind _POSIX_C_SOURCE on Linux.
"${CC:-cc}" -std=c11 -O2 -Wall -Wextra -Werror -D_GNU_SOURCE -Wno-unused-result -pthread \
    "$root/test-eagl-guest-surface.c" "$root/eagl-guest-surface.c" \
    "$root/eagl-pixel-transfer.c" -o "$out/test-eagl-guest-surface"
"$out/test-eagl-guest-surface"
# No shadow storage must preserve correctness by using fresh FULL keys, not
# silently accept DELT against unknown pixels or crash on allocation pressure.
"${CC:-cc}" -std=c11 -O2 -Wall -Wextra -Werror -D_GNU_SOURCE -Wno-unused-result -pthread \
    -DHIOS_SURFACE_SHADOW_BUDGET=0 -DHIOS_TEST_NO_SHADOW \
    "$root/test-eagl-guest-surface.c" "$root/eagl-guest-surface.c" \
    "$root/eagl-pixel-transfer.c" -o "$out/test-eagl-guest-surface-no-shadow"
"$out/test-eagl-guest-surface-no-shadow" --live-base-only
printf 'Guest whole-frame, padded delta and exact live-base handling passed\n'
