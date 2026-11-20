#!/usr/bin/env python3
"""QMP capture and optional unlock gesture for a new isolated HackiOS boot."""
import argparse
import json
from pathlib import Path
import re
import socket
import subprocess
import time


MARKS = {
    'logo-overlay': b'bootlog drawn on Apple Logo frame',
    'overlay-retired': b'bootlog retired at graphics handoff',
    'springboard-launched': b'Successfully spawned SpringBoard[',
    'lsd-first-observed-lookup': b'requestor = lsd[',
    'springboard-home-window-log': b'redwood: layers: window SBHomeScreenWindow',
}

SWAPS = {number: f'MiniIOMobileFramebuffer: swap {number} (#'.encode()
         for number in (2, 3, 4, 5, 6, 7, 8)}


def qmp(trial, commands):
    with socket.socket(socket.AF_UNIX) as sock:
        sock.settimeout(5)
        sock.connect(str(trial / 'qmp.sock'))
        with sock.makefile('rb') as stream:
            if 'QMP' not in json.loads(stream.readline()):
                raise RuntimeError('not a QMP greeting')
            def send(request):
                sock.sendall(json.dumps(request).encode() + b'\n')
                reply = json.loads(stream.readline())
                while 'event' in reply:
                    reply = json.loads(stream.readline())
                if 'return' not in reply:
                    raise RuntimeError(reply)
                return reply['return']
            send({'execute': 'qmp_capabilities'})
            commands(send)


RAMFB_BYTES = 1125 * 2436 * 4


def ramfb_address(trial):
    with (trial / 'serial.log').open('rb') as serial:
        match = re.search(rb'^display 1125 x 2436 at ([0-9a-fA-F]{8,12})\r?$', serial.read(65536), re.M)
    if match is None:
        raise ValueError('loader did not describe the expected ramfb')
    address = int(match[1], 16)
    # The isolated launcher uses QEMU virt RAM at 1 GiB, with 7 GiB mapped.
    if address % 4096 or not 0x40000000 <= address <= 0x200000000 - RAMFB_BYTES:
        raise ValueError('ramfb address outside the isolated guest RAM')
    return address


def dump_memory(send, address, n, output):
    if output.exists():
        raise FileExistsError(output)
    if re.fullmatch(r'[A-Za-z0-9_./-]+', str(output)) is None:
        raise ValueError('unexpected isolated run path')
    result = send({'execute': 'human-monitor-command', 'arguments': {
        'command-line': f'pmemsave {address:#x} {n:#x} "{output}"'}})
    if result or not output.is_file() or output.stat().st_size != n:
        raise RuntimeError(f'physical read failed: {result!r}')


def dump_surface_recording(trial, address):
    output = trial / 'surface-ring.raw'
    qmp(trial, lambda send: dump_memory(send, address, 4 << 20, output))
    print('surface-recording=' + str(output), flush=True)


def screenshot(trial, name, raw=False):
    output = trial / (name + '.png')
    if output.exists():
        raise FileExistsError(output)
    def capture(send):
        send({'execute': 'screendump', 'arguments': {'filename': str(output), 'format': 'png'}})
        if raw:
            memory = trial / (name + '.bgra')
            dump_memory(send, ramfb_address(trial), RAMFB_BYTES, memory)
            print('ramfb=' + str(memory), flush=True)
    qmp(trial, capture)
    print('capture=' + str(output), flush=True)


def swipe(trial):
    def gesture(send):
        def pos(y, button=None):
            events = [{'type': 'abs', 'data': {'axis': axis, 'value': round(ratio * 32767)}}
                      for axis, ratio in [('x', .5), ('y', y)]]
            if button is not None:
                events.append({'type': 'btn', 'data': {'button': 'left', 'down': button}})
            send({'execute': 'input-send-event', 'arguments': {'events': events}})
        pos(.985, True)
        try:
            for i in range(1, 12):
                time.sleep(.025)
                pos(.985 - .685 * i / 11)
        finally:
            pos(.3, False)
    qmp(trial, gesture)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--seconds', type=int, required=True)
    ap.add_argument('--at', nargs='*', type=int, default=[])
    ap.add_argument('--swipe-at', nargs='*', type=int, default=[], help='unlock at fixed seconds after QMP becomes ready')
    ap.add_argument('--swipe-after-swap', type=int, choices=SWAPS, help='send one unlock gesture after the guest submits this swap')
    ap.add_argument('--capture-swaps', action='store_true', help='capture selected swap-count milestones from serial')
    ap.add_argument('--probe-ramfb', action='store_true', help='record raw pixels alongside selected late captures')
    ap.add_argument('--surface-ring-at', type=int, help='dump the optional 4 MiB IOSurface ring at this host elapsed second')
    ap.add_argument('--max-gesture-lateness', type=float, default=3.0, help='skip fixed-time gestures that missed their window')
    ap.add_argument('launcher', type=Path)
    ap.add_argument('qemu', type=Path)
    ap.add_argument('payload', type=Path)
    args = ap.parse_args()
    schedule = sorted(set(args.at))
    swipes = sorted(set(args.swipe_at))
    if args.max_gesture_lateness < 0 or args.seconds < 1:
        ap.error('seconds must be positive and lateness non-negative')
    pending = dict(MARKS)
    swap_action = f'guest-swap-{args.swipe_after_swap}'
    if args.capture_swaps:
        pending.update((f'guest-swap-{n}', mark) for n, mark in SWAPS.items())
    elif args.swipe_after_swap:
        pending[swap_action] = SWAPS[args.swipe_after_swap]
    ring_at = args.surface_ring_at
    start = None
    with subprocess.Popen(['bash', args.launcher.resolve(strict=True), args.qemu.resolve(strict=True),
                           str(args.seconds), args.payload.resolve(strict=True)], stdout=subprocess.PIPE, text=True) as boot:
        line = boot.stdout.readline().strip()
        if not line.startswith('trial='):
            raise RuntimeError('launcher did not announce an isolated directory')
        print(line, flush=True)
        trial = Path(line[6:]).resolve(strict=True)
        serial = trial / 'serial.log'
        offset, overlap = 0, b''
        marker_length = max(map(len, pending.values()))
        while boot.poll() is None:
            if start is None:
                try:
                    qmp(trial, lambda send: None)
                except (OSError, RuntimeError, ValueError):
                    time.sleep(.2)
                    continue
                start = time.monotonic()
                print('qmp-ready', flush=True)
            elapsed = time.monotonic() - start
            if pending and serial.exists():
                with serial.open('rb') as f:
                    f.seek(offset)
                    chunk = f.read(256 * 1024)
                    offset = f.tell()
                log = overlap + chunk
                overlap = log[-(marker_length - 1):]
                for name, data in list(pending.items()):
                    if data in log:
                        print(f'{name} at {elapsed:.1f}s', flush=True)
                        try:
                            screenshot(trial, name, raw=args.probe_ramfb and name in {
                                'overlay-retired', 'springboard-home-window-log', 'guest-swap-7', 'guest-swap-8'})
                        except (OSError, RuntimeError, ValueError) as err:
                            print(f'capture {name}: {err}', flush=True)
                        del pending[name]
                        if name == swap_action and not any(mark in log for n, mark in SWAPS.items()
                                                           if n > args.swipe_after_swap):
                            try:
                                swipe(trial)
                                print(f'unlock gesture after {name} acknowledged by QMP', flush=True)
                            except (OSError, RuntimeError, ValueError) as err:
                                print(f'unlock gesture after {name} failed: {err}', flush=True)
            if ring_at is not None and elapsed >= ring_at and serial.exists():
                ring_at = None
                with serial.open('rb') as f:
                    recording = re.search(rb'MiniIOMobileFramebuffer: recording 1200 s of the screen at 140x304 '
                                          rb'from second 0, into 4 MB at (0x[0-9a-fA-F]{6,12})', f.read(65536))
                try:
                    address = int(recording[1], 16) if recording else 0
                    if address % 4096 or not 0x40000000 <= address <= 0x200000000 - (4 << 20):
                        raise ValueError('no bounded IOSurface recording address in serial')
                    dump_surface_recording(trial, address)
                except (OSError, RuntimeError, ValueError) as err:
                    print(f'IOSurface ring dump failed: {err}', flush=True)
            while schedule or swipes:
                capture_due = bool(schedule) and (not swipes or schedule[0] <= swipes[0])
                second = (schedule if capture_due else swipes)[0]
                actual = time.monotonic() - start
                if second > actual or boot.poll() is not None:
                    break
                (schedule if capture_due else swipes).pop(0)
                try:
                    if capture_due:
                        print(f'capture due={second}s actual={actual:.1f}s', flush=True)
                        screenshot(trial, f'boot-{second}', raw=args.probe_ramfb)
                    elif actual - second <= args.max_gesture_lateness:
                        swipe(trial)
                        print(f'unlock gesture due={second}s actual={actual:.1f}s acknowledged by QMP', flush=True)
                    else:
                        print(f'unlock gesture due={second}s skipped at {actual:.1f}s (missed window)', flush=True)
                except (OSError, RuntimeError, ValueError) as err:
                    print(f'QMP action due={second}s actual={actual:.1f}s failed: {err}', flush=True)
            time.sleep(.35)
        if start is None:
            raise RuntimeError('QMP never became ready')
        print('launcher exit', boot.returncode, 'unseen:', ', '.join(pending), flush=True)
        if boot.returncode not in (0, 124):
            raise SystemExit(boot.returncode)


if __name__ == '__main__':
    main()
