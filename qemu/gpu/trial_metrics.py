#!/usr/bin/env python3
"""Shared, byte-offset-aware log readers for isolated glpass experiments.

Serial log records are diagnostic samples, not a frame counter.  The loader
replays its buffered log after ``== KERNEL LOG ==``; that copy is never counted.
A repeated record id before replay is ignored only when its payload matches.
"""
import hashlib
import re
from pathlib import Path

FRAME = re.compile(
    rb'redwood-gl: frame (\d+): (\d+) GL calls, (\d+) draws, (\d+) triangles, '
    rb'(\d+) clears; (\d+) textures refilled from IOSurfaces \((\d+) KB\); '
    rb'(\d+) picture copied out, (\d+) drawn in place; (\d+) us '
    rb'\((\d+) handing on, (\d+) waiting, (\d+) copying out\)')
OUT = re.compile(
    rb'hackios-gl: out (\d+) v(\d+) ([\w-]+) bytes=(\d+) stride=(\d+) row=(\d+) '
    rb'inv=(\d+) gap=(\d+) malloc=(\d+) send=(\d+) hdr=(\d+) body=(\d+) '
    rb'unpack=(\d+) total=(\d+)')
READBACK = re.compile(rb'^EAGL readback: context=\d+ read=(\d+)\b')
PANEL = re.compile(rb'display (\d+) x (\d+) at ([0-9a-fA-F]+)')
REPLAY = b'== KERNEL LOG =='
HASH_RECEIPT = b'redwood-gl: glhash=1 active; two boundary timers per attempt, no per-word timing; span includes inter-row padding'
HASH_FRAME = re.compile(rb'redwood-gl: hash f (\d+) hash_calls=(\d+) hash_bytes=(\d+) hash_total_us=(\d+) hash_attempts=(\d+) hash_changed=(\d+) image_count=(\d+) scope=since-frame-end incl-setup=1')
FRAME_TIMING = re.compile(rb'tl: g (\d+) began (\d+) ended (\d+) (\d+)')
REMOTE_OPEN = re.compile(rb'hackios-eagl: guest pid=(\d+) context=(\d+) remote opened')


def sha256_file(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


class LogCursor:
    """Read only appended complete lines, returning their original byte offsets."""
    def __init__(self, path):
        self.path = Path(path)
        self.offset = 0
        self.pending = b''

    def lines(self, final=False):
        try:
            with self.path.open('rb') as stream:
                stream.seek(self.offset)
                added = stream.read()
        except FileNotFoundError:
            return []
        beginning = self.offset - len(self.pending)
        self.offset += len(added)
        data = self.pending + added
        lines = []
        position = 0
        for match in re.finditer(rb'[^\n]*\n', data):
            line = match.group(0)
            lines.append((beginning + match.start(), beginning + match.end(),
                          line.replace(b'\r', b'').rstrip(b'\n')))
            position = match.end()
        self.pending = data[position:]
        if final and self.pending:
            lines.append((beginning + position, self.offset,
                          self.pending.replace(b'\r', b'')))
            self.pending = b''
        return lines


class SerialMetrics:
    def __init__(self):
        self.frames = []
        self.outs = []
        self.seen = {'frame': {}, 'out': {}}
        self.duplicates = {'frame': 0, 'out': 0}
        self.conflicts = []
        self.replay_at = None
        self.panel = None
        self.hash_profile_receipts = []
        self.hash_frames = []
        self.frame_timings = []
        self.remote_clients = []

    def _unique(self, kind, identifier, payload, offset):
        previous = self.seen[kind].get(identifier)
        if previous is None:
            self.seen[kind][identifier] = payload
            return True
        if previous == payload:
            self.duplicates[kind] += 1
        else:
            self.conflicts.append({'kind': kind, 'id': identifier, 'offset': offset})
        return False

    def consume(self, lines):
        for start, end, line in lines:
            if self.replay_at is not None:
                continue
            if REPLAY in line:
                self.replay_at = start
                continue
            if self.panel is None:
                panel = PANEL.search(line)
                if panel:
                    self.panel = (int(panel[1]), int(panel[2]), int(panel[3], 16))
            if HASH_RECEIPT in line:
                self.hash_profile_receipts.append(dict(start=start,end=end))
            client = REMOTE_OPEN.search(line)
            if client:
                self.remote_clients.append(dict(pid=int(client[1]),context=int(client[2]),start=start,end=end))
            hashed = HASH_FRAME.search(line)
            if hashed:
                keys = ('frame','hash_calls','hash_bytes','hash_total_us','hash_attempts','hash_changed','image_count')
                self.hash_frames.append(dict(zip(keys,map(int,hashed.groups())),start=start,end=end))
            timing = FRAME_TIMING.search(line)
            if timing:
                self.frame_timings.append(dict(zip(('frame','began_us','ended_us','draws'),map(int,timing.groups())),start=start,end=end))
            frame = FRAME.search(line)
            if frame and self._unique('frame', int(frame[1]), frame.group(0), start):
                g = [int(value) for value in frame.groups()]
                self.frames.append(dict(id=g[0], calls=g[1], draws=g[2], tri=g[3],
                                        clears=g[4], tex=g[5], kb=g[6], pictures=g[7],
                                        in_place=g[8], total=g[9], hand=g[10],
                                        wait=g[11], copy=g[12], start=start, end=end))
            out = OUT.search(line)
            if out and self._unique('out', int(out[1]), line[out.start():], start):
                g = out.groups()
                self.outs.append(dict(id=int(g[0]), variant=int(g[1]),
                                     path=g[2].decode('ascii'), bytes=int(g[3]),
                                     stride=int(g[4]), row=int(g[5]), gap=int(g[7]),
                                     send=int(g[9]), hdr=int(g[10]), body=int(g[11]),
                                     unpack=int(g[12]), total=int(g[13]),
                                     start=start, end=end))
                record = self.outs[-1]
                tail = line[out.end():]
                for name in ('shadow_prepare_us', 'shadow_compose_us', 'full_copy_us',
                             'full_copy_bytes', 'mutex_wait_us'):
                    match = re.search(rb'\b' + name.encode() + rb'=(\d+)\b', tail)
                    if match:
                        record[name] = int(match[1])
                reason = re.search(rb'\bforced_full=([A-Za-z0-9_-]+)\b', tail)
                if reason:
                    record['forced_full'] = reason[1].decode('ascii')

    def selected(self, records, start, end):
        # A boundary may cut a partial line: only retain whole records.
        return [record for record in records
                if record['start'] >= start and record['end'] <= end]


class ReadbackMetrics:
    def __init__(self):
        self.count = 0
        self.last_id = 0
        self.duplicates = 0
        self.resets = 0

    def consume(self, lines):
        for _, _, line in lines:
            match = READBACK.search(line)
            if not match:
                continue
            identifier = int(match[1])
            if identifier > self.last_id:
                self.count += 1
                self.last_id = identifier
            elif identifier == self.last_id:
                self.duplicates += 1
            else:
                # A restarted/multiple writer is not a comparable experiment.
                self.resets += 1
