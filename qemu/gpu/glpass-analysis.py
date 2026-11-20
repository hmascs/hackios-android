#!/usr/bin/env python3
"""Summarise a glpass-desktop.py trial: per-variant readback split, frame period."""
import argparse
import json
from pathlib import Path
import re
from statistics import median

OUT = re.compile(rb'hackios-gl: out (\d+) v(\d+) (\w+) bytes=(\d+) stride=(\d+) row=(\d+) inv=(\d) '
                 rb'gap=(\d+) malloc=(\d+) send=(\d+) hdr=(\d+) body=(\d+) unpack=(\d+) total=(\d+)')
HOST = re.compile(rb'EAGL readback timing: seq=(\d+) read=(\d+) bytes=(\d+) host_dispatch_us=(\d+) '
                  rb'socket_write_us=(\d+) gl_readback_us=(\d+) convert_us=(\d+)')

FIELDS = ('readback', 'variant', 'path', 'bytes', 'stride', 'row', 'invert', 'gap', 'malloc',
          'send', 'hdr', 'body', 'unpack', 'total')


def parse_guest(data):
    rows = []
    for match in OUT.finditer(data):
        values = match.groups()
        row = {name: values[i] for i, name in enumerate(FIELDS)}
        row['path'] = row['path'].decode()
        row['variant'] = int(row['variant'])
        for key in FIELDS[3:]:
            row[key] = int(row[key])
        rows.append(row)
    return rows


def parse_host(data):
    rows = []
    for match in HOST.finditer(data):
        values = [int(x) for x in match.groups()]
        rows.append(dict(zip(('seq', 'read', 'bytes', 'host_dispatch', 'socket_write', 'gl_readback', 'convert'),
                             values)))
    return rows


def median_of(rows, key):
    values = [r[key] for r in rows]
    return round(median(values)) if values else None


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('trial', type=Path)
    p.add_argument('--since', type=int, default=None,
                   help='only readbacks after this ordinal (default: all)')
    p.add_argument('--variant', type=int, default=None)
    a = p.parse_args()
    serial = (a.trial / 'serial.log').read_bytes()
    worker = (a.trial / 'worker.log').read_bytes()
    guest = parse_guest(serial)
    host = parse_host(worker)
    if a.since:
        guest = [r for r in guest if r['readback'] > a.since]
    print(f'{len(guest)} guest readback lines, {len(host)} host timing lines, '
          f'run.json: {(a.trial / "run.json").read_text().strip() if (a.trial / "run.json").is_file() else "-"}')
    if not guest:
        return
    widths = {(r['stride'], r['row'], r['invert'], r['bytes']) for r in guest}
    print('geometry:', sorted(widths))
    print(f'{"v":>2} {"n":>3} {"total":>8} {"body":>8} {"unpack":>8} {"malloc":>7} {"send":>6} {"hdr":>5} '
          f'{"gap":>9} {"path":>8}')
    for variant in sorted({r['variant'] for r in guest}):
        rows = [r for r in guest if r['variant'] == variant]
        print(f'{variant:>2} {len(rows):>3} {median_of(rows, "total"):>8} {median_of(rows, "body"):>8} '
              f'{median_of(rows, "unpack"):>8} {median_of(rows, "malloc"):>7} {median_of(rows, "send"):>6} '
              f'{median_of(rows, "hdr"):>5} {median_of(rows, "gap"):>9} '
              f'{rows[0]["path"]:>8}')
    print(f'{"all":>2} {len(guest):>3} {median_of(guest, "total"):>8} {median_of(guest, "body"):>8} '
          f'{median_of(guest, "unpack"):>8} {median_of(guest, "malloc"):>7} {median_of(guest, "send"):>6} '
          f'{median_of(guest, "hdr"):>5} {median_of(guest, "gap"):>9}')
    if host:
        print(f'host: n={len(host)} host_dispatch={median_of(host, "host_dispatch")} '
              f'gl_readback={median_of(host, "gl_readback")} convert={median_of(host, "convert")} '
              f'socket_write={median_of(host, "socket_write")}')
    if (a.trial / 'measure-start.txt').is_file():
        offset = int((a.trial / 'measure-start.txt').read_text())
        window = parse_guest(serial[offset:])
        print(f'measurement-window readbacks: {len(window)}')
        if window:
            for variant in sorted({r['variant'] for r in window}):
                rows = [r for r in window if r['variant'] == variant]
                print(f'  window v{variant}: n={len(rows)} total={median_of(rows, "total")} '
                      f'body={median_of(rows, "body")} unpack={median_of(rows, "unpack")} '
                      f'gap={median_of(rows, "gap")}')


if __name__ == '__main__':
    main()
