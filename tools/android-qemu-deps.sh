#!/usr/bin/env bash
# 收集一个 Android(aarch64) 二进制的动态库闭包，用于把重编的 QEMU 连同它自己的依赖一起
# 打进 qemu-android-bundle.zip（不要指望复用旧 bundle 里版本不同的 glib）。
#
#   tools/android-qemu-deps.sh <binary> <out-dir> <search-dir>...
#
# 输出：out-dir 下按原文件名放好的 .so（含软链目标），以及一份 deps.txt 清单。
# 系统库（libc/libm/libdl/liblog/libz 之外的 android 系统库）不复制，只报告。
set -euo pipefail

bin=$1; out=$2; shift 2
[ -f "$bin" ] || { echo "缺少二进制：$bin" >&2; exit 2; }
mkdir -p "$out"

system_re='^(libc|libm|libdl|liblog|libandroid|libstdc\+\+|libc\+\+_shared|ld-android)\.so'

needed() {
  readelf -d "$1" 2>/dev/null | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p'
}

find_lib() {
  local name=$1 dir
  for dir in "$@"; do
    if [ -e "$dir/$name" ]; then readlink -f "$dir/$name"; return 0; fi
  done
  return 1
}

declare -A done_libs=()
queue=("$bin")
: > "$out/deps.txt"
while [ ${#queue[@]} -gt 0 ]; do
  cur=${queue[0]}; queue=("${queue[@]:1}")
  while IFS= read -r so; do
    [ -n "$so" ] || continue
    [ -n "${done_libs[$so]:-}" ] && continue
    done_libs[$so]=1
    if [[ $so =~ $system_re ]]; then
      echo "system  $so" >> "$out/deps.txt"
      continue
    fi
    path=$(find_lib "$so" "$@") || { echo "MISSING $so" >> "$out/deps.txt"; echo "缺少 $so（被 $cur 需要）" >&2; continue; }
    cp -f "$path" "$out/$so"
    echo "bundled $so <- $path" >> "$out/deps.txt"
    queue+=("$out/$so")
  done < <(needed "$cur")
done

echo "--- deps.txt"; cat "$out/deps.txt"
if grep -q '^MISSING' "$out/deps.txt"; then
  echo "有缺失库，见上" >&2; exit 1
fi
