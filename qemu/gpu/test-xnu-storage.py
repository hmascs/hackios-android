#!/usr/bin/env python3
"""Two-boot HFS+ acceptance on a NEW scratch disk, never the user's data disk."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import time

BYTES = 8 << 30


def require_static_entry(path):
    # LLVM 18 accepts -static with a warning but emits LC_LOAD_DYLINKER and
    # LC_MAIN. The test root contains no dyld; require a real LC_UNIXTHREAD.
    data = path.read_bytes()
    magic, cpu, _, kind, count, size, flags, _ = struct.unpack_from('<8I', data)
    if magic != 0xfeedfacf or cpu != 0x0100000c or kind != 2 or flags & 4 or 32 + size > len(data):
        raise ValueError('PID 1 must be an arm64 static Mach-O executable')
    offset, commands = 32, set()
    for _ in range(count):
        if offset + 8 > 32 + size:
            raise ValueError('invalid Mach-O load commands')
        command, length = struct.unpack_from('<II', data, offset)
        if length < 8 or length % 8 or offset + length > 32 + size:
            raise ValueError('invalid Mach-O load command size')
        commands.add(command)
        offset += length
    if offset != 32 + size or 0x5 not in commands or commands & {0xc, 0xe, 0x80000028}:
        raise ValueError('use the cctools linker: LC_UNIXTHREAD required, not dyld/LC_MAIN')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for key in ('qemu_run', 'kernel_tree', 'kernel', 'output'):
        p.add_argument(key, type=lambda x: Path(x).resolve())
    p.add_argument('--clang', default='clang')
    p.add_argument('--ld64', required=True, help='cctools/Mach-O linker capable of -static')
    p.add_argument('--mkfs', default='mkfs.hfsplus')
    p.add_argument('--timeout', type=int, default=180)
    a = p.parse_args()
    if not 30 <= a.timeout <= 600:
        p.error('timeout must be 30–600 seconds per boot')
    kernel = a.kernel.read_bytes()
    if not kernel.startswith(b'\xcf\xfa\xed\xfe') or b' sectors of %u (%llu MB)%s\n\0' not in kernel:
        p.error('requires a rebuilt Mach-O XNU with the writable HakiOS virtio-blk driver; the old prebuilt kernel is read-only')
    w, r, upstream = a.output, a.qemu_run, a.kernel_tree
    w.mkdir(mode=0o700)  # never accept a previously provisioned disk/directory

    def run(*argv):
        return subprocess.run(argv, cwd=w, check=True)

    for directory in ('dev', 'mnt', 'sbin'):
        (w / 'root' / directory).mkdir(parents=True)
    run(a.clang, '--target=arm64-apple-ios16.0', '-Os', '-c', '-ffreestanding', '-fno-builtin',
        '-fno-stack-protector', upstream / 'userland/src/hfs_test.c', '-o', 'hfs_test.o')
    # The UPSTREAM test normally uses a static Mach-O entry from the kernel.
    run(a.ld64, '-arch', 'arm64', '-platform_version', 'ios', '16.0', '16.5', '-e', '__start',
        '-pagezero_size', '0x100000000', '-static', '-o', 'root/sbin/launchd', 'hfs_test.o')
    require_static_entry(w / 'root/sbin/launchd')
    run('python3', upstream / 'tools/apfs/mkapfs.py', '-o', 'root.apfs', 'Root=root')
    run('python3', upstream / 'xnuloader/mkpayload.py', '-o', 'payload.bin',
        f'kernel={a.kernel}', 'ramdisk=root.apfs', '--cmdline', 'cpus=1 debug=0x8 serial=3 rd=md0 virtio_blk=2')

    # Formatted here on a private blank file only. The real user image is never found/opened.
    raw = w / 'scratch.raw'
    with raw.open('xb') as stream:
        stream.truncate(BYTES)
    run(a.mkfs, '-s', '-J', '-U', '0', '-G', '0', '-M', '755', '-v', 'HackiOS-Test', raw)
    disk = w / 'scratch.qcow2'
    run('qemu-img', 'convert', '-f', 'raw', '-O', 'qcow2', raw, disk)
    q = r / 'qemu-android/qemu'
    shutil.copytree(r / 'esp', w / 'esp')
    (w / 'tmp').mkdir()
    env = {**os.environ, 'LD_LIBRARY_PATH': str(q / 'lib'), 'TMPDIR': str(w / 'tmp')}
    for boot in (1, 2):
        vars_file = w / f'vars-{boot}.fd'
        shutil.copyfile(r / 'QEMU_VARS.fd', vars_file)
        serial = w / f'boot-{boot}.serial.log'
        blockdev = json.dumps({'driver': 'qcow2', 'node-name': 'data',
                               'file': {'driver': 'file', 'filename': str(disk),
                                        'cache': {'direct': False, 'no-flush': False}}})
        with (w / f'boot-{boot}.qemu.log').open('xb') as log:
            vm = subprocess.Popen([q / 'qemu-system-aarch64', '-L', r / 'qemu-share',
                '-M', 'virt,gic-version=3', '-cpu', 'cortex-a76', '-smp', '1', '-m', '4G',
                '-accel', 'tcg,tb-size=384', '-nic', 'none', '-boot', 'strict=on',
                '-drive', f'if=pflash,format=raw,readonly=on,file={r}/QEMU_EFI.fd',
                '-drive', f'if=pflash,format=raw,file={vars_file.name}',
                '-drive', 'file=fat:rw:esp,format=raw,if=none,id=esp', '-device', 'virtio-blk-pci,drive=esp,bootindex=1',
                '-device', 'loader,file=payload.bin,addr=0xf0000000,force-raw=on',
                '-global', 'virtio-mmio.force-legacy=false', '-blockdev', blockdev,
                '-device', 'virtio-blk-device,drive=data,write-cache=off', '-display', 'none',
                '-monitor', 'none', '-serial', f'file:{serial.name}', '-no-reboot'], cwd=w, env=env, stdout=log, stderr=log)
            try:
                deadline = time.monotonic() + a.timeout
                while time.monotonic() < deadline:
                    text = serial.read_bytes() if serial.exists() else b''
                    if b'hfs_test: ALL OK' in text:
                        if not re.search(rb'virtio-blk: /dev/rvblk0[^\r\n]*16777216 sectors of 512[^\r\n]*writable', text):
                            raise RuntimeError(f'writable 8 GiB virtio device not verified: {serial}')
                        if f'hfs_test: after unmount and mount, the same; boots counted {boot}\n'.encode() not in text.replace(b'\r', b''):
                            raise RuntimeError(f'unexpected persistent boot counter: {serial}')
                        break
                    if vm.poll() is not None or b'hfs_test: FAILED:' in text:
                        raise RuntimeError(f'XNU exited or failed the HFS test: {serial}')
                    time.sleep(.4)
                else:
                    raise RuntimeError(f'XNU did not complete storage test on boot {boot}: {serial}')
            finally:
                if vm.poll() is None:
                    vm.terminate()
                    try:
                        vm.wait(timeout=8)
                    except subprocess.TimeoutExpired:
                        vm.kill()
                        vm.wait()
    run('qemu-img', 'check', '-f', 'qcow2', disk)
    print(f'XNU HFS+ write, fsync, remount and second-boot persistence verified on {disk}')


if __name__ == '__main__':
    main()
