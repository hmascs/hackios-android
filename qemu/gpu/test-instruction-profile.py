#!/usr/bin/env python3
"""Known-workload validation. Run the WHOLE command under the shared flock.

Example: flock /tmp/hackios-qemu-trial.lock python3 test-instruction-profile.py
  QEMU QEMU_LIB_DIR FRESH_OUTPUT --iterations 10000000 --repeats 3
The same binary is run with HIOS_PROFILE=0/1/2. This tests direct-chain coverage
and bounds instrumentation cost on this microbenchmark, not on the iOS desktop.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import selectors
import subprocess
import time

from trial_metrics import sha256_file

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('glpass', HERE / 'glpass-desktop.py')
glpass = importlib.util.module_from_spec(spec)
spec.loader.exec_module(glpass)
spec = importlib.util.spec_from_file_location('instruction_stats', HERE / 'instruction-stats.py')
stats = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stats)


def run_one(binary, libs, elf, output, mode, iterations, fault=False):
    output.mkdir()
    command = [str(binary), '-M', 'virt,gic-version=3', '-cpu', 'cortex-a76',
               '-nic', 'none',
               '-smp', '2', '-m', '128M', '-accel', 'tcg,thread=multi,tb-size=64',
               '-kernel', str(elf), '-display', 'none', '-serial', 'stdio',
               '-monitor', 'none', '-qmp', 'unix:qmp.sock,server=on,wait=off', '-no-reboot', '-S']
    env = {key: value for key, value in os.environ.items() if not key.startswith('HIOS_')}
    env.update(LD_LIBRARY_PATH=str(libs), HIOS_PROFILE=str(mode))
    qmp = None
    with (output / 'stderr.log').open('wb') as log:
        process = subprocess.Popen(command, env=env, cwd=output, stdin=subprocess.DEVNULL,
                                   stdout=subprocess.PIPE, stderr=log)
        try:
            qmp = glpass.Qmp(glpass.qmp_socket(output / 'qmp.sock', 10))
            qmp.command('cont')
            before = done = None
            raw = b''
            deadline = time.monotonic() + 45
            with selectors.DefaultSelector() as selector:
                selector.register(process.stdout, selectors.EVENT_READ)
                while done is None:
                    events = selector.select(max(0, deadline - time.monotonic()))
                    if not events:
                        raise RuntimeError('probe timed out')
                    data = os.read(process.stdout.fileno(), 4096)
                    if not data:
                        raise RuntimeError(f'probe exited early ({process.poll()}): {raw!r}')
                    raw += data
                    stamp = time.monotonic()
                    if b'E' in data:
                        raise RuntimeError(f'probe unexpected exception: {raw!r}')
                    if b'B' in data and before is None:
                        before = stamp
                    if b'D' in data:
                        done = stamp
            if before is None or done <= before:
                raise RuntimeError('workload too short to bracket UART markers separately')
            qmp.command('stop')
            text = qmp.command('human-monitor-command', **{'command-line': 'info jit'})
            (output / 'instructions.txt').write_text(text)
            result = dict(mode=mode, elapsed_uart_seconds=done - before,
                          expected_user_instructions=3 * iterations + 4, fault=fault, command=command)
            if mode:
                counters, buckets = stats.parse_snapshot(text)
                actual = sum(row['user_instructions'] for row in counters.values())
                if actual != result['expected_user_instructions']:
                    raise AssertionError(f'direct-chain user count {actual} != {result["expected_user_instructions"]}')
                if counters[1]['user_instructions'] or counters[1]['kernel_instructions']:
                    raise AssertionError('powered-off secondary CPU unexpectedly counted instructions')
                if mode == 2:
                    bucket_total = sum(row['instructions'] for row in buckets.values())
                    all_instructions = sum(row['user_instructions'] + row['kernel_instructions']
                                           for row in counters.values())
                    if bucket_total != all_instructions:
                        raise AssertionError('full-VA buckets do not sum to the instruction counters')
                    if (0, 0x40000000) not in buckets:
                        raise AssertionError('CF_PCREL runtime address was lost (expected RAM bucket)')
                if fault:
                    if sum(row['corrections'] for row in counters.values()) < 1:
                        raise AssertionError('fault probe did not exercise restore correction')
                    if sum(row['corrected_tail'] for row in counters.values()) < 3:
                        raise AssertionError('unused load/add/SVC tail was not subtracted')
                result.update(counters=counters, bucket_count=len(buckets),
                              actual_user_instructions=actual)
            (output / 'result.json').write_text(json.dumps(result, indent=2))
            return result
        finally:
            if qmp:
                qmp.close()
            glpass.stop(process)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('qemu', type=lambda value: Path(value).resolve())
    parser.add_argument('libs', type=lambda value: Path(value).resolve())
    parser.add_argument('output', type=lambda value: Path(value).resolve())
    parser.add_argument('--iterations', type=int, default=10000000)
    parser.add_argument('--repeats', type=int, default=3)
    args = parser.parse_args()
    if not 1 <= args.iterations <= 0xffffffff:
        parser.error('iterations must fit uint32')
    args.output.mkdir()
    elf = args.output / 'probe.elf'
    build = ['clang', '--target=aarch64-none-elf', '-nostdlib', '-fuse-ld=lld',
             '-Wl,-Ttext=0x40080000', '-Wl,--entry=_start',
             f'-DITERATIONS={args.iterations}', str(HERE / 'instruction-probe.S'), '-o', str(elf)]
    subprocess.run(build, check=True)
    rows = []
    # Alternation limits thermal/order bias. Mode 2 is a diagnostic overhead case.
    for repetition in range(args.repeats):
        modes = [0, 1, 2] if repetition % 2 == 0 else [2, 1, 0]
        for mode in modes:
            row = run_one(args.qemu, args.libs, elf,
                          args.output / f'run-{repetition}-mode-{mode}', mode, args.iterations)
            rows.append(row)
            print(json.dumps(dict(repetition=repetition, mode=mode,
                                  elapsed_uart_seconds=row['elapsed_uart_seconds'],
                                  actual_user_instructions=row.get('actual_user_instructions'))), flush=True)
    fault_elf = args.output / 'fault-probe.elf'
    fault_build = build[:-2] + ['-DFAULT_PROBE=1', '-o', str(fault_elf)]
    subprocess.run(fault_build, check=True)
    for mode in (1, 2):
        row = run_one(args.qemu, args.libs, fault_elf,
                      args.output / f'fault-mode-{mode}', mode, args.iterations, fault=True)
        rows.append(row)
        print(json.dumps(dict(fault=True, mode=mode,
                              actual_user_instructions=row['actual_user_instructions'])), flush=True)
    result = dict(qemu_sha256=sha256_file(args.qemu), elf_sha256=sha256_file(elf),
                  fault_elf_sha256=sha256_file(fault_elf),
                  source_sha256=sha256_file(HERE / 'instruction-probe.S'),
                  build_command=build, iterations=args.iterations, rows=rows)
    (args.output / 'result.json').write_text(json.dumps(result, indent=2))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
