# Photo Frame — Build Plan

Companion to [DESIGN.md](DESIGN.md). Phases are ordered so the frame is *usable on the
real tablet at the end of every phase*, with the primary experience (Immich auto-sync)
landing early. Each phase ends with acceptance criteria and a manual verification pass.

**Builder notes**
- Read DESIGN.md fully before starting; section references below (§) point into it.
- Hard rules: `minSdk 28`, no Google Play Services / Firebase dependencies (Fire OS has
  no GMS), all image decodes off the UI thread, slideshow reads only from local cache.
- Dev loop: Android emulator, API 30, tablet profile 1280×800, landscape. Real-device
  checks: `adb install` over USB/Wi-Fi to the Fire HD (enable ADB + unknown sources).
- Commit per task or coherent group; keep the repo buildable at every commit.
- When an external API detail in the spec turns out wrong (Immich endpoint shape,
  Picker session fields, shared-album HTML), trust reality, fix the code, and note the
  discrepancy in `docs/API_NOTES.md`.

---

## Phase 0 — Scaffold

1. Gradle project: Kotlin, Compose + Material 3, single `app` module; `minSdk 28`,
   `targetSdk 34+`; version catalog; Java 17.
2. Core dependencies: Room, WorkManager, DataStore, EncryptedSharedPreferences, OkHttp/
   Retrofit + kotlinx.serialization, Coil, AppAuth, ZXing. Verify none pulls GMS
   (`gradlew :app:dependencies` grep for `com.google.android.gms`).
3. Single-activity app: immersive fullscreen, keep-screen-on, dark theme, placeholder
   slideshow cycling 5–6 bundled sample photos (mix of portrait/landscape,
   include EXIF-rotated ones) with a hard-cut "transition".
4. Self-signed release keystore + signing config; `assembleRelease` produces an
   installable APK.

**Accept:** app builds, installs on emulator and Fire HD, cycles bundled photos
fullscreen in landscape and portrait, survives rotation, no GMS deps.

## Phase 1 — Slideshow engine (the frame, on canned data)

Everything here runs against `LocalFolderSource`-style fixture data (bundled photos +
a debug folder), so the rendering core is provable before any network code exists.

1. Room schema + cache layout (§6); playlist engine with shuffle bag, order modes,
   recency-boosted shuffle, new-photo splicing (§7).
2. Renderer: preloaded decode pipeline, transitions (crossfade/slide/zoom/none, §8),
   Ken Burns with edge-safe randomized drift (§8).
3. Portrait handling (§7): `auto` pairing with K=10 lookahead, side-by-side layout,
   blur-fill (RenderEffect ≥31 + software fallback for 28–30 — test the fallback, Fire
   OS 7/8 will use it), landscape letterbox treatment.
4. Overlays (§9): clock/date with corner + size settings; caption fade-in with date/
   location; corner-collision resolution.
5. Gestures + controls (§11): tap-for-controls, swipe prev/next, pause, double-back
   exit.
6. Settings foundation: DataStore-backed settings screens for Display options (§11);
   values apply live to the running slideshow.

**Accept:** on the Fire HD, a folder of ~200 mixed photos plays smoothly (no visible
jank during transitions or Ken Burns), portraits pair or blur-fill per setting, clock/
captions render legibly, every Display setting takes effect without restart.

## Phase 2 — Immich source + sync engine (primary experience)

1. `PhotoSource` interface + source registry/config storage (§5); source management UI
   (add/edit/remove/toggle instances) with per-source health, last sync, photo count.
2. Immich client (§5.1): base URL + API key config, test-connection; album list +
   multi-select, people picker (names + face thumbnails), favorites, whole-library /
   rolling-window modes. Centralize endpoints; verify against the user's server
   `/api/docs`. LAN plain-HTTP support via network-security-config (§12).
3. Sync engine (§6): WorkManager periodic + manual sync-now, diff → download previews →
   LRU eviction with cap setting, mirror-vs-keep deletion policy, offline degradation,
   sync log for the diagnostics screen.
4. First-run wizard (§11): welcome → add Immich source → initial sync progress →
   slideshow.
5. Crash-restart handler (§10).

**Accept:** fresh install on the Fire HD reaches a playing slideshow from a real Immich
album in under two minutes; a photo taken on the phone (auto-backup to Immich, added to
a watched album/person) appears on the frame within one sync interval with no
interaction; pulling the server's network cable mid-slideshow changes nothing visible.

## Phase 3 — Power & kiosk

1. Quiet hours (§10): schedule setting, fake-off (black + min brightness), tap-to-wake
   5 min, automatic resume; sync paused during quiet hours.
2. Boot auto-start (§10): `BOOT_COMPLETED` receiver + `SYSTEM_ALERT_WINDOW` permission
   request flow with explanation screen.
3. Charging rule (§10): optional fake-off on unplug.

**Accept:** on the Fire HD — reboot with the app installed brings the slideshow back
unattended; quiet hours engage and release at the configured times across a real
overnight test; screen reads as "off" in a dark room during quiet hours.

## Phase 4 — Google Photos Picker source (official path)

1. `docs/GOOGLE_SETUP.md`: user-facing walkthrough — Cloud project, enable Picker API,
   consent screen in **Production** publishing status, Android OAuth client (package +
   SHA-1 of our keystore), unverified-app click-through (§5.2).
2. AppAuth OAuth flow: custom-scheme redirect, encrypted refresh-token storage, silent
   refresh, re-auth UX when refresh fails. Must work in Amazon Silk (no Custom Tabs
   assumption).
3. Picker session flow (§5.2): create session → QR code on frame + "Open here"
   fallback → poll per `pollingConfig` → page media items → immediate downloads into
   cache → delete session. Append-by-default with replace toggle and clear-all.
4. Source management UI: "N photos, picked <date>", prominent "Add more photos".

**Accept:** full pick-on-phone loop works end-to-end from the Fire HD (QR scanned with
a phone, photos picked in Google Photos, frame downloads and plays them); token
survives a week+ (Production status verified); picked photos persist across app
restarts and sync cycles without re-picking.

## Phase 5 — Shared-album watcher (unofficial Google auto-sync)

1. Parser (§5.3) in an isolated class: resolve share link, extract photo URLs +
   available metadata from `AF_initDataCallback` (regex fallback). Unit tests over
   saved HTML fixtures (fetch a real shared album page and commit it as a fixture).
2. Source instance config (paste link, masked afterwards), sync integration with
   URL-identity diffing, `DEGRADED` health when a known-good album parses to zero.
3. Document the Live Album recipe in `docs/GOOGLE_SETUP.md` (create live album with
   auto-add people → get share link → paste into frame).

**Accept:** photos added to the shared album on a phone appear on the frame within one
sync interval, no interaction; corrupting the fixture HTML makes the parser tests fail
(i.e. they genuinely pin the format); a bogus link surfaces a clear health error, not a
crash or silent emptiness.

## Phase 6 — Polish & hardening

1. `LocalFolderSource` via SAF (§5.4) — smallest slice, cut if time-boxed.
2. Diagnostics screen: recent sync log, cache usage, per-source health detail (§11).
3. Performance pass on the real device: transition frame timing, Ken Burns
   auto-disable heuristic, memory ceiling under a 10k-photo cache.
4. Long-soak test: 48 h continuous run on the Fire HD across two quiet-hours cycles —
   zero crashes/leaks (watch `adb shell dumpsys meminfo` trend), sync keeps working.
5. `README.md`: sideload instructions, source setup pointers, screenshot.

**Accept:** 48-hour soak passes; README lets a stranger set up the frame.

---

## Verification philosophy

Emulator for iteration; **the Fire HD is the truth** — every phase's acceptance run
happens on the physical tablet. The three killer end-to-end checks, in priority order:

1. Phone takes photo → Immich → frame shows it, hands-free (Phase 2).
2. Frame reboots/loses network/crashes → recovers to a playing slideshow, hands-free
   (Phases 2–3).
3. Google paths deliver photos without breaking the above (Phases 4–5).
