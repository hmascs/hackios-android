#!/usr/bin/env python3
"""Conservative publication diagnostics; never validates content FPS or causality.

A slow environmental/thermal read after the counters need not erase a bounded
observation. This report retains identity limitations and does not override the
strict frame-stats acceptance result or modify original run files.
"""
import argparse
import hashlib
import importlib.util
import json
import math
from pathlib import Path

spec=importlib.util.spec_from_file_location('frame_stats',Path(__file__).with_name('frame-stats.py'))
metrics=importlib.util.module_from_spec(spec)
spec.loader.exec_module(metrics)


def diagnostic(measure):
    if measure.get('failure'):
        raise ValueError('run failure requires separate review before describing a complete window')
    first,last=measure.get('start'),measure.get('end')
    if not first or not last:
        raise ValueError('both original boundary snapshots are required')
    errors=metrics.boundary_errors(first,'start',math.inf)+metrics.boundary_errors(last,'end',math.inf)
    if errors:
        raise ValueError('; '.join(errors))
    if first.get('readback_resets',0) or last.get('readback_resets',0):
        raise ValueError('readback reset: cannot compare counters')
    whole_min=last['t_before']-first['t_after']
    whole_max=last['t_after']-first['t_before']
    if whole_min<=0:
        raise ValueError('overlapping boundary brackets')
    sources={}
    for field,source,previous in (('ring_seq','ring','logs'),('readbacks','logs',None),('cpu','cpu','ring')):
        a,b=first.get(field),last.get(field)
        if a is None or b is None:
            continue
        if not metrics.finite_number(a) or not metrics.finite_number(b) or min(a,b)<0 or b<a:
            raise ValueError(f'{field} nonfinite, negative or regressed')
        lo0=first['source_t'][previous] if previous else first['t_before']
        lo1=last['source_t'][previous] if previous else last['t_before']
        hi0,hi1=first['source_t'][source],last['source_t'][source]
        minimum,maximum=lo1-hi0,hi1-lo0
        if minimum<=0:
            raise ValueError(f'{source} sample brackets overlap')
        delta=b-a
        sources[field]=dict(delta=delta,source_seconds_bracket=[minimum,maximum],
                            per_second_bracket=[delta/maximum,delta/minimum],
                            nominal_end_to_end_seconds=hi1-hi0,
                            nominal_per_second=delta/(hi1-hi0),
                            whole_snapshot_per_second_bracket=[delta/whole_max,delta/whole_min])
    return dict(scope='bounded diagnostic observations only',causal_comparison_approved=False,
                actual_content_fps=None,sources=sources,
                thermal_observer_seconds=[first['t_after']-first['source_t']['cpu'],
                                          last['t_after']-last['source_t']['cpu']],
                limitations=['publication/readback counts do not prove unique visible content',
                             'artifact/runtime identity and renderer correctness remain separate prerequisites',
                             'source brackets use existing ordered timestamps; no original metadata was changed'])


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('trial',type=Path)
    args=parser.parse_args()
    path=args.trial/'measure.json'
    result=diagnostic(json.loads(path.read_text()))
    result['measure_sha256']=hashlib.sha256(path.read_bytes()).hexdigest()
    identity_path=args.trial/'identity.json'
    if identity_path.is_file():
        identity=json.loads(identity_path.read_text())
        result['identity_sha256']=hashlib.sha256(identity_path.read_bytes()).hexdigest()
        result['unresolved_terminal_mapping_counts']={name:len(row.get('unreadable',[]))
            for name,row in identity.get('runtime',{}).items()}
    print(json.dumps(result,indent=2))

if __name__=='__main__':main()
