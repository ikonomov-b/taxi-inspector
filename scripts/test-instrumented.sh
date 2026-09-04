#!/usr/bin/env bash
# Runs the Room/GPS/service instrumentation suite on the emulator, starting
# one if needed (see docs/build-status.md for the tests this covers).
set -euo pipefail

cd "$(dirname "$0")/.."
source scripts/lib.sh

ensure_emulator_running >/dev/null

echo "Running connectedDebugAndroidTest..."
./gradlew --no-daemon connectedDebugAndroidTest
