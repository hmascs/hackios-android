#!/usr/bin/env python3
import importlib.util
from pathlib import Path
import unittest
spec=importlib.util.spec_from_file_location('harness',Path(__file__).with_name('glpass-desktop.py'))
harness=importlib.util.module_from_spec(spec);spec.loader.exec_module(harness)

class SocketPreflightTests(unittest.TestCase):
    def output(self,socket_bytes,prefix='/tmp/'):
        n=socket_bytes-len('/renderer.sock'.encode())-len(prefix.encode())
        return Path(prefix+'a'*n)
    def test_107_is_accepted_108_is_rejected_before_mkdir(self):
        accepted,rejected=self.output(107),self.output(108)
        self.assertEqual(len(str(accepted/'renderer.sock').encode()),107)
        harness.validate_socket_paths(accepted)
        with self.assertRaisesRegex(ValueError,'choose shorter output'):
            harness.validate_socket_paths(rejected)
        self.assertFalse(accepted.exists());self.assertFalse(rejected.exists())
    def test_utf8_bytes_not_character_count(self):
        prefix='/tmp/测量🙂-'
        accepted,rejected=self.output(107,prefix),self.output(108,prefix)
        self.assertLess(len(str(rejected/'renderer.sock')),108)
        harness.validate_socket_paths(accepted)
        with self.assertRaises(ValueError):harness.validate_socket_paths(rejected)

if __name__=='__main__':unittest.main()
