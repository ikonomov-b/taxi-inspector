# Development Environment Notes

## Android Studio

The checked local Android Studio installation is:

| Property | Value |
| --- | --- |
| Install path | `/opt/android-studio-for-platform` |
| Product | Android Studio for Platform (Panda) |
| Build/version | `AI-253.30387.90.2532.14935130` |
| Bundled runtime | OpenJDK 21.0.9 |
| Java executable | `/opt/android-studio-for-platform/jbr/bin/java` |

Use its bundled JBR when running Gradle locally until a project-specific JDK is otherwise configured. The repository now provides a Gradle 8.7 wrapper.

## Android SDK

The Android SDK is installed at `/home/bobi/Android/Sdk` with:

- Android SDK Platform 35;
- Android SDK Build-Tools 35.0.0 (and 34.0.0, installed by the Android Gradle Plugin);
- Android SDK Platform-Tools; and
- Android Emulator 37.1.11;
- the API 35 default x86_64 system image; and
- Android command-line tools at `/home/bobi/Android/Sdk/cmdline-tools/latest`.

The local `taxi-inspector-api35` AVD uses that API 35 x86_64 image. KVM acceleration is available and the AVD has completed a successful headless boot. It can be started for instrumentation with:

```bash
/home/bobi/Android/Sdk/emulator/emulator \
  -avd taxi-inspector-api35 \
  -no-window -no-audio -no-boot-anim \
  -gpu swiftshader_indirect -no-snapshot
```

Only one instrumentation run may drive that AVD at a time. `androidx.test` connects a
`UiAutomation` per run, so a second concurrent run — another terminal, an IDE run
configuration, or a parallel agent session — fails with `UiAutomation ... already
registered!` or `Not connected!` and truncates the test report. The symptom looks like a
flaky test, including in tests that never touch UiAutomation themselves. Before
investigating such a failure, confirm no other session is using the emulator, then re-run
and check that the report lists the full test count.

This checkout uses the untracked `local.properties` file to set `sdk.dir=/home/bobi/Android/Sdk`. Do not commit `local.properties`; another checkout can use `ANDROID_HOME` or its own local SDK path instead.

The Android-free fare-core sources and JUnit tests compile and run successfully with the bundled Kotlin compiler/JBR. The Gradle `test connectedDebugAndroidTest` tasks also pass against the API 35 AVD, including Room transaction, recreation, retention, idempotence, and schema-fixture coverage.

## Helper scripts (`scripts/`)

`scripts/lib.sh` is sourced by the scripts below: it resolves `$ADB`/`$EMULATOR` from `ANDROID_HOME` (falling back to the SDK path above), puts `platform-tools`/`emulator` on `PATH` for child processes (Python included), points `JAVA_HOME` at the Android Studio JBR when the caller hasn't set one (the system default `java` here is a stale JDK 8 whose cert store can't validate `services.gradle.org`, which otherwise breaks the Gradle wrapper download), and exposes `ensure_emulator_running`, which starts `taxi-inspector-api35` if no emulator is already up and waits for boot, or reuses one that's already running.

- `scripts/run-emulator.sh` — builds and installs the debug APK on the emulator (starting it if needed) and launches `MainActivity`.
- `scripts/build-device-apk.sh` — builds the debug APK and installs it on a connected physical device (e.g. a Pixel 8 Pro) if `adb devices` shows one, otherwise prints the APK path (`app/build/outputs/apk/debug/app-debug.apk`) and manual sideload instructions. There is no release signing config yet, so this always produces a debug-signed build.
  - **GNSS field diagnostics:** `AndroidGpsLocationClient` logs one `status` line (band, how many of the signals used in the fix were L5-class, and how many were reported at all) and one `fix` line (band, accuracy, speed, speed accuracy, mock flag) per second under the tag `TaxiGnss`. A log tag defaults to INFO, so this is silent until switched on for a session with `adb shell setprop log.tag.TaxiGnss DEBUG` (reset by a reboot); read it with `adb logcat -s TaxiGnss`. It never logs coordinates, only the quality of a fix, so it does not weaken the no-stored-route rule. The `l5=N of=M` count is the number to watch when deciding whether `GnssBandClassifier`'s four-signal minimum is right for real conditions -- no emulator can produce L5 status, so this is the only way to see it.
- `scripts/check.sh` — mirrors the CI verify job (`test lintDebug`) locally; needs no emulator.
- `scripts/test-instrumented.sh` — runs `connectedDebugAndroidTest` against the emulator.
- `scripts/simulate-drive.sh` — a black-box ~2 minute simulated ride: installs a clean app copy, seeds a tariff, taps "Start ride" through `uiautomator`, feeds a deterministic GPS path (60s at a constant 50 km/h, then 60s stationary) into the emulator's real GPS provider via `adb emu geo fix` (`scripts/drive_profile.py`), taps "Stop & save", then has `scripts/check_ride_result.py` compare the distanceMeters/idleMillis/fare the app actually persisted (`ride_summary`) against what RideEngine's own rules (`app/src/main/java/com/taxiinspector/ride/RideEngine.kt`) predict for that exact profile, failing loudly on a mismatch. It drives only the real UI and the real GPS provider — never the app's Kotlin code — so it exercises the full on-device stack.
  - **`geo fix` needs an explicit velocity, in knots, as its 5th argument** (`help geo fix` on the emulator console) — without one the emulator reports a native GPS speed of exactly 0 on every fix, and `AndroidGpsLocationClient` prefers that over its own derived speed, so the engine calls itself Idle within 5s regardless of real movement. `drive_profile.py` always passes altitude/satellites/velocity now; this bit an earlier version of the script silently — every distance/idle number it ever produced was meaningless — until diagnosed by polling `active_ride` and `dumpsys location` mid-ride.
  - The expected distance nets out the first fix's baseline-only cost (`RideEngine.onLocation`'s `lastBillablePoint == null` branch bills 0) but still tolerates ~40m of slack: the GPS provider typically takes an extra fix or two to deliver its first callback after Start, silently absorbing another step of distance the same way a real receiver's acquisition time would. The expected idle time is derived from the actual wall-clock duration of the stationary leg (recorded by `drive_profile.py`) plus this script's own "Stop & save" tap latency, not the nominal 60s, since ticks run on the controller's own 1Hz timer independent of the GPS feed.
  - `scripts/ui_dump.py` is the `uiautomator`-dump-and-tap helper this and `check_ride_result.py` share with the tariff-entry-by-UI path (currently unused for tariff entry — see caveat below).
  - **Caveat (remove once switched back):** this script still seeds the `app_settings` row directly into the Room database (`taxi-inspector.db`, schema in `app/schemas/com.taxiinspector.data.rides.TaxiInspectorDatabase/1.json`) via `adb root` + on-device `sqlite3`, bypassing the tariff screen. That shortcut dates from when Save tariff was unwired; it now persists through `TariffViewModel.save()` and `repository.saveTariff()`, so the script can be switched back to `ui_dump.py`'s `fill`/`tap-text "Save tariff"` calls (kept in that file, just unused). The DB shortcut is emulator-only in any case -- it needs `adb root`, which a production device will not give, so a physical-device run must enter the tariff through the screen.

These scripts drive the same shared `taxi-inspector-api35` AVD as any manual instrumentation run, so the single-user-at-a-time caveat above applies to them too.
