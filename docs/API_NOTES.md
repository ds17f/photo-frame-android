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

**Status: VERIFIED against the owner's live server — Immich v3.0.2 (2026-07-13).** Reachable at
`https://immich.example.com` (resolves to `192.168.1.100` on the LAN; valid TLS cert). All
paths centralized in `data/source/immich/ImmichApi.kt`.

| Purpose | Method + path | Result |
|---|---|---|
| Connection + auth test | `GET /api/albums` | ✅ Used for test-connection. NOT `/api/users/me` — that needs the unrelated `user.read` permission (403 without it); `/api/albums` needs only `album.read`, which the app requires anyway. |
| Delete (trash) | `DELETE /api/assets` `{ids,force:false}` | needs `asset.delete`. Long-press → delete. |
| Favorite | `PUT /api/assets` `{ids,isFavorite:true}` | needs `asset.update`. Long-press → add to favorites. |
| List albums | `GET /api/albums` | ✅ summaries (`id`, `albumName`, `assetCount`). |
| Recognized people | `GET /api/people?withHidden&page&size` | ✅ `{people:[{id,name,thumbnailPath}], total, hasNextPage}`. **Pages at 500.** |
| Metadata search | `POST /api/search/metadata` | ✅ Backs albums + people + favorites + window. |
| Preview download | `GET /api/assets/{id}/thumbnail?size=preview` | ✅ 200 `image/jpeg` (~320 KB). `size=thumbnail`→webp. |
| Person face thumbnail (UI) | `GET /api/people/{id}/thumbnail` | needs `x-api-key` header (Coil). |
| Ping (unauth) | `GET /api/server/ping` | ✅ `{"res":"pong"}` (path is `server/ping`, not `server-info/ping`). |

**v3 breaking changes vs the ~v1 shapes originally coded (both fixed):**
1. **`GET /api/albums/{id}` no longer embeds `assets`** (returns metadata only; `?withoutAssets=false`
   does nothing; no `/albums/{id}/assets` route). → Album assets are now fetched via
   `POST /api/search/metadata` with `albumIds:[...]` (paginated). `ImmichSource` does one search per album.
2. Assets expose **oriented top-level `width`/`height`** (plus raw `exifInfo.exifImageWidth/Height`)
   and a `visibility` enum (`timeline`/…) alongside `isArchived`/`isTrashed`.

- **Auth:** `x-api-key` header (injected by `ImmichClient`).
- **Search request** sends `type=IMAGE`, `isArchived=false`, `isVisible=true`, `withExif=true`,
  `page`, `size=1000`, plus one of `albumIds` / `personIds` / `isFavorite` / `takenAfter` — **all
  accepted by v3**. Pagination via string `assets.nextPage`. (Note: `assets.total` reflects the
  current page count, not the grand total — rely on `nextPage`, which the code does.)
- **⚠️ Bug caught by live testing — `encodeDefaults`:** kotlinx.serialization omits default-valued
  fields unless `encodeDefaults=true`. Without it, `type=IMAGE`/`isArchived=false`/`isVisible=true`
  were silently dropped from every request, so the server applied none of them (only weaker
  client-side filtering that misses hidden/locked assets). Fixed in `ImmichClient`'s `Json`.
- **This library's data:** 80 albums, 2757 face records but **0 are named** → the people picker is
  legitimately empty until faces are named in Immich (not a bug). `ImmichCatalog.people()` pages
  through collecting only named people.
- **Asset fields used:** `id`, `type`, `isFavorite/isArchived/isTrashed`, `fileCreatedAt`,
  `localDateTime`, `checksum` (→ `contentHash`), top-level `width`/`height`,
  `exifInfo.{exifImageWidth,exifImageHeight,city,country,dateTimeOriginal}`. The prefetcher still
  decodes the downloaded preview for the definitive display size (portrait pairing).
- **⚠️ Full-library listing is slow — offset pagination (caught by live device testing,
  streaming-cache model).** `POST /api/search/metadata` is page/offset-based, and over the owner's
  ~71k-asset library a full `size=1000` listing takes **~104 s** (71 sequential pages; later pages
  get slower as the server's OFFSET grows). Consequences and mitigations:
  - The `MetadataSyncer` **streams** pages (`PhotoSource.listPhotos(onPage)`), inserting index rows
    per page so the frame lights up within seconds and each page's DTOs are freed — before, holding
    all 71k `ImmichAssetDto`+`RemotePhoto` in memory at once pinned the heap and caused a sustained
    ~34 MB/1M-object GC storm ("startup takes forever"). Post-fix: ~3 MB GC blips.
  - The full listing runs only on the **periodic** job and on a **stale** launch (last sync older
    than the interval) — not on every warm start (`PhotoFrameApp` guards it).
  - **Pagination is not a stable snapshot:** re-listing the *same* unchanged library returns a
    slightly different set each pass (~120 of 71k drift at page boundaries — non-unique sort key +
    concurrent changes). Under MIRROR this shows as a phantom `~117 indexed / ~126 removed` churn
    each sync (deletes+re-adds boundary photos, losing their `lastShownAt`). Immich search exposes
    no stable-cursor/custom-sort knob to eliminate it. Tolerable for now; a "delete only after
    absent across two syncs" guard is the candidate fix if it ever matters.

## Google Photos Picker

**OAuth mechanism — spec (DESIGN §5.2) was wrong; Google deprecated it. Verified 2026-07.**
DESIGN §5.2 called for AppAuth with a custom-scheme redirect (reversed client id). That no longer
works with Google:
- **Custom URI schemes are no longer supported** for the *Android* (and Chrome) OAuth client type.
- **Loopback redirect is deprecated** for the *Android/iOS/Chrome* client types (Desktop only).
- Google's recommended replacement is the **Android SDK / Credential Manager**, which requires
  **Google Play Services** — absent on Fire OS (hard rule).
- The **device-authorization (limited-input) flow** — the "scan a code, log in on your phone" UX —
  does **not** allow the Picker scope. Its allowlist is only `email`/`openid`/`profile`,
  `drive.file`, `drive.appdata`, `youtube[.readonly]`.

**Implemented instead:** a **Desktop-type** OAuth client + **loopback redirect**
(`http://127.0.0.1:<ephemeral port>`), which is the only browser flow that works with no GMS *and*
the Picker scope. `GPhotosAuth` binds a short-lived `ServerSocket`, opens the system browser (Silk,
plain `ACTION_VIEW`) at `accounts.google.com/o/oauth2/v2/auth` with `access_type=offline` +
`prompt=consent` + PKCE (S256), catches the `?code=` on the socket, and exchanges it at
`oauth2.googleapis.com/token`. Consequence: the one-time sign-in is **on the tablet** (loopback
needs same-device browser+server); picking is still phone-QR. The Desktop client secret is shipped
in the app (non-confidential for installed apps, per Google) but kept in EncryptedSecrets.

**Picker API endpoint shapes — coded to Google's spec; verify on device and correct here.**
Base `https://photospicker.googleapis.com/v1`, all calls `Authorization: Bearer <access token>`:
- `POST /sessions` (empty body) → `{ id, pickerUri, mediaItemsSet, pollingConfig{ pollInterval,
  timeoutIn } }`. `pollInterval`/`timeoutIn` are protobuf Duration strings (e.g. `"5s"`), parsed by
  stripping the `s`.
- `GET /sessions/{id}` → same shape; poll until `mediaItemsSet == true`.
- `GET /mediaItems?sessionId=…&pageSize=…&pageToken=…` → `{ mediaItems[]{ id, createTime, type,
  mediaFile{ baseUrl, mimeType, filename, mediaFileMetadata{ width, height } } }, nextPageToken }`.
  Only `type == "PHOTO"` items are kept (videos skipped).
- `DELETE /sessions/{id}` after downloading.
- **Downloads:** `GET <baseUrl>=w2048-h2048` with the bearer header, streamed straight into the
  cache and indexed `READY` immediately (picker baseURLs/grants are short-lived — the local cache is
  the source of truth; nothing is deferred to the periodic prefetcher).

**Sync-engine integration.** The picker source's `listPhotos` re-emits the already-stored set, so a
periodic `MetadataSyncer` pass is a no-op diff; picker rows use a non-MIRROR policy and are pinned
out of `CacheEvictor` (an evicted file could never be re-fetched). `GPhotosPickerSource.fetch`
throws — it must never be reached.

## Photo deep-links (tap-to-QR)

The long-press "Show QR code" action needs a **durable, per-photo URL** the owner can open on a
phone. It currently works for **Immich only** and is scoped there in code
(`SlideshowViewModel.photoWebUrl` returns null unless the source resolves to an `ImmichConfig`).

- **Immich — durable.** `"$baseUrl/photos/$assetId"` (the web viewer). `baseUrl` is persisted
  non-secret in `ImmichConfig`; `assetId` is the row's `remoteId`. No network call needed. Opens in
  the Immich mobile app if it's registered to handle the server host, else the browser. Because the
  server is on the LAN, the link only resolves when the phone is on home wifi — expected for a
  personal LAN frame. **Verify on device:** whether the installed Immich app actually intercepts the
  URL vs. falling back to web (undocumented, install-dependent).

### Google Photos link — open investigation (deferred)

No durable per-photo URL is available from what we store today, so the QR action is hidden for
Picker photos (they report "No shareable link for this source").

- The **Picker API** `mediaItem` exposes only a short-lived `mediaFile.baseUrl` (expiring grant,
  used once at pick time) and **no `productUrl`**. We persist `clientId`/`pickedCount`/`lastPickedAt`
  only — not any URL. So there is nothing durable to encode.
- The old **Library API** `productUrl` (a `photos.google.com/lr/...` link that opens the photo in the
  Google Photos app/web) is **not** returned by the Picker API, and broad Library API access to a
  user's own library was restricted/deprecated (March 2025). The Picker `mediaItem.id` is **not** a
  shareable link and can't be turned into one client-side.

**To investigate before building:** (1) whether any Picker response field (or a follow-up call) can
yield a `productUrl`/`photos.google.com` link for a picked item — current spec says no; (2) whether
re-sharing/album flows expose a stable web URL; (3) if neither, whether the feature is simply
Immich-only by nature. Storing `baseUrl` is a dead end (it expires). Likely conclusion: **not
possible without a durable URL the Picker API doesn't provide** — record the finding here when
confirmed against a live account.

## Google Photos shared album (unofficial parser)

_(none yet — Phase 5)_
