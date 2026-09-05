# Taxi Inspector — Step-by-Step Implementation Plan

## Purpose and ordering principle

This plan implements Taxi Inspector as an offline, non-certified fare estimate. The implementation order deliberately proves fare correctness and preserves user data before building visual polish or secondary features.

The essential path is:

```text
reproducible build → exact fare rules → durable session state → GPS/foreground service
→ usable meter flow → failure recovery → history → accessibility and polish
```

Do not start a later phase until the exit criteria for the current phase are met. The final phase contains improvements that are valuable but must not delay a correct, safe first build.

## Guardrails that apply throughout

- Amounts are exact, non-negative decimal **tariff units**. They may represent any local currency, but the app stores no currency code, symbol, or exchange rate and displays no currency label.
- The active fare engine is the only place that calculates a total. UI code, Room entities, and location adapters never calculate a fare independently.
- Ambiguous GPS data freezes billing. The app does not estimate an unobserved gap unless Phase 8 field evidence leads to an explicit, documented bounded-reconstruction amendment; the current implementation must not be described as having that amendment.
- The foreground service is the sole owner and writer of a running ride. The UI only observes state and sends explicit commands.
- An active ride always uses the tariff locked at Start; later tariff changes cannot alter it.
- No route, raw location history, network request, account, analytics event, or advertising identifier is introduced.
- Do not add a library, module, abstraction, or feature unless it is required by a documented phase below.

## Phase 0 — Freeze the product contract

### Step 0.1: Review the planning documents as the implementation contract

Read and reconcile these documents before coding:

- `docs/taxi-inspector-design.md`
- `docs/code-structure.md`

Record implementation decisions in the code only when they exactly implement those documents. If a real-device result requires a changed threshold or behaviour, update the design document and its acceptance tests in the same change.

### Step 0.2: Define the first-build state model

Create a small state-transition table in code comments/tests before implementing Android components.

| Session phase | Tracking status | Billing | Allowed commands |
| --- | --- | --- | --- |
| Ready | Permission needed / GPS disabled | None | Save tariff, Start after recovery |
| Running | Searching / Good / Weak / GPS lost | Only Good and fresh eligible time | Pause, Stop & save, Discard ride |
| Paused | Last known status | Frozen | Resume from visible UI, Stop & save, Discard ride |
| Pending interrupted | N/A | Permanently frozen | Save as interrupted, Discard |
| Completed / Discarded | N/A | Final | None; history actions only |

Specify the exact event for every transition: Start, accepted location, weak location, 15-second timeout, Pause, Resume, Stop, Discard, permission revocation, process death, and interrupted recovery.

### Exit criteria

- The team can explain every state, transition, and user-visible control without referring to a screen implementation.
- There is no unresolved decision about currency, rounding, GPS loss, pause, stop, discard, or process-death behaviour.

## Phase 1 — Establish a reproducible Android baseline

### Step 1.1: Configure the build

- Keep one `app` module.
- Add the Kotlin Android and Compose compiler plugins, Java 17 toolchain, Compose BOM, Room/KSP, lifecycle, navigation, coroutines, and test dependencies through `gradle/libs.versions.toml`.
- Pin tested versions in the catalog; do not leave version selection to an implementation-time interpretation of “latest.”
- Enable Android Lint and make debug builds, unit tests, and lint runnable from a clean checkout.

### Step 1.2: Establish the application shell

- Create `TaxiInspectorApplication` and an explicit `AppContainer`.
- Create `MainActivity`, the Compose theme, and an empty Meter destination.
- Set `minSdk` 24 and the currently supported tested `compileSdk`/`targetSdk`.
- Do not yet request location permission or create a service.

### Step 1.3: Add only the manifest foundation

- Declare the application and launcher activity.
- Add precise-location and foreground-service declarations required for the target SDK, including the non-exported location foreground service declaration once the service class exists.
- Keep network permissions absent.

### Exit criteria

- A clean build, lint run, and empty Compose app launch successfully on an API-24-compatible emulator and a current Android emulator.
- Dependency versions are centralized and no production code uses `Double` for tariffs or totals.

## Phase 2 — Build and prove the pure fare core

This phase has no Android `Location`, `Context`, Room, Compose, or service dependencies.

### Step 2.1: Implement exact tariff-unit parsing and formatting

Create `DecimalAmount` and tariff input parsing.

- Accept ASCII digits with an optional single `.` or `,` separator and up to six fractional digits.
- Reject blank values, signs, grouping separators, multiple separators, and other ambiguous forms.
- Normalize accepted input to `BigDecimal`; never pass a decimal through `Double` or `Float`.
- Format display totals half-up to two places using the device decimal separator and no currency label.
- Format saved tariff fields without unnecessary trailing zeroes.

### Step 2.2: Implement immutable ride models

Implement `Tariff`, `ActiveRide`, `RideSummary`, `RidePhase`, `TrackingStatus`, `LocationSample`, and all ride inputs as Android-free Kotlin types.

Keep only one temporary last billable point in `ActiveRide`; summaries must not contain coordinates or a route.

### Step 2.3: Implement `FareCalculator`

Implement the documented formula using the locked tariff, confirmed distance in metres, and confirmed idle duration in milliseconds. Return the unrounded exact result; formatting is a separate presentation operation.

### Step 2.4: Implement `RideEngine` as a pure reducer

Implement `start`, `reduce`, and `finish` around immutable inputs:

- `Start`, `Pause`, `Resume`, `Stop`, and `Discard`
- `LocationReceived`
- one-second `Tick`
- `GpsTimedOut`
- `PermissionRevoked`

Implement the contract exactly:

- accepted samples are GPS-only, fresh, monotonic, and accurate to 20 m or better;
- 20–60 m samples are status-only and never change fare values;
- segments over 15 seconds, out-of-order samples, and implausible jumps are rejected;
- moving/idle hysteresis enters Idle only after five completed low-speed seconds without retroactive billing and exits only after three completed high-speed seconds;
- stale/absent speed cannot add waiting time;
- GPS loss freezes charges and the next good sample becomes a new baseline.

### Step 2.5: Write exhaustive JVM tests before adapters

Use a fake monotonic clock and deterministic samples. Cover:

- all valid and invalid decimal formats;
- half-up rounding and the known fare example;
- zero-valued tariffs and six fractional digits;
- initial tax added exactly once;
- distance rules, accuracy boundaries, and retained baseline behaviour;
- speed freshness, five-second entry, three-second exit, and hysteresis boundary values;
- pause/resume, stop, discard, permission revocation, and GPS loss;
- every state transition from Phase 0.

### Exit criteria

- Fare-core tests pass without Android instrumentation.
- Tests demonstrate that a weak, stale, missing, or out-of-order sample cannot increase the total.
- No domain type imports Android, Compose, Room, or a service class.

## Phase 3 — Add durable local state

### Step 3.1: Create the Room schema

Create a versioned private Room database with:

- `AppSettingsEntity`: one row containing the currently editable tariff;
- `ActiveRideEntity`: zero or one active snapshot, including locked tariff, phase, tracking status, confirmed components, and one last billable point;
- `RideSummaryEntity`: completed and explicitly saved interrupted rides.

Store exact decimal fields canonically as strings and durations/timestamps as integers. Do not store a route, raw location stream, or a currency field.

### Step 3.2: Implement repository transactions

`RoomRideRepository` is the only database access point outside DAO tests.

- Start atomically copies the editable tariff into the active row.
- Each accepted fare-changing update persists the active snapshot.
- Pause, resume, permission loss, and status transitions persist the changed state.
- Stop atomically inserts a completed summary, deletes the active row, and trims history to the newest ten records.
- Save interrupted is idempotent and uses the same finish transaction.
- Discard deletes only the active row after confirmation.

### Step 3.3: Add migrations and persistence tests

- Give the database an explicit initial version and forward migrations.
- Prohibit destructive migration.
- Write Room tests for start/finish atomicity, ten-record trimming, deletion, restart persistence, and no duplicate interrupted save.

### Exit criteria

- Process recreation preserves the editable tariff and active session exactly.
- An interrupted summary cannot be inserted twice.
- Eleven saved rides leave exactly the newest ten, with locked tariff values intact.

## Phase 4 — Implement location adaptation without UI

### Step 4.1: Define the location boundary

Create `LocationClient` as a narrow interface that emits domain `LocationSample` values and reports provider availability. Keep `LocationManager` and Android `Location` behind `AndroidGpsLocationClient`.

### Step 4.2: Implement GPS-only subscription rules

- Subscribe to `LocationManager.GPS_PROVIDER` at a requested one-second cadence while the service owns a Running ride.
- Use network-provider observations only for non-billable searching/status information, if used at all.
- Convert timestamps to elapsed-realtime data and reject samples that cannot satisfy the domain contract.
- Stop updates immediately on Pause, Stop, Discard, permission loss, and service destruction.

### Step 4.3: Test the adapter with fakes

Test the mapping and subscription lifecycle with a fake location client; do not depend on a real GPS receiver for rule tests.

### Exit criteria

- The fare core still has no Android dependency.
- Location subscription and cancellation are deterministic in integration tests.

## Phase 5 — Implement the foreground-service vertical slice

### Step 5.1: Build `RideTrackingService`

The service owns the only mutable active session. Serialize commands, location callbacks, and ticks through one coroutine/dispatcher so Stop cannot race a location update.

For `START`:

1. Verify precise permission, enabled GPS, editable saved tariff, and notification permission where applicable.
2. Start the service from the visible activity.
3. Create the required notification/channel and promptly promote the service to foreground mode.
4. Create/persist the locked active ride and begin GPS subscription.

If a foreground-service or permission check fails, create no billable session or cleanly roll back the just-created snapshot and return an actionable UI state.

### Step 5.2: Implement service commands and notification actions

- Support only `START`, `PAUSE`, `RESUME`, `STOP`, and `DISCARD` intents.
- Notification actions provide Pause and Stop; Resume is available only from the visible activity.
- Pause persists first, stops updates, removes foreground mode/notification, then stops the service.
- Stop persists the completed summary before stopping the service.
- Keep notification updates bounded to meaningful state/total changes, never faster than once per second.

### Step 5.3: Implement safe recovery

- Use `START_NOT_STICKY`; never silently restart tracking after process death or reboot.
- On opening a persisted Running ride, bind to the service before recovery.
- Only if no service owns that Running session, atomically convert it once to `PendingInterrupted`.
- Leave an intentionally Paused session paused.

### Step 5.4: Instrument the service

Use fake location and clock inputs to test:

- foreground start failures and permission failures;
- notification Pause/Stop paths;
- service/DB single-writer behaviour;
- screen recreation while the service runs;
- process-death recovery and idempotent interrupted saving.

### Exit criteria

- A ride can run with the screen locked, can be paused/stopped from the notification, and never bills after GPS loss.
- Killing the service leaves a recoverable, non-billing partial ride; it never resumes silently.

## Phase 6 — Build the essential Meter UI

### Step 6.1: Implement UI state and routes

Create `MeterViewModel`, `MeterUiState`, `MeterAction`, and `MeterRoute`.

- Collect repository state lifecycle-safely.
- Emit one-off effects for permission requests, Settings, navigation, and explicit service commands.
- Do not retain a `Location`, `Context`, service reference, or mutable fare calculation in a ViewModel.

### Step 6.2: Implement tariff entry

- Provide the three exact-decimal fields, inline validation, Save tariff, and current-tariff summary.
- Start is unavailable until a valid tariff has been saved.
- Disable editing while an active ride exists.
- Explain that all fields use the same user-chosen tariff unit and that no currency label or conversion is provided.

### Step 6.3: Implement the meter screen and controls

Use the Compose meter face specified in the design document. Show:

- formatted total with no currency label;
- distance, wait time, and plain-language GPS status;
- state-appropriate controls: Start, Pause, Resume, Stop & save, and confirmed Discard ride;
- status-specific recovery actions for permission and GPS settings.

Build the control behaviour before visual styling. A rider must never confuse Stop & save with Discard ride.

### Step 6.4: Add Compose UI tests

Test visible totals, state-specific controls, tariff validation, confirmation dialogs, and semantic labels.

### Exit criteria

- A user can enter a tariff, run a visible ride, pause/resume, save it, or intentionally discard it.
- The UI never shows a currency symbol or permits editing the locked tariff.
- Controls and GPS state remain correct through rotation/recreation.

## Phase 7 — Add history and interrupted-session UX

### Step 7.1: Implement History and Ride Detail

- Show newest-first summaries with end date/time, total, distance, and completed/interrupted status.
- Show the locked tariff, wait duration, elapsed duration, final total, status, and end timestamp in detail.
- Do not show or reconstruct a route.

### Step 7.2: Add destructive-action safeguards

- Require confirmation before deleting a saved history item.
- Require confirmation before discarding an active ride.
- Make Save as interrupted and Discard clear, mutually exclusive recovery actions.

### Step 7.3: Test end-to-end persistence flows

Test complete, pause/resume, discard, interrupted recovery, save interrupted, individual deletion, and trimming through UI and repository layers.

### Exit criteria

- History always reflects durable Room state and cannot show duplicate interrupted entries.
- Saved records are reproducible from their locked tariff and totals alone.

## Phase 8 — Validate quality, accessibility, and privacy

### Step 8.1: Accessibility and adaptive layout

- Ensure every action and displayed meter value has clear semantics/content descriptions.
- Maintain 48 dp touch targets and 4.5:1 text contrast.
- Test font scaling, TalkBack traversal, common phone widths, dark/light system conditions if supported, and system insets.

### Step 8.2: Field-validate GPS thresholds

Perform manual tests on real devices in:

- open streets and slow traffic;
- dense urban streets and weak-signal areas;
- tunnels/underground parking;
- screen-locked/background use;
- GPS disable/enable and permission revocation;
- notification denial, service kill, force-stop, and reboot.

Compare observed behaviour with the current design promise: weak or missing data freezes fare rather than silently billing. Record reference-taximeter fare, distance, time, calculation mode if known, signed error, and absolute error. Bucket results by uninterrupted tracking and by 5, 15, 30, 60, and 120-second outages. If the 20 m / significant-segment rules require adjustment, change the documented constants and repeat the pure-engine tests.

### Step 8.3: Resolve taximeter matching and bounded gap reconstruction

- Identify whether the intended reference meter uses single application below/above a cross-over speed, simultaneous distance/time application, or a jurisdiction-specific rule. Do not assume the current 0.8/1.3 m/s stationary hysteresis matches its tariff switch.
- Capture the reference meter's fare increment/resolution as well as its calculation mode; a continuous two-decimal estimate can otherwise disagree even when its distance and time measurements are close.
- Replay the captured field traces through the current freeze policy and an offline candidate that uses the last and next billable endpoints, elapsed gap, positional uncertainty, and average speed. Linear interpolation must not be treated as recovered route geometry. For short gaps, evaluate trusted endpoint-speed integration separately rather than assuming it improves the chord.
- For mode S, evaluate `max(distanceRate × recoveredDistance, timeRate × gapDuration)` and provisional time-rate accrual during a continuously service-owned outage. For mode D, evaluate the sum and treat it as an explicit whole-product contract change. If the target uses stationary-only waiting, do not assume endpoint average speed reconstructs a mixed interval.
- Choose or reject a maximum position-derived recovery gap, uncertainty adjustment, duration-scaled plausibility bound, and short-gap speed estimator from the measured error distribution. Decide separately whether a verified time-tariff floor may continue through longer outages. Record the decision in the design and project memory.
- Before any production reconstruction, fix immediate Weak freezing and the exact 15-second event-order boundary. Add JVM tests at 5, 14, 15, 16, 30, 60, and 120 seconds, including straight movement, curved-route under-read, stationary drift, stop-and-go ambiguity, implausible reacquisition, Pause, permission loss, process interruption, and reboot.
- For mode-S simulations with exact endpoints, prove that the candidate equals the time tariff for a stationary gap, equals the distance tariff for constant motion above cross-over, and is no greater than the reference fare for variable-speed or curved travel whose true path is at least the endpoint chord. Separately inject realistic endpoint error to measure when that lower-bound property is lost and tune or reject the uncertainty adjustment.
- If reconstruction is approved, add one explicit pure-domain gap input and an exact-once fare-attribution watermark. Keep measured and reconstructed aggregates separate, expose the estimate in Meter/Ride Detail, and migrate Room without storing a route or timestamp stream. Apply the chosen taximeter mode consistently to ordinary and reconstructed intervals; rename `idleMillis` if it becomes general tariff-time duration.
- If provisional time fare changes the total during GPS loss, add a distinct domain/UI/notification status and accessibility text; the existing “fare frozen” wording must remain reserved for intervals that cannot bill.
- Repeat the reference-taximeter drives after implementation. Accept the change only if it materially reduces absolute error without an unacceptable positive-charge bias.

### Step 8.4: Complete release privacy work

- Decide explicitly whether the private database participates in Android backup and configure it accordingly.
- Add a concise in-app estimate-only and privacy disclosure.
- Complete the store data-safety declaration consistently with the no-network/no-route design.
- Confirm release logs never contain coordinates, tariffs, or totals.

### Exit criteria

- Accessibility checks pass and all documented device scenarios have a recorded result.
- The target taximeter calculation mode and GPS-gap policy are explicitly recorded. If reconstruction is enabled, its thresholds have field evidence, its inferred contribution is disclosed, and its deterministic/domain/persistence tests pass.
- The release build accurately describes its estimate-only and data-handling behaviour.

## Phase 9 — Non-essential work, only after the essential release candidate

These tasks must not delay the correctness, safety, privacy, or accessibility work above:

1. Refine the late-1970s meter styling, animations, texture, and transition polish.
2. Add richer empty states, onboarding copy, and contextual help.
3. Improve visual presentation of history rows and detail screens.
4. Add screenshot/regression testing and performance profiling beyond functional acceptance tests.
5. Consider optional, privacy-preserving local enhancements only after a separate product decision; do not add route storage, cloud sync, maps, analytics, advertising, accounts, or currency conversion by default.

## Definition of done for version 1

Version 1 is ready only when all essential phases are complete and the acceptance criteria in `taxi-inspector-design.md` pass. The defining properties are:

- exact, currency-agnostic tariff-unit arithmetic;
- conservative GPS billing with visible quality/loss states;
- an inspectable foreground ride that does not silently resume;
- durable, reproducible local summaries without stored routes; and
- an accessible, understandable meter flow in which saving and discarding cannot be confused.
