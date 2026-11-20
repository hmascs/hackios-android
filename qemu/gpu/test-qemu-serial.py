#!/usr/bin/env python3
"""End-to-end ARM64 QEMU transport test. No Apple image, guest OS, or network needed."""
import argparse
import os
from pathlib import Path
import socket
import subprocess
import tempfile

from gpu_runner import add_worker_options, check_worker_options, prepare_worker


def build(root, work):
    def run(*argv):
        subprocess.run(argv, check=True, cwd=work)

    asm, ld, objcopy = (os.getenv(name, default) for name, default in (
        ('AARCH64_AS', 'as'), ('AARCH64_LD', 'ld'), ('AARCH64_OBJCOPY', 'objcopy')))
    run(asm, '-o', 'guest.o', root / 'guest-serial-smoke.S')
    run(ld, '-Ttext', '0x40200000', '-o', 'guest.elf', 'guest.o')
    run(objcopy, '-O', 'binary', 'guest.elf', 'guest.bin')


def stop(proc):
    if proc.poll() is None:
        proc.terminate()
    try:
        proc.wait(4)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait()


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('qemu', type=Path)
    ap.add_argument('--qemu-library-path', type=Path, help='optional private libraries for Android QEMU, not for the worker')
    add_worker_options(ap)
    a = ap.parse_args()
    check_worker_options(ap, a)
    root = Path(__file__).resolve().parent
    qenv = os.environ.copy()
    if a.qemu_library_path:
        qenv['LD_LIBRARY_PATH'] = str(a.qemu_library_path.resolve())
    with tempfile.TemporaryDirectory(prefix='hackios-gpu-') as d:
        work = Path(d)
        build(root, work)
        args, env = prepare_worker(a, root, work)
        lhs, rhs = socket.socketpair()
        with lhs, rhs, (work / 'qemu.log').open('wb') as log:
            guest = subprocess.Popen([str(a.qemu.resolve()), '-M', 'virt', '-cpu', 'cortex-a76', '-m', '128M',
                                      '-accel', 'tcg', '-nodefaults', '-display', 'none', '-monitor', 'none',
                                      '-chardev', 'stdio,id=bridge,signal=off', '-serial', 'chardev:bridge',
                                      '-device', 'loader,file=guest.bin,addr=0x40200000,cpu-num=0,force-raw=on'],
                                     stdin=lhs, stdout=lhs, stderr=log, cwd=work, env=qenv)
            lhs.close()
            try:
                worker = subprocess.Popen(args, stdin=rhs, stdout=rhs, cwd=work, env=env)
                rhs.close()
                try:
                    if worker.wait(12):
                        raise RuntimeError(f'{a.backend} serial worker failed')
                finally:
                    stop(worker)
            except (RuntimeError, subprocess.TimeoutExpired) as exc:
                print((work / 'qemu.log').read_text(errors='replace'))
                raise RuntimeError('no successful guest pixel acknowledgement') from exc
            finally:
                stop(guest)


if __name__ == '__main__':
    main()
