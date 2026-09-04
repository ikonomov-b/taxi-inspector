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
