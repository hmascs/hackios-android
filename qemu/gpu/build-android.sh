#!/usr/bin/env bash
# Usage: bash build-android.sh /path/to/ndk/toolchains/llvm/prebuilt/<host>/bin OUT
set -euo pipefail
[[ $# == 2 ]] || { echo 'usage: build-android.sh NDK_LLVM_BIN OUTPUT_DIRECTORY' >&2; exit 2; }
root=$(cd -- "$(dirname -- "$0")" && pwd)
cc=$(realpath -- "$1")/aarch64-linux-android33-clang
[[ -x "$cc" ]] || { echo "Android NDK arm64 API 33 compiler not found: $cc" >&2; exit 2; }
mkdir -p -- "$2"
out=$(realpath -- "$2")
common=(-std=c11 -O2 -Wall -Wextra -Werror -Wl,-z,max-page-size=16384)
"$cc" "${common[@]}" "$root/test-host-renderer.c" "$root/triangle-command.c" \
    -ldl -o "$out/hackios-gpu-smoke"
for backend in egl vulkan; do
    if [[ "$backend" == egl ]]; then libs=(-lEGL -lGLESv3); else libs=(-lvulkan); fi
    source="$root/host-$backend.c"
    "$cc" "${common[@]}" -shared -fPIC -Wl,--no-undefined \
        "$source" "${libs[@]}" -o "$out/libhackios_$backend.so"
    "$cc" "${common[@]}" "$root/serial-smoke-worker.c" "$root/triangle-command.c" \
        "$source" "${libs[@]}" -o "$out/hackios-$backend-worker"
    printf '%s\n' "$out/libhackios_$backend.so" "$out/hackios-$backend-worker"
done
printf '%s\n' "$out/hackios-gpu-smoke"
