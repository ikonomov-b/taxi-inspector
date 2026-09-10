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

| Placement | Fixes | Accuracy min / median / max | Fixes inside the 20 m gate | Billed |
| --- | --- | --- | --- | --- |
| Indoors, on a desk | 32 | 91 / 137 / 197 m | **0** | 0 m, 0 s |
| Window border | 66 | 111 / 239 / 355 m | **0** | 0 m, 0 s |

Neither run produced a single billable fix, and the window border was *worse* than the desk. The
GNSS status callback reported 5–7 satellites in view throughout, and the receiver spent minutes
at a time delivering nothing at all before returning to a 1 Hz stream of 150 m positions.

Two conclusions follow, and they point in opposite directions.

**The engine's conservative contract works.** For over three minutes the reported position
wandered roughly 80 m every nine seconds while the phone sat still, and the fare never moved off
the initial tax. Weak fixes are non-observations, "GPS weak — fare frozen" is literally true, and
no amount of indoor noise produced a single fake metre. That is the design behaving exactly as
written, confirmed on hardware for the first time.

**But a taxi idling under cover bills nothing at all**, where an approved meter would bill
waiting time from its own clock. This is the "provisional time-rate accrual during a
continuously service-owned outage" that `implementation-plan.md` step 8.3 lists as undecided,
and these runs are the first evidence that the case is ordinary rather than theoretical: 98
consecutive fixes, none billable, on a current flagship phone. It remains a deliberate contract
decision that needs the reference-meter comparison behind it, not a bug.

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

**Where the 20 m gate belongs.** If in-vehicle accuracy really runs 5–20 m, the gate sits at the
edge of the distribution and a large share of fixes will be refused. Loosening it is *not* a
reception improvement — it admits worse data rather than obtaining better data — and it is a
Phase 8.3 threshold decision with the reference meter behind it.

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
