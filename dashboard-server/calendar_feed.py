"""Asynchronous, private-URL-safe iCalendar feed cache for the dashboard."""
from __future__ import annotations

from datetime import date, datetime, timedelta
import hashlib
import http.client
import ipaddress
import socket
import threading
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPSHandler, HTTPRedirectHandler, ProxyHandler, Request, build_opener
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

import recurring_ical_events
from icalendar import Calendar

FEED_TTL_SECONDS = 600
FETCH_TIMEOUT_SECONDS = 8
MAX_FEED_BYTES = 2 * 1024 * 1024
MAX_EVENTS = 500


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class _PinnedHTTPSConnection(http.client.HTTPSConnection):
    """Connect to a validated IP while retaining hostname-based TLS verification."""

    def __init__(self, host, *args, pinned_ip: str, **kwargs):
        self._pinned_ip = pinned_ip
        super().__init__(host, *args, **kwargs)
        # HTTPConnection.__init__ stores socket.create_connection on the instance,
        # which shadows a class method with this name unless we bind over it here.
        self._create_connection = self._connect_pinned

    def _connect_pinned(self, address, timeout, source_address):
        return socket.create_connection((self._pinned_ip, address[1]), timeout, source_address)


class _PinnedHTTPSHandler(HTTPSHandler):
    def __init__(self, pinned_ips: list[str]):
        super().__init__()
        self.pinned_ip = pinned_ips[0]

    def https_open(self, req):
        return self.do_open(
            lambda host, **kwargs: _PinnedHTTPSConnection(host, pinned_ip=self.pinned_ip, **kwargs),
            req,
            context=self._context,
        )


def is_valid_calendar_url(url: str) -> bool:
    """Cheap config check. Network resolution and public-address checks happen at fetch time."""
    if not isinstance(url, str) or not url or len(url) > 4096:
        return False
    try:
        parsed = urlsplit(url)
        if (parsed.scheme.lower() != "https" or not parsed.hostname or parsed.username is not None
                or parsed.password is not None or parsed.fragment):
            return False
        port = parsed.port
        return port is None or 1 <= port <= 65535
    except (ValueError, UnicodeError):
        return False


def _assert_public_host(host: str) -> list[str]:
    """Reject local names and return a small set of globally routable IPs."""
    if host.lower().rstrip(".") == "localhost":
        raise ValueError("Calendar host is not public")
    try:
        literal = ipaddress.ip_address(host)
        addresses = [literal]
    except ValueError:
        try:
            answers = socket.getaddrinfo(host, None, type=socket.SOCK_STREAM)
        except OSError as exc:
            raise ValueError("Calendar host could not be resolved") from exc
        addresses = [ipaddress.ip_address(answer[4][0].split("%", 1)[0]) for answer in answers]
    if not addresses or any(not address.is_global for address in addresses):
        raise ValueError("Calendar host is not public")
    return list(dict.fromkeys(str(address) for address in addresses))[:3]


def _fetch(url: str) -> bytes:
    if not is_valid_calendar_url(url):
        raise ValueError("Calendar URL must be a valid HTTPS URL")
    host = urlsplit(url).hostname
    pinned_ips = _assert_public_host(host)
    request = Request(url, headers={"User-Agent": "photo-frame-dashboard/1.0", "Accept": "text/calendar,*/*;q=0.5"})
    last_error = None
    for pinned_ip in pinned_ips:
        opener = build_opener(ProxyHandler({}), _NoRedirect, _PinnedHTTPSHandler([pinned_ip]))
        try:
            with opener.open(request, timeout=FETCH_TIMEOUT_SECONDS) as response:
                # Do not follow redirects: a public URL must not bounce to a private host.
                if response.geturl() != url:
                    raise ValueError("Calendar redirects are not supported")
                data = response.read(MAX_FEED_BYTES + 1)
            break
        except Exception as exc:
            last_error = exc
    else:
        if last_error is not None:
            raise last_error
        raise ValueError("Calendar host could not be resolved")
    if len(data) > MAX_FEED_BYTES:
        raise ValueError("Calendar feed is too large")
    return data


def _event_rows(raw: bytes, timezone_name: str, today: date) -> list[dict[str, str]]:
    try:
        school_tz = ZoneInfo(timezone_name)
    except (ZoneInfoNotFoundError, TypeError) as exc:
        raise ValueError("Invalid calendar timezone") from exc
    through = today + timedelta(days=45)
    calendar = Calendar.from_ical(raw)
    # Very dense rules can expand millions of items over 46 days. Omit those
    # individual source events before asking the recurrence library to expand.
    for component in list(calendar.subcomponents):
        rule = component.get("RRULE")
        frequency = str(rule.get("FREQ", [""])[0]).upper() if rule else ""
        if frequency in {"SECONDLY", "MINUTELY"}:
            calendar.subcomponents.remove(component)
    if len(calendar.subcomponents) > 10_000:
        raise ValueError("Calendar feed is too large")
    # Extend by one day because the library's stop boundary varies across versions;
    # output is clipped below to today..today+45, while long all-day events can overlap today.
    instances = recurring_ical_events.of(calendar).between(today, through + timedelta(days=1))
    rows: list[dict[str, str]] = []
    for event in instances:
        if str(event.get("STATUS", "")).upper() == "CANCELLED":
            continue
        start_prop = event.get("DTSTART")
        if start_prop is None:
            continue
        start = start_prop.dt
        if isinstance(start, datetime):
            local_start = start.replace(tzinfo=school_tz) if start.tzinfo is None else start.astimezone(school_tz)
            local_day = local_start.date()
            days = [local_day]
            local_time = local_start.strftime("%H:%M")
        elif isinstance(start, date):
            local_time = None
            end_prop = event.get("DTEND")
            end = end_prop.dt if end_prop is not None else start + timedelta(days=1)
            # RFC 5545 all-day DTEND is exclusive. A missing DTEND is one day.
            if isinstance(end, datetime):
                end = end.date()
            if not isinstance(end, date) or end <= start:
                end = start + timedelta(days=1)
            days = []
            current = max(start, today)
            last = min(end, through + timedelta(days=1))
            while current < last:
                days.append(current)
                current += timedelta(days=1)
        else:
            continue
        title = str(event.get("SUMMARY", "")).strip()[:240]
        if not title:
            title = "Untitled event"
        for day in days:
            if today <= day <= through:
                row = {"date": day.isoformat(), "title": title}
                if local_time is not None:
                    row["time"] = local_time
                description_prop = event.get("DESCRIPTION")
                if description_prop is not None:
                    description = str(description_prop)[:4000]
                    if description.strip():
                        row["description"] = description
                rows.append(row)
                if len(rows) >= MAX_EVENTS:
                    return sorted(rows, key=lambda row: (row["date"], row["title"]))
    return sorted(rows, key=lambda row: (row["date"], row["title"]))


class CalendarFeedCache:
    """Return cached events immediately and refresh public HTTPS feeds in daemon threads."""

    def __init__(self):
        self.lock = threading.Lock()
        self.entries: dict[str, dict] = {}

    @staticmethod
    def _key(url: str, timezone_name: str) -> str:
        return hashlib.sha256((url + "\0" + timezone_name).encode("utf-8")).hexdigest()

    def snapshot(self, url: str | None, timezone_name: str) -> dict:
        if not url or not str(url).strip():
            return {"events": [], "status": "disabled", "updated_at": None, "error": None}
        key = self._key(str(url), str(timezone_name))
        now = time.time()
        with self.lock:
            entry = self.entries.setdefault(key, {"events": None, "updated": None, "error": None,
                                                   "last_attempt": 0.0, "running": False})
            if not entry["running"] and now - entry["last_attempt"] >= FEED_TTL_SECONDS:
                entry["running"] = True
                entry["last_attempt"] = now
                threading.Thread(target=self._refresh, args=(key, str(url), str(timezone_name)), daemon=True).start()
            events = list(entry["events"] or [])
            updated = entry["updated"]
            if updated is not None:
                status = "stale" if entry["running"] or entry["error"] else "ready"
            elif entry["running"]:
                status = "loading"
            elif entry["error"]:
                status = "error"
            else:
                status = "loading"
            return {"events": events, "status": status,
                    "updated_at": datetime.fromtimestamp(updated).astimezone().isoformat(timespec="seconds") if updated else None,
                    "error": entry["error"]}

    def _refresh(self, key: str, url: str, timezone_name: str):
        events = None
        error = None
        try:
            raw = _fetch(url)
            events = _event_rows(raw, timezone_name, datetime.now(ZoneInfo(timezone_name)).date())
        except Exception as exc:
            # Deliberately map all exceptions to fixed text; URL tokens and server details stay private.
            message = str(exc)
            if "URL must" in message:
                error = "Calendar URL must use HTTPS"
            elif "public" in message or "resolved" in message:
                error = "Calendar host is not publicly reachable"
            elif "large" in message:
                error = "Calendar feed is too large"
            elif "timezone" in message.lower():
                error = "Calendar timezone is invalid"
            elif "redirect" in message.lower():
                error = "Calendar redirects are not supported"
            elif isinstance(exc, (HTTPError, URLError, TimeoutError, OSError)):
                error = "Calendar feed could not be fetched"
            else:
                error = "Calendar feed could not be parsed"
        with self.lock:
            entry = self.entries[key]
            if events is not None:
                entry["events"] = events
                entry["updated"] = time.time()
            entry["error"] = error
            entry["running"] = False
