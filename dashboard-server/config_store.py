"""Validated, revision-checked configuration writes for the local admin UI."""
from __future__ import annotations

from datetime import date, datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import secrets
import tempfile
import threading
from urllib.parse import urlsplit
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

MAX_CONFIG_BYTES = 256 * 1024


class ConfigConflict(ValueError):
    pass


def _text(value, field, required=False, limit=300):
    if not isinstance(value, str) or len(value) > limit or (required and not value.strip()):
        raise ValueError(f"{field}: enter {'a non-empty' if required else 'a'} text value of at most {limit} characters")


def _date(value, field):
    try:
        if not isinstance(value, str) or date.fromisoformat(value).isoformat() != value:
            raise ValueError()
    except (ValueError, TypeError):
        raise ValueError(f"{field}: use a valid date in YYYY-MM-DD format") from None


def _calendar_url(value, field):
    _text(value, field, limit=2048)
    if not value:
        return
    try:
        parsed = urlsplit(value)
        if (value != value.strip() or parsed.scheme.lower() != "https" or not parsed.hostname or parsed.fragment
                or parsed.username is not None or parsed.password is not None
                or (parsed.port is not None and not 1 <= parsed.port <= 65535)):
            raise ValueError()
    except (ValueError, UnicodeError):
        raise ValueError(f"{field}: use a valid HTTPS iCal address without embedded credentials") from None


def validate_config(config):
    if not isinstance(config, dict):
        raise ValueError("Configuration must be a JSON object")
    tz = config.get("school_timezone")
    _text(tz, "School timezone", required=True, limit=100)
    try:
        ZoneInfo(tz)
    except (ZoneInfoNotFoundError, ValueError):
        raise ValueError("School timezone: use an IANA timezone such as America/Phoenix") from None
    dates = config.get("school_dates", {})
    if not isinstance(dates, dict) or len(dates) > 4000:
        raise ValueError("School dates must be an object with at most 4000 dates")
    for day, cycle in dates.items():
        _date(day, "School date")
        if not ((type(cycle) is int and 1 <= cycle <= 6) or cycle == "no_school"):
            raise ValueError(f"{day}: select Day 1–6 or No school")
    children = config.get("children", [])
    if not isinstance(children, list) or len(children) > 25:
        raise ValueError("Children must be a list with at most 25 entries")
    for i, child in enumerate(children, 1):
        if not isinstance(child, dict):
            raise ValueError(f"Child {i}: expected an object")
        _text(child.get("name"), f"Child {i} name", required=True, limit=100)
        days = child.get("days", {})
        if not isinstance(days, dict) or any(key not in ("1", "2", "3", "4", "5", "6") for key in days):
            raise ValueError(f"Child {i}: activity days must be 1–6")
        for day, activity in days.items():
            if not isinstance(activity, dict):
                raise ValueError(f"Child {i}, Day {day}: expected activity details")
            _text(activity.get("activity", ""), f"Child {i}, Day {day} activity")
            sneakers = activity.get("sneakers")
            if sneakers is not None and type(sneakers) is not bool:
                raise ValueError(f"Child {i}, Day {day}: sneaker reminder must be true, false, or null")
    events = config.get("events", [])
    if not isinstance(events, list) or len(events) > 1000:
        raise ValueError("Events must be a list with at most 1000 entries")
    for i, event in enumerate(events, 1):
        if not isinstance(event, dict):
            raise ValueError(f"Event {i}: expected an object")
        _date(event.get("date"), f"Event {i} date")
        _text(event.get("title"), f"Event {i} title", required=True)
    if "weather" in config:
        weather = config["weather"]
        if not isinstance(weather, dict):
            raise ValueError("Weather must be an object, or omit it to disable weather")
        for key, low, high in (("latitude", -90, 90), ("longitude", -180, 180)):
            value = weather.get(key)
            if type(value) not in (int, float) or not low <= value <= high or not math.isfinite(value):
                raise ValueError(f"Weather {key}: enter a number between {low} and {high}")
        _text(weather.get("location", ""), "Weather location", limit=100)
    if "calendar_url" in config:
        _calendar_url(config["calendar_url"], "Calendar URL")
    if "calendar_feeds" in config:
        feeds = config["calendar_feeds"]
        if not isinstance(feeds, dict) or any(key not in {"specials", "lunch", "events"} for key in feeds):
            raise ValueError("Calendar feeds must be an object with only specials, lunch, and events")
        for key, value in feeds.items():
            _calendar_url(value, f"Calendar feed {key}")
    if "school_calendar_source" in config:
        source = config["school_calendar_source"]
        if not isinstance(source, dict):
            raise ValueError("School calendar source must be an object")
        for key in ("name", "url", "first_day", "last_day"):
            _text(source.get(key), f"School calendar source {key}", required=True, limit=2048)
        _date(source["first_day"], "School calendar first day")
        _date(source["last_day"], "School calendar last day")
    return config


class ConfigStore:
    def __init__(self, path: Path):
        self.path = path
        self.lock = threading.Lock()
        self.csrf_token = secrets.token_urlsafe(32)

    @staticmethod
    def revision(raw):
        return hashlib.sha256(raw).hexdigest()

    def snapshot(self):
        with self.lock:
            raw = self.path.read_bytes()
            return {"config": json.loads(raw), "revision": self.revision(raw), "csrf_token": self.csrf_token}

    def save(self, config, revision):
        validate_config(config)
        raw = (json.dumps(config, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode("utf-8")
        if len(raw) > MAX_CONFIG_BYTES:
            raise ValueError("Configuration is too large (maximum 256 KiB)")
        with self.lock:
            previous = self.path.read_bytes()
            if not isinstance(revision, str) or self.revision(previous) != revision:
                raise ConfigConflict("Configuration changed since you opened it. Reload before saving to avoid overwriting someone else's changes.")
            # Write and sync the replacement before touching the active configuration.
            mode = self.path.stat().st_mode & 0o777
            temp_name = None
            try:
                with tempfile.NamedTemporaryFile(dir=self.path.parent, prefix=".config-", delete=False) as temp:
                    temp_name = temp.name
                    temp.write(raw)
                    temp.flush()
                    os.fchmod(temp.fileno(), mode)
                    os.fsync(temp.fileno())
                backups = self.path.parent / "backups"
                backups.mkdir(mode=0o750, exist_ok=True)
                stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
                backup = backups / f"config-{stamp}-{secrets.token_hex(4)}.json"
                with backup.open("xb") as f:
                    os.fchmod(f.fileno(), mode)
                    f.write(previous)
                    f.flush()
                    os.fsync(f.fileno())
                # Detect external editors that changed the file while the backup was written.
                if self.path.read_bytes() != previous:
                    raise ConfigConflict("Configuration changed while saving. Reload before trying again.")
                os.replace(temp_name, self.path)
                temp_name = None
            finally:
                if temp_name:
                    Path(temp_name).unlink(missing_ok=True)
            return {"config": config, "revision": self.revision(raw), "csrf_token": self.csrf_token}
