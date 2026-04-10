#!/usr/bin/env bash
# Build debug APK and archive a timestamped copy into apks/ so previous
# builds are preserved across rebuilds.
#
# Usage:
#   ./scripts/build.sh              # build only
#   ./scripts/build.sh --install    # build + install on connected device

set -e
cd "$(dirname "$0")/.."

./gradlew assembleDebug "$@"

APK="app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK" ]; then
    echo "WARNING: $APK not found"
    exit 0
fi

mkdir -p apks
TIMESTAMP=$(date +"%m%d%H_%H%M")
DEST="apks/${TIMESTAMP}.apk"
cp "$APK" "$DEST"
echo "Archived APK → $DEST"

if [ "$1" = "--install" ]; then
    ADB="${ANDROID_HOME:-$HOME/AppData/Local/Android/Sdk}/platform-tools/adb"
    "$ADB" install -r "$APK"
fi
