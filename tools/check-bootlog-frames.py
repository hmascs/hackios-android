#!/usr/bin/env python3
"""Classify isolated 1125×2436 boot captures using known-good QMP reference frames."""
import argparse
from pathlib import Path

from PIL import Image, ImageChops, ImageStat

SIZE = (1125, 2436)
SCENE = (0, 0, 1125, 2228)
PANEL = (50, 2228, 1074, 2420)


def load(path):
    if path.suffix == '.bgra':
        expected = SIZE[0] * SIZE[1] * 4
        with path.open('rb') as raw:
            pixels = raw.read(expected + 1)
        if len(pixels) != expected:
            raise ValueError(f'{path}: invalid raw framebuffer size')
        return Image.frombytes('RGB', SIZE, pixels, 'raw', 'BGRX')
    with Image.open(path) as source:
        if source.size != SIZE:
            raise ValueError(f'{path}: expected {SIZE}, got {source.size}')
        return source.convert('RGB')


def error(left, right):
    return sum(ImageStat.Stat(ImageChops.difference(left, right)).mean) / 3


def classify(frame, logo, desktop):
    scene = frame.crop(SCENE)
    logo_error = error(scene, logo.crop(SCENE))
    desktop_error = error(scene, desktop.crop(SCENE))
    # An all-black screen is numerically close to Logo; require bright pixels at its center.
    center = frame.crop((490, 1110, 625, 1250))
    bright_center = min(ImageStat.Stat(center).mean) > 200
    pm = frame.crop(PANEL)
    red, green, blue = pm.split()
    brightest = ImageChops.lighter(ImageChops.lighter(red, green), blue)
    panel_fraction = sum(brightest.histogram()[150:]) / (pm.width * pm.height)
    corners = [pm.getpixel((x, y)) for x in (0, pm.width - 1) for y in (0, pm.height - 1)]
    panel = corners.count((22, 22, 22)) >= 2 and panel_fraction >= .002
    if bright_center and logo_error < 1:
        phase = 'logo'
    elif desktop_error < 15:
        phase = 'desktop-reference-match'
    else:
        phase = 'other'
    return phase, panel, logo_error, desktop_error


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('logo', type=Path, help='known Apple Logo without logs')
    parser.add_argument('desktop', type=Path, help='known SpringBoard desktop with same preset')
    parser.add_argument('captures', type=Path, nargs='+')
    args = parser.parse_args()
    logo, desktop = load(args.logo), load(args.desktop)
    for capture in args.captures:
        frame = load(capture)
        phase, panel, le, de = classify(frame, logo, desktop)
        details = ''
        if capture.suffix == '.bgra' and capture.with_suffix('.png').is_file():
            details = f', MAE(adjacent QMP PNG={error(frame, load(capture.with_suffix(".png"))):.4f})'
        print(f'{capture.name}: {phase}, log-panel-signature={panel}, MAE(logo={le:.2f}, desktop={de:.2f}){details}')


if __name__ == '__main__':
    main()
