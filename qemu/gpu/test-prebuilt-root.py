#!/usr/bin/env python3
"""Offline provenance fixtures: the requested APFS must be the payload ramdisk."""
import importlib.util
from pathlib import Path
import struct
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('glpass', HERE / 'glpass-desktop.py')
harness = importlib.util.module_from_spec(spec)
spec.loader.exec_module(harness)


def xnup_entry(name, data):
    return struct.pack('<16sII', name, len(data), 0) + data + bytes(-len(data) % 16)


class PrebuiltRootTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.output = self.root / 'output'
        self.output.mkdir()
        self.prebuilt = self.root / 'prebuilt.apfs'
        self.prebuilt.write_bytes(b'x' * 8192)
        self.args = SimpleNamespace(root_apfs=self.prebuilt, rootfs=self.root/'missing-rootfs',
                                    mkapfs=self.root/'missing-packer', dylib=self.root/'missing-dylib')

    def tearDown(self):
        self.temp.cleanup()

    def test_prebuilt_skips_packer_rootfs_and_dylib_injection(self):
        with patch.object(harness.subprocess, 'run') as run:
            result = harness.prepare_root(self.args, self.output)
        run.assert_not_called()
        self.assertFalse((self.output/'rootfs').exists())
        self.assertEqual(result['mode'], 'prebuilt')
        self.assertEqual(result['source']['sha256'], result['actual']['sha256'])
        self.assertEqual((self.output/'root.apfs').read_bytes(), self.prebuilt.read_bytes())
        self.assertFalse(self.args.mkapfs.exists())

    def test_prebuilt_rejects_bad_sizes_directories_and_symlinks(self):
        for size in (0, 4097, 48*1024*1024+4096):
            with self.subTest(size=size):
                with self.prebuilt.open('wb') as stream:
                    stream.truncate(size)
                with self.assertRaises(ValueError):
                    harness.prepare_root(self.args, self.output)
        self.args.root_apfs = self.output
        with self.assertRaises(ValueError):
            harness.prepare_root(self.args, self.output)
        link = self.root/'link.apfs'
        link.symlink_to(self.prebuilt)
        self.args.root_apfs = link
        with self.assertRaises(ValueError):
            harness.prepare_root(self.args, self.output)

    def test_default_rebuild_keeps_existing_dylib_injection_behavior(self):
        source = self.root/'rootfs'
        (source/'lib').mkdir(parents=True)
        (source/'lib/libredwood-gl.dylib').write_bytes(b'old')
        dylib = self.root/'new.dylib'
        dylib.write_bytes(b'new')
        packer = self.root/'mkapfs.py'
        packer.write_text('# fixture')
        self.args = SimpleNamespace(root_apfs=None, rootfs=source, mkapfs=packer, dylib=dylib)
        def pack(command, cwd, check):
            self.assertTrue(check)
            self.assertEqual((cwd/'rootfs/lib/libredwood-gl.dylib').read_bytes(), b'new')
            (cwd/'root.apfs').write_bytes(b'p' * 8192)
        with patch.object(harness.subprocess, 'run', side_effect=pack) as run:
            result = harness.prepare_root(self.args, self.output)
        run.assert_called_once()
        self.assertEqual(result['mode'], 'rebuilt')
        self.assertEqual((source/'lib/libredwood-gl.dylib').read_bytes(), b'old')

    def test_repack_payload_ramdisk_matches_exact_prebuilt_hash(self):
        result = harness.prepare_root(self.args, self.output)
        body = (xnup_entry(b'kernel', b'kernel-fixture') +
                xnup_entry(b'ramdisk', b'old-root') +
                xnup_entry(b'cmdline', b'cpus=2 gl=1 fbscale=3\0') + bytes(24))
        source = self.root/'source.bin'
        source.write_bytes(struct.pack('<4sI', b'XNUP', len(body)) + body)
        dest = self.output/'payload.bin'
        harness.repack(source, dest, self.output/'root.apfs')
        self.assertEqual(harness.payload_ramdisk_identity(dest)['sha256'], result['source']['sha256'])
        self.assertEqual(harness.payload_ramdisk_identity(dest)['bytes'], 8192)


if __name__ == '__main__':
    unittest.main()
