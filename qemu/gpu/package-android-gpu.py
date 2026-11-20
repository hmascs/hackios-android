#!/usr/bin/env python3
"""Package only the relocatable ARM64 KGSL runtime; never package guest system images."""
import json
from pathlib import Path
import re
import subprocess
import sys
from zipfile import ZipFile, ZIP_DEFLATED

WRAPPER = '''#!/system/bin/sh
set -eu
base=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
unset VK_INSTANCE_LAYERS VK_LOADER_LAYERS_ENABLE VK_LOADER_LAYERS_DISABLE LIBGL_ALWAYS_SOFTWARE
export LD_LIBRARY_PATH="$base/lib"
export VK_DRIVER_FILES="$base/config/turnip.json"
export VK_LAYER_PATH="$base/config/empty"
export VK_IMPLICIT_LAYER_PATH="$base/config/empty"
export __EGL_VENDOR_LIBRARY_FILENAMES="$base/config/zink-egl.json"
export LIBGL_DRIVERS_PATH="$base/lib/dri"
export EGL_PLATFORM=surfaceless MESA_LOADER_DRIVER_OVERRIDE=zink GALLIUM_DRIVER=zink LIBGL_KOPPER_DISABLE=true
exec "$base/bin/hackios-zink-session" "$@"
'''
SYSTEM = {'libc.so', 'libdl.so', 'libm.so', 'liblog.so', 'libandroid.so', 'libz.so',
          'libnativewindow.so', 'libsync.so', 'libstdc++.so'}
ROOT = 'gpu-android-arm64/'


def main():
    if len(sys.argv) != 4:
        sys.exit(f'usage: {sys.argv[0]} TERMUX_PREFIX ANDROID_WORKER OUTPUT_ZIP')
    prefix, worker, output = map(lambda s: Path(s).resolve(), sys.argv[1:])
    pending = {'libvulkan.so.1', 'libvulkan_freedreno.so', 'libEGL.so.1', 'libEGL_mesa.so',
               'libGLESv2.so.2', 'dri/zink_dri.so'}
    libraries = {}

    def inspect(path):
        header = subprocess.check_output(['readelf', '-hW', path], text=True)
        if not re.search(r'Machine:\s*AArch64\b', header):
            raise ValueError(f'Not an AArch64 ELF: {path}')
        loads = subprocess.check_output(['readelf', '-lW', path], text=True)
        if any(int(line.split()[-1], 16) < 0x4000 for line in loads.splitlines() if line.lstrip().startswith('LOAD ')):
            print(f'Warning: ELF not 16 KiB aligned: {path.name}', file=sys.stderr)
        dyn = subprocess.check_output(['readelf', '-dW', path], text=True)
        needed = set(re.findall(r'\(NEEDED\).*?\[([A-Za-z0-9_.+\-]+)\]', dyn))
        pending.update(needed - SYSTEM - libraries.keys())

    inspect(worker)
    while pending:
        name = min(pending)
        pending.remove(name)
        if name in libraries:
            continue
        path = (prefix / 'lib' / name).resolve(strict=True)
        if not path.is_file() or not path.is_relative_to(prefix / 'lib'):
            raise ValueError(f'Library outside prefix: {path}')
        libraries[name] = path
        inspect(path)

    def manifest(relative, so):
        data = json.loads((prefix / 'share' / relative).read_text())
        data['ICD']['library_path'] = '../lib/' + so
        return json.dumps(data, indent=2) + '\n'

    with ZipFile(output, 'x', ZIP_DEFLATED, compresslevel=4) as z:
        z.writestr(ROOT + 'start-worker.sh', WRAPPER)
        z.writestr(ROOT + 'config/empty/', '')
        z.writestr(ROOT + 'config/turnip.json', manifest('vulkan/icd.d/freedreno_icd.aarch64.json', 'libvulkan_freedreno.so'))
        z.writestr(ROOT + 'config/zink-egl.json', manifest('glvnd/egl_vendor.d/50_mesa.json', 'libEGL_mesa.so'))
        z.write(worker, ROOT + 'bin/hackios-zink-session')
        for name, path in sorted(libraries.items()):
            z.write(path, ROOT + 'lib/' + name)
        for name in ('mesa', 'mesa-vulkan-icd-freedreno', 'libglvnd', 'vulkan-loader-generic'):
            for index, license_path in enumerate(sorted((prefix / 'share/doc' / name).glob('*'))):
                if license_path.is_file() and license_path.stat().st_size <= 1024 * 1024:
                    z.write(license_path, ROOT + f'notices/{name}-{index}.txt')
    print(f'{len(libraries)} libraries; {output.stat().st_size} bytes: {output}')


if __name__ == '__main__':
    main()
