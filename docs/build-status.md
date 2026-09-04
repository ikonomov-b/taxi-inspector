# Taxi Inspector — Build Status

## How to use this document

This is the single authoritative progress record for implementation work. Update it at the end of every implementation phase, not merely when code has been started. The project index and memory deliberately do not duplicate this live status.

- Use **Not started**, **In progress**, **Blocked**, or **Complete**.
- A phase is **Complete** only when its exit criteria in `implementation-plan.md` are met and its verification result is recorded below.
- Add new risks or scope changes when discovered; do not silently alter a planned product rule.
- Keep completed entries factual: files/components added, verification command, and remaining limitation.
- Update the summary, phase table, known risks, and next gate together so a new agent can rely on this document without reconciling multiple status records.

## Current summary

**Overall state: In progress — phases 0–5 complete; phase 6 is the next implementation gate.**

The project has a reproducible Gradle/Compose baseline, an exact Android-free fare core, an integration-tested Room layer, a GPS-only adapter, and a foreground-service tracking vertical slice. The app still presents only a placeholder screen, so users cannot yet enter tariffs, request permissions, start a ride, or view history through production UI.

Last verified: **2026-09-04**

```text
GRADLE_USER_HOME=/tmp/taxi-inspector-gradle \
JAVA_HOME=/opt/android-studio-for-platform/jbr \
./gradlew --no-daemon test connectedDebugAndroidTest

BUILD SUCCESSFUL
```

The debug JVM report contains 8 tests and the API 35 instrumentation report contains 26 Room/location/service tests, with 0 failures, 0 errors, and 0 skipped tests.

## Phase tracker

| Phase | Status | Delivered | Verification / remaining work |
| --- | --- | --- | --- |
| 0. Freeze product contract | Complete | Product, architecture, and step-by-step plan documents define tariff units, state behaviour, GPS policy, recovery, and scope. | Decisions are documented in `taxi-inspector-design.md`, `code-structure.md`, and `implementation-plan.md`. |
| 1. Reproducible Android baseline | Complete | Gradle 8.7 wrapper, version catalog, Compose application shell, Java 17 target, Room/KSP configuration, API 35 SDK configuration, and environment record. | `./gradlew --no-daemon test` passes with Android Studio Panda JBR. |
| 2. Pure fare core | Complete | `DecimalAmount`, tariff parsing/formatting, `FareCalculator`, immutable ride models, and Android-free `RideEngine`. | 8 JVM tests cover parsing, display rounding, known fare, idle entry, GPS loss, and weak fixes. More boundary cases remain a future test-expansion task, not a blocker to this phase. |
| 3. Durable local state | Complete | Room v1 database, tariff/settings row, active-ride snapshot, summary history, atomic start/finish/interrupted-save transactions, ten-record trimming, repository, mappers, exported schema, and migration-test fixture. | 7 API 35 instrumentation tests verify tariff locking, finish rollback, concurrent interrupted-save idempotence, recreation mapping, deletion, trimming, and the exported v1 schema. UI/service wiring belongs to their later phases. |
| 4. GPS-only location adapter | Complete | Android-free `LocationClient`, GPS-only `AndroidGpsLocationClient`, elapsed-realtime/sample mapping, provider availability checks, explicit 1 Hz subscription, and cancellation cleanup. | 5 API 35 fake-backed tests verify provider availability, subscription cadence, exact listener removal, GPS/non-GPS mapping, optional speed, and malformed-fix rejection. |
| 5. Foreground tracking service | Complete | Non-sticky location FGS, serialized controller, explicit commands, prerequisite rechecks, bounded fare notifications, notification Pause/Stop, ownership binder, persisted pause/stop/discard, and interrupted recovery coordination. | 14 new API 35 tests cover prerequisite/foreground failures, command serialization, permission loss, paused Resume/Stop, Discard, throttling, ownership/recovery, real service binding, screen recreation/off, and actual notification actions. |
| 6. Essential Meter UI | Not started | Only a Compose placeholder exists. | Implement tariff sheet, meter presentation, permission effects, state-specific Start/Pause/Resume/Stop/Discard controls, and accessibility semantics. |
| 7. History and recovery UI | Not started | Persistence types exist only. | Implement list/detail, deletion confirmation, and save/discard interrupted recovery. |
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

### Foreground tracking

- `RideTrackingService`: non-exported location FGS with non-sticky restart policy and local ownership binder
- `RideTrackingController`: serialized command/location/tick processing and the sole in-memory running-ride owner
- `RideNotificationFactory`: rate-bounded fare/status notification with Pause and Stop & save actions
- `RideRecoveryCoordinator`: bind-first ownership check followed by atomic interrupted recovery

## Known limitations and active risks

1. The exact fare core has targeted unit tests but does not yet cover every documented threshold, rejected segment, and interrupted-session transition.
2. `MainActivity` is intentionally a placeholder; the tested service is not yet connected to production permission, tariff, or meter controls.
3. Automated service tests use emulator/fake GPS inputs; real street, tunnel, and weak-signal field validation remains part of Phase 8.
4. The database is still version 1, so the migration fixture validates the exported baseline but cannot exercise a forward migration until a future schema version exists.
5. The local Android SDK path is machine-specific and remains in ignored `local.properties`; it must never be committed.

## Next phase gate

Implement Phase 6's essential Meter UI:

- add `MeterViewModel`, immutable UI state/actions/effects, and lifecycle-safe Room observation;
- implement exact tariff entry/validation and lock editing while a ride exists;
- request precise location and notification permission only from the visible route;
- connect explicit Start/Pause/Resume/Stop/Discard actions to the tested service command path; and
- add Compose UI tests for state-specific controls, totals, confirmations, status recovery, and semantics.

Build control behavior and recovery effects before visual styling. Do not begin History UI until the Meter flow remains correct through recreation and never exposes tariff editing during an active ride.

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
