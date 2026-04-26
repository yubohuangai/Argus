#!/usr/bin/env bash
# Build timestamping_probe for aarch64 Android via NDK.
#
# Usage:
#   NDK=/path/to/android-ndk bash build.sh
# Output:
#   ./out/timestamping_probe (aarch64 static)

set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
NDK="${NDK:-${ANDROID_NDK_HOME:-}}"
if [[ -z "$NDK" ]]; then
  echo "set NDK=/path/to/android-ndk-r26 (or ANDROID_NDK_HOME)" >&2
  exit 1
fi

API="${ANDROID_API:-29}"
TARGET="aarch64-linux-android${API}"

# Locate llvm prebuilts dir (platform varies: linux-x86_64, darwin-x86_64, windows-x86_64).
TC_ROOT="$NDK/toolchains/llvm/prebuilt"
HOST_TAG="$(ls "$TC_ROOT" | head -n 1)"
CC="$TC_ROOT/$HOST_TAG/bin/clang"

mkdir -p "$HERE/out"
"$CC" --target="$TARGET" -O2 -static -Wall -Wextra \
  -o "$HERE/out/timestamping_probe" \
  "$HERE/timestamping_probe.c"

echo "built: $HERE/out/timestamping_probe"
echo
echo "Push and run:"
echo "  adb push $HERE/out/timestamping_probe /data/local/tmp/"
echo "  adb shell chmod 755 /data/local/tmp/timestamping_probe"
echo "  adb shell /data/local/tmp/timestamping_probe"
