#!/usr/bin/env bash
# Build the existing probes against an isolated, pre-extracted Bionic Mesa prefix.
# Installs nothing to Android or Ubuntu system directories.
set -euo pipefail
[[ $# == 3 ]] || { echo 'usage: build-android-kgsl.sh NDK_LLVM_BIN TERMUX_PREFIX OUTPUT_DIRECTORY' >&2; exit 2; }
root=$(cd -- "$(dirname -- "$0")" && pwd)
cc=$(realpath -e -- "$1")/aarch64-linux-android33-clang
prefix=$(realpath -e -- "$2")
[[ -x "$cc" ]] || { echo "Android arm64 compiler unavailable: $cc" >&2; exit 2; }
for rel in lib/libvulkan.so.1 lib/libvulkan_freedreno.so lib/libEGL.so.1 lib/libEGL_mesa.so lib/libGLESv2.so.2 lib/dri/zink_dri.so; do
    [[ -f "$prefix/$rel" ]] || { echo "Termux prefix missing $rel" >&2; exit 2; }
done
mkdir -p -- "$3"
out=$(realpath -e -- "$3")
common=(-std=c11 -O2 -Wall -Wextra -Werror -Wl,-z,max-page-size=16384
    "$root/serial-smoke-worker.c" "$root/triangle-command.c")
"$cc" "${common[@]}" "$root/host-vulkan.c" -L"$prefix/lib" -l:libvulkan.so.1 -o "$out/hackios-turnip-worker"
"$cc" "${common[@]}" "$root/host-egl.c" -L"$prefix/lib" -l:libEGL.so.1 -l:libGLESv2.so.2 -o "$out/hackios-zink-worker"
"$cc" -std=c11 -O2 -Wall -Wextra -Werror -Wl,-z,max-page-size=16384 \
    "$root/eagl-client-arrays.c" "$root/eagl-host-arrays.c" "$root/test-eagl-arrays.c" \
    "$root/eagl-pixel-transfer.c" "$root/eagl-host-pixels.c" \
    -L"$prefix/lib" -l:libEGL.so.1 -l:libGLESv2.so.2 -o "$out/hackios-zink-arrays-test"
"$cc" -std=c11 -O2 -Wall -Wextra -Werror -Wl,-z,max-page-size=16384 \
    "$root/eagl-host-session.c" "$root/eagl-client-arrays.c" "$root/eagl-host-arrays.c" \
    "$root/eagl-pixel-transfer.c" "$root/eagl-host-pixels.c" \
    -L"$prefix/lib" -l:libEGL.so.1 -l:libGLESv2.so.2 -o "$out/hackios-zink-session"
python3 - "$prefix" "$out" <<'PY'
import json
from pathlib import Path
from shlex import quote
import sys

prefix, out = map(Path, sys.argv[1:])
lib = prefix / 'lib'
for src, dest, so in (
    ('vulkan/icd.d/freedreno_icd.aarch64.json', 'turnip.json', 'libvulkan_freedreno.so'),
    ('glvnd/egl_vendor.d/50_mesa.json', 'zink-egl.json', 'libEGL_mesa.so'),
):
    manifest = json.loads((prefix / 'share' / src).read_text())
    manifest['ICD']['library_path'] = str(lib / so)
    (out / dest).write_text(json.dumps(manifest, indent=2) + '\n')
layers = out / 'empty-layers'
layers.mkdir(exist_ok=True)
if any(layers.iterdir()):
    raise SystemExit('empty-layers must not contain any manifests')
common = {
    'LD_LIBRARY_PATH': lib,
    'VK_DRIVER_FILES': out / 'turnip.json',
    'VK_LAYER_PATH': layers,
    'VK_IMPLICIT_LAYER_PATH': layers,
}
zink_env = {
        '__EGL_VENDOR_LIBRARY_FILENAMES': out / 'zink-egl.json',
        'LIBGL_DRIVERS_PATH': lib / 'dri',
        'EGL_PLATFORM': 'surfaceless',
        'MESA_LOADER_DRIVER_OVERRIDE': 'zink',
        'GALLIUM_DRIVER': 'zink',
        'LIBGL_KOPPER_DISABLE': 'true',  # Offscreen probe only; no window system required.
}
for executable, extra in (
    ('turnip-worker', {}), ('zink-worker', zink_env), ('zink-arrays-test', zink_env), ('zink-session', zink_env),
):
    wrapper = out / f'{executable}.sh'
    content = '#!/bin/sh\nset -eu\nunset VK_INSTANCE_LAYERS VK_LOADER_LAYERS_ENABLE VK_LOADER_LAYERS_DISABLE LIBGL_ALWAYS_SOFTWARE\n'
    content += ''.join(f'export {key}={quote(str(value))}\n' for key, value in (common | extra).items())
    content += f'exec {quote(str(out / f"hackios-{executable}"))} "$@"\n'
    wrapper.write_text(content)
    wrapper.chmod(0o755)
    print(wrapper)
PY
