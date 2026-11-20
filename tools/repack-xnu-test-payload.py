#!/usr/bin/env python3
"""Make an isolated XNUP A/B payload, keeping all unrelated entries identical."""
import argparse
from pathlib import Path
import re
import struct

HEADER = struct.Struct('<4sI')
ENTRY = struct.Struct('<16sII')
MAX = 63 * 1024 * 1024


def repack(payload, kernel=None, bootlog=None, surface_recording=False, ramdisk=None):
    if len(payload) < HEADER.size + ENTRY.size or len(payload) > MAX:
        raise ValueError('invalid input size')
    magic, declared = HEADER.unpack_from(payload)
    if magic != b'XNUP' or declared != len(payload) - HEADER.size:
        raise ValueError('invalid XNUP header')
    pos = HEADER.size
    output = bytearray()
    seen = set()
    while pos + ENTRY.size <= len(payload):
        name, size, reserved = ENTRY.unpack_from(payload, pos)
        if not name[0]:
            if pos + ENTRY.size != len(payload) or any(payload[pos:]):
                raise ValueError('bad end entry')
            if not {b'kernel', b'cmdline'} <= seen:
                raise ValueError('missing kernel or cmdline')
            output.extend(payload[pos:])
            if len(output) + HEADER.size > MAX:
                raise ValueError('output too large')
            return HEADER.pack(b'XNUP', len(output)) + output
        key = name.rstrip(b'\0')
        if b'\0' in key or key in seen or reserved:
            raise ValueError('invalid or duplicate entry')
        seen.add(key)
        offset = pos + ENTRY.size
        end = offset + ((size + 15) & ~15)
        if end > len(payload):
            raise ValueError('truncated entry')
        replacement = None
        if key == b'kernel' and kernel is not None:
            if len(kernel) < 32 or kernel[:4] != b'\xcf\xfa\xed\xfe':
                raise ValueError('replacement is not a 64-bit LE Mach-O')
            replacement = kernel
        elif key == b'ramdisk' and ramdisk is not None:
            if len(ramdisk) < 4096 or ramdisk[32:36] != b'NXSB':
                raise ValueError('replacement is not an APFS container')
            replacement = ramdisk
        elif key == b'cmdline' and (bootlog is not None or surface_recording):
            old = payload[offset:offset + size]
            if not old.endswith(b'\0') or b'\0' in old[:-1]:
                raise ValueError('invalid command line')
            extra = b''
            if bootlog is not None:
                if re.search(rb'(?<!\S)bootlog=', old[:-1]):
                    raise ValueError('source already has a bootlog argument; use the original baseline')
                extra += b' bootlog=' + str(bootlog).encode()
            if surface_recording:
                if re.search(rb'(?<!\S)rec_(secs|mb|step|ring|start)=', old[:-1]):
                    raise ValueError('source already configures surface recording')
                extra += b' rec_secs=1200 rec_mb=4 rec_step=8 rec_ring=1'
            replacement = old[:-1] + extra + b'\0'
        if replacement is None:
            output.extend(payload[pos:end])
        else:
            output.extend(ENTRY.pack(name, len(replacement), reserved))
            output.extend(replacement)
            output.extend(b'\0' * (-len(replacement) % 16))
        pos = end
    raise ValueError('missing end entry')


def limited(path):
    with path.open('rb') as f:
        data = f.read(MAX + 1)
    if len(data) > MAX:
        raise ValueError(f'{path} too large')
    return data


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('source', type=Path)
    p.add_argument('dest', type=Path)
    p.add_argument('--kernel', type=Path)
    p.add_argument('--bootlog', type=int, choices=[0, 1])
    p.add_argument('--record-iosurface', action='store_true', help='enable a 4 MiB guest compositor recording ring')
    p.add_argument('--ramdisk', type=Path, help='replacement APFS root volume (experimental only)')
    args = p.parse_args()
    output = repack(limited(args.source), limited(args.kernel) if args.kernel else None,
                    args.bootlog, args.record_iosurface,
                    limited(args.ramdisk) if args.ramdisk else None)
    with args.dest.open('xb') as f:
        f.write(output)


if __name__ == '__main__':
    main()
