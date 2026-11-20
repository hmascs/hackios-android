#!/usr/bin/env python3
"""Read publication metadata only. Header consistency is not a pixel oracle.

HFRM/v1: publication count @24, timestamp @32; no write-in-progress guard.
HFR2/v2: same count/timestamp, independent odd/even guard @40 == 2*count
at a completed publication. A missing/odd/mismatched sample is unavailable,
never a counter reset or a fabricated zero publication.
"""
from pathlib import Path
import struct

WIDTH, HEIGHT, STRIDE = 1125, 2436, 4500
HEADER = 64


def create_ring(path, version=1):
    if version not in (1, 2):
        raise ValueError('unsupported ring protocol')
    magic = b'HFRM' if version == 1 else b'HFR2'
    data = bytearray(HEADER)
    struct.pack_into('<4sIIIIIQQQ', data, 0, magic, version, WIDTH, HEIGHT,
                     STRIDE, 1, 0, 0, 0)
    with Path(path).open('xb') as stream:
        stream.write(data)
        stream.truncate(HEADER + STRIDE * HEIGHT)


def ring_snapshot(path, version=1):
    result = dict(protocol_version=version, ring_available=False,
                  ring_seq=None, ring_timestamp_ns=None)
    try:
        with Path(path).open('rb', buffering=0) as stream:
            data = stream.read(HEADER)
            if len(data) != HEADER:
                result['ring_status'] = 'short_header'
                return result
            magic, actual, width, height, stride, pixel_format = struct.unpack_from('<4sIIIII', data)
            expected = b'HFRM' if version == 1 else b'HFR2'
            if magic != expected or actual != version:
                result['ring_status'] = 'protocol_mismatch'
                return result
            if (width, height, stride, pixel_format) != (WIDTH, HEIGHT, STRIDE, 1):
                result['ring_status'] = 'geometry_or_format_mismatch'
                return result
            sequence, stamp = struct.unpack_from('<QQ', data, 24)
            if version == 2:
                guard, = struct.unpack_from('<Q', data, 40)
                stream.seek(40)
                last = stream.read(8)
                if len(last) != 8:
                    result['ring_status'] = 'short_guard'
                    return result
                guard_after, = struct.unpack('<Q', last)
                if guard & 1 or guard != guard_after:
                    result['ring_status'] = 'publication_in_progress'
                    return result
                if guard != sequence * 2:
                    result['ring_status'] = 'guard_count_mismatch'
                    return result
                result['ring_guard'] = guard
            result.update(ring_available=True, ring_seq=sequence,
                          ring_timestamp_ns=stamp,
                          ring_status='published_header' if version == 2 else 'unguarded_v1_header')
    except FileNotFoundError:
        result['ring_status'] = 'missing'
    return result
