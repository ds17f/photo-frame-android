# CLAUDE.md

Android photo-frame app for Amazon Fire HD tablets (sideloaded, personal use).

**Start here:**
- `docs/DESIGN.md` — full product + technical design. Read it before writing code.
- `docs/PLAN.md` — phased build plan with acceptance criteria. Execute in order.

**Hard rules:**
- `minSdk 28` (Fire OS 7), no Google Play Services or Firebase dependencies anywhere —
  Fire OS has no GMS. Check transitive deps.
- The slideshow renders only from the local photo cache; network problems must never
  affect playback.
- Kotlin + Jetpack Compose, single-activity. Java 17 toolchain.

**Dev loop:** Android emulator (API 30, tablet 1280×800, landscape). Real-device
verification on the owner's Fire HD via `adb install` — each phase's acceptance
criteria run on the physical tablet.

**External APIs** (details in DESIGN.md §5): Immich REST (verify against the user's
server `/api/docs`), Google Photos Picker API, Google Photos shared-album page parsing
(unofficial — isolated parser + HTML fixtures). When reality disagrees with the spec,
fix the code and log the discrepancy in `docs/API_NOTES.md`.
