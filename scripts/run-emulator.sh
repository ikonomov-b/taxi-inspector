#!/usr/bin/env bash
# Builds and installs the debug app on a running emulator, starting one if needed.
set -euo pipefail

cd "$(dirname "$0")/.."
source scripts/lib.sh

APP_ID="com.taxiinspector"
MAIN_ACTIVITY="com.taxiinspector.MainActivity"

serial="$(ensure_emulator_running)"

echo "Building and installing debug APK..."
./gradlew installDebug

echo "Launching $APP_ID..."
"$ADB" -s "$serial" shell am start -n "$APP_ID/$MAIN_ACTIVITY"
