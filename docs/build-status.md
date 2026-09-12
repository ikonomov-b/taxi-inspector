# Taxi Inspector — Build Status

## How to use this document

This is the single authoritative progress record for implementation work. Update it at the end of every implementation phase, not merely when code has been started. The project index and memory deliberately do not duplicate this live status.

- Use **Not started**, **In progress**, **Blocked**, or **Complete**.
- A phase is **Complete** only when its exit criteria in `implementation-plan.md` are met and its verification result is recorded below.
- Add new risks or scope changes when discovered; do not silently alter a planned product rule.
- Keep completed entries factual: files/components added, verification command, and remaining limitation.
- Update the summary, phase table, known risks, and next gate together so a new agent can rely on this document without reconciling multiple status records.

## Current summary

**Overall state: In progress — phases 0–7A complete; Phase 8, the release-readiness gate, is next.**

The project has a reproducible Gradle/Compose baseline, an exact Android-free fare core, an integration-tested Room layer, a GPS-only adapter that also reads satellite status so a dual-band fix can be told from a single-band one, a foreground-service tracking vertical slice, and a working four-destination UI. A user can currently save up to ten named taxi companies, select one before Start, run a visible ride, pause/resume, stop and save or discard after confirmation, then review the newest ten completed/interrupted summaries with their locked company label and delete an individual record after confirmation. A ride copies the selected company's name and exact rates together at Start, and no later edit, selection change, or deletion can alter an active or saved ride. The remaining release work is Phase 8: accessibility, adaptive layout, privacy, backup, and real-device validation.

The fare engine was replaced on 2026-09-10 with the EU mode-S model specified in `gps-fidelity-inspection-and-plan.md`, section 4: closed intervals are attributed whole to whichever tariff earns more, Weak fixes are non-observations instead of billing boundaries, outliers are rejected by implied speed, and distance uses a WGS84 geodesic. That is commit 1 of that plan's four; the design-document rewrite is not done, and `scripts/check_ride_result.py` still encodes the superseded speed hysteresis, so `scripts/simulate-drive.sh` will fail until commit 4 amends it.

**Amended 2026-09-12.** The switch-over speed that decides each interval is no longer derived from the two money rates. A field drive showed that derivation billing ordinary 7 km/h city traffic entirely as waiting time, and the regulated figure varies by jurisdiction anyway (5 km/h in Bulgaria, up to 20 elsewhere in the EU), so `Tariff` now carries a fourth stored, user-edited value, `waitingCrossoverKilometersPerHour`, defaulting to 8 km/h. Saved rides also record a second distance aggregate, `travelledDistanceMeters`, covering every GPS-confirmed chord including the slow ones billed as waiting time, so Ride Detail shows billed and travelled distance side by side. Both landed in Room version 3 (`MIGRATION_2_3`). The full entry is at the end of this document.

A debug ride trace was added the same day: with an opt-in flag raised, every trip on a debug build writes `track.gpx`, `decisions.csv` and `meta.json` to the app's own external files directory, holding each fix's coordinates and the engine's verdict on it, so a recorded trip can be overlaid on a Locus Map recording and compared. It is off by default and compiled out of release builds entirely. `docs/field-validation.md` is the procedure.

The adapter and service robustness work followed: both GNSS callbacks now arrive on a dedicated thread so UI work cannot skew the received timestamp that decides whether a fix may bill, a ride holds a partial wakelock so a screen-off drive does not record sleep as lost GPS, and a held fix is no longer written to Room twice a second. The app version is now derived from the git history — `versionCode` is the commit count and `versionName` carries the short SHA and a dirty marker — so a captured trace names the build that produced it instead of calling every build 1.0.

Last verified: **2026-09-12**

```text
GRADLE_USER_HOME=/tmp/taxi-inspector-gradle \
JAVA_HOME=/opt/android-studio-for-platform/jbr \
./gradlew --no-daemon test lintDebug assembleDebugAndroidTest
scripts/test-instrumented.sh

BUILD SUCCESSFUL
```

The debug JVM report contains 89 tests, one of them skipped by design (`TraceReplayTest`, which runs only when handed a captured trace), and the API 35 instrumentation report contains 127 Room/location/service/UI tests, with 0 failures and 0 errors. The version-3 migration was also proven black-box on the physical Pixel 8 Pro: the debug APK was installed over the real version-2 database, which came back at `user_version` 3 with all three saved companies holding the 8 km/h default and their own rates unchanged, and with existing summaries — including the 2026-09-11 Sofia drive and its exact total — reading back a null travelled distance rather than a backfilled one. Both flag states were also driven black-box on the API 35 emulator: a full ride with the flag down wrote no files, and the same ride with it raised produced 24 track points, every one carrying a UTC time, which `scripts/compare_tracks.py` then attributed by reason code. Lint reported 0 errors. The preceding 63-test suite also passed on a physical Pixel 8 Pro (Android 17); the History tests, the five fare-boundary tests, the six version-2 migration tests, and the whole company suite have so far run on the API 35 emulator/JVM environment only. A black-box upgrade of the shipped APK over a real version-1 database, and a full simulated drive through the company editor, were also driven through the app's own UI on that emulator; the detail is in the Phase 7A entries below.

## Phase tracker

| Phase | Status | Delivered | Verification / remaining work |
| --- | --- | --- | --- |
| 0. Freeze product contract | Complete | Product, architecture, and step-by-step plan documents define tariff units, state behaviour, GPS policy, recovery, and scope. | Decisions are documented in `taxi-inspector-design.md`, `code-structure.md`, and `implementation-plan.md`. |
| 1. Reproducible Android baseline | Complete | Gradle 8.7 wrapper, version catalog, Compose application shell, Java 17 target, Room/KSP configuration, API 35 SDK configuration, and environment record. | `./gradlew --no-daemon test` passes with Android Studio Panda JBR. |
| 2. Pure fare core | Complete, superseded in part | `DecimalAmount`, tariff parsing/formatting, `FareCalculator`, immutable ride models, and Android-free `RideEngine`. Distance and waiting time were made mutually exclusive after Phase 6; mock rejection, a band-dependent movement floor, and speed-confidence gating followed; Weak and 15-second gap boundaries are now deterministic (see the amendments below). | 22 pure fare/decimal JVM tests cover parsing, display rounding, known fare, idle entry, GPS loss, immediate Weak freezing, latest-fix continuity, both event orders at exactly 15 seconds, the 5/14/15/16/30/60/120-second gap buckets, distance/waiting exclusivity, mock rejection, the dual-band movement floor, and speed confidence. More boundary cases remain a future test-expansion task, not a blocker to this phase. **Amended 2026-09-10:** the speed hysteresis, derived-speed fallback and Weak billing boundary are gone, replaced by mode-S interval attribution, a plausibility bound with an accuracy budget and two-fix relocation, `Geodesic` (Vincenty on WGS84), `RideEngine.step`/`RideDecision`, and `RideEngine.interrupt`. 55 JVM tests now cover it, including the five section-2.2 probe regressions, an exclusivity partition property, a monotone-total property, and the noise floor that keeps jitter inside two fixes' combined accuracy from being billed as distance. |
| 3. Durable local state | Complete | Room v1 database, tariff/settings row, active-ride snapshot, summary history, atomic start/finish/interrupted-save transactions, ten-record trimming, repository, mappers, exported schema, and migration-test fixture. | 7 API 35 instrumentation tests verify tariff locking, finish rollback, concurrent interrupted-save idempotence, recreation mapping, deletion, trimming, and the exported v1 schema. UI/service wiring belongs to their later phases. |
| 4. GPS-only location adapter | Complete | Android-free `LocationClient`, GPS-only `AndroidGpsLocationClient`, elapsed-realtime/sample mapping, provider availability checks, explicit 1 Hz subscription, and cancellation cleanup. A parallel `GnssStatus` subscription, the `GnssBandClassifier` carrier-frequency rule, and speed-accuracy/mock mapping were added afterwards (see the amendment below). | 9 API 35 fake-backed tests verify provider availability, subscription cadence, removal of both subscriptions, GPS/non-GPS mapping, optional speed, malformed-fix rejection, and band attachment including its stale and absent cases. 5 JVM tests cover the carrier-frequency rule itself. |
| 5. Foreground tracking service | Complete | Non-sticky location FGS, serialized controller, explicit commands, prerequisite rechecks, bounded fare notifications, notification Pause/Stop, ownership binder, persisted pause/stop/discard, and interrupted recovery coordination. | 14 new API 35 tests cover prerequisite/foreground failures, command serialization, permission loss, paused Resume/Stop, Discard, throttling, ownership/recovery, real service binding, screen recreation/off, and actual notification actions. |
| 6. Essential Meter UI | Complete | Vintage theme and `TaximeterFace`, `MeterScreen`/`MeterRoute`/`MeterViewModel` with immutable state, actions and one-off effects, a separate Tariff destination with exact-decimal validation, permission/GPS gating with Settings recovery, confirmed Discard, and bind-before-recovery wiring. | 32 API 35 tests cover the meter screen, tariff screen, meter state holder, and tariff state holder. Visual refinement of the meter face stays in Phase 9. |
| 7. History and recovery UI | Complete | Newest-first History and full Ride Detail destinations observe Room directly; completed/interrupted summaries show their required final values and locked tariff, and individual deletion requires confirmation. | 13 new API 35 tests cover meter entry, empty/list/detail rendering, navigation actions, durable formatting, trimming, idempotent interrupted display, and confirmed deletion. |
| 7A. Saved taxi companies and pre-ride selection | Complete | Room version 2 with `TaxiCompanyEntity` under a unique name-key index, `selectedCompanyId` replacing the settings tariff, a locked `companyName` snapshot on active and saved rides, a tested non-destructive `MIGRATION_1_2`, transactional create/edit/select/confirmed-delete enforcing the ten-company limit and the active-ride lock, transactional Start resolving the selection, the Taxi companies and Company editor destinations, and an accessible Meter selector. | 51 new API 35 tests: 6 migration, 4 company repository transactions including concurrent adds, 6 editor and 5 list state-holder, 12 Compose, plus the updated meter and history coverage. A version-1 APK upgraded in place on the emulator, and `scripts/simulate-drive.sh` drove the whole company editor, meter, and fare path black-box. |
| 8. Quality, accessibility, privacy, and device validation | In progress | Build uses private local storage and has no network permission. The debug ride trace, its opt-in switch, the pull and compare tooling, and `field-validation.md` are in place, so a field drive now produces attributable data rather than one fare comparison. The first real drive was captured and cross-checked against a parallel Locus recording (risk 8a), and the two product changes it prompted landed: a per-company waiting crossover and a second, travelled-distance aggregate on saved rides (Room v3; see the amendment entry at the end). Two follow-up drives with the real 5 km/h Bulgarian crossover set on the same company cross-checked at 99.5 % billed/reference, up from 82.36 % at the old 20 km/h value (risk 8c). | Complete real-device tests starting with the stationary hold in `field-validation.md`, accessibility tests, backup decision, disclosure, and store data-safety work. The reference-taximeter comparison is still owed and still gates step 8.3; the 8 km/h *default* is still a chosen value, not a validated one — only the 5 km/h regulated figure has field evidence so far. |
| 9. Non-essential polish | Not started | — | Vintage visual refinement, onboarding, extra visual regression coverage, and any separately approved optional enhancements. |

## Implemented components

### Build and environment

- `gradlew` / `gradlew.bat` and `gradle/libs.versions.toml`
- Compose, lifecycle, navigation, coroutines, Room, KSP, and JUnit dependencies
- Android Studio Panda/JBR and SDK details in `development-environment.md`
- Android SDK Platform 35, platform-tools, and build-tools are locally configured through ignored `local.properties`

### Fare domain

- `core/decimal/DecimalAmount.kt`: exact non-negative tariff-unit values; no currency code, symbol, or conversion
- `ride/FareCalculator.kt`: the sole fare formula implementation
- `ride/RideEngine.kt`: pure state reducer for location, timing, tariff attribution, pause/resume, GPS timeout, and permission loss. Each closed interval goes whole to distance or to waiting time by comparing its average speed with the locked tariff's own `waitingCrossoverKilometersPerHour`, exactly and without division
- `ride/Tariff.kt`: four stored components — initial tax, per-km rate, per-minute waiting rate, and the waiting crossover in km/h. The crossover is user-entered, never derived from the other rates; `DEFAULT_WAITING_CROSSOVER_KILOMETERS_PER_HOUR` (`8`) is a product default, not a regulatory value
- `ride/*`: Android-free models and states

### Persistence

- `TaxiCompanyEntity`: one named company and its exact four-value tariff, with a stored `nameKey` under a unique index so duplicate labels are rejected for non-ASCII names too
- `AppSettingsEntity`: the durable `selectedCompanyId` only; the tariff itself now lives with its company
- `ActiveRideEntity` / `RideSummaryEntity`: a nullable locked `companyName` beside the locked tariff, so display never joins back to a mutable company row, plus `travelledDistanceMeters` beside billed `distanceMeters` — nullable on a summary, where null means a ride saved before it was recorded
- `RideDao.finishRide()`: summary insertion, active-row deletion, and history trimming in one transaction
- `RideDao.startRide()` / `RoomRideRepository.startRide()`: resolves the selection and locks the company name and exact tariff together, creating nothing when the selection is missing or stale
- `RideDao.saveSelectedCompanyTariff()`: the interim bridge for the pre-7A.4 single-tariff editor; step 7A.4 replaces it
- `MIGRATION_1_2` and `MIGRATION_2_3`: non-destructive forward migrations; no destructive fallback is registered
- `app/schemas/.../1.json`, `2.json` and `3.json`: exported Room schemas

### Location adapter

- `LocationClient`: Android-free GPS availability and sample-flow boundary for the future tracking service
- `AndroidGpsLocationClient`: GPS-provider-only 1 Hz subscription with elapsed-realtime domain mapping, plus speed accuracy and the mock-provider flag
- `GnssBandClassifier`: marks a fix dual-band when enough of the signals used in it are L5-class (1176.45 MHz +/- 1 MHz), which covers GPS L5, Galileo E5a, BeiDou B2a, QZSS L5 and NavIC L5
- A parallel `GnssStatus` subscription carries the latest band observation forward to fixes received within five seconds of it; a stale or absent observation leaves the fix `Unknown`
- Location collection cancellation unregisters the exact platform listener and the satellite-status callback, and safely tolerates permission revocation

### GPS reception

- `SignalQuality` on every fix: the receiver's own used-in-fix count, satellites in view, the L5 count, and median carrier-to-noise density over both the used and the visible satellites. The old count came from the carrier-frequency list and read zero for positions that existed; C/N0 in dB-Hz is the only number that tells one phone placement from another. `compare_tracks.py` reports the distribution.
- `GpsWarmUp`: the meter screen holds a discarding subscription while no ride is active, so the receiver is already tracking when Start is tapped instead of acquiring from cold. It gives the engine nothing; the service's stream is still the only source of a billable fix.
- Three stationary holds on a Pixel 8 Pro are recorded in `gps-reception.md`: indoors and at a window border, with no fix inside the 20 m gate in either, and outdoors at 25.5 dB-Hz median C/N0 and `Single` band throughout, where only 2 % of 565 fixes cleared the gate. The conservative contract is confirmed with a number — 687 m of summed fix-to-fix wander billed as 0 m — and the runs correct the section-4.2 probe, which overstated the jitter exposure by assuming every fix passes the gate.

### Build identity

- `app/build.gradle.kts` derives `versionCode` from `git rev-list --count HEAD` and `versionName` as `1.0.<count>+<short sha>[.dirty]`, so two people building one commit get one version and a trace can be attributed to the build that wrote it. CI checks out with `fetch-depth: 0`, since a shallow clone would count one commit.

### Debug ride trace (debug builds only, off until switched on)

- `trace/`: Android-free `TraceRow`, `TraceCsv`, `TraceGpx` (GPX 1.1 with namespaced extensions), `TraceMeta`, the `RideTraceRecorder` boundary and its release no-op, and an arithmetic ISO-8601 UTC formatter, since `java.time` needs API 26 and `SimpleDateFormat` is not thread-safe
- `data/trace/RideTraceStore.kt`: the trace directory under the app's own external files directory so `adb pull` reaches it, the `.tracing-enabled` marker file that gates recording — beside the trace directory rather than inside it, because a directory created over adb belongs to shell and the app cannot stat what is in it — thirty-trace pruning, deletion, and closing a GPX left unterminated by a killed process
- `data/trace/FileRideTraceRecorder.kt`: one ordered writer per ride, flushed every ten rows, appending rather than replacing when a paused ride resumes
- `RideEngine.step()`/`RideDecision`/`RideEngine.constantsForTrace()`: the engine reports what it decided and the thresholds it decided with, so a trace explains itself
- `ui/history/`: `hasTrace`, Share GPX track and Share full trace, handed out through a non-exported `FileProvider`; deleting a ride deletes its route
- `scripts/trace-toggle.sh`, `scripts/pull-traces.sh`, `scripts/compare_tracks.py`, and `TraceReplayTest` for driving a captured ride back through the engine on a computer

### Meter, company, and history UI

- `ui/TaxiInspectorApp.kt` and `ui/navigation/AppNavGraph.kt`: Meter, Taxi companies, Company editor, History, and Ride Detail destinations; a first run with no saved company starts in company creation
- `ui/theme/`: the warm paper/charcoal/yellow/LCD palette and the monospaced fare type
- `ui/meter/MeterViewModel.kt`: derives display state from Room, gates Start/Resume on permissions and the GPS provider, changes the durable selection, and emits one-off effects
- `ui/meter/MeterEffect.kt`: permission requests, Settings, service commands, and the ownership check the route performs
- `ui/meter/MeterScreen.kt` and `TaximeterFace.kt`: state-specific controls, confirmed Discard, per-value content descriptions, and the pre-ride company selector; during a ride it renders the ride's own locked snapshot
- `ui/companies/`: the Taxi companies list, the company editor and its four fields, their exact-decimal and name validation, the waiting crossover's own range check (`0 < v <= 30` km/h, asked as a separate question from "is this a well-formed decimal"), the ten-company limit, and the ride lock
- `ui/TariffSummary.kt` and `ui/DestinationHeader.kt`: display formatting and the Back header shared by every destination
- `ui/tariff/`: the parked anonymous single-tariff editor, kept building and tested but not wired into navigation (see project memory)
- `ui/history/`: Room-backed newest-first history and ride detail state, locale-aware presentation, billed and travelled distance side by side on Ride Detail with an explicit unrecorded label for older rides, and confirmed individual deletion
- `tracking/RideServiceOwnershipConnection.kt`: binds without `BIND_AUTO_CREATE` so recovery cannot be answered by a service it started

### Foreground tracking

- `RideTrackingService`: non-exported location FGS with non-sticky restart policy, local ownership binder, and a partial wakelock held for the life of a tracked ride so a screen-off drive is not recorded as lost GPS
- `RideTrackingController`: serialized command/location/tick processing, the sole in-memory running-ride owner, and a write policy that persists anything a recovered ride needs at once while bounding a held fix to one write every five seconds
- `RideNotificationFactory`: rate-bounded fare/status notification with Pause and Stop & save actions
- `RideRecoveryCoordinator`: bind-first ownership check followed by atomic interrupted recovery

## Known limitations and active risks

1. The exact fare core has targeted unit tests but does not yet cover every documented threshold, rejected segment, and interrupted-session transition.
1a. `CompanyListViewModelTest.deletingTheSelectedCompanyLeavesNoSelection` failed once with "connection pool has been closed" and passed on an immediate re-run: a Room flow still emitting after the test closed its database. It aborts the whole instrumentation run when it fires, so it is worth a teardown fix rather than a retry.
2. **Resolved 2026-09-10.** Waiting time no longer depends on a reported or derived speed: a hold inside the deadband accrues time-tariff duration from the fix clock, so a device that reports no speed at all, or one whose speed accuracy straddles any threshold, bills the same. The mode-S change removed the thresholds the speed was being asked about.
2a. **Mitigated 2026-09-10, not eliminated.** The deadband now spends both endpoints' accuracy rather than the larger of the two, matching the plausibility rule, so a chord can no longer be plausible as noise and significant as movement at the same time. A seeded JVM probe against independent Gaussian fix error puts the improvement at about 80 % of the noise-driven distance at every accuracy, and the over-read on genuine 10 m/s travel at 20 m accuracy drops from +109 % to +30 %. What remains is that summing chords between noisy fixes over-reads in both directions of motion: 2 176 m per stationary ten minutes at 20 m accuracy, 2.6 on the test tariff. That is a property of chord summation, not of a missing veto, and the probe's independent-error model is the pessimistic extreme — real GNSS error is largely common-mode over tens of seconds. The stationary half has since been quantified on hardware — see risk 2c and `gps-reception.md`, which show the gate and the deadband are correlated in the field and the probe's 20 m row describes a population the gate rarely admits; what remained unmeasured was the good-reception case, where fixes are accepted and the deadband is narrow — risk 8c's two 2026-09-12 drives (1–9 m accuracy, dual-band throughout) are that case, and billed distance came in slightly under the reference path on both, so no over-read showed up there either, though a Locus-sampling caveat on that same evidence keeps the ratio itself from being exact. A wider (3-sigma) budget is a Phase 8.3 threshold decision that needs those traces first. The numbers and the reasoning are in `gps-fidelity-inspection-and-plan.md`, section 4.2.
2b. The 2.5 m dual-band movement floor is now unreachable in practice, since it only decides anything when the two endpoint accuracies sum to under 2.5 m. Under mode S the loss is small — the floor existed to stop slow city travel being measured as straight chords across curves, and slow travel bills time now — but whether to keep it is a Phase 8.3 question. This supersedes part of risk 7.
2c. **Waiting time under-reads by roughly sixty times in weak reception, measured 2026-09-10.** A ten-minute stationary outdoor hold billed 10 seconds of tariff time; the indoor holds billed none. This is the second instance of one mechanism rather than a new defect: tariff time has no predicate of its own, accruing only as a side effect of the distance path between two accepted fixes, so any rejection reason stops the clock. Risk 2 above closed the instance where the borrowed predicate was speed; this is the same stall with the accuracy gate. It is step 8.3's undecided provisional accrual during an outage and now the largest known divergence between this app and the meter it estimates. The threshold values need the reference-meter comparison; whether liveness should get a predicate separate from distance-trust can be settled by replaying a pulled hold, with no field trip. The measurement and the reasoning are in `gps-reception.md`.
3. The meter face is functional and accessible but visually plain; the late-1970s styling, texture, and transitions remain Phase 9 work.
4. Automated service tests use emulator/fake GPS inputs; real street, tunnel, and weak-signal field validation remains part of Phase 8.
5. The database is now version 2. Its forward migration is proven by six instrumentation tests and by one black-box APK-over-APK upgrade on the API 35 emulator, but not on a physical device; add that to the Phase 8 device pass.
6a. A debug build can record coordinates. That is the point of the trace, and it is why it is off until switched on, why the switch is a file anyone can see and delete, why a discarded or deleted ride loses its trace with it, and why release builds have the whole facility compiled out rather than merely disabled. Phase 8.4's disclosure work must still state plainly that a release build stores no route.
6. The local Android SDK path is machine-specific and remains in ignored `local.properties`; it must never be committed.
7. Dual-band detection is now confirmed on real hardware (see the second Phase 4 amendment), but the 2.5 m movement floor itself has still never been exercised. The floor is `max(2.5 m, each endpoint's accuracy)`, and the measured accuracy at a window was 4.5 m, so the accuracy term dominated and the tighter floor never bound. It only binds below 2.5 m reported accuracy, which needs open sky. Note that a stationary hold cannot test it either: distance accrues only while the engine is Moving, so a parked vehicle bills nothing whatever the floor is. The discriminating test is steady slow movement at roughly 3 m/s over a measured distance, where 1 Hz segments of about 3 m fall between the two floors. A walk along a measured stretch of open pavement will settle it sooner than a drive, since a windscreen pushes accuracy back above 2.5 m and the floor stops binding again.
8. In-vehicle behaviour is entirely unmeasured, and a car is harsher than anything tested so far. Two consequences are expected rather than hypothetical. Reported accuracy through a windscreen realistically runs 5-20 m rather than the 4.5 m measured at a window, so the accuracy term sets the movement floor, the 2.5 m dual-band value never binds, and below roughly 54 km/h at 1 Hz the segments fall under the deadband and accumulate as straight-line chords that under-read a curving road. And tunnels and underpasses trip the 15-second GPS Lost rule routinely rather than exceptionally, freezing the fare and resetting the baseline, where a real taximeter counting odometer pulses would not. Both err towards under-reading, which is the intended direction, but neither has been quantified. Phase 8 should capture a real drive before any threshold is revisited; phone placement (a windscreen cradle rather than a cupholder, and note that athermic glass attenuates GNSS badly) is likely to matter more than any constant.
8a. **First real drive captured and cross-checked 2026-09-12** against a Locus Map GPS-only recording taken in parallel (`docs/field-validation.md`'s comparison procedure), ~19 minutes in Sofia street traffic, band Dual on 1109 of 1138 fixes. Billed distance came to 82.36 % of the reference raw path over the same window, and the shortfall attributes cleanly: every `ClosedDistance` interval (n=335, 4841 m total) averaged 11.0 m/s, every `ClosedTime` interval (n=101, 1053 m of real movement redirected to the time tariff) averaged 1.53 m/s, and the fastest `ClosedTime` close measured 5.55 m/s against this tariff's 5.556 m/s (20 km/h) crossover — the two populations do not overlap, so `RideEngine`'s per-interval fare comparison is attributing exactly as designed, not misreading slow driving as distance loss. A concern raised against this same drive, that the Idle/Moving label looked too eager to call the vehicle idle, checked out as the crossover doing its job: `labelOnHold`'s `deadbandOutEarned` bound is a proven lower limit (if the vehicle could still be at-or-above crossover, the accumulated chord would already have cleared the deadband and closed as distance), and only one fix in the whole trace showed a reported Doppler speed confidently above crossover while still labelled Idle, itself 0.25 m/s over the line. The open question this risk raised is therefore narrower than before: the 82 % ratio is consistent with genuine sub-crossover city driving rather than a defect, but this was one drive with no reference taximeter in the car, so it does not by itself clear the Phase 8.3 gate below (wider noise budget, dual-band floor, bounded gap reconstruction) — that still needs a fare comparison against an approved meter on the same trip.
8b. **Crossover-speed doubt checked 2026-09-12, no code change.** `Tariff.crossoverSpeedMetersPerSecond` is not a tunable constant; it is `perMinuteStillRate / perKmRate`, computed fresh from whichever company is active. OIML R21 confirms the mode-S switch-over speed is defined to vary with the tariff rather than being fixed, and a real, currently published (01.01.2026) Sofia day tariff computes to a ≈19.2 km/h crossover against the 20 km/h the test drive's `Taxime` company (`0.75`/`0.25`) produces on the same formula — close enough that the 82.36 % billed/reference ratio in 8a is not explained by a wrong crossover. See `project-memory.md`'s durable fare/GPS decisions for the sourced numbers and the corridor this sits in.
8c. **Two more drives cross-checked 2026-09-12, after the per-company crossover landed (8dc5f0c).** Same `Taxime` company, now carrying the real Bulgarian regulated crossover, 5 km/h, instead of the 20 km/h the money rates used to derive in 8a/8b. Both drives had excellent reception throughout — every fix Good, accuracy 1–9 m, dual-band on all but one of 768 combined fixes, C/N0 30–33 dB-Hz versus the 25.5 dB-Hz single-band outdoor hold in `gps-reception.md` — so this is also the first data point for risk 2a's open "good-reception case," and it came back clean: billed distance was 99.48 % and 99.50 % of the reference path (2478/2491 m over 264 s; 5418/5445 m over 502 s), under rather than over in both, so the deadband is not inflating distance on this hardware when reception is good. `ClosedDistance` and `ClosedTime` again separate cleanly at the tariff's own crossover with no overlap (162/2478 m at 14.5 m/s avg vs 2/23 m at 0.37 m/s avg on the shorter drive; 371/5418 m at 13.9 m/s avg vs 3/28 m at 0.40 m/s avg on the longer one), and neither trace shows a single Weak, GPS-lost, outlier, or mock rejection. Read together with 8a, the crossover change is doing exactly what it was built for: the same city driving that redirected 1053 m to the time tariff at a 20 km/h crossover redirects only 23–28 m of incidental holding at 5 km/h.
  A genuine gap turned up in checking it: neither this pair nor the original 8a Locus recording was actually taken at the 1 s/0 m-filter setting `field-validation.md` specifies — the exported GPX tracks sample at a 2–3 s median interval with occasional gaps past 40 s, which straightens curves and understates the true path, so every billed/reference ratio recorded so far (82.36 %, 99.48 %, 99.50 %) is somewhat optimistic versus a genuinely raw reference. `scripts/compare_tracks.py` now reports the reference track's own min/median/max sample interval and warns when the median sits above 1.5 s, so a future run that used the wrong Locus setting is caught from the tool's own output instead of by noticing that some one-minute bucket billed more than its reference.
9. Instrumentation on a physical device needs preparation the emulator does not: animations disabled and the screen held awake, or Compose tests fail intermittently with `No compose hierarchies found in the app`. The commands are in `development-environment.md`. This is a runner caveat rather than an app defect, but it will bite anyone repeating the Phase 8 device runs.
10. Bounded GPS-gap reconstruction is documented as a Phase 8 accuracy proposal, not implemented behaviour. The current engine bridges a billable endpoint chord only when consecutive accepted fixes are less than 15 seconds apart and otherwise freezes. Before choosing a longer recovery window, Phase 8 must identify the reference taximeter's calculation mode and fare increment, compare signed and absolute fare error by gap length, and settle the maximum distance-recovery gap, uncertainty/plausibility bounds, and any short-gap speed estimator. For mode S the candidate is the larger of endpoint-distance fare and elapsed-time fare; for mode D it is their sum. Either must apply consistently to ordinary and reconstructed intervals. A production version would require pure-domain exact-once attribution, separate measured/estimated aggregates, visible disclosure, and probably a Room migration.

## Next phase gate

Phase 7A is complete, so Phase 8 — the release-readiness gate — is next, and it now has the final company-selection flow to validate:

- accessibility and adaptive layout across Meter, Taxi companies, Company editor, History, and Ride Detail, including font scaling and TalkBack on a physical device;
- field validation of the GPS thresholds, including the in-vehicle behaviour and the unexercised 2.5 m dual-band floor in risks 7 and 8;
- the reference-taximeter comparison and the explicit decision gate for bounded GPS-gap reconstruction, which remains unimplemented; and
- the release privacy work: the backup decision for company tariffs and ride summaries, the estimate-only disclosure, and the Play data-safety declaration.

Phase 8 should also upgrade a physical device across `MIGRATION_1_2`, which has so far only been proven on the emulator, and repeat the company flow there. `MIGRATION_2_3` has since been proven both ways — six instrumentation tests and a black-box APK-over-APK upgrade on the real Pixel 8 Pro — so the remaining device-migration debt is the version-1 path only. Phase 9 visual polish must not displace that gate.

Phase 7A is required for release and must complete before Phase 8. Phase 8 remains the release-readiness gate and must validate accessibility, adaptive layout, privacy, backup behaviour, and real-device operation against the final company-selection flow. Phase 9 visual polish must not displace either essential gate.

Phase 8 also includes a documented reference-taximeter comparison and an explicit decision gate for bounded GPS-gap reconstruction. The proposal does not authorize changing fare behaviour before that evidence and contract update.

### Phase 3 — Durable local state

Status: Complete
Date: 2026-09-04
Delivered:
- Added API 35 Room repository/DAO integration coverage and an exported-v1 migration-test fixture.
- Made interrupted recovery idempotent inside one Room transaction, including concurrent retry coverage.
- Exposed history deletion through the repository and verified it leaves unrelated summaries intact.
- Installed Android Emulator 37.1.11, an API 35 default x86_64 system image, and the `taxi-inspector-api35` AVD for local instrumentation.

Verification:
- Command: `GRADLE_USER_HOME=/tmp/taxi-inspector-gradle JAVA_HOME=/opt/android-studio-for-platform/jbr ./gradlew --no-daemon test connectedDebugAndroidTest`
- Result: `BUILD SUCCESSFUL`; 8 debug JVM tests and 7 API 35 instrumentation tests passed with no failures, errors, or skips.
- Command: `GRADLE_USER_HOME=/tmp/taxi-inspector-gradle JAVA_HOME=/opt/android-studio-for-platform/jbr ./gradlew --no-daemon lint`
- Result: `BUILD SUCCESSFUL`.

Remaining risk or next gate:
- Implement and integration-test the Phase 4 GPS-only location adapter without introducing Android dependencies into `ride`.

### Phase 4 — GPS-only location adapter

Status: Complete
Date: 2026-09-04
Delivered:
- Added an Android-free `LocationClient` boundary with current GPS-provider availability and a cold sample flow.
- Added `AndroidGpsLocationClient`, requesting only `LocationManager.GPS_PROVIDER` at a one-second cadence and unregistering its listener when collection ends.
- Mapped Android locations to elapsed-realtime domain samples without inventing missing speed or accepting malformed accuracy/coordinates.
- Wired the location client into the explicit application container without adding UI or service behaviour.

Verification:
- Command: `GRADLE_USER_HOME=/tmp/taxi-inspector-gradle JAVA_HOME=/opt/android-studio-for-platform/jbr ./gradlew --no-daemon test connectedDebugAndroidTest`
- Result: `BUILD SUCCESSFUL`; 8 debug JVM tests and 12 API 35 instrumentation tests passed with no failures, errors, or skips.
- Command: `GRADLE_USER_HOME=/tmp/taxi-inspector-gradle JAVA_HOME=/opt/android-studio-for-platform/jbr ./gradlew --no-daemon lint`
- Result: `BUILD SUCCESSFUL`.

Remaining risk or next gate:
- The adapter has deterministic fake-backed coverage but no real-world GPS field validation yet; that remains part of Phase 8.
- Implement Phase 5 with the foreground service as the sole active-ride writer.

### Phase 5 — Foreground tracking service

Status: Complete
Date: 2026-09-04
Delivered:
- Added `RideTrackingService` as a non-exported location foreground service with `START_NOT_STICKY` behavior and an ownership binder.
- Added serialized Start/Pause/Resume/Stop/Discard, GPS callback, and one-second tick handling through one service-owned controller.
- Added precise-location, notification-permission, GPS-provider, saved-tariff, and active-session prerequisite checks.
- Added rate-bounded fare/status notifications with Pause and Stop & save actions using the same command path.
- Added persistence-first Pause/Stop behavior, paused-session Resume/Stop/Discard handling, permission-loss freezing, and atomic unowned-running recovery.

Verification:
- Command: `GRADLE_USER_HOME=/tmp/taxi-inspector-gradle JAVA_HOME=/opt/android-studio-for-platform/jbr ./gradlew --no-daemon test connectedDebugAndroidTest`
- Result: `BUILD SUCCESSFUL`; 8 debug JVM tests and 26 API 35 instrumentation tests passed with no failures, errors, or skips.
- API 35 checks include real foreground start from a visible activity, binding, screen recreation, screen-off continuation, and actual notification Pause/Stop actions.
- Command: `GRADLE_USER_HOME=/tmp/taxi-inspector-gradle JAVA_HOME=/opt/android-studio-for-platform/jbr ./gradlew --no-daemon lint`
- Result: `BUILD SUCCESSFUL`; 0 errors. Existing pinned-dependency and placeholder-resource warnings remain.

Remaining risk or next gate:
- The service is not user-accessible until Phase 6 supplies tariff, permission, meter, confirmation, and recovery UI.
- Real-world GPS/background field validation remains a Phase 8 gate.

### Phase 6 — Essential Meter UI

Status: Complete
Date: 2026-09-04
Delivered:
- Added `MeterViewModel` with immutable `MeterUiState`, a single `onAction` entry point, and one-off `MeterEffect` values, holding no `Context`, `Location`, service reference, or mutable fare state.
- Added `MeterScreen` and the `TaximeterFace` drawing component: formatted total with no currency label, distance, wait time, plain-language GPS status outside the meter, and per-value content descriptions.
- Added state-specific controls — Start/Reset, Pause/Stop & save, Resume/Stop & save, Save as interrupted — with Discard kept apart from Stop & save and always confirmed.
- Moved tariff entry to its own destination (`ui/tariff/`) with per-field exact-decimal validation, the ride lock, and a `TariffViewModel`; a first run with no saved tariff opens it directly and cannot be left without saving.
- Added `ui/navigation/AppNavGraph.kt` with the Meter and Tariff destinations and the first-run start-destination decision.
- Gated Start and Resume on precise location, notification permission, and an enabled GPS provider, requesting one permission at a time and offering Settings recovery when a check fails.
- Wired production interrupted-recovery: `RideServiceOwnershipConnection` binds without `BIND_AUTO_CREATE`, and a Running snapshot is converted only when no live service claims it.
- Fixed `RideTrackingController.stopAndSave()` to clamp the end timestamp for a snapshot recovered after a reboot, so Save as interrupted cannot fail on an elapsed-realtime reset.
- Added the vintage Compose theme and moved the palette into `ui/theme/Color.kt`, removing the superseded duplicate XML colors.

Verification:
- Command: `GRADLE_USER_HOME=/tmp/taxi-inspector-gradle JAVA_HOME=/opt/android-studio-for-platform/jbr ./gradlew --no-daemon test connectedDebugAndroidTest`
- Result: `BUILD SUCCESSFUL`; 8 debug JVM tests and 58 API 35 instrumentation tests passed with no failures, errors, or skips.
- The 32 new API 35 tests cover meter totals and readouts, state-specific controls, the confirmed Discard dialog, screen-reader labels, permission and GPS recovery actions, tariff validation per field, the ride lock, the documented `6.45` fare example rendered end to end, and single-shot interrupted recovery.
- One Compose test types into the tariff fields with the IME open, because focusing a field re-pads and scrolls the inset-aware column; every interaction re-resolves its node rather than reusing earlier bounds.
- Command: `GRADLE_USER_HOME=/tmp/taxi-inspector-gradle JAVA_HOME=/opt/android-studio-for-platform/jbr ./gradlew --no-daemon lint`
- Result: `BUILD SUCCESSFUL`; 0 errors. Existing pinned-dependency and missing-launcher-icon warnings remain.

Remaining risk or next gate:
- The meter face is functional and accessible but visually plain; the late-1970s styling, texture, and transitions are Phase 9 work.
- Saved rides can now be reviewed and individually deleted through the Phase 7 History and Ride Detail destinations.
- Real-device street, tunnel, weak-signal, and reboot validation of the whole flow remains a Phase 8 gate.

### Phase 7 — History and interrupted-session UX

Status: Complete
Date: 2026-09-05
Delivered:
- Added History and Ride Detail destinations reached from the meter, with back navigation and a missing-record state.
- Added Room-backed state holders and locale-aware presentation for the newest ten summaries, including end date/time, final total, distance, completed/interrupted status, wait duration, elapsed duration, and every locked tariff value.
- Added reactive single-summary observation without changing the Room schema; history and detail remain projections of durable Room state and contain no route or coordinates.
- Added individual deletion from Ride Detail with an explicit confirmation dialog, durable-success navigation, and a visible failure state.

Verification:
- Command: `GRADLE_USER_HOME=/tmp/taxi-inspector-gradle JAVA_HOME=/opt/android-studio-for-platform/jbr ./gradlew --no-daemon test assembleDebugAndroidTest`
- Result: `BUILD SUCCESSFUL`; 22 debug JVM tests passed and both instrumentation APKs compiled.
- Command: `GRADLE_USER_HOME=/tmp/taxi-inspector-gradle JAVA_HOME=/opt/android-studio-for-platform/jbr scripts/test-instrumented.sh`
- Result: `BUILD SUCCESSFUL`; all 76 API 35 emulator tests passed with 0 failures, errors, or skips. The 13 new tests cover History entry, empty/list/detail rendering, row actions and semantics, locked-summary formatting, ten-record trimming, one-row interrupted retry behavior, and confirmed deletion that preserves unrelated history.
- Command: `GRADLE_USER_HOME=/tmp/taxi-inspector-gradle JAVA_HOME=/opt/android-studio-for-platform/jbr ./gradlew --no-daemon lintDebug`
- Result: `BUILD SUCCESSFUL`; 0 errors.

Remaining risk or next gate:
- The new History UI has emulator coverage but has not yet had the Phase 8 font-scaling, TalkBack, adaptive-layout, or physical-device pass.
- Begin wall-clock time is not stored or displayed: summaries retain an end UTC timestamp and monotonic elapsed duration. Inferring begin time by subtraction could be wrong if the device clock changed during a ride; trustworthy begin display would require an explicitly persisted start UTC field and a Room migration.
- Complete Phase 8 device, accessibility, backup/privacy, disclosure, and release-log validation.

### Phase 2 amendment — distance and waiting time are mutually exclusive

Status: Complete
Date: 2026-09-04
Delivered:
- `RideEngine.onLocation` billed distance without consulting `motionState`, while `onTick` billed waiting from `motionState` alone, so both could advance over the same second. Distance now accrues only while Moving.
- Kept the billable baseline advancing on any significant segment, billed or not, so leaving Idle measures from a current point instead of back-billing movement already charged as waiting.
- Updated the design document's fare rules and acceptance criteria, and the durable fare decisions in project memory.

Why it mattered:
- One regime was documented and bounded: the three-second Idle exit confirmation bills waiting while the vehicle accelerates away.
- Two were unbounded. A crawl at or below 0.8 m/s stays Idle yet still crosses the five-metre significance threshold every few seconds; and a vehicle held between the 0.8 and 1.3 m/s thresholds never leaves Idle at all. Simulated at 1 Hz, sixty seconds in either regime billed roughly 40–55 m of distance alongside 55 s of waiting.
- The existing engine tests could not catch it: every fix in `RideEngineTest` reused one coordinate, so distance was always zero.

Verification:
- Command: `GRADLE_USER_HOME=/tmp/taxi-inspector-gradle JAVA_HOME=/opt/android-studio-for-platform/jbr ./gradlew --no-daemon test connectedDebugAndroidTest lint`
- Result: `BUILD SUCCESSFUL`; 13 debug JVM tests and 58 API 35 instrumentation tests passed, lint reported 0 errors.
- Five new engine tests drive a moving 1 Hz profile and cover the crawl, the hysteresis band, ordinary movement, the exit interval, and the absence of back-billing after leaving Idle.

Remaining risk or next gate:
- Behaviour while Moving is unchanged: the baseline advances on exactly the segments it did before, so the documented noise rule still holds.
- Real-device confirmation of the thresholds and of the no-reported-speed case remains a Phase 8 gate.

### Phase 2 amendment 2 — deterministic Weak and GPS-loss boundaries

Status: Complete
Date: 2026-09-05
Delivered:
- Made every Weak location rejection an immediate billing boundary by clearing the retained speed, motion candidates, and distance baseline; a later Good fix cannot back-bill waiting or bridge distance across the uncertain interval.
- Measured segment continuity from the latest accepted fix instead of the intentionally older distance-noise baseline, preserving cumulative sub-floor movement while accepted fixes remain continuous.
- Made the valid segment window half-open: gaps below 15 seconds may bill, while exactly 15 seconds is GPS Lost. A returning location and the timeout tick now produce identical state and fare in either event order.
- Kept bounded GPS-gap reconstruction unimplemented because its reference-taximeter mode, error evidence, and safe thresholds remain unresolved Phase 8 decisions.

Verification:
- Command: `GRADLE_USER_HOME=/tmp/taxi-inspector-gradle JAVA_HOME=/opt/android-studio-for-platform/jbr ./gradlew --no-daemon test lintDebug assembleDebugAndroidTest`
- Result: `BUILD SUCCESSFUL`; 27 debug JVM tests passed, both instrumentation APKs compiled, and lint reported 0 errors.
- Command: `GRADLE_USER_HOME=/tmp/taxi-inspector-gradle JAVA_HOME=/opt/android-studio-for-platform/jbr scripts/test-instrumented.sh`
- Result: `BUILD SUCCESSFUL`; all 76 API 35 emulator tests passed with 0 failures, errors, or skips.

Remaining risk or next gate:
- The five new reducer tests cover immediate Weak freezing, no distance bridge after Weak, retained-noise-baseline continuity, both exact-boundary event orders, and 5/14/15/16/30/60/120-second gaps. Field/replay coverage of curved routes, stop-and-go ambiguity, and real outages remains part of Phase 8.
- Phase 7A saved-company work remains the next essential implementation gate before Phase 8 begins.

### Phase 4 amendment — dual-band GNSS, mock rejection, and speed confidence

Status: Complete
Date: 2026-09-04
Delivered:
- Added a `GnssStatus` subscription beside the existing GPS subscription, and `GnssBandClassifier`, which marks a fix dual-band when at least four of the signals used in it sit within 1 MHz of 1176.45 MHz.
- Carried the band, the reported speed accuracy, and the mock-provider flag through `LocationSample` as defaulted fields, so no existing call site changed and no Room migration was needed.
- `RideEngine` now lowers the movement floor from 5 m to 2.5 m when both endpoints of a segment are dual-band, refuses any mocked fix outright, and treats a reported speed whose accuracy is wider than the 0.8-1.3 m/s hysteresis band as no reported speed, falling through to its existing derived-speed path.
- Updated the design document's fare rules, timing contract, location handling, architecture summary, and acceptance criteria, and corrected its two stale claims that the app subscribes to the network provider.

Why it mattered:
- The engine's noise deadband is `max(floor, accuracy of each endpoint)`, so it already tightened itself on better hardware -- except that the 5 m constant floor bound it. On a dual-band handset reporting 2 m accuracy the app could resolve movement it was discarding.
- That floor sets a resolvable-speed threshold: at 1 Hz, 5 m per sample is 18 km/h. Below it the baseline is retained and distance lands in chord-jumps that cut corners, which under-reads real road distance in exactly the stop-and-go traffic a taxi meter spends its time in. Under-reading makes an honest meter look inflated, which is the wrong direction of error for an inspection tool.
- An app cannot request L5; the receiver decides. The only thing available is observation, so the band is inferred from the carrier frequencies reported as used in the fix.
- Nothing previously stopped a mock provider from manufacturing distance.

Verification:
- Command: `GRADLE_USER_HOME=/tmp/taxi-inspector-gradle JAVA_HOME=/opt/android-studio-for-platform/jbr ./gradlew --no-daemon test connectedDebugAndroidTest lint`
- Result: `BUILD SUCCESSFUL`; 21 debug JVM tests and 62 API 35 instrumentation tests passed, lint reported 0 errors.
- `scripts/simulate-drive.sh` passed unchanged: distance 791.71 m against a predicted 819.44 m (40 m tolerance), idle 58017 ms against 56823 ms (3000 ms tolerance), fare exact.
- That run also settled an open question: the emulator's `geo fix` positions are not flagged as mock, so mock rejection does not break the simulated-drive harness. Had they been, the ride would have billed zero distance.

Remaining risk or next gate:
- The simulated drive exercises none of the dual-band path. `geo fix` supplies no satellite status, so every fix is `Unknown` and takes the same 5 m floor as before; the run proves the change is inert on single-band input, not that the 2.5 m floor is right.
- A baseline recovered from an interrupted ride returns as `Unknown` because the band is deliberately not persisted, so one segment after recovery uses the 5 m floor. This avoids a schema version for a negligible effect.
- Band is not yet visible in the UI, and a mocked fix currently reports `Weak` rather than a status of its own. Both need the band threaded through the persisted `ActiveRide` and a new `TrackingStatus` value.
- Real-device confirmation of the 2.5 m floor is a Phase 8 gate (see risk 7).

### Phase 4 amendment 2 — field validation on a Pixel 8 Pro, and two threshold corrections

Status: Complete
Date: 2026-09-04
Delivered:
- Fixed a defect in the speed-confidence gate. It tested only the reported speed accuracy and never the speed, so a vehicle at 15 m/s with +/-2 m/s speed accuracy had its speed discarded despite being unambiguously Moving. A speed is now refused only when its own uncertainty spans a decision threshold, meaning 0.8 or 1.3 m/s falls within one reported accuracy of it.
- Lowered `MINIMUM_L5_SIGNALS` from 4 to 3 on measured evidence.
- Widened the field diagnostics: the status line now reports satellites in view alongside the L5 and used-in-fix counts, and a fix the mapper refuses now logs `dropped fix` with the field that caused it. Without that second line, a dropped fix and a fix that never arrived were indistinguishable from the log.
- Made the satellite-status subscription non-blocking: it is now requested after the location subscription rather than before it, and tolerantly, so a receiver that refuses it can still bill a ride. A regression test covers it.

Field measurements (Pixel 8 Pro, Android 17, window sill, 45 samples at 1 Hz):
- `inView=48`, `usedInFix=15`, `band=Dual`. Dual-band detection works on real hardware -- the first confirmation of the feature, since no emulator can synthesise L5 satellite status.
- L5 count was 5 on 38 samples, 4 on six, and 3 on one. A minimum of 4 left almost no margin, hence the reduction to 3.
- Reported accuracy held near 4.5 m; speed accuracy ranged 0.334-0.451 m/s while stationary, against the 0.5 m/s bound the old gate used. That gate would therefore have begun refusing speeds under only slightly worse conditions.
- Indoors on a desk the same build logged `inView=0` for minutes and correctly reported `GPS searching`. An earlier report of the meter being stuck there was environmental: the receiver could not see a single satellite, while Google Maps showed a confident position from the fused provider, which this app deliberately does not use.

Why it mattered:
- Both refuted thresholds had been chosen by reasoning alone and were running within one sample of their limits in good conditions. The speed gate was worse than tight -- it was wrong, and it degraded exactly the weak reception it was meant to protect.
- Android documents the reported speed as Doppler-derived and its accuracy at the 68th percentile. Preferring it over position differencing follows the platform's own guidance; discarding it wholesale did not.

Verification:
- Command: `GRADLE_USER_HOME=/tmp/taxi-inspector-gradle JAVA_HOME=/opt/android-studio-for-platform/jbr ./gradlew --no-daemon test lintDebug` then `ANDROID_SERIAL=emulator-5554 ... connectedDebugAndroidTest`
- Result: `BUILD SUCCESSFUL`; 22 debug JVM tests and 63 API 35 instrumentation tests passed, lint reported 0 errors.
- The same instrumentation run against the Pixel 8 Pro passed 46 of 63 at the time; the 17 failures were every Compose UI test, an Espresso/Android 17 incompatibility rather than an app fault, resolved by the tooling amendment below.

Remaining risk or next gate:
- The 2.5 m floor is still unexercised; see risk 7 for the test that would exercise it.
- Running `connectedDebugAndroidTest` with both the emulator and a phone attached targets both, so a locked phone or the Espresso incompatibility fails the whole task. Pass `ANDROID_SERIAL` to choose one.

### Tooling amendment — Espresso 3.7.0 unblocks real-device UI tests

Status: Complete
Date: 2026-09-04
Delivered:
- Pinned `androidx.test.espresso:espresso-core` to 3.7.0 in the version catalog and added it as an explicit `androidTestImplementation`. The Compose BOM resolves espresso 3.5.0 transitively, and nothing else in the build raised it.
- Raised `androidx.test` core/rules to 1.7.0, runner to 1.7.0, and `androidx.test.ext:junit` to 1.3.0 to match.
- Pinned `scripts/test-instrumented.sh` to the emulator with `ANDROID_SERIAL`. `connectedDebugAndroidTest` enrols every attached device, so a phone connected for field testing silently joined the run and failed it.
- Documented the physical-device preparation in `development-environment.md`.

Why it mattered:
- Espresso before 3.7.0 obtained the input manager through a reflective `InputManager.getInstance()`, which Android 16 removed. On an Android 17 device every Compose UI test failed in `Espresso.onIdle` with `NoSuchMethodException`, which is 17 of the 63 instrumentation tests. Espresso 3.7.0 uses `getSystemService` instead.
- That blocked real-device UI validation entirely, which Phase 8 depends on.

Verification:
- Command: `ANDROID_SERIAL=39261FDJG006MZ ... ./gradlew --no-daemon connectedDebugAndroidTest` on a Pixel 8 Pro (Android 17)
- Result: `BUILD SUCCESSFUL`; 63 of 63 passed, up from 46.
- Emulator unaffected: `ANDROID_SERIAL=emulator-5554 ... test lintDebug connectedDebugAndroidTest` passes 22 JVM and 63 instrumentation tests with 0 lint errors.
- The upgrade alone took the device from 17 failures to two; the remaining two were the screen timing out mid-run, and disabling animations plus `svc power stayon true` cleared them. Both steps are needed, and neither is a code fault.

Remaining risk or next gate:
- Compose BOM 2024.12.01 still resolves espresso 3.5.0, so the explicit pin must stay until a BOM ships a version at or above 3.7.0. Removing it would silently reintroduce the failure on modern devices.

### Phase 7A step 7A.2 — Room version 2: taxi companies, selection, and locked labels

Status: In progress
Date: 2026-09-08
Delivered:
- Added `TaxiCompanyEntity` (id, name, `nameKey`, three canonical decimal strings) under a unique `nameKey` index, and `TaxiCompany` in the pure `ride` domain carrying the ten-company and 80-character limits and the locale-independent `nameKey()` rule.
- Replaced the three tariff columns on `app_settings` with a nullable `selectedCompanyId`, so a selection can no longer disagree with the tariff it names.
- Added a nullable locked `companyName` to `active_ride`, `ride_summary`, `ActiveRide`, and `RideSummary`. `RideEngine.start` now takes a `TaxiCompany` rather than a `Tariff`, so a ride cannot lock a name from one company and a tariff from another, and `RideEngine.finish` carries the label into the summary.
- Bumped the database to version 2, exported `2.json`, and registered `MIGRATION_1_2` with no destructive fallback. `RideDao.startRide()` resolves the selection inside its own transaction and creates nothing when it is missing or stale.
- Added `observeCompanies()`, `observeSelectedCompany()`, and `selectedCompany()` to `RoomRideRepository`. The observable selection is composed from two single-table flows, so a change to either the selection or the company invalidates it and a stale selection reads as no selection.
- Kept `observeTariff`, `currentTariff`, and `saveTariff` as documented interim bridges over the selected company, leaving Meter, Tariff, History, and the tracking service untouched until step 7A.4 replaces them.

Why the shape:
- `nameKey` is a stored column rather than a `COLLATE NOCASE` index because NOCASE folds ASCII only. A Cyrillic or Greek name would otherwise have admitted two indistinguishable picker entries.
- `app_settings.selectedCompanyId` carries no foreign key. `ON DELETE SET NULL` would clear a selection invisibly, whereas the product rule requires the deleting transaction to clear it and Start to reject a stale selection with an actionable message.
- The migrated placeholder company is named "Unnamed company", and pre-company rides keep `companyName` NULL. Writing a label onto those rows would invent a business identity; NULL lets the UI render its own legacy label and keeps the string out of the database.

Verification:
- Command: `GRADLE_USER_HOME=/tmp/taxi-inspector-gradle JAVA_HOME=/opt/android-studio-for-platform/jbr ./gradlew --no-daemon test lintDebug assembleDebugAndroidTest`
- Result: `BUILD SUCCESSFUL`; 27 debug JVM tests passed and lint reported 0 errors.
- Command: `GRADLE_USER_HOME=/tmp/taxi-inspector-gradle JAVA_HOME=/opt/android-studio-for-platform/jbr scripts/test-instrumented.sh`
- Result: `BUILD SUCCESSFUL`; 81 of 81 API 35 emulator tests passed with 0 failures, errors, or skips. Six migration tests replace the single version-1 fixture test: exact-value placeholder migration, a ride started from the migrated selection, case-insensitive duplicate rejection, an active ride keeping its own locked tariff with no label, summaries surviving in newest-first order, and a tariff-less version-1 database migrating to no company and rejecting Start.
- The duplicate-rejection test earned its place immediately: company writes were first written with `OnConflictStrategy.REPLACE`, which silently deleted the existing company sharing a name key instead of rejecting the insert. Company inserts now abort on conflict, with an explicit `updateCompany` for edits.
- One `connectedDebugAndroidTest` run reported a build failure with no test output and an empty result XML, directly after the APKs were rebuilt; an identical rerun passed 81 of 81. Recorded as the known emulator-runner flake rather than an app defect.
- Black-box upgrade run on the API 35 emulator, because `MigrationTestHelper` proves the SQL but not that the shipped app upgrades itself. The pre-migration APK was built from commit `c4a9f1e`, installed clean, and driven through its own UI to save the tariff `3.45 / 1.05 / 0.123456`, complete one ride, and leave a second ride Running. Installing the version-2 APK over it with `adb install -r` then produced:
  - `pragma user_version` 2 with a new `taxi_company` table holding `migrated-v1-tariff | Unnamed company | unnamed company | 3.45 | 1.05 | 0.123456`, and `app_settings` holding `1 | migrated-v1-tariff`;
  - the interrupted ride and both summaries keeping their own tariff strings with `companyName` NULL, including the completed total `3.462349715199999999958848` unchanged to its last digit;
  - recovery working across the upgrade: the install killed the process, so the Running row opened as `PendingInterrupted` and the meter offered Save as interrupted and Discard, showing its locked tariff;
  - History and Ride Detail rendering the migrated records, and a new ride locking `Unnamed company` with the migrated tariff into both the active row and its saved summary; and
  - the interim tariff editor retariffing that same company to `9.875/km` without adding a second row, changing the selection, or rewriting the `1.05` already locked into saved rides.
- Command: `GRADLE_USER_HOME=/tmp/taxi-inspector-gradle JAVA_HOME=/opt/android-studio-for-platform/jbr scripts/simulate-drive.sh`
- Result: `All checks passed`; distance 791.7 m against 819.4 m expected (40 m tolerance), idle 58,017 ms against 56,842 ms expected (3 s tolerance), and the persisted total `2.32675630216744740240` matching the engine's own arithmetic exactly. The script previously seeded a tariff straight into `app_settings`, which the schema change made impossible; it now types the tariff into the real screen, so the run also proves a first install creates the placeholder company and its selection through `TariffViewModel`.

Remaining risk or next gate:
- Steps 7A.3 and 7A.4 remain, and until 7A.4 lands the app still edits a single tariff and shows no company name; the interim bridge keeps exactly one placeholder company in the database.
- The migration is proven only against synthetic version-1 fixtures on the emulator. Phase 8 should upgrade a real device across it.

### Phase 7A steps 7A.3–7A.5 — company transactions, the management flow, and the Meter selector

Status: Complete
Date: 2026-09-09
Delivered:
- Added transactional `createCompany`, `updateCompanyDetails`, `selectCompany`, and `deleteCompany` to `RideDao`. Every guard runs inside the transaction, so a concurrent add cannot pass the ten-company limit or the duplicate-name rule between the check and the insert, and no company is ever evicted to make room.
- Only the very first company selects itself. Adding one after the selected company was deleted does not silently choose for the user, which is what the product rule requires.
- Deleting the selected company clears the selection in the same transaction, so Start stays unavailable until another is chosen explicitly.
- Added the `ui/companies/` package: the Taxi companies list (radio selection, per-row Edit and Delete, Add with its limit explanation, confirmed deletion naming the company and saying what survives) and the company editor, which reuses the exact-decimal rate validation and adds trimmed, nonblank, 80-character name validation with its own messages.
- Replaced the meter's inline tariff block with a Selected company block: the name and rates stay visible, Change opens an accessible selector listing each profile with enough rate detail to tell two apart, and Manage companies opens the list. During any active phase both controls are disabled and the meter renders the ride's own locked snapshot rather than the current selection.
- Ride Detail now shows the locked company name, and a summary recorded before companies existed shows an explicit unrecorded label instead of an invented identity.
- Navigation grew to five destinations, and a first run with no companies opens company creation with no path to Start.
- Kept `ui/tariff/` as the parked anonymous single-tariff variant: it still builds and is still tested, but nothing navigates to it. Its storage path is `RoomRideRepository.saveTariff`, which writes one unnamed company row, so both variants share the version-2 schema.

Why the shape:
- `RideEngine.start` takes a `TaxiCompany` rather than a name and a tariff, so a ride cannot lock a name from one company and rates from another.
- The observable selection is composed from the settings and company flows rather than a joined query, so a selection whose company has been deleted reads as no selection instead of a dangling id.
- Company rows are one merged selectable node rather than a custom content description. A screen reader announces the name and its rates as one option, and the names stay findable by every text query, which the custom label had silently prevented.
- The list holds no transient error state. Its refusals are already durable state: a started ride disables management with a visible reason, and a company deleted elsewhere simply leaves the list.

Verification:
- Command: `GRADLE_USER_HOME=/tmp/taxi-inspector-gradle JAVA_HOME=/opt/android-studio-for-platform/jbr ./gradlew --no-daemon test lintDebug`
- Result: `BUILD SUCCESSFUL`; 27 debug JVM tests passed and lint reported 0 errors.
- Command: `GRADLE_USER_HOME=/tmp/taxi-inspector-gradle JAVA_HOME=/opt/android-studio-for-platform/jbr scripts/test-instrumented.sh`
- Result: `BUILD SUCCESSFUL`; 113 of 113 API 35 emulator tests passed with 0 failures, errors, or skips, up from 81. The new coverage is 4 repository transaction tests (concurrent adds against the limit, case-insensitive duplicates, selected-company deletion blocking Start, and edits or deletion leaving active and historic snapshots unchanged), 6 editor and 5 list state-holder tests, and 12 Compose tests across both company screens, plus meter tests for the selector, the locked snapshot, and the unrecorded-name case.
- Command: `GRADLE_USER_HOME=/tmp/taxi-inspector-gradle JAVA_HOME=/opt/android-studio-for-platform/jbr scripts/simulate-drive.sh`
- Result: `All checks passed`, driving the company editor, the meter, and the fare path black-box on a clean install; the persisted total matched the engine's own arithmetic exactly.
- Walked the real app on the emulator: a company added through the editor did not steal the selection, the Meter selector switched the whole profile durably, a ride locked `Night Cabs` with its rates into both the active row and its summary, Ride Detail showed that label, and during the ride both Change and Manage companies were inert.

Four defects the tests caught, all fixed:
- `RoomRideRepository.createCompany` passed the raw typed name to `TaxiCompany`, whose trimmed-name invariant then threw instead of storing `"  CITY taxi "` as a duplicate of `City Taxi`. The repository now trims at the boundary.
- Company rows used `clearAndSetSemantics`, which removed the company names from the semantics tree entirely, so no text query could find them.
- A Compose typing test rendered a screen that could not hold what it reported, so every field reported an extra empty change; it now uses a stateful render like the tariff typing test.
- One new test asserted the company list and the selection in the same snapshot. Deleting touches both tables, so the two flows can settle in either order; the test now waits for the state they converge on. No stale selection can be shown either way, which is why the selection is composed rather than joined.

Remaining risk or next gate:
- The whole company flow has emulator coverage only. Phase 8 owns the TalkBack, font-scaling, adaptive-layout, and physical-device pass, and should upgrade a real device across `MIGRATION_1_2`.
- An edit re-saves untouched rates from their displayed form, so a stored `2.40` becomes `2.4`. The values are numerically identical and display identically, because configured costs omit trailing zeroes by design.

### Phase 8 amendment — a per-company waiting crossover, and two distances on a saved ride

Status: Complete
Date: 2026-09-12
Delivered:
- **The waiting crossover is now stored per company, not derived from the money rates.** `Tariff` gained a fourth component, `waitingCrossoverKilometersPerHour`, and `Tariff.crossoverSpeedMetersPerSecond` (the `perMinuteStillRate / perKmRate` getter) was deleted. `RideEngine.reconcile`, `labelOnHold`, `labelOnTimeClose` and the tick label fallback all read the locked ride's own value through one exact, division-free comparison (`metres × 3600 ≥ kmh × millis`). The company editor gained a fourth field, pre-filled at 8 km/h for a new company and validated to `0 < v ≤ 30`; the tariff summary line on Meter, the company list and the selector all show it, since two profiles could otherwise differ only in the value that decides what gets billed.
- **A saved ride now records two distances.** `ActiveRide`/`RideSummary` gained `travelledDistanceMeters`, committed alongside `distanceMeters` on every closed interval — both the distance-won and the time-won branch — so it equals what was billed plus whatever a slow interval redirected to the waiting tariff. Ride Detail shows *Billed distance* and *Distance travelled* beside the existing *Wait time*. It has no provisional counterpart by design: a held, sub-deadband chord commits to neither figure, so GPS jitter is never published as movement. `ActiveRide.init` now requires `travelled ≥ billed`.
- Room schema version 3 with `MIGRATION_2_3`, adding both columns non-destructively in one migration. Configuration takes a default and an observation does not: every existing company and ride gets the 8 km/h crossover, an in-flight active ride's travelled distance is backfilled from its own billed distance, and an existing summary's is left NULL — rendered as *Not recorded* rather than a backfilled guess.
- The parked anonymous editor (`ui/tariff/`) writes the product default and gained no field; `RideDao.saveSelectedCompanyTariff` copies the crossover so a retariff through it cannot silently reset one.
- Trace metadata replaced the derived `crossoverSpeedMetersPerSecond` with the stored `waitingCrossoverKilometersPerHour`, `fareModel` became `perCompanyCrossover-perClosedInterval`, and `TraceReplayTest` prints the crossover it replayed under, so a replay that fell back to the default is visible rather than silent.

Why the shape:
- 8 km/h is a **product default, not a regulatory value**. Bulgaria's regulated figure is 5 km/h and other EU states allow up to 20, which is exactly why no single constant and no formula on the two rates is right for every profile. The 2026-09-12 field drive is what ruled out the derived crossover: it billed 7 km/h city traffic entirely as waiting time (risk 8a below).
- The field is `DecimalAmount`, not `Double`: it is user-entered through the same grammar as the three rates, stored in the same canonical-string column style, and it decides a billing attribution, so the comparison stays exact end to end. `Double` appears only in `waitingCrossoverMetersPerSecond`, used where the engine compares against a measured Doppler speed.
- The field is named `travelledDistanceMeters`, and the UI says *Distance travelled*, never "actual": it is a sum of chords with a noise deadband, so it is a lower bound on the physical path, and this app does not claim measurements it cannot make.
- `Tariff` has no constructor default for the crossover, so the compiler enumerated every construction site rather than letting a wrong value enter a ride silently.

Verification:
- Command: `GRADLE_USER_HOME=/tmp/taxi-inspector-gradle JAVA_HOME=/opt/android-studio-for-platform/jbr ./gradlew --no-daemon test lintDebug assembleDebugAndroidTest`
- Result: `BUILD SUCCESSFUL`; 89 debug JVM tests, 88 passed and one skipped by design (`TraceReplayTest`, which runs only when handed a captured trace), lint reported 0 errors, and both instrumentation APKs built. The JVM suite grew by nine: four crossover tests (reconcile reading the ride's own tariff, the same 7 km/h crawl billing as time at 17.5 km/h and as distance at 3 km/h, the 8 km/h default separating a crawl from ordinary driving, and the exact tie at 18 km/h), four travelled-distance tests (distance-won, time-won, held, and outlier/relocation), and `finish` carrying travelled distance into the summary, plus a new invariant assertion inside the existing partition property test and the two `Tariff` construction tests replacing the deleted derived-crossover ones.
- `RideEngineTest`'s fixture was given an explicit 17.5 km/h crossover, exactly the figure `1.20`/`0.35` used to derive, so every previously tuned expectation stayed valid under a fixture edit rather than a re-derivation. The two tests named "matches the mode S meter" were renamed to say "at this company's crossover", because that claim is now conditional on a setting.
- Command: `GRADLE_USER_HOME=/tmp/taxi-inspector-gradle JAVA_HOME=/opt/android-studio-for-platform/jbr scripts/test-instrumented.sh`
- Result: `BUILD SUCCESSFUL`; 127 of 127 API 35 emulator tests passed with 0 failures and 0 skips, up from 113. Six new `MIGRATION_2_3` tests cover the defaulted company, an edited non-default crossover locking into a started ride, the active-ride backfill, the unrecorded summary, a post-upgrade ride recording both distances independently, and a 1→3 chain validating the exported `3.json`; the Ride Detail tests gained the two-distance rows and the unrecorded case.
- The same suite was also attempted on the Pixel 8 Pro and is **not** a clean result there: 40 of its 46 failures were `No compose hierarchies found in the app`, the documented risk-9 runner caveat, because the keyguard re-engaged mid-run even with `stay_on_while_plugged_in` set and the animation scales at 0. `svc power stayon true` keeps the display awake but does not keep the device unlocked, so a physical-device UI run needs the screen unlocked for its whole duration — worth adding to `development-environment.md`'s device checklist before the Phase 8 device pass repeats it.

Remaining risk or next gate:
- Three defects this change introduced were caught by the suite and fixed: five test call sites set `distanceMeters` through `.copy()` without the matching travelled distance and tripped the new invariant, and the version-1 migration test's Room builder registered only `MIGRATION_1_2` while `@Database` had moved to 3.
- The crossover is deliberately **not** shown on Ride Detail: a pre-version-3 summary carries the schema default rather than what it was actually billed under, so showing it would assert something untrue about a historic ride. Revisit once every stored row carries a real value.
- Travelled distance is not shown live on the Meter. The data is on `ActiveRide` and persisted, so adding it later needs no schema work; whether the live meter should show a second, deliberately unbilled number is a Phase 9 copy-and-layout question with a real counter-argument (live transparency is arguably the product's point).
- This does **not** advance the step 8.3 gate. A user-set crossover makes matching a specific meter partly a configuration question, and the reference-taximeter fare comparison is still owed. The cheapest first validation costs no field trip: replay the captured 2026-09-12 trace against the new engine and check the travelled-versus-billed gap against the 1 053 m of `ClosedTime` chord already visible in it.
- 8 km/h itself is unvalidated. It is a chosen default sitting between Bulgaria's 5 and the EU's 20, and the 82.36 % billed/reference ratio in risk 8a was measured at a 20 km/h crossover and cannot be reused to judge it.

## Update template

Copy this section at the end of each completed or blocked phase.

```text
### Phase N — <name>

Status: Complete | Blocked
Date: YYYY-MM-DD
Delivered:
- ...

Verification:
- Command/test/device check: ...
- Result: ...

Remaining risk or next gate:
- ...
```
