#!/usr/bin/env python3
"""Measure observed pixel changes and transfer cost, not guest presents or Android VSYNC."""
import argparse
import hashlib
import json
from pathlib import Path
import socket
from statistics import median
import struct
import tempfile
import threading
import time


class Rfb:
    def __init__(self, sock):
        self.sock = sock
        self.reader = sock.makefile('rb')
        assert self.read(12) == b'RFB 003.008\n'
        sock.sendall(b'RFB 003.008\n')
        assert b'\x01' in self.read(self.read(1)[0])
        sock.sendall(b'\x01')
        assert self.read(4) == bytes(4)
        sock.sendall(b'\x01')
        self.resize(*self.unpack('HH'))
        self.read(16)
        name_length, = self.unpack('I')
        assert name_length <= 65536
        self.read(name_length)
        sock.sendall(struct.pack('!IBBBBHHHBBBBBB', 0, 32, 24, 0, 1, 255, 255, 255, 0, 8, 16, 0, 0, 0))
        sock.sendall(struct.pack('!BBHii', 2, 0, 2, 0, -223))

    def read(self, size):
        data = self.reader.read(size)
        if len(data) != size:
            raise EOFError('short RFB read')
        return data

    def unpack(self, fmt):
        fmt = '!' + fmt
        return struct.unpack(fmt, self.read(struct.calcsize(fmt)))

    def resize(self, w, h):
        assert 0 < w * h <= 4194304
        self.w, self.h = w, h
        self.pixels = bytearray(w * h * 4)

    def capture(self):
        for _ in range(4):
            self.sock.sendall(struct.pack('!BBHHHH', 3, 0, 0, 0, self.w, self.h))
            kind, _, count = self.unpack('BBH')
            assert kind == 0 and 0 < count <= 4096
            resized = False
            for _ in range(count):
                x, y, w, h, encoding = self.unpack('HHHHi')
                if encoding == -223:
                    self.resize(w, h)
                    resized = True
                else:
                    assert encoding == 0 and x + w <= self.w and y + h <= self.h
                    if x == 0 and w == self.w:
                        start = y * w * 4
                        self.pixels[start:start + w * h * 4] = self.read(w * h * 4)
                    else:
                        for row in range(y, y + h):
                            start = (row * self.w + x) * 4
                            self.pixels[start:start + w * 4] = self.read(w * 4)
            if not resized:
                return self.w, self.h, self.pixels, 4
        raise RuntimeError('repeated RFB size changes')


class QmpDump:
    def __init__(self, sock, filename):
        self.reader = sock.makefile('rwb')
        self.filename = filename
        if 'QMP' not in self.read():
            raise RuntimeError('missing QMP greeting')
        self.command('qmp_capabilities')

    def read(self):
        return json.loads(self.reader.readline())

    def command(self, name, **params):
        self.reader.write(json.dumps(dict(execute=name, arguments=params)).encode() + b'\n')
        self.reader.flush()
        while True:
            reply = self.read()
            if 'return' in reply:
                return reply['return']
            if 'error' in reply:
                raise RuntimeError(reply['error'])

    def capture(self):
        self.command('screendump', filename=str(self.filename))
        magic, size, depth, pixels = self.filename.read_bytes().split(b'\n', 3)
        w, h = map(int, size.split())
        if magic != b'P6' or depth != b'255' or not (0 < w * h <= 4194304):
            raise ValueError(f'Invalid PPM header: {magic!r} {w}x{h} max={depth!r}')
        row = w * 3
        stride, remainder = divmod(len(pixels), h)
        if remainder or stride not in (row, (row + 3) & ~3):
            raise ValueError(f'Invalid PPM data length for {w}x{h}: {len(pixels)} bytes')
        if stride != row:  # QEMU 11.1.2 writes Pixman rows, including alignment padding.
            pixels = b''.join(pixels[y:y + row] for y in range(0, len(pixels), stride))
        return w, h, pixels, 3


def sample(capture, seconds, target_fps, snapshot=None):
    sizes = set()
    latencies = []
    last = None
    changes = 0
    start = time.monotonic()
    deadline = start + seconds
    while time.monotonic() < deadline:
        before = time.monotonic()
        w, h, data, bpp = capture()
        latencies.append(time.monotonic() - before)
        sizes.add((w, h))
        if bpp == 4:
            rgb = bytearray(w * h * 3)
            rgb[::3], rgb[1::3], rgb[2::3] = data[::4], data[1::4], data[2::4]
        else:
            rgb = data
        digest = hashlib.blake2b(rgb, digest_size=16).hexdigest()
        if last is not None and digest != last:
            changes += 1
        last = digest
        remain = min(deadline, before + (1 / target_fps)) - time.monotonic()
        if remain > 0:
            time.sleep(remain)
    elapsed = time.monotonic() - start
    if snapshot is not None:
        with snapshot.open('xb') as f:
            f.write(f'P6\n{w} {h}\n255\n'.encode())
            f.write(rgb)
    latencies.sort()
    return dict(observed_seconds=round(elapsed, 3), capture_samples=len(latencies),
                capture_samples_per_second=round(len(latencies) / elapsed, 3),
                changed_samples=changes, observed_changes_per_second=round(changes / elapsed, 3),
                capture_ms_median=round(median(latencies) * 1000, 3),
                capture_ms_p95=round(latencies[(len(latencies) * 95 - 1) // 100] * 1000, 3),
                sizes=sorted(sizes), last_rgb_digest=last)


def swipe_pages(path, stop, stats, ready):
    def position(x):
        return [dict(type='abs', data=dict(axis=axis, value=round(frac * 32767)))
                for axis, frac in [('x', x), ('y', 0.61)]]

    def button(down):
        return dict(type='btn', data=dict(button='left', down=down))

    with socket.socket(socket.AF_UNIX) as sock:
        sock.settimeout(2)
        try:
            sock.connect(path)
            qmp = QmpDump(sock, None)
            ready.set()
            a, b = 0.85, 0.15
            while not stop.is_set():
                qmp.command('input-send-event', events=position(a) + [button(True)])
                try:
                    for i in range(1, 19):
                        if stop.wait(0.016):
                            break
                        x = a + (b - a) * i / 18
                        qmp.command('input-send-event', events=position(x))
                finally:
                    qmp.command('input-send-event', events=[button(False)])
                if not stop.is_set():
                    stats['completed_swipes'] += 1
                a, b = b, a
                stop.wait(1.2)
        except Exception as exc:
            stats['input_error'] = str(exc)
        finally:
            ready.set()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('transport', choices=('qmp-dump', 'unix-rfb-raw'))
    parser.add_argument('socket')
    parser.add_argument('--seconds', type=float, default=15)
    parser.add_argument('--sample-hz', type=float, default=90)
    parser.add_argument('--snapshot', type=Path, help='write last image to a new PPM file')
    parser.add_argument('--input-socket', help='second QMP socket of a disposable VM already on SpringBoard; alternate page swipes')
    a = parser.parse_args()
    if not (0 < a.seconds <= 300 and 0 < a.sample_hz <= 240):
        parser.error('invalid duration or rate')
    with tempfile.TemporaryDirectory(prefix='hackios-fps-') as tmp, socket.socket(socket.AF_UNIX) as s:
        s.settimeout(10)
        s.connect(a.socket)
        reader = Rfb(s) if a.transport == 'unix-rfb-raw' else QmpDump(s, Path(tmp) / 'screen.ppm')
        # Negotiate and fill the display once; don't count connection warm-up in transfer latency.
        reader.capture()
        stats = {}
        worker = None
        stop = threading.Event()
        try:
            if a.input_socket:
                if Path(a.input_socket).resolve() == Path(a.socket).resolve():
                    parser.error('input and capture must use different sockets')
                stats['completed_swipes'] = 0
                ready = threading.Event()
                worker = threading.Thread(target=swipe_pages, args=(a.input_socket, stop, stats, ready), daemon=True)
                worker.start()
                if not ready.wait(4) or 'input_error' in stats:
                    raise RuntimeError(f'Cannot start input workload: {stats}')
            result = sample(reader.capture, a.seconds, a.sample_hz, a.snapshot)
        finally:
            stop.set()
            if worker is not None:
                worker.join(5)
        if worker is not None:
            if worker.is_alive() or 'input_error' in stats or not stats['completed_swipes']:
                raise RuntimeError(f'Input workload incomplete: {stats}')
            # QMP ACK is not proof SpringBoard showed a gesture; a static/black scanout is not an FPS result.
            stats['workload_status'] = ('pixel-changes-observed-verify-springboard'
                                        if result['changed_samples'] else 'no-scanout-change-not-a-valid-fps-run')
    print(json.dumps(dict(transport=a.transport, **result, **stats), indent=2))


if __name__ == '__main__':
    main()
