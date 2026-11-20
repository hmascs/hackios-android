#!/bin/sh
# 用新编的 bionic QEMU 替换 App 内置的 qemu-android-bundle.zip 里的二进制。
#
# 用法：tools/rebuild-qemu-bundle.sh <新二进制> [输出 zip]
#   tools/rebuild-qemu-bundle.sh /root/workspace/android-build/qemu-android-fast
#
# 为什么不能直接 zip -u 覆盖：v8 的教训是 zip 不携带模式位，App 侧 afterExtract 会给
# bin/ 下每个条目补 0755；只要条目名和相对路径不变，重新打包不会影响这些修补。但 zip 的
# symlink 条目在这里是**物化的普通文件**（v8 把 16 个软链写成真文件），所以解包再打包时
# 不能用会保留软链语义的工具，否则会又变回软链形态、在 Android 上解出 0 字节存根。
#
# 步骤：解包到临时目录 → 替换 qemu/qemu-system-aarch64 → 按原顺序用 -X 重打包
# （-X 去掉 uid/gid/时间戳等额外信息，减小体积并让产物可复现）。
set -eu

NEW_BIN=${1:?请给新二进制路径}
OUT=${2:-app/src/main/assets/qemu-android-bundle.zip}
[ -f "$NEW_BIN" ] || { echo "找不到 $NEW_BIN" >&2; exit 1; }

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SRC="$ROOT/app/src/main/assets/qemu-android-bundle.zip"
[ -f "$SRC" ] || { echo "找不到 $SRC" >&2; exit 1; }

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
echo "解包 $SRC → $TMP"
unzip -q "$SRC" -d "$TMP"

echo "替换 qemu/qemu-system-aarch64 ($(wc -c < "$NEW_BIN") 字节)"
cp "$NEW_BIN" "$TMP/qemu/qemu-system-aarch64"
chmod 755 "$TMP/qemu/qemu-system-aarch64"

# 打包：-r 递归、-X 去额外属性、-9 最大压缩（QEMU 二进制压缩率一般，但白拿）
(cd "$TMP" && zip -qrX -9 "$OUT.tmp" .)
mv "$OUT.tmp" "$OUT"

echo "完成：$OUT ($(wc -c < "$OUT") 字节)"
unzip -l "$OUT" | grep -E "qemu-system-aarch64|files$" | tail -2
echo
echo "记得："
echo " 1. 改 app/src/main/java/com/android/gudao/iphoneos/AssetDeployer.kt 的 LAYOUT_VERSION +1，"
echo "    并在注释里写清这次换的是 QEMU 二进制、为什么；"
echo " 2. 改 app/build.gradle.kts 的 versionCode/versionName；"
echo " 3. 重编 APK 并跑一次真机启动验收（改二进制属于高风险改动，必须真机进桌面）。"
