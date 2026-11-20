#!/usr/bin/env python3
"""Capture real QMP scanout and compare the icon grid with a known SpringBoard image.

A successful match proves visible desktop state only. The optional swipe
round-trip also requires new renderer replies, but is not an FPS benchmark.
"""
import argparse
import json
import re
from pathlib import Path
import runpy
import socket
import time

from PIL import Image, ImageChops, ImageStat

QmpDump = runpy.run_path(str(Path(__file__).with_name('measure-framebuffer.py')))['QmpDump']


def features(im):
    # Ignore the clock/status bar and app text; only the actual icon grid matters.
    w, h = im.size
    return im.convert('RGB').crop((int(w * .04), int(h * .315), int(w * .96), int(h * .755))).resize((112, 112))


def rgb_error(a, b):
    return sum(ImageStat.Stat(ImageChops.difference(a, b)).mean) / 3


def readback_count(log):
    frames = re.findall(rb'^EAGL readback: context=1 read=(\d+) draws_submitted=[1-9]\d* size=1125x2436$',
                        log.read_bytes(), re.MULTILINE)
    return max(map(int, frames), default=0)


def swipe(qmp, source, target):
    def position(x):
        return [{'type': 'abs', 'data': {'axis': axis, 'value': round(fraction * 32767)}}
                for axis, fraction in [('x', x), ('y', .48)]]

    def button(down):
        return {'type': 'btn', 'data': {'down': down, 'button': 'left'}}

    qmp.command('input-send-event', events=position(source) + [button(True)])
    try:
        for i in range(1, 13):
            time.sleep(.035)
            qmp.command('input-send-event', events=position(source + (target - source) * i / 12))
    finally:
        qmp.command('input-send-event', events=[button(False)])


def await_interaction_frame(qmp, log, previous, condition, timeout=120):
    start = time.monotonic()
    last = None
    while time.monotonic() - start < timeout:
        count = readback_count(log)
        if count <= previous:
            time.sleep(2)
            continue
        w, h, pixels, channels = qmp.capture()
        if channels == 3 and (w, h) == (1125, 2436):
            im = Image.frombytes('RGB', (w, h), bytes(pixels))
            current = features(im)
            if condition(current):
                if last is not None and rgb_error(last, current) < 4:
                    return im, count
                last = current
            else:
                last = None
        time.sleep(2)
    raise SystemExit('Interaction produced no stable matching scanout with a new full-size GPU readback')


def verify_swipes(qmp, args, desktop):
    anchor = features(desktop)
    before = readback_count(args.gpu_log)
    if not before:
        raise SystemExit('Desktop found before any full-size Adreno readback')
    swipe(qmp, .21, .79)
    changed, away = await_interaction_frame(qmp, args.gpu_log, before, lambda im: rgb_error(anchor, im) >= 16)
    with args.output.with_name('desktop-after-swipe.png').open('xb') as f:
        changed.save(f, 'PNG')
    before_return = readback_count(args.gpu_log)
    swipe(qmp, .79, .21)
    returned, after = await_interaction_frame(qmp, args.gpu_log, before_return, lambda im: rgb_error(anchor, im) <= 8)
    with args.output.with_name('desktop-after-return.png').open('xb') as f:
        returned.save(f, 'PNG')
    print(json.dumps({'swipe_roundtrip': True, 'gpu_readbacks': [before, away, before_return, after],
                      'away_rgb_error': round(rgb_error(anchor, features(changed)), 2),
                      'return_rgb_error': round(rgb_error(anchor, features(returned)), 2)}), flush=True)


def settings_candidate(anchor, candidate):
    # A black app-launch transition also differs greatly from SpringBoard.
    # Require content in the interior, not just a status bar/home indicator.
    # This is a screen-content gate, not an app-identity classifier.
    if rgb_error(anchor, candidate) < 25:
        return False
    luma = candidate.convert('L')
    hist = luma.histogram()
    return sum(hist[36:]) >= luma.width * luma.height * .10 and ImageStat.Stat(luma).stddev[0] >= 8


def verify_settings(qmp, args, desktop):
    anchor = features(desktop)
    # The Settings gear is on row four, column three in the known iOS 16.5 reference.
    # Move before pressing, so the guest sees both axes before the touch begins.
    qmp.command('input-send-event', events=[
        {'type': 'abs', 'data': {'axis': axis, 'value': round(value * 32767)}}
        for axis, value in [('x', .615), ('y', .71)]])
    time.sleep(.25)
    before = readback_count(args.gpu_log)
    if not before:
        raise SystemExit('Desktop found before any full-size Adreno readback')
    qmp.command('input-send-event', events=[{'type': 'btn', 'data': {'down': True, 'button': 'left'}}])
    try:
        time.sleep(.18)
    finally:
        qmp.command('input-send-event', events=[{'type': 'btn', 'data': {'down': False, 'button': 'left'}}])
    changed, after = await_interaction_frame(qmp, args.gpu_log, before,
                                             lambda im: settings_candidate(anchor, im), timeout=240)
    with args.output.with_name('desktop-after-settings-tap.png').open('xb') as f:
        changed.save(f, 'PNG')
    # The metric proves a large canvas change; the screenshot must show Settings.
    print(json.dumps({'settings_tap_candidate': True, 'gpu_readbacks': [before, after],
                      'changed_rgb_error': round(rgb_error(anchor, features(changed)), 2)}), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('qmp', type=Path)
    parser.add_argument('springboard_reference', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--gpu-log', type=Path, help='require actual input and newer full-size Adreno readback')
    parser.add_argument('--interaction', choices=('swipe', 'settings'), default='swipe')
    parser.add_argument('--timeout', type=float, default=600)
    parser.add_argument('--max-mean-rgb-error', type=float, default=19.0)
    args = parser.parse_args()
    if args.output.exists() or not 10 <= args.timeout <= 900 or not 5 <= args.max_mean_rgb_error <= 30:
        parser.error('output must be new; timeout 10–900s; error threshold 5–30')
    with Image.open(args.springboard_reference) as im:
        expected = features(im)
    start = time.monotonic()
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as sock:
        sock.settimeout(5)
        sock.connect(str(args.qmp.resolve()))
        qmp = QmpDump(sock, args.output.parent.resolve() / (args.output.name + '.qmp.ppm'))
        best = 255.0
        desktop = None
        while time.monotonic() - start <= args.timeout:
            try:
                w, h, pixels, channels = qmp.capture()
                if channels != 3 or w / h > .6:
                    time.sleep(3)
                    continue
                im = Image.frombytes('RGB', (w, h), bytes(pixels))
                measured = features(im)
                error = rgb_error(expected, measured)
                best = min(error, best)
                if error <= args.max_mean_rgb_error:
                    with args.output.open('xb') as f:
                        im.save(f, 'PNG')
                    print(json.dumps({'width': w, 'height': h, 'mean_rgb_error': round(error, 2),
                                      'seconds_after_qmp_connect': round(time.monotonic() - start, 2)}), flush=True)
                    desktop = im
                    break
            except (OSError, ValueError, RuntimeError) as exc:
                if isinstance(exc, (ConnectionError, TimeoutError)):
                    raise
                print(f'desktop not captured yet: {exc}', flush=True)
            time.sleep(3)
        if desktop is None:
            raise SystemExit(f'No SpringBoard screen matched within the time limit (best mean RGB error: {best:.2f})')
        if args.gpu_log:
            verifier = verify_settings if args.interaction == 'settings' else verify_swipes
            verifier(qmp, args, desktop)


if __name__ == '__main__':
    main()
