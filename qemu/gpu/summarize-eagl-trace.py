#!/usr/bin/env python3
"""Measure observed Redwood/CoreAnimation call names, not the whole source ABI."""
import argparse
from collections import Counter
import json
from pathlib import Path
import re


def parse(lines):
    calls = Counter()
    frames = set()
    draws = 0
    for line in lines:
        # Writes to the shared serial log can be interleaved. Only count a
        # complete leading record; do not recover guessed calls from a fragment.
        frame = re.match(r'^gl: ---- frame ([1-9][0-9]*) \(framebuffer [0-9]+\)\s*$', line)
        if frame:
            frames.add(int(frame[1]))
        call = re.match(r'^gl: (gl[A-Z][A-Za-z0-9_]*)\([^\r\n]*\)\s*$', line)
        if call:
            name = call[1]
            calls[name] += 1
            draws += name in ('glDrawArrays', 'glDrawElements', 'glDrawArraysInstanced', 'glDrawElementsInstanced')
    if not frames or not draws:
        raise ValueError('no complete CoreAnimation frame trace with actual draw calls')
    return {'frames': sorted(frames), 'draw_calls': draws,
            'total_calls': sum(calls.values()), 'distinct_calls': len(calls),
            'calls': dict(sorted(calls.items()))}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('serial_log', type=Path)
    args = parser.parse_args()
    with args.serial_log.open(encoding='utf-8', errors='replace') as log:
        try:
            print(json.dumps(parse(log), indent=2))
        except ValueError as exc:
            parser.exit(1, f'{exc}\n')


if __name__ == '__main__':
    main()
