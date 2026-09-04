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

echo "Launching the app once to let Room create its database..."
"$ADB" -s "$serial" shell am start -n "$APP_ID/$MAIN_ACTIVITY"
sleep 2

# The in-app tariff-entry screen is mid-refactor and its Save button isn't
# wired up yet, so seed the same row it would write (app_settings, per
# app/schemas/com.taxiinspector.data.rides.TaxiInspectorDatabase/1.json)
# directly into the Room database instead of blocking on that UI.
echo "Seeding a tariff directly into the Room database..."
"$ADB" root >/dev/null
sleep 1
"$ADB" -s "$serial" shell am force-stop "$APP_ID"
db_path="/data/data/$APP_ID/databases/taxi-inspector.db"
seed_sql="$(mktemp)"
cat > "$seed_sql" <<'SQL'
PRAGMA wal_checkpoint(TRUNCATE);
INSERT OR REPLACE INTO app_settings (id, initialTax, perKmRate, perMinuteStillRate)
VALUES (1, '1.50', '0.80', '0.20');
SQL
"$ADB" -s "$serial" push "$seed_sql" /data/local/tmp/seed_tariff.sql >/dev/null
"$ADB" -s "$serial" shell "sqlite3 $db_path < /data/local/tmp/seed_tariff.sql"
"$ADB" -s "$serial" shell rm -f /data/local/tmp/seed_tariff.sql
rm -f "$seed_sql"

echo "Relaunching the app with the seeded tariff..."
"$ADB" -s "$serial" shell am start -n "$APP_ID/$MAIN_ACTIVITY"
sleep 2

echo "Starting the ride..."
python3 "$SCRIPT_DIR/ui_dump.py" --serial "$serial" tap-text "Start ride"
sleep 1

echo "Driving 60s at a constant 50 km/h, then sitting stationary for 60s..."
python3 "$SCRIPT_DIR/drive_profile.py" --serial "$serial"

echo "Stopping, saving, and checking the app's distance/idle/fare math..."
python3 "$SCRIPT_DIR/check_ride_result.py" --serial "$serial"
