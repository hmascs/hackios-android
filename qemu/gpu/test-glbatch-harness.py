#!/usr/bin/env python3
import contextlib,importlib.util,io,json
from pathlib import Path
import struct,tempfile,unittest
HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('harness',HERE/'glpass-desktop.py')
h=importlib.util.module_from_spec(spec);spec.loader.exec_module(h)
def entry(name,data):return struct.pack('<16sII',name,len(data),0)+data+bytes(-len(data)%16)
class BatchHarnessTests(unittest.TestCase):
 def setUp(self):self.t=tempfile.TemporaryDirectory();self.root=Path(self.t.name);self.apfs=self.root/'root';self.apfs.write_bytes(b'a'*4096)
 def tearDown(self):self.t.cleanup()
 def payload(self,command,name='input'):
  body=entry(b'ramdisk',b'old')+entry(b'cmdline',command+b'\0')+bytes(24)
  p=self.root/name;p.write_bytes(struct.pack('<4sI',b'XNUP',len(body))+body);return p
 def test_exact_batch_pair_strips_only_render_diagnostics(self):
  p=self.payload(b'cpus=2 loader_return_after=1200 glbatch=0 glbatch=1 noglbatch=1 glhash=1 glframes=1 touch_lat=2 other=keep')
  original=p.read_bytes();tokens=[]
  for mode in (0,1):
   receipt=h.repack(p,self.root/f'out{mode}',self.apfs,session_seconds=1500,glbatch=mode)
   row=receipt['cmdlines'][0];items=row['output'].split();tokens.append([x for x in items if not x.startswith('glbatch=')])
   self.assertEqual([x for x in items if x.startswith('glbatch=')],[f'glbatch={mode}'])
   self.assertFalse(any(x.startswith(('glhash=','glframes=','touch_lat=')) for x in items))
   self.assertIn('noglbatch=1',items);self.assertIn('loader_return_after=1500',items)
   self.assertTrue(row['render_diagnostics_stripped'])
  self.assertEqual(tokens[0],tokens[1]);self.assertEqual(p.read_bytes(),original)
 def test_malformed_source_and_profile_combinations_fail_closed(self):
  for i,flag in enumerate((b'glbatch',b'glbatch=2',b'glbatch=1x')):
   p=self.payload(b'cpus=2 '+flag,f'in{i}')
   with self.assertRaises(ValueError):h.repack(p,self.root/f'bad{i}',self.apfs,glbatch=1)
  p=self.payload(b'cpus=2','plain')
  with self.assertRaises(ValueError):h.repack(p,self.root/'hash',self.apfs,glbatch=1,guest_hash_profile=True)
  with contextlib.redirect_stderr(io.StringIO()),self.assertRaises(SystemExit):h.main(['/missing']*7+[str(self.root/'noout'),'--glbatch','1','--profile-mode','1'])
  self.assertFalse((self.root/'noout').exists())
 def test_atomic_phase_is_readable_without_final_run(self):
  h.atomic_phase(self.root,'measure-end',dict(phase='measure-end',measure=dict(end=dict(ring_seq=5))))
  self.assertEqual(json.loads((self.root/'phase-measure-end.json').read_text())['measure']['end']['ring_seq'],5)
  self.assertFalse((self.root/'run.json').exists());self.assertFalse(list(self.root.glob('*.tmp')))
  with self.assertRaises(ValueError):h.atomic_phase(self.root,'../escape',{})
if __name__=='__main__':unittest.main()
