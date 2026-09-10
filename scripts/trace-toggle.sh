#!/usr/bin/env bash
# Turns debug ride tracing on or off on a connected device.
#
# Tracing is off by default even on a debug build, because a trace records coordinates. The
# switch is a marker file in the app's own external files directory, so it survives a reboot and
# needs no UI. A release build has the facility compiled out and cannot be switched on at all.
set -euo pipefail

cd "$(dirname "$0")/.."
source scripts/lib.sh
serial="$(resolve_target_serial)"

PACKAGE="com.taxiinspector"
echo "Target device: $serial" >&2
TRACE_DIR="/sdcard/Android/data/$PACKAGE/files/traces"
SWITCH="$TRACE_DIR/.tracing-enabled"

usage() {
  echo "usage: $(basename "$0") on|off|status" >&2
  exit 2
}

[[ $# -eq 1 ]] || usage

case "$1" in
  on)
    "$ADB" -s "$serial" shell "mkdir -p '$TRACE_DIR' && echo 'Delete this file to stop recording ride traces.' > '$SWITCH'"
    echo "Tracing ON. Every ride started from now writes $TRACE_DIR/<rideId>/."
    ;;
  off)
    "$ADB" -s "$serial" shell "rm -f '$SWITCH'"
    echo "Tracing OFF. Existing traces are kept; scripts/pull-traces.sh collects them."
    ;;
  status)
    if "$ADB" -s "$serial" shell "[ -f '$SWITCH' ]" 2>/dev/null; then
      echo "Tracing ON"
    else
      echo "Tracing OFF"
    fi
    "$ADB" -s "$serial" shell "ls '$TRACE_DIR' 2>/dev/null | grep -v '^\.' | wc -l" \
      | tr -d '\r' | xargs -I{} echo "Traces stored on device: {}"
    ;;
  *) usage ;;
esac
