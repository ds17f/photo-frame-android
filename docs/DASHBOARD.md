# Desktop dashboard

The Android app displays a remote page over its locally cached slideshow. The server
owns weather, school-day rules, calendar data, and layout; the app owns gestures,
photo dimming, controls, and quiet hours. Changing the server does not require an APK.

## Frame setup

Open **Settings → Display → Desktop dashboard**. Enter the full HTTPS dashboard URL
and choose **On tap** or **Always on**. The default is **Off**. Adjust photo dimming
from 0–90% to suit the page's text and background. Valid URLs save automatically;
Done, focus loss, and Back also commit immediately. Invalid drafts keep the saved URL.

On tap opens the dashboard with the playback controls. Another tap or Back closes
it. Remote Select has the same behavior; swipes/Left/Right continue changing photos.
Controls disappear after their usual timeout; an opened dashboard stays until closed.
In Always on mode, taps show the normal controls without hiding the dashboard.

The dashboard is read-only on the frame. Links, forms, and sign-in pages cannot be
operated there. Serve a dedicated display page that is already accessible from the
device, for example over a private network with a valid HTTPS certificate. Use your
reverse proxy for access controls if needed. Embedded URL usernames/passwords are
rejected. Plain HTTP is not supported, including on a LAN.

## Companion server

See [dashboard-server/README.md](../dashboard-server/README.md) for commands and the
sample configuration. It is a small Python server with iCalendar parsing support and can run behind an HTTPS
reverse proxy on a small remote machine.

The household deployment is **https://frame.example.lan/**, available on the
home LAN and WireGuard. It runs on the home server under
`<services-dir>/photo-frame-dashboard`. Caddy uses the existing
wildcard certificate; Pi-hole maps the hostname to the reverse proxy's LAN IP.

Open **https://frame.example.lan/admin** on a phone or computer to configure
the server. The editor saves validated settings, backs up the previous file, and
rejects stale-tab overwrites. Its page is subject to the same LAN/WireGuard restriction
as the display. No APK update is needed for server configuration.

Live configuration is `config/config.json` inside that service directory. It starts
with the household server's `America/Phoenix` timezone and empty school/event data;
weather stays disabled until real coordinates are supplied. Chandler Unified's
2026–27 weekday closures have been imported from the provided district PDF. The configuration
directory is private deployment data and is ignored by Git. To rebuild/start after
copying updated server source, run `make photo-frame-dashboard-up` from
`<services-dir>`. The backend publishes no host port.

The sample contains fictional children, coordinates, dates, and events. Replace them
before use. Weather comes from [Open-Meteo](https://open-meteo.com/en/docs). The header
shows current condition icons, temperature, today’s high/low, feels-like temperature
and wind, plus a five-day forecast with icons, daily highs/lows and precipitation
chances. The weather location’s timezone determines forecast dates. Weather refreshes
every ten minutes; failed refreshes retain the previous data with a warning.

Google is optional. Today and Tomorrow each have dedicated **Specials**, **Lunch**,
and **Other events** sections, backed by local data on the homeserver by default.
Lunch and Specials are hidden for weekend dates in the school timezone; Other
events remain visible. A Sunday dashboard still shows Monday's school sections.
School dates map to Day 1–6 or `no_school`; activities/sneaker rules are per child.
The household uses a four-day rotation anchored to **Tuesday October 20, 2026 = Day 3**,
confirmed by the household. Wednesday is Day 4 and Thursday is Day 1. Weekends,
holidays, intersessions, and other configured no-school dates do not advance the
rotation. Early-release school days count. Explicit dates are generated through
May 20, 2027 with `dashboard-server/rotation_calendar.py`; rerun it after changing
closures. The six-day district block PDF was incorrectly applied previously and
is retained only as a reference artifact, not the household rotation source.

Lunch menus are transcribed from the school's monthly PDFs into JSON files in the
server's `lunch-calendars/` directory. These files and PDFs are household data and
are not in Git; Compose mounts the directory read-only. Missing menu information
remains unknown. Full menu descriptions are available in the lunch `.ics`; the frame
shows the published entrée choices. These are monthly publication snapshots, so
future months need to be added rather than being inferred from weekday patterns.

The local school and lunch calendar downloads are available in `/admin` at
`/api/school-calendar.ics` and `/api/lunch-calendar.ics`. They can be exported to other
calendar apps; exporting does not change the local source of truth.

For optional subscriptions, expand the external-calendar section in `/admin`.
Separate iCal URLs can drive Specials, Lunch, or Family / other events, with a
ten-minute refresh and recurrence/exception handling. Each slot stays separate;
private feed URLs never reach the display API. Clearing a URL restores its local
calendar. A subscription is read-only, so externally hosted events are edited in
their original calendar service.

## Page contract and recovery

Any HTTPS page can be used; there is no Android-specific JSON schema or JavaScript
bridge. Use `html, body { background: transparent; }`, responsive sizing, and text
that is readable over photographs. Keep audio, video, and interactive widgets out of
the display page. Main-frame navigation stays on the configured HTTPS origin.

The companion page refreshes data every minute and marks failures visibly. The app
reloads the full page every 15 minutes. Failed or timed-out page loads restore normal
photos and local overlays and retry after a minute; WebView renderer failures also
retry without stopping playback. Data freshness within a successfully loaded page
is the server/page's responsibility. Quiet hours and leaving the slideshow release
the WebView. Backgrounding the app pauses it.

Verify on a Fire tablet: Off mode, tap-open/close, Back, Always on, dimming, photo
swipes/long-press, remote controls, settings access, server outage/recovery, overnight
date change, and quiet hours. URL persistence and dashboard rendering were verified
in Waydroid; final Fire tablet verification remains.

The tablet layout uses a compact weather header, dates beside Today/Tomorrow,
cycle-day labels beside Specials, and two-column lunch choices. Upcoming events
show at most two rows plus a remaining count; the card is hidden when empty or on
landscape viewports no taller than 700 CSS pixels. Daily entries retain their
activities, sneaker reminders, and every published entrée choice. Layout checks
cover usable viewports from 854×533 through 1920×1200, including Android density.
