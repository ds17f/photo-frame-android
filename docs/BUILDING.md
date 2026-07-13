# Building & Dev Loop

## Prerequisites

- Android SDK with `platforms;android-35` and `build-tools;35.0.0` (set `ANDROID_HOME`
  or `local.properties` `sdk.dir`).
- No JDK install needed beyond one recent JDK to launch Gradle: the **Java 17 toolchain**
  is auto-provisioned by the Gradle foojay resolver on first build (network required once).
- AGP 8.7.3 / Gradle 8.13 (via the checked-in wrapper), Kotlin 2.0.21.

## Common commands

```bash
./gradlew :app:assembleDebug        # debug APK -> app/build/outputs/apk/debug/
./gradlew :app:assembleRelease      # signed release APK (self-signed key, see below)
./gradlew :app:testDebugUnitTest    # JVM unit tests
./gradlew :app:lintDebug            # Android Lint
```

### GMS guard (hard rule — Fire OS has no Google Play Services)

No dependency, direct or transitive, may pull GMS/Firebase. Verify after any dependency change:

```bash
./gradlew :app:dependencies --configuration releaseRuntimeClasspath \
  | grep -Ei 'com\.google\.android\.gms|com\.google\.firebase|play-services'   # must print nothing
```

CI enforces this automatically (`.github/workflows/ci.yml`).

## Signing

`keystore.properties` points at the checked-in self-signed `photoframe-release.keystore`
(personal sideloaded app — DESIGN.md §3). If the keystore is absent, release builds fall
back to the debug key so a fresh checkout still builds. To publish to a store later, inject
a real keystore via the CI secrets path documented in the workflow.

## Emulator / device

Dev iteration target: Android emulator, API 30, tablet profile, 1280×800, landscape.
Create it with:

```bash
sdkmanager "system-images;android-30;default;x86_64"   # AOSP image = no GMS, closest to Fire OS
avdmanager create avd -n frame_tablet_api30 \
  -k "system-images;android-30;default;x86_64" -d "10.1in WXGA (Tablet)"
emulator -avd frame_tablet_api30
```

**The Fire HD is the source of truth** (PLAN.md): every phase's acceptance run happens on
the physical tablet via `adb install`. Enable ADB + "Apps from Unknown Sources" on the
device first.

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n com.photoframe/.SlideshowActivity
```
