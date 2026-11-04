#!/usr/bin/env python3
"""Small standard-library dashboard server for a home photo frame."""
from __future__ import annotations

import argparse
from datetime import date, datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from zoneinfo import ZoneInfo
import json
import math
import os
import secrets
import threading
import time
import urllib.parse
import urllib.request
from config_store import ConfigStore, ConfigConflict, MAX_CONFIG_BYTES
from calendar_feed import CalendarFeedCache
from school_calendar import export_school_calendar
from lunch_calendar import lunch_for_day, export_lunch_calendar

ROOT = Path(__file__).resolve().parent
WEATHER_REFRESH_SECONDS = 600
LUNCH_DIRECTORY = Path(os.environ.get("LUNCH_CALENDAR_DIR", ROOT / "lunch-calendars"))
CALENDAR_KINDS = ("specials", "lunch", "events")


def load_config(path: Path) -> dict:
    with path.open(encoding="utf-8") as f:
        return json.load(f)


def _iso_date(value: str) -> date:
    return date.fromisoformat(value)


def schedule_for_day(config: dict, day: date) -> dict:
    """Resolve only explicitly mapped school dates; never infer a cycle day."""
    key = day.isoformat()
    school_dates = config.get("school_dates", {})
    cycle_day = school_dates.get(key)
    if cycle_day is None:
        return {"status": "unknown", "cycle_day": None, "children": []}
    if cycle_day == "no_school":
        return {"status": "no_school", "cycle_day": None, "children": []}
    if str(cycle_day) not in {"1", "2", "3", "4", "5", "6"}:
        return {"status": "unknown", "cycle_day": None, "children": []}
    children = []
    for child in config.get("children", []):
        entry = child.get("days", {}).get(str(cycle_day))
        if entry is None:
            children.append({"name": child.get("name", "Child"), "status": "unknown", "activity": None, "sneakers": None})
        else:
            children.append({"name": child.get("name", "Child"), "status": "known",
                             "activity": entry.get("activity", ""), "sneakers": entry.get("sneakers")})
    return {"status": "school", "cycle_day": int(cycle_day), "children": children}


def build_data(config: dict, now: datetime | None = None) -> dict:
    tz = ZoneInfo(config["school_timezone"])
    now = (now or datetime.now(tz)).astimezone(tz)
    today = now.date()
    tomorrow = today + timedelta(days=1)
    events = []
    for event in config.get("events", []):
        try:
            event_date = _iso_date(event["date"])
        except (KeyError, ValueError, TypeError):
            continue
        if today <= event_date <= today + timedelta(days=45):
            events.append({"date": event_date.isoformat(), "title": str(event.get("title", ""))})
    events.sort(key=lambda e: (e["date"], e["title"]))
    return {
        "generated_at": now.isoformat(timespec="seconds"),
        "timezone": config["school_timezone"],
        "today": {"date": today.isoformat(), "weekday": today.strftime("%A"), "schedule": schedule_for_day(config, today)},
        "tomorrow": {"date": tomorrow.isoformat(), "weekday": tomorrow.strftime("%A"), "schedule": schedule_for_day(config, tomorrow)},
        "events": events,
        "weather": None,
    }


def add_calendar_data(data: dict, config: dict, calendar_cache: CalendarFeedCache,
                      lunch_directory: Path = LUNCH_DIRECTORY) -> dict:
    """Keep specials, lunch, and other events separate, with local calendars by default."""
    configured = config.get("calendar_feeds", {})
    urls = {kind: configured.get(kind, "") for kind in CALENDAR_KINDS}
    if "events" not in configured:
        urls["events"] = config.get("calendar_url", "")
    disabled = {"events": [], "status": "disabled", "updated_at": None, "error": None}
    feeds = {kind: calendar_cache.snapshot(url, config["school_timezone"]) if url else dict(disabled)
             for kind, url in urls.items()}
    first_date = data["today"]["date"]
    last_date = (date.fromisoformat(first_date) + timedelta(days=45)).isoformat()

    def rows(events):
        merged = {}
        for event in events:
            if first_date <= event["date"] <= last_date:
                key = (event["date"], event.get("time", ""), event["title"])
                merged[key] = event
        return [merged[key] for key in sorted(merged)]

    for feed in feeds.values():
        feed["events"] = rows(feed["events"])
    data["events"] = rows(data["events"] + feeds["events"]["events"])
    # Only status is public; private subscription URLs never reach the display API.
    data["calendars"] = {kind: {key: feed[key] for key in ("status", "updated_at", "error")}
                         for kind, feed in feeds.items()}
    data["calendar"] = data["calendars"]["events"]  # old display clients
    for key in ("today", "tomorrow"):
        day = data[key]
        iso = day["date"]
        day["events"] = [event for event in data["events"] if event["date"] == iso]
        if urls["specials"]:
            feed = feeds["specials"]
            day["specials"] = {"source": "calendar", "status": feed["status"],
                               "entries": [event for event in feed["events"] if event["date"] == iso]}
        else:
            day["specials"] = {"source": "local", "status": day["schedule"]["status"],
                               "schedule": day["schedule"]}
        if urls["lunch"]:
            feed = feeds["lunch"]
            day["lunch"] = {"source": "calendar", "status": feed["status"],
                            "entries": [event for event in feed["events"] if event["date"] == iso]}
        else:
            day["lunch"] = {"source": "local", **lunch_for_day(lunch_directory, iso)}
    return data


class WeatherCache:
    def __init__(self):
        self.lock = threading.Lock()
        self.entries = {}

    @staticmethod
    def _key(latitude, longitude, location):
        return (float(latitude), float(longitude), str(location))

    def snapshot(self, latitude, longitude, location):
        with self.lock:
            entry = self.entries.get(self._key(latitude, longitude, location), {})
            updated = entry.get("updated")
            age = int(time.time() - updated) if updated else None
            result = dict(entry["data"]) if entry.get("data") else None
            error = entry.get("error")
            if result:
                result.update({"updated_at": datetime.fromtimestamp(updated).astimezone().isoformat(timespec="seconds"),
                               "age_seconds": age, "stale": age > WEATHER_REFRESH_SECONDS})
            return {"data": result, "error": error, "age_seconds": age}

    def request_refresh(self, latitude: float, longitude: float, location: str):
        key = self._key(latitude, longitude, location)
        with self.lock:
            now = time.time()
            entry = self.entries.setdefault(key, {"data": None, "updated": None, "error": None,
                                                   "last_attempt": 0.0, "running": False})
            if entry["running"] or now - entry["last_attempt"] < WEATHER_REFRESH_SECONDS:
                return
            entry["running"] = True
            entry["last_attempt"] = now
        threading.Thread(target=self._refresh, args=(key, latitude, longitude, location), daemon=True).start()

    def _refresh(self, key, latitude: float, longitude: float, location: str):
        try:
            params = urllib.parse.urlencode({
                "latitude": latitude, "longitude": longitude,
                "current": "temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m,is_day",
                "daily": "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max",
                "forecast_days": 5,
                "temperature_unit": "fahrenheit", "wind_speed_unit": "mph", "timezone": "auto",
            })
            req = urllib.request.Request("https://api.open-meteo.com/v1/forecast?" + params,
                                         headers={"User-Agent": "photo-frame-dashboard/1.0"})
            with urllib.request.urlopen(req, timeout=8) as response:
                raw = json.loads(response.read().decode("utf-8"))
            current = raw.get("current", {})
            if not isinstance(current, dict):
                raise ValueError("Weather response has no current conditions")
            temperature = _weather_number(current.get("temperature_2m"))
            if temperature is None:
                raise ValueError("Weather response has no valid current temperature")
            code = _weather_integer(current.get("weather_code"))
            result = {"location": location, "time": current.get("time"), "temperature_f": temperature,
                      "apparent_temperature_f": _weather_number(current.get("apparent_temperature")),
                      "humidity_percent": _weather_number(current.get("relative_humidity_2m")),
                      "precipitation_mm": _weather_number(current.get("precipitation")), "weather_code": code,
                      "is_day": current.get("is_day"), "description": WEATHER_LABELS.get(code, "Weather conditions"),
                      "wind_mph": _weather_number(current.get("wind_speed_10m")),
                      "high_f": None, "low_f": None, "today_precipitation_probability_max": None,
                      "forecast_timezone": raw.get("timezone"), "forecast": []}
            daily = raw.get("daily", {})
            if isinstance(daily, dict):
                dates = daily.get("time")
                dates = dates if isinstance(dates, list) else []
                highs, lows = daily.get("temperature_2m_max"), daily.get("temperature_2m_min")
                codes, probabilities = daily.get("weather_code"), daily.get("precipitation_probability_max")
                highs = highs if isinstance(highs, list) else []
                lows = lows if isinstance(lows, list) else []
                codes = codes if isinstance(codes, list) else []
                probabilities = probabilities if isinstance(probabilities, list) else []
                for index, day in enumerate(dates[:5]):
                    if not isinstance(day, str):
                        continue
                    try:
                        parsed_day = date.fromisoformat(day)
                    except ValueError:
                        continue
                    if parsed_day.isoformat() != day:
                        continue
                    day_code = _weather_integer(codes[index]) if index < len(codes) else None
                    day_high = _weather_number(highs[index]) if index < len(highs) else None
                    day_low = _weather_number(lows[index]) if index < len(lows) else None
                    probability = _weather_number(probabilities[index]) if index < len(probabilities) else None
                    result["forecast"].append({"date": day, "weather_code": day_code,
                        "description": WEATHER_LABELS.get(day_code, "Weather conditions"),
                        "high_f": day_high, "low_f": day_low,
                        "precipitation_probability_max": probability})
                if result["forecast"]:
                    result["high_f"] = result["forecast"][0]["high_f"]
                    result["low_f"] = result["forecast"][0]["low_f"]
                    result["today_precipitation_probability_max"] = result["forecast"][0]["precipitation_probability_max"]
            with self.lock:
                entry = self.entries[key]
                entry["data"], entry["updated"], entry["error"] = result, time.time(), None
        except Exception as exc:
            with self.lock:
                self.entries[key]["error"] = str(exc)[:240]
        finally:
            with self.lock:
                self.entries[key]["running"] = False


def _weather_number(value):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    if isinstance(value, float) and not math.isfinite(value):
        return None
    return value


def _weather_integer(value):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    if isinstance(value, float):
        if not math.isfinite(value) or not value.is_integer():
            return None
        return int(value)
    return value


WEATHER_LABELS = {0: "Clear", 1: "Mostly clear", 2: "Partly cloudy", 3: "Overcast", 45: "Fog", 48: "Rime fog",
                  51: "Light drizzle", 53: "Drizzle", 55: "Heavy drizzle", 56: "Light freezing drizzle", 57: "Heavy freezing drizzle",
                  61: "Light rain", 63: "Rain", 65: "Heavy rain", 66: "Light freezing rain", 67: "Heavy freezing rain",
                  71: "Light snow", 73: "Snow", 75: "Heavy snow", 77: "Snow grains",
                  80: "Rain showers", 81: "Rain showers", 82: "Heavy showers",
                  85: "Snow showers", 86: "Heavy snow showers",
                  95: "Thunderstorm", 96: "Thunderstorm with hail", 99: "Thunderstorm with hail"}


def make_handler(config_path: Path, weather_cache: WeatherCache):
    store = ConfigStore(config_path)
    calendar_cache = CalendarFeedCache()

    class Handler(BaseHTTPRequestHandler):
        def send_json(self, status, payload):
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("X-Frame-Options", "DENY")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_POST(self):
            if self.path != "/api/config":
                self.send_json(404, {"error": "Not found"})
                return
            # The proxy preserves Host and supplies the original protocol. Browser
            # POSTs must come from this site and carry the unguessable page token.
            scheme = self.headers.get("X-Forwarded-Proto", "http")
            expected_origin = f"{scheme}://{self.headers.get('Host', '')}"
            token = self.headers.get("X-CSRF-Token", "")
            if (self.headers.get("Origin") != expected_origin or
                    self.headers.get("Sec-Fetch-Site") == "cross-site" or
                    not secrets.compare_digest(token.encode("utf-8"), store.csrf_token.encode("utf-8"))):
                self.send_json(403, {"error": "Open the admin page on this site and reload before saving."})
                return
            if self.headers.get("Content-Type", "").split(";", 1)[0].strip().lower() != "application/json":
                self.send_json(415, {"error": "Send configuration as application/json"})
                return
            try:
                length = int(self.headers.get("Content-Length", "-1"))
            except ValueError:
                length = -1
            if length < 0 or self.headers.get("Transfer-Encoding"):
                self.send_json(400, {"error": "A valid Content-Length is required"})
                return
            if length > MAX_CONFIG_BYTES:
                self.send_json(413, {"error": "Configuration is too large (maximum 256 KiB)"})
                return
            try:
                self.connection.settimeout(15)
                raw = self.rfile.read(length)
                if len(raw) != length:
                    raise ValueError("Incomplete request body")
                payload = json.loads(raw)
                if not isinstance(payload, dict) or "config" not in payload:
                    raise ValueError("Expected an object containing config and revision")
                result = store.save(payload["config"], payload.get("revision"))
            except ConfigConflict as exc:
                self.send_json(409, {"error": str(exc)})
            except (ValueError, UnicodeError) as exc:
                self.send_json(400, {"error": str(exc)})
            except OSError:
                self.send_json(500, {"error": "Unable to save configuration. The previous configuration remains available; check the server's config directory permissions."})
            else:
                self.send_json(200, result)

        def do_GET(self):
            if self.path == "/api/lunch-calendar.ics":
                try:
                    body = export_lunch_calendar(LUNCH_DIRECTORY)
                except (OSError, ValueError, KeyError, TypeError):
                    self.send_json(500, {"error": "Lunch calendar cannot be exported. Check its source files."})
                    return
                self.send_response(200)
                self.send_header("Content-Type", "text/calendar; charset=utf-8")
                self.send_header("Content-Disposition", 'attachment; filename="lunch.ics"')
                self.send_header("Cache-Control", "no-store")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            if self.path == "/api/school-calendar.ics":
                try:
                    body = export_school_calendar(load_config(config_path))
                except (OSError, ValueError, KeyError, TypeError):
                    self.send_json(500, {"error": "School calendar cannot be exported. Check its configuration."})
                    return
                self.send_response(200)
                self.send_header("Content-Type", "text/calendar; charset=utf-8")
                self.send_header("Content-Disposition", 'attachment; filename="school-calendar.ics"')
                self.send_header("Cache-Control", "no-store")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            if self.path == "/api/config":
                try:
                    self.send_json(200, store.snapshot())
                except (OSError, ValueError):
                    self.send_json(500, {"error": "Configuration cannot be read. Repair the file on the server before editing."})
                return
            if self.path in ("/", "/index.html", "/admin", "/admin/"):
                try:
                    page = (ROOT / ("admin.html" if self.path.startswith("/admin") else "index.html")).read_bytes()
                except OSError:
                    self.send_error(500)
                    return
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Cache-Control", "no-store")
                self.send_header("X-Content-Type-Options", "nosniff")
                self.send_header("X-Frame-Options", "DENY")
                self.send_header("Content-Length", str(len(page)))
                self.end_headers()
                self.wfile.write(page)
                return
            if self.path != "/api/data":
                self.send_error(404)
                return
            try:
                config = load_config(config_path)
                data = build_data(config)
                data = add_calendar_data(data, config, calendar_cache)
                weather_cfg = config.get("weather", {})
                if weather_cfg.get("enabled", True) and weather_cfg.get("latitude") is not None and weather_cfg.get("longitude") is not None:
                    latitude, longitude = float(weather_cfg["latitude"]), float(weather_cfg["longitude"])
                    location = str(weather_cfg.get("location", "Weather"))
                    weather_cache.request_refresh(latitude, longitude, location)
                    snapshot = weather_cache.snapshot(latitude, longitude, location)
                    if snapshot["data"]:
                        weather = snapshot["data"]
                        weather["description"] = WEATHER_LABELS.get(weather.get("weather_code"), "Conditions unavailable")
                        weather["error"] = snapshot["error"]
                        data["weather"] = weather
                    else:
                        data["weather"] = {"status": "unavailable", "error": snapshot["error"] or "Loading weather"}
                body = json.dumps(data, ensure_ascii=False).encode("utf-8")
            except Exception as exc:
                body = json.dumps({"error": "Configuration unavailable", "detail": str(exc)[:200]}).encode()
                self.send_response(500)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, fmt, *args):
            print("%s - %s" % (self.address_string(), fmt % args))
    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=ROOT / "config.json")
    parser.add_argument("--host", default="127.0.0.1", help="Bind address (default localhost; use 0.0.0.0 only on a trusted private network)")
    parser.add_argument("--port", type=int, default=8080)
    args = parser.parse_args()
    server = ThreadingHTTPServer((args.host, args.port), make_handler(args.config, WeatherCache()))
    print(f"Dashboard listening on http://{args.host}:{args.port} using {args.config}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
