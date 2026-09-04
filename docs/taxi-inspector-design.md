# Taxi Inspector — Design Document

## Purpose

Taxi Inspector is a small Android taximeter for checking a taxi fare in real time. A rider or inspector enters the local tariff, starts the meter, and compares the displayed total with the taxi’s own meter during a journey.

The app is an independent estimate, not a certified taxi meter. GPS accuracy, the selected tariff, map position, and local regulations can affect the result.

## Primary flow

1. The app opens to the taximeter and shows the most recently saved tariff.
2. The user opens **Tariff** and enters three monetary amounts:
   - Initial tax — charged once when a ride begins.
   - Per km rate — charged for GPS distance travelled.
   - Per minute car-still rate — charged while speed is below the still threshold.
3. The user taps **Start ride** and grants precise-location permission if needed.
4. The meter updates the total, distance, and wait time once per second while GPS fixes arrive.
5. The user can pause/resume, Stop & save the ride, or discard an active ride after confirmation. Reset is available only before a ride starts.

## Screen design

The main screen uses one focused, vintage taximeter panel rather than a dashboard.

```text
┌─────────────────────────────────────┐
│  TAXI INSPECTOR          GPS: GOOD  │
│                                     │
│     ┌─────────────────────────┐     │
│     │    ● METER RUNNING      │     │
│     │          12.85          │     │
│     │                          │     │
│     │  DISTANCE   │ WAIT TIME │     │
│     │   6.42 km   │  03:18    │     │
│     └─────────────────────────┘     │
│                                     │
│     [ Pause ]  [ Stop & save ]      │
│                                     │
│  Current tariff              [Edit] │
│  Initial 2.40 · 1.20/km · .35/min   │
└─────────────────────────────────────┘
```

### Currency and numeric format

The app does not impose, select, store, or display a currency. Amounts are neutral tariff units: the rider enters all three values in the same currency or unit used by the taxi being checked, and the total is meaningful only in that same unit. This supports any local currency without a currency list, symbol, conversion, or exchange-rate feature. The app never converts currencies.

All three costs accept non-negative whole numbers or decimal values. Users may enter a decimal point or decimal comma (for example `2`, `2.4`, or `2,40`). The input permits up to six fractional digits, which is more than enough for uncommon per-distance tariffs without making mistakes hard to spot.

Input is trimmed and must contain digits with at most one decimal separator (`.` or `,`). Thousands/grouping separators, signs, and blank values are rejected, so `1,234` is always interpreted as a decimal value rather than ambiguously accepted. Values are normalized into exact decimal numbers, never floating-point numbers. Fare components retain their precision until display; the total is rounded **half up** to two decimal places. It is shown using the device’s decimal separator and without a currency label. Configured costs omit trailing zeroes.

### Visual language

- **Mood:** a sturdy late-1970s mechanical meter—warm paper background, dark charcoal housing, yellow trim, and a muted green LCD-style display.
- **Readability:** the fare is the largest element and uses a high-contrast monospaced face. Distance and wait time are secondary but always visible.
- **Status:** a plain-language GPS status (Searching, Weak, Good, Permission needed) is shown outside the meter so it is never mistaken for a fare reading.
- **Controls:** large labelled buttons, with text as well as color, support use in a moving vehicle and accessibility.

## Tariff editing

Tariff editing is presented in a bottom section or sheet with three numeric fields. A **Save tariff** action accepts whole numbers or decimal values (with either `.` or `,` as the decimal separator), validates that all costs are non-negative, and persists them locally. On first launch there is no implied default tariff: Start remains unavailable until all three fields are saved. The current tariff remains visible beneath the meter while a ride runs.

Initial tax and rates may be changed only between rides. Starting a ride copies and locks the full tariff for that ride; the tariff editor is disabled until it ends. This makes every saved fare reproducible in its original tariff units, even though no currency is stored.

## Saved rides

A **History** control opens a newest-first list of the 10 saved rides. Each row shows its end date/time, final total, distance, and status (**Completed** or **Interrupted**). Selecting a row shows the locked tariff, distance, wait time, elapsed ride time, total, status, and end timestamp. A user may delete an individual record from this detail screen after a confirmation; there is no route export or stored route.

Tapping **Stop & save** ends and immediately saves a ride as Completed. It is a primary action beside Pause while a ride runs. A user may stop immediately after starting; that saves the locked initial tax and zero distance/wait time as the actual recorded session.

Before a ride starts, **Reset** simply clears the display. Once a ride has started, the destructive action is labelled **Discard ride** (rather than Reset), asks for confirmation, and removes the active ride without saving it. Pausing never creates a saved record.

## Fare calculation

For an active ride:

```text
total = initialTax
      + (trackedDistanceMeters / 1,000 × perKmRate)
      + (idleSeconds / 60 × perMinuteStillRate)
```

- `initialTax` is added once after the rider starts a ride.
- `trackedDistanceMeters` is the sum of valid consecutive GPS fixes.
- The fare engine has an explicit **Moving** or **Idle** state. `idleSeconds` starts accumulating only after speed is at or below **0.8 m/s** for five consecutive seconds. An active Idle state ends only after speed is at or above **1.3 m/s** for three consecutive seconds. Between those thresholds it retains its current state. This hysteresis prevents GPS speed jitter from repeatedly switching the waiting charge.
- If the provider supplies no speed, the app may derive it only from two accepted GPS fixes no more than five seconds apart. If speed cannot be obtained safely, waiting time does not accrue.
- The UI normally rounds the total to two decimal places for display; the calculation retains precision until display. This display convention does not restrict configured costs to two decimal places.
- Pause freezes both distance and waiting time. Discarding an active ride clears every ride value and removes the initial tax; pre-ride Reset does the same without confirmation.

### Timing and state contract

Fare rules use elapsed-realtime timestamps, never wall-clock time. A one-second UI tick may refresh the screen, but it does not itself prove that the taxi moved or remained still.

- A billable location sample is GPS-provider data with precise permission, a monotonic elapsed-realtime timestamp, an age of at most five seconds, and billing-quality accuracy. The first-build billing-quality threshold is **20 m or better**. A 20–60 m GPS fix may update the visible status to **Weak**, but cannot change distance or waiting charges.
- A billable segment is formed only by consecutive billable samples. It is rejected if its timestamps are not increasing, its gap exceeds 15 seconds, or it is implausible. Movement smaller than the larger of **5 m** and either endpoint's reported accuracy is treated as GPS noise; the last billable baseline remains until a significant segment is received.
- A reported speed is usable only while its source sample remains fresh. Derived speed is usable only between two billable samples no more than five seconds apart. An absent or stale speed cannot add wait time.
- Five completed consecutive seconds at or below 0.8 m/s move the engine into Idle; the qualifying five seconds are not charged retroactively. Billing begins with the next eligible elapsed interval. While Idle, three completed consecutive seconds at or above 1.3 m/s end Idle; waiting time remains billable during that exit-confirmation interval. Speeds between the thresholds retain the existing state.
- Fifteen seconds after the last billable sample, the engine enters GPS Lost, freezes both fare components, clears movement/idle candidates, and treats the next billable sample as a new distance baseline. It never charges across the unobserved gap.

## Location handling

The app requests **precise location** only when the user starts a ride. It uses Android’s location provider and displays enough status for the user to judge reliability.

To reduce false charges caused by GPS drift, the first release should:

- Treat GPS fixes with accuracy worse than 60 m as unusable. Fixes between the 20 m billing threshold and 60 m are status-only; they never add fare distance or waiting time.
- Apply the billable-segment noise rule in the timing contract; do not treat a small raw coordinate change as travel.
- Ignore implausible jumps above 1,500 m, out-of-order fixes, and all segments separated by more than 15 seconds.
- Sum distance only while the meter is running.
- Request high-accuracy location from the phone’s **GPS provider** at **one update per second (1 Hz)** during a ride. This is responsive enough for the meter while avoiding the needless battery cost and GPS noise of faster polling. Network-provider positions may help display a searching status, but never add fare distance or waiting time.
- Use reported GPS speed when available; otherwise derive speed only from two valid, recent GPS fixes.
- A received fix is fresh only when it is no more than five seconds old, has billing-quality accuracy, and came from GPS. Only such a fix resets the loss timer.
- If no fresh valid fix arrives for **15 seconds**, show `GPS lost — fare frozen`, stop both distance and waiting-time billing, and retain the last confirmed total. When a valid fix returns, use it as a new baseline rather than charging for the unobserved gap.

The app must state that underground parking, dense buildings, tunnels, and low-quality GPS can make the estimate unreliable.

### Background tracking

An active ride may continue while the app is in the background or the screen is locked. To make this visible and reliable, starting a ride launches a foreground location service with a persistent notification showing the current total and **Pause** and **Stop** actions. Before tracking begins, the app confirms that GPS location services are enabled and that every required runtime permission has been granted; otherwise it explains what is missing and does not start the meter.

- The service, not the activity, owns the active fare engine and location subscription.
- The screen reconnects to the service when brought to the foreground and renders its current state.
- Pausing persists a Paused active ride, stops location updates, removes the foreground notification, and stops the service. Resume is available only from the visible app screen and starts a new foreground-service instance. Stopping saves the ride and also stops updates, removes the notification, and stops the service.
- The manifest declares `ACCESS_FINE_LOCATION`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, and a non-exported service with `foregroundServiceType="location"`. On Android 13 and later, notification permission is a product prerequisite for Start because the live notification is essential to an inspectable background ride. Android can technically launch a foreground service without that grant, but this app deliberately does not.
- Precise location is required: approximate-only or denied location displays a recovery action to system Settings and cannot start a ride. A disabled GPS provider similarly blocks Start. The app does not request all-the-time background location because the user starts the location foreground service from the visible Start-ride screen; it never starts or resumes tracking silently from the background.
- A compact active-ride snapshot (locked tariff, confirmed fare components, status, and last billable point) is saved locally after each valid update and on lifecycle changes.
- If Android kills the service or the device restarts, the app does not silently restart the meter. On a later launch, the app first reconnects to any live service for a session persisted as Running; only when none owns that running session does it convert the snapshot once into **Pending interrupted**. A deliberately Paused ride remains Paused. Recovery shows the last confirmed total and offers **Save as interrupted** or **Discard**. It never bills the unobserved interval or creates duplicate interrupted records.

### Status and recovery behaviour

The status label describes whether the displayed total is presently billable, not merely whether Android has supplied a location.

| Status | Fare behaviour | Service behaviour | User recovery |
| --- | --- | --- | --- |
| Permission needed | Cannot start. | Not running. | Grant precise location in system Settings. |
| GPS disabled | Cannot start. | Not running. | Enable the GPS provider. |
| Searching | The initial tax is retained, but distance and wait charges do not start. | Foreground service is subscribed to GPS. | Wait, Pause, or Stop & save. |
| Good | Bill distance and eligible wait time. | Foreground service tracks at the requested cadence. | Pause or Stop & save. |
| Weak | Freeze distance and wait charges. | Service remains subscribed for a billing-quality fix. | Move to clearer sky, Pause, or Stop & save. |
| GPS lost | Freeze charges and reset the next distance baseline. | Service remains subscribed. | Wait or Stop & save. |
| Paused | Freeze charges. | Service and notification are stopped. | Resume in the visible app, Stop & save, or Discard ride. |
| Pending interrupted | Freeze charges permanently. | Not running. | Save as interrupted or Discard. |

If precise permission is revoked during a ride, the service immediately freezes the fare, persists the recoverable state, stops GPS work safely, and presents Permission needed when the app next becomes visible. It never attempts to continue using approximate location.

## Data and privacy

- Tariff values, the active-ride snapshot, and saved rides are stored only in the app's private on-device Room database. The database is the single persistent source of truth; no parallel preferences store is used.
- The application does not require accounts, network access, advertising IDs, or a server.
- Location is processed in memory for the active ride and is not saved as a route or shared.
- Stopping or discarding the ride clears its in-memory location history. A saved ride retains only its final tariff, totals, duration, end status, and timestamp—not a route.
- The app retains the **10 most recent saved rides**. When an eleventh is saved, it removes the oldest record. A completed or explicitly saved interrupted ride both count toward this limit.

## Android implementation outline

- **Minimum Android version:** Android 7.0 (API 24).
- **Main screen:** one `MainActivity` containing the meter, ride controls, tariff summary, and tariff editor.
- **Meter renderer:** a custom Jetpack Compose drawing component renders the vintage face crisply across screen sizes while semantic Compose text and controls preserve accessibility.
- **Active ride:** a foreground `Service` owns location updates and the fare engine, allowing tracking to continue with the app backgrounded or the screen locked. Its ongoing notification exposes the active state and a stop control.
- **State:** a small fare engine owns distance, idle duration, explicit moving/idle state, running state, and decimal fare calculation; it is separated from UI code for straightforward unit tests.
- **Persistence:** one small Room database retains the tariff, a lightweight active-ride snapshot, and the latest 10 saved ride summaries.
- **Location:** `LocationManager` supplies high-accuracy GPS/network updates at a requested 1 Hz rate after the app verifies permission and that location services are enabled.

## Acceptance criteria for the first build

- The user can save all three cost fields (as either whole or decimal values) and sees them after reopening the app; the meter shows totals with two decimal places and no currency label.
- With location permission granted, Start/Pause/Resume/Stop & save/Discard ride work reliably; pre-ride Reset works reliably.
- The displayed bill starts with the initial tax, grows with valid distance, and grows with valid still time.
- An active ride continues to track when the app is backgrounded, with a persistent notification that makes this clear and can stop the ride.
- A missing or weak GPS signal is communicated clearly and never silently treated as good tracking.
- GPS-loss test: after 15 seconds without a valid fix, no further distance or wait charge is added; a returning fix creates a new baseline.
- Idle-hysteresis test: waiting charges begin only after five seconds at or below 0.8 m/s and stop after three seconds at or above 1.3 m/s.
- Permission test: the meter cannot start until required permissions and enabled location services are confirmed; denied permissions give a clear recovery action.
- Background test: lock the screen and background the app for a live ride, then confirm one-second updates and notification Pause/Stop controls. Restart/kill recovery must present an interrupted partial ride without adding unobserved cost.
- State/recovery test: verify every Ready, Running, Paused, GPS Lost, Pending Interrupted, Completed, and Discarded transition; bind to a live service before recovery and verify repeated recovery cannot duplicate a history record.
- Persistence test: complete or explicitly save 11 rides, reopen the app, and confirm that exactly the latest 10 summaries remain with their locked tariffs and totals.
- Deterministic fare test: with an initial tax of `2.40`, rate of `1.20` per km, still rate of `0.35` per minute, 2.5 km and three idle minutes, the displayed total is `6.45`. Cover half-up rounding, whole-number costs, decimal-comma input, invalid grouping input, and six-fractional-digit input.
- State-machine test: verify boundary values, the non-retroactive five-second idle entry, three-second moving exit, and that values between 0.8 and 1.3 m/s retain the previous state. Verify that absent/stale speed, weak fixes, out-of-order fixes, and a 15-second gap add no fare.
- History test: Stop & save creates a completed ride, confirmed Discard ride removes an active ride without history, an interrupted recovery can be saved or discarded, and individual history deletion requires confirmation.
- Accessibility test: check dynamic text scaling, a minimum 48 dp control target, 4.5:1 text contrast, and screen-reader labels for fare, GPS status, and every action.
- The interface matches the simple vintage-meter direction above and remains usable at common phone widths.
- Release validation includes real-device street, slow-traffic, urban-canyon, tunnel, GPS-toggle, permission-revocation, notification-denial, process-death, force-stop, and reboot scenarios. Billing-quality thresholds are field-validated before release.
