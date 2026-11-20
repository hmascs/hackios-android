#!/usr/bin/env python3
"""Check counter uncertainty and full-address handling independently of QEMU."""
import importlib.util
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('instruction_stats',
                                            Path(__file__).with_name('instruction-stats.py'))
stats = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stats)


def counter(cpu=0, **changes):
    row = dict(cpu=cpu, mode=2, user_instructions=100, kernel_instructions=20,
               user_blocks=10, kernel_blocks=2, user_uncertain=0, kernel_uncertain=0,
               corrected_tail=0, corrections=0, unbounded_exits=0, translations=10)
    row.update(changes)
    return row


def snapshot(*rows):
    return '\n'.join('HIOS_PROFILE ' + json.dumps(row) for row in rows)


class CounterTests(unittest.TestCase):
    def test_bounded_instruction_delta_and_full_kernel_va(self):
        beginning = snapshot(counter())
        ending = snapshot(counter(user_instructions=300, kernel_instructions=50,
                                  user_blocks=110, kernel_blocks=5, user_uncertain=1,
                                  kernel_uncertain=4, corrected_tail=8, corrections=2,
                                  unbounded_exits=2, translations=12))
        ending += '\nHIOS_BUCKET ' + json.dumps(dict(cpu=0, address='0xfffffff007400000',
                                                     instructions=30, uncertain=4))
        result = stats.difference(beginning, ending)
        self.assertEqual((result['lower_instructions'], result['credited_instructions']),
                         (225, 230))
        self.assertEqual(result['top_buckets'][0]['address'], '0xfffffff007400000')
        self.assertEqual(result['translations'], 2)

    def test_cpus_aggregate_once_and_regressions_fail(self):
        beginning = snapshot(counter(0), counter(1))
        ending = snapshot(counter(0, user_instructions=105),
                          counter(1, user_instructions=110))
        result = stats.difference(beginning, ending)
        self.assertEqual(result['credited_instructions'], 15)
        with self.assertRaisesRegex(ValueError, 'regressed'):
            stats.difference(ending, beginning)

    def test_missing_paused_counters_and_duplicate_cpu_fail(self):
        with self.assertRaisesRegex(ValueError, 'no paused'):
            stats.parse_snapshot('HIOS_PROFILE_UNAVAILABLE pause-vcpus-first\n')
        with self.assertRaisesRegex(ValueError, 'duplicate vCPU'):
            stats.parse_snapshot(snapshot(counter(), counter()))


if __name__ == '__main__':
    unittest.main()
