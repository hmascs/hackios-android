#!/usr/bin/env python3
"""Bounded post-measure input/capture plan using the harness's ONE QMP client.

Only tap, swipe and capture are accepted. QMP acknowledgement proves delivery
submission, not guest input handling or app identity. Image/sequence evidence
must be reviewed independently; this module never calculates content FPS.
"""
import hashlib
import json
import math
from pathlib import Path
import re
import struct
import time

from ring_metrics import ring_snapshot, WIDTH, HEIGHT, HEADER, STRIDE

LABEL = re.compile(r'^[A-Za-z0-9][A-Za-z0-9_-]{0,47}$')


def number(value, low, high, label):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or not low <= value <= high:
        raise ValueError(f'invalid {label}')
    return value


def keys(record, allowed):
    if not isinstance(record, dict) or set(record)-set(allowed):
        raise ValueError('unknown interaction key or non-object record')


def validate_plan(plan):
    keys(plan, ('version', 'canvas', 'timeout_seconds', 'capture_interval_ms', 'max_captures', 'steps'))
    if plan.get('version') != 1 or plan.get('canvas') != [WIDTH, HEIGHT]:
        raise ValueError('interaction plan requires version1 and fixed 1125x2436 canvas')
    number(plan.get('timeout_seconds'), 1, 600, 'total timeout')
    number(plan.get('capture_interval_ms', 5000), 1000, 30000, 'capture interval')
    cap = number(plan.get('max_captures', 32), 1, 64, 'capture limit')
    if int(cap) != cap:
        raise ValueError('capture limit must be integer')
    steps = plan.get('steps')
    if not isinstance(steps, list) or not 1 <= len(steps) <= 16:
        raise ValueError('interaction plan requires 1..16 steps')
    labels = set()
    milliseconds = 0
    for step in steps:
        keys(step, ('action', 'name', 'x', 'y', 'from', 'to', 'hold_ms', 'duration_ms', 'points', 'settle_ms'))
        action, name = step.get('action'), step.get('name')
        if action not in ('tap', 'swipe', 'capture') or not isinstance(name, str) or not LABEL.fullmatch(name) or name in labels:
            raise ValueError('invalid/duplicate interaction name or unsupported action')
        labels.add(name)
        settle = number(step.get('settle_ms', 0), 0, 240000, 'settle time')
        milliseconds += settle
        if action == 'tap':
            if set(step)-{'action','name','x','y','hold_ms','settle_ms'}:
                raise ValueError('invalid tap keys')
            number(step.get('x'), 0, WIDTH-1, 'tap x')
            number(step.get('y'), 0, HEIGHT-1, 'tap y')
            milliseconds += 250 + number(step.get('hold_ms',180), 20, 2000, 'tap hold')
        elif action == 'swipe':
            if set(step)-{'action','name','from','to','duration_ms','points','settle_ms'}:
                raise ValueError('invalid swipe keys')
            for field in ('from','to'):
                point = step.get(field)
                if not isinstance(point,list) or len(point)!=2:
                    raise ValueError('swipe points require [x,y]')
                number(point[0],0,WIDTH-1,field+' x')
                number(point[1],0,HEIGHT-1,field+' y')
            points = number(step.get('points',12),2,32,'swipe point count')
            if int(points)!=points:
                raise ValueError('swipe point count must be integer')
            milliseconds += number(step.get('duration_ms',420),100,2000,'swipe duration')
        elif set(step)-{'action','name','settle_ms'}:
            raise ValueError('capture step cannot contain input fields')
    if milliseconds >= plan['timeout_seconds']*1000:
        raise ValueError('planned input/waits exceed total timeout (capture overhead also needs headroom)')
    return plan


def load_plan(path):
    path = Path(path)
    if not path.is_file() or path.stat().st_size>65536:
        raise ValueError('interaction plan must be a regular JSON file <=64KiB')
    return validate_plan(json.loads(path.read_text()))


def position(x,y):
    return [dict(type='abs',data=dict(axis=axis,value=round(value*32767/extent)))
            for axis,value,extent in (('x',x,WIDTH-1),('y',y,HEIGHT-1))]


def button(down):
    return dict(type='btn',data=dict(down=down,button='left'))


def hash_ring(path, version):
    first = ring_snapshot(path,version)
    if not first['ring_available']:
        return first
    with Path(path).open('rb',buffering=0) as stream:
        stream.seek(HEADER)
        pixels = stream.read(STRIDE*HEIGHT)
    last = ring_snapshot(path,version)
    if len(pixels)!=STRIDE*HEIGHT or not last['ring_available'] or first['ring_seq']!=last['ring_seq'] or first.get('ring_guard')!=last.get('ring_guard'):
        return dict(protocol_version=version,ring_available=False,ring_status='changed_during_hash',ring_seq=None)
    first.update(pixel_sha256=hashlib.sha256(pixels).hexdigest(),
                 hash_guard_checked=version==2,
                 note='v1 hash is unguarded; even v2 header/hash is not an app-identity or content-FPS oracle')
    return first


def desktop_precondition(capture_path, reference_path, serial_path):
    if reference_path is None or serial_path is None:
        return dict(passed=False,reason='desktop reference and serial evidence are required before input')
    from PIL import ImageChops, ImageStat
    from ppm_pixels import load_rgb
    expected,reference_decode=load_rgb(reference_path)
    current,capture_decode=load_rgb(capture_path)
    with expected,current:
        if expected.size!=(WIDTH,HEIGHT) or current.size!=(WIDTH,HEIGHT):
            return dict(passed=False,reason='desktop reference/capture dimensions do not match fixed canvas')
        def grid(image):
            return image.convert('RGB').crop((int(WIDTH*.04),int(HEIGHT*.315),int(WIDTH*.96),int(HEIGHT*.755))).resize((112,112))
        a,b=grid(expected),grid(current)
        error=sum(ImageStat.Stat(ImageChops.difference(a,b)).mean)/3
        contrast=sum(ImageStat.Stat(a).stddev)/3
    with Path(serial_path).open('rb') as stream:
        stream.seek(0,2);size=stream.tell();stream.seek(max(0,size-2*1024*1024))
        text=stream.read().decode(errors='replace')
    signals=[line[:500] for line in text.splitlines() if
             ('System shell connected' in line and 'springboard' in line.lower()) or
             'SpringBoard: OpenGL ES draws' in line or '[BootUI] systemShellChangedWithPrimary' in line]
    return dict(passed=error<=19 and contrast>=8 and bool(signals),
                icon_grid_mean_rgb_error=error,maximum_error=19,reference_contrast=contrast,
                reference_decoder=reference_decode,capture_decoder=capture_decode,
                springboard_log_signals=signals[-8:],
                meaning='reference similarity plus SpringBoard log evidence; not an app-identity oracle')


def run_plan(qmp, plan, output, ring, version, desktop_reference=None, serial_path=None, finalfb_recorder=None):
    validate_plan(plan)
    directory = Path(output)/'post-interaction'
    directory.mkdir()
    (directory/'plan.json').write_text(json.dumps(plan,indent=2))
    began = time.monotonic()
    deadline = began+plan['timeout_seconds']
    interval = plan.get('capture_interval_ms',5000)/1000
    report = dict(schema=1,started_monotonic=began,events=[],captures=[],
                  input_ack_semantics='QMP delivery acknowledgement only; guest/UI acceptance unverified',
                  measurement_scope='post-measure only; excluded from published throughput window',failure=None)
    pressed = False
    observer_started = observer_finished = False
    next_capture = began
    serial_offset = Path(serial_path).stat().st_size if serial_path is not None else 0

    def app_signals():
        nonlocal serial_offset
        if serial_path is None:
            return []
        with Path(serial_path).open('rb') as stream:
            stream.seek(serial_offset);data=stream.read(1024*1024);serial_offset+=len(data)
        tokens=('Preferences','com.apple.Preferences','SpringBoard','systemShellChanged')
        return [line[:500] for line in data.decode(errors='replace').splitlines()
                if any(token in line for token in tokens)][-40:]

    def persist():
        (directory/'result.json').write_text(json.dumps(report,indent=2))

    def check():
        if time.monotonic()>deadline:
            raise TimeoutError('post-measure interaction plan deadline exceeded')

    def send(events, cleanup=False):
        if not cleanup:
            check()
        row=dict(requested_t=time.monotonic()-began,events=events,cleanup=cleanup,submitted_ns=time.monotonic_ns())
        report['events'].append(row)
        try:
            row['ack']=qmp.command('input-send-event',events=events)
            row['ack_t']=time.monotonic()-began
            row['ack_ns']=time.monotonic_ns()
        except BaseException as exc:
            row['error']=f'{type(exc).__name__}: {exc}'
            raise

    def capture(label):
        nonlocal next_capture
        check()
        if len(report['captures'])>=plan.get('max_captures',32):
            raise RuntimeError('post-measure capture budget exhausted')
        index=len(report['captures'])
        path=directory/f'{index:03d}-{label}.ppm'
        row=dict(name=label,requested_t=time.monotonic()-began,
                 ring_before=hash_ring(ring,version),screenshot=path.name)
        report['captures'].append(row)
        row['submitted_ns']=time.monotonic_ns()
        qmp.command('screendump',filename=str(path.resolve()))
        row['completed_ns']=time.monotonic_ns()
        row.update(completed_t=time.monotonic()-began,ring_after=hash_ring(ring,version),
                   serial_offset=serial_offset,app_log_signals=app_signals(),
                   app_log_signal_semantics='observed serial text only; not a live process probe',
                   screenshot_sha256=hashlib.sha256(path.read_bytes()).hexdigest())
        next_capture=time.monotonic()+interval
        persist()

    def finish_observer():
        nonlocal observer_finished
        if finalfb_recorder is None or not observer_started or observer_finished:
            return
        observer_finished = True
        try:
            trace = finalfb_recorder.finish()
            report['finalfb'] = dict(trace='../finalfb-settings/trace.json',
                attempted=len(trace['samples']), planned=trace['planned_slots'],
                observer_error=trace.get('observer_error'), semantic_success=None)
        except Exception as exc:
            report['finalfb'] = dict(observer_error=f'{type(exc).__name__}: {exc}', semantic_success=None)
        persist()

    def observe():
        if finalfb_recorder is None or not observer_started or observer_finished:
            return None
        try:
            if finalfb_recorder.next_due_ns is not None:
                finalfb_recorder.capture_if_due(qmp, time.monotonic_ns())
            if finalfb_recorder.next_due_ns is None:
                finish_observer()
                return None
            return finalfb_recorder.next_due_ns / 1e9
        except Exception as exc:
            report['finalfb_error'] = f'{type(exc).__name__}: {exc}'
            finish_observer()
            return None

    def wait(seconds, label, periodic=True):
        target=time.monotonic()+seconds
        while time.monotonic()<target:
            check()
            observer_due=observe()
            now=time.monotonic()
            if periodic and now>=next_capture:
                capture(label)
                continue
            # Serial, fixed-rate observer: skip missed slots, never catch up.
            pause=min(target-now,deadline-now,next_capture-now if periodic else target-now,
                      observer_due-now if observer_due is not None else target-now)
            if pause>0:
                time.sleep(pause)

    def release():
        nonlocal pressed
        # A failed input request may have reached QEMU before its reply failed.
        # Always attempt release, even when the local pressed state is uncertain.
        send([button(False)],cleanup=True)
        pressed=False

    try:
        capture('before')
        if any(step['action'] in ('tap','swipe') for step in plan['steps']):
            report['precondition']=desktop_precondition(directory/report['captures'][0]['screenshot'],
                                                       desktop_reference,serial_path)
            persist()
            if not report['precondition']['passed']:
                raise RuntimeError('post-measure desktop precondition failed; no press submitted')
        for step in plan['steps']:
            name,action=step['name'],step['action']
            if action=='capture':
                capture(name)
            else:
                try:
                    if action=='tap':
                        send(position(step['x'],step['y']))
                        wait(.25,name,False)
                        pressed=True
                        send([button(True)])
                        wait(step.get('hold_ms',180)/1000,name,False)
                    else:
                        a,b=step['from'],step['to']
                        pressed=True
                        send(position(*a)+[button(True)])
                        count=int(step.get('points',12))
                        for i in range(1,count+1):
                            wait(step.get('duration_ms',420)/count/1000,name,False)
                            send(position(a[0]+(b[0]-a[0])*i/count,a[1]+(b[1]-a[1])*i/count))
                finally:
                    release()
                if action == 'tap' and finalfb_recorder is not None and not observer_started and not observer_finished:
                    try:
                        finalfb_recorder.start(time.monotonic_ns())
                        observer_started = True
                        report['finalfb'] = dict(started_ns=finalfb_recorder.origin,
                            scene='first-tap-launch-including-idle', completed_content_fps=None)
                        observe()
                    except Exception as exc:
                        observer_finished = True
                        report['finalfb_error'] = f'{type(exc).__name__}: {exc}'
                capture(name+'-after-input')
            wait(step.get('settle_ms',0)/1000,name+'-settle')
        capture('after')
    except BaseException as exc:
        report['failure']=f'{type(exc).__name__}: {exc}'
        raise
    finally:
        try:
            release()
        except BaseException as exc:
            report['release_error']=f'{type(exc).__name__}: {exc}'
        finish_observer()
        report['finished_t']=time.monotonic()-began
        report['pressed_state_at_finish']=pressed
        persist()
    return report
