#!/usr/bin/env bash
# Push ptpd2 + leader.conf to a rooted Pixel 7 hotspot host and run.
# Usage: bash run_leader.sh [adb-serial]
set -euo pipefail
SERIAL="${1:-}"
ADB=(adb)
[[ -n "$SERIAL" ]] && ADB+=(-s "$SERIAL")

HERE="$(cd "$(dirname "$0")" && pwd)"
BIN="$HERE/../phase2/out/ptpd2"
CFG="$HERE/leader.conf"
[[ -f "$BIN" ]] || { echo "missing $BIN - run phase2 build first" >&2; exit 1; }

"${ADB[@]}" push "$BIN" /data/local/tmp/ptpd2 >/dev/null
"${ADB[@]}" push "$CFG" /data/local/tmp/leader.conf >/dev/null
"${ADB[@]}" shell chmod 755 /data/local/tmp/ptpd2

echo "starting leader ptpd2 (Ctrl-C to stop)..."
"${ADB[@]}" shell su -c '/data/local/tmp/ptpd2 -c /data/local/tmp/leader.conf -V'
