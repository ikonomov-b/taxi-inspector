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
- Android command-line tools at `/home/bobi/Android/Sdk/cmdline-tools/latest`.

This checkout uses the untracked `local.properties` file to set `sdk.dir=/home/bobi/Android/Sdk`. Do not commit `local.properties`; another checkout can use `ANDROID_HOME` or its own local SDK path instead.

The Android-free fare-core sources and JUnit tests compile and run successfully with the bundled Kotlin compiler/JBR. The complete Gradle `test` task also passed with this SDK configuration.
