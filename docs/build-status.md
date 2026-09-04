# Taxi Inspector — Build Status

## How to use this document

This is the single authoritative progress record for implementation work. Update it at the end of every implementation phase, not merely when code has been started. The project index and memory deliberately do not duplicate this live status.

- Use **Not started**, **In progress**, **Blocked**, or **Complete**.
- A phase is **Complete** only when its exit criteria in `implementation-plan.md` are met and its verification result is recorded below.
- Add new risks or scope changes when discovered; do not silently alter a planned product rule.
- Keep completed entries factual: files/components added, verification command, and remaining limitation.
- Update the summary, phase table, known risks, and next gate together so a new agent can rely on this document without reconciling multiple status records.

## Current summary

**Overall state: In progress — phases 0–6 complete; phase 7 is the next implementation gate.**

The project has a reproducible Gradle/Compose baseline, an exact Android-free fare core, an integration-tested Room layer, a GPS-only adapter that also reads satellite status so a dual-band fix can be told from a single-band one, a foreground-service tracking vertical slice, and a working Meter UI wired to that service. A user can now save a tariff, grant permissions, run a visible ride, pause/resume, stop and save, or discard after confirmation. History and ride detail remain unbuilt, so saved rides cannot yet be reviewed in the app.

Last verified: **2026-09-04**

```text
GRADLE_USER_HOME=/tmp/taxi-inspector-gradle \
JAVA_HOME=/opt/android-studio-for-platform/jbr \
./gradlew --no-daemon test connectedDebugAndroidTest

BUILD SUCCESSFUL
```

The debug JVM report contains 22 tests and the API 35 instrumentation report contains 63 Room/location/service/UI tests, with 0 failures, 0 errors, and 0 skipped tests. Lint reported 0 errors. The same 63 instrumentation tests now also pass on a physical Pixel 8 Pro (Android 17), with no failures.

## Phase tracker

| Phase | Status | Delivered | Verification / remaining work |
| --- | --- | --- | --- |
| 0. Freeze product contract | Complete | Product, architecture, and step-by-step plan documents define tariff units, state behaviour, GPS policy, recovery, and scope. | Decisions are documented in `taxi-inspector-design.md`, `code-structure.md`, and `implementation-plan.md`. |
| 1. Reproducible Android baseline | Complete | Gradle 8.7 wrapper, version catalog, Compose application shell, Java 17 target, Room/KSP configuration, API 35 SDK configuration, and environment record. | `./gradlew --no-daemon test` passes with Android Studio Panda JBR. |
| 2. Pure fare core | Complete | `DecimalAmount`, tariff parsing/formatting, `FareCalculator`, immutable ride models, and Android-free `RideEngine`. Distance and waiting time were made mutually exclusive after Phase 6, and mock rejection, a band-dependent movement floor, and speed-confidence gating were added with the Phase 4 amendment (see both amendments below). | 21 JVM tests cover parsing, display rounding, known fare, idle entry, GPS loss, weak fixes, distance/waiting exclusivity, mock rejection, the dual-band movement floor, and a reported speed too coarse to trust. More boundary cases remain a future test-expansion task, not a blocker to this phase. |
| 3. Durable local state | Complete | Room v1 database, tariff/settings row, active-ride snapshot, summary history, atomic start/finish/interrupted-save transactions, ten-record trimming, repository, mappers, exported schema, and migration-test fixture. | 7 API 35 instrumentation tests verify tariff locking, finish rollback, concurrent interrupted-save idempotence, recreation mapping, deletion, trimming, and the exported v1 schema. UI/service wiring belongs to their later phases. |
| 4. GPS-only location adapter | Complete | Android-free `LocationClient`, GPS-only `AndroidGpsLocationClient`, elapsed-realtime/sample mapping, provider availability checks, explicit 1 Hz subscription, and cancellation cleanup. A parallel `GnssStatus` subscription, the `GnssBandClassifier` carrier-frequency rule, and speed-accuracy/mock mapping were added afterwards (see the amendment below). | 9 API 35 fake-backed tests verify provider availability, subscription cadence, removal of both subscriptions, GPS/non-GPS mapping, optional speed, malformed-fix rejection, and band attachment including its stale and absent cases. 5 JVM tests cover the carrier-frequency rule itself. |
| 5. Foreground tracking service | Complete | Non-sticky location FGS, serialized controller, explicit commands, prerequisite rechecks, bounded fare notifications, notification Pause/Stop, ownership binder, persisted pause/stop/discard, and interrupted recovery coordination. | 14 new API 35 tests cover prerequisite/foreground failures, command serialization, permission loss, paused Resume/Stop, Discard, throttling, ownership/recovery, real service binding, screen recreation/off, and actual notification actions. |
| 6. Essential Meter UI | Complete | Vintage theme and `TaximeterFace`, `MeterScreen`/`MeterRoute`/`MeterViewModel` with immutable state, actions and one-off effects, a separate Tariff destination with exact-decimal validation, permission/GPS gating with Settings recovery, confirmed Discard, and bind-before-recovery wiring. | 32 API 35 tests cover the meter screen, tariff screen, meter state holder, and tariff state holder. Visual refinement of the meter face stays in Phase 9. |
| 7. History and recovery UI | Not started | Persistence types exist; the meter already presents and can save or discard a recovered interrupted ride. | Implement list/detail destinations, individual deletion confirmation, and end-to-end persistence tests. |
| 8. Quality, accessibility, privacy, and device validation | Not started | Build uses private local storage and has no network permission. | Complete real-device tests, accessibility tests, backup decision, disclosure, and store data-safety work. |
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
- `ride/RideEngine.kt`: pure state reducer for location, timing, idle hysteresis, pause/resume, GPS timeout, and permission loss
- `ride/*`: Android-free models and states

### Persistence

- `AppSettingsEntity`: one editable tariff
- `ActiveRideEntity`: one compact active snapshot; only a temporary point baseline, never a route
- `RideSummaryEntity`: completed/interrupted summaries
- `RideDao.finishRide()`: summary insertion, active-row deletion, and history trimming in one transaction
- `RoomRideRepository.startRide()`: atomically locks the saved tariff into the new active session
- `app/schemas/.../1.json`: exported Room version-1 schema

### Location adapter

- `LocationClient`: Android-free GPS availability and sample-flow boundary for the future tracking service
- `AndroidGpsLocationClient`: GPS-provider-only 1 Hz subscription with elapsed-realtime domain mapping, plus speed accuracy and the mock-provider flag
- `GnssBandClassifier`: marks a fix dual-band when enough of the signals used in it are L5-class (1176.45 MHz +/- 1 MHz), which covers GPS L5, Galileo E5a, BeiDou B2a, QZSS L5 and NavIC L5
- A parallel `GnssStatus` subscription carries the latest band observation forward to fixes received within five seconds of it; a stale or absent observation leaves the fix `Unknown`
- Location collection cancellation unregisters the exact platform listener and the satellite-status callback, and safely tolerates permission revocation

### Meter and tariff UI

- `ui/TaxiInspectorApp.kt` and `ui/navigation/AppNavGraph.kt`: Meter and Tariff destinations; a first run with no saved tariff starts on Tariff
- `ui/theme/`: the warm paper/charcoal/yellow/LCD palette and the monospaced fare type
- `ui/meter/MeterViewModel.kt`: derives display state from Room, gates Start/Resume on permissions and the GPS provider, and emits one-off effects
- `ui/meter/MeterEffect.kt`: permission requests, Settings, service commands, and the ownership check the route performs
- `ui/meter/MeterScreen.kt` and `TaximeterFace.kt`: state-specific controls, confirmed Discard, and per-value content descriptions
- `ui/tariff/`: the tariff destination, its exact-decimal validation, and the ride lock
- `tracking/RideServiceOwnershipConnection.kt`: binds without `BIND_AUTO_CREATE` so recovery cannot be answered by a service it started

### Foreground tracking

- `RideTrackingService`: non-exported location FGS with non-sticky restart policy and local ownership binder
- `RideTrackingController`: serialized command/location/tick processing and the sole in-memory running-ride owner
- `RideNotificationFactory`: rate-bounded fare/status notification with Pause and Stop & save actions
- `RideRecoveryCoordinator`: bind-first ownership check followed by atomic interrupted recovery

## Known limitations and active risks

1. The exact fare core has targeted unit tests but does not yet cover every documented threshold, rejected segment, and interrupted-session transition.
2. On a device whose GPS fixes carry no reported speed, waiting time under-reads: derived speed is unavailable for part of a slow crawl, and a stationary vehicle's jitter keeps the engine out of Idle entirely. This follows the design rule that speed which cannot be obtained safely accrues no waiting time, and it errs towards under-reading rather than over-charging. Confirm it against a real device in Phase 8 before deciding whether the derivation needs its own last-accepted-fix reference.
3. The meter face is functional and accessible but visually plain; the late-1970s styling, texture, and transitions remain Phase 9 work.
4. Automated service tests use emulator/fake GPS inputs; real street, tunnel, and weak-signal field validation remains part of Phase 8.
5. The database is still version 1, so the migration fixture validates the exported baseline but cannot exercise a forward migration until a future schema version exists.
6. The local Android SDK path is machine-specific and remains in ignored `local.properties`; it must never be committed.
7. Dual-band detection is now confirmed on real hardware (see the second Phase 4 amendment), but the 2.5 m movement floor itself has still never been exercised. The floor is `max(2.5 m, each endpoint's accuracy)`, and the measured accuracy at a window was 4.5 m, so the accuracy term dominated and the tighter floor never bound. It only binds below 2.5 m reported accuracy, which needs open sky. Note that a stationary hold cannot test it either: distance accrues only while the engine is Moving, so a parked vehicle bills nothing whatever the floor is. The discriminating test is steady slow movement at roughly 3 m/s over a measured distance, where 1 Hz segments of about 3 m fall between the two floors.
8. Instrumentation on a physical device needs preparation the emulator does not: animations disabled and the screen held awake, or Compose tests fail intermittently with `No compose hierarchies found in the app`. The commands are in `development-environment.md`. This is a runner caveat rather than an app defect, but it will bite anyone repeating the Phase 8 device runs.

## Next phase gate

Implement Phase 7's history and interrupted-session UX:

- add History and Ride Detail destinations to `ui/navigation/AppNavGraph.kt`, reached from the meter;
- show newest-first summaries with end date/time, total, distance, and completed/interrupted status, and never a route;
- require confirmation before deleting a saved record; and
- test complete, discard, interrupted-save, deletion, and ten-record trimming end to end through the UI and repository.

The meter already presents a recovered interrupted ride and can save or discard it, so Phase 7 adds review and deletion rather than recovery itself.

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
- Saved rides cannot yet be reviewed or deleted in the app; that is the Phase 7 gate.
- Real-device street, tunnel, weak-signal, and reboot validation of the whole flow remains a Phase 8 gate.

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
