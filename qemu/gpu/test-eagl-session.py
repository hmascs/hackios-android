#!/usr/bin/env python3
"""Verify the native EAGL command stream (not a system SpringBoard test)."""
import argparse
import os
from pathlib import Path
import re
import struct
import subprocess
import tempfile


def words(*values):
    return struct.pack('<' + 'I' * len(values), *(v & 0xffffffff for v in values))


SCALAR = dict(re.findall(r'GL_(?:VOID|UINT)\((\d+), (gl\w+),',
                        Path(__file__).with_name('eagl-scalars.def').read_text()))
SCALAR = {v: int(k) for k, v in SCALAR.items()}
OPEN, CLOSE, STRING, SOURCE, SHADER_IV, PROGRAM_IV, LOG, ATTRIB, UNIFORM, GEN = range(1, 11)
TEXTURE, DRAW, READ, INTEGER, END = range(13, 18)


class Session:
    def __init__(self, process):
        self.p = process
        self.seq = 0
        self.slot = 1
        self.reads = []

    def command(self, op, data=b'', *, status=0):
        self.seq += 1
        self.p.stdin.write(b'HGQ1' + words(self.seq, op, self.slot, len(data)) + data)
        self.p.stdin.flush()
        h = self.p.stdout.read(20)
        if len(h) != 20:
            raise RuntimeError(f'short reply for op {op} (return {self.p.poll()})')
        assert h[:4] == b'HGR1'
        seq, reply_op, code, size = struct.unpack('<4I', h[4:])
        assert (seq, reply_op, code) == (self.seq, op, status), (op, code, status)
        assert size <= 32 * 1024 * 1024
        result = self.p.stdout.read(size)
        assert len(result) == size
        if op == READ and not status:
            self.reads.append((seq, size))
        return result

    def gl(self, name, *args):
        data = b''.join(struct.pack('<f', x) if isinstance(x, float) else words(x) for x in args)
        return self.command(SCALAR[name], data)

    def shader(self, kind, source):
        shader, = struct.unpack('<I', self.gl('glCreateShader', kind))
        assert shader
        self.command(SOURCE, words(shader) + source + b'\0')
        self.gl('glCompileShader', shader)
        if self.command(SHADER_IV, words(shader, 0x8b81)) != words(1):
            raise RuntimeError(self.command(LOG, words(shader, 1)).decode('utf8', errors='replace'))
        return shader

    def program(self, vs, fs, attributes=()):
        vs = self.shader(0x8b31, vs)
        fs = self.shader(0x8b30, fs)
        program, = struct.unpack('<I', self.gl('glCreateProgram'))
        self.gl('glAttachShader', program, vs)
        self.gl('glAttachShader', program, fs)
        for i, name in enumerate(attributes):
            self.command(ATTRIB, words(program, i) + name + b'\0')
        self.gl('glLinkProgram', program)
        if self.command(PROGRAM_IV, words(program, 0x8b82)) != words(1):
            raise RuntimeError(self.command(LOG, words(program, 2)).decode('utf8', errors='replace'))
        self.gl('glDeleteShader', vs)
        self.gl('glDeleteShader', fs)
        return program


def render(s, renderer, trace):
    s.command(READ, words(2, 2), status=2)  # no current context
    s.command(OPEN, words(3))
    assert renderer.encode() in s.command(STRING, words(0x1f01))
    subpixel_bits, = struct.unpack('<I', s.command(INTEGER, words(0x0d50)))
    assert subpixel_bits >= 4
    if trace:
        # Redwood records these two GLSL sources as contiguous writes, but other
        # services share serial; extract only the captured first pair, no guessing.
        log = re.sub(rb'\r+\n', b'\n', trace.read_bytes())
        captured = re.findall(rb'redwood-gl: the first shaders, for the record:\n(#[^\0]*?\n}\n)', log)
        if len(captured) != 2 or any(len(src) > 4096 or b'launchd|' in src for src in captured):
            raise ValueError('expected the two complete Redwood shader dumps')
        s.program(*captured, attributes=(b'vertex_position', b'vertex_color'))
        print('Captured CoreAnimation GLSL pair compiled and linked by', renderer, flush=True)
    vs = b'#version 300 es\nlayout(location=0) in vec2 p; void main(){gl_Position=vec4(p,0.0,1.0);}'
    fs = (b'#version 300 es\nprecision highp float; uniform sampler2D t; uniform vec2 uv[2]; '
          b'uniform vec3 shift; uniform float bias; out vec4 o; '
          b'void main(){o=texture(t,uv[0]+uv[1]+shift.xy+vec2(shift.z+bias));}')
    program = s.program(vs, fs)
    assert s.gl('glIsProgram', program) == words(1)
    assert s.gl('glIsProgram', 0) == words(0)
    s.gl('glUseProgram', program)
    loc, = struct.unpack('<i', s.command(UNIFORM, words(program) + b't\0'))
    assert loc >= 0
    s.gl('glUniform1i', loc, 0)
    uv, = struct.unpack('<i', s.command(UNIFORM, words(program) + b'uv[0]\0'))
    assert uv >= 0
    packet_uv = words(uv, 2, 2) + struct.pack('<4f', .0625, .125, .25, .1875)
    s.command(12, packet_uv[:-4], status=1)
    s.command(12, packet_uv)
    shift, = struct.unpack('<i', s.command(UNIFORM, words(program) + b'shift\0'))
    bias, = struct.unpack('<i', s.command(UNIFORM, words(program) + b'bias\0'))
    assert shift >= 0 and bias >= 0
    s.command(12, words(shift, 1, 3) + struct.pack('<3f', .0625, .0625, .0625))
    s.command(12, words(bias, 1, 1) + struct.pack('<f', .125))
    s.gl('glActiveTexture', 0x84c0)
    inp, dest = struct.unpack('<II', s.command(GEN, words(1, 2)))
    s.gl('glBindTexture', 0xde1, inp)
    s.gl('glTexParameteri', 0xde1, 0x2801, 0x2600)
    s.gl('glTexParameteri', 0xde1, 0x2800, 0x2600)
    s.gl('glTexParameteri', 0xde1, 0x2802, 0x812f)
    assert s.command(24, words(0xde1, 0x2800)) == words(0x2600)
    assert s.command(24, words(0xde1, 0x2802)) == words(0x812f)
    tint = bytes([61, 167, 251, 255])
    s.command(TEXTURE, words(1, 1, 1) + tint[:-1], status=1)
    s.command(TEXTURE, words(1, 1, 1) + tint)
    s.gl('glBindTexture', 0xde1, dest)
    s.command(TEXTURE, words(32, 32, 1))
    assert s.command(23, words(0xde1, 0x1000, 0)) == words(32)  # GL_TEXTURE_WIDTH
    assert s.command(23, words(0xde1, 0x1001, 0)) == words(32)  # GL_TEXTURE_HEIGHT
    assert s.command(23, words(0xde1, 0x1003, 0)) == words(0x8058)  # GL_RGBA8
    s.command(23, words(0xde1, 0x1000, 15), status=1)
    fbo, = struct.unpack('<I', s.command(GEN, words(2, 1)))
    s.gl('glBindFramebuffer', 0x8d40, fbo)
    s.gl('glFramebufferTexture2D', 0x8d40, 0x8ce0, 0xde1, dest, 0)
    assert s.gl('glCheckFramebufferStatus', 0x8d40) == words(0x8cd5)
    assert s.command(21, words(0x8d40, 0x8ce0, 0x8cd1)) == words(dest)
    s.gl('glViewport', 0, 0, 32, 32)
    s.gl('glBindTexture', 0xde1, inp)
    s.gl('glClearColor', .25, .5, .75, 1.)
    assert s.command(22, words(0x0c22)) == struct.pack('<4f', .25, .5, .75, 1.)
    s.command(22, words(0x0ba2), status=1)
    s.gl('glClearColor', 0., 0., 0., 1.)
    s.gl('glClear', 0x4000)
    packet = b'HAA1' + words(4, 3, 0x1401, 3, 1, 0, 2, 0x1406, 0, 24) + b'\x00\x01\x02'
    packet += struct.pack('<6f', -.8, -.8, .8, -.8, 0, .8)
    s.command(DRAW, packet[:-1], status=1)
    s.command(DRAW, packet)
    fence = s.command(18, words(0x9117, 0))
    assert len(fence) == 4 and fence != words(0)
    # Switch EGL context; state in slot 1 must survive and not leak to slot 2.
    s.slot = 2
    s.command(OPEN, words(3))
    assert s.command(INTEGER, words(0x8ca6)) == words(0)
    s.command(CLOSE)
    s.command(READ, words(2, 2), status=2)
    s.slot = 1
    assert s.command(INTEGER, words(0x8ca6)) == words(fbo)
    assert s.command(19, fence + words(1, 1000000000, 0)) in (words(0x911a), words(0x911c))
    s.command(20, fence)
    s.command(19, fence + words(0, 0, 0), status=1)
    rgba = s.command(READ, words(32, 32))
    assert len(rgba) == 4096 and rgba[:4] == bytes([0, 0, 0, 255])
    center = (32 * 16 + 16) * 4
    assert rgba[center:center + 4] == tint
    # (.5625,.5625) selects the last texel; omitting either new uniform selects another.
    s.command(TEXTURE, words(2, 2, 5) + bytes([7, 19, 23, 41, 51, 89, 53, 192]))
    s.command(25, words(1, 1, 5, 15) + bytes([203, 80]), status=1)
    s.command(25, words(1, 1, 5, 1) + bytes([203]), status=1)
    s.command(25, words(1, 1, 5, 1) + bytes([203, 80]))
    assert s.command(23, words(0xde1, 0x1000, 0)) == words(2)
    assert s.command(23, words(0xde1, 0x1000, 1)) == words(1)
    assert s.command(23, words(0xde1, 0x1003, 1)) == words(0x822b)  # GL_RG8
    s.command(DRAW, packet)
    rgba = s.command(READ, words(32, 32))
    assert rgba[center:center + 4] == bytes([53, 53, 53, 192])
    s.gl('glTexParameteri', 0xde1, 0x813c, 1)  # GL_TEXTURE_BASE_LEVEL
    s.command(DRAW, packet)
    rgba = s.command(READ, words(32, 32))
    assert rgba[center:center + 4] == bytes([203, 203, 203, 80])
    # No vertex arrays: gl_VertexID must start at 7, not be rebased to 0.
    procedural = s.program(
        b'#version 300 es\nvoid main(){int i=gl_VertexID-7; '
        b'vec2 p=vec2(-.8,-.8); if(i==1)p=vec2(.8,-.8); if(i==2)p=vec2(0,.8); '
        b'gl_Position=vec4(p,0,1);}',
        b'#version 300 es\nprecision highp float; out vec4 o; void main(){o=vec4(0,1,0,1);}')
    s.gl('glUseProgram', procedural)
    s.command(26, words(4, 0x7fffffff, 4), status=1)
    s.command(26, words(4, 7, 4))  # GL ignores the incomplete last triangle.
    rgba = s.command(READ, words(32, 32))
    assert rgba[center:center + 4] == bytes([0, 255, 0, 255])
    assert s.gl('glGetError') == words(0)
    s.command(CLOSE)
    s.slot = 0
    s.command(END)
    print('EAGL stream: isolated contexts, shader, texture, FBO, indexed draw and pixels verified', flush=True)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('worker', type=Path)
    p.add_argument('renderer')
    p.add_argument('--trace', type=Path, help='optional actual Redwood shader dump in serial log')
    p.add_argument('--profile-readback', action='store_true')
    a = p.parse_args()
    env = os.environ.copy()
    env['HACKIOS_EAGL_PROFILE_READBACK'] = '1' if a.profile_readback else '0'
    with tempfile.TemporaryFile() as log:
        with subprocess.Popen([a.worker.resolve(strict=True), a.renderer], stdin=subprocess.PIPE,
                              stdout=subprocess.PIPE, stderr=log, env=env) as child:
            try:
                session = Session(child)
                render(session, a.renderer, a.trace)
                assert child.wait(timeout=5) == 0
            finally:
                if child.poll() is None:
                    child.kill()
                    child.wait()
                log.seek(0)
                text = log.read().decode(errors='replace')
                print(text, end='', flush=True)
        matches = re.findall(r'^EAGL readback timing: seq=(\d+) read=(\d+) bytes=(\d+) '
                             r'host_dispatch_us=(\d+) socket_write_us=(\d+)'
                             r'(?: gl_readback_us=(\d+) convert_us=(\d+))?$', text, re.M)
        if a.profile_readback:
            assert [(int(m[0]), int(m[2])) for m in matches] == session.reads
            assert [int(m[1]) for m in matches] == list(range(1, len(matches) + 1))
        else:
            assert 'EAGL readback timing:' not in text


if __name__ == '__main__':
    main()
