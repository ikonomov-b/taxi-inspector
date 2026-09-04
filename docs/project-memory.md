# Taxi Inspector — Project Memory

> Read `agent-brief.md` and `build-status.md` first at session startup. This document is the detailed reference for durable decisions; it is not a progress report or source index.

## Durable product decisions

- The app estimates a taxi fare; it is not a certified or regulated taximeter.
- Tariffs are currency-agnostic decimal units. Users enter all values in the taxi’s local unit. The app has no currency setting, label, code, conversion, or exchange rate.
- Tariff values accept ASCII digits with one optional `.` or `,`, up to six fractional digits. Values are exact `BigDecimal` amounts. Totals display to two places with half-up rounding and the device decimal separator.
- A ride locks the full tariff at Start. Tariffs cannot be edited while any active ride exists.
- Tariff entry is its own destination, not a panel on the meter. A first run with no saved tariff opens it directly and offers no way out without saving; afterwards it is reached from the meter's Edit control, which is withdrawn while a ride is active. This keeps the fare reading off the same screen as a keyboard and makes "no tariff, no meter" structural rather than a disabled button.
- Stop & save records a Completed ride. Discard ride is destructive and requires confirmation. Pre-ride Reset has no saved ride to delete.
- Retain only the ten newest saved records. Save no route or raw location history.

## Durable fare and GPS decisions

- The fare formula is initial tax plus confirmed kilometres times rate plus confirmed idle minutes times rate. Distance and waiting are mutually exclusive: distance accrues only while the engine is Moving, so no second is ever charged as both. The billable baseline still advances while Idle so that leaving Idle never back-bills.
- Time calculations use elapsed-realtime semantics. Wall-clock changes must not alter billing.
- A billable GPS sample is GPS-provider, unmocked, fresh, monotonic, and accurate to 20 m or better. A 20–60 m sample is Weak/status-only. Worse, stale, non-GPS, mocked, or out-of-order input never bills.
- A meaningful segment must exceed the larger of the movement floor and either endpoint accuracy. That floor is 5 m, or 2.5 m when both endpoints are dual-band fixes. Segments over 1,500 m or separated by more than 15 seconds do not bill.
- Dual-band is observed, never requested: an app cannot ask the receiver for L5. A fix counts as dual-band when at least four of the signals used in it lie within 1 MHz of 1176.45 MHz, the shared centre of GPS L5, Galileo E5a, BeiDou B2a, QZSS L5 and NavIC L5. Other secondary frequencies do not qualify, and a receiver that reports no carrier frequency is Unknown and treated as single-band. The band is never persisted, so a recovered baseline is Unknown for one segment.
- Idle entry requires five completed seconds at or below 0.8 m/s and is not retroactively charged. Idle exit requires three completed seconds at or above 1.3 m/s; its confirmation interval remains billable as waiting, and no distance is billed across it.
- Speed derived from GPS displacement uses the held billable baseline, so a stationary vehicle's jitter yields a decaying rather than a constant apparent speed. A reported speed whose own accuracy is wider than the 0.8-1.3 m/s hysteresis band cannot place the vehicle on either side of it and is treated as no reported speed, which falls through to derivation. When no usable speed exists the engine bills no waiting time at all, per the design rule that unobtainable speed accrues nothing. On a device whose fixes carry no reported speed this under-reads waiting time; under-reading is the intended conservative direction.
- After 15 seconds without a fresh billable fix, GPS Lost freezes distance and waiting charges; a returning good fix starts a new baseline.

## Architecture decisions

- One Android app module; no Hilt, DataStore, multi-module split, network, analytics, map, account, or generic clean-architecture framework.
- Compose is the sole UI approach. Use a custom Compose drawing component for the vintage face, not the deleted legacy Android `View`.
- `ride` is a pure Kotlin domain. `Room` and Android APIs belong outside it.
- Room is the only persistent store for current tariff, active ride, and history. Decimal database values are canonical strings.
- The foreground tracking service is the sole writer of a running active session. It runs `START_NOT_STICKY`, never silently resumes after process death, and requires a bind/check of live service ownership before classifying a Running snapshot as interrupted. That bind deliberately omits `BIND_AUTO_CREATE`, because a service created by the check would answer the ownership question about itself.
- ViewModels read no Android state. The route reports permission and GPS-provider facts as an explicit environment value, and every Android side effect (permission dialog, Settings, navigation, service command, ownership bind) leaves the ViewModel as a one-off effect. This keeps screen recreation from re-requesting a permission or re-sending a command.

## Live-state pointer

`build-status.md` is the authoritative live implementation record. Do not copy phase completion, test counts, current risks, or next tasks into this memory file; that duplication causes drift.

## Environment memory

- Android Studio Panda: `/opt/android-studio-for-platform`.
- Bundled JBR: `/opt/android-studio-for-platform/jbr` (OpenJDK 21.0.9).
- Android SDK: `/home/bobi/Android/Sdk`, with Platform 35 and build-tools 35.0.0.
- Android Emulator 37.1.11 and the API 35 default x86_64 system image are installed; the verified KVM-accelerated AVD is named `taxi-inspector-api35`. Exactly one instrumentation run may drive it at a time; concurrent runs collide over `UiAutomation` and produce misleading test failures.
- `local.properties` points to that SDK and is intentionally ignored. Never commit it.

## Update rules

Update this file only for durable facts: product decisions, architecture boundaries, and verified environment facts. Put all progress, test history, risks, and next work in `build-status.md` instead.
