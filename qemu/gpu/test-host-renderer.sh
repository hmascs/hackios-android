#!/usr/bin/env bash
# Linux compile/run check. Software rendering is forced so this is reproducible in CI.
set -euo pipefail
root=$(cd -- "$(dirname -- "$0")" && pwd)
work=$(realpath -- "$(mktemp -d)")
[[ "$work" == /* && "$work" != / && -O "$work" ]]
trap 'test -n "${work:-}" && test "$work" != / && rm -r -- "$work"' EXIT
read -r -a gl <<< "$(pkg-config --cflags --libs egl glesv2)"
read -r -a vk <<< "$(pkg-config --cflags --libs vulkan)"
common=(-std=c11 -O2 -Wall -Wextra -Werror)
"${CC:-cc}" "${common[@]}" -shared -fPIC "$root/host-egl.c" "${gl[@]}" -o "$work/host-egl.so"
"${CC:-cc}" "${common[@]}" -shared -fPIC "$root/host-vulkan.c" "${vk[@]}" -o "$work/host-vulkan.so"
"${CC:-cc}" "${common[@]}" "$root/test-host-renderer.c" "$root/triangle-command.c" -ldl -o "$work/test"
EGL_PLATFORM=surfaceless LIBGL_ALWAYS_SOFTWARE=true "$work/test" "$work/host-egl.so"
shopt -s nullglob
icds=(/usr/share/vulkan/icd.d/lvp_icd*.json)
if [[ -z ${HIOS_LAVAPIPE_ICD:-} && ${#icds[@]} != 1 ]]; then
    echo 'set HIOS_LAVAPIPE_ICD to the host architecture Lavapipe ICD JSON' >&2
    exit 2
fi
icd=$(realpath -e -- "${HIOS_LAVAPIPE_ICD:-${icds[0]}}")
VK_DRIVER_FILES="$icd" VK_ICD_FILENAMES="$icd" "$work/test" "$work/host-vulkan.so"
