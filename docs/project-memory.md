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

- The fare formula is initial tax plus confirmed kilometres times rate plus confirmed idle minutes times rate.
- Time calculations use elapsed-realtime semantics. Wall-clock changes must not alter billing.
- A billable GPS sample is GPS-provider, fresh, monotonic, and accurate to 20 m or better. A 20–60 m sample is Weak/status-only. Worse, stale, non-GPS, or out-of-order input never bills.
- A meaningful segment must exceed the larger of 5 m and either endpoint accuracy. Segments over 1,500 m or separated by more than 15 seconds do not bill.
- Idle entry requires five completed seconds at or below 0.8 m/s and is not retroactively charged. Idle exit requires three completed seconds at or above 1.3 m/s; its confirmation interval remains billable.
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
- Android Emulator 37.1.11 and the API 35 default x86_64 system image are installed; the verified KVM-accelerated AVD is named `taxi-inspector-api35`.
- `local.properties` points to that SDK and is intentionally ignored. Never commit it.

## Update rules

Update this file only for durable facts: product decisions, architecture boundaries, and verified environment facts. Put all progress, test history, risks, and next work in `build-status.md` instead.
