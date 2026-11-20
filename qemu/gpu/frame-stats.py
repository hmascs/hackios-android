#!/usr/bin/env python3
"""Report measured throughput separately from sampled guest rendering costs.

New experiments record exact boundary snapshots in measure.json.  Legacy trials
can still be inspected using measure-start.txt and timed samples, but their
sampled interval is explicitly labelled; no duration is inferred from a local GL
function's elapsed time.  Ring publications/readbacks may contain identical or
offscreen content and do not by themselves establish unique visible-content FPS.
"""
import argparse
import json
import math
from pathlib import Path
import statistics
import sys

from trial_metrics import LogCursor, SerialMetrics


def average(rows, key):
    return statistics.mean(row[key] for row in rows) if rows else 0.0


def finite_number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def boundary_errors(boundary, name, budget):
    errors = []
    for key in ('t', 't_before', 't_after', 'clock_bracket_seconds'):
        if not finite_number(boundary.get(key)):
            errors.append(f'{name} non-finite/missing timing: {key}')
    sources = boundary.get('source_t', {})
    order = ('logs', 'ring', 'cpu', 'thermal')
    for key in order:
        if not finite_number(sources.get(key)):
            errors.append(f'{name} non-finite/missing source timing: {key}')
    if errors:
        return errors
    before, after = boundary['t_before'], boundary['t_after']
    times = [before] + [sources[key] for key in order] + [after]
    if times != sorted(times) or not before <= boundary['t'] <= after:
        errors.append(f'{name} out-of-order snapshot/source timestamps')
    actual = after - before
    if actual < 0:
        errors.append(f'{name} negative snapshot bracket')
    previous = before
    for key in ('logs', 'ring', 'cpu'):
        if sources[key] - previous > budget:
            errors.append(f'{name} critical {key} source skew exceeds boundary budget')
        previous = sources[key]
    if not math.isclose(actual, boundary['clock_bracket_seconds'], rel_tol=1e-7, abs_tol=1e-8):
        errors.append(f'{name} declared snapshot bracket differs from timestamps')
    return errors


def load_trial(trial, tail=60):
    trial = Path(trial)
    serial = SerialMetrics()
    cursor = LogCursor(trial / 'serial.log')
    serial.consume(cursor.lines())
    metadata_path = trial / 'measure.json'
    metadata = json.loads(metadata_path.read_text()) if metadata_path.is_file() else {}
    start_path = trial / 'measure-start.txt'
    end_path = trial / 'measure-end.txt'
    start = int(start_path.read_text().strip()) if start_path.is_file() else None
    end = int(end_path.read_text().strip()) if end_path.is_file() else cursor.offset
    if metadata.get('start'):
        start = metadata['start']['serial_offset']
    if metadata.get('end'):
        end = metadata['end']['serial_offset']
    if serial.replay_at is not None:
        end = min(end, serial.replay_at)

    warnings, invalid = [], []
    if cursor.pending:
        warnings.append('unterminated EOF line excluded from diagnostic records')
    if metadata.get('schema', 0) >= 3:
        identity_path = trial / 'identity.json'
        identity = json.loads(identity_path.read_text()) if identity_path.is_file() else {}
        required = ('qemu', 'payload', 'root_apfs', 'worker_launcher',
                    'firmware', 'loader', 'vars_initial', 'ram_system', 'harness', 'metrics_module')
        preparation = identity.get('root_preparation', {})
        if preparation.get('mode') == 'prebuilt':
            actual = identity.get('inputs', {}).get('root_apfs', {})
            for part in ('source', 'actual', 'payload_ramdisk'):
                entry = preparation.get(part, {})
                if any(entry.get(key) != actual.get(key) or key not in entry for key in ('bytes', 'sha256')):
                    invalid.append(f'prebuilt root identity mismatch: {part}')
        else:
            required += ('dylib',)
        for name in required:
            item = identity.get('inputs', {}).get(name, {})
            if len(item.get('sha256', '')) != 64 or item.get('bytes') is None:
                invalid.append(f'missing artifact identity: {name}')
        for child in ('qemu', 'worker'):
            runtime = identity.get('runtime', {}).get(child, {})
            if not runtime.get('mapped_files') or runtime.get('error') or runtime.get('unreadable'):
                invalid.append(f'incomplete runtime DSO identity: {child}')
    if metadata.get('failure'):
        invalid.append('run reported failure: ' + str(metadata['failure']))
    run_path = trial / 'run.json'
    run = json.loads(run_path.read_text()) if run_path.is_file() else {}
    if run.get('failure'):
        invalid.append('run metadata reported failure: ' + str(run['failure']))
    if start is not None and (start < 0 or end < start or end > cursor.offset):
        invalid.append('invalid/out-of-file serial byte boundaries')
    if serial.replay_at is not None and metadata.get('end') and serial.replay_at < metadata['end']['serial_offset']:
        invalid.append('kernel replay occurred before measurement end')
    if serial.conflicts:
        invalid.append('conflicting serial record ids: restarted or multiple producer')
    if start is None:
        warnings.append('no measurement-start offset: local costs cover the original log only')
        start = 0
    if not metadata.get('end') and not end_path.is_file():
        warnings.append('no measurement-end byte offset: local costs after start may include later diagnostics')
    if serial.conflicts:
        warnings.append('record ids have conflicting payloads; multiple writers/restarts require review')
    frames = serial.selected(serial.frames, start, end)
    outs = serial.selected(serial.outs, start, end)
    result = dict(trial=trial.name, serial_start=start, serial_end=end,
                  replay_at=serial.replay_at, duplicates=serial.duplicates,
                  conflicts=serial.conflicts, frame_samples=len(frames),
                  readback_samples=len(outs), warnings=warnings,
                  local_costs=None, throughput=None)
    if frames:
        selected = frames[-tail:] if tail else frames
        result['local_costs'] = dict(
            scope='diagnostic frame samples inside byte window',
            sampled_frames=len(selected), first_id=selected[0]['id'], last_id=selected[-1]['id'],
            mean_ms=average(selected, 'total') / 1000,
            median_ms=statistics.median(row['total'] for row in selected) / 1000,
            copy_ms=average(selected, 'copy') / 1000,
            hand_ms=average(selected, 'hand') / 1000,
            other_ms=average(selected, 'total') / 1000 -
                     sum(average(selected, key) / 1000 for key in ('copy', 'hand', 'wait')),
            calls=average(selected, 'calls'), draws=average(selected, 'draws'),
            triangles=average(selected, 'tri'), surface_refills=average(selected, 'tex'),
            refill_kb=average(selected, 'kb'))
    if outs:
        selected = outs[-tail:] if tail else outs
        result['transport'] = dict(samples=len(selected),
                                   header_ms=average(selected, 'hdr') / 1000,
                                   body_ms=average(selected, 'body') / 1000,
                                   unpack_ms=average(selected, 'unpack') / 1000)

    restore_keys = ('shadow_prepare_us','shadow_compose_us','full_copy_us','full_copy_bytes','mutex_wait_us')
    restored = [row for row in (outs[-tail:] if tail else outs) if all(key in row for key in restore_keys)]
    if restored:
        from collections import Counter
        result['surface_restore'] = dict(
            samples=len(restored), shadow_prepare_ms=average(restored,'shadow_prepare_us')/1000,
            shadow_compose_ms=average(restored,'shadow_compose_us')/1000,
            full_copy_ms=average(restored,'full_copy_us')/1000,
            full_copy_bytes=average(restored,'full_copy_bytes'),
            mutex_wait_ms=average(restored,'mutex_wait_us')/1000,
            forced_full_reasons=dict(Counter(row.get('forced_full','unreported') for row in restored)),
            delta_missing_full_writeback=sum(row['path']=='delta' and row['full_copy_bytes']!=row['bytes'] for row in restored),
            timing_semantics='local wall-time subcosts: compose/full_copy are nested in unpack and total; do not sum them again or call their inverse FPS')
    first, last = metadata.get('start'), metadata.get('end')
    scope = 'measurement boundary snapshots'
    if not first or not last:
        samples_path = trial / 'samples.jsonl'
        samples = [json.loads(line) for line in samples_path.read_text().splitlines()
                   if line.strip()] if samples_path.is_file() else []
        end_t = metadata.get('end', {}).get('t')
        measuring = [row for row in samples if row.get('phase') == 'measure'
                     and (end_t is None or row['t'] <= end_t)]
        if not measuring:
            started_at = run.get('milestones', {}).get('measure-start')
            if started_at is not None:
                measuring = [row for row in samples if row['t'] >= started_at
                             and (end_t is None or row['t'] <= end_t)]
        if len(measuring) >= 2:
            first, last = measuring[0], measuring[-1]
            scope = 'legacy timed-sample interval (not the whole measurement window)'
        else:
            first, last = None, None
            warnings.append('missing exact boundaries/timed measure samples: throughput unavailable')
    if first and last:
        seconds = last['t'] - first['t']
        if not finite_number(seconds) or seconds <= 0:
            invalid.append('non-positive/non-finite boundary duration')
        if metadata.get('schema', 0) >= 3:
            budget = metadata.get('max_boundary_skew_seconds', 0.1)
            if not finite_number(budget) or not 0 < budget <= 0.1:
                invalid.append('invalid boundary skew budget')
                budget = 0.1
            invalid.extend(boundary_errors(first, 'start', budget))
            invalid.extend(boundary_errors(last, 'end', budget))
            if not invalid and last['t_before'] <= first['t_after']:
                invalid.append('overlapping start/end source brackets')
        if first.get('readback_resets', 0) or last.get('readback_resets', 0):
            invalid.append('readback producer reset')
        if first.get('protocol_version', 1) != last.get('protocol_version', 1):
            invalid.append('frame ring protocol changed during measurement')
        for boundary in (first, last):
            bracket = boundary.get('clock_bracket_seconds')
            if metadata.get('schema', 0) < 3 and bracket is not None and bracket > metadata.get('max_boundary_skew_seconds', 0.1):
                invalid.append('snapshot source skew exceeds boundary budget')
            elif metadata.get('schema', 0) >= 3 and bracket is not None and bracket > .1:
                warnings.append('whole snapshot exceeds 100ms; per-source intervals bound count timing, thermal is environmental only')
        for field in ('ring_seq', 'readbacks', 'cpu'):
            if first.get(field) is not None and last.get(field) is not None:
                if not finite_number(first[field]) or not finite_number(last[field]):
                    invalid.append(f'{field} non-finite')
                elif last[field] < first[field]:
                    invalid.append(f'{field} regressed')
        if not invalid:
            throughput = dict(scope=scope, start_t=first['t'], end_t=last['t'], seconds=seconds,
                              boundary_bracket_seconds=[first.get('clock_bracket_seconds'),
                                                        last.get('clock_bracket_seconds')])
            for field, label in (('ring_seq', 'published'), ('readbacks', 'readbacks')):
                if first.get(field) is None or last.get(field) is None:
                    warnings.append(f'{field} unavailable at a boundary; its throughput is unmeasured')
                    continue
                delta = last[field] - first[field]
                if delta < 0:
                    warnings.append(f'{field} regressed: experiment invalid')
                    continue
                source = 'ring' if field == 'ring_seq' else 'logs'
                source_seconds = (last['source_t'][source] - first['source_t'][source]
                                  if metadata.get('schema', 0) >= 3 else seconds)
                throughput[f'{label}_count'] = delta
                throughput[f'{label}_source_seconds'] = source_seconds
                throughput[f'{label}_per_second'] = delta / source_seconds
                if metadata.get('schema', 0) >= 3:
                    lower_seconds = last['t_before'] - first['t_after']
                    upper_seconds = last['t_after'] - first['t_before']
                    throughput[f'{label}_per_second_bracket'] = [delta / upper_seconds,
                                                               delta / lower_seconds]
            if first.get('cpu') is not None and last.get('cpu') is not None:
                cpu_span = (last['source_t']['cpu'] - first['source_t']['cpu']
                            if metadata.get('schema', 0) >= 3 else seconds)
                throughput['qemu_cores'] = (last['cpu'] - first['cpu']) / cpu_span
            result['throughput'] = throughput
    exact = bool(metadata.get('start') and metadata.get('end'))
    if metadata.get('schema', 0) >= 3:
        if not exact:
            invalid.append('new trial has incomplete measurement boundaries')
        else:
            for name in ('start', 'end'):
                boundary = metadata[name]
                for key in ('t_before', 't_after', 'source_t', 'clock_bracket_seconds'):
                    if key not in boundary:
                        invalid.append(f'{name} missing whole-source timing: {key}')
    if invalid:
        result['throughput'] = None
    result['validity'] = dict(status='invalid' if invalid else
                             ('bounded_measurement' if exact and metadata.get('schema', 0) >= 3
                              else 'legacy_unverified'), reasons=sorted(set(invalid)))
    if result['validity']['status'] == 'legacy_unverified':
        warnings.append('legacy identity/timing QoS not fully recorded; descriptive data only')
    result['content_fps'] = None
    result['content_fps_reason'] = (
        'publication/readback counts do not prove distinct visible contents; '
        'local diagnostic costs are not frame intervals')
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('trial', type=Path)
    parser.add_argument('--tail', type=int, default=60,
                        help='trailing diagnostic samples WITHIN the measurement window (0=all)')
    parser.add_argument('--json', action='store_true')
    args = parser.parse_args(argv)
    if args.tail < 0:
        parser.error('--tail must be non-negative')
    result = load_trial(args.trial, args.tail)
    if args.json:
        print(json.dumps(result, indent=2))
        return 2 if result['validity']['status'] == 'invalid' else 0
    print(f'{result["trial"]}: serial byte window '
          f'[{result["serial_start"]}, {result["serial_end"]}), '
          f'{result["frame_samples"]} unique diagnostic frame samples, '
          f'{result["readback_samples"]} unique readback records')
    costs = result['local_costs']
    if costs:
        print('  local GL scope: mean=%.1f ms median=%.1f ms, '
              'copy=%.1f ms hand=%.2f ms other=%.1f ms (not a frame interval)'
              % (costs['mean_ms'], costs['median_ms'], costs['copy_ms'],
                 costs['hand_ms'], costs['other_ms']))
        print('  workload/sample: %.1f GL calls, %.1f draws, %.1f triangles, '
              '%.2f surface refills (%.0f KB)'
              % (costs['calls'], costs['draws'], costs['triangles'],
                 costs['surface_refills'], costs['refill_kb']))
    restore = result.get('surface_restore')
    if restore:
        print('  surface restore: %d samples, shadow prepare %.3f ms compose %.3f ms, '
              'full-copy %.3f ms / %.0f bytes, mutex %.3f ms'
              % (restore['samples'],restore['shadow_prepare_ms'],restore['shadow_compose_ms'],
                 restore['full_copy_ms'],restore['full_copy_bytes'],restore['mutex_wait_ms']))
        print('  note:', restore['timing_semantics'])
    throughput = result['throughput']
    if throughput:
        print('  %s: %.3f s' % (throughput['scope'], throughput['seconds']))
        for key, label in [('published', 'ring publications'), ('readbacks', 'readbacks')]:
            if f'{key}_per_second' in throughput:
                print('  %s: %d / %.3f s = %.3f /s'
                      % (label, throughput[f'{key}_count'], throughput[f'{key}_source_seconds'],
                         throughput[f'{key}_per_second']))
        if 'qemu_cores' in throughput:
            print('  QEMU CPU use: %.3f cores' % throughput['qemu_cores'])
    print('  distinct visible-content FPS: unmeasured')
    print('  measurement validity:', result['validity']['status'])
    for reason in result['validity']['reasons']:
        print('  invalid:', reason)
    for warning in result['warnings']:
        print('  warning:', warning)
    return 2 if result['validity']['status'] == 'invalid' else 0


if __name__ == '__main__':
    sys.exit(main())
