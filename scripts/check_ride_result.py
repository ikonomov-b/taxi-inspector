#!/usr/bin/env python3
"""Taps "Stop & save" to finish the active ride, then checks what the app
persisted (app/schemas/.../ride_summary) against what scripts/drive_profile.py's
deterministic GPS feed should produce under RideEngine's own rules
(app/src/main/java/com/taxiinspector/ride/RideEngine.kt):

- distanceMeters only accrues from the *second* accepted fix onward -- the
  first fix after Start always seeds the baseline for free (RideEngine.kt's
  `lastBillablePoint == null` branch) -- so a --drive-seconds leg at
  --speed-kmh contributes (drive_seconds - 1) * speed_mps metres, regardless
  of any individual fix getting throttled/dropped along the way (each
  accepted fix's segment is measured from whatever the last accepted
  baseline was, so distance along a straight line telescopes to the same
  total either way).
- idleMillis accrues once the vehicle has been slower than IDLE_ENTRY_SPEED
  (0.8 m/s) for a qualifying IDLE_ENTRY_MILLIS (5s, billed as 0 while still
  "Moving"), then 1ms per elapsed ms for the rest of the stationary leg --
  so the expected value is derived from the *actual* wall-clock duration of
  that leg (recorded by drive_profile.py) plus the time this script's own
  "Stop & save" tap took to land, not the nominal --stop-seconds, since
  adb/UI-automation overhead makes those differ by a second or more.
- the persisted `total` fare is independently recomputed from the app's own
  reported distanceMeters/idleMillis and tariff rates (FareCalculator.kt),
  as a check that persistence/rounding didn't corrupt it in transit.

Exits non-zero if any of the three checks falls outside tolerance.
"""
import argparse
import json
import subprocess
import sys
import time
from decimal import Decimal, ROUND_HALF_UP, getcontext
from pathlib import Path

IDLE_ENTRY_MILLIS = 5_000  # RideEngine.IDLE_ENTRY_MILLIS
SCRIPT_DIR = Path(__file__).resolve().parent


def adb(serial, *args):
    return subprocess.run(
        ["adb", "-s", serial, *args], check=True, capture_output=True, text=True
    )


def tap_stop_and_save(serial):
    before = time.time()
    subprocess.run(
        ["python3", str(SCRIPT_DIR / "ui_dump.py"), "--serial", serial, "tap-text", "Stop & save"],
        check=True,
    )
    after = time.time()
    return (before + after) / 2


def latest_ride_summary(serial, app_id):
    db_path = f"/data/data/{app_id}/databases/taxi-inspector.db"
    query = (
        "SELECT id, initialTax, perKmRate, perMinuteStillRate, total, "
        "distanceMeters, idleMillis, elapsedMillis, endedElapsedMillis, "
        "endedAtUtcMillis, status FROM ride_summary "
        "ORDER BY endedAtUtcMillis DESC, id DESC LIMIT 1;"
    )
    result = adb(serial, "shell", f"sqlite3 {db_path} \"{query}\"")
    line = result.stdout.strip()
    if not line:
        sys.exit("No ride_summary row found -- did Stop & save actually save the ride?")
    fields = line.split("|")
    columns = [
        "id", "initialTax", "perKmRate", "perMinuteStillRate", "total",
        "distanceMeters", "idleMillis", "elapsedMillis", "endedElapsedMillis",
        "endedAtUtcMillis", "status",
    ]
    return dict(zip(columns, fields))


def expected_total(row):
    getcontext().prec = 50
    eighteen_places = Decimal(1).scaleb(-18)

    distance_fare = Decimal(row["perKmRate"]) * (
        Decimal(row["distanceMeters"]) / Decimal(1000)
    ).quantize(eighteen_places, rounding=ROUND_HALF_UP)
    idle_fare = Decimal(row["perMinuteStillRate"]) * (
        Decimal(row["idleMillis"]) / Decimal(60_000)
    ).quantize(eighteen_places, rounding=ROUND_HALF_UP)
    return Decimal(row["initialTax"]) + distance_fare + idle_fare


def report(label, expected, actual, tolerance, unit):
    diff = abs(actual - expected)
    passed = diff <= tolerance
    status = "PASS" if passed else "FAIL"
    print(f"[{status}] {label}: expected={expected:.4f}{unit} actual={actual:.4f}{unit} "
          f"diff={diff:.4f}{unit} (tolerance={tolerance:.4f}{unit})")
    return passed


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--app-id", default="com.taxiinspector")
    parser.add_argument("--timestamps", default="/tmp/taxi-inspector-drive-profile.json")
    # A few metres beyond the ~1-fix warm-up cost already netted out of
    # expected_distance_m: in practice the GPS provider typically takes an
    # extra fix or two to deliver its first callback after Start (real
    # receivers have the same acquisition latency), silently absorbing
    # another step's worth of distance. 40m (~5% of the default profile's
    # 819m) comfortably covers that without masking an actual regression --
    # the run that motivated this test undercounted distance by >90%.
    parser.add_argument("--distance-tolerance-meters", type=float, default=40.0)
    parser.add_argument("--idle-tolerance-ms", type=float, default=3_000)
    parser.add_argument("--fare-tolerance", type=float, default=1e-9)
    args = parser.parse_args()

    profile = json.loads(Path(args.timestamps).read_text())

    print("Tapping 'Stop & save'...")
    stop_tap_epoch = tap_stop_and_save(args.serial)
    time.sleep(1)  # let the save transaction land before reading it back

    adb(args.serial, "root")
    time.sleep(1)
    row = latest_ride_summary(args.serial, args.app_id)

    expected_distance_m = (profile["drive_seconds"] - 1) * profile["speed_mps"]
    actual_distance_m = float(row["distanceMeters"])

    stationary_elapsed_s = stop_tap_epoch - profile["stationary_start_epoch"]
    expected_idle_ms = max(0.0, stationary_elapsed_s * 1000 - IDLE_ENTRY_MILLIS)
    actual_idle_ms = float(row["idleMillis"])

    expected_fare_total = expected_total(row)
    actual_fare_total = Decimal(row["total"])

    print(f"Ride {row['id']} ({row['status']}): "
          f"distanceMeters={row['distanceMeters']} idleMillis={row['idleMillis']} "
          f"elapsedMillis={row['elapsedMillis']} total={row['total']}")

    passed = True
    passed &= report(
        "distance", expected_distance_m, actual_distance_m,
        args.distance_tolerance_meters, "m",
    )
    passed &= report(
        "idle time", expected_idle_ms, actual_idle_ms,
        args.idle_tolerance_ms, "ms",
    )
    fare_diff = abs(actual_fare_total - expected_fare_total)
    fare_passed = fare_diff <= Decimal(str(args.fare_tolerance))
    print(f"[{'PASS' if fare_passed else 'FAIL'}] fare total: expected={expected_fare_total} "
          f"actual={actual_fare_total} diff={fare_diff}")
    passed &= fare_passed

    if not passed:
        sys.exit(1)
    print("All checks passed.")


if __name__ == "__main__":
    main()
