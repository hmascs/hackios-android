#!/usr/bin/env python3
"""Run one isolated remote-GLES boot with host-clock measurement boundaries.

Readback thresholds are a readiness heuristic, not proof of desktop contents.
Ring/readback throughput and sampled guest GL costs are separate metrics.  Raw
PC snapshots are optional diagnostics and cannot estimate guest CPU time shares.
All files, process ids, identities and measurement boundaries live in output/.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import struct
import subprocess
import sys
import time

from trial_metrics import LogCursor, ReadbackMetrics, SerialMetrics, sha256_file
from ring_metrics import create_ring, ring_snapshot

QEMU = 'qemu-system-aarch64'


def repack(src, output, root_apfs, vcpus=2, fbscale=None, guest_hash_profile=False, session_seconds=None, glbatch=None):
    if glbatch is not None and (isinstance(glbatch, bool) or not isinstance(glbatch, int) or glbatch not in (0, 1)):
        raise ValueError('glbatch must be 0 or 1')
    if glbatch is not None and guest_hash_profile:
        raise ValueError('production batch pair excludes guest hash-profile diagnostics')
    if session_seconds is not None and (isinstance(session_seconds, bool) or not isinstance(session_seconds, int) or not 300 <= session_seconds <= 7200):
        raise ValueError('session-seconds must be an integer in 300..7200')
    data = src.read_bytes()
    cmdline_records = []

    def entries():
        magic, length = struct.unpack_from('<4sI', data)
        if magic != b'XNUP' or length + 8 != len(data):
            raise ValueError('invalid XNUP header')
        offset = 8
        seen = set()
        while offset + 24 <= len(data):
            name, size, reserved = struct.unpack_from('<16sII', data, offset)
            offset += 24
            end = offset + (size + 15) // 16 * 16
            if reserved or end > len(data):
                raise ValueError('invalid XNUP entry')
            if name == bytes(16):
                if size or end != len(data) or b'ramdisk' not in seen:
                    raise ValueError('invalid XNUP terminator or missing ramdisk')
                yield bytes(24)
                return
            key = name.rstrip(b'\0')
            if key in seen:
                raise ValueError('duplicate XNUP entry')
            seen.add(key)
            item = root_apfs.read_bytes() if key == b'ramdisk' else data[offset:offset + size]
            if key == b'cmdline':
                original_cmdline = item.rstrip(b'\0')
                args = original_cmdline.split()
                replaced_session_tokens = 0
                if session_seconds is not None:
                    for token in args:
                        if token == b'loader_return_after' or token.startswith(b'loader_return_after='):
                            value = token.partition(b'=')[2]
                            if not value.isdigit() or int(value) > 0xffffffff:
                                raise ValueError('malformed loader_return_after token in source payload')
                            replaced_session_tokens += 1
                    args = [token for token in args if not token.startswith(b'loader_return_after=')]
                    args.append(f'loader_return_after={session_seconds}'.encode())
                if glbatch is not None:
                    for token in args:
                        if token == b'glbatch' or token.startswith(b'glbatch='):
                            if token.partition(b'=')[2] not in (b'0', b'1'):
                                raise ValueError('malformed glbatch token in source payload')
                    args = [token for token in args if not token.startswith((b'glbatch=', b'glhash=', b'glframes=', b'touch_lat='))]
                    args.append(f'glbatch={glbatch}'.encode())
                if sum(x.startswith(b'cpus=') for x in args) != 1:
                    raise ValueError('expected exactly one cpus= boot argument')
                args = [f'cpus={vcpus}'.encode() if x.startswith(b'cpus=') else x for x in args
                        if not x.startswith(b'virtio_blk=')]
                if fbscale:
                    args = [x for x in args if not x.startswith(b'fbscale=')]
                    args.append(f'fbscale={fbscale}'.encode())
                if guest_hash_profile:
                    args = [x for x in args if not x.startswith((b'glhash=', b'glframes=', b'touch_lat='))]
                    # Mode2 keeps frame/input timestamps without the frequent
                    # SpringBoard main-loop trace. Keep flags identical in 0/1.
                    args += [b'glhash=1', b'glframes=1', b'touch_lat=2']
                item = b' '.join(args) + b'\0'
                cmdline_records.append(dict(input=original_cmdline.decode('utf-8', errors='backslashreplace'),
                    output=item.rstrip(b'\0').decode('utf-8', errors='backslashreplace'),
                    input_sha256=hashlib.sha256(original_cmdline).hexdigest(),
                    output_sha256=hashlib.sha256(item.rstrip(b'\0')).hexdigest(),
                    session_seconds_requested=session_seconds,replaced_session_tokens=replaced_session_tokens,
                    glbatch_requested=glbatch,render_diagnostics_stripped=glbatch is not None))
            yield struct.pack('<16sII', name, len(item), 0) + item + bytes(-len(item) % 16)
            offset = end
        raise ValueError('XNUP terminator missing')

    body = b''.join(entries())
    if (session_seconds is not None or guest_hash_profile or glbatch is not None) and len(cmdline_records) != 1:
        raise ValueError('managed diagnostic flags require exactly one XNUP cmdline entry')
    final_payload = struct.pack('<4sI', b'XNUP', len(body)) + body
    with output.open('xb') as stream:
        stream.write(final_payload)
    return dict(source_payload_sha256=hashlib.sha256(data).hexdigest(),
                output_payload_sha256=hashlib.sha256(final_payload).hexdigest(),
                cmdlines=cmdline_records, session_seconds_requested=session_seconds)


def process_identity(pid):
    record = dict(pid=pid)
    try:
        text = Path(f'/proc/{pid}/stat').read_text()
        fields = text[text.rfind(')')+2:].split()
        record['starttime_ticks'] = int(fields[19])
    except (OSError, ValueError, IndexError) as exc:
        record['identity_error'] = str(exc)
    return record


def stop(proc):
    # Operate only on the Popen child owned by this run, never a name pattern.
    if proc is None or proc.poll() is not None:
        return
    proc.terminate()
    try:
        proc.wait(timeout=8)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait(timeout=8)


def qmp_socket(path, timeout=10):
    deadline = time.monotonic() + timeout
    while True:
        stream = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        stream.settimeout(timeout)
        try:
            stream.connect(str(path))
            return stream
        except (FileNotFoundError, ConnectionRefusedError):
            stream.close()
            if time.monotonic() >= deadline:
                raise RuntimeError(f'no QMP socket at {path}')
            time.sleep(.05)


class Qmp:
    def __init__(self, sock):
        self.sock = sock
        self.stream = sock.makefile('rwb')
        self.next_id = 0
        greeting = self.read()
        if 'QMP' not in greeting:
            raise RuntimeError(f'invalid QMP greeting: {greeting}')
        self.command('qmp_capabilities')

    def read(self):
        line = self.stream.readline()
        if not line:
            raise RuntimeError('QMP connection closed')
        return json.loads(line)

    def command(self, name, **params):
        self.next_id += 1
        command_id = self.next_id
        self.stream.write(json.dumps(dict(execute=name, arguments=params,
                                          id=command_id)).encode() + b'\n')
        self.stream.flush()
        while True:
            reply = self.read()
            if reply.get('id') != command_id:
                continue
            if 'return' in reply:
                return reply['return']
            if 'error' in reply:
                raise RuntimeError(reply['error'])

    def close(self):
        self.stream.close()
        self.sock.close()


def cpu_seconds(pid):
    try:
        fields = Path(f'/proc/{pid}/stat').read_text().rsplit(')', 1)[1].split()
        return (int(fields[11]) + int(fields[12])) / os.sysconf('SC_CLK_TCK')
    except (FileNotFoundError, ProcessLookupError, IndexError):
        return None


def thermal():
    values = []
    for zone in Path('/sys/class/thermal').glob('thermal_zone*/temp'):
        try:
            value = int(zone.read_text()) / 1000
            if 0 < value < 150:
                values.append(value)
        except (OSError, ValueError):
            pass
    return max(values) if values else None


def kernel_identity(payload):
    import hashlib
    data = payload.read_bytes()
    offset = 8
    while offset + 24 <= len(data):
        name, size, reserved = struct.unpack_from('<16sII', data, offset)
        offset += 24
        if reserved or offset + size > len(data):
            raise ValueError('invalid payload for kernel identity')
        if name.rstrip(b'\0') == b'kernel':
            return dict(bytes=size, sha256=hashlib.sha256(data[offset:offset + size]).hexdigest())
        offset += (size + 15) // 16 * 16
    raise ValueError('payload has no kernel')


def file_identity(path):
    path = Path(path)
    return dict(path=str(path), bytes=path.stat().st_size, sha256=sha256_file(path))


def runtime_identity(pid):
    from mapped_identity import runtime_identity as collect
    return collect(pid)


def prepare_root(args, output):
    """Prepare the exact ramdisk used by repack; never rebuild a declared prebuilt root."""
    destination = output / 'root.apfs'
    if args.root_apfs is not None:
        source = args.root_apfs
        if source.is_symlink() or not source.is_file():
            raise ValueError('prebuilt root must be a regular non-symlink file')
        size = source.stat().st_size
        if not 4096 <= size <= 48 * 1024 * 1024 or size % 4096:
            raise ValueError('prebuilt root must be 4KiB aligned and between 4KiB and 48MiB')
        before = file_identity(source)
        with source.open('rb') as reader, destination.open('xb') as writer:
            shutil.copyfileobj(reader, writer, 1024 * 1024)
        actual = file_identity(destination)
        after = file_identity(source)
        if any(before[key] != actual[key] or before[key] != after[key]
               for key in ('bytes', 'sha256')):
            raise ValueError('prebuilt root changed during copy or copied hash differs')
        return dict(mode='prebuilt', source=before, actual=actual, dylib_injection='none',
                    note='prebuilt root contents are authoritative; the --dylib path is only a declared component')
    copied_root = output / 'rootfs'
    shutil.copytree(args.rootfs, copied_root, symlinks=True)
    dylib = copied_root / 'lib/libredwood-gl.dylib'
    if not dylib.is_file() or dylib.is_symlink():
        raise ValueError('expected a regular Redwood dylib in the copied root')
    shutil.copyfile(args.dylib, dylib)
    subprocess.run(['python3', str(args.mkapfs), '-z', '-o', 'root.apfs', 'root=rootfs'],
                   cwd=output, check=True)
    return dict(mode='rebuilt', actual=file_identity(destination),
                packer=file_identity(args.mkapfs), dylib_injection='copied into rootfs/lib')


def payload_ramdisk_identity(payload):
    import hashlib
    data = payload.read_bytes()
    magic, size = struct.unpack_from('<4sI', data)
    if magic != b'XNUP' or size + 8 != len(data):
        raise ValueError('invalid payload identity header')
    offset = 8
    while offset + 24 <= len(data):
        name, size, reserved = struct.unpack_from('<16sII', data, offset)
        offset += 24
        if reserved or offset + size > len(data):
            raise ValueError('invalid payload ramdisk entry')
        if name.rstrip(b'\0') == b'ramdisk':
            return dict(bytes=size, sha256=hashlib.sha256(data[offset:offset+size]).hexdigest())
        offset += (size + 15) // 16 * 16
    raise ValueError('payload has no ramdisk')


def validate_socket_paths(output):
    output = Path(output).resolve()
    for name in ('qmp.sock', 'renderer.sock'):
        path = output / name
        size = len(str(path).encode('utf-8'))
        if size >= 108:
            raise ValueError(f'AF_UNIX socket path is {size} UTF-8 bytes (must be <108): {path}; choose shorter output (prefer <=100 bytes per socket)')


def atomic_phase(output, name, data):
    if not re.fullmatch(r'[a-z]+(?:-[a-z]+)*', name):
        raise ValueError('invalid internal phase name')
    output = Path(output).resolve()
    target = output / f'phase-{name}.json'
    temporary = output / f'.phase-{name}.json.tmp'
    if target.resolve().parent != output or temporary.resolve().parent != output:
        raise ValueError('phase output escaped current run')
    with temporary.open('x') as stream:
        json.dump(data, stream, indent=2)
    os.replace(temporary, target)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('qemu_run', 'ram', 'payload', 'rootfs', 'mkapfs', 'dylib', 'worker', 'output'):
        parser.add_argument(name, type=lambda value: Path(value).resolve())
    parser.add_argument('--root-apfs', type=lambda value: Path(value).absolute(),
                        help='copy this prebuilt root unchanged; skip rootfs, dylib injection and mkapfs')
    parser.add_argument('--vcpus', type=int, default=2)
    parser.add_argument('--machine', default='virt,gic-version=3',
                        help='explicit machine type/options, e.g. virt-10.1,gic-version=3')
    parser.add_argument('--settle-seconds', type=float, default=30)
    parser.add_argument('--desktop-readbacks', type=int, default=8,
                        help='readiness heuristic; inspect captured contents separately')
    parser.add_argument('--measure-seconds', type=float, default=420)
    parser.add_argument('--pc-sample', type=float, default=0,
                        help='biased env->pc diagnostic snapshots only; never CPU time shares')
    parser.add_argument('--boot-timeout', type=float, default=1500)
    parser.add_argument('--panel-address', type=lambda value: int(value, 0))
    parser.add_argument('--fbscale', type=int, choices=(1, 2, 3))
    parser.add_argument('--accel', default=os.environ.get('HIOS_ACCEL',
                                                       'tcg,thread=multi,tb-size=1360'))
    parser.add_argument('--ring-version', type=int, choices=(1, 2), default=1,
                        help='explicit producer protocol; v2 needs a matching HFR2 worker')
    parser.add_argument('--post-reference-desktop', type=lambda value: Path(value).resolve(),
                        help='human-checked 1125x2436 desktop reference required before post-measure input')
    parser.add_argument('--post-measure-plan', type=lambda value: Path(value).resolve(),
                        help='optional bounded tap/swipe/capture JSON, executed only after measure end')
    parser.add_argument('--finalfb-hz', type=int, choices=(0, 10, 20), default=0,
                        help='optional 30s final RAMFB memfd observer after first post-plan tap; no full-frame sequence to disk')
    parser.add_argument('--glbatch', type=int, choices=(0, 1), default=None,
                        help='exact production glbatch token; strip glhash/glframes/touch_lat for controlled batch pair')
    parser.add_argument('--session-seconds', type=int, default=None,
                        help='isolated payload loader_return_after override, 300..7200s; default preserves source token')
    parser.add_argument('--profile-mode', type=int, choices=(0, 1),
                        help='startup-only HIOS_PROFILE; 1 enables paused counter boundaries, 0 disables counters')
    parser.add_argument('--guest-hash-profile', action='store_true',
                        help='diagnostic boot flags glhash=1 glframes=1 touch_lat=2; activation receipt required')
    parser.add_argument('--instruction-profile', action='store_true',
                        help='stop vCPUs at boundaries and save custom info jit counters')
    args = parser.parse_args(argv)
    if args.profile_mode is not None:
        if args.profile_mode == 0 and args.instruction_profile:
            parser.error('profile-mode 0 cannot collect instruction counters')
        args.instruction_profile = args.profile_mode == 1
    if args.glbatch is not None and (args.guest_hash_profile or args.instruction_profile or args.pc_sample):
        parser.error('production glbatch pair excludes instruction/hash/PC profiling')
    if min(args.measure_seconds, args.boot_timeout) <= 0 or args.settle_seconds < 0:
        parser.error('measurement/boot durations must be positive; settle duration non-negative')
    if args.pc_sample and args.instruction_profile:
        parser.error('do not mix biased PC sampling with the instruction-count experiment')

    post_plan = None
    if args.post_measure_plan is not None:
        from post_interaction import load_plan
        post_plan = load_plan(args.post_measure_plan)
        if any(step['action'] in ('tap', 'swipe') for step in post_plan['steps']):
            if args.post_reference_desktop is None or not args.post_reference_desktop.is_file() or args.post_reference_desktop.stat().st_size > 16*1024*1024:
                parser.error('post-measure input requires a regular desktop reference <=16MiB')
    if args.finalfb_hz and (post_plan is None or not any(step['action'] == 'tap' for step in post_plan['steps'])):
        parser.error('finalfb observer requires a post-measure plan with a tap')
    if args.session_seconds is not None:
        if not 300 <= args.session_seconds <= 7200:
            parser.error('session-seconds must be in 300..7200')
        required = args.boot_timeout + args.settle_seconds + args.measure_seconds + (post_plan['timeout_seconds'] if post_plan else 0)
        if args.session_seconds < required + 30:
            parser.error('session-seconds must exceed boot + settle + measure + post-plan timeout by at least 30s')
    output, root = args.output, args.qemu_run
    try:
        validate_socket_paths(output)
    except ValueError as exc:
        parser.error(str(exc))
    output.mkdir(mode=0o700)
    if post_plan is not None:
        (output / 'post-measure-plan.json').write_text(json.dumps(post_plan, indent=2))
    qemu_root = root / 'qemu-android/qemu'
    root_preparation = prepare_root(args, output)
    shutil.copytree(root / 'esp', output / 'esp')
    shutil.copyfile(root / 'QEMU_VARS.fd', output / 'vars.fd')
    (output / 'tmp').mkdir()
    payload_preparation = repack(args.payload, output / 'payload.bin', output / 'root.apfs', args.vcpus, args.fbscale,
                                 guest_hash_profile=args.guest_hash_profile, session_seconds=args.session_seconds, glbatch=args.glbatch)
    root_preparation['payload_ramdisk'] = payload_ramdisk_identity(output / 'payload.bin')
    if any(root_preparation['payload_ramdisk'][key] != root_preparation['actual'][key]
           for key in ('bytes', 'sha256')):
        raise ValueError('actual payload ramdisk differs from prepared root')
    env = {**os.environ, 'LD_LIBRARY_PATH': str(qemu_root / 'lib'), 'TMPDIR': str(output / 'tmp')}
    if args.profile_mode is not None:
        env['HIOS_PROFILE'] = str(args.profile_mode)
    elif args.glbatch is not None:
        env['HIOS_PROFILE'] = '0'
    worker_env = {**os.environ, 'HACKIOS_EAGL_PROFILE_READBACK': '1',
                  'TMPDIR': str(output / 'tmp')}
    ring = output / 'gpu-frames.ring'
    create_ring(ring, args.ring_version)

    command = [str(qemu_root / QEMU), '-L', str(root / 'qemu-share'),
               '-M', args.machine, '-cpu', 'cortex-a76',
               '-smp', f'{args.vcpus},sockets=1,cores={args.vcpus},threads=1',
               '-m', '7G', '-accel', args.accel,
               '-chardev', 'socket,id=gpu,path=renderer.sock',
               '-netdev', 'user,id=net0,restrict=on,guestfwd=tcp:10.0.2.100:19484-chardev:gpu',
               '-device', 'virtio-net-device,netdev=net0',
               '-drive', f'if=pflash,format=raw,readonly=on,file={root}/QEMU_EFI.fd',
               '-drive', 'if=pflash,format=raw,file=vars.fd',
               '-drive', 'file=fat:rw:esp,format=raw,if=virtio',
               '-device', 'loader,file=payload.bin,addr=0xf0000000,force-raw=on',
               '-object', f'memory-backend-file,id=ram,size=7G,mem-path={args.ram},readonly=on,rom=off,share=off',
               '-machine', 'memory-backend=ram', '-global', 'virtio-mmio.force-legacy=false',
               '-device', 'virtio-tablet-device', '-device', 'virtio-keyboard-device', '-device', 'ramfb',
               '-qmp', 'unix:qmp.sock,server=on,wait=off',
               '-display', 'none', '-monitor', 'none', '-serial', 'file:serial.log', '-no-reboot']
    inputs = dict(qemu=qemu_root / QEMU, payload=output / 'payload.bin',
                  root_apfs=output / 'root.apfs', worker_launcher=args.worker,
                  firmware=root / 'QEMU_EFI.fd', loader=output / 'esp/EFI/BOOT/BOOTAA64.EFI',
                  vars_initial=output / 'vars.fd', ram_system=args.ram,
                  harness=Path(__file__).resolve(),
                  metrics_module=Path(__file__).with_name('trial_metrics.py').resolve(),
                  ring_module=Path(__file__).with_name('ring_metrics.py').resolve())
    if root_preparation['mode'] == 'rebuilt':
        inputs['dylib'] = args.dylib
        inputs['mkapfs'] = args.mkapfs
    elif args.dylib.is_file():
        inputs['declared_prebuilt_dylib'] = args.dylib
    if args.finalfb_hz:
        recorder_module = Path(__file__).with_name('finalbuffer.py').resolve()
        if recorder_module.is_file():
            inputs['finalbuffer_module'] = recorder_module
    inputs['mapped_identity_module'] = Path(__file__).with_name('mapped_identity.py').resolve()
    if post_plan is not None:
        inputs['post_measure_plan'] = output / 'post-measure-plan.json'
        inputs['post_interaction_module'] = Path(__file__).with_name('post_interaction.py').resolve()
        inputs['ppm_pixels_module'] = Path(__file__).with_name('ppm_pixels.py').resolve()
        if args.post_reference_desktop is not None:
            inputs['post_reference_desktop'] = args.post_reference_desktop
    worker_binary = args.worker.parent / 'hackios-zink-session'
    if worker_binary.is_file():
        inputs['worker_binary'] = worker_binary
    identity = dict(schema=3, command=command, protocol_version=args.ring_version,
                    root_preparation=root_preparation, payload_preparation=payload_preparation,
                    startup_profile_mode=args.profile_mode,
                    guest_hash_profile_requested=args.guest_hash_profile,
                    inputs={name: file_identity(path) for name, path in inputs.items()},
                    roms={str(path.relative_to(root / 'qemu-share')): file_identity(path)
                          for path in sorted((root / 'qemu-share').rglob('*')) if path.is_file()},
                    kernel=kernel_identity(output / 'payload.bin'),
                    environment={key: value for key, value in env.items() if key.startswith('HIOS_')},
                    limitations=['header publication consistency does not by itself validate copied pixel contents',
                                 'runtime mapped-DSO list is terminal only; previously unloaded libraries are not covered',
                                 'readiness is a readback-count heuristic, not visual desktop detection'])
    (output / 'identity.json').write_text(json.dumps(identity, indent=2))
    serial_cursor = LogCursor(output / 'serial.log')
    worker_cursor = LogCursor(output / 'worker.log')
    serial, readbacks = SerialMetrics(), ReadbackMetrics()
    started = time.monotonic()
    vm = worker = qmp = None
    finalfb_fd = None
    finalfb_recorder = None
    if args.finalfb_hz:
        try:
            finalfb_fd = os.memfd_create('hios-finalfb', 0)
        except (OSError, AttributeError) as exc:
            (output / 'finalfb-unavailable.json').write_text(json.dumps(dict(error=str(exc), measured=False)))
    milestones, measure, pc_samples = {}, dict(schema=3, max_boundary_skew_seconds=0.1), []
    failure = None
    panel = args.panel_address
    thermal_cache = dict(value=None, sampled_at=None)

    def save_phase(name):
        atomic_phase(output, name, dict(schema=1, phase=name, t=time.monotonic()-started,
            measure=measure, milestones=milestones, serial_offset=serial_cursor.offset,
            worker_offset=worker_cursor.offset, readbacks=readbacks.count,
            qemu_pid=vm.pid if vm else None, worker_pid=worker.pid if worker else None,
            note='stage action completion only; not visual Settings/Home acceptance'))

    def append_logs():
        serial.consume(serial_cursor.lines())
        readbacks.consume(worker_cursor.lines())

    def snapshot(phase, boundary=None):
        # Count sources are not sampled atomically. Bracket the WHOLE read,
        # retaining per-source instants so consumers can bound boundary skew.
        before = time.monotonic()
        append_logs()
        logs_at = time.monotonic()
        publication = ring_snapshot(ring, args.ring_version)
        ring_at = time.monotonic()
        cpu = cpu_seconds(vm.pid)
        cpu_at = time.monotonic()
        # Hundreds of proot thermal sysfs reads previously cost 0.2–0.4s
        # per snapshot. Cache environmental observations and never block a
        # measure boundary on a temperature refresh.
        if boundary is None and (thermal_cache['sampled_at'] is None or
                                 cpu_at - thermal_cache['sampled_at'] >= 30):
            thermal_cache.update(value=thermal(), sampled_at=time.monotonic())
        temperature = thermal_cache['value']
        after = time.monotonic()
        item = dict(t=(before + after) / 2 - started,
                    t_before=before - started, t_after=after - started,
                    source_t=dict(logs=logs_at - started, ring=ring_at - started,
                                  cpu=cpu_at - started, thermal=after - started),
                    clock_bracket_seconds=after - before,
                    phase=phase, desktop=milestones.get('desktop-readbacks'),
                    cpu=cpu, thermal=temperature,
                    thermal_sample_t=(thermal_cache['sampled_at'] - started
                                      if thermal_cache['sampled_at'] is not None else None),
                    thermal_age_seconds=(after - thermal_cache['sampled_at']
                                         if thermal_cache['sampled_at'] is not None else None),
                    readbacks=readbacks.count,
                    readback_resets=readbacks.resets, serial_offset=serial_cursor.offset,
                    worker_offset=worker_cursor.offset, **publication)
        if boundary:
            item['boundary'] = boundary
        with (output / 'samples.jsonl').open('a') as stream:
            stream.write(json.dumps(item) + '\n')
        return item

    def profile_snapshot(name):
        before = time.monotonic()
        qmp.command('stop')
        if qmp.command('query-status')['running']:
            raise RuntimeError('instruction snapshot requested while vCPUs still running')
        stopped = time.monotonic()
        text = qmp.command('human-monitor-command', **{'command-line': 'info jit'})
        if 'HIOS_PROFILE {' not in text:
            raise RuntimeError('paused instruction counters missing; use HIOS_PROFILE=1/2 instrumented QEMU')
        (output / f'{name}-instructions.txt').write_text(text)
        resume_requested = time.monotonic()
        qmp.command('cont')
        return dict(stop_requested_t=before - started, stopped_t=stopped - started,
                    resume_requested_t=resume_requested - started,
                    resumed_t=time.monotonic() - started)

    def interrupt(signum, frame):
        raise KeyboardInterrupt(f'signal {signum}')

    old_term = signal.signal(signal.SIGTERM, interrupt)
    try:
        with socket.socket(socket.AF_UNIX) as server, (output / 'qemu.log').open('wb') as log:
            server.bind(str(output / 'renderer.sock'))
            server.listen(1)
            server.settimeout(10)
            vm = subprocess.Popen(command, cwd=output, env=env, stdout=log, stderr=log,
                                  pass_fds=(finalfb_fd,) if finalfb_fd is not None else ())
            with server.accept()[0] as channel, (output / 'worker.log').open('wb') as worker_log:
                worker = subprocess.Popen([args.worker, 'Adreno', '--frame-ring', str(ring)],
                                          stdin=channel, stdout=channel, stderr=worker_log,
                                          cwd=output, env=worker_env)
                (output / 'processes.json').write_text(json.dumps(dict(
                    harness_pid=os.getpid(), qemu_pid=vm.pid, worker_pid=worker.pid,
                    cwd=str(output), qemu_command=command,
                    processes=dict(harness=process_identity(os.getpid()),
                                   qemu=process_identity(vm.pid), worker=process_identity(worker.pid))), indent=2))
                qmp = Qmp(qmp_socket(output / 'qmp.sock'))
                requested_type = args.machine.split(',', 1)[0]
                machines = qmp.command('query-machines')
                resolved = [entry for entry in machines
                            if entry.get('name') == requested_type or entry.get('alias') == requested_type]
                identity['machine'] = dict(requested=args.machine,
                                           resolved_types=[entry['name'] for entry in resolved])
                (output / 'identity.json').write_text(json.dumps(identity, indent=2))
                save_phase('qmp-connected')
                desktop_at = deadline = None
                next_sample = next_report = started
                next_pc_sample = started
                while True:
                    now = time.monotonic()
                    append_logs()
                    if vm.poll() is not None:
                        raise RuntimeError(f'QEMU exited {vm.returncode}; see qemu.log/serial.log')
                    if worker.poll() is not None:
                        raise RuntimeError(f'worker exited {worker.returncode}; see worker.log')
                    if serial.replay_at is not None:
                        raise RuntimeError('loader returned/replayed kernel log before measurement finished')
                    if serial.frames and 'first-frame' not in milestones:
                        milestones['first-frame'] = now - started
                    if panel is None and serial.panel:
                        width, height, panel = serial.panel
                        milestones['panel'] = now - started
                        (output / 'panel-address.txt').write_text(f'{width}x{height} {panel:#x}\n')
                    if desktop_at is None and readbacks.count >= args.desktop_readbacks:
                        desktop_at = now
                        milestones['desktop-readbacks'] = now - started
                    if desktop_at is None and now - started > args.boot_timeout:
                        raise RuntimeError(f'fewer than {args.desktop_readbacks} readbacks after {args.boot_timeout}s')
                    if desktop_at is not None and 'start' not in measure and now >= desktop_at + args.settle_seconds:
                        measure['hash_profile_receipts'] = list(serial.hash_profile_receipts)
                        measure['remote_clients'] = list(serial.remote_clients)
                        if args.guest_hash_profile and not serial.hash_profile_receipts:
                            raise RuntimeError('glhash=1 requested but activation receipt missing; missing hash lines are not zero work')
                        if args.instruction_profile:
                            measure['profile_start'] = profile_snapshot('start')
                        measure['start'] = snapshot('measure', 'start')
                        milestones['measure-start'] = measure['start']['t']
                        (output / 'measure-start.txt').write_text(f'{measure["start"]["serial_offset"]}\n')
                        deadline = started + measure['start']['t'] + args.measure_seconds
                        save_phase('measure-start')
                        print(f'{measure["start"]["t"]:7.2f}s measuring {args.measure_seconds:g}s', flush=True)
                    if deadline is not None and now >= deadline:
                        measure['end'] = snapshot('measure', 'end')
                        (output / 'measure-end.txt').write_text(f'{measure["end"]["serial_offset"]}\n')
                        if args.instruction_profile:
                            measure['profile_end'] = profile_snapshot('end')
                        save_phase('measure-end')
                        break
                    phase = 'measure' if 'start' in measure else ('settle' if desktop_at else 'boot')
                    if now >= next_sample:
                        item = snapshot(phase)
                        next_sample = now + 1
                        if now >= next_report:
                            print(f'{item["t"]:7.2f}s phase={phase} cpu={item["cpu"]}s '
                                  f'thermal={item["thermal"]} readbacks={item["readbacks"]} '
                                  f'rings={item["ring_seq"]}', flush=True)
                            next_report = now + 30
                    if args.pc_sample and phase == 'measure' and now >= next_pc_sample:
                        until = time.monotonic() + args.pc_sample
                        while time.monotonic() < until:
                            text = qmp.command('human-monitor-command', **{'command-line': 'info registers -a'})
                            pc_samples.append(dict(t=time.monotonic() - started, dump=text))
                            time.sleep(.008)
                        next_pc_sample = time.monotonic() + 1
                    time.sleep(.05)
                # The measurement end and (optional) paused-counter end have
                # already been saved. Post-measure input cannot affect that window.
                if post_plan is not None:
                    from post_interaction import run_plan
                    measure['post_interaction'] = dict(after_measure_end_t=measure['end']['t'],
                                                       report='post-interaction/result.json')
                    if args.instruction_profile:
                        measure['post_start'] = snapshot('post-interaction', 'post-start')
                        measure['post_profile_start'] = profile_snapshot('post-start')
                    if args.finalfb_hz and finalfb_fd is not None:
                        try:
                            from finalbuffer import FinalFbRecorder
                            from ppm_pixels import decode_p6
                            finalfb_recorder = FinalFbRecorder(output / 'finalfb-settings', finalfb_fd,
                                hz=args.finalfb_hz, seconds=30, scene='settings-launch-including-idle', decoder=decode_p6)
                        except Exception as exc:
                            (output / 'finalfb-unavailable.json').write_text(json.dumps(dict(error=str(exc), measured=False)))
                    save_phase('post-start')
                    try:
                        run_plan(qmp, post_plan, output, ring, args.ring_version,
                                 desktop_reference=args.post_reference_desktop, serial_path=output / 'serial.log',
                                 finalfb_recorder=finalfb_recorder)
                    finally:
                        if args.instruction_profile:
                            measure['post_profile_end'] = profile_snapshot('post-end')
                            measure['post_end'] = snapshot('post-interaction', 'post-end')
                    save_phase('post-end')
                # These hashes are outside the timed interval. They identify the
                # executable DSOs and GPU/loader settings actually mapped by each
                # child, including launcher overrides of the inherited environment.
                identity['runtime'] = dict(qemu=runtime_identity(vm.pid),
                                           worker=runtime_identity(worker.pid))
                (output / 'identity.json').write_text(json.dumps(identity, indent=2))
                # One persistent QMP connection avoids the old second-greeting deadlock.
                # Evidence collection happens AFTER the measurement boundary.
                final_image = 'post-interaction-final.ppm' if post_plan is not None else 'desktop.ppm'
                qmp.command('screendump', filename=str(output / final_image))
                if panel and serial.panel:
                    width, height, _ = serial.panel
                    qmp.command('pmemsave', val=panel, size=width * height * 4,
                                filename=str(output / 'panel.bin'))
                (output / 'ring-frame.raw').write_bytes(ring.read_bytes()[64:])
    except BaseException as exc:
        failure = f'{type(exc).__name__}: {exc}'
        raise
    finally:
        if qmp is not None:
            qmp.close()
        stop(worker)
        stop(vm)
        if finalfb_fd is not None:
            os.close(finalfb_fd)
        signal.signal(signal.SIGTERM, old_term)
        measure['failure'] = failure
        measure['notes'] = ['ring/readback throughput is not proof of distinct visible contents',
                            'PC dumps expose env->pc and are not a CPU time profile']
        (output / 'measure.json').write_text(json.dumps(measure, indent=2))
        (output / 'run.json').write_text(json.dumps(dict(
            schema=2, milestones=milestones, wall_seconds=time.monotonic() - started,
            panel=f'{panel:#x}' if panel else None, failure=failure,
            serial_duplicates=serial.duplicates, serial_conflicts=serial.conflicts), indent=2))
        if pc_samples:
            (output / 'pc-samples.json').write_text(json.dumps(dict(
                warning='biased env->pc snapshots; not a CPU-time distribution', samples=pc_samples)))
    print(json.dumps(dict(milestones=milestones, failure=failure)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
