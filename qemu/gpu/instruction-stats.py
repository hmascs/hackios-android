#!/usr/bin/env python3
"""Summarise paused per-vCPU HIOS_PROFILE snapshots, without time-share claims."""
import argparse
import json
from pathlib import Path


def parse_snapshot(text):
    counters, buckets = {}, {}
    for line in text.splitlines():
        if line.startswith('HIOS_PROFILE '):
            row = json.loads(line[len('HIOS_PROFILE '):])
            if row['cpu'] in counters:
                raise ValueError('duplicate vCPU in counter snapshot')
            counters[row['cpu']] = row
        elif line.startswith('HIOS_BUCKET '):
            row = json.loads(line[len('HIOS_BUCKET '):])
            key = (row['cpu'], int(row['address'], 16))
            if key in buckets:
                raise ValueError('duplicate full-address bucket in snapshot')
            buckets[key] = row
    if not counters:
        raise ValueError('no paused HIOS_PROFILE counters; instrumented build/environment required')
    return counters, buckets


def difference(start_text, end_text):
    start, start_buckets = parse_snapshot(start_text)
    end, end_buckets = parse_snapshot(end_text)
    if set(start) != set(end):
        raise ValueError('vCPU set changed between snapshots')
    delta = {}
    for cpu, last in end.items():
        if last['mode'] != start[cpu]['mode']:
            raise ValueError('profile mode changed during the run')
        for key, value in last.items():
            if key in ('cpu', 'mode'):
                continue
            amount = value - start[cpu][key]
            if amount < 0:
                raise ValueError(f'counter regressed: cpu={cpu} {key}')
            delta[key] = delta.get(key, 0) + amount
    classes = {}
    for cls in ('user', 'kernel'):
        upper = delta[f'{cls}_instructions']
        uncertain = delta[f'{cls}_uncertain']
        classes[cls] = dict(credited_instructions=upper,
                            lower_instructions=max(0, upper - uncertain),
                            uncertain_credit=uncertain, blocks=delta[f'{cls}_blocks'])
    credited = sum(row['credited_instructions'] for row in classes.values())
    lower = sum(row['lower_instructions'] for row in classes.values())
    buckets = []
    for key, last in end_buckets.items():
        previous = start_buckets.get(key, dict(instructions=0, uncertain=0))
        amount = last['instructions'] - previous['instructions']
        uncertain = last['uncertain'] - previous['uncertain']
        if amount < 0 or uncertain < 0:
            raise ValueError('address bucket regressed')
        if amount:
            buckets.append(dict(cpu=key[0], address=f'0x{key[1]:016x}',
                                credited_instructions=amount, uncertain_credit=uncertain))
    return dict(semantics='TB-entry credits, restore-corrected; lower bound subtracts all no-restore credits',
                classes=classes, credited_instructions=credited, lower_instructions=lower,
                uncertainty_fraction=(credited - lower) / credited if credited else None,
                translations=delta['translations'], corrected_tail=delta['corrected_tail'],
                corrections=delta['corrections'], unbounded_exits=delta['unbounded_exits'],
                top_buckets=sorted(buckets, key=lambda row: row['credited_instructions'], reverse=True)[:16])


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('trial', type=Path)
    parser.add_argument('--window', choices=('measure', 'post'), default='measure')
    args = parser.parse_args(argv)
    trial = args.trial
    prefix = 'post-' if args.window == 'post' else ''
    result = difference((trial / f'{prefix}start-instructions.txt').read_text(),
                        (trial / f'{prefix}end-instructions.txt').read_text())
    measure = json.loads((trial / 'measure.json').read_text())
    if args.window == 'post':
        post = json.loads((trial / 'post-interaction/result.json').read_text())
        if post.get('failure'):
            raise ValueError('post plan did not complete; counters are partial diagnostics only')
    result['window'] = args.window
    result['actual_content_fps'] = None
    result['scope_note'] = 'all-VM EL0/EL1+ credits, not per-process CPU share or verified content frames'
    first, last = measure[prefix.replace('-', '_')+'profile_start'], measure[prefix.replace('-', '_')+'profile_end']
    minimum_seconds = last['stop_requested_t'] - first['resumed_t']
    maximum_seconds = last['stopped_t'] - first['resume_requested_t']
    if min(minimum_seconds, maximum_seconds) <= 0:
        raise ValueError('invalid counter time brackets')
    result['running_wall_seconds_bracket'] = [minimum_seconds, maximum_seconds]
    result['MIPS_bracket'] = [result['lower_instructions'] / maximum_seconds / 1e6,
                             result['credited_instructions'] / minimum_seconds / 1e6]
    first_count = measure[prefix.replace('-', '_')+'start'].get('ring_seq')
    last_count = measure[prefix.replace('-', '_')+'end'].get('ring_seq')
    publications = (last_count - first_count
                    if first_count is not None and last_count is not None else 0)
    if publications > 0:
        result['approx_instructions_per_ring_publication'] = [
            result['lower_instructions'] / publications,
            result['credited_instructions'] / publications]
        result['per_publication_note'] = (
            'ring and paused-counter endpoints are bracketed separately; '
            'ring publications do not establish distinct visible-content frames')
    print(json.dumps(result, indent=2))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
