#!/usr/bin/env python3
"""Observe real vCPU thread runtime and placement via QMP plus Linux /proc. No affinity writes."""
import argparse
from collections import Counter
import importlib.util
import json
import os
from pathlib import Path
import socket
import time

spec = importlib.util.spec_from_file_location('framebuffer', Path(__file__).with_name('measure-framebuffer.py'))
fb = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fb)


def task_stat(pid, tid):
    raw = Path(f'/proc/{pid}/task/{tid}/stat').read_text()
    # The name is parenthesized and may itself contain spaces or parentheses.
    fields = raw[raw.rindex(')') + 2:].split()
    return int(fields[11]) + int(fields[12]), int(fields[36]), int(fields[19])


def measure(pid, tids, duration, interval):
    previous = {tid: task_stat(pid, tid) for tid in tids}
    observed = {tid: Counter() for tid in tids}
    active_ticks = Counter()
    collision_samples = 0
    steps = 0
    start = time.monotonic()
    while time.monotonic() - start < duration:
        time.sleep(interval)
        current = {tid: task_stat(pid, tid) for tid in tids}
        cpus = []
        for tid, (ticks, cpu, starttime) in current.items():
            prior, _, identity = previous[tid]
            if starttime != identity or ticks < prior:
                raise RuntimeError(f'vCPU thread {tid} changed identity during test')
            delta = ticks - prior
            if delta:
                cpus.append(cpu)
                observed[tid][cpu] += 1
                active_ticks[tid] += delta
        collision_samples += len(cpus) != len(set(cpus))
        previous = current
        steps += 1
    elapsed = time.monotonic() - start
    hz = os.sysconf('SC_CLK_TCK')
    return dict(elapsed_s=round(elapsed, 3), sample_intervals=steps,
                intervals_with_shared_last_cpu=collision_samples,
                total_average_host_cores=round(sum(active_ticks.values()) / hz / elapsed, 3),
                vcpus=[dict(index=i, tid=tid, average_core_fraction=round(active_ticks[tid] / hz / elapsed, 3),
                            sampled_last_cpu_when_active=dict(sorted(observed[tid].items())))
                       for i, tid in enumerate(tids)])


def frequency(cpu):
    try:
        return int(Path(f'/sys/devices/system/cpu/cpu{cpu}/cpufreq/cpuinfo_max_freq').read_text())
    except (OSError, ValueError):
        return None


def isolation_plan(available, tids, spare, get_frequency=frequency):
    if spare < 2 or len(available) < len(tids) + spare:
        raise ValueError('not enough allowed CPUs for one per vCPU plus two housekeeping CPUs')
    speeds = {cpu: get_frequency(cpu) for cpu in available}
    if any(speed is None for speed in speeds.values()):
        raise ValueError('CPU frequency ranking unavailable; do not guess big cores')
    ranked = sorted(available, key=lambda cpu: (-speeds[cpu], cpu))
    return dict(zip(tids, ranked)), set(ranked[len(tids):])


def isolate_for_measurement(pid, tids, seconds, interval):
    # This is an opt-in lab benchmark, not an Android scheduler or an OS CPU reservation.
    task_dir = Path(f'/proc/{pid}/task')
    tasks = sorted(int(p.name) for p in task_dir.iterdir())
    before = {tid: (os.sched_getaffinity(tid), task_stat(pid, tid)[2]) for tid in tasks}
    common = set.intersection(*(mask for mask, _ in before.values()))
    dedicated, housekeeping = isolation_plan(common, tids, 2)
    desired = {tid: {dedicated[tid]} if tid in dedicated else housekeeping for tid in tasks}
    touched = []
    try:
        for tid in tasks:
            if task_stat(pid, tid)[2] != before[tid][1]:
                raise RuntimeError('thread identity changed before pinning')
            os.sched_setaffinity(tid, desired[tid])
            touched.append(tid)
            if os.sched_getaffinity(tid) != desired[tid]:
                raise RuntimeError('kernel did not apply complete affinity')
        result = measure(pid, tids, seconds, interval)
        if set(tasks) != {int(p.name) for p in task_dir.iterdir()}:
            raise RuntimeError('QEMU created or removed threads during test; isolation result invalid')
        if any(os.sched_getaffinity(tid) != desired[tid] for tid in tasks):
            raise RuntimeError('affinity changed during test; isolation result invalid')
        result['experimental_isolation'] = dict(vcpu_host_cpus=dedicated, housekeeping_host_cpus=sorted(housekeeping))
        return result, [sorted(desired[tid]) for tid in tids]
    finally:
        failures = []
        for tid in reversed(touched):
            try:
                if task_stat(pid, tid)[2] == before[tid][1]:
                    os.sched_setaffinity(tid, before[tid][0])
            except FileNotFoundError:
                pass
            except OSError as exc:
                failures.append(f'{tid}: {exc}')
        if failures:
            raise RuntimeError('could not restore original affinity: ' + '; '.join(failures))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('qmp_socket')
    p.add_argument('pid', type=int)
    p.add_argument('--seconds', type=float, default=10)
    p.add_argument('--interval-ms', type=float, default=100)
    p.add_argument('--isolate', action='store_true', help='temporarily pin vCPUs to distinct fast CPUs; isolated test VM only')
    a = p.parse_args()
    if a.pid <= 1 or not 0 < a.seconds <= 300 or not 20 <= a.interval_ms <= 1000:
        p.error('invalid PID, duration or interval')
    with socket.socket(socket.AF_UNIX) as sock:
        sock.settimeout(5)
        sock.connect(a.qmp_socket)
        qmp = fb.QmpDump(sock, None)
        cpus = sorted(qmp.command('query-cpus-fast'), key=lambda c: c['cpu-index'])
    if not cpus or [c['cpu-index'] for c in cpus] != list(range(len(cpus))):
        raise RuntimeError('QEMU vCPU IDs missing or non-contiguous; test requires a fixed topology')
    tids = [c['thread-id'] for c in cpus]
    if len(tids) != len(set(tids)) or any(tid <= 0 for tid in tids):
        raise RuntimeError('vCPUs share a host thread; cannot attribute runtime per vCPU (check MTTCG)')
    # Validate that the QMP TIDs belong to this PID before touching any affinity.
    for tid in tids:
        task_stat(a.pid, tid)
    interval = a.interval_ms / 1000
    if a.isolate:
        result, masks = isolate_for_measurement(a.pid, tids, a.seconds, interval)
    else:
        masks = [sorted(os.sched_getaffinity(tid)) for tid in tids]
        result = measure(a.pid, tids, a.seconds, interval)
    for cpu, mask in zip(result['vcpus'], masks):
        cpu['allowed_host_cpus_at_start'] = mask
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
