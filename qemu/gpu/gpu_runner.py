"""Backend selection for the two isolated GPU transport probes."""
import os
from pathlib import Path
import subprocess


BACKENDS = {
    'gl': ('egl-gles3-pbuffer', 'host-egl.c', ('egl', 'glesv2')),
    'vk': ('vulkan-rgba8', 'host-vulkan.c', ('vulkan',)),
}
LOADER_OVERRIDES = (
    'EGL_PLATFORM', 'LIBGL_ALWAYS_SOFTWARE', 'MESA_LOADER_DRIVER_OVERRIDE',
    'VK_DRIVER_FILES', 'VK_ICD_FILENAMES', 'VK_ADD_DRIVER_FILES',
    'VK_LOADER_DRIVERS_SELECT', 'VK_LOADER_DRIVERS_DISABLE',
)


def add_worker_options(parser):
    parser.add_argument('--backend', choices=tuple(BACKENDS), default='gl')
    parser.add_argument('--native-worker', type=Path, help='prebuilt native process for the selected backend')
    parser.add_argument('--expect-renderer', help='require a case-sensitive renderer/device name substring')


def check_worker_options(parser, args):
    if args.native_worker and not args.expect_renderer:
        parser.error('--native-worker requires --expect-renderer')


def prepare_worker(args, root, work):
    backend_id, source, packages = BACKENDS[args.backend]
    env = os.environ.copy()
    for key in LOADER_OVERRIDES:
        env.pop(key, None)
    renderer = args.expect_renderer
    if args.native_worker:
        exe = args.native_worker.resolve(strict=True)
    else:
        flags = subprocess.check_output(['pkg-config', '--cflags', '--libs', *packages], text=True).split()
        exe = work / 'worker'
        subprocess.run([os.getenv('CC', 'cc'), '-std=c11', '-O2', '-Wall', '-Wextra', '-Werror',
                        root / 'serial-smoke-worker.c', root / 'triangle-command.c', root / source,
                        *flags, '-o', exe], check=True, cwd=work)
        if args.backend == 'gl':
            env.update(EGL_PLATFORM='surfaceless', LIBGL_ALWAYS_SOFTWARE='true')
        else:
            icd = os.getenv('HIOS_LAVAPIPE_ICD')
            if not icd:
                candidates = sorted(Path('/usr/share/vulkan/icd.d').glob('lvp_icd*.json'))
                if len(candidates) != 1:
                    raise RuntimeError('set HIOS_LAVAPIPE_ICD to the host architecture’s Lavapipe ICD JSON')
                icd = candidates[0]
            env['VK_DRIVER_FILES'] = env['VK_ICD_FILENAMES'] = str(Path(icd).resolve(strict=True))
        # Mesa's software GLES and Vulkan ICDs both report llvmpipe.
        renderer = renderer or 'llvmpipe'
    return [str(exe), backend_id, renderer], env
