#!/usr/bin/env bash
# Shared helpers for scripts/*.sh. Source this, don't execute it directly.

SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/home/bobi/Android/Sdk}}"
AVD_NAME="${AVD_NAME:-taxi-inspector-api35}"

ADB="$SDK_DIR/platform-tools/adb"
EMULATOR="$SDK_DIR/emulator/emulator"

# The system default `java` here is a stale JDK 8 whose cert store can't
# validate services.gradle.org, breaking the wrapper download. Prefer Android
# Studio's bundled JBR (documented in docs/development-environment.md) unless
# the caller already set JAVA_HOME.
JBR_HOME="/opt/android-studio-for-platform/jbr"
if [[ -z "${JAVA_HOME:-}" && -x "$JBR_HOME/bin/java" ]]; then
  export JAVA_HOME="$JBR_HOME"
fi

require_sdk_tools() {
  if [[ ! -x "$ADB" || ! -x "$EMULATOR" ]]; then
    echo "Android SDK tools not found under $SDK_DIR (set ANDROID_HOME to override)." >&2
    exit 1
  fi
}

running_emulator_serial() {
  "$ADB" devices | awk '/^emulator-/ && /device$/ {print $1; exit}'
}

# Starts the AVD if none is running, waits for boot to complete, and prints
# the device serial on stdout (diagnostics go to stderr).
ensure_emulator_running() {
  require_sdk_tools
  local serial
  serial="$(running_emulator_serial)"

  if [[ -z "$serial" ]]; then
    echo "Starting emulator '$AVD_NAME'..." >&2
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

    echo "Waiting for '$serial' to finish booting..." >&2
    "$ADB" -s "$serial" wait-for-device
    until [[ "$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
      sleep 2
    done
  else
    echo "Using already-running emulator '$serial'." >&2
  fi

  echo "$serial"
}
