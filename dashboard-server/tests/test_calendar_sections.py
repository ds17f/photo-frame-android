import json
from datetime import datetime
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


NOW = datetime(2026, 10, 19, 12, 0, tzinfo=ZoneInfo("America/Phoenix"))
TODAY = "2026-10-19"
TOMORROW = "2026-10-20"


def local_config():
    return {
        "school_timezone": "America/Phoenix",
        "school_dates": {TODAY: 2, TOMORROW: 3},
        "children": [{
            "name": "Avery",
            "days": {
                "2": {"activity": "PE", "sneakers": True},
                "3": {"activity": "Art", "sneakers": False},
            },
        }],
        "events": [{"date": TOMORROW, "title": "Family dinner"}],
    }


class RecordingCalendarCache:
    def __init__(self, snapshots):
        self.snapshots = snapshots
        self.calls = []

    def snapshot(self, url, timezone_name):
        self.calls.append((url, timezone_name))
        return self.snapshots[url]


class CalendarSectionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.lunch_dir = Path(self.temp.name) / "lunch"
        self.lunch_dir.mkdir()
        self.write_lunch_fixture()

    def write_lunch_fixture(self):
        menu = {
            "school": "Example Elementary",
            "month": "2026-10",
            "source_file": "fixture.pdf",
            "source_url": "https://example.test/menu",
            "days": {
                TODAY: {"status": "menu", "summary": "Crispy Chicken", "items": ["Crispy Chicken"]},
                TOMORROW: {"status": "menu", "summary": "Tacos", "items": ["Tacos"]},
            },
        }
        (self.lunch_dir / "2026-10.json").write_text(json.dumps(menu), encoding="utf-8")

    def build(self, config, cache=None):
        data = server.build_data(config, NOW)
        return server.add_calendar_data(data, config, cache or RecordingCalendarCache({}), self.lunch_dir)

    def test_local_specials_schedule_lunch_and_family_events_stay_in_separate_sections(self):
        cache = RecordingCalendarCache({})
        data = self.build(local_config(), cache)

        self.assertEqual(cache.calls, [])
        self.assertEqual(data["today"]["specials"]["source"], "local")
        self.assertEqual(data["today"]["specials"]["schedule"]["children"][0]["activity"], "PE")
        self.assertTrue(data["today"]["specials"]["schedule"]["children"][0]["sneakers"])
        self.assertEqual(data["today"]["lunch"]["source"], "local")
        self.assertEqual(data["today"]["lunch"]["status"], "menu")
        self.assertEqual(data["today"]["lunch"]["summary"], "Crispy Chicken")
        self.assertEqual(data["tomorrow"]["lunch"]["summary"], "Tacos")

        today_titles = [event["title"] for event in data["today"]["events"]]
        tomorrow_titles = [event["title"] for event in data["tomorrow"]["events"]]
        self.assertNotIn("Family dinner", today_titles)
        self.assertIn("Family dinner", tomorrow_titles)
        self.assertNotIn("Family dinner", str(data["today"]["lunch"]))
        self.assertNotIn("Family dinner", str(data["today"]["specials"]))

    def test_typed_feeds_keep_rows_and_independent_statuses_in_their_sections(self):
        config = local_config()
        config["calendar_feeds"] = {
            "specials": "https://feeds.example/specials-secret.ics",
            "lunch": "https://feeds.example/lunch-secret.ics",
            "events": "https://feeds.example/events-secret.ics",
        }
        feeds = {
            config["calendar_feeds"]["specials"]: {
                "status": "stale", "updated_at": "2026-10-19T10:00:00-07:00", "error": "refresh failed",
                "events": [{"date": TODAY, "title": "Library", "time": "09:15", "description": "Bring books"}],
            },
            config["calendar_feeds"]["lunch"]: {
                "status": "error", "updated_at": None, "error": "feed unavailable",
                "events": [{"date": TODAY, "title": "Imported lunch menu", "time": "12:00"}],
            },
            config["calendar_feeds"]["events"]: {
                "status": "ready", "updated_at": "2026-10-19T11:00:00-07:00", "error": None,
                "events": [{"date": TODAY, "title": "Family event", "time": "17:30"}],
            },
        }
        cache = RecordingCalendarCache(feeds)
        data = self.build(config, cache)

        self.assertEqual({url for url, _ in cache.calls}, set(config["calendar_feeds"].values()))
        self.assertEqual(data["today"]["specials"]["status"], "stale")
        self.assertEqual(data["today"]["specials"]["entries"], feeds[config["calendar_feeds"]["specials"]]["events"])
        self.assertEqual(data["today"]["specials"]["entries"][0]["time"], "09:15")
        self.assertEqual(data["today"]["specials"]["entries"][0]["description"], "Bring books")
        self.assertEqual(data["today"]["lunch"]["source"], "calendar")
        self.assertEqual(data["today"]["lunch"]["status"], "error")
        self.assertEqual(data["today"]["lunch"]["entries"], feeds[config["calendar_feeds"]["lunch"]]["events"])
        self.assertNotIn("Crispy Chicken", str(data["today"]["lunch"]))
        self.assertEqual([row["title"] for row in data["today"]["events"]], ["Family event"])
        serialized = json.dumps(data)
        for url in config["calendar_feeds"].values():
            self.assertNotIn(url, serialized)

    def test_legacy_calendar_url_is_events_fallback_only_when_events_key_is_absent(self):
        legacy = local_config()
        legacy_url = "https://feeds.example/legacy-secret.ics"
        legacy["calendar_url"] = legacy_url
        cache = RecordingCalendarCache({legacy_url: {
            "status": "ready", "updated_at": None, "error": None,
            "events": [{"date": TODAY, "title": "Legacy family event"}],
        }})
        data = self.build(legacy, cache)
        self.assertEqual([url for url, _ in cache.calls], [legacy_url])
        self.assertIn("Legacy family event", [row["title"] for row in data["today"]["events"]])
        self.assertEqual(data["today"]["specials"]["source"], "local")
        self.assertEqual(data["today"]["lunch"]["source"], "local")

        explicitly_disabled = dict(legacy)
        explicitly_disabled["calendar_feeds"] = {"events": ""}
        disabled_cache = RecordingCalendarCache({})
        disabled = self.build(explicitly_disabled, disabled_cache)
        self.assertEqual(disabled_cache.calls, [])
        self.assertNotIn("Legacy family event", [row["title"] for row in disabled["today"]["events"]])
        self.assertEqual(disabled["calendars"]["events"]["status"], "disabled")

    def test_public_lunch_ics_endpoint_serves_calendar_content(self):
        config_path = Path(self.temp.name) / "config.json"
        config_path.write_text(json.dumps(local_config()), encoding="utf-8")
        httpd = ThreadingHTTPServer(("127.0.0.1", 0), server.make_handler(config_path, server.WeatherCache()))
        thread = threading.Thread(target=httpd.serve_forever, daemon=True)
        thread.start()
        try:
            with patch.object(server, "LUNCH_DIRECTORY", self.lunch_dir):
                with urlopen(f"http://127.0.0.1:{httpd.server_port}/api/lunch-calendar.ics") as response:
                    body = response.read()
                    self.assertEqual(response.status, 200)
                    self.assertIn("text/calendar", response.headers.get("Content-Type", ""))
            calendar = Calendar.from_ical(body)
            events = [event for event in calendar.walk("VEVENT")]
            self.assertEqual({event.decoded("DTSTART").isoformat() for event in events}, {TODAY, TOMORROW})
            self.assertTrue(all(event.decoded("DTEND").isoformat() > event.decoded("DTSTART").isoformat() for event in events))
        finally:
            httpd.shutdown()
            httpd.server_close()
            thread.join(timeout=2)


if __name__ == "__main__":
    unittest.main()
