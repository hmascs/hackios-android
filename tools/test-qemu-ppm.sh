#!/usr/bin/env bash
# Compile the exact ppm_save() functions (stock and patched) with a small QIO test shim.
# Run: bash tools/test-qemu-ppm.sh /path/to/qemu-11.1.2/ui/ui-qmp-cmds.c
set -euo pipefail
root=$(cd -- "$(dirname -- "$0")/.." && pwd)
source_file=${1:?Specify QEMU v11.1.2 ui/ui-qmp-cmds.c}
work=$(mktemp -d)
[[ "$work" == /* && -d "$work" && -O "$work" ]]
work=$(realpath -- "$work")
# Only our own generated files are stored here.
trap 'test -n "${work:-}" && test "$work" != / && rm -r -- "$work"' EXIT
mkdir "$work/ui"
cp -- "$source_file" "$work/ui/ui-qmp-cmds.c"
patch --batch --fuzz=0 -d "$work" -p1 < "$root/qemu/patches/0001-ppm-batch-packed-pixman-rows.patch"
python3 - "$source_file" "$work/ui/ui-qmp-cmds.c" "$work" <<'PY'
from pathlib import Path
import sys
for src, name in zip(sys.argv[1:3], ('stock', 'patched')):
    s = Path(src).read_text()
    p = s.index('static bool ppm_save(')
    q = s.index('\n}\n', p)
    Path(sys.argv[3], name + '.h').write_text(s[p:q+3].replace('bool ppm_save(', f'bool {name}('))
PY
cc -O2 -Wall -Wextra -Werror -I "$work" "$root/tools/test-qemu-ppm.c" \
    $(pkg-config --cflags --libs pixman-1) -o "$work/test"
"$work/test"
