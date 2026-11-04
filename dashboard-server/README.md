# Family photo-frame dashboard

A small Python 3.10+ server for a transparent display and a browser settings editor.
It serves `/`, `/admin`, and same-origin APIs; the Android frame remains a passive display.

## Run

```sh
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
cp config.example.json config.json
python3 server.py --config config.json
```

Open `http://127.0.0.1:8080/`. The server binds to localhost by default. The built-in server uses plain HTTP, so use HTTPS for Android frame access too. On a local network, put it behind a trusted HTTPS reverse proxy or a private VPN endpoint with HTTPS. Bind with `--host 0.0.0.0` only when that network path is protected. A passive frame usually cannot complete an interactive login; if a proxy needs authentication, use a device-scoped, read-only access method that the frame can load. Avoid placing secrets in URLs, which can be retained in logs and history. Never expose this server directly to the public internet.

The page shows condition icons, current temperature, today’s high/low, feels-like temperature, wind, and a five-day forecast with daily highs/lows and precipitation chances. Clear conditions use a moon icon at night. It fetches weather from the official [Open-Meteo Forecast API](https://open-meteo.com/en/docs). Set `weather.latitude` and `weather.longitude` to the desired coordinates, or omit `weather` to disable weather. Coordinates in the sample are illustrative only. Weather calls run in the background, have an eight-second timeout, and are rate-limited to at most one attempt per ten minutes. Weather cache entries are scoped to the configured coordinates and location label, so changing the location cannot display the previous place’s conditions. The page shows the last successful update and marks weather stale after ten minutes; failures are surfaced alongside cached data.

## Configuration

Open **https://frame.example.lan/admin** on a phone or computer on the home
LAN/WireGuard. Edit settings and click **Save changes**. Saves validate the complete
configuration, back up the previous file under `config/backups/`, and atomically
replace it. A stale browser tab receives a conflict instead of overwriting newer edits.
The existing Caddy LAN/WireGuard restriction also covers the editor.

Google Calendar is not required. The default is three separate local sections on
both Today and Tomorrow: **Specials**, **Lunch**, and **Other events**. School dates,
each child's activities and sneaker rules, and family events are configured in
`/admin`. Lunch menus are JSON files in `lunch-calendars/`. They are household data,
so Git ignores them and Compose mounts the directory read-only into the container
(override the path with `LUNCH_CALENDAR_DIR`). They do not require an external
calendar subscription.
Lunch and Specials are hidden on Saturdays and Sundays, independently for each
displayed date in the school timezone. Other events remain visible on weekends.

Local iCalendar exports are available at `/api/school-calendar.ics` for school
closures/specials and `/api/lunch-calendar.ics` for the lunch menus. These can be
downloaded/imported elsewhere without making Google the source of truth.

If wanted, expand **Optional external calendar subscriptions** in `/admin` and paste
an HTTPS iCal URL for Specials, Lunch, or Family / other events. Each feed drives
only its matching section; disabling it restores the local source. Legacy
`calendar_url` settings remain supported as Family / other events only. Private
Google calendars use the **Secret address in iCal format** from Settings → Integrate
calendar; keep that URL private. Google cannot subscribe to this LAN-only server
itself, so a Google import uses a downloaded `.ics` file.

External subscriptions refresh every ten minutes, expand recurrences/exclusions,
and convert timed events to the school timezone. Full event descriptions are kept.
The display shows loading/failure/stale status and never exposes feed URLs. Only
direct public HTTPS feed URLs are supported; redirects and private-network hosts
are rejected. A failed external feed does not silently substitute a local calendar.

- `school_timezone` is an IANA timezone (for example, `America/Denver`). It defines the dashboard's today and tomorrow.
- `school_dates` explicitly maps each school date to `1`, `2`, `3`, `4`, `5`, `6`, or `"no_school"`. Dates absent from this map are **unknown**, including weekends, holidays, and dates beyond the configured calendar. There is no weekday or cycle-day inference. Add dates as the school calendar becomes known.
- Each child has a `days` mapping from cycle-day number to an `activity` and `sneakers` boolean. Missing date mappings or child day entries remain unknown. Unknown sneaker reminders never display as “no sneakers.”
- `events` contains manually entered `{ "date": "YYYY-MM-DD", "title": "..." }` entries. The page lists events from today through the next 45 days.
- `calendar_feeds` optionally maps `specials`, `lunch`, and/or `events` to HTTPS iCal URLs.
- `calendar_url` is the legacy Family / other events URL, used only when the new events slot is absent.
- `school_calendar_source` records the imported district PDF and first/last student dates.

The live configuration includes 42 weekday no-school dates from Chandler Unified's
2026–27 district calendar (holidays, teacher days and regular-school intersessions).
Early-release days remain school days. The exported `.ics` also includes first/last
student days. The household uses a **four-day** specials rotation, anchored by household confirmation
that October 20, 2026 is Day 3. Weekends and configured `no_school` dates do not
advance the rotation; early-release school days do. Dates are materialized through
May 20, 2027. The six-day PDF and `cusd-elementary-2026-27.json` are retained as
reference artifacts but do **not** define the household rotation.
District closures come from `school-calendars/chandler-2026-27.json`.

To regenerate after correcting closures or confirming a new anchor, run from this
directory (the previous configuration is backed up automatically):

```sh
python3 rotation_calendar.py --config config/config.json --anchor-date 2026-10-20 --anchor-day 3
```

This replaces the term's rotation dates while preserving closures, children, weather,
and events. It counts backward and forward from the confirmed anchor. New closures
require regeneration so subsequent cycle dates shift. The editor shows four days for
the household, using `school_calendar_source.rotation.cycle_days`.

Each lunch file covers one month and is transcribed from the school's published
menu. Its fields are `school`, `month`, `source_file`, `source_url`, and `days`, which
maps ISO dates to `{"status": "menu" | "no_school" | "unknown", "summary", "items",
"choices"}`. Dates without a published menu stay unknown and are not inferred as
closures. The local calendar is a snapshot, not an automatic monthly downloader: add
a file for each new month to the deployed `lunch-calendars/` directory. No rebuild
is needed. The source PDFs are also kept locally and ignored by Git.

The lunch ICS has one all-day entry for each menu and no-school date, with
stable identifiers and full menu descriptions. The frame shows all published entrée
choices in a dedicated Lunch section rather than mixing lunches into family events.
On shorter landscape displays, the distant Coming up card yields to the complete
Today/Tomorrow sections so the passive frame does not require scrolling.

The server reloads the JSON configuration for each API request. The browser refreshes data once per minute and reloads the page every 15 minutes. Configuration values are inserted into page elements as text, not HTML.

Run the tests with `python3 -m unittest discover -s tests` from this directory.

## Household deployment

The live display URL is **https://frame.example.lan/** on the home LAN or
WireGuard. The service directory on the home server is
`<services-dir>/photo-frame-dashboard`. Live settings are in
`config/config.json`; do not overwrite that directory when copying source updates.

The included Compose definition joins the existing external `caddy_network` and
publishes no host ports. Caddy terminates HTTPS using the household wildcard
certificate; Pi-hole resolves the dedicated name to the reverse proxy's LAN IP.

From `<services-dir>`, use `make photo-frame-dashboard-up` to rebuild
and start, `make photo-frame-dashboard-status` to check health, or
`make photo-frame-dashboard-logs` for logs. Configuration edits apply without a
restart; copy new `server.py`, `index.html`, and deployment files from this directory
and rebuild to apply code changes. The initial live configuration contains no
example children or weather coordinates. Only the configuration directory is writable;
the process runs as UID/GID 1000 with a read-only root filesystem. Back up `config/`,
including its school data and any private calendar subscription URL.

The tablet layout uses a compact weather header, dates beside Today/Tomorrow,
cycle-day labels beside Specials, and two-column lunch choices. Upcoming events
show at most two rows plus a remaining count; the card is hidden when empty or on
landscape viewports no taller than 700 CSS pixels. Daily entries retain their
activities, sneaker reminders, and every published entrée choice. Layout checks
cover usable viewports from 854×533 through 1920×1200, including Android density.
