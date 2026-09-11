# Taxi Inspector — GPS Fidelity Inspection and Mode-S Implementation Plan

> Status: **sections 4, 5 and 6 implemented; section 7 not started**. Written 2026-09-09 against commit `7761412`. The engine (commit 1) and the debug ride trace (commit 3) landed on 2026-09-10, each amendment they needed marked **Amended in implementation** below. Commit 2's robustness work landed on the same day. Commit 4's document and script rewrite is still open, so `scripts/check_ride_result.py` still encodes the superseded speed hysteresis and `simulate-drive.sh` fails until it is amended. The fare and GPS contract in `taxi-inspector-design.md` is behind the code; until commit 4 this document is authoritative for engine behaviour, and `field-validation.md` for the trace.

## 1. Purpose

This document records an inspection of the GPS tracking and fare logic against best practice and against how a real taximeter bills, the product decisions taken on its findings, and a detailed implementation plan for:

1. replacing the fixed 0.8 / 1.3 m/s Moving/Idle hysteresis with the EU taximeter **mode S** calculation model;
2. the fidelity and robustness fixes the inspection found necessary regardless of model;
3. a **debug ride trace** recorded for every trip on the developer's phone, shareable to Locus Map, so the app's track and billing decisions can be compared with an independent recording of the same trip and, later, with a reference taximeter.

## 2. Inspection findings

### 2.1 What the current engine does well

The engine is a sound conservative, evidence-grade estimator. Elapsed-realtime timestamps, GPS-provider-only input, mock-provider refusal, accuracy gating at 20 m, an accuracy-scaled deadband, continuity measured from the latest accepted fix, the half-open 15-second loss boundary with deterministic tick/location ordering, distance/waiting exclusivity, and a pure deterministic reducer with JVM tests are all best practice. The four logic commits (`4e3d5f2` mutual exclusion, `eb84ff2` dual-band and mock rejection, `7095475` speed-gate correction, `c4a9f1e` Weak and 15 s boundaries) were reviewed individually: each fixed a real defect, none regressed, and the current code matches the documents.

### 2.2 Measured behaviour

A throwaway JVM probe drove the current `RideEngine` at 1 Hz with the test tariff 2.40 initial, 1.20 per km, 0.35 per minute.

| Scenario | Ground truth | Engine result |
| --- | --- | --- |
| Ten-minute jam at 7 km/h | mode-S meter total 5.90 | 3.78 |
| Stationary 60 s, Doppler speed 0.3 ± 1.0 m/s, fixes Good at 8 m | ~55 s waiting | 0 s waiting, status Good |
| Stationary 60 s, device reports no speed | ~55 s waiting | 0 s waiting, status Good |
| 10 m/s, one fix in five at 25 m accuracy, rest 15 m | 590 m | 240 m |
| 10 m/s, accuracy alternating 15 m and 25 m | 590 m | 0 m |
| 10 m/s, one 200 m outlier fix | 100 m | 480 m |
| Creep at 2 m/s with three 20 s stops | 120 m and 60 s under the current model; 120 s of time under mode S | 108 m, 51 s |

### 2.3 Findings, ranked by fidelity impact

**F1. The idle switch is not how a taximeter switches.** An EU-approved meter in calculation mode S (MID Annex IX, MI-007) applies the time tariff below the cross-over speed and the distance tariff above it, with no debounce, because at the cross-over both tariffs yield the same money per second. Cross-over speed = time rate ÷ distance rate: 17.5 km/h for the test tariff, 15 km/h for the simulate-drive tariff (0.20 per minute, 0.80 per km). The engine switches at 0.8 m/s. Between those speeds the app bills distance where a mode-S meter bills time, and the shortfall is one minus speed over cross-over: 36 % for the jam row. The 5 s entry and 3 s exit hysteresis add to it: each stop loses five seconds of waiting, each departure misattributes three seconds and drops the distance covered.

**F2. An ambiguous Doppler speed stops waiting time while the status says Good.** When one sigma of the reported speed straddles 0.8 or 1.3 m/s the engine falls back to a derived speed that requires a baseline under five seconds old. A stationary car holds its baseline indefinitely, so the fallback is null and ticks bill nothing. Speed accuracy of 1–2 m/s is normal in urban canyons; devices without reported speed hit this always. It breaks the contract that the status label says whether the total is billable.

**F3. A single Weak fix as a hard billing boundary loses most urban distance.** The chord between two billing-quality fixes is a lower bound on the path whatever the fix between them looked like, so clearing the baseline buys no correctness. It discards the deadband residual at every boundary, and in-car accuracy hovering around 20 m makes those boundaries frequent: 59 % and 100 % loss in the flicker rows. The 60 m tier is dead code, since both branches call the same freeze.

**F4. Outliers are billed twice.** The only plausibility bound is 1,500 m per segment. 360 km/h is the fastest *sustained* rate it admits, since continuity caps a segment at 15 seconds; a single 1 Hz segment may be the whole 1,500 m, which is 5,400 km/h. A multipath jump moves the baseline to the outlier and the return is billed again.

**F5. Deadband residual and chord under-read are structural.** Every stop loses up to the deadband, and with in-car accuracy of 5–20 m the 2.5 m dual-band floor never binds. Accepted for now; the trace will measure it.

**F6. Robustness items.** Location callbacks arrive on the main looper, so UI jank skews the received timestamp that freshness and billing use. No partial wakelock is held, so the ticker and delivery can pause when the processor sleeps. The haversine mean radius is within 0.3 % of WGS84, above the 0.2 % distance tolerance in MI-007. `RideInput.GpsTimedOut` has no producer. Room is written twice per second.

## 3. Decisions

1. **Fare model: mode S.** Implemented per closed segment as `max(perKm × chord, perMin × elapsed)`. This equals mode S exactly when speed stays on one side of the cross-over within the segment and is a lower bound otherwise, so the app reads at or below the meter. No speed and no hysteresis are needed for billing. The cross-over speed derives from the tariff; there is no new user input. The reference taximeter's own mode must still be confirmed in Phase 8; the plan's replay tooling makes that comparison cheap.
2. **Switching from drive to idle must not take 15 seconds.** Billing switches within one fix; the display label within 2–5 s; the 15 s window governs only continuity and GPS loss (section 4.4).
3. **Weak fixes are non-observations**, not billing boundaries. GPS Lost at 15 s remains the only boundary.
4. **Outliers are rejected by implied speed** with an accuracy budget, and a relocation is confirmed by two mutually plausible fixes or a streak of three.
5. **Debug ride trace on by default in debug builds**, compiled out of release, shareable from Ride Detail.

   **Amended in implementation: off by default, opt-in.** A trace holds coordinates, so nothing collects them until asked to, debug build or not.

   **Corrected the same day, on device.** The switch was first placed *inside* `files/traces/`, which does not work: `adb shell` creating that directory makes it shell-owned, and the app then cannot stat what is inside it, so every ride went untraced while the marker sat there looking correct. It now lives beside the trace directory, at `files/.tracing-enabled`, because `getExternalFilesDir` creates `files/` itself and the app can therefore read a shell-written file in it. `trace-toggle.sh on` refuses outright if `files/` does not exist yet and says to open the app once, rather than creating it and reintroducing the same silent failure. This is exactly the failure the section-6.3 diagnostic below was added to expose, and it exposed it within a minute. The compile-time gate stays — a release build has the facility compiled out and cannot be switched on at all — and a debug build now needs a switch raised as well. The switch is a marker file, `traces/.tracing-enabled` in the app's own external files directory, rather than a stored setting: it can be flipped over adb without opening the app, it needs no Room migration, and it survives a reboot, because a field trip that goes unrecorded because a toggle reset itself is the one failure this facility cannot afford. `scripts/trace-toggle.sh on|off|status` manages it.
6. **No Room schema change** in this work. Renamed domain fields keep their columns.

## 4. Engine specification (`app/src/main/java/com/taxiinspector/ride/`)

### 4.1 State model (`ActiveRide.kt`)

| Field | Persisted (`ActiveRideEntity`, schema unchanged) | Note |
| --- | --- | --- |
| `id`, `companyName`, `tariff`, `phase`, `trackingStatus`, `startedElapsedMillis`, `lastTickElapsedMillis` | yes | unchanged; `lastTickElapsedMillis` only guards tick monotonicity and loss detection |
| `distanceMeters: BigDecimal` | yes | billed distance |
| `timeTariffMillis: Long` (was `idleMillis`) | column `idleMillis`, written as `timeTariffMillis + provisionalTimeMillis` | committed time-tariff duration |
| `provisionalTimeMillis: Long` | folded into the column on write, 0 on read | `= lastAcceptedFix.fix − lastBillablePoint.fix` while the baseline is held: the observed, not yet reconciled hold |
| `billedTimeMillis` (derived) | — | `timeTariffMillis + provisionalTimeMillis`; the only time figure the meter, notification, `FareCalculator.total` and `finish()` consume |
| `lastBillablePoint: LocationSample?` | yes (point columns; band reads back Unknown) | deadband baseline, start of the open interval |
| `lastAcceptedFix: LocationSample?` | timestamp only, in `lastAcceptedFixElapsedMillis`; null on read | continuity clock and plausibility reference |
| `lastFreshBillableReceivedElapsedMillis: Long?` | yes | loss timer; refreshed by accepted fixes and outliers, never by Weak |
| `pendingOutlier: LocationSample?`, `outlierStreak: Int` | no | two-fix relocation and the streak cap |
| `motionState` | yes | label only; names `Moving`/`Idle` kept so `valueOf` on stored snapshots works; `Idle` now means "time tariff currently applies" |
| removed: `lastSpeedMetersPerSecond`, `lastSpeedReceivedElapsedMillis`, `lowSpeedCandidateMillis`, `highSpeedCandidateMillis` | columns written null/null/0/0, ignored on read | |

`init` requires: `distance ≥ 0`, `timeTariff ≥ 0`, `provisional ≥ 0`, `lastBillablePoint == null ⇒ provisional == 0`, `pendingOutlier != null ⇒ lastAcceptedFix != null`, `outlierStreak ≥ 0`.

Persistence (`data/rides/RideMappers.kt`): `toEntity` writes `idleMillis = timeTariff + provisional`, `lastAcceptedFixElapsedMillis = lastAcceptedFix?.fixElapsedMillis`, point columns from `lastBillablePoint`. `toDomain` reads `timeTariffMillis = idleMillis`, `provisional = 0`, `lastAcceptedFix = null`, `pendingOutlier = null`, `outlierStreak = 0`. This is exact on every reachable path: Paused → Resume (Pause already committed provisional) and Running → interrupted (the sum is the confirmed total). The engine treats `lastBillablePoint != null && lastAcceptedFix == null` exactly like no baseline, so a loaded stale point can never form a chord.

Companion changes:
- `Tariff.crossoverSpeedMetersPerSecond`: `+∞` when `perKm == 0`, else `perMin × 1000 / (perKm × 60)`; `0.0` when `perMin == 0`. Label and trace only.
- `FareCalculator`: extract `distanceFare(tariff, meters: BigDecimal)` and `timeFare(tariff, millis: Long)` (scale 18, HALF_UP), used by `total`; parameter `idleMillis` renamed `timeTariffMillis`.
  **Amended in implementation.** `reconcile` and the label rule do not compare those two values. Dividing at scale 18 leaves a sixtieth a hair large, so for the documented tie case — `perKm` 1.20, `perMin` 0.36, 5 m in 1 s — `timeFare` exceeds `distanceFare` by 1.2e-19 and the tie falls to Time, not Distance. Both now call `FareCalculator.compareDistanceToTimeFare(tariff, meters, millis)`, which cross-multiplies `perKm x metres x 60` against `perMin x millis`: the same inequality with no division and no rounding at all. `FareCalculatorTest` pins both halves of this.
- New `Geodesic.kt`: Vincenty inverse on WGS84 (a = 6 378 137, f = 1/298.257223563, tolerance 1e-12, ≤ 200 iterations), haversine fallback (R = 6 371 008.8) on non-convergence, 0 for coincident points. Replaces the mean-radius haversine.
- `LocationSample`: add `utcMillis: Long? = null`, `bearingDegrees: Double? = null`, `altitudeMeters: Double? = null`, `satellitesUsedInFix: Int? = null`, `l5SignalCount: Int? = null`. Engine-neutral; needed by the trace (Locus GPX carries UTC).

  **Amended in implementation: mapped in commit 3, not commit 2.** A GPX without `<time>` cannot be aligned with a Locus recording at all, which is the whole point of the trace, so the adapter's mapping of these five fields was pulled forward out of section 5.1. The rest of 5.1, moving both callbacks onto a dedicated `HandlerThread`, is still outstanding.
- `RideSummary.idleMillis → timeTariffMillis` (entity column unchanged); `finish()` writes `billedTimeMillis`.
- New pure `RideEngine.interrupt(ride)` and `internal fun reconcile(tariff, meters, millis): Attribution`; `RideDao.markRunningRideInterrupted` calls `interrupt` instead of copying fields by hand.
- Constants: keep 20 m billing accuracy, 5 m / 2.5 m floors, 5 000 ms freshness, 15 000 ms loss. Delete `WEAK_ACCURACY_METERS`, `MAXIMUM_SEGMENT_METERS`, `IDLE_ENTRY_*`, `MOVING_EXIT_*`, `RideInput.GpsTimedOut`. Add `MAX_PLAUSIBLE_SPEED = 55.0` m/s, `SPEED_MARGIN = 15.0` m/s, `OUTLIER_STREAK_LIMIT = 3`.

### 4.2 Transitions (`RideEngine.kt`)

```text
start(id, company, now): Searching, distance 0, timeTariff 0, provisional 0, motion Idle, all points null, streak 0

plausible(from, to):                                     // both already passed the Weak filters, so acc <= 20
  gapS   = (to.fix - from.fix) / 1000.0                  // > 0 by ordering
  excess = max(0.0, Geodesic.distance(from, to) - (from.acc + to.acc)) / gapS   // accuracy budget first
  bound  = if (to.speed != null && to.speedAccuracy != null) min(55.0, to.speed + to.speedAccuracy + 15.0) else 55.0
  return excess <= bound                                 // no speed accuracy -> absolute bound only

reconcile(tariff, dMeters, dtMillis): Attribution =
  if (FareCalculator.distanceFare(tariff, d) >= FareCalculator.timeFare(tariff, dt)) Distance else Time   // tie -> Distance

onLocation(ride, s, now):
  if phase != Running -> ride
  if s.isMock || s.provider != Gps || s.acc > 20 || (now - s.fix) !in 0..5000
      -> copy(status Weak, lastTick = max(lastTick, now))             // non-observation: nothing else changes
  last = ride.lastAcceptedFix
  if last != null && s.fix <= last.fix -> ride                        // out of order
  r = if (last != null && s.fix - last.fix >= 15000) markGpsLost(ride, now) else ride
  common = status Good, lastFresh = now, lastTick = (r.status != Good ? max(lastTick, now) : lastTick)
  if r.lastBillablePoint == null || r.lastAcceptedFix == null         // Searching, after Lost, or loaded snapshot
      -> seed: r.copy(common, baseline = s, lastAcceptedFix = s, pending null, streak 0, provisional 0, motion Idle)
  if !plausible(r.lastAcceptedFix, s):
      streak = r.outlierStreak + 1
      if (r.pendingOutlier != null && plausible(r.pendingOutlier, s)) || streak >= 3
          -> relocate: r.copy(common, timeTariff += provisional, provisional 0, baseline = s, lastAcceptedFix = s,
                              pending null, streak 0, motion Idle)     // observed hold committed; the jump is never billed
      else -> r.copy(common, pendingOutlier = s, outlierStreak = streak)   // baseline, lastAcceptedFix, provisional untouched
  b = r.lastBillablePoint; d = Geodesic.distance(b, s); dt = s.fix - b.fix
  D = max(floor(b, s), b.acc + s.acc)                                  // floor 5 m, or 2.5 m when both endpoints Dual
  if d < D                                                             // held fix: accrue the observed hold
      -> r.copy(common, lastAcceptedFix = s, pending null, streak 0, provisional = dt, motion = labelOnHold(r.motion, dt, D, s))
  when reconcile(tariff, BigDecimal.valueOf(d), dt):
      Distance -> r.copy(common, distance += d,   provisional 0, baseline = s, lastAcceptedFix = s, pending null, streak 0, motion Moving)
      Time     -> r.copy(common, timeTariff += dt, provisional 0, baseline = s, lastAcceptedFix = s, pending null, streak 0,
                         motion = labelOnTimeClose(r.lastAcceptedFix, s))

labelOnHold(current, dt, D, s) = when {
  fastDoppler(s)                                    -> Moving
  slowDoppler(s) || timeFare(dt) > distanceFare(D)  -> Idle          // the hold proves average speed < D / dt < crossover
  else                                              -> current }
labelOnTimeClose(prev, s) =                                          // the first post-hold close is always Time-won;
  if (Geodesic.distance(prev, s) / ((s.fix - prev.fix) / 1000.0) >= crossover) Moving else Idle   // sub-interval since the previous fix
fastDoppler(s) = s.speed != null && s.speedAccuracy != null && s.speed - s.speedAccuracy >= crossover
slowDoppler(s) = s.speed != null && s.speedAccuracy != null && s.speed + s.speedAccuracy <  crossover

onTick(ride, now):
  if phase != Running || now <= lastTick -> ride
  if isGpsLost(now) -> markGpsLost(ride, now)
  label = if (baseline != null && motion == Moving && timeFare(now - baseline.fix) > distanceFare(20 m)) Idle else motion
  -> copy(lastTick = now, motion = label)                             // ticks never bill; the label fallback covers Weak stretches

markGpsLost(ride, now): copy(status GpsLost, lastTick = now, timeTariff += provisional, provisional 0,
                             baseline/lastAcceptedFix/pending null, streak 0, lastFresh null, motion Idle)
   // provisional == lastAcceptedFix.fix - baseline.fix: exactly the observed hold; the tail after the last accepted fix is never charged
Pause:             Running -> copy(phase Paused, timeTariff += provisional, provisional 0)
Resume(now):       Paused  -> copy(phase Running, status Searching, lastTick = now, points null, streak 0, lastFresh null, motion Idle)
PermissionRevoked: Running -> copy(phase Paused, status PermissionNeeded, timeTariff += provisional, provisional 0)
interrupt:         copy(phase PendingInterrupted, status GpsLost, timeTariff += provisional, provisional 0, points null, lastFresh null)
finish(ride, end): total(tariff, distance, billedTimeMillis); summary.timeTariffMillis = billedTimeMillis
```

Rules the pseudocode encodes:

- **Provisional time is fix-based, not tick-based.** It changes only on an accepted Good fix inside the deadband and equals the fix-clock hold length. Monotonicity is then exact: at a close `provisional ≤ dt`, and either `timeTariff += dt` or `distanceFare(d) ≥ timeFare(dt) ≥ timeFare(provisional)`. Tick-based accrual could exceed `dt` (a tick's receive-clock time can pass the closing sample's fix time), so a distance win could lower the total. Fix-based accrual also makes GPS Lost commit exactly the observed hold, needs no per-tick cap, and makes tick/location order irrelevant to the fare.
- **Provisional is committed, never cleared**: at Pause, PermissionRevoked, GPS Lost, interrupt, confirmed relocation, and it is included by `finish()`.
- **Whole-interval reconciliation.** `Δt` is the baseline-to-sample gap. Committing provisional as time and comparing only the final sub-interval would double-bill the held seconds while moving (+21 % at 2 m/s, +30 % at 8 m/s). Under-read bound of the chosen rule: at most `perKm × D − perMin × 1 s` per speed-regime transition, ≤ 0.018 at 20 m accuracy, always conservative.
- **Weak is a non-observation.** Nothing is cleared, the loss timer is not refreshed, provisional does not grow, so the total is flat while the status reads Weak and "GPS weak — fare frozen" stays literally true. A Good fix within 15 s bridges the whole held interval in one reduce; only a Weak spell that reaches 15 s loses, which is the existing contract. **Measured 2026-09-10 (`gps-reception.md`): this conflates two predicates.** A fix too inaccurate to measure a chord from is still proof that the vehicle is under observation and roughly here, so treating Weak as a non-observation for the *time* tariff as well is what cost a ten-minute stationary hold all but 10 seconds of its wait. Distance-trust is already held by the deadband, which scales with both endpoints' accuracy; observation-liveness is served by nothing of its own. The 8.3 decision is therefore whether to give liveness its own bound, not where to move the one gate.
- **Plausibility replaces the 1,500 m bound.** The bound admits at most about 865 m per 15 s. The accuracy budget matters: at 20 m accuracy consecutive stationary fixes routinely differ by 15–30 m and must never be outliers. The relative bound applies only when speed accuracy is reported, so a receiver reporting a bare 0 speed (the emulator without a velocity argument) cannot turn every fix into an outlier. The streak cap bounds a freeze from garbage fixes to 3 s.
- **15 s determinism is preserved.** Location-first at exactly 15 s runs `markGpsLost` (commits the same provisional, sets `lastTick = now`) and then seeds; tick-first runs the same `markGpsLost`, then the location path takes `max(lastTick, now)`. Identical states.

Known residuals, accepted and to be measured through the trace in Phase 8: a jitter excursion ≥ D in the first second after a close bills up to D metres as distance (about 0.006–0.012 per excursion; the same exposure exists today whenever the engine is Moving); a receiver that reports ≤ 20 m accuracy while producing 200 m jitter without speed accuracy can be accepted after 3 s by the streak cap. A "return to the previous baseline within the deadband" veto is the follow-up if field data shows creep.

**Amended in implementation — the first residual is larger than stated, and it is a new over-read.** It is not confined to the first second after a close, and its size is not bounded by one excursion. D is the *larger* of the movement floor and either endpoint's accuracy, never their sum, while the plausibility budget above allows each endpoint its full accuracy. Section 2.3's own noise model says consecutive stationary fixes at 20 m accuracy "routinely differ by 15–30 m". Every such difference over 20 m is therefore simultaneously *plausible* and *significant*: it clears the deadband, reads as 30 m/s, wins on distance, and advances the baseline, so the next excursion bills again. `RideEngineTest.jitter wider than the deadband is billed as distance` pins the measured figure: a vehicle standing still for twenty seconds at 20 m accuracy bills 570 m, about 0.68 on the test tariff, or roughly 2 per minute of standing still.

The superseded engine billed nothing there: a trusted 0 m/s dropped it into Idle within five seconds and distance/waiting exclusivity suppressed the distance. So this is the one place where the mode-S change moves the fare *up* rather than down, against the guardrail that ambiguous data must never over-charge, and it lands exactly in the urban-canyon case F2 and F3 were about. It is bounded only by the receiver's accuracy reporting, not by anything the engine does.

**Resolved the same day, in the direction the plan named.** The deadband is now `max(floor, a_baseline + a_sample)` — the same accuracy budget the plausibility rule spends, so a chord can no longer be plausible as noise and significant as movement at once. Nothing else changed, and the change can only lower a fare: holding instead of closing bills the interval as time, and a close only wins on distance when distance out-earns the whole interval as time.

The plan's second candidate, a "return to the previous baseline" veto, was rejected. It catches a strict oscillation but not a random walk, which is the actual failure: for a 2-D walk the fix two steps back is typically further away than the deadband, so it vetoes about a quarter of steps.

A throwaway JVM probe measured both rules against Gaussian per-axis noise at sigma = accuracy / 1.51 (Android reports accuracy as a 68 % radius), 1 Hz, twenty seeds, medians:

| Reported accuracy | Stationary 10 min, distance billed | 10 m/s for 10 min, 6 000 m true |
| --- | --- | --- |
| 5 m | 1 857 m → **303 m** | 6 628 m → **6 395 m** (+10.5 % → +6.6 %) |
| 10 m | 4 716 m → **890 m** | 8 152 m → **6 784 m** (+36 % → +13 %) |
| 20 m | 10 453 m → **2 176 m** | 12 541 m → **7 778 m** (+109 % → +30 %) |

So the budget removes about 80 % of the noise-driven distance at every accuracy, and the over-read on genuine travel at 20 m accuracy falls from more than double to +30 %.

Three things this does not settle, and they are now the most valuable thing the commit-3 trace can measure:

1. **The residual is still material** — 2 176 m per stationary ten minutes at 20 m accuracy is 2.6 on the test tariff. Summing chords between noisy fixes over-reads whether the vehicle moves or not, because the chord is `|displacement + noise|` and the close fires on the first chord to clear the deadband, which favours the noisy-high ones. No veto removes that; only a wider deadband or a different distance estimator does. A 3-sigma budget (about `3 x accuracy` rather than `2 x accuracy`) would cut the leak rate from roughly 10 % of steps to 0.6 %, but it is an invented constant and it costs chord fidelity on curves, so it belongs in the 8.3 threshold decision with real traces behind it.
2. **The probe assumes independent per-fix error, which is the pessimistic extreme.** Real GNSS error is strongly common-mode over tens of seconds, so a stationary phone drifts rather than jumps and the field figure should be far smaller. Nobody has measured it on this hardware. The trace answers it directly: the reason-code histogram plus a stationary hold is the whole experiment.
   **Answered 2026-09-10 on a Pixel 8 Pro; see `gps-reception.md`.** The error model was wrong in structure, not only in magnitude: it drew accuracy independently of acceptance, so the 20 m row above describes a population the gate almost never admits. In the field the gate and the deadband are correlated — a fix inaccurate enough to leak through the deadband is usually inaccurate enough to be rejected first — and a ten-minute stationary hold whose raw fix-to-fix path summed to 687 m billed 0 m, with 98 % of fixes refused before the deadband was consulted. The exposure has moved rather than gone, to the good-reception case where fixes are accepted and the deadband is 10–20 m wide. That case is still unmeasured.
3. **The over-read on genuine travel is not new.** The superseded engine summed chords the same way whenever it was Moving, and did it with the narrower deadband, so the +109 % row is pre-existing behaviour that this change improves. It has simply never been measured before.

Note also what the wider budget costs: the 2.5 m dual-band floor now only decides anything when the two accuracies sum to under 2.5 m, which no phone reports. Under mode S that is a small loss — the floor existed to keep slow city travel from being measured as straight chords across curves, and slow travel now bills time — but whether to keep the floor at all is a Phase 8.3 question.

### 4.3 Worked results under the new engine

Tariff 2.40 / 1.20 per km / 0.35 per minute, cross-over 4.861 m/s (0.0058333 per second, 0.0012 per metre).

| Scenario | New engine | Mode-S meter | Today |
| --- | --- | --- | --- |
| Jam 7 km/h for 10 min, 5 m accuracy | closes every 3 s (5.83 m): 0.0070 < 0.0175 → time; 600 s → **5.90** | 5.90 | 3.78 |
| Stationary 60 s, Doppler 0.3 ± 1.0 or none, 8 m fixes | held; provisional 60 s → **2.75** (speed is not consulted) | 2.75 | 2.40 |
| 10 m/s, one Weak fix in five (15/25 m) | Weak skipped; deadband 15 m; closes at 2, 5, 7, 10 … → **600 m** | 600 m | 240 m |
| 10 m/s, alternating 15/25 m | closes every even second → **600 m** | 600 m | 0 m |
| Single 200 m outlier at 10 m/s, 5 m accuracy | excess (200 − 10)/1 = 190 > 55 → pending; next fix 20 m/2 s plausible → **100 m** | 100 m | 480 m |
| Creep 2 m/s, three 20 s stops, 120 s | every close ≤ 6 m over ≥ 3 s → time; **120 s, 0 m → 3.10** | 3.10 | 108 m, 51 s |
| 60 s stop then 6 m/s, 5 m accuracy, read at 70 s | fix@61 closes 6 m/61 s → 61 s time; then 6 m/s distance: 61 s + 54 m → 0.4206 above initial | 0.422 | — |
| Steady 2 m/s, 10 m accuracy (closes every 5 s, 10 m) | 0.012 < 0.0292 → time; 0.350 per minute | 0.350 | — |
| Steady 8 m/s, 10 m accuracy (closes every 2 s, 16 m) | 0.0192 > 0.0117 → distance; 480 m per minute → 0.576 | 0.576 | — |
| Still 0–6 s then 3 m/s to t = 10 (12 m), 10 m accuracy | one close 12 m/10 s → 10 s time = 0.0583 | 0.0583 | — |
| Still 0–3 s then 10 m/s, 10 m accuracy | close at t = 4: 10 m/4 s → 4 s time = 0.0233 | 0.0295 | — |

### 4.4 Switch latency

Timeline for 8 m/s then a hard stop at position P at t = 0, 10 m accuracy (deadband D = 10 m), segments closing every 2 s while cruising, last distance close landing on the stop fix:

| t (s) | chord from baseline | engine action | billed time shown | total change | label |
| --- | --- | --- | --- | --- | --- |
| −1 | 8 m < 10 | held | 1 s | +0.0058 | Moving (10/1 > 4.86) |
| 0 (stop) | 16 m ≥ 10, Δt 2 s: 0.0192 ≥ 0.0117 | close as distance; baseline := fix@0 | 0 | +0.0134 net | Moving |
| 1 | 0 | held | 1 s | +0.0058 | Moving |
| 2 | 0 | held | 2 s | +0.0058 | Moving (10/2 = 5.0 > 4.86) |
| 3 | 0 | held | 3 s | +0.0058 | **Idle** (10/3 = 3.33 < 4.86) |
| 4–6 | 0 | held | 4–6 s | +0.0058 each | Idle |

- **Billing latency: one fix.** Provisional grows on the first accepted fix after the last close and every second thereafter; it never waits for a close and never consults the 15 s window. If fixes pause, the display freezes and catches up in full on the next accepted fix. Committed versus provisional is invisible to the user.
- **Label, Moving → Idle:** the first held fix with `timeFare(dt) > distanceFare(D)`, i.e. `dt > D / v_c`: 2 s at 5 m accuracy, 3 s at 10 m, 5 s at 20 m; 1 s with a trusted-slow Doppler (`speed + accuracy < v_c`). The tick fallback (D = 20 m) covers Weak stretches, outlier streaks and dropouts within 5 s. At cruise the baseline closes before the bound can fire, so the label does not flap. For a tariff with a very low cross-over the geometry cannot separate crawl from stop quickly and Doppler carries the label.
- **Label, Idle → Moving:** about 1 s with Doppler (`speed − accuracy ≥ v_c`); without it, at the first close after departure, using the sub-interval speed since the previous fix, so the first post-hold close, which is always Time-won, still labels a departing car correctly: 3.2 s at 10 m and 2 m/s², 4.5 s at 1 m/s², 6.3 s at 20 m and 1 m/s².
- **Departure and arrival bias.** The held stop-plus-pull-away interval closes when the chord clears D (2.2–6.3 s across D = 5–20 m and 1–2 m/s²), and time wins for any stop longer than about D / v_c, so the first D metres bill as time. A departing car is itself below v_c for its first v_c / a seconds, so the bias is small: for D = 10 m and 2 m/s², meter 0.0191 vs app 0.0187. Arrival absorbs the residual chord since the last close into the hold as time: worst −0.0062 at 10 m. Per stop-and-go cycle at most about 0.024 at 10 m, always under-reading; thirty city stops cost about 0.2–0.4 on a 15–20 fare, against −2.12 for the jam alone today.
- **The 15 s window is used in exactly two places**: continuity (`s.fix − lastAcceptedFix.fix < 15000`) and GPS Lost. Neither the billing switch nor the label reads them. Places where a switch could otherwise drift toward 15 s, and their bounds: a Weak stretch (nothing accrues while the status reads Weak, which is its documented meaning; the label flips by tick fallback within 5 s; the next Good fix settles the whole hold in one reduce); an outlier stream (one implausible fix delays accrual and label by one second; two mutually plausible outliers relocate in 2 s; `OUTLIER_STREAK_LIMIT = 3` caps any freeze at 3 s); a fix dropout (display frozen, bridged on return, Lost at 15 s); late delivery under doze (age > 5 s is Weak, as above). Pause, PermissionRevoked, interrupt and finish commit immediately; Searching accrues from the second fix.

### 4.5 Decision output for the trace

`RideEngine.step(ride, input): Step(ride, decision: RideDecision?)`; `reduce()` delegates to `step().ride`. `RideDecision` (`ride/RideDecision.kt`, pure): `reason` ∈ {Seeded, Held, ClosedDistance, ClosedTime, RejectedMock, RejectedNonGps, RejectedStale, RejectedAccuracy, RejectedOutOfOrder, Outlier, Relocated, GpsLostReset}, `chordMeters`, `significantMeters`, `baselineAgeMillis`, `excessSpeedMetersPerSecond`, `billedAs` ∈ {Distance, Time, None}, `deltaMillis`.

### 4.6 Tests

`app/src/test/java/com/taxiinspector/ride/RideEngineTest.kt`. Helpers derive metres-per-degree from `Geodesic` (about 111 087 m per degree of latitude at 42.7° under WGS84, not the spherical 111 320 m; a nominal "5.0 m" step otherwise lands at 4.99 m and misses the floor) and avoid exact-threshold steps. Add a `driveProfile(fixes, accuracy, band, speed?)` helper.

Delete: the five-second wait-billing test, the hysteresis-band test, and both speed-confidence tests.

Keep: Weak never becomes a baseline; the 5/14/15/16/30/60/120 s gap buckets (100 m over 14 s is 7.1 m/s, still distance); mock rejection.

Rewrite with mode-S expectations: GPS timeout commits the observed wait (fixes 0..10, `Tick(25_000)` → GpsLost, 10 000 ms, provisional 0); a Weak fix is a non-observation (Good 0..10 still, Weak 11–12 at 25 m, Good 13 → provisional 13 000, baseline at 0, total unchanged during Weak); Good after Weak bridges the segment (Good 0, Weak 1, Good 2 at 20 m → 20 m billed, baseline at 2); 2 m jitter for 16 s then 6 m → 16 s time, distance 0; 15 s order determinism without candidate fields; 60 s at 2 m/s → 59 000 ms, 0 m, label Idle; 20 s at 10 m/s → 190 m, 0 s; still 10 s then 6 m/s for 20 s → 11 000 ms, 108 m; 0.7 m/s for 30 s then 8 m/s → 31 000 ms, 224 m; dual-band 3 m steps at 2 Hz and 2 m accuracy bill 3.0 m only on Dual.

New:
- Probe regressions: jam → total 5.90; stationary with ambiguous Doppler and without speed → 60 s, then Pause commits 60 000; one Weak in five and alternating Weak → 600 m; single outlier → 100 m with `pendingOutlier` set at 5 s and cleared at 6 s, status Good throughout; creep with three stops → 120 000 ms, 0 m, 3.10.
- Two consecutive plausible outliers relocate without billing the jump (fixes 0..4 on track, 5..10 on a parallel track 200 m east → 80 m, 0 s, baseline at 6 000). Three mutually implausible fixes relocate (streak cap). An outlier burst refreshes the loss timer but not the baseline.
  **Amended in implementation.** The burst case was specified as five outliers (5..9 alternating ±200 m), which `OUTLIER_STREAK_LIMIT = 3` relocates at fix 7 by design, so the baseline does move and the total is 60 m, not 100 m. A burst that keeps the baseline is by definition shorter than the cap: the test uses two mutually implausible outliers at 5 and 6, then on-track fixes at 7..10, which bills the real 100 m across the burst without ever relocating and without ever going Lost.
- Plausibility: 14 s gap with 700 m bills, 900 m is an outlier; reported 10 ± 1 m/s over 1 s with 5 m accuracy: 50 m chord rejected (excess 40 > 26), 30 m accepted; 20 m accuracy with ±30 m jumps and 0 ± 0.5 m/s never an outlier.
- Latency: the timeline above (billed time 1..6 s and one +16 m step; label Idle at t = 3 for 10 m, t = 2 for 5 m); tick fallback labels Idle by 5 000 ms during Weak; slow Doppler flips in one second; fast Doppler flips to Moving before the first distance close; Moving on a distance close and on a Time close whose sub-interval speed exceeds the cross-over; departure closes within four seconds at 10 m and settles as time.
- Commit rules: Pause, PermissionRevoked, GPS loss (fixes 0..30, tick 45 000 → 30 000 committed, total unchanged across the tick), finish includes provisional, interrupt commits provisional.
- Non-observations: out-of-order ignored; stale fix is Weak with baseline kept; Weak does not reset the loss timer (Good 0, Weak 5/10/14, tick 15 000 → GpsLost); a Weak spell under 15 s is bridged.
- Exclusivity property over a mixed 60 s profile (still 10 s, 6 m/s 10 s, 2 m/s 10 s, 10 m/s 10 s, still 10 s, 8 m/s 10 s): accumulate `distanceTimeMillis += newBaseline.fix − oldBaseline.fix` whenever distance grows and assert `timeTariff + provisional + distanceTimeMillis == lastAcceptedFix.fix − firstFix.fix` after every reduce.
- Monotone-total property: the same profile plus Weak fixes, one outlier, one 20 s dropout and a Pause/Resume; `FareCalculator.total(tariff, distance, billedTimeMillis)` never decreases.
- Cross-over ±10 % on dual-band 2 m fixes so every second closes: 5.347 m/s → 315.5 m, 0 s; 4.375 m/s → 59 000 ms, 0 m. Exact tie via `reconcile(Tariff(…, 1.20, 0.36), 5 m, 1 s)` → Distance.

New `TariffTest.kt`: (1.20, 0.35) → 4.8611; (1.20, 0.36) → 5.0; perKm 0 → +∞; perMin 0 → 0.0. New `GeodesicTest.kt`: 111 319.49 m per equatorial degree of longitude; 110 574.39 m per meridional degree at the equator; Flinders Peak to Buninyong 54 972.27 m; Sofia 0.001° of latitude ≈ 111.09 m (haversine gives 111.19); coincident 0; near-antipodal returns a finite fallback within 0.5 % of haversine. `FareCalculatorTest.kt`: parameter rename.

Out-of-package fallout (mechanical): `MeterViewModel`, `RideNotificationFactory`, `RideHistoryFormatter` use `billedTimeMillis` and `summary.timeTariffMillis`; `RideDao.markRunningRideInterrupted` calls `RideEngine.interrupt`; androidTests `RoomRideRepositoryTest`, `TaxiInspectorMigrationTest`, `RideTrackingControllerTest`, `HistoryViewModelTest`, `RideDetailViewModelTest`, `MeterViewModelTest` rename fields.

## 5. Adapter and service robustness

### 5.1 `data/location/AndroidGpsLocationClient.kt`

- **Implemented.** Deliver location and `GnssStatus` callbacks on a dedicated `HandlerThread("TaxiGnss")` created inside `callbackFlow` and quit in `awaitClose`, so UI work cannot skew `receivedElapsedMillis`. `AndroidGpsLocationClientTest` asserts that neither callback is registered on the main looper and that both share one thread. `GpsLocationSource.requestGpsUpdates` and `registerGnssStatus` gain a `Looper`/`Handler` parameter; `AndroidGpsLocationClientTest.FakeGpsLocationSource` ignores it. Both callbacks stay on one thread, so the band carry-forward still needs no synchronisation.
- Map `Location.time` → `utcMillis`, `altitude` and `bearing` when present; extend `GnssStatusListener` with `usedInFixCount` and pass the L5 count through; echo the new fields in `logFieldQuality` (still never coordinates in Logcat).

### 5.2 `tracking/RideTrackingService.kt`

**Implemented**, and its justification has narrowed since the plan was written: ticks no longer bill and the hold is measured on the fix clock, so doze can no longer change a fare. What it protects now is the continuity of the *measurement* — without it a screen-off drive records GPS-Lost stretches caused by the device sleeping, which a trace cannot tell apart from bad reception afterwards.

Hold a `PowerManager.PARTIAL_WAKE_LOCK` (tag `TaxiInspector:ride`, non-reference-counted) from `startPreparing()` until `stop()` and `onDestroy()`. Verified on the emulator: held for the life of a ride, released on Stop. Add `android.permission.WAKE_LOCK` to the manifest. Suppress lint `WakelockTimeout` with a comment: the lock lifetime is bounded by the foreground service, and a ride may legitimately last hours.

### 5.3 Room write cadence

**Amended in implementation** — F6 lists "Room is written twice per second" and nothing in sections 4–8 acted on it. `RideTrackingController.updateRide` writes on every changed reduce, and both the 1 Hz ticker and the 1 Hz location flow change state, so a ride writes about twice a second for its whole duration; the trace recorder adds its own I/O on top.

**Resolved: durable changes at once, a held fix on a bounded interval.** An earlier draft of this amendment claimed the hold was reconstructible after a process death, from `lastAcceptedFix.fix − lastBillablePoint.fix`. It is not: `toDomain` deliberately returns `lastAcceptedFix = null`, so that a baseline restored without the fix clock it was measured against cannot form a chord across the gap. The stored `idleMillis` is the only record of the hold, so leaving a hold unwritten until the next close would cost a whole stop's waiting time to an unexpected kill.

`RideTrackingController.shouldPersist` therefore writes immediately whenever a phase, status, committed time, distance or baseline changes, and otherwise at most every five seconds. A ride at a light writes ten times less; what a kill can lose is bounded at five seconds of waiting time, in the under-reading direction.

### 5.4 `tracking/RideTrackingController.kt`

Use `RideEngine.step()` and hand `(input, before, after, decision)` to a `RideTraceRecorder` (section 6). Record every location (accepted or not), ticks only when status or label changes, every command, and the ride end. The notification reads `billedTimeMillis`.

## 6. Debug ride trace

### 6.1 Build flag and manifest

- `app/build.gradle.kts`: `buildFeatures { buildConfig = true }`; `buildConfigField("boolean", "RIDE_TRACE_ENABLED", "true")` in `debug`, `"false"` in `release`. `AppContainer` picks `FileRideTraceRecorder` or `NoOpRideTraceRecorder`. The developer installs debug builds via `scripts/build-device-apk.sh`, so every trip on the phone is traced; release keeps the documented privacy contract (no stored route, no coordinates in logs).
- `AndroidManifest.xml`: `androidx.core.content.FileProvider` with authority `${applicationId}.traces`, `exported="false"`, `grantUriPermissions="true"`, and `res/xml/trace_paths.xml` exposing `files/traces/`.

  **Amended in implementation.** Traces are written to the app's own *external* files directory (`getExternalFilesDir`), not `filesDir`, so a captured trip can be pulled with a plain `adb pull` instead of needing `run-as` on a debuggable build. It is still app-scoped: no permission, nothing else can write it, and uninstalling removes every trace. `trace_paths.xml` declares `external-files-path`, keeping the internal path as a fallback for a device with no external storage. `scripts/pull-traces.sh` collects them.

### 6.2 Pure formatting package `com.taxiinspector.trace` (JVM-testable, Android-free)

- `TraceRow`: sample fields, decision fields, cumulative `distanceMeters`, `billedTimeMillis`, `trackingStatus`, `motionState`, formatted total.
- `TraceCsv`: header plus one row per raw fix, per status- or label-changing tick, per command. Columns: `seq,type,utcMillis,fixElapsedMillis,receivedElapsedMillis,lat,lon,altM,accuracyM,speedMps,speedAccMps,bearingDeg,band,l5,usedInFix,mock,reason,chordM,significantM,excessSpeedMps,baselineAgeMs,deltaMs,billedAs,distanceM,timeMs,status,motion,total`.
- `TraceGpx`: GPX 1.1, one `<trk>` with one `<trkseg>`, every raw fix as `<trkpt lat lon>` with `<ele>`, ISO-8601 UTC `<time>`, and `<extensions>` carrying accuracy, speed, band, reason, billedAs and the cumulative totals. Locus imports it as a track.

  **Amended in implementation.** The extension elements are namespaced (`urn:taxi-inspector:gpx:1`, prefix `ti`). Unprefixed children of `<extensions>` are not valid GPX, and while Locus tolerates them a strict parser need not; namespacing means every reader ignores what it does not recognise instead of rejecting the file. `TraceFormatTest` parses the output with a namespace-aware `DocumentBuilder` to hold that. ISO-8601 is computed arithmetically rather than formatted: `java.time` needs API 26 or core library desugaring, this app targets 24 without it, and `SimpleDateFormat` is not safe to share between threads.
- `TraceMeta` JSON: rideId, company name, tariff, cross-over speed, engine constants, app `versionName`, device model, Android release, start UTC.

### 6.3 `data/trace/FileRideTraceRecorder.kt` and `RideTraceStore`

- Directory `filesDir/traces/<rideId>/` holding `track.gpx`, `decisions.csv`, `meta.json`. A single-thread dispatcher with buffered writers, flushed every 5 s or 10 rows so a service kill loses at most a few seconds (itself evidence). The GPX footer is written on ride end; an unterminated trace gets its footer when shared.

  **Amended in implementation: a failure to write must not be silent.** A trace may never throw into a ride, but swallowing the reason makes a storage or ownership problem indistinguishable from tracing being switched off, and the cost of telling those apart is a wasted field trip. Failures, a directory that cannot be created, and tracing being off are all logged under the `TaxiTrace` tag — paths and reasons only, never a coordinate.
- `RideTraceStore`: `filesFor(rideId)`, `delete(rideId)`, `prune(keepNewest = 30)`. Discard deletes the ride's trace; deleting a saved ride deletes its trace; prune runs at ride start.

### 6.4 Share from Ride Detail (`ui/history/`)

- `RideDetailUiState.hasTrace`; actions `ShareGpx` and `ShareTrace`; a one-off `RideDetailEffect.ShareFiles(files, mime)`. `RideDetailRoute` maps the files to `FileProvider` URIs and launches `Intent.createChooser`: `ACTION_SEND` with `application/gpx+xml` for the GPX alone (Locus offers import directly), `ACTION_SEND_MULTIPLE` with `*/*` for GPX, CSV and meta together.
- Two outlined buttons under the locked tariff, visible only when `hasTrace`: "Share GPX track" and "Share full trace". Strings in `res/values/strings.xml`. The addition is independent of the company-name row being added to the same screen.
- `RideDetailViewModel` takes a `RideTraceStore`; `deleteRide()` also deletes the trace.

### 6.5 Offline replay and comparison tooling

- `app/src/test/java/com/taxiinspector/trace/TraceReplayTest.kt`: reads a `decisions.csv` named by the system property `taxi.trace`, rebuilds `LocationSample`s and 1 Hz ticks, runs `RideEngine`, prints totals and a reason-code histogram; skipped when the property is absent. `build.gradle.kts` forwards `-Dtaxi.trace` to the test JVM. Any real trace can then be replayed against engine variants without a device.
- `scripts/compare_tracks.py`: takes the app GPX/CSV and a Locus GPX, aligns by UTC time, reports Locus raw distance against app billed distance per minute, and attributes the shortfall by reason code (Weak, outlier, deadband residual, GPS Lost). Implemented, standard library only, with its own Vincenty so it measures the reference path exactly as the engine does. It also runs with no reference file, which is what a stationary hold needs.

### 6.6 Locus comparison procedure (now `docs/field-validation.md`)

1. In Locus Map, record with GPS only (disable fused and network sources), a 1 s interval, a 0 m distance filter, and the accuracy filter off, so the recording is a raw track rather than a smoothed one.
2. Start both recordings in the taxi; Stop & save in Taxi Inspector at the end; stop the Locus recording.
3. Open the ride in History → Ride Detail → "Share GPX track" into Locus for a visual overlay, and "Share full trace" to a computer.
4. Export the Locus GPX and run `scripts/compare_tracks.py decisions.csv locus.gpx`.
5. Compare distance over Moving intervals only; Locus over-reads while stationary because it sums raw jitter. The reason-code histogram attributes every metre of difference. The taximeter, not Locus, remains the reference for the mode-S switch and for the fare increment; record its mode and increment on the same drive.

## 7. Scripts and documents to amend with the code change

- `scripts/check_ride_result.py`: drop `IDLE_ENTRY_MILLIS` and the 5 s deduction; compute the cross-over from the row and assert the drive speed exceeds it; distance unchanged (`(drive_seconds − 1) × speed_mps`, 40 m tolerance); time is fix-based, so expect it within `[stationary span − 3 s, span to the stop tap + 1 s + 3 s]`; docstring rewritten for mode S.
- `scripts/drive_profile.py`: derive the latitude step from the WGS84 meridian radius `M(φ) = a(1 − e²) / (1 − e² sin²φ)^{3/2}` at the start latitude, so each step's Vincenty distance equals the intended metres.
- `docs/taxi-inspector-design.md`: rewrite "Fare calculation", "Timing and state contract", "Location handling" and the status table for mode S, Weak as non-observation, plausibility, provisional time; close the reconstruction proposal's mode question; update the acceptance criteria (the idle-hysteresis test becomes a cross-over test). Wording: "equal for intervals with speed on one side of the cross-over, a lower bound otherwise".
- `docs/project-memory.md`: replace the bullets on hysteresis, derived speed, the Weak boundary and 1,500 m; add the trace facility and its debug-only privacy carve-out.
- `docs/build-status.md`, `docs/implementation-plan.md`, `docs/code-structure.md`, `docs/project-index.md`: phase amendments, the `trace` package, FileProvider, wakelock, HandlerThread, and the Phase 8.3 mode decision.

## 8. Sequencing and verification

Commits to `main`, in order:

1. **Engine**: section 4 with tests and the mapper. `./gradlew --no-daemon test` green.
2. **Adapter and service**: section 5, `LocationSample` fields, instrumentation updates.
3. **Trace**: section 6 with JVM tests for CSV, GPX and meta formatting and instrumentation for share and delete; replay and comparison tooling.
4. **Docs and scripts**: section 7; `scripts/simulate-drive.sh` on the emulator.

Verification:

- JVM: `JAVA_HOME=/opt/android-studio-for-platform/jbr ./gradlew --no-daemon test lintDebug` (mirrors `scripts/check.sh`).
- Instrumentation: `scripts/test-instrumented.sh`, one emulator run at a time.
- Black box: `scripts/simulate-drive.sh` with the new expectation model; a trace directory appears for the simulated ride and `TraceReplayTest` reproduces its totals from the CSV.
- Device: `scripts/build-device-apk.sh`, one real drive with Locus recording in parallel, share the GPX into Locus, run `compare_tracks.py`, and record the reference meter's mode and increment.
- Regression numbers asserted in `RideEngineTest`: jam 5.90; stationary 2.75; both flicker patterns 600 m; single outlier 100 m; creep 3.10; label Idle within 3 s at 10 m accuracy.
