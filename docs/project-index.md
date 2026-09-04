# Taxi Inspector — Project Index

## Start here

This is the repository’s navigation map. New agents first read root `AGENTS.md`, `agent-brief.md`, and `build-status.md`; they then use this document immediately before inspecting or changing a relevant source area.

## Product at a glance

Taxi Inspector is a small offline Android application for independently estimating a taxi fare during a ride. Users enter an initial tax, per-kilometre rate, and per-minute waiting rate in any consistent local tariff unit. The app does not identify or convert currencies, retain routes, or contact a server.

The safety priority is explainability: uncertain GPS data freezes billing rather than creating an inferred charge.

## Documentation index

| Document | Read when | Purpose |
| --- | --- | --- |
| `../AGENTS.md` | Every session | Mandatory startup, product invariants, and verification command. |
| `agent-brief.md` | Every session | One-screen project context, authority map, and session protocol. |
| `build-status.md` | Every session | Phase tracker, proof of completed work, risks, and next gate. Update after each phase. |
| `project-memory.md` | Before changing durable rules, architecture, or environment | Full record of durable decisions and verified environment facts. |
| `implementation-plan.md` | Before implementation | Ordered delivery plan and phase exit criteria. |
| `taxi-inspector-design.md` | Product/UI/rule changes | User flow, tariff semantics, fare/GPS rules, privacy, and acceptance criteria. |
| `code-structure.md` | Architecture/data/service changes | Dependencies, packages, persistence, service lifecycle, and test strategy. |
| `development-environment.md` | Build/tooling work | Verified Android Studio/JBR/SDK paths and local setup. |

`build-status.md` is the only live implementation-status document. This index intentionally does not duplicate phase completion, verification results, or current risks.

## Source index

| Area | Path | Current responsibility |
| --- | --- | --- |
| Application shell | `app/src/main/java/com/taxiinspector/MainActivity.kt` | Edge-to-edge Compose host; it only sets `TaxiInspectorApp()` and owns no ride state. |
| Application composition | `app/src/main/java/com/taxiinspector/TaxiInspectorApplication.kt` | Creates the explicit application container. |
| Exact decimal units | `app/src/main/java/com/taxiinspector/core/decimal/DecimalAmount.kt` | Parse, preserve, and format currency-neutral tariff units. |
| Clock boundary | `app/src/main/java/com/taxiinspector/core/time/` | Monotonic elapsed time for billing and UTC time for history. |
| Pure fare/session domain | `app/src/main/java/com/taxiinspector/ride/` | Tariff, fare calculation, state models, GPS inputs, and `RideEngine`. Must stay Android-free. |
| Local persistence | `app/src/main/java/com/taxiinspector/data/rides/` | Room entities, mappings, DAO, database, repository, and app container. |
| GPS location adapter | `app/src/main/java/com/taxiinspector/data/location/` | Android-free `LocationClient` boundary, the `LocationManager.GPS_PROVIDER` adapter, and the `GnssStatus` carrier-frequency band classifier. |
| Foreground tracking | `app/src/main/java/com/taxiinspector/tracking/` | Non-sticky service, serialized ride owner, commands, notifications, prerequisite checks, ownership binding, and recovery coordination. |
| Meter UI | `app/src/main/java/com/taxiinspector/ui/` | `TaxiInspectorApp`, the vintage theme, and `meter/` + `tariff/`: immutable UI state, actions, one-off effects, the meter face, and tariff entry. It renders state and sends commands; it never owns a ride or reads location. |
| Unit tests | `app/src/test/java/com/taxiinspector/` | JVM tests for decimal parsing, fare calculation, and core ride state. |
| Android integration tests | `app/src/androidTest/java/com/taxiinspector/` | API 35 Room, GPS adapter, foreground service, notification action, recovery, Compose meter-screen, and meter state-holder tests. |
| Room schema | `app/schemas/` | Versioned exported schema. Keep it updated with intentional schema changes. |
| Android resources | `app/src/main/res/` | Vintage palette, launcher/notification resources, and every user-facing string. Visual refinement remains Phase 9 work. |
| Local dev/emulator scripts | `scripts/` | Boot/install/launch, CI-mirroring check, instrumented-test runner, and a black-box simulated-drive test; see `development-environment.md`. |

## Current implementation status

Read `build-status.md` for the current phase, implemented boundary, test evidence, active risks, and next gate. Do not use this index as a progress report.

## Target architecture direction

```text
Compose UI → ride domain ← tracking service
     │                         │
     └──── observes Room repository ────┘
                    │
             Room / Android adapters
```

- `ride` is pure Kotlin and owns all fare decisions.
- `data` adapts Room and, later, Android location.
- `tracking` serializes all running-session mutations.
- `ui` renders state and sends actions; it never calculates fares or requests locations directly.

This describes the intended end state. Consult the source index and `build-status.md` before assuming a target package or class exists.

## Task routing

| If the task concerns… | Read first | Update when changed |
| --- | --- | --- |
| Fare, input, rounding, GPS thresholds, state semantics | `taxi-inspector-design.md` and `ride/` tests | Design, tests, project memory if durable decision changes. |
| Room schema, history retention, active snapshot | `code-structure.md` and `data/rides/` | Exported schema, build status, and migration tests. |
| Location, service, notification, permission | Design background/status sections and code structure flow | Both design docs, manifest, tests, build status. |
| Compose screen/control/accessibility | Design screen sections and code structure UI section | UI tests, build status, design if interaction changes. |
| Dependency/tooling/environment | `development-environment.md` | Version catalog, environment notes, build status. |

## Coding-agent task protocol

1. Read the mandatory startup documents and the task-routed specification.
2. Read the relevant phase and exit criteria in `implementation-plan.md`.
3. Inspect the actual source paths in the source index and confirm the live state in `build-status.md`.
4. Make the smallest change that completes the current phase gate; preserve domain boundaries.
5. Add or update the appropriate test before declaring a behaviour complete.
6. Run the relevant verification, then update `build-status.md`. Update project memory only when a durable decision or environment fact changed.

## Required verification

Run the relevant test subset while iterating and, before handoff, run:

```bash
GRADLE_USER_HOME=/tmp/taxi-inspector-gradle \
JAVA_HOME=/opt/android-studio-for-platform/jbr \
./gradlew --no-daemon test
```

When an Android/device feature is added, add the appropriate instrumented or manual validation described in `implementation-plan.md`; JVM tests alone do not prove foreground-service or GPS behaviour.
