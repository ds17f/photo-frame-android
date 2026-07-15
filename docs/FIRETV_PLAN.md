# Fire TV Stick Port — Plan

Status: **planned, not started.** Written 2026-07-15. Target device: the owner's older
Fire TV Stick reporting SDK 25 — i.e. **Fire OS 6 (Android 7.1), almost certainly a Fire
TV Stick 4K 1st gen (2018, `Build.MODEL` AFTMM)**. Goal: the slideshow runs on the TV
while **Spotify keeps playing in the background**, with the whole app usable from the
Fire TV remote (D-pad + select + back + home + menu + play/pause — no touch).

## Feasibility verdict

Viable with no architectural change. API 25 is above Compose's floor (21), OkHttp 4.x's
floor (21), and security-crypto 1.1.x's floor (23). The Spotify goal is **officially
supported Fire OS behavior**, not a hack (see below). The work splits into a compat
floor (minSdk + desugaring), Fire TV manifest plumbing, and one real feature: a D-pad
input layer over UI that is currently 100% touch.

## The Spotify story (the original motivation — and the easy part)

Amazon's Fire TV docs state verbatim: *"If a user starts playing music from a music app
prior to starting your app, Amazon Fire TV will continue to play music over your app.
The Play/Pause buttons will control the music instead of the video in your app."*
Background audio stops only when the foreground app **requests audio focus**.

Our app plays no audio and never touches `AudioManager`/audio focus (verified — no media
or audio code anywhere). So the rules of engagement are:

- **Never add an audio-focus request** — this is now a hard rule alongside
  "slideshow renders only from local cache."
- **Do not handle `KEYCODE_MEDIA_PLAY_PAUSE`** in the key-event layer (Phase T3) — let it
  fall through to Spotify's media session so the remote's play/pause steers the music.
- Flow: start Spotify (on the stick, or from a phone via **Spotify Connect** — the nicer
  path since the slideshow will be fullscreen) → launch the frame → music continues.

Sources: developer.amazon.com/docs/fire-tv/differences-from-android-tv-development.html,
…/managing-audio-focus.html, …/multimedia-app-requirements.html.

## What blocks it today (codebase audit, 2026-07-15)

| # | Blocker | Where |
|---|---|---|
| 1 | `minSdk = 28` — APK refuses to install on API 25 | `app/build.gradle.kts` |
| 2 | `java.time.*` in ~20 files, core-library desugaring **off** (needs API 26+ without it) | pervasive: sync, playlist, overlays, settings, sources |
| 3 | **Zero D-pad support** — no `focusable`/`onKeyEvent`/`FocusRequester` anywhere; all interaction is tap / long-press / swipe | all of `ui/` |
| 4 | No `LEANBACK_LAUNCHER` category, no touchscreen `uses-feature`, no TV banner — app won't appear properly in the Fire TV launcher | `AndroidManifest.xml` |
| 5 | Launcher icons are adaptive-only (`mipmap-anydpi-v26`), no pre-26 PNG fallback — blank/default icon on API 25 | `res/` |
| 6 | TLS trust: Android 7.1 predates ISRG Root X1 (Let's Encrypt) — 7.1.**1** was the cutoff. No `networkSecurityConfig` exists to bundle roots | `ImmichClient`, manifest |

Non-blockers with TV wrinkles:

- **Quiet-hours "fake off"** dims via `window.attributes.screenBrightness = 0f`
  (`SlideshowRoute.kt`) — a no-op over HDMI; the TV panel isn't app-controllable.
- **`screenOrientation="fullSensor"`** on a sensorless, always-landscape device.
- **SD-card cache option** is meaningless on a stick; `PhotoCache.resolveRemovableRoot()`
  already degrades to internal, but the radio option will show as unavailable.
- **EncryptedSharedPreferences** (Immich API key storage) is Keystore-backed and
  historically flaky on older non-GMS devices — a test-first risk, not a known failure.
- **kotlinx.serialization + R8**: we already shipped the `ClassValue` crash on API 28
  (see the proguard keep rule from that fix). API 25 also lacks `java.lang.ClassValue`,
  so that rule stays load-bearing — verify a **release** build on the stick, not just
  debug.
- **Boot relaunch** works *better* here than on modern Android: API 25 has no
  background-activity-start restrictions, so `BootReceiver` needs no overlay permission.

## Phases

### Phase T0 — Probe the stick (no app changes)

`adb connect <stick-ip>` and settle the three unknowns before writing code, with a
throwaway probe APK (minSdk 25, a button per check) or adb alone:

1. **Spotify-over-app**: install Spotify on the stick, start playback, launch any
   sideloaded app — confirm music continues and remote play/pause steers Spotify on
   this Fire OS 6 build.
2. **TLS to Immich**: OkHttp `GET` against the server. If it fails
   (`SSLHandshakeException` → missing root), confirm bundling ISRG Root X1 via
   network-security-config fixes it. (LAN server — actual cert setup determines how
   much of blocker #6 is real.)
3. **EncryptedSharedPreferences**: create + write + reopen-and-read round-trip.

Acceptance: three yes/no answers recorded here; plan below adjusted if any is "no."

### Phase T1 — Install & boot (compat floor)

- `minSdk 28 → 25` (single APK for tablet + stick; nothing here changes tablet
  behavior — no flavor split unless T3 forces one).
- Enable `isCoreLibraryDesugaringEnabled` + `coreLibraryDesugaring(...)` — fixes all
  `java.time` usage at once. Full-app regression on the **tablet** afterward: desugaring
  rewrites bytecode globally, so the tablet build is the thing to re-verify.
- Legacy raster launcher icons for pre-26 (`mipmap-{m,h,xh,xxh,xxxh}dpi` PNGs).
- Network-security-config with `<trust-anchors>` = system + bundled ISRG Root X1/X2
  (or the LAN CA), **if** T0.2 showed failure.
- Audit any behavior gated between 25 and 28 (only one `SDK_INT` check exists — the
  blur fallback in `BlurFillBackground.kt`, already safe).

Acceptance: debug **and release** APKs install on the stick; app boots, completes an
Immich metadata sync, and the slideshow plays (driven via `adb shell input` taps since
the UI isn't remote-navigable yet). Tablet build re-verified.

### Phase T2 — Fire TV launcher & manifest

- Add `android.intent.category.LEANBACK_LAUNCHER` **alongside** `LAUNCHER` on
  `SlideshowActivity` (harmless on the tablet; the combination is what reliably shows
  up in the Fire TV apps row for sideloaded apps).
- `<uses-feature android:name="android.hardware.touchscreen" android:required="false"/>`
  (and `android.software.leanback` `required=false` for tidiness).
- `android:banner` — 320×180 TV banner drawable.
- `screenOrientation`: `fullSensor → unspecified` (or `sensorLandscape`); verify the
  tablet still rotates as expected.

Acceptance: app appears in Fire TV "Your Apps & Channels" with a proper icon/banner and
launches from the remote.

### Phase T3 — D-pad input layer (the bulk of the work)

**Slideshow** (`SlideshowRoute`): an `onKeyEvent` layer mapping —

| Remote key | Action | Today's touch equivalent |
|---|---|---|
| DPAD_LEFT / DPAD_RIGHT | previous / next photo | horizontal swipe |
| DPAD_CENTER / select | show transient controls | tap |
| MENU | per-photo actions dialog | long-press |
| BACK | controls → exit (two-step) | already works (`BackHandler`) |
| PLAY_PAUSE | **not handled** — falls through to Spotify | n/a |

Also: DPAD_CENTER must wake from quiet-hours fake-off (today: tap).

**Controls bar + settings screens**: make every `IconButton` / `clickable` row
focusable with **visible focus indication** — stock mobile Material shows essentially
none on TV (the classic Compose-on-TV pitfall). One shared
`Modifier.tvFocusHighlight()` (border/background via
`interactionSource.collectIsFocusedAsState()`) applied everywhere beats adopting
`androidx.tv:tv-material` for a UI this small. Request initial focus per screen with a
`FocusRequester` in a `LaunchedEffect` — nothing is focused by default, so the D-pad is
otherwise dead on arrival. `AlertDialog` buttons need the same treatment.

**Text entry** (Immich name/URL/API key, GPhotos fields): the Fire TV on-screen
keyboard drives `OutlinedTextField` — painful but it's one-time setup, and the Fire TV
phone app's keyboard makes it painless. No code change planned; the GPhotos QR flow is
already remote-free.

Acceptance: complete setup-to-slideshow flow (add Immich source, sync, watch, open
per-photo menu, favorite a photo, change a setting) **using only the remote**, on the
stick. Tablet touch flow unchanged.

### Phase T4 — TV behavior polish

- **Quiet-hours on TV**: render a black overlay instead of (or in addition to) the
  brightness override — black frames are the only app-level "off" over HDMI. Keep the
  brightness path for the tablet; pick by `UI_MODE_TYPE_TELEVISION`.
- Hide the SD-card storage option when no removable storage exists.
- **Sleep/screensaver soak test**: `FLAG_KEEP_SCREEN_ON` (already set) should suppress
  the Amazon screensaver and the ~20-min sleep per Amazon's docs — verify over an
  hours-long run. Known remedy if the stick sleeps anyway:
  `adb shell settings put secure sleep_timeout 0`. (TV-side eco/auto-standby timers are
  TV settings, not app code.)

Acceptance: overnight run — no screensaver, no sleep; quiet hours blank the TV.

### Phase T5 — Spotify acceptance (the finish line)

On the stick: start Spotify → launch frame → music continues; remote play/pause steers
Spotify while D-pad steers photos; Spotify Connect from a phone starts/changes music
while the slideshow stays fullscreen; slideshow survives hours of simultaneous playback.

Acceptance: all of the above, plus network loss during playback affects neither photos
(hard rule) nor confuses the app when Spotify drops.

## Rough sizing

T0 ~an hour with the stick in hand. T1–T2 ~a day of build/config work. T3 is the real
effort — it touches most `ui/` screens. T4–T5 are small. Total: a comfortable weekend
project if T0's probes all come back "yes."

## Non-goals

- Fire OS 5 (API 22) sticks — below what current Compose supports; out of scope.
- Amazon Appstore publishing — sideload-first stands (see project memory/decisions).
- Audio playback of any kind in the app itself.
