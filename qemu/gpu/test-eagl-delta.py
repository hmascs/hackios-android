#!/usr/bin/env python3
"""Verify the delta readback (HIOS_EAGL_READ_DELTA) against a real worker.

Every frame is drawn with glClear only -- the whole picture, or a rectangle of
rows with the scissor test -- so the expected bytes are known exactly here. The
test applies each reply to a model of the guest surface the same way
eagl-guest-surface.c does, and compares that with the expected frame: that is
the correctness assertion.

The worker keeps one base frame per surface, so the test also drives two
surfaces in rotation with the panel's padded strides (4544/4608). That is what
CoreAnimation does on the device (the two strides alternate every frame); with
a single base frame every switch would resync a whole frame and the delta would
buy almost nothing. The rotation must show only the first read of each surface
coming whole, and every later read -- including the 0-band "nothing changed"
ones -- coming as bands.

No guest and no QEMU: only the worker and this file.
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
OPEN, CLOSE, TEXTURE, READ, END, GEN = 1, 2, 13, 15, 17, 10
SWAP, DEST_ROWS, NO_REPLY, DELTA = 1, 2, 4, 8
FULL, PART = b'FULL', b'DELT'
BAND_ROWS = 16

# 100 rows is six full bands and a four-row last band. The row itself is
# 64 * 4 bytes, but the surface strides are the panel's real ones (4500-byte
# rows in 4544/4608-byte slots) to prove the stride is part of the identity.
WIDTH, HEIGHT = 64, 100
ROW = WIDTH * 4
STRIDE_A, STRIDE_B = 4544, 4608
KEY_A = 0x7fff0000          # a surface handle: the guest's base address
KEY_C, KEY_D = 0x7ffe0000, 0x7ffe8000
COLOR_CLEAR, COLOR_BAND, COLOR_BAND_2 = b'\x40\x80\xbf\xff', b'\x00\xff\x00\xff', b'\xff\x00\x00\xff'
COLOR_TAIL, COLOR_HALF, COLOR_MOST = b'\x00\x00\xff\xff', b'\xc0\xc0\xc0\xff', b'\x20\x40\x60\xff'
COLOR_WHOLE, COLOR_SMALL = b'\xff\xff\x00\xff', b'\x80\x00\x80\xff'
COLOR_BAND_3, COLOR_BAND_4 = b'\x11\x22\x33\xff', b'\x44\x55\x66\xff'


def clear(s, color, rows=None):
    s.gl('glClearColor', color[0] / 255, color[1] / 255, color[2] / 255, color[3] / 255)
    if rows is None:
        s.gl('glDisable', 0x0c11)                       # GL_SCISSOR_TEST
    else:
        # Only the 64x100 target is cleared by rows; the resized one is uniform.
        y, height = rows
        s.gl('glEnable', 0x0c11)
        s.gl('glScissor', 0, y, WIDTH, height)
    s.gl('glClear', 0x4000)                             # GL_COLOR_BUFFER_BIT


def show(s, base_color, bands=()):
    """Make the framebuffer hold that surface's picture, band by band."""
    clear(s, base_color)
    for y, color in bands:
        clear(s, color, (y, BAND_ROWS))


def surface_bytes(color):
    """The colour as the surface holds it, which is BGRA (the reply is SWAPped)."""
    return bytes([color[2], color[1], color[0], color[3]])


def model(color, rows=None, base=None):
    """What the surface must hold after that clear: bytes, top row first."""
    image = bytearray(base) if base is not None else bytearray(WIDTH * HEIGHT * 4)
    span = surface_bytes(color) * WIDTH
    y0, height = rows if rows is not None else (0, HEIGHT)
    for y in range(y0, y0 + height):
        image[y * ROW:(y + 1) * ROW] = span
    return image


def picture(base_color, bands=()):
    image = model(base_color)
    for y, color in bands:
        image = model(color, (y, BAND_ROWS), image)
    return image


def apply(surface, reply):
    """Apply a reply to the guest surface model, exactly as the guest does."""
    plain = WIDTH * HEIGHT * 4
    if reply[:4] == FULL:
        assert len(reply) == plain + 4, (len(reply), plain + 4)
        return bytearray(reply[4:])
    assert reply[:4] == PART, reply[:4]
    surface = bytearray(surface)
    count, = struct.unpack_from('<I', reply, 4)
    assert count <= (HEIGHT + BAND_ROWS - 1) // BAND_ROWS, count
    table = struct.unpack_from('<' + 'II' * count, reply, 8)
    at, previous = 8 + 8 * count, 0
    for i in range(count):
        y, rows = table[2 * i:2 * i + 2]
        assert 0 < rows <= BAND_ROWS and y >= previous and y + rows <= HEIGHT, (y, rows, previous)
        previous = y + rows
        end = at + rows * ROW
        surface[y * ROW:(y + rows) * ROW] = reply[at:end]
        at = end
    assert at == len(reply), (at, len(reply))
    return surface


def expect(reply, kind, *, bands=None):
    assert reply[:4] == kind, (reply[:4], kind)
    if kind == PART:
        count, = struct.unpack_from('<I', reply, 4)
        if bands is not None:
            assert count == bands, (count, bands)
    return reply


def delta_read(s, key, stride, width=WIDTH, height=HEIGHT, flags=SWAP):
    """One DELTA request naming its surface (handle, stride, size)."""
    return s.command(READ, words(width, height, flags | DELTA,
                                 key & 0xffffffff, (key >> 32) & 0xffffffff, stride))


def open_context(s):
    s.command(OPEN, words(3))


def bind_target(s, width, height):
    """A fresh texture with a framebuffer on it, as the guest's surface is."""
    texture, = struct.unpack('<I', s.command(GEN, words(1, 1)))
    fbo, = struct.unpack('<I', s.command(GEN, words(2, 1)))
    s.gl('glBindTexture', 0xde1, texture)
    s.command(TEXTURE, words(width, height, 1) + bytes(width * height * 4))
    s.gl('glBindFramebuffer', 0x8d40, fbo)
    s.gl('glFramebufferTexture2D', 0x8d40, 0x8ce0, 0xde1, texture, 0)
    s.gl('glViewport', 0, 0, width, height)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('worker', type=Path, help='the zink-session.sh wrapper')
    p.add_argument('renderer')
    a = p.parse_args()
    frames = []
    with tempfile.TemporaryFile() as log:
        with subprocess.Popen([str(a.worker.resolve()), a.renderer],
                              stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=log,
                              env=dict(os.environ, HACKIOS_EAGL_PROFILE_READBACK='1')) as child:
            s = Session(child)
            plain_frame = WIDTH * HEIGHT * 4
            open_context(s)
            bind_target(s, WIDTH, HEIGHT)
            surface = bytearray(WIDTH * HEIGHT * 4)

            # 1. Nothing has been sent yet: the first read of a surface is whole.
            show(s, COLOR_CLEAR)
            expected = model(COLOR_CLEAR)
            reply = expect(delta_read(s, KEY_A, STRIDE_A), FULL)
            surface = apply(surface, reply)
            assert surface == expected, 'frame 1 (first readback)'
            frames.append(('first read of a surface', FULL, HEIGHT, HEIGHT, len(reply), plain_frame))

            # 2. One band changed in the middle.
            clear(s, COLOR_BAND, (16, 16))
            expected = model(COLOR_BAND, (16, 16), expected)
            reply = expect(delta_read(s, KEY_A, STRIDE_A), PART, bands=1)
            assert len(reply) == 8 + 8 + 16 * ROW, len(reply)
            surface = apply(surface, reply)
            assert surface == expected, 'frame 2 (one band)'
            frames.append(('one band at y=16', PART, HEIGHT, 16, len(reply), plain_frame))

            # 3. Another band, to prove the row table is per frame, not sticky.
            clear(s, COLOR_BAND_2, (64, 16))
            expected = model(COLOR_BAND_2, (64, 16), expected)
            reply = expect(delta_read(s, KEY_A, STRIDE_A), PART, bands=1)
            assert struct.unpack_from('<II', reply, 8) == (64, 16), reply[8:16]
            surface = apply(surface, reply)
            assert surface == expected, 'frame 3 (second band)'
            frames.append(('one band at y=64', PART, HEIGHT, 16, len(reply), plain_frame))

            # 4. Nothing changed: an empty band table is a valid answer.
            reply = expect(delta_read(s, KEY_A, STRIDE_A), PART, bands=0)
            assert len(reply) == 8, len(reply)
            surface = apply(surface, reply)
            assert surface == expected, 'frame 4 (unchanged)'
            frames.append(('no change', PART, HEIGHT, 0, len(reply), plain_frame))

            # 5. The last band is short (rows 96..99): a partial band must be
            # bounded by the height, not by the band size.
            clear(s, COLOR_TAIL, (96, 4))
            expected = model(COLOR_TAIL, (96, 4), expected)
            reply = expect(delta_read(s, KEY_A, STRIDE_A), PART, bands=1)
            assert struct.unpack_from('<II', reply, 8) == (96, 4), reply[8:16]
            assert len(reply) == 8 + 8 + 4 * ROW, len(reply)
            surface = apply(surface, reply)
            assert surface == expected, 'frame 5 (short last band)'
            frames.append(('short last band y=96..99', PART, HEIGHT, 4, len(reply), plain_frame))

            # 6. Four bands at once are 64% of the rows: past half, the whole
            # frame is the cheaper reply, even though only part of it changed.
            clear(s, COLOR_MOST, (0, 64))
            expected = model(COLOR_MOST, (0, 64), expected)
            reply = expect(delta_read(s, KEY_A, STRIDE_A), FULL)
            surface = apply(surface, reply)
            assert surface == expected, 'frame 6 (past half)'
            frames.append(('64% of the rows', FULL, HEIGHT, 64, len(reply), plain_frame))

            # 7. Three bands are 48%: still a band reply, and the table carries
            # three entries in ascending row order.
            clear(s, COLOR_HALF, (48, 48))
            expected = model(COLOR_HALF, (48, 48), expected)
            reply = expect(delta_read(s, KEY_A, STRIDE_A), PART, bands=3)
            table = struct.unpack_from('<6I', reply, 8)
            assert table == (48, 16, 64, 16, 80, 16), table
            assert len(reply) == 8 + 3 * 8 + 48 * ROW, len(reply)
            surface = apply(surface, reply)
            assert surface == expected, 'frame 7 (three bands)'
            frames.append(('48% of the rows', PART, HEIGHT, 48, len(reply), plain_frame))

            # 8. Everything changed.
            clear(s, COLOR_WHOLE)
            expected = model(COLOR_WHOLE)
            reply = expect(delta_read(s, KEY_A, STRIDE_A), FULL)
            surface = apply(surface, reply)
            assert surface == expected, 'frame 8 (whole frame)'
            frames.append(('whole frame', FULL, HEIGHT, HEIGHT, len(reply), plain_frame))

            # 9. The same picture, now converted with DEST_ROWS too (the guest
            # asks for that when the surface is inverted). A different
            # conversion makes the base incomparable, so this is whole again.
            reply = expect(delta_read(s, KEY_A, STRIDE_A, flags=SWAP | DEST_ROWS), FULL)
            assert reply[4:] == expected, 'frame 9 (DEST_ROWS whole frame)'
            frames.append(('rows flipped (DEST_ROWS)', FULL, HEIGHT, HEIGHT, len(reply), plain_frame))

            # 10. A band with DEST_ROWS is reported in destination rows: GL rows
            # 16..31 are rows 68..83 of a 100-row picture once flipped, and that
            # straddles two 16-row bands (64..79 and 80..95), so two go out.
            clear(s, COLOR_TAIL, (16, 16))
            expected = model(COLOR_TAIL, (68, 16), expected)
            reply = expect(delta_read(s, KEY_A, STRIDE_A, flags=SWAP | DEST_ROWS), PART, bands=2)
            assert struct.unpack_from('<4I', reply, 8) == (64, 16, 80, 16), reply[8:24]
            surface = apply(surface, reply)
            assert surface == expected, 'frame 10 (DEST_ROWS band)'
            frames.append(('16 rows after a flip', PART, HEIGHT, 32, len(reply), plain_frame))

            # 11. The same surface read from another EGL context: the surface is
            # the identity, not the context, so this is still a band reply.
            # (A delta against the same picture has nothing to send.)
            s.slot = 2
            s.command(OPEN, words(3))
            bind_target(s, WIDTH, HEIGHT)
            show(s, COLOR_WHOLE, [(16, COLOR_TAIL)])
            reply = expect(delta_read(s, KEY_A, STRIDE_A, flags=SWAP | DEST_ROWS), PART, bands=0)
            assert len(reply) == 8, len(reply)
            surface = apply(surface, reply)
            assert surface == expected, 'frame 11 (other context)'
            frames.append(('other context, same surface', PART, HEIGHT, 0, len(reply), plain_frame))

            # 12. Two surfaces in rotation, which is what CoreAnimation does on
            # the device: the panel strides 4544 and 4608 alternate every
            # frame. Each surface has its own base frame, so after the first
            # read of each, a switch is a band reply again.
            model_c = picture(COLOR_CLEAR)
            show(s, COLOR_CLEAR)
            reply = expect(delta_read(s, KEY_C, STRIDE_A), FULL)
            assert reply[4:] == model_c, 'surface C first read'
            frames.append(('surface C, first read', FULL, HEIGHT, HEIGHT, len(reply), plain_frame))

            model_d = picture(COLOR_CLEAR)
            show(s, COLOR_CLEAR)
            reply = expect(delta_read(s, KEY_D, STRIDE_B), FULL)
            assert reply[4:] == model_d, 'surface D first read'
            frames.append(('surface D, first read', FULL, HEIGHT, HEIGHT, len(reply), plain_frame))

            # C changes one band of its own, D another. A single base frame
            # would compare D's frame with C's and have to send both bands.
            show(s, COLOR_CLEAR, [(16, COLOR_BAND)])
            reply = expect(delta_read(s, KEY_C, STRIDE_A), PART, bands=1)
            assert struct.unpack_from('<II', reply, 8) == (16, 16), reply[8:16]
            model_c = apply(model_c, reply)
            assert model_c == picture(COLOR_CLEAR, [(16, COLOR_BAND)]), 'surface C band'
            frames.append(('surface C, one band', PART, HEIGHT, 16, len(reply), plain_frame))

            show(s, COLOR_CLEAR, [(32, COLOR_BAND_3)])
            reply = expect(delta_read(s, KEY_D, STRIDE_B), PART, bands=1)
            assert struct.unpack_from('<II', reply, 8) == (32, 16), reply[8:16]
            model_d = apply(model_d, reply)
            assert model_d == picture(COLOR_CLEAR, [(32, COLOR_BAND_3)]), 'surface D band'
            frames.append(('surface D, one band', PART, HEIGHT, 16, len(reply), plain_frame))

            # Read both again with nothing drawn since: each is compared with
            # its own last frame, so both answers carry no bands at all. A
            # single base frame would have to resend the other surface's band.
            show(s, COLOR_CLEAR, [(16, COLOR_BAND)])
            reply = expect(delta_read(s, KEY_C, STRIDE_A), PART, bands=0)
            assert len(reply) == 8, len(reply)
            model_c = apply(model_c, reply)
            assert model_c == picture(COLOR_CLEAR, [(16, COLOR_BAND)]), 'surface C unchanged'
            frames.append(('surface C again, no change', PART, HEIGHT, 0, len(reply), plain_frame))

            show(s, COLOR_CLEAR, [(32, COLOR_BAND_3)])
            reply = expect(delta_read(s, KEY_D, STRIDE_B), PART, bands=0)
            assert len(reply) == 8, len(reply)
            model_d = apply(model_d, reply)
            assert model_d == picture(COLOR_CLEAR, [(32, COLOR_BAND_3)]), 'surface D unchanged'
            frames.append(('surface D again, no change', PART, HEIGHT, 0, len(reply), plain_frame))

            # And change both again, alternating, still one band each.
            show(s, COLOR_CLEAR, [(16, COLOR_BAND_4)])
            reply = expect(delta_read(s, KEY_C, STRIDE_A), PART, bands=1)
            assert struct.unpack_from('<II', reply, 8) == (16, 16), reply[8:16]
            model_c = apply(model_c, reply)
            assert model_c == picture(COLOR_CLEAR, [(16, COLOR_BAND_4)]), 'surface C second band'
            frames.append(('surface C again, one band', PART, HEIGHT, 16, len(reply), plain_frame))

            show(s, COLOR_CLEAR, [(32, COLOR_BAND_2)])
            reply = expect(delta_read(s, KEY_D, STRIDE_B), PART, bands=1)
            assert struct.unpack_from('<II', reply, 8) == (32, 16), reply[8:16]
            model_d = apply(model_d, reply)
            assert model_d == picture(COLOR_CLEAR, [(32, COLOR_BAND_2)]), 'surface D second band'
            frames.append(('surface D again, one band', PART, HEIGHT, 16, len(reply), plain_frame))

            # 13. A resize is a different key component: the same surface handle
            # at another size has no base, so it goes whole.
            bind_target(s, 48, 64)
            clear(s, COLOR_SMALL)
            expected_small = surface_bytes(COLOR_SMALL) * (48 * 64)
            plain_frame = 48 * 64 * 4
            reply = expect(delta_read(s, KEY_A, STRIDE_A, 48, 64, SWAP | DEST_ROWS), FULL)
            assert reply[4:] == expected_small, 'frame 13 (size change)'
            frames.append(('resize to 48x64', FULL, 64, 64, len(reply), plain_frame))

            # 14. A request without DELTA still gets every pixel, converted as
            # asked, exactly as before this feature existed.
            reply = s.command(READ, words(48, 64, SWAP | DEST_ROWS))
            assert len(reply) == 48 * 64 * 4, len(reply)
            assert reply == expected_small, 'frame 14 (plain request)'
            # 15. And the original eight-byte request still gets the whole
            # picture in GL order (RGBA, bottom-up), untouched by the delta.
            reply = s.command(READ, words(48, 64))
            assert len(reply) == 48 * 64 * 4, len(reply)
            assert reply == bytes([COLOR_SMALL[0], COLOR_SMALL[1], COLOR_SMALL[2], 255]) * (48 * 64), \
                'frame 15 (eight-byte request)'

            # The shipped 0.31.6 guest asked with a twelve-byte DELTA request,
            # without the surface handle: that still works, as one shared
            # bucket (a whole frame first, bands after).
            reply = expect(s.command(READ, words(48, 64, SWAP | DELTA)), FULL)
            assert reply[4:] == expected_small, 'twelve-byte delta request'
            reply = expect(s.command(READ, words(48, 64, SWAP | DELTA)), PART, bands=0)
            assert len(reply) == 8, len(reply)

            # A band reply that is not into a BGRA surface, or that would have
            # no pixels at all, is refused instead of corrupting the surface.
            s.command(READ, words(WIDTH, HEIGHT, DELTA), status=1)
            s.command(READ, words(WIDTH, HEIGHT, SWAP | NO_REPLY | DELTA), status=1)

            s.command(CLOSE)
            s.slot = 0
            s.command(END)
            assert child.wait(timeout=10) == 0
        log.seek(0)
        worker_log = log.read().decode(errors='replace')

    plain = WIDTH * HEIGHT * 4
    print(f'{len(frames)} delta frames at {WIDTH}x{HEIGHT}, whole frame is {plain} bytes')
    for name, kind, height, rows, size, whole_bytes in frames:
        print(f'  {name:>28}: {kind.decode()} {rows:>3}/{height} rows, {size:>6} bytes '
              f'({100 * size / (whole_bytes + 4):5.1f}% of a whole reply)')
    for line in worker_log.splitlines():
        print(f'  worker: {line}')
    print('EAGL delta readback: one base frame per surface, FULL only when a surface '
          'has none, bands for every later change, and each reply applied to that '
          'surface equals its full frame')


if __name__ == '__main__':
    main()
