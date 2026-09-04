#!/usr/bin/env bash
# Builds a debug APK installable on a physical device such as a Pixel 8 Pro.
# If one is connected over adb it's installed there directly; otherwise the
# APK path is printed so it can be sideloaded manually (adb install, or a
# file transfer with "install from unknown sources" enabled).
set -euo pipefail

cd "$(dirname "$0")/.."
source scripts/lib.sh

require_sdk_tools

echo "Building debug APK..."
./gradlew assembleDebug

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -f "$APK_PATH" ]]; then
  echo "Expected APK not found at $APK_PATH" >&2
  exit 1
fi

echo "APK ready: $APK_PATH"

# First non-emulator serial in the "device" (fully authorized) state.
serial="$("$ADB" devices | awk '$1 !~ /^emulator-/ && $2 == "device" {print $1; exit}')"

if [[ -z "$serial" ]]; then
  cat <<EOF

No physical device connected over adb.
On the Pixel 8 Pro: Settings > About phone > tap "Build number" 7 times to
enable Developer options, then Settings > System > Developer options > USB
debugging. Connect over USB (or "adb connect <ip>:5555" for wireless
debugging) and accept the RSA fingerprint prompt, then either:
  adb install -r $APK_PATH
or copy the APK to the device and install it from a file manager with
"install from unknown sources" allowed for that app.
EOF
  exit 0
fi

model="$("$ADB" -s "$serial" shell getprop ro.product.model | tr -d '\r')"
if [[ "$model" != "Pixel 8 Pro" ]]; then
  echo "Note: connected device '$serial' reports model '$model', not a Pixel 8 Pro." >&2
fi

echo "Installing on connected device '$serial' ($model)..."
"$ADB" -s "$serial" install -r "$APK_PATH"
echo "Installed."
