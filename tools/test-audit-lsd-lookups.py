#!/usr/bin/env python3
import runpy
from pathlib import Path
import unittest

AUDIT = runpy.run_path(str(Path(__file__).with_name('audit-lsd-lookups.py')))['audit']


def lookup(suffix, *, name='com.apple.analyticsd', pid=24, error=3, requester='lsd'):
    return (f'com.apple.xpc.launchd|2026-11-18 18:11:15.{suffix:06d} (user/501) <Warning>: '
            f'failed lookup: name = {name}, flags = 0x8, requestor = {requester}[{pid}], error = {error}: failure\n')


class AuditTests(unittest.TestCase):
    def test_only_distinct_missing_records_count(self):
        warning = 'process lsd[24] denied attempt to escape CPU monitor (missing required entitlement).\n'
        result = AUDIT([warning, lookup(1), lookup(1), lookup(2, pid=30), lookup(3, error=5),
                        lookup(4, requester='Preferences'), warning])
        self.assertEqual(result['repeated_missing'], ['com.apple.analyticsd'])
        self.assertEqual(result['lookups'][0]['count'], 2)
        self.assertEqual(result['lookups'][0]['pids'], [24, 30])
        self.assertEqual(result['duplicate_lookup_records'], 1)
        self.assertEqual(result['cpu_monitor_before_first_lookup'], 1)
        self.assertEqual(result['cpu_monitor_messages'], 2)

    def test_app_export_only_counts_raw_serial(self):
        result = AUDIT([lookup(1), '===== 串口 serial.log  ·  serial.log =====\n', lookup(2),
                        '===== QEMU 输出 qemu.log  ·  qemu.log =====\n', lookup(3)], exported=True)
        self.assertEqual(result['lookups'][0]['count'], 1)
        self.assertTrue(result['lookups'][0]['first'].endswith('.000002'))
        self.assertEqual(result['repeated_missing'], [])

    def test_malformed_and_missing_are_not_candidates(self):
        result = AUDIT(['process lsd[24] denied attempt to escape CPU monitor\n',
                        'failed lookup: name = test, requestor = lsd[24], truncated\n'])
        self.assertEqual(result['lookups'], [])
        self.assertEqual(result['incomplete_lookup_records'], 1)
        self.assertIsNone(result['cpu_monitor_before_first_lookup'])
        with self.assertRaises(ValueError):
            AUDIT([lookup(1)], exported=True)


if __name__ == '__main__':
    unittest.main()
