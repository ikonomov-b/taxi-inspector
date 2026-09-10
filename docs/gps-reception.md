# Taxi Inspector — In-Vehicle GPS Reception

## Why this document exists

The engine can only bill what the receiver delivers. Everything else in this project — mode-S
attribution, the deadband, the plausibility bound — operates on fixes that already passed the
20 m accuracy gate, and none of it matters if the gate rejects everything. This records what was
measured on real hardware, what limits reception, what was changed on the strength of it, and
which decisions are still open.

Written 2026-09-10 against `1.0.31+8cec2b3a` on a Google Pixel 8 Pro, Android 17.

## What was measured

Two ten-minute stationary holds with the debug ride trace running, test tariff
`2.40 / 1.20 / 0.35`:

| Placement | Fixes | Accuracy min / median / max | Inside the 20 m gate | Raw fix-to-fix path | Billed |
| --- | --- | --- | --- | --- | --- |
| Indoors, on a desk | 32 | 91 / 137 / 197 m | **0** | 270 m | 0 m, 0 s |
| Window border | 66 | 111 / 239 / 355 m | **0** | — | 0 m, 0 s |
| Outdoors | 565 | 19 / 31 / 50 m | **11 (2 %)** | **687 m** | **0 m, 10 s** |

Neither indoor run produced a billable fix, and the window border was *worse* than the desk. The
receiver spent minutes at a time delivering nothing before returning to a 1 Hz stream of 150 m
positions.

Outdoors, with the signal-quality instrumentation in place:

| | median | range |
| --- | --- | --- |
| Satellites used in fix | 6 | 3–8 |
| Satellites in view | 23 | 19–39 |
| C/N0 over the used satellites | **25.5 dB-Hz** | 22.5–28.3 |
| C/N0 over everything in view | 16.9 dB-Hz | 12.6–22.1 |
| Band | `Single` on **all 565 fixes** | no L5 fix at any point |

Accuracy did not converge. It wandered between 19 and 50 m for the whole ten minutes, and the
satellites the receiver was willing to use drifted between 3 and 8 while more than twenty stayed
in view. That is the signature of a constellation arriving too weak to trust: a Pixel 8 Pro under
genuinely open sky reports 35–45 dB-Hz and produces L5 fixes. At 25 dB-Hz this receiver was
working near its noise floor, dropping satellites from the solution, and the geometry degraded
with them. The phone was on USB power throughout, and cheap chargers are known to raise the GNSS
noise floor by about this margin — now a testable proposition rather than a guess, which is what
the C/N0 column is for.

Two conclusions follow, and they point in opposite directions.

**The engine's conservative contract works, and now there is a number for it.** Summed
fix-to-fix, the outdoor hold's positions wandered **687 m** in ten minutes while the phone sat
still on the ground. The engine billed **0 m**. Weak fixes are non-observations, "GPS weak — fare
frozen" is literally true, and 687 m of noise produced not one fake metre.

That also corrects the probe in `gps-fidelity-inspection-and-plan.md`, section 4.2, which put
the noise-driven distance at up to 2 176 m per stationary ten minutes at 20 m accuracy. The probe
generated every fix inside the billing gate. In the field the two filters are *correlated*: a fix
whose accuracy is poor enough to make the deadband leak is usually poor enough to be rejected
outright, and here the gate refused 98 % of fixes before the deadband was ever consulted. The
exposure is therefore much smaller than the probe implied — but it has moved rather than gone,
to the good-reception case where fixes are accepted and the deadband is only 10–20 m wide. That
case still needs measuring, and it needs better reception than any of these three runs achieved.

**But waiting time under-reads by two orders of magnitude, and that is the most important
result here.** The outdoor hold stood still for 600 seconds with more than twenty satellites in
view, and billed **10 seconds** of time. Indoors it billed none at all. An approved meter, which
counts time on its own clock, would have billed the full ten minutes in every one of these runs.

Only intervals between two *accepted* fixes accrue tariff time, so when the gate refuses 98 % of
fixes there is almost nothing to accrue between. This is the "provisional time-rate accrual
during a continuously service-owned outage" that `implementation-plan.md` step 8.3 lists as
undecided, and it is no longer theoretical: a stationary vehicle in ordinary conditions bills
roughly 2 % of the waiting time it is owed. It remains a deliberate contract decision needing the
reference-meter comparison behind it rather than a bug, but it is now the largest known
divergence between this app and the meter it estimates.

## What limits reception

Three facts about the code, verified rather than assumed:

1. **Every ride started the receiver cold.** `locationSamples()` had exactly one caller,
   `RideTrackingController.startLocationAndTicks()`, which runs on Start or Resume. Before that
   the app only asked `isGpsProviderEnabled()`, a boolean query that touches no hardware. So the
   chip was idle until the driver tapped Start and then had to acquire from scratch — 30–120
   seconds in the open, and as the measurements above show, sometimes never under cover. Under
   the current contract that interval bills nothing, so it is both a reception problem and a
   fare loss.
2. **The platform request was the legacy minimum.**
   `requestLocationUpdates(GPS_PROVIDER, 1000, 0f, listener, looper)`, with no `LocationRequest`
   and therefore no `QUALITY_HIGH_ACCURACY` hint the system could act on.
3. **There is no assistance path, by design.** GPS-provider-only means no A-GPS to shorten
   time-to-first-fix and no sensor fusion to carry a position through a tunnel. That is the
   documented invariant, not an oversight, but it is the direct cause of what was observed.

## What was changed

### The trace can now measure a placement

The trace recorded `usedInFix=0` for positions that plainly existed — no position can be solved
from no satellites. The count came from the *carrier-frequency list*, which holds only those
used-in-fix satellites whose frequency is readable, a strict and sometimes empty subset. It was
measuring the API's reporting quirks, not the constellation.

`SignalQuality` now carries, per fix:

| Field | Why |
| --- | --- |
| `satellitesUsedInFix` | The receiver's own count, read from `usedInFix` alone |
| `satellitesInView` | The denominator: 6 in view with 4 used says something different from 20 in view with 4 used |
| `l5SignalCount` | Unchanged, still from the carrier frequencies |
| `medianCn0UsedDbHz` | Carrier-to-noise density over the satellites that solved the fix |
| `medianCn0InViewDbHz` | The same over everything visible, which is the only number available when nothing is used |

**C/N0 in dB-Hz is the metric that separates one mount from another.** A few dB is the whole
difference between the middle of an athermic windscreen and its transponder patch, and no other
number in a trace shows it. Both populations are recorded because they differ, and a single
figure whose meaning changed silently with the fix state would be a trap. A missing reading is
recorded as absent, never as a signal of zero strength.

`compare_tracks.py` reports the distribution, so two placements can be compared with numbers
rather than impressions.

### The receiver is warmed up before Start

`GpsWarmUp` holds a subscription while the meter is on screen and no ride is active, and drops
every sample. By the time Start is tapped the ephemeris is current and a solution already
exists, so the first minute of the trip is billable.

It gives the engine nothing. The foreground service's subscription remains the only source of a
fix that can reach `RideEngine`, and the warm-up holds no state of its own — the meter screen's
coroutine owns its lifetime, so it ends when the screen stops being visible or a ride takes
over.

**This amends a documented rule.** `project-index.md` said the UI "never requests locations
directly". The rule's intent — that no position may reach a fare except through the
service-owned stream — is preserved exactly. What changed is that the meter screen may now hold
a discarding subscription, scoped to itself, because the alternative is that every ride begins
with a cold receiver. The rule is restated accordingly rather than quietly broken.

## Still open

**The provider decision.** The Fused Location Provider would give A-GPS, sensor fusion, and much
faster acquisition. But its positions report `provider = "fused"` with no dependable way to tell
a GNSS solution from a WiFi one, which collides head-on with the invariant that only GPS-provider
fixes may bill, and with mock rejection. The recommendation is to keep GPS-only and state the
cost plainly: no assistance, and tunnels stay frozen rather than dead-reckoned. Note that dead
reckoning is the mode-S gap-reconstruction question of section 8.3 wearing different clothes,
and both should be decided together.

**`LocationRequest` with `QUALITY_HIGH_ACCURACY`** (API 31+, legacy call as the fallback) is
contract-neutral and cheap. Not done; expected payoff is modest and unmeasured.

**Where the 20 m gate belongs.** This is now the sharpest open question. Outdoors, on a
flagship phone with 23 satellites in view, the gate refused 98 % of fixes and the app billed
0 m and 10 s over ten stationary minutes. Loosening it is *not* a reception improvement — it
admits worse data rather than obtaining better data — but leaving it where it is may mean the app
bills almost nothing in exactly the conditions a city taxi works in. The decision needs the
reference-meter comparison from step 8.3, and it should be taken together with the waiting-time
accrual above, because the two together decide whether the estimate is usable at all in weak
reception.

A drive will answer part of it by itself: a moving vehicle with a windscreen-mounted phone and a
clear forward sky view is a different reception regime from a phone lying on the ground, and the
share of fixes inside the gate is the first number to read off that trace.

## How to compare two placements

The point of the instrumentation above is that this is now a measurement rather than a
discussion.

1. `scripts/trace-toggle.sh on`, then drive or hold the same route twice, changing exactly one
   thing between runs.
2. `scripts/pull-traces.sh`, then `scripts/compare_tracks.py traces/<rideId>/decisions.csv`.
3. Compare median C/N0 first, then the share of fixes inside the 20 m gate. Accuracy alone is
   the receiver's own estimate and is less trustworthy than the signal strength that produced it.

Variables worth testing, in the order they are likely to matter in a car:

- **Windscreen position.** Athermic and heated screens attenuate GNSS by roughly 10–20 dB. Most
  have a small non-metallised patch behind the rear-view mirror for toll transponders; a cradle
  there against one in the middle of the glass is the single most promising comparison.
- **Charger.** Cheap USB chargers raise the GNSS noise floor measurably. Test plugged against
  unplugged, holding everything else constant.
- **Mount.** Metal arms and wireless-charging coils can shield or detune the antenna, which sits
  near the top edge of most phones.
- **Cradle against cupholder or seat**, which is the baseline the plan's risk 8 assumes.

Record the placement with every run. A C/N0 figure without a note of where the phone was is not
comparable to anything.
