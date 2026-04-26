#!/usr/bin/env bash
# Push ptpd2 + client.conf to a rooted Pixel 7 client and run.
# Usage: bash run_client.sh [adb-serial]
set -euo pipefail
SERIAL="${1:-}"
ADB=(adb)
[[ -n "$SERIAL" ]] && ADB+=(-s "$SERIAL")

HERE="$(cd "$(dirname "$0")" && pwd)"
BIN="$HERE/../phase2/out/ptpd2"
CFG="$HERE/client.conf"
[[ -f "$BIN" ]] || { echo "missing $BIN - run phase2 build first" >&2; exit 1; }

"${ADB[@]}" push "$BIN" /data/local/tmp/ptpd2 >/dev/null
"${ADB[@]}" push "$CFG" /data/local/tmp/client.conf >/dev/null
"${ADB[@]}" shell chmod 755 /data/local/tmp/ptpd2

echo "starting client ptpd2 (Ctrl-C to stop)..."
"${ADB[@]}" shell su -c '/data/local/tmp/ptpd2 -c /data/local/tmp/client.conf -V'
