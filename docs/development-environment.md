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
  - **GNSS field diagnostics:** `AndroidGpsLocationClient` logs one `status` line (band, how many of the signals used in the fix were L5-class, and how many were reported at all) and one `fix` line (band, accuracy, speed, speed accuracy, mock flag) per second under the tag `TaxiGnss`. A log tag defaults to INFO, so this is silent until switched on for a session with `adb shell setprop log.tag.TaxiGnss DEBUG` (reset by a reboot); read it with `adb logcat -s TaxiGnss`. It never logs coordinates, only the quality of a fix, so it does not weaken the no-stored-route rule. For a whole drive, widen the buffer first with `adb logcat -G 16M` (hours of headroom at two lines a second) and read it back afterwards with `adb logcat -d -s TaxiGnss > drive.log`, since the default buffer rotates away a long ride. The `l5=N of=M` count is the number to watch when deciding whether `GnssBandClassifier`'s four-signal minimum is right for real conditions -- no emulator can produce L5 status, so this is the only way to see it.
- `scripts/check.sh` — mirrors the CI verify job (`test lintDebug`) locally; needs no emulator.
- `scripts/test-instrumented.sh` — runs `connectedDebugAndroidTest` against the emulator, pinned with `ANDROID_SERIAL` because the Gradle task otherwise enrols every attached device at once, so a phone connected for field testing joins the run.
  - **Running the suite on a physical device** takes `ANDROID_SERIAL=<serial>` plus two things that are easy to miss. Espresso must be 3.7.0 or newer, which is why the version catalog pins it ahead of the Compose BOM (the BOM still resolves 3.5.0, and anything older reaches `InputManager.getInstance` reflectively — removed in Android 16 — so every Compose UI test dies inside `Espresso.onIdle` with `NoSuchMethodException`). And the device needs animations disabled and its screen held awake, or Compose tests fail intermittently with `No compose hierarchies found in the app` when the screen times out mid-run:

    ```bash
    adb -s "$serial" shell settings put global window_animation_scale 0
    adb -s "$serial" shell settings put global transition_animation_scale 0
    adb -s "$serial" shell settings put global animator_duration_scale 0
    adb -s "$serial" shell svc power stayon true
    ```

    Measured on a Pixel 8 Pro (Android 17): 17 failures on Espresso 3.5.0, two or three flaky ones on 3.7.0 without the device preparation, and none with both. Restore the animation scales to `1.0` and `svc power stayon false` afterwards if it is a phone in daily use.

    **A third thing, learned 2026-09-12: the screen must also stay _unlocked_, not merely awake.** `svc power stayon true` keeps the display on, but the keyguard can still engage, and a locked keyguard fails every Compose test in the run with the same `No compose hierarchies found in the app` — 40 of them in one pass, which looks exactly like the timeout problem above and is not. Unlock the device by hand before starting and check it stayed unlocked afterwards; `adb shell dumpsys window | grep mDreamingLockscreen` reads `false` only while it is genuinely unlocked, and `adb shell wm dismiss-keyguard` does nothing against a secured lock. The emulator has no keyguard, which is the other reason `scripts/test-instrumented.sh` pins itself to it.
- `scripts/simulate-drive.sh` — a black-box ~2 minute simulated ride: installs a clean app copy, types a company and its rates into the app's own editor, taps "Start ride" through `uiautomator`, feeds a deterministic GPS path (60s at a constant 50 km/h, then 60s stationary) into the emulator's real GPS provider via `adb emu geo fix` (`scripts/drive_profile.py`), taps "Stop & save", then has `scripts/check_ride_result.py` compare the distanceMeters/idleMillis/fare the app actually persisted (`ride_summary`) against what RideEngine's own rules (`app/src/main/java/com/taxiinspector/ride/RideEngine.kt`) predict for that exact profile, failing loudly on a mismatch. It drives only the real UI and the real GPS provider — never the app's Kotlin code — so it exercises the full on-device stack.
  - **`geo fix` needs an explicit velocity, in knots, as its 5th argument** (`help geo fix` on the emulator console) — without one the emulator reports a native GPS speed of exactly 0 on every fix, and `AndroidGpsLocationClient` prefers that over its own derived speed, so the engine calls itself Idle within 5s regardless of real movement. `drive_profile.py` always passes altitude/satellites/velocity now; this bit an earlier version of the script silently — every distance/idle number it ever produced was meaningless — until diagnosed by polling `active_ride` and `dumpsys location` mid-ride.
  - The expected distance nets out the first fix's baseline-only cost (`RideEngine.onLocation`'s `lastBillablePoint == null` branch bills 0) but still tolerates ~40m of slack: the GPS provider typically takes an extra fix or two to deliver its first callback after Start, silently absorbing another step of distance the same way a real receiver's acquisition time would. The expected idle time is derived from the actual wall-clock duration of the stationary leg (recorded by `drive_profile.py`) plus this script's own "Stop & save" tap latency, not the nominal 60s, since ticks run on the controller's own 1Hz timer independent of the GPS feed.
  - `scripts/ui_dump.py` is the `uiautomator`-dump-and-tap helper this and `check_ride_result.py` share; `fill` handles the three tariff fields and `tap-text` handles every button.
  - `input text` splits its argument on spaces, so `ui_dump.py` sends them as the `%s` escape; the company name is the first value here that can contain one. The editor is also taller than the old tariff screen, so **Save company** sits under the keyboard until it is dismissed, and `uiautomator` cannot tap what the IME covers. `dismiss-keyboard` polls the input method's own `mInputShown` rather than the field's focus, because Compose keeps a text field focused after the IME hides and waiting on focus always timed out.
  - The distance check tolerates a number of 1 Hz steps rather than a fixed distance, since each fix lost to provider acquisition costs exactly one second of travel. A fixed 40 m was about 2.9 steps at 50 km/h and failed a clean run that lost three; the default is now four steps, about 56 m on the standard profile.
  - The tariff is typed through the screen rather than seeded into Room. The old shortcut inserted an `app_settings` row directly, which stopped being possible when the tariff moved to its taxi company: a direct insert would now have to fabricate a company row and a selection. The script still calls `adb root`, but only so `check_ride_result.py` can read the saved summary out of the app's private database at the end, and it does so before any UI automation because a mid-run adbd restart would break the taps.

These scripts drive the same shared `taxi-inspector-api35` AVD as any manual instrumentation run, so the single-user-at-a-time caveat above applies to them too.

## Checking a database upgrade end to end

`MigrationTestHelper` proves a migration's SQL against a synthetic fixture; it does not prove that the shipped app upgrades an installed one. To check that, build the previous APK from a worktree and install the new one over it:

```bash
git worktree add /tmp/pre-migration <commit before the schema change>
cp local.properties /tmp/pre-migration/
(cd /tmp/pre-migration && ./gradlew --no-daemon assembleDebug)
adb uninstall com.taxiinspector                                   # start from no data
adb install /tmp/pre-migration/app/build/outputs/apk/debug/app-debug.apk
# drive the old app through its own UI (scripts/ui_dump.py) so it writes real rows
./gradlew --no-daemon assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk           # -r keeps the data
```

Then read the upgraded database back. `run-as` needs no root, so this works on a physical device too:

```bash
db=/data/data/com.taxiinspector/databases/taxi-inspector.db
adb shell "run-as com.taxiinspector sqlite3 $db 'pragma user_version'"
adb shell "run-as com.taxiinspector sqlite3 $db 'select * from taxi_company'"
```

Note that `adb install -r` kills the process, so a ride left Running comes back as `PendingInterrupted`. That is the recovery path working, not migration damage. Remember to `git worktree remove --force` the checkout afterwards.
