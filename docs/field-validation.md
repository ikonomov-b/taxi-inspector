# Taxi Inspector — Field Validation with a Debug Ride Trace

## What this is for

The engine bills at or below what an approved mode-S taximeter would charge, by construction. What
nobody has measured on real hardware is *how far* below, and how much of the difference each rule
accounts for. The debug ride trace answers that: it records every fix the app received, its
coordinates, and the verdict the engine reached on it, so a recorded trip can be compared against
an independent recording of the same trip and, with a reference meter in the car, against a real
fare.

The open question the traces exist to settle is in
`gps-fidelity-inspection-and-plan.md`, section 4.2: summing chords between noisy fixes over-reads
distance whether the vehicle moves or not, and how much depends on how strongly consecutive GNSS
errors are correlated on this hardware. A stationary ten-minute hold measures it directly.

## Tracing is off until you switch it on

A trace holds coordinates, so nothing collects them by default.

- A **release** build has the facility compiled out (`RIDE_TRACE_ENABLED = false`). It cannot be
  switched on, and it stores no route at all. That is the documented privacy contract.
- A **debug** build can trace, but does not until the switch is raised. The switch is a marker
  file, `.tracing-enabled` in the app's own external files directory, so it can be flipped over
  adb without opening the app and it survives a reboot — a field trip that goes unrecorded
  because a toggle reset itself is the one failure this facility cannot afford.

```sh
scripts/build-device-apk.sh          # installs the debug build
# Open the app once first: it has to create its own storage before the switch can go into it.
# The script refuses rather than creating that directory itself, because a directory created
# over adb belongs to shell and the app cannot then read the switch inside it.
scripts/trace-toggle.sh on           # raise the flag
scripts/trace-toggle.sh status       # confirm, and count what is stored
```

If a ride records nothing, `adb logcat -s TaxiTrace` says why — tracing off, a directory it could
not create, or a failed write. It never logs a coordinate.

Each ride then writes `traces/<rideId>/`:

| File | What it holds |
| --- | --- |
| `track.gpx` | Every raw fix as a GPX 1.1 track point: coordinates, elevation, UTC time, and the engine's verdict in a namespaced `<extensions>` block. Refused fixes are on the track too, so a dropout is visible rather than absent. |
| `decisions.csv` | The same events as a table, one row per fix, per status- or label-changing tick, and per command, with the reason code, the chord, the deadband, and the running totals. |
| `meta.json` | The locked tariff, its cross-over speed, every engine constant in force, the app version, the device and the start time. A trace replayed against different constants is not the same measurement. |

The newest thirty rides are kept; older traces are pruned at ride start. Discarding a ride deletes
its trace, and deleting a saved ride from Ride Detail deletes it too.

## Getting the files off the phone

```sh
scripts/pull-traces.sh               # adb pull into ./traces/
```

Or share them from the phone: **History → a ride → Share GPX track** hands the GPX straight to
Locus Map, which offers to import it as a track. **Share full trace** sends all three files
together, for mail or a file manager. Both go through the app's own `FileProvider`, so the
receiving app gets a one-off read grant and nothing else is exposed.

## The stationary hold — do this one first

It needs no drive, no reference meter and no second recording, and it measures the largest open
uncertainty in the engine.

1. Save a company with the test tariff `2.40 / 1.20 / 0.35`, so the numbers line up with the
   probe table in section 4.2 of the plan.
2. `adb shell setprop log.tag.TaxiGnss DEBUG` and `adb logcat -s TaxiGnss > hold.log`, so the
   accuracy the receiver reported is recorded alongside.
3. Start a ride with the phone still, screen on, for ten minutes. Stop & save.
4. Do it twice: once on a windowsill (open sky, expect about 4.5 m accuracy) and once behind a
   parked car's windscreen (expect 5–20 m, and note that athermic glass attenuates GNSS badly).

Read the billed distance off Ride Detail. **It should be under about 50 m, with the wait time
close to the full ten minutes.** Hundreds of metres means the noise floor in the deadband is not
enough on this hardware, and the wider budget discussed in section 4.2 becomes a real change
rather than a hypothetical one. `compare_tracks.py` on the pulled trace attributes it per rule.

## Comparing a drive with Locus Map

1. In Locus Map, record with **GPS only** — disable fused and network sources — a **1 s**
   interval, a **0 m** distance filter, and the accuracy filter **off**. The point is a raw track,
   not a smoothed one; a filtered Locus track is not a reference.
2. Start both recordings in the vehicle. Mount the phone in a windscreen cradle rather than a
   cupholder: placement matters more than any constant in the engine.
3. Drive. Stop & save in Taxi Inspector, then stop the Locus recording.
4. For a visual overlay, share the app's GPX into Locus and view both tracks together.
5. For numbers, export the Locus GPX and pull the trace:

```sh
scripts/pull-traces.sh
scripts/compare_tracks.py traces/<rideId>/decisions.csv locus-export.gpx --per-minute
```

The script reports the app's raw fix-to-fix path, what it billed, the reference path over the same
time window, and a reason histogram attributing every fix the engine did not bill as distance.

Two things to hold in mind when reading it:

- **Locus over-reads while stationary**, because it sums raw jitter with no deadband at all. Only
  intervals that actually moved compare fairly. That is also why the stationary hold is measured
  from the app's own billed distance rather than against Locus.
- **Locus is not the fare reference.** It measures the path. Only an approved taximeter settles
  whether the app's mode-S attribution matches a real one, and the reference meter's calculation
  mode and fare increment must be recorded on the same drive — that is Phase 8.3's open decision.

## What to record for each run

Per the plan's step 8.2: reference-meter fare, distance, time and calculation mode if known; the
app's total, distance and wait; signed and absolute error; and the outage buckets (uninterrupted,
then 5, 15, 30, 60 and 120 seconds). Note the phone placement and the accuracy distribution from
the `TaxiGnss` log with every run — a result without them cannot be compared with the next one.

## Replaying a trace offline

A captured `decisions.csv` can be driven back through the engine on a computer, with no device, to
see what a changed rule would have billed on real data:

```sh
GRADLE_USER_HOME=/tmp/taxi-inspector-gradle \
JAVA_HOME=/opt/android-studio-for-platform/jbr \
./gradlew --no-daemon test --tests '*TraceReplayTest' -Dtaxi.trace=$PWD/traces/<rideId>/decisions.csv
```

Without `-Dtaxi.trace` the test is skipped, so it costs nothing in a normal run.
