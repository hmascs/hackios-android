#!/usr/bin/env python3
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('vcpu', Path(__file__).with_name('measure-vcpu-scheduling.py'))
vcpu = importlib.util.module_from_spec(spec)
spec.loader.exec_module(vcpu)


class Placement(unittest.TestCase):
    def setUp(self):
        self.cpus = set(range(8))
        self.rank = lambda n: 4320000 if n >= 6 else 3532800

    def test_six_vcpus_reserve_two_distinct_host_cpus_for_housekeeping(self):
        ids, housekeeping = vcpu.isolation_plan(self.cpus, list(range(20, 26)), 2, self.rank)
        self.assertEqual(list(ids.values()), [6, 7, 0, 1, 2, 3])
        self.assertEqual(housekeeping, {4, 5})
        self.assertFalse(housekeeping.intersection(ids.values()))

    def test_does_not_claim_affinity_if_overcommitted_or_missing_frequency(self):
        with self.assertRaises(ValueError):
            vcpu.isolation_plan(self.cpus, list(range(8)), 2, self.rank)
        with self.assertRaises(ValueError):
            vcpu.isolation_plan(self.cpus, [20, 21], 2, lambda n: None)

    def test_restores_threads_if_pinning_fails_midway(self):
        threads = [100, 101, 102]
        masks = {tid: set(self.cpus) for tid in threads}

        def pin(tid, cpus):
            if tid == 102 and cpus != self.cpus:
                raise PermissionError('cpuset changed')
            masks[tid] = set(cpus)

        with patch.object(vcpu.Path, 'iterdir', return_value=[Path(str(t)) for t in threads]), \
             patch.object(vcpu, 'task_stat', return_value=(0, 0, 123)), \
             patch.object(vcpu.os, 'sched_getaffinity', side_effect=lambda tid: set(masks[tid])), \
             patch.object(vcpu.os, 'sched_setaffinity', side_effect=pin), \
             patch.object(vcpu, 'isolation_plan', side_effect=lambda c, t, s: ({101: 6, 102: 7}, set(range(6)))):
            with self.assertRaises(PermissionError):
                vcpu.isolate_for_measurement(100, [101, 102], 1, .1)
        self.assertEqual(masks, {tid: self.cpus for tid in threads})


if __name__ == '__main__':
    unittest.main()
