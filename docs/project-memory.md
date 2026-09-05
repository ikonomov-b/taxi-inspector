# Taxi Inspector — Project Memory

> Read `agent-brief.md` and `build-status.md` first at session startup. This document is the detailed reference for durable decisions; it is not a progress report or source index.

## Durable product decisions

- The app estimates a taxi fare; it is not a certified or regulated taximeter.
- Tariffs are currency-agnostic decimal units. Users enter all values in the taxi’s local unit. The app has no currency setting, label, code, conversion, or exchange rate.
- Tariff values accept ASCII digits with one optional `.` or `,`, up to six fractional digits. Values are exact `BigDecimal` amounts. Totals display to two places with half-up rounding and the device decimal separator.
- The app retains up to ten locally named taxi-company profiles, each containing exactly one existing three-value tariff. Company names are trimmed, nonblank user labels of at most 80 characters rather than verified business identities; duplicates are rejected after trimming and case-insensitive comparison. An eleventh add is blocked and never silently evicts a company.
- One company selection persists locally. The first saved company is selected automatically; deleting the selected company requires confirmation and clears the selection, so the user must explicitly select another before Start.
- A ride atomically locks the selected company name and full tariff at Start. Company selection, creation, editing, and deletion are unavailable while any active ride exists. Later edits or deletion cannot alter active or historic snapshots.
- Company/tariff entry is separate from the meter, while an accessible Meter selector chooses a complete saved profile before Start. A first run with no saved companies opens creation and offers no path to Start without saving; this keeps fare entry off the same screen as the live reading while making "no selected tariff, no meter" structural.
- Stop & save records a Completed ride. Discard ride is destructive and requires confirmation. Pre-ride Reset has no saved ride to delete.
- Retain only the ten newest saved ride records, independently of the separate ten-company limit. Saved ride detail retains the locked company-name label and tariff but no route or raw location history.

## Durable fare and GPS decisions

- The fare formula is initial tax plus confirmed kilometres times rate plus confirmed idle minutes times rate. Distance and waiting are mutually exclusive: distance accrues only while the engine is Moving, so no second is ever charged as both. The billable baseline still advances while Idle so that leaving Idle never back-bills.
- Time calculations use elapsed-realtime semantics. Wall-clock changes must not alter billing.
- A billable GPS sample is GPS-provider, unmocked, fresh, monotonic, and accurate to 20 m or better. A 20–60 m sample is Weak/status-only. Worse, stale, non-GPS, mocked, or out-of-order input never bills.
- A meaningful segment must exceed the larger of the movement floor and either endpoint accuracy. That floor is 5 m, or 2.5 m when both endpoints are dual-band fixes. Segments over 1,500 m or separated by 15 seconds or more do not bill. Segment continuity is measured from the latest accepted fix, while distance may still accumulate from the older retained noise baseline.
- Dual-band is observed, never requested: an app cannot ask the receiver for L5. A fix counts as dual-band when at least three of the signals used in it lie within 1 MHz of 1176.45 MHz, the shared centre of GPS L5, Galileo E5a, BeiDou B2a, QZSS L5 and NavIC L5. Other secondary frequencies do not qualify, and a receiver that reports no carrier frequency is Unknown and treated as single-band. The band is never persisted, so a recovered baseline is Unknown for one segment.
- Idle entry requires five completed seconds at or below 0.8 m/s and is not retroactively charged. Idle exit requires three completed seconds at or above 1.3 m/s; its confirmation interval remains billable as waiting, and no distance is billed across it.
- Speed derived from GPS displacement uses the held billable baseline, so a stationary vehicle's jitter yields a decaying rather than a constant apparent speed. Reported speed is preferred over derivation, being Doppler-derived; it is refused only when its own accuracy leaves it ambiguous, meaning 0.8 or 1.3 m/s falls within one reported speed accuracy of it. A fast vehicle stays trusted however loose that accuracy. One accuracy bound (Android's 68th percentile) is deliberate, so that weak reception does not lose most of its speeds. When no usable speed exists the engine bills no waiting time at all, per the design rule that unobtainable speed accrues nothing. On a device whose fixes carry no reported speed this under-reads waiting time; under-reading is the intended conservative direction.
- A Weak fix immediately clears speed eligibility, movement candidates, and the distance baseline, preventing both tick billing and later distance bridging across the uncertain interval. At exactly 15 seconds without a fresh billable fix, GPS Lost freezes distance and waiting charges; a returning good fix starts a new baseline. The half-open boundary produces the same result whichever of the tick or location callback arrives first.
- Product direction is to evaluate bounded GPS-gap reconstruction in Phase 8 to reduce discrepancies with a reference taximeter. This is not implemented and the preceding freeze rule remains authoritative until field evidence supports an explicit amendment. Any approved reconstruction must use billable endpoint timestamps, attribute each interval exactly once, keep measured and inferred aggregates separate, disclose inferred fare, retain no route/timestamp stream, and reject gaps spanning Pause, permission loss, explicit GPS disablement, service interruption, process death, or reboot.
- The target taximeter calculation mode is unresolved. Before changing the fixed 0.8/1.3 m/s Moving/Idle contract, verify whether the intended meter applies time below a tariff-derived cross-over speed and distance above it, applies both simultaneously, or follows another jurisdiction-specific rule. For verified mode S, a recovered interval's candidate formula is `max(distanceRate × recoveredDistance, timeRate × gapDuration)`; for mode D it is the sum. Neither may be introduced only for gaps while ordinary intervals retain a contradictory model. Fare increment/resolution must also be captured when comparing displayed totals.

## Architecture decisions

- One Android app module; no Hilt, DataStore, multi-module split, network, analytics, map, account, or generic clean-architecture framework.
- Compose is the sole UI approach. Use a custom Compose drawing component for the vintage face, not the deleted legacy Android `View`.
- `ride` is a pure Kotlin domain. `Room` and Android APIs belong outside it.
- Room is the only persistent store for taxi-company tariffs, the selected company id, active ride, and history. Decimal database values are canonical strings. Start snapshots the selected name and tariff into active/history state instead of making those records depend on a mutable company row.
- The version-1 singleton tariff must migrate forward into one selected placeholder company without changing its exact values. Existing active rides and summaries keep their tariff and use an explicit legacy/unavailable company label; destructive migration remains prohibited.
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
