#!/usr/bin/env bash
# Cross-compile ptpd2 for aarch64 Android via the NDK.
#
# Run on a Linux or WSL2 host with autoreconf + bash. The Windows
# powershell host cannot run autotools.
#
# Inputs (env vars):
#   NDK        -> /opt/android-ndk-r26d (or ANDROID_NDK_HOME)
#   PTPD_SRC   -> path to the cloned ptpd repo (e.g. /mnt/c/Users/yuboh/GitHub/ptpd)
#   ANDROID_API-> default 29 (matches RecSync minSdk)
#
# Output:
#   ./out/ptpd2  (statically linked aarch64-android)

set -euo pipefail

NDK="${NDK:-${ANDROID_NDK_HOME:-}}"
[[ -z "$NDK" ]] && { echo "set NDK=/path/to/android-ndk" >&2; exit 1; }
PTPD_SRC="${PTPD_SRC:-}"
[[ -z "$PTPD_SRC" ]] && { echo "set PTPD_SRC=/path/to/ptpd source repo" >&2; exit 1; }
[[ -d "$PTPD_SRC" ]] || { echo "PTPD_SRC not found: $PTPD_SRC" >&2; exit 1; }

API="${ANDROID_API:-29}"
TARGET="aarch64-linux-android"
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/out"
BUILD="$HERE/build"
mkdir -p "$OUT" "$BUILD"

TC_ROOT="$NDK/toolchains/llvm/prebuilt"
HOST_TAG="$(ls "$TC_ROOT" | head -n 1)"
TC_BIN="$TC_ROOT/$HOST_TAG/bin"

export CC="$TC_BIN/${TARGET}${API}-clang"
export CXX="$TC_BIN/${TARGET}${API}-clang++"
export AR="$TC_BIN/llvm-ar"
export RANLIB="$TC_BIN/llvm-ranlib"
export STRIP="$TC_BIN/llvm-strip"
export CFLAGS="-O2 -fPIE -static -Wno-error"
export LDFLAGS="-static -fPIE"

cd "$PTPD_SRC"

# Bootstrap autotools artifacts if missing.
if [[ ! -x configure ]]; then
  autoreconf -vi
fi

# Configure for cross-build. PCAP and SNMP disabled to keep deps minimal;
# statistics enabled because we need stats.log for analysis.
./configure \
  --host="$TARGET" \
  --disable-snmp \
  --without-pcap \
  --enable-slave-only=no \
  --prefix="$BUILD/install" \
  CC="$CC" CXX="$CXX" CFLAGS="$CFLAGS" LDFLAGS="$LDFLAGS"

make -j"$(nproc 2>/dev/null || sysctl -n hw.ncpu || echo 4)"
"$STRIP" src/ptpd2
cp src/ptpd2 "$OUT/ptpd2"

echo
echo "Built: $OUT/ptpd2"
"$TC_BIN/llvm-readelf" -h "$OUT/ptpd2" | head -n 12

cat <<MSG

Push and smoke-test on a rooted Pixel 7:

  adb push $OUT/ptpd2 /data/local/tmp/
  adb shell chmod 755 /data/local/tmp/ptpd2
  adb shell su -c '/data/local/tmp/ptpd2 --help | head -n 30'

If you see help output, Phase 2 is complete.

Known fragility:
- Android bionic lacks a few legacy Linux shims; if compilation fails
  on functions like daemon(), getopt_long, openlog, etc., apply the
  patches under tools/ptp-spike/phase2/patches/ (create as needed).
- SELinux on stock-rooted Pixel 7 will deny raw socket binds. You may
  need 'su -c "setenforce 0"' for the spike runs only. Document this
  in your report.
MSG
