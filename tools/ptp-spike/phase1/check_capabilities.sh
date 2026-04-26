#!/usr/bin/env bash
# Phase 1 - capability inventory for IEEE 1588 PTP on a stock Pixel 7.
#
# Drives adb shell to dump:
#   - Kernel PTP / timestamping config flags
#   - /sys/class/ptp/, /dev/ptp*
#   - ethtool -T equivalents per interface
#   - Wi-Fi NIC + driver identification
#
# No root required. Output is written as Markdown to phase1/output/.
#
# Usage: bash tools/ptp-spike/phase1/check_capabilities.sh [adb-serial]

set -uo pipefail

SERIAL="${1:-}"
ADB=(adb)
[[ -n "$SERIAL" ]] && ADB+=(-s "$SERIAL")

OUT_DIR="$(cd "$(dirname "$0")" && pwd)/output"
mkdir -p "$OUT_DIR"
TS="$(date +%Y%m%d_%H%M%S)"
REPORT="$OUT_DIR/capability_${TS}.md"

run() {
  # Run adb shell command, label output. Always prints non-fatally.
  local label="$1"; shift
  echo "## ${label}"
  echo
  echo '```'
  "${ADB[@]}" shell "$@" 2>&1 || echo "[error rc=$?]"
  echo '```'
  echo
}

{
  echo "# Pixel 7 PTP Capability Inventory (${TS})"
  echo
  run "Device props" 'getprop ro.product.model; getprop ro.build.fingerprint; getprop ro.boot.hardware.platform'
  run "Kernel version" 'uname -a; cat /proc/version'
  run "Kernel PTP config (/proc/config.gz)" 'zcat /proc/config.gz 2>/dev/null | grep -E "^CONFIG_(PTP_|NETWORK_PHY_TIMESTAMPING|PHYLIB|NET_PKTGEN)" || echo "config.gz unavailable"'
  run "/sys/class/ptp" 'ls -la /sys/class/ptp 2>&1'
  run "/dev/ptp*" 'ls -la /dev/ptp* 2>&1'
  run "Network interfaces" 'ip -o link show; echo; ip -4 addr'
  run "Wi-Fi driver" 'getprop ro.wifi.channels; ls /sys/class/net/wlan0/device/driver 2>&1; readlink /sys/class/net/wlan0/device/driver 2>&1'
  run "SO_TIMESTAMPING header presence" 'grep -E "SOF_TIMESTAMPING|SO_TIMESTAMPING" /system/usr/include/linux/net_tstamp.h 2>&1 | head -n 40 || echo "headers absent on device"'
} | tee "$REPORT"

echo
echo "Report written: $REPORT"
echo
echo "Next: build and push the SO_TIMESTAMPING probe (phase1/native_test/) and append its output to $REPORT."
