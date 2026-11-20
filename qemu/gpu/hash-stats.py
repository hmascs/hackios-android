#!/usr/bin/env python3
"""Aggregate observed glhash records; never substitute frame_us for setup-inclusive time."""
import argparse
from collections import Counter
import json
from pathlib import Path
from trial_metrics import LogCursor,SerialMetrics


def summarise(serial,start,end,single_context_confirmed=False):
    selected=serial.selected(serial.hash_frames,start,end)
    first=serial.hash_frames[0] if serial.hash_frames else None
    regular=[row for row in selected if row['frame']>1]
    clients=sorted({(row['pid'],row['context']) for row in serial.remote_clients})
    result=dict(activation_receipt_seen=bool(serial.hash_profile_receipts),observed_records=len(selected),
        accepted_client_receipts=serial.remote_clients,distinct_client_pairs=clients,
        startup_first_completed_record=first,observed_setup_records=[row for row in selected if row['frame']<=1],records=selected,
        scope='raw completed-frame log records; context/PID is not encoded in hash/tl:g lines',
        timing_semantics='hash wall-us includes metadata/guards and setup; NOT exclusive CPU; never divide by same-frame frame_us',
        zero_semantics='missing receipt or completed-frame records is unknown, not zero hashing',
        actual_content_fps=None,adjacent_completed_frame_intervals=None)
    if regular:
        result['observed_regular_totals']={key:sum(row[key] for row in regular)
            for key in ('hash_calls','hash_bytes','hash_total_us','hash_attempts','hash_changed')}
        result['observed_regular_totals']['records']=len(regular)
    else:result['observed_regular_totals']=None
    ids=Counter(row['frame'] for row in serial.hash_frames)
    timings={}
    for row in serial.frame_timings:timings.setdefault(row['frame'],[]).append(row)
    regressed=any(b['frame']<=a['frame'] for a,b in zip(serial.hash_frames,serial.hash_frames[1:]))
    if not single_context_confirmed or len(clients)!=1 or clients[0][1]!=1 or any(n>1 for n in ids.values()) or regressed:
        result['alignment_limitation']='single accepted-client/source-contract/no-restart-or-fork scope must be independently confirmed; repeated frame ids disable pairing'
        return result
    intervals=[]
    for row in regular:
        previous,current=timings.get(row['frame']-1,[]),timings.get(row['frame'],[])
        if len(previous)!=1 or len(current)!=1:continue
        elapsed=current[0]['ended_us']-previous[0]['ended_us']
        if elapsed<=0:continue
        intervals.append(dict(frame=row['frame'],completed_frame_interval_us=elapsed,
            hash_wall_us=row['hash_total_us'],hash_span_fraction=row['hash_total_us']/elapsed,
            note='same-context adjacent completed ends, includes setup; wall-time fraction not CPU share'))
    result['adjacent_completed_frame_intervals']=intervals
    result['alignment_limitation']='conditional on explicit independently confirmed single-context scope, not inferred from matching f alone'
    return result


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('trial',type=Path)
    parser.add_argument('--window',choices=('measure','post','all'),default='measure')
    parser.add_argument('--single-context-confirmed',action='store_true',help='reviewed runtime/source contract only; do not infer from one frame id')
    args=parser.parse_args()
    cursor=LogCursor(args.trial/'serial.log');serial=SerialMetrics();serial.consume(cursor.lines())
    if args.window=='all':start,end=0,cursor.offset
    else:
        measure=json.loads((args.trial/'measure.json').read_text());prefix='post_' if args.window=='post' else ''
        start=measure[prefix+'start']['serial_offset'];end=measure[prefix+'end']['serial_offset']
    result=summarise(serial,start,end,args.single_context_confirmed)
    result.update(serial_byte_window=[start,end],window=args.window,unterminated_eof_excluded=bool(cursor.pending))
    print(json.dumps(result,indent=2))

if __name__=='__main__':main()
