import json
from datetime import datetime, timedelta
from http.server import ThreadingHTTPServer
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch
from urllib.request import urlopen
from zoneinfo import ZoneInfo

from icalendar import Calendar
import server


class FixedCalendarCache:
    def __init__(self, snapshot):
        self.result = snapshot

    def snapshot(self, url, timezone_name):
        self.last_url = url
        self.last_timezone = timezone_name
        return self.result


class CalendarDisplayTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.config_path = Path(self.temp.name) / "config.json"
        today = datetime.now(ZoneInfo("America/Phoenix")).date()
        self.today = today
        self.tomorrow = today + timedelta(days=1)
        self.calendar_url = "https://calendar-secret.example/private-feed/token-abc.ics"
        self.config = {
            "school_timezone": "America/Phoenix",
            "calendar_url": self.calendar_url,
            "school_dates": {},
            "children": [],
            "events": [{"date": self.tomorrow.isoformat(), "title": "Manual event"}],
        }
        self.config_path.write_text(json.dumps(self.config), encoding="utf-8")
        self.feed_cache = FixedCalendarCache({
            "status": "ready",
            "updated_at": "2026-10-18T12:00:00-07:00",
            "error": None,
            "events": [
                {"date": today.isoformat(), "title": "Imported today"},
                {"date": self.tomorrow.isoformat(), "title": "Imported tomorrow"},
                {"date": self.tomorrow.isoformat(), "title": "Manual event"},
                {"date": (today - timedelta(days=1)).isoformat(), "title": "Stale event"},
                {"date": (today + timedelta(days=46)).isoformat(), "title": "Too far away"},
            ],
        })
        self.cache_patch = patch.object(server, "CalendarFeedCache", return_value=self.feed_cache)
        self.cache_patch.start()
        self.addCleanup(self.cache_patch.stop)
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), server.make_handler(self.config_path, server.WeatherCache()))
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self._stop_server)
        self.base = f"http://127.0.0.1:{self.httpd.server_port}"

    def _stop_server(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(timeout=2)
        self.temp.cleanup()

    def get(self, path):
        with urlopen(self.base + path) as response:
            return response.status, response.headers, response.read()

    def test_display_merges_current_and_tomorrow_events_without_exposing_feed_url(self):
        status, headers, body = self.get("/api/data")
        self.assertEqual(status, 200)
        self.assertEqual(headers.get("Cache-Control"), "no-store")
        payload = json.loads(body)

        self.assertEqual(payload["calendar"]["status"], "ready")
        serialized = body.decode("utf-8")
        self.assertNotIn(self.calendar_url, serialized)
        self.assertNotIn("token-abc", serialized)
        self.assertEqual(self.feed_cache.last_url, self.calendar_url)

        today_events = payload["today"]["events"]
        tomorrow_events = payload["tomorrow"]["events"]
        self.assertIn("Imported today", [event["title"] for event in today_events])
        self.assertIn("Imported tomorrow", [event["title"] for event in tomorrow_events])
        self.assertIn("Manual event", [event["title"] for event in tomorrow_events])

        rows = payload["events"]
        keys = [(event["date"], event["title"]) for event in rows]
        self.assertEqual(len(keys), len(set(keys)))
        self.assertNotIn((self.today.isoformat(), "Stale event"), keys)
        self.assertNotIn(((self.today + timedelta(days=46)).isoformat(), "Too far away"), keys)
        self.assertEqual(keys.count((self.tomorrow.isoformat(), "Manual event")), 1)

    def test_school_calendar_ics_contains_closures_with_stable_all_day_events(self):
        today = datetime.now(ZoneInfo("America/Phoenix")).date()
        tomorrow = today + timedelta(days=1)
        later = today + timedelta(days=2)
        self.config["school_dates"] = {
            today.isoformat(): "no_school",
            tomorrow.isoformat(): "no_school",
            later.isoformat(): 2,
        }
        self.config["children"] = [{
            "name": "Child",
            "days": {"2": {"activity": "PE", "sneakers": True}},
        }]
        self.config_path.write_text(json.dumps(self.config), encoding="utf-8")

        status, headers, first_bytes = self.get("/api/school-calendar.ics")
        self.assertEqual(status, 200)
        self.assertIn("text/calendar", headers.get("Content-Type", ""))
        self.assertNotIn(self.calendar_url.encode(), first_bytes)
        first = Calendar.from_ical(first_bytes)

        status, _, second_bytes = self.get("/api/school-calendar.ics")
        self.assertEqual(status, 200)
        second = Calendar.from_ical(second_bytes)

        first_events = [component for component in first.walk("VEVENT")]
        second_events = [component for component in second.walk("VEVENT")]
        closures = [event for event in first_events if str(event.get("SUMMARY", "")).lower().find("no school") >= 0]
        self.assertGreaterEqual(len(closures), 2)
        closure_starts = {event.decoded("DTSTART") for event in closures}
        self.assertTrue({today, tomorrow}.issubset(closure_starts))

        first_by_start = {event.decoded("DTSTART"): event for event in first_events}
        second_by_start = {event.decoded("DTSTART"): event for event in second_events}
        for day in (today, tomorrow):
            event = first_by_start[day]
            self.assertEqual(event.decoded("DTEND"), day + timedelta(days=1))
        self.assertEqual(
            {event.get("UID") for event in first_events},
            {event.get("UID") for event in second_events},
        )


if __name__ == "__main__":
    unittest.main()
