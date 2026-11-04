# Streaming Cache — Design

Status: **designed, ready to plan implementation.** Written 2026-07-14. Supersedes the
earlier exploration in this file (mirror-the-library-onto-SD, "Option A/B"). This is now
the intended design; DESIGN.md §6 should be amended to match once implemented.

## The pivot

The old model treated the local cache as a **mirror of the library**: sync downloaded as
much of the collection as fit under the cap, newest-first, and the slideshow could only
ever show what was physically on disk. That welded "how deep into the collection the
frame can see" to "how many bytes fit on disk" — hence the 4 GB cap, the SD-card
pressure, and the frame never going wide & deep.

The new model treats the cache as a **prefetch buffer over a full metadata index**:

1. The Room DB holds a row for **every photo on the remote** (~60k rows — metadata only,
   no files). This is the "list of ALL the URLs."
2. The **playlist draws from the full index**, so shuffle roams the entire collection by
   construction. No sampling algorithm needed.
3. A **prefetcher** downloads the next ~20 upcoming photos ahead of playback (LAN Immich,
   ~250 KB previews → sub-second each).
4. **Playback renders only from local files** (hard rule unchanged). If an upcoming photo
   isn't downloaded yet, it's skipped, never waited for.
5. **Eviction** deletes *files*, never index rows, least-recently-shown first, down to a
   modest cap. Everything under the cap is a retained pool that avoids re-downloading
   recently shown photos.
6. The cap becomes a boring buffer size (default **1 GB ≈ ~4k photos**), not a depth
   limit. It fits comfortably on internal storage; the **SD card remains an option** for
   whoever wants a bigger retained pool.

Requirements from the owner (2026-07-14 discussion):

- Keep the SD-card cache-location option — we may still want the card for the cache.
- The full index must **refresh regularly** so new photos on the remote appear on the
  frame soon after they're added.
- Keep **per-photo last-shown tracking** — it now persists for the photo's lifetime
  (rows are never deleted by eviction) and is something selection can key on later.

## Data model

### `PhotoEntity` becomes an index row (file optional)

| field | change |
|---|---|
| `localPath` | **nullable** — null means "indexed, not cached" |
| `sizeBytes` | 0 when not cached |
| `status` | `INDEXED` (no file) / `READY` (file on disk). Existing `PhotoStatus` enum grows `INDEXED`. |
| `lastShownAt` | unchanged — but now **survives eviction** (rows persist), giving a permanent per-photo show history |
| `width`/`height` | populated from remote metadata at index time; **corrected from decoded bounds after download** (see Gotchas — orientation) |
| everything else | unchanged (`takenAt`, `addedAt`, `contentHash`, `locationLabel`) |

Room migration: bump version; make `localPath` nullable. All existing rows are cached
files → keep them as `READY` (warm start, nothing re-downloads).

Rows are deleted **only** when the remote photo vanishes (MIRROR deletion policy) or the
source is removed — never by eviction.

### Index scale vs. the CursorWindow crash (do not skip this)

We already shipped a crash from this: observing the full photo set as fat entities blew
SQLite's 2 MB CursorWindow at ~1900 rows (fixed by the `PhotoRef` projection, see
`PhotoRef.kt`). At ~60 bytes/row, `PhotoRef` itself overflows somewhere around ~35k rows
— a 60k index **crosses that line again**.

Decision: **keep the in-memory `PlaylistEngine` but load the index in chunks.**

- Replace `observeRefsByStatus` as the playlist feed with: observe a cheap invalidation
  signal (e.g. `observeCount` on the photos table), then reload refs via **keyset-paged
  reads** (`WHERE (sourceId, remoteId) > last-key ORDER BY sourceId, remoteId LIMIT 5000`)
  off the main thread and call `engine.setPhotos(all)`.
- 60k `PlaylistPhoto`s in memory is ~10 MB — acceptable on the Fire HD, and it preserves
  all existing engine semantics (bag cycles, splice-new-near-front, history).
- Fallback if memory ever bites: a DB-backed bag (refill `remaining` from
  `ORDER BY COALESCE(lastShownAt,0), RANDOM() LIMIT 500`). Noted, not planned.

The playlist is fed **all indexed photos** (`INDEXED` + `READY`), not just cached ones —
that's the whole point.

## Metadata sync (replaces bulk download)

`SourceSyncer.sync()` today: list → diff → **download until cap** → report
`skippedForBudget`. It becomes `MetadataSyncer`:

1. `source.listPhotos()` — full remote listing (unchanged; failure → OFFLINE, index
   untouched, frame keeps playing).
2. Diff against the index (`SyncDiff` largely as-is):
   - **New remote photos** → insert `INDEXED` rows (no download). The existing Room flow
     fires → `PlaylistEngine.setPhotos` splices them near the front of the bag → the
     prefetcher fetches them → **new photos appear on the frame within one sync interval
     plus seconds.** (The old splice-near-front behavior now does exactly what the owner
     asked for, end to end.)
   - **Vanished photos** (MIRROR) → delete file + row.
   - **Changed `contentHash`** → keep row, delete stale file, status back to `INDEXED`
     (re-fetched on demand).
3. **Upsert must preserve** `lastShownAt`, `addedAt`, `localPath`, `sizeBytes`, `status`
   on existing rows — metadata refresh only updates remote-owned fields. (A blind
   `REPLACE` upsert would wipe the cache state; write an explicit
   insert-new/update-metadata pair in the DAO.)

Cadence: the existing WorkManager periodic job, same default 30 min, same quiet-hours
rule. A metadata pass is one paginated listing call — cheap enough to run more often if
30 min ever feels slow. Also run a pass on app start (foreground), so a frame that was
off overnight catches up immediately. `downloaded/skippedForBudget` fields and the
"Cache full — showing newest N" health state disappear; sync summary becomes
`indexed/removed/failed` and health messages report index count vs. cached count.

First-run: add a source → 60k rows insert in seconds → prefetcher pulls the first ~20 →
slideshow is playing within seconds, roaming the whole library. (Today: hours of
newest-first downloading, capped forever.)

## Prefetcher (new component)

A playback-side coroutine (owned by the app container, started/stopped with the
slideshow), **not** WorkManager — it has to track the playlist head with second-level
latency.

- **Invariant:** the next `HORIZON` photos in the playlist bag are `READY` or in-flight.
  `HORIZON = 20` (constant; not a setting unless it ever needs to be).
- Needs `PlaylistEngine.peek(n)` — return the front `n` of `remaining` without consuming
  (plus the forward-history slides if the user has been swiping back). Trivial to add;
  the engine already owns `remaining` as an `ArrayDeque`.
- Trigger on: slide advance, `setPhotos` (bag changed), order-mode change (new bag),
  prefetch completion.
- Download path reuses `source.fetch(photo, CacheSink)` exactly as `SourceSyncer.download`
  does today, including the decoded-bounds width/height correction, then flips the row to
  `READY` with `localPath`/`sizeBytes`. `RemotePhoto` is reconstructed from the index row
  (remoteId + contentHash + dims).
- Parallelism 3 (same as today's `DOWNLOAD_PARALLELISM`).
- **Failure = skip, with backoff.** A failed fetch marks the photo skipped in-memory
  (`failCount`/`nextAttemptAt`, not persisted) and moves on. When the network is down
  entirely, the prefetcher idles with exponential backoff (cap ~5 min) instead of
  hammering; playback continues from the retained pool untouched. Network problems never
  affect playback — they only pause pool turnover.

## Playback changes

- `advance()` resolves the next slide as today, but if a slide photo is not `READY`
  (or its file is missing on disk — SD card pulled), **skip to the next slide**, bounded
  (e.g. 50 attempts) to avoid spinning on an empty pool; missing-file rows get repaired
  to `INDEXED` so the prefetcher re-fetches. With the prefetcher holding the horizon,
  skips are rare in steady state.
- `markShown` only fires for photos actually displayed (unchanged) — `lastShownAt` stays
  honest as a show history.
- Pairing: `SlideBuilder` consumes from the bag as today. The horizon is counted in
  photos, which covers pairs (2 photos/slide worst case ⇒ ≥10 slides of runway).

## Eviction

`CacheEvictor` changes from "delete file + row" to **"delete file, keep row"**:

- Victims: `READY` rows, ascending `lastShownAt`, **excluding** the prefetch horizon and
  in-flight downloads (the evictor asks the prefetcher for its protected set).
- On evict: delete file, `localPath = null`, `sizeBytes = 0`, `status = INDEXED`.
  `lastShownAt` survives — that's the show-history requirement.
- The never-shown protection stays (a freshly prefetched photo awaiting its turn must not
  be evicted; in practice it's in the horizon set anyway).
- Runs after each sync pass (as today) and additionally after prefetch batches once the
  pool is near the cap, so the cap holds in steady state without waiting 30 min.

Cap: default drops **4 GB → 1 GB** (~4k photos retained ≈ days of offline variety).
Range `256 MB..32 GB` — on the SD card you can still crank it up and effectively hold the
whole library if you ever want mirror-like behavior back; nothing in the design forbids
it, the cap just no longer *matters* for depth.

## Storage location (SD card option — kept)

- `PhotoCache.kt:14` root becomes a resolved setting: `INTERNAL` (default) or
  `SD_CARD`. SD resolves via `context.getExternalFilesDirs(null)` → the entry that is
  removable (`Environment.isExternalStorageRemovable`) and mounted
  (`Environment.getExternalStorageState == MEDIA_MOUNTED`). App-specific dir: no runtime
  permission, wiped on uninstall — fine for a disposable buffer.
- **Switching location got dramatically cheaper under this design**: delete old files,
  null out `localPath` on all rows (one UPDATE), and the prefetcher repopulates in
  seconds. The index — including all show history — is untouched. The old
  "clear + full re-sync" decision is obsolete.
- **SD card pulled / unmounted:** resolver falls back to internal storage and logs;
  render-time missing-file handling (above) repairs stale SD paths to `INDEXED` as
  they're encountered, and a startup consistency sweep (walk rows where `status=READY`,
  verify the file exists) cleans up in bulk. The frame keeps playing off whatever it can
  reach — worst case it behaves like a fresh, small pool. **This kills the old failure
  mode where pulling the card meant losing collection reach**: reach lives in the index
  on internal storage, not on the card.

## Offline behavior (the one real trade-off)

Today a dead server/Wi-Fi costs nothing because everything is local. Under this design,
playback still never blocks on the network, but during an extended outage variety shrinks
to the retained pool (~4k photos at the 1 GB default — days of rotation on a frame).
Accepted trade-off for a LAN Immich setup; a bigger cap (or SD card + big cap) is the
knob if it ever matters.

## Settings & UI

- **New Storage screen** (`ui/settings/`, mirror `SyncSettingsScreen`): location picker
  (Internal / SD card, showing free space), cache cap slider, current usage
  ("N of M photos cached, X MB").
- **Sync screen:** interval stays (now "how often we check for new photos"); the cap
  moves to the Storage screen; deletion policy stays.
- **Diagnostics:** index count vs. cached count, prefetcher state (horizon readiness,
  last failure), per-source "M photos indexed".
- Source health: "OK — 60,412 photos indexed, 4,102 cached" replaces "Cache full —
  showing newest N".

## Gotchas / implementation notes

1. **Orientation & pairing dims.** Today dims come from decoding the downloaded preview
   (EXIF-oriented, so true display dims — see `SourceSyncer.decodeBounds`). Index rows
   initially carry remote-reported dims, which for some assets are unrotated. The
   prefetcher applies the same decode-bounds correction on download, *before* the photo
   becomes `READY` — but bag entries in `PlaylistEngine` are snapshots taken at
   `setPhotos` time, so a photo whose metadata dims were wrong can be mis-paired on its
   first-ever show. Fix in-engine: `setPhotos` already rebuilds `all`; also refresh dims
   on matching entries still in `remaining`. Cheap, and worth doing rather than accepting
   occasional wrong pairing.
2. **Upsert semantics.** The single `REPLACE` upsert is now dangerous (would clobber
   cache state on metadata refresh). Split DAO writes: `insertIgnore` for new index rows,
   `updateRemoteMetadata(...)` for existing, `markCached(...)` /`markEvicted(...)` for
   the file lifecycle. The prefetcher and evictor race on rows; both are row-scoped
   single-statement updates, so no transaction gymnastics needed beyond that.
3. **Google Photos shared-album source (future).** Its download URLs come from page
   parsing and go stale; under prefetch-on-demand, `fetch()` can run hours after
   `listPhotos()`. That source's `fetch` must re-resolve the URL (by remoteId) at fetch
   time. Immich fetch-by-id with an API key has no such issue. Log in `API_NOTES.md`
   when that source lands.
4. **`PhotoRepository.observeReadyPhotos`** is also used for `photoCount` in the UI —
   audit each consumer: playlist wants *indexed*, "photos" counters should probably show
   indexed (with cached as secondary), delete-photo flow wants the row regardless of
   status.
5. **Fixtures/debug** (`FixtureImporter`) import as `READY` local files with no source —
   unchanged, they just live in the index like everything else.

## Implementation phases

Each phase leaves the app shippable; acceptance on the physical Fire HD as usual.

**Phase S1 — Index schema + metadata sync + prefetcher (the core, lands together).**
Nullable `localPath` migration; `INDEXED` status; `MetadataSyncer` replaces bulk
download; chunked index load into the playlist; `peek()` + prefetcher; skip-unready
playback. *Accept:* fresh install with the real Immich source is playing within ~1 min
and the photo counter shows the full library size; photos older than the old 4 GB
horizon appear in the slideshow; airplane-mode playback keeps running on the pool.

**Phase S2 — Eviction + cap rework.** Evict-file-keep-row; horizon protection; 1 GB
default + range change; steady-state cap enforcement. *Accept:* disk usage plateaus at
the cap while the slideshow keeps surfacing never-before-cached photos; `lastShownAt`
non-null on evicted rows.

**Phase S3 — Storage screen + SD option.** Location setting + resolver + fallback;
switch-location flow (delete files, null paths, prefetcher refills); startup consistency
sweep. *Accept:* switch to SD → files land on the card and playback recovers in <1 min;
pull the card mid-slideshow → no crash, playback continues, sweep repairs on restart.

**Phase S4 — Cleanup.** Health/diagnostics strings, sync summary fields, DESIGN.md §6
amendment, delete dead code (`skippedForBudget`, cap-cutoff loop, old health state).

## Key files touched

- `data/db/PhotoEntity.kt`, `PhotoDao.kt`, `PhotoFrameDatabase.kt` (+migration), `PhotoRef.kt`
- `data/sync/SourceSyncer.kt` → `MetadataSyncer` (+ `SyncDiff` tweaks), `SyncManager.kt`
- **new** `data/sync/PhotoPrefetcher.kt`
- `data/sync/CacheEvictor.kt`
- `data/cache/PhotoCache.kt` (root resolution)
- `playlist/PlaylistEngine.kt` (`peek`, dim refresh)
- `ui/SlideshowViewModel.kt` (skip-unready, chunked feed)
- `settings/SyncSettings.kt` (+ new `StorageSettings` + repository)
- **new** `ui/settings/StorageSettingsScreen.kt`; `SettingsHomeScreen.kt`, `DiagnosticsScreen.kt`
- `AppContainer.kt` (wiring)

Startup playback now queries small keyset pages of READY rows and checks that the
local file exists and is nonempty before loading the complete library. The first
usable cached image appears immediately; it does not consume the engine’s pending
full-library slide. The full index and normal prefetch horizon initialize alongside
it. When a fresh cache has no usable files, the first successful prefetch triggers
the same startup selection rather than waiting for a different shuffled target.
Missing files are repaired to INDEXED for re-fetch. A fresh installation still needs
a successful source connection and one downloaded photo; the dashboard has its own
connection and can work while photo downloads fail.
