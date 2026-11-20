#!/usr/bin/env python3
import hashlib
import os
from pathlib import Path
import tempfile
import unittest
from mapped_identity import file_key, mapping_identity, executable_mappings

class MappingTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.path=Path(self.temp.name)/'lib fixture.so';self.path.write_bytes(b'fixture')
        key=file_key(self.path.stat())
        self.record=dict(kernel_path=str(self.path),device=list(key[:2]),inode=key[2],range='0-1')
    def tearDown(self): self.temp.cleanup()
    def test_exact_device_inode_file_hash(self):
        identity,_=mapping_identity(self.record,99999999)
        self.assertEqual(identity['sha256'],hashlib.sha256(b'fixture').hexdigest())
        self.assertEqual(identity['matched_by'],'kernel_path')
    def test_wrong_inode_rejected_even_same_name_and_bytes(self):
        row=dict(self.record,inode=self.record['inode']+1)
        identity,_=mapping_identity(row,99999999)
        self.assertIsNone(identity)
    def test_verified_prefix_translation_still_checks_inode(self):
        prefix='/unmapped/proot-root'
        row=dict(self.record,kernel_path=prefix+str(self.path))
        identity,_=mapping_identity(row,99999999,prefix)
        self.assertEqual(identity['matched_by'],'verified_proot_prefix')
        dev=self.record['device']
        text=f'1-2 r-xp 0000 {dev[0]:x}:{dev[1]:x} {self.record["inode"]} {row["kernel_path"]}\n'
        parsed=executable_mappings(text)[0]
        self.assertEqual(parsed['kernel_path'],row['kernel_path'])
        self.assertEqual(parsed['inode'],row['inode'])

if __name__=='__main__': unittest.main()
