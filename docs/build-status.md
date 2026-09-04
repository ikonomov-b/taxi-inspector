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

The project has a reproducible Gradle/Compose baseline, an exact Android-free fare core, an integration-tested Room layer, a GPS-only adapter, a foreground-service tracking vertical slice, and a working Meter UI wired to that service. A user can now save a tariff, grant permissions, run a visible ride, pause/resume, stop and save, or discard after confirmation. History and ride detail remain unbuilt, so saved rides cannot yet be reviewed in the app.

Last verified: **2026-09-04**

```text
GRADLE_USER_HOME=/tmp/taxi-inspector-gradle \
JAVA_HOME=/opt/android-studio-for-platform/jbr \
./gradlew --no-daemon test connectedDebugAndroidTest

BUILD SUCCESSFUL
```

The debug JVM report contains 8 tests and the API 35 instrumentation report contains 58 Room/location/service/UI tests, with 0 failures, 0 errors, and 0 skipped tests.

## Phase tracker

| Phase | Status | Delivered | Verification / remaining work |
| --- | --- | --- | --- |
| 0. Freeze product contract | Complete | Product, architecture, and step-by-step plan documents define tariff units, state behaviour, GPS policy, recovery, and scope. | Decisions are documented in `taxi-inspector-design.md`, `code-structure.md`, and `implementation-plan.md`. |
| 1. Reproducible Android baseline | Complete | Gradle 8.7 wrapper, version catalog, Compose application shell, Java 17 target, Room/KSP configuration, API 35 SDK configuration, and environment record. | `./gradlew --no-daemon test` passes with Android Studio Panda JBR. |
| 2. Pure fare core | Complete | `DecimalAmount`, tariff parsing/formatting, `FareCalculator`, immutable ride models, and Android-free `RideEngine`. | 8 JVM tests cover parsing, display rounding, known fare, idle entry, GPS loss, and weak fixes. More boundary cases remain a future test-expansion task, not a blocker to this phase. |
| 3. Durable local state | Complete | Room v1 database, tariff/settings row, active-ride snapshot, summary history, atomic start/finish/interrupted-save transactions, ten-record trimming, repository, mappers, exported schema, and migration-test fixture. | 7 API 35 instrumentation tests verify tariff locking, finish rollback, concurrent interrupted-save idempotence, recreation mapping, deletion, trimming, and the exported v1 schema. UI/service wiring belongs to their later phases. |
| 4. GPS-only location adapter | Complete | Android-free `LocationClient`, GPS-only `AndroidGpsLocationClient`, elapsed-realtime/sample mapping, provider availability checks, explicit 1 Hz subscription, and cancellation cleanup. | 5 API 35 fake-backed tests verify provider availability, subscription cadence, exact listener removal, GPS/non-GPS mapping, optional speed, and malformed-fix rejection. |
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
- `AndroidGpsLocationClient`: GPS-provider-only 1 Hz subscription with elapsed-realtime domain mapping
- Location collection cancellation unregisters the exact platform listener and safely tolerates permission revocation

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
2. The meter face is functional and accessible but visually plain; the late-1970s styling, texture, and transitions remain Phase 9 work.
3. Automated service tests use emulator/fake GPS inputs; real street, tunnel, and weak-signal field validation remains part of Phase 8.
4. The database is still version 1, so the migration fixture validates the exported baseline but cannot exercise a forward migration until a future schema version exists.
5. The local Android SDK path is machine-specific and remains in ignored `local.properties`; it must never be committed.
6. The local emulator intermittently aborts an instrumentation run with a `UiAutomation` "already registered" / "Not connected!" error, truncating the report. It is an environment fault, not a product one: the affected tests pass on a re-run, and it has hit both the Phase 5 service tests and the Phase 6 UI tests. Re-run `connectedDebugAndroidTest` and confirm the report shows the full 58 tests before trusting a failure.

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
