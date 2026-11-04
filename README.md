# Photo Frame

An Android photo-frame app for Amazon Fire HD tablets — a self-hosted replacement for
stock slideshow apps. It syncs photos from sources like [Immich](https://immich.app/) into
a local cache and renders a resilient slideshow that keeps playing even when the network is
down. Sideloaded and personal-use; not published to any store.

- **Design & rationale:** [`docs/DESIGN.md`](docs/DESIGN.md)
- **Build plan / phases:** [`docs/PLAN.md`](docs/PLAN.md)
- **API discrepancies log:** [`docs/API_NOTES.md`](docs/API_NOTES.md)
- **Desktop dashboard (weather and school reminders):** [`docs/DASHBOARD.md`](docs/DASHBOARD.md)

## Requirements

- JDK 17 (the project uses the Java 17 toolchain)
- Android SDK, `minSdk 28` (Fire OS 7), `compileSdk 35`
- No Google Play Services / Firebase — Fire OS has no GMS, and CI fails the build if any
  GMS/Firebase/play-services dependency appears (direct or transitive).

## Build

```bash
./gradlew :app:assembleDebug      # debug APK  → app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest  # unit tests
./gradlew :app:assembleRelease    # signed release APK (see signing below)
```

`make help` lists device shortcuts (install/launch on a Fire tablet, push Immich config,
etc.). Day-to-day dev runs on a standard Android emulator (API 30, tablet 1280×800,
landscape); final verification is by sideloading to the real Fire HD.

## Release signing (first-time setup)

The release keystore is **not** committed — it's a self-signed key that stays on your
machine and in CI secrets. Without it, release builds fall back to the debug key so a fresh
clone still builds.

To sign your own release builds:

```bash
cp .env.example .env          # then edit .env to set your signing passwords
scripts/init-signing.sh       # generates the keystore + keystore.properties from .env
./gradlew :app:assembleRelease
```

`init-signing.sh` is idempotent — if a keystore already exists it leaves it alone and just
(re)writes `keystore.properties`. Both files are gitignored.

> **Keep your keystore.** Android only accepts an app update signed by the *same* key. If
> you lose it you can still build, but you'll have to uninstall/reinstall on the tablet
> rather than update in place.

## CI signing (optional)

CI ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) signs the release APK from
GitHub Actions secrets when they're present; otherwise it uses the debug key. To upload
your local key's values as secrets:

```bash
scripts/set-gh-secrets.sh     # reads .env, pushes to the repo's Actions secrets via `gh`
```

This sets `RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_PASSWORD`, and
`RELEASE_KEY_ALIAS`. Requires the [GitHub CLI](https://cli.github.com) with admin rights on
the repo.

## Contributing

`main` is protected — changes land via pull request. Fork or branch, push, and open a PR;
CI (build, unit tests, lint, GMS guard, signed release) must pass.
