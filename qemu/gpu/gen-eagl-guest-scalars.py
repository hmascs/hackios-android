#!/usr/bin/env python3
"""Generate typed GLES guest wrappers from the allowlist and Khronos gl.xml."""
import re
import sys
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).parent
commands = {c.findtext('proto/name'): c for c in ET.parse(sys.argv[1]).iter('command') if c.find('proto') is not None}
formats = {'GLfloat': 'fbits', 'GLclampf': 'fbits', 'GLenum': 'word', 'GLuint': 'word',
           'GLint': 'word', 'GLsizei': 'word', 'GLbitfield': 'word', 'GLboolean': 'word'}
print('/* Generated guest wrappers. Include after GLES scalar types and guest adapter. */')
for kind, name, count in re.findall(r'GL_(VOID|UINT)\(\d+, (gl\w+), (\d+),', (root / 'eagl-scalars.def').read_text()):
    cmd = commands[name]
    ret = ''.join(cmd.find('proto').itertext()).removesuffix(name).strip()
    args = [(p.findtext('ptype'), p.findtext('name')) for p in cmd.findall('param')]
    if len(args) != int(count) or (ret == 'void') != (kind == 'VOID'):
        raise ValueError(name)
    print(f'{ret} {name}(' + (', '.join(f'{t} {n}' for t, n in args) or 'void') + ') {')
    if args:
        print('    const uint32_t words[] = {' + ', '.join(f'{formats[t]}({n})' for t, n in args) + '};')
    print(f'    ' + ('return (' + ret + ')' if kind == 'UINT' else '(void)') +
          f'guest_scalar(EA_{name}, ' + ('words' if args else 'NULL') + f', {count}, {int(kind == "UINT")});')
    print('}')
