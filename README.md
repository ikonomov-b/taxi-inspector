# Taxi Inspector

[![Android CI](https://github.com/ikonomov-b/taxi-inspector/actions/workflows/android.yml/badge.svg?branch=main)](https://github.com/ikonomov-b/taxi-inspector/actions/workflows/android.yml)

Taxi Inspector is an offline Android app that helps a rider independently estimate a taxi fare during a ride. It is an estimate only—not a certified or regulated taximeter.

The rider enters an initial tax, distance rate, and waiting rate in the same local tariff unit used by the taxi. The app deliberately has no currency identity, conversion, exchange-rate, account, or network feature.

## What it does

- Uses exact decimal arithmetic for tariff values and fare totals.
- Separates the fare rules from Android and UI code so the core is deterministic and testable.
- Treats uncertain GPS data conservatively: stale, weak, missing, or ambiguous data freezes billing rather than creating an inferred charge.
- Stores a compact active-ride snapshot and the ten newest ride summaries locally.
- Avoids route history, raw location history, analytics, advertising identifiers, maps, and cloud sync.

## Project status

The project is under active implementation. The exact fare core and initial Room persistence layer are implemented and verified. GPS tracking, the foreground service, recovery flow, history UI, and production meter UI remain in progress.

For the current phase, verification evidence, known risks, and next gate, see [`docs/build-status.md`](docs/build-status.md).

## Build and test

The project includes the Gradle wrapper. A clean checkout requires:

- JDK 17-compatible tooling;
- Android SDK Platform 35; and
- Android SDK Build-Tools 35.0.0.

Set the Android SDK location in `local.properties` or through `ANDROID_HOME`. The machine-specific `local.properties` file is ignored and must not be committed.

Run unit tests and lint together:

```bash
./gradlew --no-daemon test lintDebug
```

The verified local environment and Android Studio setup are documented in [`docs/development-environment.md`](docs/development-environment.md). The same test and lint commands run in [GitHub Actions](.github/workflows/android.yml).

Scripts under [`scripts/`](scripts/) wrap common emulator workflows: booting the AVD and installing/launching the app, mirroring CI locally, running instrumented tests, and a black-box simulated-drive test that feeds a realistic GPS route into a real emulator. See the "Helper scripts" section of [`docs/development-environment.md`](docs/development-environment.md) for details and current caveats.

## Architecture

The repository is a single Android application module:

```text
Compose UI → ride domain ← future tracking service
     │                         │
     └──────── Room repository ┘
```

- `app/src/main/java/com/taxiinspector/ride/` — Android-free tariff, fare, GPS-input, and ride-state rules.
- `app/src/main/java/com/taxiinspector/data/rides/` — Room entities, DAO, mappings, repository, and database.
- `app/src/test/` — deterministic JVM tests for the fare core.
- `app/src/androidTest/` — Android/Room integration tests.
- `app/schemas/` — versioned exported Room schemas.
- `docs/` — product rules, architecture, implementation plan, environment, and live status.

The `ride` domain must remain free of Android, Room, Compose, and service dependencies. Tariff values must remain exact decimals; floating-point types are reserved for GPS measurements and geometric calculations.

## Contributing

Read [`AGENTS.md`](AGENTS.md), [`docs/agent-brief.md`](docs/agent-brief.md), and [`docs/build-status.md`](docs/build-status.md) before making implementation changes. Follow [`CONTRIBUTING.md`](CONTRIBUTING.md) for the contribution workflow and testing expectations.

## License

No project license has been selected yet. Until a license is added, the project remains subject to the default copyright rules.
