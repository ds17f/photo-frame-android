# API Notes & Discrepancy Log

Running log of places where external reality diverged from the spec in DESIGN.md, plus
build/signing facts worth keeping close. Add an entry whenever an Immich endpoint shape,
Google Photos Picker field, or shared-album HTML format differs from what the design
assumed (per CLAUDE.md and PLAN.md builder notes).

## Build & signing facts

- **Release keystore:** `photoframe-release.keystore` (checked in), alias `photoframe`.
  Self-signed, personal/sideloaded app (DESIGN.md §3).
  - **SHA-1:** `E2:FC:76:77:9F:F0:6A:E9:60:86:EF:60:DC:EB:42:B4:F7:A1:D7:BA`
  - **SHA-256:** `B6:9F:85:6D:4D:68:C2:06:3E:B1:6E:31:1E:45:8C:78:B8:5C:BA:4E:FE:DB:60:C8:8D:85:D0:75:86:5F:A0:E0`
  - Needed when creating the Google OAuth **Android** client in Phase 4
    (package `com.photoframe` + this SHA-1). Note the `.debug` build has applicationId
    `com.photoframe.debug` and is signed with the debug key — register that separately if
    testing OAuth on a debug build.
- **Toolchain:** compileSdk 35, targetSdk 35, minSdk 28, Java 17 toolchain
  (auto-provisioned via the Gradle foojay resolver), AGP 8.7.3 / Gradle 8.13, Kotlin 2.0.21.
- **GMS check:** `releaseRuntimeClasspath` verified free of `com.google.android.gms`,
  `com.google.firebase`, and `play-services` (Phase 0). Re-run on every new dependency:
  `./gradlew :app:dependencies --configuration releaseRuntimeClasspath | grep -Ei 'gms|firebase|play-services'`

## Immich

_(none yet — populate in Phase 2 after verifying against the user's server `/api/docs`)_

## Google Photos Picker

_(none yet — Phase 4)_

## Google Photos shared album (unofficial parser)

_(none yet — Phase 5)_
