#!/usr/bin/env bash
# Host-side --native-worker adapter for a real Android shell (no PTY).
# The user selects the paired device; this script does not pair or deploy.
set -euo pipefail
if [[ $# != 2 || $2 != Adreno ]]; then
    echo 'adb-worker.sh requires a backend ID and --expect-renderer Adreno' >&2
    exit 2
fi
case "$1" in
    egl-gles3-pbuffer) worker=hackios-egl-worker ;;
    vulkan-rgba8) worker=hackios-vulkan-worker ;;
    *) echo "unsupported GPU backend: $1" >&2; exit 2 ;;
esac
: "${HIOS_ADB_SERIAL:?set HIOS_ADB_SERIAL to the Android device from adb devices -l}"
exec adb -s "$HIOS_ADB_SERIAL" shell -T "/data/local/tmp/hackios-gpu/$worker" "$1" Adreno
