#!/usr/bin/env python3
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import struct
import tempfile
import unittest
from trial_metrics import HASH_RECEIPT,LogCursor,SerialMetrics

HERE=Path(__file__).resolve().parent

def module(name,filename):
    spec=importlib.util.spec_from_file_location(name,HERE/filename)
    value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value

harness=module('harness','glpass-desktop.py')
hashes=module('hashes','hash-stats.py')
instructions=module('instructions','instruction-stats.py')

def entry(name,data):return struct.pack('<16sII',name,len(data),0)+data+bytes(-len(data)%16)

def hashline(frame,us):
    return f'redwood-gl: hash f {frame} hash_calls=2 hash_bytes=100 hash_total_us={us} hash_attempts=3 hash_changed=1 image_count=2 scope=since-frame-end incl-setup=1\n'.encode()


class CpuProfileTests(unittest.TestCase):
    def setUp(self):self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)
    def tearDown(self):self.temp.cleanup()
    def serial(self,data):
        path=self.root/'serial.log';path.write_bytes(data)
        cursor=LogCursor(path);serial=SerialMetrics();serial.consume(cursor.lines());return serial

    def test_explicit_process_identity_has_pid_reuse_guard(self):
        row=harness.process_identity(os.getpid())
        self.assertEqual(row['pid'],os.getpid());self.assertGreater(row['starttime_ticks'],0)

    def test_profile_boot_flags_are_exact_and_do_not_modify_sources(self):
        root=self.root/'root.apfs';root.write_bytes(b'a'*4096)
        command=b'cpus=2 glhash=0 glframes=0 touch_lat=1 other=keep\0'
        body=entry(b'ramdisk',b'old')+entry(b'cmdline',command)+bytes(24)
        source=self.root/'input.bin';original=struct.pack('<4sI',b'XNUP',len(body))+body;source.write_bytes(original)
        output=self.root/'output.bin';harness.repack(source,output,root,guest_hash_profile=True)
        raw=output.read_bytes();offset=8;cmd=None
        while offset+24<=len(raw):
            name,size,_=struct.unpack_from('<16sII',raw,offset);offset+=24
            if name.rstrip(b'\0')==b'cmdline':cmd=raw[offset:offset+size].rstrip(b'\0').split()
            offset+=(size+15)//16*16
        self.assertEqual([x for x in cmd if x.startswith(b'glhash=')],[b'glhash=1'])
        self.assertEqual([x for x in cmd if x.startswith(b'glframes=')],[b'glframes=1'])
        self.assertEqual([x for x in cmd if x.startswith(b'touch_lat=')],[b'touch_lat=2'])
        self.assertIn(b'other=keep',cmd);self.assertEqual(source.read_bytes(),original)

    def test_session_override_is_exact_deduplicated_and_receipted(self):
        root=self.root/'session-root.apfs';root.write_bytes(b'a'*4096)
        command=b'cpus=2 loader_return_after=1200 loader_return_after=900 noloader_return_after=7\0'
        body=entry(b'ramdisk',b'old')+entry(b'cmdline',command)+bytes(24)
        source=self.root/'session-input.bin';original=struct.pack('<4sI',b'XNUP',len(body))+body;source.write_bytes(original)
        for value in (None,2400,7200):
            output=self.root/f'session-{value}.bin'
            receipt=harness.repack(source,output,root,session_seconds=value)
            tokens=receipt['cmdlines'][0]['output'].split()
            managed=[token for token in tokens if token.startswith('loader_return_after=')]
            self.assertEqual(managed,['loader_return_after=1200','loader_return_after=900'] if value is None else [f'loader_return_after={value}'])
            self.assertIn('noloader_return_after=7',tokens)
            self.assertEqual(source.read_bytes(),original)
            self.assertEqual(receipt['source_payload_sha256'],__import__('hashlib').sha256(original).hexdigest())
            self.assertEqual(receipt['output_payload_sha256'],__import__('hashlib').sha256(output.read_bytes()).hexdigest())

    def test_bad_session_values_tokens_and_budget_fail_before_output(self):
        root=self.root/'bad-root.apfs';root.write_bytes(b'a'*4096)
        for i,token in enumerate((b'loader_return_after=abc',b'loader_return_after=-1',b'loader_return_after',b'loader_return_after=4294967296')):
            body=entry(b'ramdisk',b'old')+entry(b'cmdline',b'cpus=2 '+token+b'\0')+bytes(24)
            source=self.root/f'bad-{i}.bin';source.write_bytes(struct.pack('<4sI',b'XNUP',len(body))+body)
            output=self.root/f'bad-out-{i}.bin'
            with self.assertRaises(ValueError):harness.repack(source,output,root,session_seconds=2400)
            self.assertFalse(output.exists())
        for value in (299,7201,True,2400.0):
            with self.assertRaises(ValueError):harness.repack(source,self.root/'invalid-budget.bin',root,session_seconds=value)
        output=self.root/'budget-out'
        argv=['/missing']*7+[str(output),'--session-seconds','7200','--boot-timeout','7200','--measure-seconds','1','--settle-seconds','0']
        with contextlib.redirect_stderr(io.StringIO()),self.assertRaises(SystemExit):harness.main(argv)
        self.assertFalse(output.exists())

    def test_mode_zero_counter_conflict_rejected_before_output_creation(self):
        output=self.root/'out'
        argv=['/missing']*7+[str(output),'--profile-mode','0','--instruction-profile']
        with contextlib.redirect_stderr(io.StringIO()),self.assertRaises(SystemExit) as caught:
            harness.main(argv)
        self.assertEqual(caught.exception.code,2);self.assertFalse(output.exists())

    def test_hash_first_setup_separate_and_adjacent_ended_interval_only(self):
        data=(HASH_RECEIPT+b'\n'+b'hackios-eagl: guest pid=88 context=1 remote opened\n'+
              hashline(1,100)+b'tl: g 1 began 90 ended 100 4\n'+
              hashline(2,20)+b'tl: g 2 began 290 ended 300 4\n')
        serial=self.serial(data)
        report=hashes.summarise(serial,0,len(data))
        self.assertIsNone(report['adjacent_completed_frame_intervals'])
        report=hashes.summarise(serial,0,len(data),True)
        self.assertEqual(report['observed_setup_records'][0]['hash_total_us'],100)
        self.assertEqual(report['observed_regular_totals']['hash_total_us'],20)
        self.assertEqual(report['adjacent_completed_frame_intervals'][0]['completed_frame_interval_us'],200)
        self.assertEqual(report['adjacent_completed_frame_intervals'][0]['hash_span_fraction'],.1)

    def test_missing_hash_records_are_unknown_and_repeated_ids_disable_pairing(self):
        report=hashes.summarise(self.serial(HASH_RECEIPT+b'\n'),0,100000)
        self.assertTrue(report['activation_receipt_seen']);self.assertIsNone(report['observed_regular_totals'])
        serial=self.serial(b'hackios-eagl: guest pid=88 context=1 remote opened\n'+hashline(1,5)+hashline(1,6))
        report=hashes.summarise(serial,0,100000,True)
        self.assertEqual(report['observed_records'],2)
        self.assertIsNone(report['adjacent_completed_frame_intervals'])

    def test_post_counter_cli_preserves_uncertainty_and_publication_caveat(self):
        row=dict(cpu=0,mode=1,user_instructions=100,kernel_instructions=20,user_uncertain=0,kernel_uncertain=0,
                 user_blocks=1,kernel_blocks=1,translations=1,corrected_tail=0,corrections=0,unbounded_exits=0)
        end=dict(row,user_instructions=300,kernel_instructions=50,user_uncertain=5,user_blocks=5,kernel_blocks=3)
        for name,value in [('post-start',row),('post-end',end)]:
            (self.root/f'{name}-instructions.txt').write_text('HIOS_PROFILE '+json.dumps(value)+'\n')
        measure=dict(post_profile_start=dict(stop_requested_t=0,stopped_t=.1,resume_requested_t=.15,resumed_t=.2),
            post_profile_end=dict(stop_requested_t=2,stopped_t=2.1,resume_requested_t=2.15,resumed_t=2.2),
            post_start=dict(ring_seq=5),post_end=dict(ring_seq=10))
        (self.root/'measure.json').write_text(json.dumps(measure));(self.root/'post-interaction').mkdir()
        (self.root/'post-interaction/result.json').write_text(json.dumps(dict(failure=None)))
        stream=io.StringIO()
        with contextlib.redirect_stdout(stream):instructions.main([str(self.root),'--window','post'])
        report=json.loads(stream.getvalue())
        self.assertEqual([report['lower_instructions'],report['credited_instructions']],[225,230])
        self.assertEqual(report['approx_instructions_per_ring_publication'],[45,46])
        self.assertIsNone(report['actual_content_fps']);self.assertEqual(report['window'],'post')

if __name__=='__main__':unittest.main()
