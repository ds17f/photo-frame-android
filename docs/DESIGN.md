# Photo Frame — Design Specification

An Android digital photo frame app for Amazon Fire HD tablets. It runs a full-screen
slideshow sourced from the owner's photo services, auto-syncing so that newly taken
photos appear on the frame without manual steps.

## 1. Goals

- Turn a Fire HD 8/10 tablet into an always-on photo frame, mounted in landscape.
- **Auto-sync:** new photos the user takes should appear on the frame automatically
  (Immich auto-backup → frame polls → photo shows up; no taps required).
- Handle portrait photos gracefully on a landscape screen.
- Common frame features: transitions, Ken Burns, clock/date overlay, photo captions,
  scheduled quiet hours.
- Fully usable offline once photos are cached (server/Wi-Fi outages must not blank the frame).

## 2. Non-goals (future ideas, explicitly out of scope for v1)

- Weather overlay, background music, video playback.
- Remote control / companion app / multi-frame management.
- Camera-based presence detection.
- Play Store / Amazon Appstore distribution (sideload only).

## 3. Target environment & constraints

| Constraint | Consequence |
|---|---|
| Fire HD 8/10 (2020+), Fire OS 7/8 (Android 9/11 base) | `minSdk 28`, `targetSdk 34+`, `compileSdk` latest stable |
| **No Google Play Services / GMS** on Fire OS | No play-services or Firebase dependencies anywhere. OAuth must use browser-based AppAuth, not Google Sign-In SDK |
| Screens: 1280×800 (HD 8), 1920×1200 (HD 10) | Download/decode images at ~2048px max; never hold full-res originals in memory |
| 2–3 GB RAM, modest GPU | Downsample bitmaps to view size, preload only one photo ahead, keep transitions GPU-cheap |
| Default browser is Amazon Silk | AppAuth must tolerate missing Custom Tabs (falls back to a plain `VIEW` intent) |
| Sideloaded APK | Simple `assembleRelease` + `adb install` workflow; self-signed keystore generated locally via `scripts/init-signing.sh` (gitignored, never committed — CI signs from GitHub secrets) is fine for a personal app |
| Tablet lives on home LAN, plugged in | Immich reachable by LAN URL; "keep screen on" is safe; optional sleep-when-unplugged |

Development note: no Fire tablet is needed for day-to-day dev. Use a standard Android
emulator (API 30, tablet profile, 1280×800 landscape) and treat "no GMS" as a hard rule.
Final verification happens by sideloading to the real device.

## 4. Architecture overview

```
┌────────────────────────────────────────────────────────────┐
│ UI (Jetpack Compose, single Activity)                      │
│  SlideshowScreen ── overlays (clock, caption, controls)    │
│  Settings screens ── source management, display, schedule  │
├────────────────────────────────────────────────────────────┤
│ Playlist Engine                                            │
│  shuffle bag · portrait pairing · pacing                   │
├────────────────────────────────────────────────────────────┤
│ Sync Engine (WorkManager)                                  │
│  per-source diff → download → cache (LRU) → Room index     │
├────────────────────────────────────────────────────────────┤
│ PhotoSource abstraction                                    │
│  ImmichSource │ GPhotosPickerSource │ GPhotosSharedAlbum   │
│  (bonus: LocalFolderSource — also used as dev fixture)     │
└────────────────────────────────────────────────────────────┘
```

**Tech stack:** Kotlin, Jetpack Compose + Material 3, Room, WorkManager, DataStore
(preferences) + EncryptedSharedPreferences (secrets), OkHttp + Retrofit +
kotlinx.serialization, Coil (image loading), AppAuth-Android (OAuth), ZXing (QR code
rendering). Java 17 toolchain, current AGP/Gradle. **No dependency may transitively
require Google Play Services.**

## 5. Photo sources

All sources implement one interface; all are equal citizens — the user can enable any
combination, and the playlist mixes them.

```kotlin
interface PhotoSource {
    val id: String                    // stable per configured source instance
    val type: SourceType              // IMMICH, GPHOTOS_PICKER, GPHOTOS_SHARED_ALBUM, LOCAL_FOLDER
    suspend fun healthCheck(): SourceHealth
    suspend fun listPhotos(): List<RemotePhoto>   // full desired set per current selection config
    suspend fun fetch(photo: RemotePhoto, sink: Sink)  // download bytes (display-sized)
}

data class RemotePhoto(
    val remoteId: String,
    val takenAt: Instant?,            // for chronology + captions
    val width: Int?, val height: Int?, // orientation; may be unknown (shared album) until download
    val locationLabel: String?,       // e.g. "Lisbon, Portugal" when the source provides it
    val contentHash: String?          // for change detection when available
)
```

Multiple configured instances of the same type are allowed (e.g. two Immich album
selections, three shared-album links).

### 5.1 ImmichSource (primary)

- Config: server base URL + API key (created in Immich user settings). Auth via
  `x-api-key` header. A "Test connection" button calls the server-info endpoint.
- Selection modes (all supported, combinable per source instance):
  - **Albums:** list albums, user multi-selects; sync pulls each album's assets.
  - **People:** list recognized people (with thumbnail + name), user multi-selects;
    sync uses metadata search filtered by person IDs.
  - **Favorites:** metadata search with `isFavorite=true`.
  - **Whole library / recent window:** all photos, or `takenAfter` a rolling window
    (e.g. last 12 months) — window length is a setting.
- Downloads use Immich's pre-generated **preview** thumbnail endpoint (~1440px), not
  originals — fast, small, ideal for the screen.
- Filter to image assets only (skip videos); skip trashed/archived assets.
- **Implementation caution:** Immich's API evolves across versions. The builder must
  code against the OpenAPI spec served by the user's own server at `<server>/api/docs`
  (or the published spec for its version), keep all endpoint paths in one small client
  class, and surface clear runtime errors (`SourceHealth`) when an endpoint 404s so
  version drift is diagnosable from the frame's UI. Expected endpoint shapes (verify,
  don't trust blindly): `GET /api/albums`, `GET /api/albums/{id}`, `GET /api/people`,
  `POST /api/search/metadata`, `GET /api/assets/{id}/thumbnail?size=preview`.

### 5.2 GPhotosPickerSource (official Google path)

Google's Library API read scopes were removed 2025-03-31; the Picker API is the only
official way to read a user's Google Photos content. The trade-off is inherent:
**the app only ever sees photos the user explicitly picked**, and to add new photos
the user re-opens the picker. Build the re-pick flow to be as pleasant as possible.

- OAuth 2.0 with scope `https://www.googleapis.com/auth/photospicker.mediaitems.readonly`,
  refresh token stored encrypted. **Mechanism (revised — Google deprecated the original plan; see
  `docs/API_NOTES.md`):** custom-scheme and loopback redirects are no longer supported for the
  *Android* OAuth client type, and Google's SDK replacement needs GMS (absent on Fire OS); the
  device-code flow disallows the Picker scope. The only no-GMS-viable path is a **Desktop-type**
  OAuth client + **loopback redirect** (`http://127.0.0.1:<ephemeral port>`): the app binds a local
  socket, opens the browser (Silk-safe `ACTION_VIEW`), catches the `?code=` redirect, and exchanges
  it with PKCE. The Desktop client secret is non-confidential for installed apps but kept in
  EncryptedSecrets. **The one-time sign-in therefore happens on the tablet** (loopback is
  same-device); each photo *pick* below still happens on the phone via QR.
- Picking flow:
  1. `POST https://photospicker.googleapis.com/v1/sessions` → returns `pickerUri` + polling config.
  2. Show the `pickerUri` **as a QR code on the frame** with the hint "Scan with your
     phone to pick photos in Google Photos" (much nicer than picking on the tablet),
     plus a fallback "Open here" button.
  3. Poll the session per the returned `pollingConfig` until `mediaItemsSet=true`.
  4. Page through `GET /v1/mediaItems?sessionId=…`, then **download every picked item
     immediately** (`baseUrl=w2048-h2048`, `Authorization: Bearer`) — picker grants
     and baseUrls are short-lived, so the local cache is the source of truth.
  5. Delete the session.
- New picks **append** to the source's photo set (dedupe by media item ID); provide a
  "Replace instead of add" toggle in the flow and a way to clear the set.
- Settings UI shows "N photos, picked <date>" and a prominent "Add more photos" action.
- **User setup required (see `docs/GOOGLE_SETUP.md`):** create a Google Cloud project, enable the
  Photos Picker API, configure the OAuth consent screen, and — critically — set publishing status
  to **Production** (in Testing mode refresh tokens die after 7 days; the "unverified app" warning
  during sign-in is fine for personal use). Create a **Desktop app** OAuth client (client ID +
  secret) — **no package name or SHA-1 needed** (the Android client type's redirects are
  deprecated; see the OAuth note above).

### 5.3 GPhotosSharedAlbumSource (unofficial, enables Google auto-sync)

Watches a public "anyone with the link" shared album by parsing its web page — no
auth, no Cloud project. Paired with a Google Photos **Live Album** (auto-add of
selected people/pets), this restores true auto-sync from Google Photos.

- Config: one shared album URL per source instance (user pastes the `photos.app.goo.gl`
  or `photos.google.com/share/…` link).
- Sync: follow redirects to the album page, extract photo base URLs
  (`https://lh3.googleusercontent.com/pw/…`) from the embedded `AF_initDataCallback`
  JSON (fall back to regex over the HTML). Request display size by appending
  `=w2048-h2048`. Where present in the embedded data, also extract width/height and
  timestamp; otherwise derive orientation after download.
- Diffing by base-URL identity: new URLs are downloaded, vanished URLs are handled per
  the deletion policy (§6).
- **Fragility is a design input:** the parser lives in one isolated class with its own
  unit tests over saved HTML fixtures. When parsing yields zero photos from a
  previously working album, the source reports `DEGRADED` health with a clear message
  ("Google may have changed the shared-album format") on the source status screen —
  never silently show an emptier frame. Cached photos keep displaying regardless.

### 5.4 LocalFolderSource (bonus, near-free)

Slideshows a device folder chosen via Storage Access Framework (e.g. a Syncthing or
LocalSend target dir). Trivial to implement, doubles as the development fixture source
and an escape hatch if every network source is down. Low priority; cut last if time
matters.

## 6. Sync engine & cache

- **Schedule:** WorkManager periodic sync per enabled source, default every 60 min
  (configurable 15 min–24 h; Immich default 30 min), constrained to unmetered network.
  Manual "Sync now" in settings. Immich on LAN is cheap to poll; a future push/websocket
  upgrade is out of scope.
- **Per-sync flow:** `listPhotos()` → diff against Room index → enqueue downloads for
  new items → verify/evict per policy → update index. Downloads are sequential with
  modest parallelism (2–3) to keep the UI smooth.
- **Cache:** app-private storage (`filesDir/photos/<sourceId>/…`), display-sized files
  (~200–600 KB each). Configurable cap, default **4 GB** (≈10–20k photos), LRU-evicted
  by `lastShownAt` — but never evict below one full playlist cycle.
- **Deletion policy (setting):** default **mirror** — photos removed from the remote
  selection disappear from the frame (matches "I deleted it for a reason"). Option:
  keep-until-evicted. Exception: Picker source never mirrors (its grants expire by
  design) — picked photos persist until the user clears them.
- **Offline resilience:** the slideshow reads only from the local cache; sync failures
  mark the source `DEGRADED`/`OFFLINE` on the status screen and are otherwise
  invisible. First-run with an empty cache shows a friendly setup/progress screen.
- **Room schema (sketch):** `photos(pk = sourceId+remoteId, localPath, takenAt,
  width, height, locationLabel, addedAt, lastShownAt, sizeBytes, status)` plus a
  `sources` table for config snapshots and health.

## 7. Playlist engine

- **Order modes:** Shuffle (default), Chronological, Newest-first, and **Recency-boosted
  shuffle** — photos added in the last N days (default 7) get ~3× draw weight, so new
  photos show up promptly without dominating.
- **Shuffle bag:** random-without-replacement across the merged photo set of all
  enabled sources; reshuffle when exhausted. Newly synced photos are spliced into the
  current bag near the front (that's the "new photo appears soon" moment).
- **Interval:** per-photo display duration, default 30 s (range 5 s–1 h).
- **Portrait handling** (`auto` default / `pair` / `blur-fill` setting):
  - `auto`: when the next photo is portrait, look ahead up to K=10 items in the bag for
    another portrait; if found, show the two side-by-side (each fit to full height,
    thin gap, order preserved); otherwise blur-fill.
  - `blur-fill`: portrait centered at full height over a blurred, scaled copy of itself
    (RenderEffect on API 31+; RenderScript-free fallback — e.g. downscale + stack blur —
    on API 28–30, since Fire OS 7/8 devices need it).
  - Paired photos count as one slideshow step; both get `lastShownAt` updates.
- Landscape photos: fit-inside (letterbox with blur-fill sides if aspect differs
  significantly, else subtle black bars — blur-fill everywhere is the default look).

## 8. Rendering, transitions & effects

- Single always-on Activity, immersive full-screen (hide status/nav bars),
  `FLAG_KEEP_SCREEN_ON`, orientation follows sensor (landscape is primary; portrait
  mounting must also work — the pairing logic simply flips axis).
- **Transitions** (setting + duration 0.5–3 s): Crossfade (default), Slide,
  Zoom-crossfade, None. Implemented with Compose `AnimatedContent`; keep to alpha/scale/
  translate transforms only (GPU-cheap on Fire hardware).
- **Ken Burns** (on by default): slow pan/zoom over the photo's display duration.
  Randomized per photo: zoom 1.0→1.06–1.12, drift direction chosen so the frame never
  reveals letterbox edges. Runs on `graphicsLayer`; disabled automatically if the
  device struggles (dropped-frame heuristic) and toggleable in settings.
- **Preloading:** decode the next photo (or pair) fully before its transition starts;
  never decode on the UI thread; Coil memory cache sized conservatively for 2 GB RAM.

## 9. Overlays

- **Clock & date** (toggleable, on by default): digital time + date, corner position
  setting, semi-transparent white with soft shadow for legibility on any photo. Font
  size setting (small/medium/large — kitchen vs. bedside viewing distance).
- **Photo caption** (toggleable): photo date ("June 2024") and location label when the
  source provides one, fading in for the first ~5 s of each photo, opposite corner from
  the clock. Paired portraits: caption only if both share a date context, else omit.
- Overlays must never both occupy the same corner; layout resolves collisions.

## 10. Power, scheduling & kiosk behavior

- **Quiet hours** ("scheduled off", e.g. 23:00–07:00, per-day-of-week optional):
  the app can't truly power the screen off without device-admin, so it does what Fotoo
  does — **fake off**: render a pure-black screen and drop window brightness to
  minimum (on the HD's LCD this reads as off in a dark room). Slideshow, Ken Burns and
  sync pause; a tap during quiet hours wakes the frame for 5 min. At the scheduled end,
  the frame resumes automatically (the activity never stopped, so no wake-lock
  gymnastics needed).
- **Auto-start on boot:** `BOOT_COMPLETED` receiver relaunches the slideshow. On API
  29+ background activity starts require the **"Display over other apps"**
  (`SYSTEM_ALERT_WINDOW`) permission — the settings screen requests it with an
  explanation, and setup docs cover the Fire OS toggle.
- **Charging awareness (setting, default off):** optionally fake-off while unplugged,
  resume on power.
- **Crash resilience:** default uncaught-exception handler schedules an app restart
  (AlarmManager, ~5 s) before dying — a frame on a shelf must self-heal.

## 11. UX

- **Slideshow gestures:** tap → show controls for 5 s (pause/play, prev/next, source
  health glance, gear icon); swipe left/right → next/previous photo; back button →
  show controls first, second press exits (with "frame will stop" toast).
- **Settings** (Compose screens): Sources (add/edit/remove/toggle each instance; type-
  specific config flows from §5; per-source health + last-sync + photo count), Display
  (interval, transition, Ken Burns, portrait mode, order mode, overlays), Schedule
  (quiet hours, charging rule), Cache (size cap, usage, clear), About/diagnostics
  (recent sync log — invaluable for a headless frame).
- **First-run wizard:** welcome → add first source → initial sync with progress →
  slideshow. The Immich path should take under two minutes.
- Visual style: dark theme throughout (it's a photo frame; chrome must recede).

## 12. Security & privacy

- Immich API key and OAuth tokens in `EncryptedSharedPreferences`; never logged.
- Photo cache is in app-private storage (not world-readable, excluded from backups
  via `android:allowBackup="false"` or backup rules).
- Shared-album URLs are secrets in practice (anyone with the link sees the photos) —
  treat them like keys: masked in UI after entry, never logged.
- All network calls over HTTPS; plain HTTP allowed only for LAN Immich URLs via a
  scoped network-security-config exception for the user-entered host (Immich on LAN is
  commonly `http://192.168.x.x:2283`).

## 13. Risks & mitigations

| Risk | Mitigation |
|---|---|
| Shared-album page format changes | Isolated parser + HTML fixtures + `DEGRADED` health surfacing; cache keeps frame alive; Picker & Immich unaffected |
| Immich API drift across versions | Endpoints centralized in one client; runtime health errors name the failing endpoint; verify against user's `/api/docs` |
| Picker grants expire | Local cache is source of truth; picked photos persist until user clears |
| Google OAuth "unverified app" friction | Documented one-time click-through; Production publishing status avoids 7-day token death |
| Fire OS quirks (Silk, no GMS, boot restrictions) | No-GMS dependency rule; AppAuth browser fallback; overlay permission for boot start; test on real device each phase |
| 2 GB RAM pressure | Display-sized downloads, single-photo preload, conservative Coil caches, bitmap downsampling |
