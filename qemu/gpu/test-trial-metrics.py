#!/usr/bin/env python3
"""Regression fixtures for the measurement mistakes found in older trials."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

from trial_metrics import LogCursor, ReadbackMetrics, SerialMetrics

spec = importlib.util.spec_from_file_location('frame_stats', Path(__file__).with_name('frame-stats.py'))
frame_stats = importlib.util.module_from_spec(spec)
spec.loader.exec_module(frame_stats)


def frame(identifier, usec=100000):
    return (f'redwood-gl: frame {identifier}: 44 GL calls, 6 draws, 36 triangles, 0 clears; '
            f'0 textures refilled from IOSurfaces (0 KB); 1 picture copied out, '
            f'0 drawn in place; {usec} us (2000 handing on, 0 waiting, 30000 copying out)\r\r\n').encode()


def readback(identifier):
    return f'EAGL readback: context=1 read={identifier} size=1125x2436\n'.encode()


class MeasurementTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def test_loader_replay_and_identical_ids_are_not_counted_twice(self):
        (self.path / 'serial.log').write_bytes(frame(1) + frame(1) + frame(10) +
                                               b'== KERNEL LOG ==\n' + frame(1) + frame(10))
        cursor = LogCursor(self.path / 'serial.log')
        metrics = SerialMetrics()
        metrics.consume(cursor.lines(final=True))
        self.assertEqual([row['id'] for row in metrics.frames], [1, 10])
        self.assertEqual(metrics.duplicates['frame'], 1)
        self.assertIsNotNone(metrics.replay_at)

    def test_partial_appends_keep_true_byte_offsets(self):
        path = self.path / 'serial.log'
        message = frame(10)
        path.write_bytes(message[:45])
        cursor = LogCursor(path)
        self.assertEqual(cursor.lines(), [])
        with path.open('ab') as stream:
            stream.write(message[45:])
        self.assertEqual(cursor.lines(), [(0, len(message), message.replace(b'\r', b'').rstrip(b'\n'))])
        self.assertEqual(cursor.lines(), [])

    def test_conflicting_ids_are_reported(self):
        (self.path / 'serial.log').write_bytes(frame(10) + frame(10, 222000))
        metrics = SerialMetrics()
        metrics.consume(LogCursor(self.path / 'serial.log').lines(final=True))
        self.assertEqual(len(metrics.conflicts), 1)
        self.assertEqual(len(metrics.frames), 1)

    def test_full_measurement_window_and_boundary_throughput(self):
        before, within, after = frame(1), frame(10), frame(20)
        (self.path / 'serial.log').write_bytes(before + within + after)
        (self.path / 'measure.json').write_text(json.dumps(dict(
            start=dict(serial_offset=len(before), t=100.0, ring_seq=10, readbacks=10, cpu=200),
            end=dict(serial_offset=len(before + within), t=110.0, ring_seq=30, readbacks=30, cpu=215))))
        result = frame_stats.load_trial(self.path)
        self.assertEqual(result['frame_samples'], 1)
        self.assertEqual(result['local_costs']['first_id'], 10)
        self.assertEqual(result['local_costs']['mean_ms'], 100)
        self.assertEqual(result['throughput']['published_per_second'], 2)
        self.assertEqual(result['throughput']['qemu_cores'], 1.5)
        self.assertIsNone(result['content_fps'])
        self.assertNotIn('fps', result['local_costs'])

    def test_window_cutting_a_line_excludes_that_record(self):
        record = frame(10)
        (self.path / 'serial.log').write_bytes(record + frame(20))
        (self.path / 'measure-start.txt').write_text('5\n')
        result = frame_stats.load_trial(self.path)
        self.assertEqual(result['frame_samples'], 1)
        self.assertEqual(result['local_costs']['first_id'], 20)

    def test_old_samples_need_no_nonexistent_desktop_field(self):
        (self.path / 'serial.log').write_bytes(frame(10))
        (self.path / 'measure-start.txt').write_text('0\n')
        (self.path / 'run.json').write_text(json.dumps(dict(milestones={'measure-start': 400})))
        rows = [dict(t=390, ring_seq=10, readbacks=10, cpu=20),
                dict(t=420, ring_seq=20, readbacks=20, cpu=30),
                dict(t=450, ring_seq=65, readbacks=65, cpu=75)]
        (self.path / 'samples.jsonl').write_text(''.join(json.dumps(row) + '\n' for row in rows))
        result = frame_stats.load_trial(self.path)
        self.assertEqual(result['throughput']['published_count'], 45)
        self.assertEqual(result['throughput']['published_per_second'], 1.5)
        self.assertIn('legacy', result['throughput']['scope'])

    def test_missing_wall_window_never_becomes_fps(self):
        (self.path / 'serial.log').write_bytes(frame(1))
        result = frame_stats.load_trial(self.path)
        self.assertIsNone(result['throughput'])
        self.assertIsNone(result['content_fps'])

    def test_incremental_readbacks_survive_more_than_tail_size(self):
        path = self.path / 'worker.log'
        path.write_bytes(readback(1))
        cursor = LogCursor(path)
        metrics = ReadbackMetrics()
        metrics.consume(cursor.lines())
        with path.open('ab') as stream:
            stream.write(b'x' * (1024 * 1024 + 8) + b'\n' + readback(2))
        metrics.consume(cursor.lines())
        self.assertEqual(metrics.count, 2)
        metrics.consume(cursor.lines())
        self.assertEqual(metrics.count, 2)

    def test_failed_reset_regressed_or_skewed_run_has_no_throughput(self):
        message = frame(1)
        (self.path / 'serial.log').write_bytes(message)
        baseline = dict(start=dict(t=0, serial_offset=0, ring_seq=0, readbacks=0, cpu=0),
                        end=dict(t=10, serial_offset=len(message), ring_seq=20, readbacks=20, cpu=10))
        for change in ('failure', 'reset', 'regressed', 'skew', 'conflict'):
            with self.subTest(change=change):
                meta = json.loads(json.dumps(baseline))
                (self.path / 'serial.log').write_bytes(message)
                if change == 'failure': meta['failure'] = 'RuntimeError: worker exited'
                if change == 'reset': meta['end']['readback_resets'] = 1
                if change == 'regressed': meta['end']['ring_seq'] = -1
                if change == 'skew': meta['end']['clock_bracket_seconds'] = .101
                if change == 'conflict': (self.path / 'serial.log').write_bytes(message + frame(1, 200000))
                (self.path / 'measure.json').write_text(json.dumps(meta))
                result = frame_stats.load_trial(self.path)
                self.assertIsNone(result['throughput'])
                self.assertEqual(result['validity']['status'], 'invalid')

    def test_new_schema_missing_end_cannot_fall_back_to_success(self):
        (self.path / 'serial.log').write_bytes(frame(1))
        (self.path / 'measure.json').write_text(json.dumps(dict(
            schema=3, start=dict(t=100, serial_offset=0))))
        (self.path / 'samples.jsonl').write_text('\n'.join(json.dumps(dict(
            phase='measure', t=t, ring_seq=t, readbacks=t)) for t in (101, 111)))
        result = frame_stats.load_trial(self.path)
        self.assertIsNone(result['throughput'])
        self.assertEqual(result['validity']['status'], 'invalid')

    def test_exact_source_timing_is_required_in_new_schema(self):
        (self.path / 'serial.log').write_bytes(frame(1))
        def boundary(t, count):
            return dict(t=t, t_before=t-.001, t_after=t+.001, source_t=dict(
                logs=t, ring=t, cpu=t, thermal=t), clock_bracket_seconds=.002,
                serial_offset=0, ring_seq=count, readbacks=count, cpu=count)
        meta = dict(schema=3, start=boundary(10, 0), end=boundary(20, 20))
        required = ('qemu', 'payload', 'root_apfs', 'dylib', 'worker_launcher',
                    'firmware', 'loader', 'vars_initial', 'ram_system', 'harness', 'metrics_module')
        identity = dict(inputs={name: dict(sha256='0'*64, bytes=1) for name in required},
                        runtime={name: dict(mapped_files={'/mock/test.so': {}}, unreadable=[])
                                 for name in ('qemu', 'worker')})
        (self.path / 'identity.json').write_text(json.dumps(identity))
        (self.path / 'measure.json').write_text(json.dumps(meta))
        valid = frame_stats.load_trial(self.path)
        self.assertEqual(valid['validity']['status'], 'bounded_measurement')
        self.assertEqual(valid['throughput']['published_source_seconds'], 10)
        lo, hi = valid['throughput']['published_per_second_bracket']
        self.assertLess(lo, 2)
        self.assertGreater(hi, 2)
        for case in ('nan', 'outside', 'false_bracket'):
            malformed = json.loads(json.dumps(meta))
            if case == 'nan': malformed['end']['t'] = float('nan')
            if case == 'outside': malformed['end']['source_t']['ring'] = 1000
            if case == 'false_bracket': malformed['end']['clock_bracket_seconds'] = .0001
            (self.path / 'measure.json').write_text(json.dumps(malformed))
            with self.subTest(case=case):
                self.assertIsNone(frame_stats.load_trial(self.path)['throughput'])
        del meta['end']['source_t']
        (self.path / 'measure.json').write_text(json.dumps(meta))
        self.assertIsNone(frame_stats.load_trial(self.path)['throughput'])

    def test_unterminated_eof_record_is_not_a_complete_sample(self):
        (self.path / 'serial.log').write_bytes(frame(1).rstrip(b'\r\n'))
        result = frame_stats.load_trial(self.path)
        self.assertEqual(result['frame_samples'], 0)
        self.assertTrue(any('unterminated' in text for text in result['warnings']))

    def test_new_trial_without_artifact_identity_is_invalid(self):
        (self.path / 'serial.log').write_bytes(frame(1))
        (self.path / 'measure.json').write_text(json.dumps(dict(schema=3,
            start=dict(t=10, serial_offset=0, ring_seq=0, readbacks=0),
            end=dict(t=20, serial_offset=0, ring_seq=10, readbacks=10))))
        result = frame_stats.load_trial(self.path)
        self.assertIsNone(result['throughput'])
        self.assertTrue(any('artifact identity' in text for text in result['validity']['reasons']))

    def test_writing_v2_boundary_is_unavailable_not_a_counter_reset(self):
        (self.path / 'serial.log').write_bytes(frame(1))
        (self.path / 'measure.json').write_text(json.dumps(dict(
            start=dict(t=10, serial_offset=0, ring_seq=7, readbacks=7, protocol_version=2),
            end=dict(t=20, serial_offset=0, ring_seq=None, ring_available=False,
                     readbacks=20, protocol_version=2))))
        result = frame_stats.load_trial(self.path)
        self.assertEqual(result['throughput']['readbacks_per_second'], 1.3)
        self.assertNotIn('published_per_second', result['throughput'])
        self.assertNotEqual(result['validity']['status'], 'invalid')

    def test_fullcopy_tail_fields_are_measured_without_double_counting(self):
        prefix=(b'hackios-gl: out 1 v5 delta bytes=10962000 stride=4608 row=4500 inv=0 '
                b'gap=300000 malloc=0 send=100 hdr=200 body=300 unpack=600 total=1200')
        tail=(b' shadow_prepare_us=10 shadow_compose_us=200 full_copy_us=300 '
              b'full_copy_bytes=10962000 mutex_wait_us=50 forced_full=unproven_base\n')
        (self.path/'serial.log').write_bytes(prefix+tail)
        result=frame_stats.load_trial(self.path)
        self.assertEqual(result['transport']['unpack_ms'],.6)
        self.assertEqual(result['surface_restore']['full_copy_ms'],.3)
        self.assertEqual(result['surface_restore']['shadow_compose_ms'],.2)
        self.assertEqual(result['surface_restore']['delta_missing_full_writeback'],0)
        self.assertIn('nested',result['surface_restore']['timing_semantics'])
        self.assertIsNone(result['content_fps'])

    def test_same_out_id_with_changed_fullcopy_tail_is_conflict(self):
        base=(b'hackios-gl: out 1 v5 delta bytes=8 stride=4 row=4 inv=0 '
              b'gap=1 malloc=0 send=1 hdr=1 body=1 unpack=2 total=6')
        (self.path/'serial.log').write_bytes(base+b' full_copy_us=1\n'+base+b' full_copy_us=2\n')
        result=frame_stats.load_trial(self.path)
        self.assertEqual(len(result['conflicts']),1)
        self.assertNotIn('surface_restore',result)


if __name__ == '__main__':
    unittest.main()
