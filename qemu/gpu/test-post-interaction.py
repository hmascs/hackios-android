#!/usr/bin/env python3
"""Offline QMP mock fixtures. These do not prove guest input acceptance."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import post_interaction as post


class Clock:
    def __init__(self): self.now=100.0
    def monotonic(self): return self.now
    def sleep(self,seconds): self.now+=seconds


class FakeQmp:
    def __init__(self, clock, failure=None):
        self.clock,self.failure=clock,failure
        self.calls=[]
    def command(self,name,**arguments):
        assert name in ('input-send-event','screendump'),name
        self.calls.append((name,arguments))
        if name=='screendump':
            Path(arguments['filename']).write_bytes(b'P6\n1 1\n255\n\0\0\0')
        if name=='input-send-event' and any(e['type']=='btn' and e['data']['down'] for e in arguments['events']):
            if self.failure=='cancel': raise KeyboardInterrupt('fixture cancellation after down request')
            if self.failure=='timeout': self.clock.now+=20
        return {}


class FakeObserver:
    def __init__(self,qmp,fail=False):
        self.qmp=qmp;self.origin=None;self.samples=[];self.finished=False;self.error=None;self.fail=fail
    def start(self,origin):
        assert self.qmp.calls[-1]==('input-send-event',dict(events=[post.button(False)]))
        self.origin=origin
    @property
    def next_due_ns(self):
        return None if self.origin is None or self.finished or self.error or len(self.samples)>=3 else self.origin+len(self.samples)*400_000_000
    def capture_if_due(self,qmp,now):
        assert qmp is self.qmp
        if self.fail:self.error='fixture unsupported memfd';raise RuntimeError(self.error)
        if self.next_due_ns is not None and now>=self.next_due_ns:self.samples.append(dict(now=now))
    def finish(self):
        assert not self.finished;self.finished=True
        return dict(samples=self.samples,planned_slots=3,observer_error=self.error)


class PostInteractionTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.output=Path(self.temp.name)
        self.clock=Clock()
    def tearDown(self): self.temp.cleanup()
    def plan(self):
        return dict(version=1,canvas=[1125,2436],timeout_seconds=10,
                    capture_interval_ms=1000,max_captures=20,steps=[
                        dict(action='tap',name='settings',x=691,y=1729,hold_ms=180,settle_ms=1100),
                        dict(action='swipe',name='home',**{'from':[562,2330],'to':[562,900]},duration_ms=420,points=12),
                        dict(action='capture',name='returned')])
    def run_mock(self,qmp,plan=None,precondition=True,observer=None):
        counter=0
        def ring(path,version):
            nonlocal counter
            counter+=1
            return dict(protocol_version=version,ring_seq=counter,ring_guard=counter*2,
                        ring_available=True,pixel_sha256='0'*64,hash_guard_checked=True)
        with patch.object(post.time,'monotonic',self.clock.monotonic), \
             patch.object(post.time,'sleep',self.clock.sleep), patch.object(post.time,'monotonic_ns',lambda:int(self.clock.now*1e9)), patch.object(post,'hash_ring',ring), \
             patch.object(post,'desktop_precondition',return_value=dict(passed=precondition)):
            return post.run_plan(qmp,plan or self.plan(),self.output,self.output/'unused.ring',2,finalfb_recorder=observer)

    def test_known_commands_coords_capture_paths_and_final_release(self):
        qmp=FakeQmp(self.clock)
        result=self.run_mock(qmp)
        self.assertIsNone(result['failure'])
        self.assertFalse(result['pressed_state_at_finish'])
        inputs=[args['events'] for name,args in qmp.calls if name=='input-send-event']
        self.assertEqual(inputs[0],post.position(691,1729))
        self.assertEqual(inputs[-1],[post.button(False)])
        self.assertGreaterEqual(len(result['captures']),6)
        self.assertTrue(all((self.output/'post-interaction'/row['screenshot']).is_file() for row in result['captures']))
        self.assertTrue(all(Path(args['filename']).is_relative_to(self.output/'post-interaction')
                            for name,args in qmp.calls if name=='screendump'))
        self.assertEqual(result['measurement_scope'],'post-measure only; excluded from published throughput window')

    def test_cancellation_after_down_still_sends_release(self):
        qmp=FakeQmp(self.clock,'cancel')
        with self.assertRaises(KeyboardInterrupt): self.run_mock(qmp)
        result=json.loads((self.output/'post-interaction/result.json').read_text())
        self.assertIn('KeyboardInterrupt',result['failure'])
        self.assertFalse(result['pressed_state_at_finish'])
        self.assertEqual(qmp.calls[-1],('input-send-event',dict(events=[post.button(False)])))

    def test_timeout_does_not_skip_cleanup_release(self):
        qmp=FakeQmp(self.clock,'timeout')
        with self.assertRaises(TimeoutError): self.run_mock(qmp)
        result=json.loads((self.output/'post-interaction/result.json').read_text())
        self.assertFalse(result['pressed_state_at_finish'])
        self.assertTrue(result['events'][-1]['cleanup'])
        self.assertEqual(qmp.calls[-1],('input-send-event',dict(events=[post.button(False)])))

    def test_unknown_commands_paths_nonfinite_and_canvas_are_rejected(self):
        for change in ('shell','path','coordinate','nan','canvas','traversal','budget'):
            plan=self.plan()
            if change=='shell': plan['steps'][0]['action']='human-monitor-command'
            if change=='path': plan['steps'][0]['path']='/tmp/escape'
            if change=='coordinate': plan['steps'][0]['x']=1125
            if change=='nan': plan['steps'][0]['hold_ms']=float('nan')
            if change=='canvas': plan['canvas']=[750,1624]
            if change=='traversal': plan['steps'][0]['name']='../escape'
            if change=='budget': plan['timeout_seconds']=1
            with self.subTest(change=change), self.assertRaises(ValueError): post.validate_plan(plan)

    def test_capture_budget_failure_releases_button(self):
        plan=self.plan();plan['max_captures']=1
        qmp=FakeQmp(self.clock)
        with self.assertRaises(RuntimeError): self.run_mock(qmp,plan)
        self.assertEqual(qmp.calls[-1],('input-send-event',dict(events=[post.button(False)])))

    def test_plan_load_limit_and_json_roundtrip(self):
        path=self.output/'plan.json';path.write_text(json.dumps(self.plan()))
        self.assertEqual(post.load_plan(path),self.plan())
        path.write_bytes(b' '*(65536+1))
        with self.assertRaises(ValueError): post.load_plan(path)

    def test_failed_desktop_precondition_never_submits_a_press(self):
        qmp=FakeQmp(self.clock)
        with self.assertRaises(RuntimeError): self.run_mock(qmp,precondition=False)
        inputs=[event for name,args in qmp.calls if name=='input-send-event' for event in args['events']]
        self.assertTrue(all(event==post.button(False) for event in inputs))


    def test_observer_uses_same_qmp_after_release_and_finishes_in_settle(self):
        qmp=FakeQmp(self.clock);observer=FakeObserver(qmp)
        result=self.run_mock(qmp,observer=observer)
        self.assertTrue(observer.finished);self.assertEqual(len(observer.samples),3)
        self.assertIsNone(result['failure']);self.assertIsNone(result['finalfb']['semantic_success'])

    def test_observer_failure_keeps_ordinary_capture_and_input_evidence(self):
        qmp=FakeQmp(self.clock);observer=FakeObserver(qmp,fail=True)
        result=self.run_mock(qmp,observer=observer)
        self.assertIsNone(result['failure']);self.assertTrue(observer.finished)
        self.assertIn('unsupported',result['finalfb_error'])
        self.assertGreaterEqual(len(result['captures']),6)
        self.assertEqual(qmp.calls[-1],('input-send-event',dict(events=[post.button(False)])))


if __name__=='__main__': unittest.main()
