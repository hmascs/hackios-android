#!/usr/bin/env bash
# Experimental link. Unimplemented GL entries fail loudly, never call Mesa.
set -euo pipefail
if [[ $# != 5 ]]; then
    echo 'usage: build-redwood-remote.sh REDWOOD_USERLAND IOS_SDK GL_XML BASELINE_OSMESA NEW_OUT' >&2
    exit 2
fi
root=$(cd -- "$(dirname -- "$0")" && pwd)
redwood=$(realpath -e -- "$1"); sdk=$(realpath -e -- "$2")
xml=$(realpath -e -- "$3"); osmesa=$(realpath -e -- "$4")
mkdir -- "$5"
out=$(realpath -e -- "$5")
nm=${LLVM_NM:-llvm-nm-18}
flags=(--target=arm64-apple-ios16.0 -isysroot "$sdk" -O2 -fno-stack-protector -I"$root" -I"$out" -I"$redwood/src")
python3 "$root/prepare-redwood-remote.py" "$redwood/src/eagl.c" "$out/eagl.c"
python3 "$root/gen-eagl-guest-scalars.py" "$xml" > "$out/eagl-guest-generated.h"
"$nm" -gUj "$osmesa" | awk '/^_gl[A-Z]/{ print substr($0,2) }' > "$out/osmesa-exports.txt"
test -s "$out/osmesa-exports.txt"
python3 "$redwood/mkeagl.py" "$redwood/src/eagl_slots.txt" "$xml" "$out/osmesa-exports.txt" "$out/eagl.c" > "$out/eagl_gen.h"
objects=()
for unit in eagl-guest-{client,context,draw,query,scalars,shaders,surface,sync,textures,uniforms} eagl-client-arrays eagl-pixel-transfer; do
    obj="$out/$unit.o"
    "${CLANG:-clang}" "${flags[@]}" -c "$root/$unit.c" -o "$obj"
    objects+=("$obj")
done
"$nm" -gUj "${objects[@]}" > "$out/bridge-exports.txt"
python3 - "$out" <<'PY'
from pathlib import Path
import re
import sys
out = Path(sys.argv[1])
provided = set((out / 'bridge-exports.txt').read_text().split())
header = (out / 'eagl_gen.h').read_text()
missing = [(sig, name) for sig, name in re.findall(r'^extern ([^\n;]*?\b(gl[A-Z]\w*)\([^\n;]*\));$', header, re.M) if '_' + name not in provided]
if not missing:
    raise SystemExit('Unexpectedly found no unsupported symbols; check the generated declarations')
with (out / 'eagl.c').open('a') as source:
    source.write('\n/* Generated, deliberately fail-closed unsupported entries. */\n')
    for sig, name in missing:
        source.write(f'{sig} {{ say("hackios-eagl: unsupported {name}\\n"); abort(); }}\n')
(out / 'unsupported-gl.txt').write_text(''.join(name + '\n' for _, name in missing))
print(f'{len(missing)} explicit unsupported GL entries')
PY
"${CLANG:-clang}" "${flags[@]}" -DHIOS_REMOTE_GLES -c "$out/eagl.c" -o "$out/eagl.o"
"${LD64:-/usr/lib/llvm-18/bin/ld64.lld}" -arch arm64 -platform_version ios 16.0 16.5 \
    -syslibroot "$sdk" -dylib -install_name /redwood/lib/libredwood-gl.dylib -adhoc_codesign \
    -o "$out/libredwood-gl.dylib" "$out/eagl.o" "${objects[@]}" \
    -framework CoreFoundation -framework IOKit -framework IOSurface -lobjc -lSystem
