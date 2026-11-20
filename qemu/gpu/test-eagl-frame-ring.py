#!/usr/bin/env python3
"""Verify the worker -> App frame ring (docs/gl-frame-ring.md) without a guest.

Starts the worker with --frame-ring on a ring file this script creates and draws
known colours into a 32x32 framebuffer. The default --ring-version=1 explicitly
exercises the legacy HFRM worker; --ring-version=2 explicitly exercises HFR2,
whose geometry is fixed and whose publication counter at 24 is independent of
its odd/even guard at 40. This sequential integration test checks completed
publications, not concurrent memory ordering (covered by the C/JVM tests).
"""
import argparse
import os
from pathlib import Path
import runpy
import struct
import subprocess
import tempfile

Session = runpy.run_path(str(Path(__file__).with_name('test-eagl-session.py')))['Session']
words = runpy.run_path(str(Path(__file__).with_name('test-eagl-session.py')))['words']
GEN, READ, CLOSE, END = 10, 15, 2, 17
SWAP, DEST_ROWS, NO_REPLY = 1, 2, 4


def bind_framebuffer(s, width, height):
    s.command(1, words(3))  # EA_OPEN
    texture, = struct.unpack('<I', s.command(GEN, words(1, 1)))
    fbo, = struct.unpack('<I', s.command(GEN, words(2, 1)))
    s.gl('glBindTexture', 0xde1, texture)
    s.command(13, words(width, height, 1) + bytes(width * height * 4))
    s.gl('glBindFramebuffer', 0x8d40, fbo)
    s.gl('glFramebufferTexture2D', 0x8d40, 0x8ce0, 0xde1, texture, 0)
    s.gl('glViewport', 0, 0, width, height)


def close_session(s, child):
    s.command(CLOSE)
    s.slot = 0
    s.command(END)
    assert child.wait(timeout=10) == 0


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('worker', type=Path, help='the zink-session.sh wrapper')
    p.add_argument('renderer')
    p.add_argument('--ring-version', type=int, choices=(1, 2), default=1,
                   help='1: legacy HFRM worker (default); 2: HFR2 worker, fixed geometry and guard')
    a = p.parse_args()
    width = height = 32
    stride = width * 4
    with tempfile.TemporaryDirectory() as directory:
        ring = Path(directory) / 'gpu-frames.ring'
        # Legacy HFRM changes geometry on publish. HFR2 deliberately rejects
        # non-panel geometry, so create the exact dimensions in explicit v2 mode.
        ring_width = ring_height = 64 if a.ring_version == 1 else width
        ring_stride = ring_width * 4
        magic = b'HFRM' if a.ring_version == 1 else b'HFR2'
        header = bytearray(64)
        struct.pack_into('<4sIIIIIQQ', header, 0, magic, a.ring_version,
                         ring_width, ring_height, ring_stride, 1, 0, 0)
        # offset 24: complete publications; offset 32: monotonic timestamp;
        # offset 40: HFR2 guard. All start at zero, including reserved bytes.
        with ring.open('wb') as f:
            f.write(header)
            f.truncate(64 + ring_stride * ring_height)

        def verify_publication(expected_count, pixel):
            # Worker is waiting for its next command: no writer overlaps this
            # snapshot. Do not call this a Python acquire/seqlock implementation.
            data = ring.read_bytes()
            values = struct.unpack_from('<4sIIIIIQQ', data, 0)
            observed_magic, version, w, h, observed_stride, fmt, count, stamp = values
            assert (observed_magic, version, w, h, observed_stride, fmt) == \
                (magic, a.ring_version, width, height, stride, 1), values
            assert count == expected_count, (count, expected_count)
            assert stamp > 0
            if a.ring_version == 2:
                guard, = struct.unpack_from('<Q', data, 40)
                assert guard == expected_count * 2 and not guard & 1, (guard, count)
            assert data[64:64 + stride * height] == pixel * (width * height)

        env = dict(os.environ, HACKIOS_EAGL_PROFILE_READBACK='1')
        with tempfile.TemporaryFile() as log:
            with subprocess.Popen([str(a.worker.resolve()), a.renderer, '--frame-ring', str(ring)],
                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=log,
                                  env=env) as child:
                s = Session(child)
                bind_framebuffer(s, width, height)
                s.gl('glClearColor', .25, .5, .75, 1.)
                s.gl('glClear', 0x4000)
                reply = s.command(READ, words(width, height, SWAP | DEST_ROWS | NO_REPLY))
                assert reply == b'', reply
                # glClearColor(.25,.5,.75) -> R=64 G=128 B=191; the ring is BGRA.
                verify_publication(1, bytes([191, 128, 64, 255]))
                if a.ring_version == 2:
                    # A normal synchronous readback must also publish. The test
                    # above uses NO_REPLY only as an isolated ring fixture; the
                    # production guest must retain its IOSurface pixel reply.
                    s.gl('glClearColor', 1., 0., 0., 1.)
                    s.gl('glClear', 0x4000)
                    expected = bytes([0, 0, 255, 255])
                    reply = s.command(READ, words(width, height, SWAP | DEST_ROWS))
                    assert reply == expected * (width * height)
                    verify_publication(2, expected)
                    # A non-panel readback still returns pixels to the guest,
                    # but must not resize or overwrite the fixed HFR2 ring.
                    s.gl('glClearColor', 0., 1., 0., 1.)
                    s.gl('glClear', 0x4000)
                    green = bytes([0, 255, 0, 255])
                    reply = s.command(READ, words(16, 16, SWAP | DEST_ROWS | NO_REPLY))
                    assert reply == green * (16 * 16)
                    verify_publication(2, expected)
                    reply = s.command(READ, words(width, height, SWAP | DEST_ROWS))
                    assert reply == green * (width * height)
                    verify_publication(3, green)
                close_session(s, child)
            log.seek(0)
            worker_log = log.read().decode(errors='replace')
        expected_log = 'frame ring at' if a.ring_version == 1 else 'HFR2 frame ring'
        assert expected_log in worker_log, worker_log
        if a.ring_version == 2:
            # A v2 worker must refuse an old magic without breaking ordinary
            # guest I/O. Even NO_REPLY must fall back to complete pixels.
            legacy_ring = Path(directory) / 'legacy-v1.ring'
            legacy_header = bytearray(64)
            struct.pack_into('<4sIIIIIQQ', legacy_header, 0,
                             b'HFRM', 1, width, height, stride, 1, 0, 0)
            legacy_data = bytes(legacy_header) + bytes(stride * height)
            legacy_ring.write_bytes(legacy_data)
            with tempfile.TemporaryFile() as log:
                with subprocess.Popen([str(a.worker.resolve()), a.renderer,
                                       '--frame-ring', str(legacy_ring)],
                                      stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                      stderr=log, env=env) as child:
                    s = Session(child)
                    bind_framebuffer(s, width, height)
                    s.gl('glClearColor', 0., 0., 1., 1.)
                    s.gl('glClear', 0x4000)
                    blue = bytes([255, 0, 0, 255])
                    reply = s.command(READ, words(width, height, SWAP | DEST_ROWS | NO_REPLY))
                    assert reply == blue * (width * height)
                    close_session(s, child)
                log.seek(0)
                rejected_log = log.read().decode(errors='replace')
            assert 'incompatible frame ring' in rejected_log, rejected_log
            assert legacy_ring.read_bytes() == legacy_data, 'v2 worker modified an incompatible v1 ring'
        print(f'EAGL frame ring v{a.ring_version}: complete BGRA pixels and publication count verified'
              + ('; guard, geometry mismatch and incompatible-v1 fallback verified'
                 if a.ring_version == 2 else ''))


if __name__ == '__main__':
    main()
