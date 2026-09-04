#!/usr/bin/env bash
# Runs the Room/GPS/service instrumentation suite on the emulator, starting
# one if needed (see docs/build-status.md for the tests this covers).
set -euo pipefail

cd "$(dirname "$0")/.."
source scripts/lib.sh

serial="$(ensure_emulator_running)"

echo "Running connectedDebugAndroidTest on $serial..."
# Pinned deliberately: connectedDebugAndroidTest enrols every attached device, so a
# phone plugged in for field testing would otherwise join the run and fail it.
ANDROID_SERIAL="$serial" ./gradlew --no-daemon connectedDebugAndroidTest
