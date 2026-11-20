#!/usr/bin/env python3
"""Count observable lsd requests without mistaking kernel warnings for XPC retries."""
import argparse
from collections import Counter
import json
from pathlib import Path
import re

SECTION = re.compile(r'^={5,} (.+?) +· +[^ ]+ +={5,}$')
TS = r'\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d+'
LOOKUP = re.compile(
    r'^com\.apple\.xpc\.launchd\|(?P<time>' + TS + r') [^\n]*?failed lookup: '
    r'name = (?P<name>[A-Za-z0-9_.-]+), (?:(?:flags|scope) = [^,]+, )*'
    r'requestor = lsd\[(?P<pid>\d+)\], error = (?P<error>\d+):')
CPU = re.compile(r'process lsd\[(\d+)\] denied attempt to escape CPU monitor')
SPAWN = re.compile(r'^com\.apple\.xpc\.launchd\|(' + TS + r') [^\n]*?Successfully spawned (lsd|mobileactivationd)\[(\d+)\]')


def serial_lines(lines, exported):
    if not exported:
        yield from lines
        return
    found = False
    for line in lines:
        section = SECTION.fullmatch(line.rstrip('\r\n'))
        if section:
            if found:
                return
            found = section[1] == '串口 serial.log'
        elif found:
            yield line
    if not found:
        raise ValueError('export contains no serial.log section')


def audit(lines, exported=False):
    seen = set()
    stats = {}
    cpu = Counter()
    spawn = set()
    incomplete = duplicates = cpu_before_lookup = 0
    for line in serial_lines(lines, exported):
        warnings = CPU.findall(line)
        cpu.update(warnings)
        if not seen:
            cpu_before_lookup += len(warnings)
        boot = SPAWN.match(line)
        if boot:
            spawn.add(boot.groups())
        m = LOOKUP.match(line)
        if not m:
            if 'failed lookup:' in line and 'requestor = lsd[' in line:
                incomplete += 1
            continue
        stamp, name, pid, error = m.groups()
        observation = stamp, name, pid, error
        if observation in seen:
            duplicates += 1
            continue
        seen.add(observation)
        identity = (name, error)
        hit = stats.setdefault(identity, {'count': 0, 'first': stamp, 'last': stamp, 'pids': set()})
        hit['count'] += 1
        hit['first'] = min(hit['first'], stamp)
        hit['last'] = max(hit['last'], stamp)
        hit['pids'].add(int(pid))
    result = []
    for (name, error), hit in sorted(stats.items()):
        result.append(dict(name=name, error=int(error), **{**hit, 'pids': sorted(hit['pids'])}))
    return dict(lookups=result, repeated_missing=[r['name'] for r in result if r['error'] == 3 and r['count'] > 1],
                cpu_monitor_messages=sum(cpu.values()), cpu_monitor_pids=dict(sorted(cpu.items())),
                cpu_monitor_before_first_lookup=cpu_before_lookup if seen else None,
                duplicate_lookup_records=duplicates, incomplete_lookup_records=incomplete,
                reported_spawns=[dict(time=t, service=s, pid=int(p)) for t, s, p in sorted(spawn)])


def main():
    p = argparse.ArgumentParser(description=__doc__)
    group = p.add_mutually_exclusive_group(required=True)
    group.add_argument('--serial', nargs='+', type=Path, help='native serial logs, one per boot')
    group.add_argument('--export', nargs='+', type=Path, help='App log exports with a serial section')
    a = p.parse_args()
    for path in a.serial or a.export:
        with path.open(encoding='utf-8', errors='replace') as f:
            print(json.dumps({'log': str(path), **audit(f, a.export is not None)}, ensure_ascii=False))


if __name__ == '__main__':
    main()
