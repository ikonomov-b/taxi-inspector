#!/usr/bin/env bash
# Pulls every debug ride trace off a connected device into ./traces/ for analysis.
#
# Each ride is one directory holding track.gpx (every raw fix with its coordinates and the
# engine's verdict on it), decisions.csv (the same as a table, including refused fixes) and
# meta.json (the locked tariff, the engine constants, the build and the device).
set -euo pipefail

cd "$(dirname "$0")/.."
source scripts/lib.sh
serial="$(resolve_target_serial)"

PACKAGE="com.taxiinspector"
echo "Target device: $serial" >&2
REMOTE="/sdcard/Android/data/$PACKAGE/files/traces"
LOCAL="${1:-traces}"

if ! "$ADB" -s "$serial" shell "[ -d '$REMOTE' ]" 2>/dev/null; then
  echo "No trace directory on the device. Is this a debug build with tracing switched on?" >&2
  echo "Run scripts/trace-toggle.sh on, then record a ride." >&2
  exit 1
fi

mkdir -p "$LOCAL"
"$ADB" -s "$serial" pull "$REMOTE/." "$LOCAL" >/dev/null
echo "Pulled into $LOCAL/:"
find "$LOCAL" -name 'track.gpx' -printf '  %h\n' | sort
