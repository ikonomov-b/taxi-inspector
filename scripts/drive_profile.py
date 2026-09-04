#!/usr/bin/env python3
"""Feeds a realistic ~60s urban driving profile into the emulator's GPS
provider via the console `geo fix` command: pull away, cruise, slow for a
red light, sit still, pull away again, cruise, and stop. This is a pure
physics simulation of real-world movement -- it has no knowledge of how the
app interprets GPS fixes, and would drive any app the same way.
"""
import argparse
import subprocess
import time

METERS_PER_DEGREE_LAT = 111_320.0

# (duration_seconds, start_speed_mps, end_speed_mps), sampled at 1 Hz to
# match a real GPS receiver's typical fix rate.
PROFILE = [
    (5, 0.0, 10.0),    # pull away from the curb
    (10, 10.0, 10.0),  # cruise (~36 km/h)
    (5, 10.0, 0.0),    # slow for a red light
    (15, 0.0, 0.0),    # stopped at the light
    (5, 0.0, 10.0),    # pull away again
    (10, 10.0, 10.0),  # cruise
    (5, 10.0, 0.0),    # slow to the destination
    (5, 0.0, 0.0),     # stopped, ride over
]


def geo_fix(serial, lon, lat):
    subprocess.run(
        ["adb", "-s", serial, "emu", "geo", "fix", f"{lon:.7f}", f"{lat:.7f}"],
        check=True, capture_output=True,
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--start-lat", type=float, default=42.6977)
    parser.add_argument("--start-lon", type=float, default=23.3219)
    args = parser.parse_args()

    lat, lon = args.start_lat, args.start_lon
    total = sum(duration for duration, _, _ in PROFILE)
    elapsed = 0
    for duration, v0, v1 in PROFILE:
        for step in range(duration):
            speed = v0 + (v1 - v0) * (step + 1) / duration
            lat += speed / METERS_PER_DEGREE_LAT
            geo_fix(args.serial, lon, lat)
            elapsed += 1
            print(f"[{elapsed:>2}/{total}s] speed={speed:4.1f} m/s")
            time.sleep(1)


if __name__ == "__main__":
    main()
