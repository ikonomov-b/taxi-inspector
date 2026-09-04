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
- `scripts/check.sh` — mirrors the CI verify job (`test lintDebug`) locally; needs no emulator.
- `scripts/test-instrumented.sh` — runs `connectedDebugAndroidTest` against the emulator.
- `scripts/simulate-drive.sh` — a black-box ~60s simulated ride (start → moving → still → moving → stop): installs a clean app copy, seeds a tariff, taps "Start ride" through `uiautomator`, feeds a realistic accelerate/cruise/stop-at-a-light/cruise/stop GPS path into the emulator's real GPS provider via `adb emu geo fix` (`scripts/drive_profile.py`), then taps "Stop & save". It drives only the real UI and the real GPS provider — never the app's Kotlin code — so it exercises the full on-device stack. `scripts/ui_dump.py` is the `uiautomator`-dump-and-tap helper it shares with the tariff-entry-by-UI path (currently unused — see caveat below).
  - **Caveat (remove once fixed):** the in-app tariff-entry screen's Save button is not wired up yet, so this script currently seeds the `app_settings` row directly into the Room database (`taxi-inspector.db`, schema in `app/schemas/com.taxiinspector.data.rides.TaxiInspectorDatabase/1.json`) via `adb root` + on-device `sqlite3`, bypassing that screen. Once Save tariff works, switch the script back to `ui_dump.py`'s `fill`/`tap-text "Save tariff"` calls (kept in that file, just unused) instead of the DB seed step.

These scripts drive the same shared `taxi-inspector-api35` AVD as any manual instrumentation run, so the single-user-at-a-time caveat above applies to them too.
