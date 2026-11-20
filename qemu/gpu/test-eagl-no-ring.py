#!/usr/bin/env python3
"""A NO_REPLY readback against a worker with no ring must still return the picture.

A6 turns the pixel transfer off, but the guest has to find out whether the
worker really has a ring: it asks with HIOS_EAGL_READ_NO_REPLY and reads the
answer as the signal. A worker that accepted the request with no ring would
publish the frame nowhere and answer with no pixels at all -- the guest would
then keep its surface untouched forever, and the screen would freeze with
nothing in any log. So the rule is: no ring, ordinary reply. This test is the
one that keeps that rule honest.

Same plumbing as test-eagl-frame-ring.py, minus --frame-ring.
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


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('worker', type=Path, help='the zink-session.sh wrapper')
    p.add_argument('renderer')
    a = p.parse_args()
    width = height = 32
    with tempfile.TemporaryDirectory() as directory:
        # Deliberately no --frame-ring: this is the "worker has no ring" worker.
        env = dict(os.environ, HACKIOS_EAGL_PROFILE_READBACK='1')
        with tempfile.TemporaryFile() as log:
            with subprocess.Popen([str(a.worker.resolve()), a.renderer],
                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=log,
                                  env=env) as child:
                s = Session(child)
                s.command(1, words(3))                                  # EA_OPEN
                texture, = struct.unpack('<I', s.command(GEN, words(1, 1)))
                fbo, = struct.unpack('<I', s.command(GEN, words(2, 1)))
                s.gl('glBindTexture', 0xde1, texture)
                s.command(13, words(width, height, 1) + bytes(width * height * 4))   # EA_TEXTURE_BASE
                s.gl('glBindFramebuffer', 0x8d40, fbo)
                s.gl('glFramebufferTexture2D', 0x8d40, 0x8ce0, 0xde1, texture, 0)
                s.gl('glViewport', 0, 0, width, height)
                s.gl('glClearColor', .25, .5, .75, 1.)
                s.gl('glClear', 0x4000)
                reply = s.command(READ, words(width, height, SWAP | DEST_ROWS | NO_REPLY))
                s.command(CLOSE)
                s.slot = 0
                s.command(END)
                assert child.wait(timeout=10) == 0
            log.seek(0)
            worker_log = log.read().decode(errors='replace')
        # The whole picture, not the header alone: 32x32 RGBA.
        assert len(reply) == width * height * 4, len(reply)
        # SWAP and DEST_ROWS were asked for, so the reply is already BGRA and
        # already flipped by the worker: B=191 G=128 R=64 A=255.
        assert reply[0:4] == bytes([191, 128, 64, 255]), reply[0:4]
        assert 'frame ring' not in worker_log, worker_log
        print('EAGL no-ring fallback: NO_REPLY still returns the converted picture')


if __name__ == '__main__':
    main()
