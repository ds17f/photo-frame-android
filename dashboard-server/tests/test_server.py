import json
from datetime import date, datetime, timezone
from http.server import ThreadingHTTPServer
from pathlib import Path
import tempfile
import threading
import unittest
from urllib.request import urlopen
from zoneinfo import ZoneInfo

import server

CONFIG = {
    "school_timezone": "America/Phoenix",
    "school_dates": {"2026-10-19": 1, "2026-10-20": 2, "2026-10-21": "no_school", "2027-01-04": 4},
    "children": [
        {"name": "A", "days": {"1": {"activity": "PE", "sneakers": True}, "2": {"activity": "Read", "sneakers": False}}},
        {"name": "B", "days": {"1": {"activity": "Art", "sneakers": None}, "2": {"activity": "PE", "sneakers": True}}},
    ],
    "events": [{"date": "2026-10-19", "title": "Meeting"}, {"date": "2026-10-17", "title": "Past"}],
}

class ScheduleTests(unittest.TestCase):
    def test_explicit_cycle_mapping_and_per_child_activities(self):
        result = server.schedule_for_day(CONFIG, date(2026, 10, 19))
        self.assertEqual((result["status"], result["cycle_day"]), ("school", 1))
        self.assertEqual([c["activity"] for c in result["children"]], ["PE", "Art"])
        self.assertEqual([c["sneakers"] for c in result["children"]], [True, None])

    def test_unmapped_weekend_holiday_and_future_year_stay_unknown(self):
        for day in (date(2026, 10, 17), date(2026, 12, 25), date(2028, 1, 1)):
            with self.subTest(day=day):
                self.assertEqual(server.schedule_for_day(CONFIG, day)["status"], "unknown")

    def test_explicit_no_school_is_distinct_from_unknown(self):
        result = server.schedule_for_day(CONFIG, date(2026, 10, 21))
        self.assertEqual(result["status"], "no_school")
        self.assertEqual(result["children"], [])

    def test_missing_child_cycle_entry_stays_unknown(self):
        config = {"school_dates": {"2026-10-19": 3}, "children": [{"name": "A", "days": {}}]}
        child = server.schedule_for_day(config, date(2026, 10, 19))["children"][0]
        self.assertEqual(child["status"], "unknown")
        self.assertIsNone(child["sneakers"])

    def test_timezone_conversion_controls_today_and_tomorrow(self):
        result = server.build_data(CONFIG, datetime(2026, 10, 20, 6, 30, tzinfo=timezone.utc))
        self.assertEqual(result["today"]["date"], "2026-10-19")
        self.assertEqual(result["tomorrow"]["date"], "2026-10-20")
        self.assertEqual(result["today"]["weekday"], "Monday")

    def test_year_rollover_and_events_are_date_sorted(self):
        config = {**CONFIG, "events": [{"date": "2027-01-05", "title": "Later"}, {"date": "2027-01-04", "title": "Soon"}]}
        result = server.build_data(config, datetime(2027, 1, 3, 12, tzinfo=timezone.utc))
        self.assertEqual(result["tomorrow"]["schedule"]["cycle_day"], 4)
        self.assertEqual([event["title"] for event in result["events"]], ["Soon", "Later"])

    def test_weather_cache_is_scoped_to_configured_location(self):
        cache = server.WeatherCache()
        key = cache._key(10, 20, "City A")
        cache.entries[key] = {"data": {"location": "City A"}, "updated": 1234567890,
                              "error": None, "last_attempt": 0, "running": False}
        self.assertEqual(cache.snapshot(10, 20, "City A")["data"]["location"], "City A")
        self.assertIsNone(cache.snapshot(30, 40, "City B")["data"])

class HttpTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.config_path = Path(self.temp.name) / "config.json"
        self.config_path.write_text(json.dumps({**CONFIG, "school_dates": {}}), encoding="utf-8")
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), server.make_handler(self.config_path, server.WeatherCache()))
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()
        self.base = f"http://127.0.0.1:{self.httpd.server_port}"

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(timeout=2)
        self.temp.cleanup()

    def test_page_and_same_origin_json_endpoint(self):
        with urlopen(self.base + "/") as response:
            self.assertIn("text/html", response.headers["Content-Type"])
            self.assertIn("textContent", response.read().decode())
            self.assertEqual(response.headers["Cache-Control"], "no-store")
        with urlopen(self.base + "/api/data") as response:
            payload = json.loads(response.read())
            self.assertEqual(payload["today"]["schedule"]["status"], "unknown")
            self.assertEqual(response.headers["Cache-Control"], "no-store")

    def test_config_is_reloaded_on_each_request(self):
        with urlopen(self.base + "/api/data") as response:
            self.assertEqual(json.loads(response.read())["today"]["schedule"]["status"], "unknown")
        today = datetime.now(ZoneInfo(CONFIG["school_timezone"])).date().isoformat()
        changed = {**CONFIG, "school_dates": {today: "no_school"}}
        self.config_path.write_text(json.dumps(changed), encoding="utf-8")
        with urlopen(self.base + "/api/data") as response:
            self.assertEqual(json.loads(response.read())["today"]["schedule"]["status"], "no_school")

if __name__ == "__main__":
    unittest.main()
