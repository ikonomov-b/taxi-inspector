# Taxi Inspector — Design Document

## Purpose

Taxi Inspector is a small Android taximeter for checking a taxi fare in real time. A rider or inspector enters the local tariff, starts the meter, and compares the displayed total with the taxi’s own meter during a journey.

The app is an independent estimate, not a certified taxi meter. GPS accuracy, the selected tariff, map position, and local regulations can affect the result.

## Primary flow

1. The app opens to the taximeter and shows the selected taxi company and its tariff. On a first run, when no company has been saved yet, it opens the **Taxi companies** flow instead, because the meter cannot bill without a selected tariff.
2. The user can save up to ten taxi companies. Each has a user-entered name and three monetary amounts:
   - Initial tax — charged once when a ride begins.
   - Per km rate — charged for GPS distance travelled.
   - Per minute car-still rate — charged while speed is below the still threshold.
3. Before starting, the user selects a saved company from the meter. Selecting its name selects all three associated tariff values together.
4. The user taps **Start ride** and grants precise-location permission if needed.
5. The meter updates the total, distance, and wait time once per second while GPS fixes arrive.
6. The user can pause/resume, Stop & save the ride, or discard an active ride after confirmation. Reset is available only before a ride starts.

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
│  Selected company        [Change] │
│  City Taxi                          │
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

## Taxi companies and tariff editing

Company management and tariff editing use a **separate screen**, not entry fields inside the meter. The app retains no more than ten local company profiles. Each profile has a trimmed, nonblank user-provided company name of at most 80 characters and exactly one set of the three tariff values. Names are compared case-insensitively after trimming so duplicate picker entries are rejected. Company names are convenient labels supplied by the user; the app does not verify that they are official business identities.

The screen opens automatically on a first run with no saved companies and is otherwise reached from **Manage companies** beside the selected company on the meter. **Save company** accepts whole numbers or decimal tariff values (with either `.` or `,` as the decimal separator), validates that all costs are non-negative, and persists the name and full tariff together. The first saved company becomes selected. At the ten-company limit, Add is unavailable with a clear explanation; the app never silently deletes an existing company to make room.

Keeping entry fields off the meter means the fare reading never competes with a keyboard and a running meter cannot be mistaken for an editable form. On a first run there is no path to Start without saving a company. Existing companies may be edited or deleted only between rides. Deleting a company requires confirmation; deleting the selected company leaves no selection, so the user must explicitly select another before Start becomes available.

Before a ride starts, an accessible selector on Meter lists the saved company names with enough tariff detail to distinguish them. Selecting a company durably selects its complete tariff. The selected company and tariff remain visible beneath the meter. Selection and company-management controls are disabled for every active phase, including Paused and Pending interrupted.

Starting a ride atomically copies and locks both the selected company name and the full tariff. Later selection changes, edits, or deletion cannot alter the active ride or a saved ride. This makes every saved fare reproducible in its original tariff units and keeps the recorded label that the user selected, even though no currency or verified company identity is stored.

## Saved rides

A **History** control opens a newest-first list of the 10 saved rides. Each row shows its end date/time, final total, distance, and status (**Completed** or **Interrupted**). Selecting a row shows the locked company name, locked tariff, distance, wait time, elapsed ride time, total, status, and end timestamp. A user may delete an individual record from this detail screen after a confirmation; there is no route export or stored route. Records created before company names existed show an explicit legacy/unavailable label rather than an invented company identity.

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
- `trackedDistanceMeters` is the sum of valid consecutive GPS fixes **received while the engine is Moving**. The two variable components are mutually exclusive: like a real taximeter, the app charges for distance or for waiting, never for both over the same second. Without this rule a vehicle crawling in traffic bills twice, and a vehicle held between the 0.8 and 1.3 m/s thresholds bills twice indefinitely.
- The fare engine has an explicit **Moving** or **Idle** state. `idleSeconds` starts accumulating only after speed is at or below **0.8 m/s** for five consecutive seconds. An active Idle state ends only after speed is at or above **1.3 m/s** for three consecutive seconds. Between those thresholds it retains its current state. This hysteresis prevents GPS speed jitter from repeatedly switching the waiting charge.
- If the provider supplies no speed, the app may derive it only from two accepted GPS fixes no more than five seconds apart. A reported speed counts as no reported speed only when its own accuracy leaves it genuinely ambiguous — that is, when the speed could lie on either side of 0.8 or 1.3 m/s. A fast vehicle stays trusted however loose its speed accuracy. If speed cannot be obtained safely, waiting time does not accrue.
- The UI normally rounds the total to two decimal places for display; the calculation retains precision until display. This display convention does not restrict configured costs to two decimal places.
- Pause freezes both distance and waiting time. Discarding an active ride clears every ride value and removes the initial tax; pre-ride Reset does the same without confirmation.

### Timing and state contract

Fare rules use elapsed-realtime timestamps, never wall-clock time. A one-second UI tick may refresh the screen, but it does not itself prove that the taxi moved or remained still.

- A billable location sample is GPS-provider data with precise permission, a monotonic elapsed-realtime timestamp, an age of at most five seconds, and billing-quality accuracy. It must not be flagged as coming from a mock provider; this app's reading is meant to hold up as evidence, so a synthetic fix is refused and shown as **Weak**. The first-build billing-quality threshold is **20 m or better**. A 20–60 m GPS fix may update the visible status to **Weak**, but cannot change distance or waiting charges.
- A billable segment is formed only by consecutive billable samples. Its elapsed gap is measured from the latest accepted fix, even when the distance-noise baseline is older, and must be strictly less than 15 seconds. Timestamps that are not increasing, gaps of 15 seconds or more, and implausible segments are rejected. Movement smaller than the larger of the segment's movement floor and either endpoint's reported accuracy is treated as GPS noise; the last billable baseline remains until a significant segment is received. That floor is **5 m**, or **2.5 m** when both endpoints were fixed with the help of L5-class signals (see Location handling).
- A reported speed is usable only while its source sample remains fresh and its own reported accuracy does not span a decision threshold: it is refused when 0.8 or 1.3 m/s falls inside one reported speed accuracy of it, and trusted otherwise. Android reports that accuracy at the 68th percentile; one such bound is deliberate, because a wider one would refuse far more speeds in exactly the weak reception where they are scarcest, and the five- and three-second hysteresis already absorbs the residual noise. Derived speed is usable only between two billable samples no more than five seconds apart. An absent, stale, or ambiguous speed cannot add wait time.
- Five completed consecutive seconds at or below 0.8 m/s move the engine into Idle; the qualifying five seconds are not charged retroactively, and distance covered during them is ordinary billable distance. Billing begins with the next eligible elapsed interval. While Idle, three completed consecutive seconds at or above 1.3 m/s end Idle; waiting time remains billable during that exit-confirmation interval. Speeds between the thresholds retain the existing state.
- No distance is billed while the engine is Idle, including throughout the exit-confirmation interval. The billable baseline still advances across an Idle period, so leaving Idle measures from a current point and never back-bills movement that was already charged as waiting.
- A Weak fix freezes billing immediately: it clears speed eligibility, movement/idle candidates, and the distance baseline, so neither later ticks nor a returning Good fix can charge across the uncertain interval. Fifteen seconds after the last billable sample, the engine enters GPS Lost, freezes both fare components, clears movement/idle candidates, and treats the next billable sample as a new distance baseline. Exactly 15 seconds belongs to GPS Lost, regardless of whether the tick or returning location callback is reduced first. It never charges across the unobserved gap.

## Location handling

The app requests **precise location** only when the user starts a ride. It uses Android’s location provider and displays enough status for the user to judge reliability.

To reduce false charges caused by GPS drift, the first release should:

- Treat GPS fixes with accuracy worse than 60 m as unusable. Fixes between the 20 m billing threshold and 60 m are status-only; they never add fare distance or waiting time.
- Apply the billable-segment noise rule in the timing contract; do not treat a small raw coordinate change as travel.
- Ignore implausible jumps above 1,500 m, out-of-order fixes, and all segments separated by 15 seconds or more.
- Sum distance only while the meter is running.
- Request high-accuracy location from the phone’s **GPS provider** at **one update per second (1 Hz)** during a ride. This is responsive enough for the meter while avoiding the needless battery cost and GPS noise of faster polling. The app subscribes to no other provider: a network-provider position could never add fare distance or waiting time, so none is collected.
- Prefer the reported GPS speed: it is Doppler-derived and better than anything the app can difference from two positions. Fall back to deriving speed from two valid, recent GPS fixes only when the reported speed is absent, or when its own accuracy leaves it ambiguous about the idle thresholds.
- Refuse any fix flagged as coming from a mock provider, rather than letting it reach the fare.
- Use dual-band GNSS where the device offers it. An app cannot ask for L5; the receiver decides. Instead the app watches the carrier frequencies of the signals used in each fix, and when enough L5-class signals contributed — 1176.45 MHz, which covers GPS L5, Galileo E5a, BeiDou B2a, QZSS L5 and NavIC L5 alike — it treats the fix as dual-band and lowers the movement floor from 5 m to 2.5 m. This matters most in slow city traffic, where a 5 m floor measures straight-line chords across curves and so under-reads real road distance. Other secondary frequencies (GPS L2, Galileo E5b and E6, BeiDou B3, GLONASS L2) are not L5-class and do not qualify. A device or Android version that cannot report carrier frequency keeps the 5 m floor. Note the scope of the gain: the floor is the larger of 2.5 m and either endpoint's accuracy, so the tighter value only takes effect once reported accuracy is already better than 2.5 m. Where it is not — most of the time inside a vehicle — dual-band still helps, but through the accuracy term rather than the floor, because L5 improves the reported accuracy that the deadband was already scaled to.
- A received fix is fresh only when it is no more than five seconds old, has billing-quality accuracy, and came from GPS. Only such a fix resets the loss timer.
- If no fresh valid fix arrives for **15 seconds**, show `GPS lost — fare frozen`, stop both distance and waiting-time billing, and retain the last confirmed total. When a valid fix returns, use it as a new baseline rather than charging for the unobserved gap.

The app must state that underground parking, dense buildings, tunnels, and low-quality GPS can make the estimate unreliable.

### Proposed accuracy improvement — bounded GPS-gap reconstruction

This proposal is **not implemented and does not replace the current first-build contract** above. Today, a gap of less than 15 seconds may produce one straight-line segment between billable endpoints; at 15 seconds the engine enters GPS Lost, the returning fix starts a new baseline, and the lost interval adds no fare. Phase 8 must compare that conservative result with a real taximeter before a reconstruction policy becomes billable product behaviour.

The objective of reconstruction would be to reduce the systematic under-reading caused by tunnels, underpasses, and urban-canyon outages while keeping every inferred contribution explicit and bounded. Linear interpolation cannot recreate the road taken: intermediate points on a straight line sum to the same endpoint chord. It can only distribute that chord over the gap so the fare engine can make one deterministic distance-versus-time decision.

Before selecting that decision rule, field validation must identify the calculation mode of the target taximeter. The EU measuring-instruments definition distinguishes normal mode S, which applies the time tariff below a tariff-derived cross-over speed and the distance tariff above it, from mode D, which applies both throughout the trip. Taxi Inspector currently implements a separate fixed-speed Moving/Idle model, so changing gap handling alone may not remove the largest slow-traffic discrepancy. [EU taximeter definitions (MI-007)](https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX%3A02014L0032-20150127)

If field evidence supports reconstruction, the product change should follow these constraints:

- Define a successful endpoint as a fresh, monotonic, unmocked, billing-quality GPS fix. A missing one-second callback alone is not failure; detect the gap from elapsed-realtime fix timestamps.
- Retain only the minimum active-session state required for the latest accepted endpoint, the distance-noise baseline, and the time through which fare has already been attributed. Never retain an interpolated route or a stream of timestamps.
- On reacquisition, compute the elapsed gap, the great-circle endpoint chord, and its implied average speed. Do not manufacture one-second `LocationSample` values: they add no observation and would make noise filtering and hysteresis depend on invented events. For brief gaps, separately evaluate whether integrating two trusted endpoint speeds improves curved-road distance; do not use that extrapolation unless replay and field data bound its error in acceleration and stop-and-go cases.
- Reject position-derived reconstruction if either endpoint is weak or mocked, timestamps are non-monotonic or cross a reboot/session boundary, the ride was paused or interrupted, displacement does not clear an uncertainty-aware floor, implied motion is implausible, or the gap exceeds a field-validated distance-recovery maximum.
- Attribute each elapsed interval exactly once. Any waiting time already billed from a still-fresh pre-gap speed must be excluded from the recovered interval; reconstructed distance and waiting must not overlap unless a separately approved target explicitly uses taximeter mode D.
- If the target uses mode S, reconstruct the interval as `max(distanceRate × recoveredDistance, timeRate × gapDuration)`. This is the cross-over rule expressed directly in fare units and avoids inventing a sequence of motion states. While service ownership is continuous, the time candidate may accrue provisionally during GPS loss because a mode-S taximeter charges at least its time rate; on reacquisition, replace it with the distance candidate only when the latter is larger. This requires renaming the current `idleMillis` concept to tariff-time duration if the same rule is adopted for ordinary tracking.
- If the target uses mode D, reconstruction is `distanceRate × recoveredDistance + timeRate × gapDuration`. Mode D conflicts with the current mutual-exclusivity contract and therefore requires explicit product approval and a whole-trip fare-model change, not a gap-only exception.
- If the target really charges time only while stationary under the current fixed 0.8/1.3 m/s rules, two endpoints cannot reliably recover a mixed stop-and-go interval. In that case, average-speed classification remains only a hypothesis and must beat the freeze policy in reference-drive replay before adoption.
- Apply the maximum gap to position-derived distance, not automatically to elapsed time. A verified mode-S or mode-D time component may continue through a long outage only while the same foreground service continuously owns a Running ride; it must stop on Pause, permission loss, explicit GPS disablement, service interruption, process death, or reboot.
- Keep measured and reconstructed distance/time as separate aggregates even if both feed the displayed estimate. The meter and saved detail must disclose that GPS-gap estimation contributed to the total and show its duration.
- If provisional time fare is enabled, replace `GPS lost — fare frozen` with an unambiguous state such as `GPS lost — time estimate active`; the notification and accessibility description must make the same distinction. Never display a frozen status while the total is changing.
- Preserve the present no-reconstruction behaviour when a candidate fails any guard. Never reconstruct across permission loss, explicit GPS disablement, Pause, service/process interruption, force-stop, or reboot.

The following decisions intentionally remain open until Phase 8 evidence exists:

1. Which taximeter calculation mode and cross-over behaviour the app is intended to approximate.
2. The maximum recoverable gap and the uncertainty/plausibility bounds.
3. How positional uncertainty adjusts the recovered chord, and whether short-gap endpoint-speed integration improves rather than worsens expected fare error.
4. Whether reconstructed aggregates belong in the active snapshot and ride summary, which would require a Room migration and updated history disclosure.
5. Whether the user-facing waiting/still tariff and `idleMillis` names must become a general time tariff and tariff-time duration for the selected reference mode.

The prerequisite boundary semantics are implemented and covered by pure-domain tests: Weak freezes fare immediately, gap detection uses the latest accepted fix rather than an older noise baseline, and exactly 15 seconds has one deterministic result regardless of ticker/location event order. These corrections do not authorize gap reconstruction; the open evidence requirements above still apply.

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

- Taxi-company names and tariffs, the selected-company id, the active-ride snapshot, and saved rides are stored only in the app's private on-device Room database. The database is the single persistent source of truth; no parallel preferences store is used.
- The application does not require accounts, network access, advertising IDs, or a server.
- Location is processed in memory for the active ride and is not saved as a route or shared.
- Stopping or discarding the ride clears its in-memory location history. A saved ride retains only its locked company-name label, final tariff, totals, duration, end status, and timestamp—not a route.
- The app retains the **10 most recent saved rides**. When an eleventh is saved, it removes the oldest record. A completed or explicitly saved interrupted ride both count toward this limit.
- The separate company list retains at most **10 saved companies**. Reaching that limit blocks another add until the user explicitly deletes one; it never trims companies automatically.

## Android implementation outline

- **Minimum Android version:** Android 7.0 (API 24).
- **Main screen:** one `MainActivity` hosting Compose destinations. Meter carries the meter face, ride controls, and a pre-ride selected-company control with a read-only tariff summary; company management and tariff entry remain separate from the meter.
- **Meter renderer:** a custom Jetpack Compose drawing component renders the vintage face crisply across screen sizes while semantic Compose text and controls preserve accessibility.
- **Active ride:** a foreground `Service` owns location updates and the fare engine, allowing tracking to continue with the app backgrounded or the screen locked. Its ongoing notification exposes the active state and a stop control.
- **State:** a small fare engine owns distance, idle duration, explicit moving/idle state, running state, and decimal fare calculation; it is separated from UI code for straightforward unit tests.
- **Persistence:** one small Room database retains up to 10 named taxi-company tariffs, the selected company id, a lightweight active-ride snapshot with a locked company-name/tariff copy, and the latest 10 saved ride summaries.
- **Location:** `LocationManager` supplies GPS-provider updates at a requested 1 Hz rate after the app verifies permission and that location services are enabled. A parallel `GnssStatus` subscription reports the carrier frequencies in use, so each fix can be marked single- or dual-band.

## Acceptance criteria for the first build

- A first run opens company creation, and the meter becomes usable only once a named company and all three cost fields are saved. The first company becomes selected. The user can retain up to ten companies, select one before Start, and sees the selection after reopening the app; an eleventh add is rejected without eviction.
- Starting copies the selected company name and all three tariff values together. Editing or deleting the source company later does not alter the active ride or its saved history, and deleting the selected company prevents Start until another is explicitly selected.
- Version-1 migration preserves the prior singleton tariff, any active ride, and all saved summaries without destructive fallback; records without a real company name use an explicit legacy/unavailable label.
- With location permission granted, Start/Pause/Resume/Stop & save/Discard ride work reliably; pre-ride Reset works reliably.
- The displayed bill starts with the initial tax, grows with valid distance, and grows with valid still time.
- An active ride continues to track when the app is backgrounded, with a persistent notification that makes this clear and can stop the ride.
- A missing or weak GPS signal is communicated clearly and never silently treated as good tracking.
- GPS-loss test: after 15 seconds without a valid fix, no further distance or wait charge is added; a returning fix creates a new baseline.
- Idle-hysteresis test: waiting charges begin only after five seconds at or below 0.8 m/s and stop after three seconds at or above 1.3 m/s.
- Exclusivity test: a crawl below 0.8 m/s, and a vehicle held inside the 0.8–1.3 m/s band, bill waiting time and no distance; a moving vehicle bills distance and no waiting time; leaving Idle bills neither the exit interval's distance nor, afterwards, the distance covered while Idle.
- Permission test: the meter cannot start until required permissions and enabled location services are confirmed; denied permissions give a clear recovery action.
- Background test: lock the screen and background the app for a live ride, then confirm one-second updates and notification Pause/Stop controls. Restart/kill recovery must present an interrupted partial ride without adding unobserved cost.
- State/recovery test: verify every Ready, Running, Paused, GPS Lost, Pending Interrupted, Completed, and Discarded transition; bind to a live service before recovery and verify repeated recovery cannot duplicate a history record.
- Persistence test: complete or explicitly save 11 rides, reopen the app, and confirm that exactly the latest 10 summaries remain with their locked tariffs and totals.
- Deterministic fare test: with an initial tax of `2.40`, rate of `1.20` per km, still rate of `0.35` per minute, 2.5 km and three idle minutes, the displayed total is `6.45`. Cover half-up rounding, whole-number costs, decimal-comma input, invalid grouping input, and six-fractional-digit input.
- State-machine test: verify boundary values, the non-retroactive five-second idle entry, three-second moving exit, and that values between 0.8 and 1.3 m/s retain the previous state. Verify that absent/stale speed, weak fixes, out-of-order fixes, and a 15-second gap add no fare.
- Dual-band test: a fix helped by enough L5-class signals lowers the movement floor to 2.5 m; secondary frequencies that are not L5-class never count towards it; and a receiver that reports no carrier frequency keeps the 5 m floor.
- Mock-location test: a fix flagged as coming from a mock provider adds no distance and no waiting time, and reports Weak.
- History test: Stop & save creates a completed ride, confirmed Discard ride removes an active ride without history, an interrupted recovery can be saved or discarded, and individual history deletion requires confirmation.
- Accessibility test: check dynamic text scaling, a minimum 48 dp control target, 4.5:1 text contrast, and screen-reader labels for fare, GPS status, and every action.
- The interface matches the simple vintage-meter direction above and remains usable at common phone widths.
- Release validation includes real-device street, slow-traffic, urban-canyon, tunnel, GPS-toggle, permission-revocation, notification-denial, process-death, force-stop, and reboot scenarios. Billing-quality thresholds are field-validated before release.
