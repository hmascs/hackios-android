#!/usr/bin/env bash
# 在已连接的设备/模拟器上抓取四个标签页 + 语义树，用于人工比对 iOS 参考稿。
# 用法: ./tools/screens.sh <out-dir> [serial]
set -euo pipefail

OUT=${1:-artifacts/screens}
SERIAL=${2:-}
ADB="adb ${SERIAL:+-s $SERIAL}"
PKG=com.android.gudao.iphoneos

mkdir -p "$OUT"

$ADB shell am force-stop "$PKG" || true
$ADB shell settings put global window_animation_scale 0
$ADB shell settings put global transition_animation_scale 0
$ADB shell settings put global animator_duration_scale 0
$ADB shell am start -n "$PKG/.MainActivity" > /dev/null
sleep 12

shot() {
  local name=$1
  $ADB exec-out screencap -p > "$OUT/$name.png"
  $ADB shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 || true
  $ADB pull /sdcard/ui.xml "$OUT/$name.xml" > /dev/null 2>&1 || true
  echo "  → $OUT/$name.png"
}

# 底部标签栏四个热区（1080×2400，420dpi）
TAB_Y=2290
sleep 10; shot 10_device_tab
$ADB shell input tap 432 $TAB_Y; sleep 18; shot 20_screen_tab
$ADB shell input tap 694 $TAB_Y; sleep 18; shot 30_library_tab
$ADB shell input tap 957 $TAB_Y; sleep 18; shot 40_settings_tab
$ADB shell input tap 168 $TAB_Y; sleep 4

echo "done: $OUT"
