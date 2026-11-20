#!/usr/bin/env bash
# Linux software control for the host half of the EAGL bridge. Android hardware
# testing instead uses the zink-arrays-test.sh from build-android-kgsl.sh.
set -euo pipefail
root=$(cd -- "$(dirname -- "$0")" && pwd)
out=$(mktemp -d "${TMPDIR:-/tmp}/hios-eagl.XXXXXX")
# Every output is private; leave the directory for troubleshooting.
printf 'test directory: %s\n' "$out"
"${CC:-cc}" -std=c11 -O2 -Wall -Wextra -Werror \
    "$root/eagl-client-arrays.c" "$root/test-eagl-array-wire.c" -o "$out/test-wire"
"$out/test-wire"
read -ra flags <<<"$(pkg-config --cflags --libs egl glesv2)"
"${CC:-cc}" -std=c11 -O2 -Wall -Wextra -Werror \
    "$root/eagl-client-arrays.c" "$root/eagl-host-arrays.c" \
    "$root/eagl-pixel-transfer.c" "$root/eagl-host-pixels.c" \
    "$root/test-eagl-arrays.c" "${flags[@]}" -o "$out/test-eagl"
EGL_PLATFORM=surfaceless LIBGL_ALWAYS_SOFTWARE=true "$out/test-eagl" llvmpipe
