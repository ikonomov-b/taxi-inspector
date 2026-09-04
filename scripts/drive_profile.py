#!/usr/bin/env python3
"""Feeds a deterministic driving profile into the emulator's GPS provider via
the console `geo fix` command: a constant-speed leg (default 50 km/h) for a
fixed duration, then a stationary leg for a fixed duration. This is a pure
physics simulation of real-world movement -- it has no knowledge of how the
app interprets GPS fixes, and would drive any app the same way.

Unlike a hand-tuned "realistic" accelerate/cruise/decelerate ramp, every leg
here is a plain step function, so the traveled distance and stopped duration
are exactly known and a caller can check the app's math against them (see
scripts/check_ride_result.py, which predicts the app's expected
distanceMeters/idleMillis from this profile plus RideEngine's own rules --
app/src/main/java/com/taxiinspector/ride/RideEngine.kt -- then compares them
against what actually got persisted).

Latitude steps are derived from RideEngine's own haversine formula (mean
Earth radius 6,371,000 m -- RideEngine.kt's EARTH_RADIUS_METERS) rather than
a separate "meters per degree" approximation, so the app's computed segment
distance for each step matches the intended distance to float precision
instead of differing by the ~0.1% two different approximations would
disagree by.

Every fix carries an explicit velocity (the console's `geo fix` takes it as
an optional final argument, in knots -- `help geo fix` on the emulator
console). Omitting it, as earlier versions of this script did, makes the
emulator report a native GPS speed of exactly 0 on every fix; RideEngine's
AndroidGpsLocationClient prefers that native speed over its own
position-delta-derived speed whenever the platform Location reports one
(Location.hasSpeed() is true even for an explicit 0), so without this the
engine sees 0 m/s regardless of real movement and calls itself Idle within
IDLE_ENTRY_MILLIS (5s) of Start, no matter how fast the injected fixes
actually move -- a test-harness gap, not an app bug, but one that silently
made every prior distance/idle measurement meaningless.

Writes phase boundary timestamps to --timestamps-out as JSON so
check_ride_result.py can compute the expected idle time from the *actual*
wall-clock duration of the stationary leg (recorded here) rather than the
nominal --stop-seconds, since adb/UI-automation overhead means those two
differ by a second or more in practice.
"""
import argparse
import json
import math
import subprocess
import time

EARTH_RADIUS_METERS = 6_371_000.0  # matches RideEngine.EARTH_RADIUS_METERS
METERS_PER_SECOND_PER_KNOT = 0.514444
SATELLITES = 8  # arbitrary but >0, so the fix doesn't look satellite-starved


def geo_fix(serial, lon, lat, speed_mps):
    speed_knots = speed_mps / METERS_PER_SECOND_PER_KNOT
    subprocess.run(
        ["adb", "-s", serial, "emu", "geo", "fix",
         f"{lon:.7f}", f"{lat:.7f}", "0", str(SATELLITES), f"{speed_knots:.4f}"],
        check=True, capture_output=True,
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--start-lat", type=float, default=42.6977)
    parser.add_argument("--start-lon", type=float, default=23.3219)
    parser.add_argument("--speed-kmh", type=float, default=50.0)
    parser.add_argument("--drive-seconds", type=int, default=60)
    parser.add_argument("--stop-seconds", type=int, default=60)
    parser.add_argument("--timestamps-out", default="/tmp/taxi-inspector-drive-profile.json")
    args = parser.parse_args()

    speed_mps = args.speed_kmh * 1000.0 / 3600.0
    per_step_meters = speed_mps  # 1 Hz sampling: one step == one second of travel
    lat_degrees_per_meter = math.degrees(1.0 / EARTH_RADIUS_METERS)
    lat, lon = args.start_lat, args.start_lon

    print(f"Driving {args.drive_seconds}s at a constant {args.speed_kmh:.1f} km/h ({speed_mps:.4f} m/s)...")
    for step in range(1, args.drive_seconds + 1):
        lat += per_step_meters * lat_degrees_per_meter
        geo_fix(args.serial, lon, lat, speed_mps)
        print(f"[drive {step:>2}/{args.drive_seconds}s] speed={speed_mps:5.2f} m/s")
        time.sleep(1)

    stationary_start_epoch = time.time()
    print(f"Stopped. Holding position for {args.stop_seconds}s...")
    for step in range(1, args.stop_seconds + 1):
        geo_fix(args.serial, lon, lat, 0.0)
        print(f"[stop {step:>2}/{args.stop_seconds}s] speed= 0.00 m/s")
        time.sleep(1)
    stationary_end_epoch = time.time()

    with open(args.timestamps_out, "w") as f:
        json.dump({
            "speed_kmh": args.speed_kmh,
            "speed_mps": speed_mps,
            "drive_seconds": args.drive_seconds,
            "stop_seconds": args.stop_seconds,
            "stationary_start_epoch": stationary_start_epoch,
            "stationary_end_epoch": stationary_end_epoch,
            "final_lat": lat,
            "final_lon": lon,
        }, f)
    print(f"Wrote phase timestamps to {args.timestamps_out}")


if __name__ == "__main__":
    main()
