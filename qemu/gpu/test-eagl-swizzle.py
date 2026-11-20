#!/usr/bin/env python3
"""Real-worker shader sampling oracle for logical user/legacy texture swizzles.

Default is the minimal D4 regression: the same user swizzle must survive an
ordinary RGBA base reupload. --full adds format transitions, getters, mip and
texture/context lifetime checks. This is not a system animation/FPS test.
"""
import argparse
import hashlib
import json
from pathlib import Path
import runpy
import struct
import subprocess
import tempfile

R, G, B, A, ZERO, ONE = 0x1903, 0x1904, 0x1905, 0x1906, 0, 1
P = (0x8e42, 0x8e43, 0x8e44, 0x8e45)
TEX, FBO = 0xde1, 0x8d40
RGBA = bytes([17, 63, 211, 97])
W = H = 8


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('worker', type=Path)
    p.add_argument('renderer')
    p.add_argument('--full', action='store_true')
    p.add_argument('--result', type=Path)
    a = p.parse_args()
    f = runpy.run_path(str(Path(__file__).with_name('test-eagl-session.py')))
    Session, words = f['Session'], f['words']
    result = {'scope': 'same worker EGL context shader sampling, not iOS animation',
              'worker_wrapper': str(a.worker.resolve()), 'cases': []}
    try:
        with tempfile.TemporaryFile() as log:
            child = subprocess.Popen([str(a.worker.resolve()), a.renderer],
                                     stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=log)
            try:
                s = Session(child)
                s.command(1, words(3))
                program = s.program(
                    b'#version 300 es\nvoid main(){int i=gl_VertexID;vec2 p=vec2(float((i<<1)&2),float(i&2));gl_Position=vec4(p*2.-1.,0,1);}',
                    b'#version 300 es\nprecision highp float;uniform sampler2D tex;out vec4 c;void main(){c=texture(tex,vec2(.5));}')
                loc, = struct.unpack('<i', s.command(9, words(program) + b'tex\0'))
                sink, = struct.unpack('<I', s.command(10, words(1, 1)))
                target, = struct.unpack('<I', s.command(10, words(2, 1)))
                s.gl('glBindTexture', TEX, sink)
                s.command(13, words(W, H, 1) + bytes(W * H * 4))
                s.gl('glBindFramebuffer', FBO, target)
                s.gl('glFramebufferTexture2D', FBO, 0x8ce0, TEX, sink, 0)
                s.gl('glViewport', 0, 0, W, H)
                source, = struct.unpack('<I', s.command(10, words(1, 1)))
                s.gl('glBindTexture', TEX, source)
                for name, value in ((0x2801, 0x2600), (0x2800, 0x2600), (0x2802, 0x812f), (0x2803, 0x812f)):
                    s.gl('glTexParameteri', TEX, name, value)

                def upload(fmt, data, width=1, height=1, level=0):
                    s.gl('glBindTexture', TEX, source)
                    s.command(25 if level else 13, words(width, height, fmt, level) + data if level
                              else words(width, height, fmt) + data)

                def set_user(channels):
                    for name, value in zip(P, channels):
                        s.gl('glTexParameteri', TEX, name, value)

                def get_user():
                    return tuple(struct.unpack('<I', s.command(24, words(TEX, name)))[0] for name in P)

                def sample(label, expected):
                    s.gl('glBindFramebuffer', FBO, target)
                    s.gl('glUseProgram', program)
                    s.gl('glActiveTexture', 0x84c0)
                    s.gl('glBindTexture', TEX, source)
                    s.gl('glUniform1i', loc, 0)
                    s.command(26, words(4, 0, 3))
                    got = s.command(15, words(W, H))
                    error, = struct.unpack('<I', s.gl('glGetError'))
                    want = expected * (W * H)
                    result['cases'].append({'case': label, 'pixel': list(got[:4]),
                        'expected_pixel': list(expected), 'rgba_sha256': hashlib.sha256(got).hexdigest(),
                        'gl_error': error, 'pass': got == want and error == 0})
                    assert got == want and error == 0, (label, got[:4].hex(), expected.hex(), error)

                upload(1, RGBA)
                set_user((B, G, R, ONE))
                sample('custom before RGBA reupload', bytes([211, 63, 17, 255]))
                upload(1, RGBA)
                sample('custom survives RGBA base reupload', bytes([211, 63, 17, 255]))
                if a.full:
                    assert get_user() == (B, G, R, ONE)
                    s.gl('glTexParameteri', TEX, P[0], 0xdead)
                    error, = struct.unpack('<I', s.gl('glGetError'))
                    assert error == 0x500, error  # GL_INVALID_ENUM
                    assert get_user() == (B, G, R, ONE)
                    # Binding zero is explicitly unsupported: fail the wire
                    # operation rather than silently lose logical state.
                    s.gl('glBindTexture', TEX, 0)
                    s.command(84, words(TEX, P[0], R), status=1)
                    s.command(24, words(TEX, P[0]), status=1)
                    s.command(13, words(1, 1, 1) + RGBA, status=1)
                    s.gl('glBindTexture', TEX, source)
                    assert get_user() == (B, G, R, ONE)
                    # Original logical identity -> legacy ALPHA -> RGBA identity.
                    set_user((R, G, B, A))
                    upload(3, bytes([73]))
                    sample('ALPHA logical identity', bytes([0, 0, 0, 73]))
                    assert get_user() == (R, G, B, A)
                    # Explicit ZERO is equal to today's physical R swizzle for
                    # ALPHA but must remain a user setting after RGBA transition.
                    s.gl('glTexParameteri', TEX, P[0], ZERO)
                    upload(1, RGBA)
                    sample('same-value setter is not guessed from physical state', bytes([0, 63, 211, 97]))
                    assert get_user() == (ZERO, G, B, A)
                    set_user((A, B, R, ONE))
                    upload(5, bytes([85, 119]))
                    sample('LA composed custom user swizzle', bytes([119, 85, 85, 255]))
                    upload(5, bytes([41, 29]))
                    sample('LA reupload preserves custom user swizzle', bytes([29, 41, 41, 255]))
                    # Mip compatibility uses logical base format, not physical
                    # alpha swizzle, which this valid user maps to ONE.
                    upload(5, bytes([85, 119]) * 4, 2, 2)
                    upload(5, bytes([33, 66]), 1, 1, 1)
                    s.gl('glTexParameteri', TEX, 0x813c, 1)
                    sample('LA mip with user alpha ONE', bytes([66, 33, 33, 255]))
                    s.gl('glTexParameteri', TEX, 0x813c, 0)
                    upload(1, RGBA)
                    sample('legacy to RGBA keeps logical user mapping', bytes([97, 211, 17, 255]))
                    assert get_user() == (A, B, R, ONE)
                    # Delete/rebind identical name resets logical parameter state.
                    s.command(11, words(1, 1, source))
                    s.gl('glBindTexture', TEX, source)
                    upload(1, RGBA)
                    for name, value in ((0x2801, 0x2600), (0x2800, 0x2600)):
                        s.gl('glTexParameteri', TEX, name, value)
                    assert get_user() == (R, G, B, A)
                    sample('deleted name rebound starts with identity', RGBA)
                    s.slot = 2
                    s.command(1, words(3))
                    s.gl('glBindTexture', TEX, source)
                    assert get_user() == (R, G, B, A)
                    set_user((B, G, R, ONE))
                    s.command(2)
                    s.command(1, words(3))
                    s.gl('glBindTexture', TEX, source)
                    assert get_user() == (R, G, B, A)
                    s.command(2)
                    s.slot = 1
                    s.gl('glBindTexture', TEX, source)
                    assert get_user() == (R, G, B, A)
                s.command(2)
                s.slot = 0
                s.command(17)
                assert child.wait(timeout=10) == 0
                result['status'] = 'pass'
            finally:
                if child.poll() is None:
                    child.terminate()
                    try:
                        child.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        child.kill(); child.wait()
                log.seek(0)
                result['worker_log'] = log.read().decode(errors='replace')
    except Exception as exc:
        result['status'] = 'fail'
        result['error'] = repr(exc)
        raise
    finally:
        if a.result:
            with a.result.open('x') as out:
                json.dump(result, out, indent=2); out.write('\n')
        print(json.dumps(result, indent=2), flush=True)


if __name__ == '__main__':
    main()
