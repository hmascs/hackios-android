#!/usr/bin/env python3
"""Run one GPU pixel probe in an existing HackiOS iOS/XNU guest on an isolated copied root."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import socket
import struct
import subprocess
import time

from gpu_runner import add_worker_options, check_worker_options, prepare_worker


def repack(src, output, root_apfs, vcpus, disk_enabled, trace_frames=None):
    data = src.read_bytes()
    def entries():
        magic, length = struct.unpack_from('<4sI', data)
        if magic != b'XNUP' or length + 8 != len(data):
            raise ValueError('invalid XNUP header')
        offset = 8
        seen = set()
        while offset + 24 <= len(data):
            name, size, reserved = struct.unpack_from('<16sII', data, offset)
            offset += 24
            end = offset + (size + 15) // 16 * 16
            if reserved or end > len(data):
                raise ValueError('invalid XNUP entry')
            if name == bytes(16):
                if size or end != len(data) or b'ramdisk' not in seen:
                    raise ValueError('invalid XNUP terminator or missing ramdisk')
                yield bytes(24)
                return
            key = name.rstrip(b'\0')
            if key in seen:
                raise ValueError('duplicate XNUP entry')
            seen.add(key)
            item = root_apfs.read_bytes() if key == b'ramdisk' else data[offset:offset + size]
            if key == b'cmdline':
                args = item.rstrip(b'\0').split()
                if sum(x.startswith(b'cpus=') for x in args) != 1:
                    raise ValueError('expected exactly one cpus= boot argument')
                args = [f'cpus={vcpus}'.encode() if x.startswith(b'cpus=') else x for x in args
                        if not x.startswith(b'virtio_blk=')]
                if disk_enabled:
                    args.append(b'virtio_blk=1')
                if trace_frames:
                    args = [x for x in args if not x.startswith(b'gltrace=')]
                    args.append(f'gltrace={trace_frames}'.encode())
                item = b' '.join(args) + b'\0' * (len(item) - len(item.rstrip(b'\0')))
            yield struct.pack('<16sII', name, len(item), 0) + item + bytes(-len(item) % 16)
            offset = end
        raise ValueError('XNUP terminator missing')
    body = b''.join(entries())
    with output.open('xb') as f:
        f.write(struct.pack('<4sI', b'XNUP', len(body)) + body)


def stop(proc):
    if proc.poll() is not None:
        return
    proc.terminate()
    try:
        proc.wait(timeout=8)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait()


def remote_evidence(serial, worker_log):
    opened = re.search(rb'hackios-eagl: guest pid=([1-9]\d*) context=1 remote opened', serial)
    renderer = re.search(rb'^EAGL worker: context=1 renderer=[^\r\n]*Adreno[^\r\n]*MESA_TURNIP',
                         worker_log, re.MULTILINE)
    frame = re.search(rb'^EAGL readback: context=1 read=\d+ draws_submitted=[1-9]\d* size=1125x2436$',
                      worker_log, re.MULTILINE)
    if not (opened and renderer and frame):
        return None
    compositor = re.search(rb'Successfully spawned (backboardd|SpringBoard)\[' + opened[1] + rb'\](?= because )', serial)
    return (compositor[1].decode('ascii'), int(opened[1])) if compositor else None


def check_remote_desktop(a, root, w, worker):
    command = ['python3', root.parents[1] / 'tools/verify-ios-desktop.py',
               w / 'qmp.sock', a.desktop_reference, w / 'desktop.png', '--timeout', str(min(a.timeout, 900)),
               '--gpu-log', w / 'worker.log', '--interaction', a.remote_interaction]
    with subprocess.Popen(command, cwd=w) as verifier:
        try:
            while verifier.poll() is None:
                if worker.poll() is not None:
                    stage = 'interaction' if (w / 'desktop.png').is_file() else 'desktop startup'
                    raise RuntimeError(f'remote EGL worker exited during {stage} ({worker.returncode}): {w / "worker.log"}')
                time.sleep(0.25)
            if verifier.returncode or worker.poll() is not None:
                raise RuntimeError(f'remote desktop verification failed: {w}')
        finally:
            stop(verifier)
    evidence = remote_evidence((w / 'serial.log').read_bytes(), (w / 'worker.log').read_bytes())
    if evidence is None:
        raise RuntimeError(f'desktop found without same-boot compositor PID and hardware frame evidence: {w}')
    service, pid = evidence
    print(f'Remote desktop and {a.remote_interaction} scanout matched ({service}[{pid}]); review evidence in {w}')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('qemu_run', 'ram', 'payload', 'rootfs', 'mkapfs', 'output'):
        p.add_argument(name, type=lambda s: Path(s).resolve())
    p.add_argument('--ld64', default='ld64.lld')
    p.add_argument('--clang', default='clang')
    p.add_argument('--timeout', type=int, default=420)
    p.add_argument('--vcpus', type=int, choices=(2, 4, 6), default=2)
    p.add_argument('--trace-gl-frames', metavar='FIRST-LAST', help='trace only a bounded frame interval in Redwood')
    p.add_argument('--cpu-samples', type=int, choices=range(0, 8), default=0,
                   help='run N identical six-thread CPU samples before the GPU probe')
    p.add_argument('--desktop-reference', type=lambda s: Path(s).resolve(strict=True),
                   help='after GPU and disk probes, require a visible SpringBoard scanout')
    p.add_argument('--probe-data-disk', type=lambda s: Path(s).resolve(strict=True),
                   help='attach a qcow2 read-only and read an 8 GiB /dev/rrvblk0 from XNU')
    p.add_argument('--remote-redwood', type=lambda s: Path(s).resolve(strict=True),
                   help='experimental libredwood-gl.dylib, installed only in the copied root')
    p.add_argument('--remote-worker', type=lambda s: Path(s).resolve(strict=True),
                   help='Android zink-session.sh from build-android-kgsl.sh')
    p.add_argument('--remote-interaction', choices=('swipe', 'settings'), default='swipe',
                   help='same-boot input after matching the SpringBoard desktop')
    add_worker_options(p)
    a = p.parse_args()
    remote = a.remote_redwood is not None
    if remote != (a.remote_worker is not None):
        p.error('--remote-redwood and --remote-worker must be provided together')
    if remote:
        if not a.desktop_reference or a.probe_data_disk or a.cpu_samples:
            p.error('remote boot requires --desktop-reference; isolated probes are disabled')
        if not a.remote_redwood.is_file() or not os.access(a.remote_worker, os.X_OK):
            p.error('expected replacement dylib and executable remote worker')
    else:
        check_worker_options(p, a)
    if not 30 <= a.timeout <= 1200:
        p.error('timeout must be 30–1200 s')
    if a.trace_gl_frames:
        bounds = re.fullmatch(r'([1-9][0-9]{0,3})-([1-9][0-9]{0,3})', a.trace_gl_frames)
        if not bounds or not 0 <= int(bounds[2]) - int(bounds[1]) <= 12:
            p.error('--trace-gl-frames requires FIRST-LAST and at most 13 frames')
    a.output.mkdir(mode=0o700)  # must not exist
    root = Path(__file__).resolve().parent
    w, r = a.output, a.qemu_run
    q = r / 'qemu-android/qemu'
    def run(*argv):
        subprocess.run(argv, check=True, cwd=w)
    worker_args, env = ([a.remote_worker, 'Adreno'], os.environ.copy()) if remote else prepare_worker(a, root, w)
    copied_root = w / 'rootfs'
    shutil.copytree(a.rootfs, copied_root, symlinks=True)
    if remote:
        destination = copied_root / 'lib/libredwood-gl.dylib'
        if (destination.parent.is_symlink() or destination.is_symlink() or not destination.is_file()
                or not destination.resolve().is_relative_to(copied_root.resolve())):
            raise ValueError('expected a regular Redwood dylib in the private root')
        shutil.copyfile(a.remote_redwood, destination)
    shutil.copytree(r / 'esp', w / 'esp')
    # Copy into a new path; the original writable EFI variables remain untouched.
    shutil.copyfile(r / 'QEMU_VARS.fd', w / 'vars.fd')
    (w / 'tmp').mkdir()
    def build_probe(source, name, entry='_start'):
        probe = w / 'rootfs/bin' / name
        obj = w / (name + '.o')
        if probe.exists() or probe.is_symlink():
            raise ValueError(f'{name} already exists in input rootfs')
        run(a.clang, '--target=arm64-apple-ios16.0', '-O2', '-Wall', '-Wextra', '-Werror',
            '-ffreestanding', '-fno-stack-protector', '-c', root / source, '-o', obj)
        run(a.ld64, '-arch', 'arm64', '-platform_version', 'ios', '16.0', '16.5', '-e', entry,
            '-adhoc_codesign', '-o', probe, obj, root / 'libSystem.tbd')
        probe.chmod(0o755)
    if not remote:
        build_probe('guest-xnu-smoke.S', 'hackios-gpu-probe')
    if a.cpu_samples:
        build_probe('guest-xnu-cpu-probe.c', 'hackios-cpu-probe', '_main')
    esp_args = ['-drive', 'file=fat:rw:esp,format=raw,if=virtio']
    disk_args = []
    if a.probe_data_disk:
        build_probe('guest-xnu-disk-probe.S', 'hackios-disk-probe')
        # Prevent EDK2 from selecting the blank data disk instead of the ESP.
        esp_args = ['-drive', 'file=fat:rw:esp,format=raw,if=none,id=esp',
                    '-device', 'virtio-blk-pci,drive=esp,bootindex=1', '-boot', 'strict=on']
        disk_args = ['-blockdev', json.dumps({
            'node-name': 'probe-disk', 'driver': 'qcow2', 'read-only': True,
            'file': {'driver': 'file', 'filename': str(a.probe_data_disk), 'read-only': True},
        }), '-device', 'virtio-blk-device,drive=probe-disk']
    jobs = w / 'rootfs/etc/rc.jobs'
    if jobs.is_symlink() or not jobs.is_file():
        raise ValueError('expected regular rc.jobs in copied root')
    with jobs.open('a') as f:
        if disk_args:
            f.write('\n/redwood/bin/hackios-disk-probe\n')
        if a.cpu_samples:
            f.write('\n' + '/redwood/bin/hackios-cpu-probe\n' * a.cpu_samples)
        f.write('\n/redwood/bin/net_test dhcp en0 waitaddr en0 60\n')
        if not remote:
            f.write('/redwood/bin/hackios-gpu-probe\n')
    guest_port = 19484 if remote else 19483
    run('python3', a.mkapfs, '-z', '-o', 'root.apfs', 'root=rootfs')
    repack(a.payload, w / 'payload.bin', w / 'root.apfs', a.vcpus, bool(disk_args), a.trace_gl_frames)
    qenv = {**os.environ, 'LD_LIBRARY_PATH': str(q / 'lib'), 'TMPDIR': str(w / 'tmp')}
    with socket.socket(socket.AF_UNIX) as server, (w / 'qemu.log').open('wb') as log:
        server.bind(str(w / 'renderer.sock'))
        server.listen(1)
        server.settimeout(10)
        vm = subprocess.Popen([q / 'qemu-system-aarch64', '-L', r / 'qemu-share',
            '-M', 'virt,gic-version=3', '-cpu', 'cortex-a76',
            '-smp', f'{a.vcpus},sockets=1,cores={a.vcpus},threads=1',
            '-m', '7G', '-accel', 'tcg,thread=multi,tb-size=1360',
            '-chardev', 'socket,id=gpu,path=renderer.sock',
            '-netdev', f'user,id=net0,restrict=on,guestfwd=tcp:10.0.2.100:{guest_port}-chardev:gpu',
            '-device', 'virtio-net-device,netdev=net0',
            '-drive', f'if=pflash,format=raw,readonly=on,file={r}/QEMU_EFI.fd',
            '-drive', 'if=pflash,format=raw,file=vars.fd', *esp_args,
            '-device', 'loader,file=payload.bin,addr=0xf0000000,force-raw=on',
            '-object', f'memory-backend-file,id=ram,size=7G,mem-path={a.ram},readonly=on,rom=off,share=off',
            '-machine', 'memory-backend=ram', '-global', 'virtio-mmio.force-legacy=false',
            '-device', 'virtio-tablet-device', '-device', 'virtio-keyboard-device', '-device', 'ramfb',
            *disk_args, '-qmp', 'unix:qmp.sock,server=on,wait=off',
            '-display', 'none', '-monitor', 'none', '-serial', 'file:serial.log', '-no-reboot'],
            cwd=w, env=qenv, stdout=log, stderr=log)
        try:
            with server.accept()[0] as channel, (w / 'worker.log').open('wb') as worker_log:
                worker = subprocess.Popen(worker_args, stdin=channel, stdout=channel, stderr=worker_log, cwd=w, env=env)
                try:
                    if remote:
                        check_remote_desktop(a, root, w, worker)
                    elif worker.wait(a.timeout):
                        raise RuntimeError(f'XNU probe did not accept rendered pixels; logs: {w}')
                except subprocess.TimeoutExpired as exc:
                    raise RuntimeError(f'XNU probe produced no acknowledgement; logs: {w}') from exc
                finally:
                    stop(worker)
            if a.desktop_reference and not remote:
                run('python3', root.parents[1] / 'tools/verify-ios-desktop.py',
                    w / 'qmp.sock', a.desktop_reference, w / 'desktop.png', '--timeout', str(min(a.timeout, 900)))
        finally:
            stop(vm)
    serial = (w / 'serial.log').read_bytes()
    if a.probe_data_disk and b'hackios-disk: XNU read sector from 8 GiB virtio block device' not in serial:
        raise RuntimeError(f'XNU failed the read-only block-device probe; logs: {w}')
    if a.cpu_samples:
        samples = re.findall(rb'hackios-cpu: workers=6 pixels=131072 passes=32 wall_us=(\d+) digest=([0-9a-f]{16})', serial)
        if len(samples) != a.cpu_samples or any(int(t) <= 0 or digest != b'defb1e6d620fe8f4' for t, digest in samples):
            raise RuntimeError(f'XNU CPU sample missing, invalid, or incorrect output; logs: {w}')
        print('XNU CPU samples:', ', '.join(t.decode() + ' us' for t, _ in samples),
              'digest:', samples[0][1].decode())
    print(f'{"Remote XNU desktop smoke" if remote else "XNU GPU transport"} completed: {w}')


if __name__ == '__main__':
    main()
