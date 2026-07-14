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
| Connection + auth test | `GET /api/users/me` | ✅ 200 (id/email/name). Used for test-connection. |
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
  `exifInfo.{exifImageWidth,exifImageHeight,city,country,dateTimeOriginal}`. `SourceSyncer` still
  decodes the downloaded preview for the definitive display size (portrait pairing).

## Google Photos Picker

_(none yet — Phase 4)_

## Google Photos shared album (unofficial parser)

_(none yet — Phase 5)_
