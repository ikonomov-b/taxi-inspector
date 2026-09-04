#!/usr/bin/env bash
# Builds and installs the debug app on a running emulator, starting one if needed.
set -euo pipefail

cd "$(dirname "$0")/.."

SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/home/bobi/Android/Sdk}}"
AVD_NAME="${AVD_NAME:-taxi-inspector-api35}"
APP_ID="com.taxiinspector"
MAIN_ACTIVITY="com.taxiinspector.MainActivity"

ADB="$SDK_DIR/platform-tools/adb"
EMULATOR="$SDK_DIR/emulator/emulator"

JBR_HOME="/opt/android-studio-for-platform/jbr"
if [[ -z "${JAVA_HOME:-}" && -x "$JBR_HOME/bin/java" ]]; then
  export JAVA_HOME="$JBR_HOME"
fi

if [[ ! -x "$ADB" || ! -x "$EMULATOR" ]]; then
  echo "Android SDK tools not found under $SDK_DIR (set ANDROID_HOME to override)." >&2
  exit 1
fi

running_emulator_serial() {
  "$ADB" devices | awk '/^emulator-/ && /device$/ {print $1; exit}'
}

serial="$(running_emulator_serial)"

if [[ -z "$serial" ]]; then
  echo "Starting emulator '$AVD_NAME'..."
  "$EMULATOR" -avd "$AVD_NAME" -gpu swiftshader_indirect -no-snapshot >/tmp/taxi-inspector-emulator.log 2>&1 &
  disown

  for _ in $(seq 1 60); do
    serial="$(running_emulator_serial)"
    [[ -n "$serial" ]] && break
    sleep 2
  done

  if [[ -z "$serial" ]]; then
    echo "Emulator did not show up in 'adb devices' in time. See /tmp/taxi-inspector-emulator.log" >&2
    exit 1
  fi

  echo "Waiting for '$serial' to finish booting..."
  "$ADB" -s "$serial" wait-for-device
  until [[ "$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
    sleep 2
  done
else
  echo "Using already-running emulator '$serial'."
fi

echo "Building and installing debug APK..."
./gradlew installDebug

echo "Launching $APP_ID..."
"$ADB" -s "$serial" shell am start -n "$APP_ID/$MAIN_ACTIVITY"
