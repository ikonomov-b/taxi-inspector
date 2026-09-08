#!/usr/bin/env bash
# Black-box simulation of a ~2 minute taxi ride: installs a clean copy of the
# app, enters a tariff, starts a ride, feeds a deterministic GPS path (drive
# 60s at a constant 50 km/h, then sit stationary for 60s) into the emulator's
# real GPS provider, stops & saves, then checks the distance/idle time/fare
# the app persisted against what RideEngine's own rules predict for that
# exact profile (see scripts/check_ride_result.py) -- failing loudly if the
# app's math doesn't match, rather than just eyeballing the final screen.
#
# This drives only the real UI (via uiautomator) and the real GPS provider
# (via the emulator console) -- it never calls the app's Kotlin code
# directly, so it exercises the full on-device stack the way a real driver
# and a real GPS receiver would.
set -euo pipefail

cd "$(dirname "$0")/.."
source scripts/lib.sh

APP_ID="com.taxiinspector"
MAIN_ACTIVITY="com.taxiinspector.MainActivity"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

serial="$(ensure_emulator_running)"

echo "Installing a clean copy of the app..."
./gradlew installDebug
"$ADB" -s "$serial" shell pm clear "$APP_ID" >/dev/null

echo "Granting permissions (so no system dialog blocks automation)..."
"$ADB" -s "$serial" shell pm grant "$APP_ID" android.permission.ACCESS_FINE_LOCATION
sdk_version="$("$ADB" -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')"
if [[ "$sdk_version" -ge 33 ]]; then
  "$ADB" -s "$serial" shell pm grant "$APP_ID" android.permission.POST_NOTIFICATIONS
fi

# Restarted as root here, before any UI automation, only so
# check_ride_result.py can read the saved summary back out of the app's
# private database at the end; adbd restarting mid-run would break the taps.
"$ADB" root >/dev/null
sleep 1

echo "Launching the app; a clean install opens on the company editor..."
"$ADB" -s "$serial" shell am start -n "$APP_ID/$MAIN_ACTIVITY"
sleep 2

# Typed into the real company editor rather than seeded into Room. The tariff
# belongs to a saved taxi company instead of the app_settings row, so a direct
# insert would have to fabricate a company row and a selection; typing it
# exercises the same path a user takes and works without root on a phone. A
# clean install opens this editor itself, and the first company saved becomes
# the selection, so Start is available as soon as it is saved.
echo "Entering a company and its rates through the app's own editor..."
python3 "$SCRIPT_DIR/ui_dump.py" --serial "$serial" fill "Company name" "Simulated Taxi"
python3 "$SCRIPT_DIR/ui_dump.py" --serial "$serial" fill "Initial tax" "1.50"
python3 "$SCRIPT_DIR/ui_dump.py" --serial "$serial" fill "Per km rate" "0.80"
python3 "$SCRIPT_DIR/ui_dump.py" --serial "$serial" fill "Per minute car-still rate" "0.20"
# The editor is taller than the old tariff screen, so Save sits under the
# keyboard until it is dismissed; uiautomator cannot tap what the IME covers.
python3 "$SCRIPT_DIR/ui_dump.py" --serial "$serial" dismiss-keyboard
python3 "$SCRIPT_DIR/ui_dump.py" --serial "$serial" tap-text "Save company"
sleep 2

echo "Starting the ride..."
python3 "$SCRIPT_DIR/ui_dump.py" --serial "$serial" tap-text "Start ride"
sleep 1

echo "Driving 60s at a constant 50 km/h, then sitting stationary for 60s..."
python3 "$SCRIPT_DIR/drive_profile.py" --serial "$serial"

echo "Stopping, saving, and checking the app's distance/idle/fare math..."
python3 "$SCRIPT_DIR/check_ride_result.py" --serial "$serial"
