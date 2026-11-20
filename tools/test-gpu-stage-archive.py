#!/usr/bin/env python3
import io
from pathlib import Path
import runpy
import tarfile
import tempfile
import unittest

MODULE = runpy.run_path(str(Path(__file__).resolve().parents[1] /
    'app/src/gpuTest/assets/gpu-stage-archive.py'))
archive, restore = (MODULE[k] for k in ('archive', 'restore'))


class StageArchiveTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.snapshot = self.root / 'test.tar'
        self.output = self.root / 'state'

    def pack_entries(self, entries):
        with tarfile.open(self.snapshot, 'w', format=tarfile.USTAR_FORMAT) as tar:
            for name, kind, value in entries:
                info = tarfile.TarInfo(name)
                info.type = kind
                if kind == tarfile.REGTYPE:
                    info.size = len(value)
                    tar.addfile(info, io.BytesIO(value))
                else:
                    info.linkname = value
                    tar.addfile(info)

    def test_round_trip(self):
        source = self.root / 'source'
        (source / 'etc').mkdir(parents=True)
        data = source / 'etc' / 'rc.jobs'
        data.write_bytes(b'ordinary guest file\n')
        data.chmod(0o755)
        (source / 'link').symlink_to('/guest/path')
        archive(source, self.snapshot)
        restore(self.snapshot, self.output)
        self.assertEqual((self.output / 'etc' / 'rc.jobs').read_bytes(), data.read_bytes())
        self.assertEqual((self.output / 'etc' / 'rc.jobs').stat().st_mode & 0o777, 0o755)
        self.assertEqual((self.output / 'link').readlink(), Path('/guest/path'))

    def test_traversal_is_rejected(self):
        self.pack_entries([('../outside', tarfile.REGTYPE, b'modified')])
        with self.assertRaises(ValueError):
            restore(self.snapshot, self.output)
        self.assertFalse((self.root / 'outside').exists())

    def test_link_parent_is_rejected(self):
        self.pack_entries([('link', tarfile.SYMTYPE, '..'), ('link/outside', tarfile.REGTYPE, b'modified')])
        with self.assertRaises(ValueError):
            restore(self.snapshot, self.output)
        self.assertFalse((self.root / 'outside').exists())

    def test_hard_link_is_rejected(self):
        self.pack_entries([('link', tarfile.LNKTYPE, '/etc/passwd')])
        with self.assertRaises(ValueError):
            restore(self.snapshot, self.output)

    def test_inaccessible_directory_is_rejected(self):
        self.pack_entries([('locked', tarfile.DIRTYPE, '')])
        with self.assertRaises(ValueError):
            restore(self.snapshot, self.output)
        self.assertFalse((self.output / 'locked').exists())

    def test_duplicate_entries_are_rejected(self):
        self.pack_entries([('entry', tarfile.REGTYPE, b'1'), ('entry', tarfile.REGTYPE, b'2')])
        with self.assertRaises(ValueError):
            restore(self.snapshot, self.output)


if __name__ == '__main__':
    unittest.main()
