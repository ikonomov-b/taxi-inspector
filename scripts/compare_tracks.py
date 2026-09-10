#!/usr/bin/env python3
"""Compare a Taxi Inspector ride trace with an independent recording of the same trip.

The app's own view of a ride is in decisions.csv: every fix it received, with the verdict it
reached on each one. A reference recorder -- Locus Map, recording raw GPS with no filtering --
sees the same fixes without any billing rules applied. Aligning the two on wall-clock time shows
how much of the difference between "distance travelled" and "distance billed" each rule accounts
for, which is the whole question a field drive is meant to answer.

    scripts/compare_tracks.py traces/<rideId>/decisions.csv locus-export.gpx

Standard library only, so it runs wherever the traces were pulled.
"""

from __future__ import annotations

import argparse
import csv
import math
import sys
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from datetime import datetime, timezone

WGS84_A = 6378137.0
WGS84_F = 1 / 298.257223563


def geodesic_meters(lat1, lon1, lat2, lon2):
    """Vincenty inverse on WGS84, matching the app's own Geodesic, haversine on failure."""
    if (lat1, lon1) == (lat2, lon2):
        return 0.0
    b = WGS84_A * (1 - WGS84_F)
    difference = math.radians(lon2 - lon1)
    u1 = math.atan((1 - WGS84_F) * math.tan(math.radians(lat1)))
    u2 = math.atan((1 - WGS84_F) * math.tan(math.radians(lat2)))
    sin_u1, cos_u1 = math.sin(u1), math.cos(u1)
    sin_u2, cos_u2 = math.sin(u2), math.cos(u2)
    lam = difference
    for _ in range(200):
        sin_lam, cos_lam = math.sin(lam), math.cos(lam)
        sin_sigma = math.hypot(cos_u2 * sin_lam, cos_u1 * sin_u2 - sin_u1 * cos_u2 * cos_lam)
        if sin_sigma == 0:
            return 0.0
        cos_sigma = sin_u1 * sin_u2 + cos_u1 * cos_u2 * cos_lam
        sigma = math.atan2(sin_sigma, cos_sigma)
        sin_alpha = cos_u1 * cos_u2 * sin_lam / sin_sigma
        cos_sq_alpha = 1 - sin_alpha**2
        cos_2sm = 0.0 if cos_sq_alpha == 0 else cos_sigma - 2 * sin_u1 * sin_u2 / cos_sq_alpha
        c = WGS84_F / 16 * cos_sq_alpha * (4 + WGS84_F * (4 - 3 * cos_sq_alpha))
        previous = lam
        lam = difference + (1 - c) * WGS84_F * sin_alpha * (
            sigma + c * sin_sigma * (cos_2sm + c * cos_sigma * (-1 + 2 * cos_2sm**2))
        )
        if abs(lam - previous) < 1e-12:
            break
    else:
        return haversine_meters(lat1, lon1, lat2, lon2)
    u_sq = cos_sq_alpha * (WGS84_A**2 - b**2) / b**2
    a_coefficient = 1 + u_sq / 16384 * (4096 + u_sq * (-768 + u_sq * (320 - 175 * u_sq)))
    b_coefficient = u_sq / 1024 * (256 + u_sq * (-128 + u_sq * (74 - 47 * u_sq)))
    delta_sigma = (
        b_coefficient
        * sin_sigma
        * (
            cos_2sm
            + b_coefficient
            / 4
            * (
                cos_sigma * (-1 + 2 * cos_2sm**2)
                - b_coefficient / 6 * cos_2sm * (-3 + 4 * sin_sigma**2) * (-3 + 4 * cos_2sm**2)
            )
        )
    )
    return b * a_coefficient * (sigma - delta_sigma)


def haversine_meters(lat1, lon1, lat2, lon2):
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    d_phi = phi2 - phi1
    d_lam = math.radians(lon2 - lon1)
    a = math.sin(d_phi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(d_lam / 2) ** 2
    return 6371008.8 * 2 * math.asin(min(1.0, math.sqrt(a)))


def read_reference_track(path):
    """Track points from any GPX: (utc_millis, lat, lon), in time order."""
    points = []
    root = ET.parse(path).getroot()
    for point in root.iter():
        if not point.tag.endswith("trkpt"):
            continue
        time_element = next((c for c in point if c.tag.endswith("time")), None)
        if time_element is None or not time_element.text:
            continue
        stamp = time_element.text.strip().replace("Z", "+00:00")
        try:
            when = datetime.fromisoformat(stamp)
        except ValueError:
            continue
        if when.tzinfo is None:
            when = when.replace(tzinfo=timezone.utc)
        points.append(
            (
                int(when.timestamp() * 1000),
                float(point.attrib["lat"]),
                float(point.attrib["lon"]),
            )
        )
    points.sort(key=lambda row: row[0])
    return points


def read_app_rows(path):
    with open(path, newline="", encoding="utf-8") as handle:
        return [row for row in csv.DictReader(handle)]


def number(value, default=0.0):
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def raw_distance(points, start_millis=None, end_millis=None):
    total = 0.0
    previous = None
    for millis, lat, lon in points:
        if start_millis is not None and millis < start_millis:
            continue
        if end_millis is not None and millis > end_millis:
            continue
        if previous is not None:
            total += geodesic_meters(previous[0], previous[1], lat, lon)
        previous = (lat, lon)
    return total


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("decisions", help="decisions.csv from a pulled ride trace")
    parser.add_argument("reference", nargs="?", help="a GPX recorded independently of the app")
    parser.add_argument("--per-minute", action="store_true", help="tabulate minute by minute")
    args = parser.parse_args(argv)

    rows = read_app_rows(args.decisions)
    fixes = [row for row in rows if row["type"] == "Fix"]
    if not fixes:
        print("No fixes in this trace.", file=sys.stderr)
        return 1

    timed = [row for row in fixes if row["utcMillis"]]
    first_millis = int(timed[0]["utcMillis"]) if timed else None
    last_millis = int(timed[-1]["utcMillis"]) if timed else None
    billed_distance = number(rows[-1]["distanceM"])
    billed_time_ms = number(rows[-1]["timeMs"])

    app_points = [
        (int(row["utcMillis"]), number(row["lat"]), number(row["lon"]))
        for row in timed
        if row["lat"] and row["lon"]
    ]
    app_raw = raw_distance(app_points)

    print(f"fixes received         {len(fixes)}")
    print(f"app raw path           {app_raw:9.0f} m   (sum of every fix-to-fix step)")
    print(f"app billed distance    {billed_distance:9.0f} m")
    print(f"app billed time        {billed_time_ms / 1000:9.0f} s")
    print(f"final total            {rows[-1]['total']:>9}")

    reference_points = []
    if args.reference:
        reference_points = read_reference_track(args.reference)
        overlap = raw_distance(reference_points, first_millis, last_millis)
        print(f"reference points       {len(reference_points)}")
        print(f"reference raw path     {overlap:9.0f} m   (same time window)")
        if overlap:
            print(f"billed / reference     {billed_distance / overlap:9.2%}")
        # The reference over-reads while stationary because it sums raw jitter with no
        # deadband at all, so the two only compare fairly over intervals that moved.

    print()
    print("reception (compare one phone placement with another)")
    accuracies = [number(r["accuracyM"]) for r in fixes if r["accuracyM"]]
    if accuracies:
        accuracies.sort()
        billable = sum(1 for a in accuracies if a <= 20)
        print(f"  accuracy  min/median/max   {accuracies[0]:.0f} / "
              f"{accuracies[len(accuracies) // 2]:.0f} / {accuracies[-1]:.0f} m")
        print(f"  fixes at or under 20 m     {billable} of {len(accuracies)}"
              f" ({billable / len(accuracies):.0%}) -- only these can bill")
    for label, column in (("used in fix", "usedInFix"), ("in view", "inView")):
        counts = [number(r[column]) for r in fixes if r.get(column)]
        if counts:
            print(f"  satellites {label:<12}   min {min(counts):.0f}  median "
                  f"{sorted(counts)[len(counts) // 2]:.0f}  max {max(counts):.0f}")
    # Carrier-to-noise density is the metric that separates a good mount from a bad one. A few
    # dB here is the difference between an athermic windscreen and its transponder patch.
    for label, column in (("used", "cn0Used"), ("in view", "cn0View")):
        values = [number(r[column]) for r in fixes if r.get(column)]
        if values:
            values.sort()
            print(f"  C/N0 {label:<8} dB-Hz     min {values[0]:.1f}  median "
                  f"{values[len(values) // 2]:.1f}  max {values[-1]:.1f}")
    bands = Counter(r["band"] for r in fixes if r.get("band"))
    if bands:
        print("  band                       " + "  ".join(f"{b}={n}" for b, n in bands.most_common()))

    print()
    print("what the engine did with each fix")
    print("  reason                fixes   chord it measured but did not bill as distance")
    counts = Counter(row["reason"] for row in fixes)
    withheld = defaultdict(float)
    for row in fixes:
        if row["billedAs"] != "Distance":
            withheld[row["reason"]] += number(row["chordM"])
    for reason, count in counts.most_common():
        # A refused fix has no chord: the engine never measured from it, and the next accepted
        # fix bridges the whole interval, so nothing is missing on its account.
        metres = f"{withheld[reason]:8.0f} m" if withheld[reason] else "       --"
        print(f"  {reason:<20} {count:5}   {metres}")

    if args.per_minute and timed:
        print()
        print("minute  reference m   billed m")
        minute_start = first_millis
        while minute_start <= last_millis:
            minute_end = minute_start + 60_000
            in_window = [row for row in timed if minute_start <= int(row["utcMillis"]) < minute_end]
            if in_window:
                billed_delta = number(in_window[-1]["distanceM"]) - number(in_window[0]["distanceM"])
                reference_delta = raw_distance(reference_points, minute_start, minute_end)
                index = (minute_start - first_millis) // 60_000
                print(f"{index:6}  {reference_delta:11.0f}  {billed_delta:9.0f}")
            minute_start = minute_end
    return 0


if __name__ == "__main__":
    sys.exit(main())
