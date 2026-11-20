#!/usr/bin/env python3
"""Read a bounded RWREC003 diagnostic capture without assuming a desktop appeared."""
import argparse
from pathlib import Path
import struct

from PIL import Image

CAPACITY = 4 << 20
SIZE = (140, 304)
HEADER = struct.Struct('<8sIIIIQ')
STAMP = struct.Struct('<Q')
PIXELS = SIZE[0] * SIZE[1] * 3
RECORD = STAMP.size + PIXELS


def decode(data):
    if len(data) != CAPACITY:
        raise ValueError('expected one 4 MiB IOSurface recording')
    magic, width, height, count, oldest, began = HEADER.unpack_from(data)
    slots = (CAPACITY - HEADER.size) // RECORD
    if (magic != b'RWREC003' or (width, height) != SIZE or count > slots or
            (oldest and (count != slots or oldest >= count))):
        raise ValueError('invalid IOSurface ring header')
    previous = -1
    for offset in range(count):
        index = (oldest + offset) % count
        at = HEADER.size + index * RECORD
        timestamp, = STAMP.unpack_from(data, at)
        if timestamp < previous or timestamp > 1200_000_000_000:
            if offset + 1 == count:
                # The newest record can be half written when the ring is copied; drop that one only.
                return
            raise ValueError('inconsistent IOSurface timestamps (possibly sampled during a write)')
        previous = timestamp
        yield began + timestamp, Image.frombytes('RGB', SIZE, data[at + STAMP.size:at + RECORD])


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('recording', type=Path)
    p.add_argument('output', type=Path, help='new directory for decoded PNGs')
    a = p.parse_args()
    with a.recording.open('rb') as source:
        frames = list(decode(source.read(CAPACITY + 1)))
    a.output.mkdir()
    for i, (timestamp, image) in enumerate(frames):
        image.save(a.output / f'{i:03d}-{timestamp}.png')
    print(f'{len(frames)} surface frames')


if __name__ == '__main__':
    main()
