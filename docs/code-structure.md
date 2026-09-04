# Taxi Inspector — Code Structure

## Goal and scope

This structure supports a single Android application with an on-device tariff, GPS-driven active ride, foreground tracking service, and a local history of ten rides. It deliberately uses **one Gradle app module**. Splitting such a small offline application into feature modules would add build and dependency complexity without a present benefit.

The design separates Android UI, storage/location adapters, and the small set of pure fare rules. It follows unidirectional data flow: the UI sends actions, state holders coordinate them, and immutable state flows back to the UI. This is a lightweight application of Android’s UI/data-layer guidance, not a multi-module “clean architecture” implementation. [Android architecture guide](https://developer.android.com/topic/architecture)

## Technology choices

| Concern | Choice | Why |
| --- | --- | --- |
| Language | Kotlin | Idiomatic Android, null safety, coroutines. |
| UI | Jetpack Compose + Material 3 | A concise, state-driven way to build the custom meter, dialogs, and history. |
| State | `ViewModel`, `StateFlow`, lifecycle-aware collection | UI survives rotation and reacts to the single source of truth. |
| Background work | `RideTrackingService`, a `location` foreground service | An active, user-visible ride continues with the screen locked or app backgrounded. |
| GPS | `LocationManager` GPS provider plus `GnssStatus` | Fare distance is GPS-only; no Google Play Services dependency is required. Satellite status is read only to tell a single-band fix from a dual-band one. |
| Settings, active ride, and history | Room | One private, local source of truth with transactional start, completion, recovery, and history trimming. |
| Object construction | Small `AppContainer` | Explicit construction is clearer than a DI framework at this size. |
| Tests | JUnit, kotlinx-coroutines-test, Room tests, Compose UI tests | Covers fare correctness first, then Android integration. |

Use a Gradle version catalog in `gradle/libs.versions.toml`, Kotlin Symbol Processing (KSP) for Room code generation, and Android Lint in every build. Compose is the recommended modern Android UI toolkit, while Room is appropriate for structured local data. [Android UI architecture guidance](https://developer.android.com/topic/architecture/ui-layer), [Room overview](https://developer.android.com/training/data-storage/room)

## Language, toolchain, and library set

### Language and build baseline

- **Kotlin** is the application language. Do not introduce Java production code; Android framework interoperability remains available when required.
- Use the **Kotlin Android** and **Kotlin Compose compiler** Gradle plugins, the current stable Android Gradle Plugin compatible with them, and a **Java 17** toolchain.
- Support Android 7.0 / API 24 and above, with the current stable Android `compileSdk` and `targetSdk` at implementation time.
- Use coroutines and `Flow` for all asynchronous and observable work. A `suspend` function performs one operation; `Flow` represents ongoing state such as a ride, history, or tariff.
- Put every dependency version in `gradle/libs.versions.toml`. Use the Compose BOM for mutually compatible Compose artifacts; do not scatter version strings through module build files.

### Production dependencies

| Library / API | Use in Taxi Inspector |
| --- | --- |
| Kotlin standard library and `java.math.BigDecimal` | Exact decimal calculations in user-defined tariff units; no currency code or conversion is modelled. |
| `androidx.core:core-ktx` | Kotlin-friendly Android APIs. |
| `androidx.activity:activity-compose` | Compose activity host and permission-launcher integration. |
| Compose BOM, `ui`, `ui-tooling-preview`, `foundation`, `material3` | The vintage meter, forms, dialogs, lists, theme, and previews. |
| `androidx.lifecycle:lifecycle-runtime-compose`, `lifecycle-viewmodel-ktx`, `lifecycle-viewmodel-compose` | Lifecycle-aware `Flow` collection and screen state holders. |
| `androidx.navigation:navigation-compose` | Meter, History, and Ride Detail destinations. |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | Service/controller concurrency and observable state. |
| `androidx.room:room-runtime`, `room-ktx`, and Room KSP compiler | Tariff, active-ride snapshot, and transactional ten-record history. |
| Android platform `LocationManager`, `Service`, `NotificationManager` | GPS-only location, foreground tracking, and persistent ride notification; no external location SDK. |

The app should use stable releases that are mutually compatible when implementation begins, rather than hard-coding library versions in this document. Compose and its compiler must be selected as a compatible set through the Compose BOM and Kotlin tooling.

### Test and quality dependencies

| Library / tool | Use |
| --- | --- |
| JUnit 4 and `kotlinx-coroutines-test` | Fast JVM tests for decimals, fare calculation, reducer, and ViewModels. |
| `androidx.test` runner, rules, and core-ktx | Device/emulator instrumentation foundation. |
| `androidx.room:room-testing` | In-memory Room transaction and migration tests. |
| Compose UI test JUnit 4, manifest, and tooling | UI semantics, visual state, dialogs, and accessibility tests. |
| Android Lint | Build-time Android correctness checks. |

Prefer fakes over mocking: `FakeLocationClient`, `FakeClock`, and fake repositories make fare and lifecycle tests readable. Add no mocking framework unless a real integration boundary cannot be tested with a fake.

### Deliberate exclusions

- **No Google Play Services location / Fused Location Provider:** the product explicitly meters GPS-only fixes, and `LocationManager` meets that need without a Play Services dependency. This is a deliberate departure from mainstream Android guidance, which recommends the fused provider: its sensor dead reckoning would keep producing positions through tunnels and underground parking, and billing inferred movement would contradict the rule that the app never charges across an unobserved gap.
- **No WorkManager:** WorkManager is not suitable for a live, second-by-second, user-visible ride; the location foreground service is the correct owner.
- **No networking, analytics, crash-reporting, maps, or account SDKs:** they do not serve the offline inspector use case and would expand privacy obligations.
- **No floating-point money library:** `BigDecimal` is in the platform and directly expresses the planned exact-decimal tariff-unit rules.
- **No Hilt, DataStore, multi-module, or generic clean-architecture framework:** one app module, one Room database, and an explicit application container are clearer at this scale.

## Target package layout

This tree is the intended end-state layout after the remaining implementation phases. It is not a claim that every listed file already exists. Before adding or changing code, use `project-index.md` to find the current source and `build-status.md` to determine the active phase.

```text
app/
├── build.gradle.kts
└── src/
    ├── main/
    │   ├── AndroidManifest.xml
    │   ├── java/com/taxiinspector/
    │   │   ├── TaxiInspectorApplication.kt
    │   │   ├── core/
    │   │   │   ├── decimal/DecimalAmount.kt
    │   │   │   ├── time/Clock.kt
    │   │   │   ├── time/AndroidClock.kt
    │   │   │   └── result/AppError.kt
    │   │   ├── ride/
    │   │   │   ├── Tariff.kt
    │   │   │   ├── ActiveRide.kt
    │   │   │   ├── RideSummary.kt
    │   │   │   ├── RidePhase.kt
    │   │   │   ├── TrackingStatus.kt
    │   │   │   ├── LocationSample.kt
    │   │   │   ├── FareCalculator.kt
    │   │   │   ├── RideEngine.kt
    │   │   │   └── RideInput.kt
    │   │   ├── data/
    │   │   │   ├── rides/
    │   │   │   │   ├── TaxiInspectorDatabase.kt
    │   │   │   │   ├── AppSettingsEntity.kt
    │   │   │   │   ├── ActiveRideEntity.kt
    │   │   │   │   ├── RideSummaryEntity.kt
    │   │   │   │   ├── RideDao.kt
    │   │   │   │   ├── RoomRideRepository.kt
    │   │   │   │   └── RideMappers.kt
    │   │   │   └── location/
    │   │   │       ├── LocationClient.kt
    │   │   │       ├── AndroidGpsLocationClient.kt
    │   │   │       └── GnssBandClassifier.kt
    │   │   ├── tracking/
    │   │   │   ├── RideTrackingService.kt
    │   │   │   ├── RideTrackingController.kt
    │   │   │   ├── RideCommand.kt
    │   │   │   ├── RideTrackingState.kt
    │   │   │   ├── TrackingPrerequisites.kt
    │   │   │   ├── RideRecoveryCoordinator.kt
    │   │   │   ├── RideServiceCommandRouter.kt
    │   │   │   ├── RideServiceOwnershipConnection.kt
    │   │   │   └── RideNotificationFactory.kt
    │   │   ├── ui/
    │   │   │   ├── TaxiInspectorApp.kt
    │   │   │   ├── navigation/AppNavGraph.kt
    │   │   │   ├── theme/
    │   │   │   ├── meter/
    │   │   │   │   ├── MeterRoute.kt
    │   │   │   │   ├── MeterViewModel.kt
    │   │   │   │   ├── MeterUiState.kt
    │   │   │   │   ├── MeterAction.kt
    │   │   │   │   ├── MeterEffect.kt
    │   │   │   │   ├── MeterScreen.kt
    │   │   │   │   └── TaximeterFace.kt
    │   │   │   ├── tariff/
    │   │   │   │   ├── TariffRoute.kt
    │   │   │   │   ├── TariffViewModel.kt
    │   │   │   │   ├── TariffUiState.kt
    │   │   │   │   └── TariffScreen.kt
    │   │   │   └── history/
    │   │   │       ├── HistoryViewModel.kt
    │   │   │       ├── HistoryScreen.kt
    │   │   │       ├── RideDetailViewModel.kt
    │   │   │       └── RideDetailScreen.kt
    │   │   └── AppContainer.kt
    │   ├── test/java/com/taxiinspector/...
    │   └── androidTest/java/com/taxiinspector/...
    └── ...
```

`DecimalAmount` is an exact non-negative decimal value in the user’s chosen tariff unit, not a currency value: it has no currency code, symbol, or conversion operation. `core` otherwise has tiny, dependency-free cross-cutting types. `ride` contains the application’s terms and pure fare/session rules, and must not import Android framework classes, Room, or Compose. `data` adapts storage and GPS APIs to those types. `tracking` is the only foreground-service package. `ui` renders display-only state and sends actions; it does not calculate a fare or request locations directly. `AppContainer` constructs the database, repository, location client, clock, and ViewModel factories once for the application.

## Model and persistence boundaries

### Ride models

```text
Tariff(initialTax, perKmRate, perMinuteStillRate) // all in one user-defined unit
ActiveRide(id, tariff, phase, trackingStatus, distanceMeters, idleMillis,
           movingOrIdle, startedAt, lastConfirmedAt, lastBillablePoint,
           speedCandidateState)
RideSummary(id, tariff, total, distanceMeters, idleMillis, elapsedMillis,
            endedAt, status)
```

- Amounts are `BigDecimal`-backed `DecimalAmount` values. Rates and the final calculation are never `Double` or `Float`; no model carries a currency identifier because the app intentionally supports any user-entered tariff unit.
- Durations are stored as integer milliseconds; timestamps use UTC epoch milliseconds for history display.
- GPS interval calculations use elapsed-realtime values, not wall-clock time, so a device clock change cannot create a false wait charge. A sample whose elapsed timestamp is not greater than the previous accepted timestamp is rejected.
- A `LocationSample` contains only the input needed for a calculation: coordinates, accuracy, provider, speed and speed accuracy when available, the GNSS band that produced the fix, whether the fix was mocked, received elapsed time, and fix elapsed time. It is never written to ride history. A billable sample is GPS-only, unmocked, fresh, monotonic, and accurate to 20 m or better; weaker 20–60 m samples are status-only. The band is `Dual`, `Single`, or `Unknown`, and `Unknown` is treated exactly as `Single`, so a device that cannot report carrier frequency simply keeps the stricter movement floor.
- `ActiveRide` is the sole persisted active-session row. It contains one temporary last billable GPS point only; it is deleted or converted to a summary when the ride ends. Its phase is one of `Running`, `Paused`, or `PendingInterrupted`; a separate tracking status represents `Searching`, `Good`, `Weak`, `GpsLost`, or `PermissionNeeded`. Terminal outcomes exist only in summaries.

Room stores one `AppSettingsEntity` (including the current tariff), one `ActiveRideEntity`, and `RideSummaryEntity` rows. `RoomRideRepository` is the sole data access point. Starting a ride atomically reads and copies the tariff into the active row. `RideDao.finishRide()` runs in a database transaction: insert the summary, delete the active row, and remove rows older than the ten newest. This makes the ten-ride limit correct even if the process stops during a save. The current tariff is copied into every active/saved ride, so historic totals remain reproducible in their original tariff units.

The database has an explicit version and forward migrations. Destructive migration is prohibited: an update must preserve tariff and history data, and every migration has a Room migration test. The active row is updated after every accepted GPS point, every one-second tick that changes wait cost, and every pause/resume/stop state transition. At most one small local write per second occurs during an idle ride.

## Fare and session logic

`FareCalculator` is a pure function. Given a locked tariff, confirmed distance, and confirmed idle duration, it returns an unrounded decimal total; `DecimalAmount.format()` applies the documented two-decimal, half-up display rule using the device decimal separator and no currency label.

`RideEngine` is a small pure reducer:

```text
(ActiveRide, RideInput) -> ActiveRide
```

It also has `start(tariff, now)` and `finish(activeRide, now)` functions. Inputs include `Pause`, `Resume`, `LocationReceived`, `Tick`, `GpsTimedOut`, and `PermissionRevoked`. The engine only changes fare/session values. `Tick` may charge only a completed eligible elapsed interval backed by a fresh billable speed; it never makes a lost or weak GPS period billable. The service handles Android effects—starting/stopping location, saving the resulting snapshot, and updating/removing the notification. Keeping the engine Android-free makes the 15-second GPS timeout, movement thresholds, fare rules, discard, and pre-ride reset behaviour unit-testable without an effect framework.

`RideTrackingService` owns `RideTrackingController`, the one mutable in-memory active session. The controller serializes GPS callbacks, ticker ticks, and commands through one channel/coroutine, passes inputs to `RideEngine`, persists changed state, and exposes non-coordinate ownership/status through the service binder. No ViewModel, DAO callback, or composable is allowed to mutate an active ride.

## Background tracking flow

```text
Meter UI action
  → MeterViewModel validates its current UI state
  → UI requests missing Android permission / enables GPS
  → UI sends an explicit command intent to RideTrackingService
  → service verifies prerequisites again, calls startForeground(), and starts GPS
  → LocationClient + 1 Hz ticker emit RideInputs
  → RideEngine → Room snapshot/notification/state flow
  → Room flows → ViewModels → Compose UI
```

The UI is never the location owner. When the screen returns, it observes repository state and renders it; it does not reconstruct a fare from the screen lifecycle.

The service accepts only explicit commands: `START`, `PAUSE`, `RESUME`, `STOP`, and `DISCARD`. Notification actions use the same command path. `START` and `RESUME` may only be issued while the activity is visible and after precise-location, notification, and GPS-enabled checks pass. `STOP` saves a completed summary; `DISCARD` deletes the active row only after UI confirmation. Notification actions expose Pause and Stop, never Resume.

The manifest declares a non-exported service as `foregroundServiceType="location"` and includes `ACCESS_FINE_LOCATION`, `FOREGROUND_SERVICE`, and `FOREGROUND_SERVICE_LOCATION`. The service verifies prerequisites again, creates its notification channel, calls `startForeground()` promptly, and handles foreground-service start/security failures by persisting no new billable state and returning an actionable error. `POST_NOTIFICATIONS` is requested on Android 13+ and is deliberately required by this product before Start: although Android permits an FGS to launch without it, a hidden notification would undermine the app's inspectable live-ride promise. Android requires foreground-service types to be declared and checks their applicable prerequisites. [Android foreground-service declaration guidance](https://developer.android.com/develop/background-work/services/fgs/declare)

The service runs `START_NOT_STICKY`. Service liveness is not inferred from an `AppContainer` flag. When the UI opens with an active row in the `Running` phase, `RideServiceOwnershipConnection` binds **without** `BIND_AUTO_CREATE` — creating the service would answer the ownership question with a service the check itself started — and asks whether it owns that session. A ride this screen just started is never re-checked, because binding could otherwise race the service still starting up. Only after confirming that no live service owns that running session may a repository transaction change the row once to `PendingInterrupted`; a deliberately `Paused` row is rendered as paused and is never recovered as interrupted. The UI then exposes **Save as interrupted** / **Discard**. Saving is idempotent, so repeated launches cannot insert duplicate history. The app never restarts GPS or bills time between processes.

`PAUSE` persists the Paused row before it stops location updates, removes foreground mode and its notification, and stops the service. The next `RESUME` creates a foreground-service instance from that persisted row while the activity is visible. If precise permission is revoked while tracking, the service freezes the fare, persists a recoverable permission-needed state, and stops GPS work safely.

## UI structure and state

The app has four destinations: Meter, Tariff, History, and Ride Detail. Tariff is its own destination rather than a sheet on Meter, so the fare reading never shares a screen with entry fields and a soft keyboard. A first run with no saved tariff starts on Tariff; every later visit is reached from the meter's Edit control.

Each screen has one `ViewModel` with a public immutable `UiState` and a single action entry point:

```kotlin
data class MeterUiState(
    val presentation: MeterPresentation = MeterPresentation.EMPTY,
    val savedTariff: TariffSummary? = null,
    val status: MeterStatus = MeterStatus.TariffNeeded,
    val canStart: Boolean = false,
    val canEditTariff: Boolean = true,
    val isDiscardConfirmationVisible: Boolean = false,
    val recovery: MeterRecovery? = null,
    val message: MeterMessage? = null
)

fun onAction(action: MeterAction)
```

`MeterPresentation` contains only formatted fare, distance, wait-time, ride status, and GPS status—never coordinates or a raw `Location`. Compose screens collect state with `collectAsStateWithLifecycle()`. They render only from `UiState`; transient Android work (permission launcher, opening Settings, navigation, and service command intent) is emitted as one-off `MeterEffect` values over a channel and performed by the route/activity. `TaximeterFace` is a pure composable drawing component, taking formatted values and content descriptions as parameters.

Permission and GPS-provider state are Android facts a ViewModel must not read. The route inspects them whenever the screen resumes and after a permission dialog closes, and reports them as an explicit `MeterEnvironment` value. The ViewModel therefore decides *whether* a Start may proceed while the route decides *how* to ask.

Because a derived `StateFlow` may not have recomposed when an action arrives, each state holder also mirrors the durable values its actions depend on (the saved tariff and the active ride) in plain fields updated by the same collectors. An action reads the mirror; the repository transaction remains the final guard.

ViewModels never hold `Context`, `Location`, a `Service`, or mutable fare state. The service and repositories expose `Flow`s; ViewModels combine them for display. This prevents an orientation change or screen recreation from altering a ride.

## Dependency direction

```text
ui ───────────────► ride ◄──────────── tracking
 │                   ▲                    │
 │                   │                    │
 └──────────────── data ──────────────────┘
                    ▲
                    │
          Android APIs / Room
```

- `ride` contains pure fare/session rules and models.
- `data` owns Room and `LocationManager` adaptation; it exposes state through `RoomRideRepository`.
- `tracking` uses the repository, GPS adapter, and pure engine; it is the only active-session writer.
- `ui` observes repository flows and creates service command intents, never touching a DAO or `LocationManager`.
- `AppContainer` is the composition root and builds concrete objects and ViewModel factories.

Avoid generic `BaseViewModel`, repository base classes, event buses, global mutable ride state, and use-case wrapper classes. This structure is intentionally the smallest one that keeps fare logic testable and the background ride reliable.

## Error and privacy rules

- Map platform failures into explicit domain/UI states: `PermissionMissing`, `GpsDisabled`, `Searching`, `Good`, `Weak`, `GpsLost`, `Paused`, and `PendingInterrupted`. Each state defines whether billing is permitted, whether the service remains subscribed, and the available recovery action; a status-only weak fix must never mutate fare data.
- Do not log coordinates, tariffs, or totals in release builds. Errors may log a non-sensitive error code.
- All data stays in private app storage. There is no network client, analytics SDK, route export, or account layer.
- Repository writes use an IO dispatcher supplied by `AppContainer`; location events and UI state updates are serialized to prevent a Stop/Location race.

## Test plan and ownership

| Test level | Target | Key cases |
| --- | --- | --- |
| Unit | `DecimalAmount`, `FareCalculator` | decimal-comma normalization, currency-neutral formatting, precision, half-up rounding, and known fare totals. |
| Unit | `RideEngine` | non-retroactive 1 Hz idle entry, 0.8/1.3 m/s hysteresis, stale-speed expiry, weak/out-of-order fixes, 15-second GPS loss, and pause/resume. |
| Unit | ViewModels | permission/status states and actions using a fake repository. |
| Instrumented | Room DAO/repository | active-to-summary transaction, history trimming to 10, persistence after recreation. |
| Instrumented | Service/location adapter | foreground command handling, fake location updates, notification actions, discard confirmation path, service-bind-before-recovery, and idempotent interrupted-session recovery. |
| Compose UI | Meter, tariff screen, history/detail | formatted display, confirmations, accessibility labels, dynamic text. |
| Manual device | real GPS/background | screen lock, GPS toggle, weak signal, permission revocation, notification denial, process death, force-stop, reboot, and street/slow-traffic/urban-canyon/tunnel field validation. |

Keep test fakes alongside their consumers. For example, `FakeLocationClient` sits beside tracking tests rather than mocking `LocationManager`. Every documented fare threshold has a named unit test.

## Implementation order

1. Set up the project, Compose theme, version catalog, Room, Lint, and test dependencies.
2. Implement and unit-test `DecimalAmount`, ride models, `FareCalculator`, and `RideEngine` before Android UI work.
3. Add the Room entities/DAO/repository; test transaction, migration, and ten-record trimming.
4. Implement the GPS adapter and foreground service with fake-location tests for billable/weak/lost fixes, foreground start failure, and safe pause/resume.
5. Build the Meter destination and the separate Tariff destination, explicit Stop & save/Discard ride controls, permission/status handling, then attach them to the service command path.
6. Build history/detail and recovery flows.
7. Complete Compose accessibility tests and manual real-device background/GPS validation.

Before release, pin the tested Android/Compose/Room versions in the version catalog, decide whether private ride summaries participate in Android backup, and publish the estimate-only/privacy disclosure and Play data-safety declaration. This order validates the fare rules before any visual implementation and keeps Android lifecycle code away from the calculation core.
