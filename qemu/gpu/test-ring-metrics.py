#!/usr/bin/env python3
import io
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch

from ring_metrics import create_ring, ring_snapshot


class RingMetadataTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / 'frames.ring'

    def tearDown(self):
        self.temp.cleanup()

    def publish(self, count, guard=None):
        with self.path.open('r+b') as stream:
            stream.seek(24)
            stream.write(struct.pack('<QQQ', count, 12345, count*2 if guard is None else guard))

    def test_old_v1_default_count_remains_count(self):
        create_ring(self.path)
        self.publish(7)
        result = ring_snapshot(self.path)
        self.assertEqual(result['protocol_version'], 1)
        self.assertTrue(result['ring_available'])
        self.assertEqual(result['ring_seq'], 7)

    def test_v2_count_does_not_double_with_guard(self):
        create_ring(self.path, 2)
        self.publish(7)
        result = ring_snapshot(self.path, 2)
        self.assertEqual((result['ring_seq'], result['ring_guard']), (7, 14))
        self.assertTrue(result['ring_available'])

    def test_zero_count_is_valid_only_with_zero_even_guard(self):
        create_ring(self.path, 2)
        result = ring_snapshot(self.path, 2)
        self.assertTrue(result['ring_available'])
        self.assertEqual(result['ring_seq'], 0)

    def test_odd_guard_is_unavailable_not_zero(self):
        create_ring(self.path, 2)
        self.publish(7, 15)
        result = ring_snapshot(self.path, 2)
        self.assertFalse(result['ring_available'])
        self.assertIsNone(result['ring_seq'])

    def test_even_guard_count_mismatch_is_unavailable(self):
        create_ring(self.path, 2)
        self.publish(7, 16)
        self.assertEqual(ring_snapshot(self.path, 2)['ring_status'], 'guard_count_mismatch')

    def test_mixed_version_is_rejected_both_ways(self):
        for created, expected in ((1, 2), (2, 1)):
            with self.subTest(created=created):
                path = self.path.with_name(f'ring-{created}')
                create_ring(path, created)
                self.assertEqual(ring_snapshot(path, expected)['ring_status'], 'protocol_mismatch')

    def test_second_guard_read_detects_concurrent_begin(self):
        create_ring(self.path, 2)
        self.publish(7)
        data = self.path.read_bytes()[:64]
        class ConcurrentFile(io.BytesIO):
            def read(self, size=-1):
                if self.tell() == 40:
                    return struct.pack('<Q', 15)
                return super().read(size)
        with patch.object(Path, 'open', return_value=ConcurrentFile(data)):
            result = ring_snapshot(self.path, 2)
        self.assertFalse(result['ring_available'])
        self.assertIsNone(result['ring_seq'])

    def test_corrupt_geometry_is_rejected(self):
        create_ring(self.path, 2)
        with self.path.open('r+b') as stream:
            stream.seek(8)
            stream.write(struct.pack('<I', 750))
        self.assertEqual(ring_snapshot(self.path, 2)['ring_status'], 'geometry_or_format_mismatch')


if __name__ == '__main__':
    unittest.main()
