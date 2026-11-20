#!/usr/bin/env bash
# Run one isolated iOS CPU trial at a time. Uses a private CoW RAM mapping, not a writable original.
# Usage: bash tools/boot-ios-cpu-trial.sh N QEMU_RUN_DIR RAM_IMAGE XNUP_PAYLOAD
set -euo pipefail
[[ $# == 4 ]] || { echo 'usage: boot-ios-cpu-trial.sh N QEMU_RUN_DIR RAM_IMAGE XNUP_PAYLOAD' >&2; exit 2; }
n=$1
[[ $n =~ ^[2468]$ ]] || { echo 'use 2, 4, 6 or 8 vCPUs' >&2; exit 2; }
r=$(realpath -e -- "$2"); ram=$(realpath -e -- "$3"); payload=$(realpath -e -- "$4")
q=$r/qemu-android/qemu
b=$(mktemp -d "$r/cpu${n}.XXXXXX")
echo "trial_dir=$b"
cp -a -- "$r/esp" "$b/esp"
cp --sparse=always -- "$r/QEMU_VARS.fd" "$b/vars.fd"
python3 - "$payload" "$b/payload.bin" "$n" <<'PY'
from pathlib import Path
import re
import sys
src, dst, cpus = sys.argv[1:]
data = Path(src).read_bytes()
# The test keeps the binary layout and all other boot arguments byte-for-byte.
marker = rb'(?<= )cpus=[1-8](?= virtio_input=1 virtio_net=1 gl=1 )'
data, count = re.subn(marker, b'cpus=' + cpus.encode(), data)
if count != 1:
    raise SystemExit(f'expected exactly one known cmdline marker, found {count}')
with Path(dst).open('xb') as f:
    f.write(data)
PY
mkdir -- "$b/tmp"
export LD_LIBRARY_PATH="$q/lib" TMPDIR="$b/tmp"
cd -- "$b"
exec timeout -k 10s 22m "$q/qemu-system-aarch64" \
    -L "$r/qemu-share" -M virt,gic-version=3 -cpu cortex-a76 \
    -smp "$n,sockets=1,cores=$n,threads=1" -m 7G -accel tcg,thread=multi,tb-size=1360 \
    -netdev user,id=net0,restrict=on -device virtio-net-device,netdev=net0 \
    -drive "if=pflash,format=raw,readonly=on,file=$r/QEMU_EFI.fd" \
    -drive if=pflash,format=raw,file=vars.fd \
    -drive file=fat:rw:esp,format=raw,if=virtio \
    -device loader,file=payload.bin,addr=0xf0000000,force-raw=on \
    -object "memory-backend-file,id=ram,size=7G,mem-path=$ram,readonly=on,rom=off,share=off" \
    -machine memory-backend=ram -global virtio-mmio.force-legacy=false \
    -device virtio-tablet-device -device virtio-keyboard-device -device ramfb \
    -qmp unix:qmp.sock,server=on,wait=off \
    -qmp unix:input.sock,server=on,wait=off \
    -display none -monitor none -serial file:serial.log -no-reboot
