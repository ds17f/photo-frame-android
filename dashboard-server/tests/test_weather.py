import json
import math
import threading
import time
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs, urlparse

import server


class Response:
    def __init__(self, payload):
        self.payload = json.dumps(payload).encode()

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def read(self):
        return self.payload


class WeatherCacheTests(unittest.TestCase):
    def setUp(self):
        self.cache = server.WeatherCache()
        self.key = self.cache._key(33.3, -111.8, "Gilbert")
        self.cache.entries[self.key] = {"data": None, "updated": None, "error": None,
                                        "last_attempt": 0, "running": True}

    def refresh(self, payload):
        with patch.object(server.urllib.request, "urlopen", return_value=Response(payload)) as opener:
            self.cache._refresh(self.key, 33.3, -111.8, "Gilbert")
        return opener

    def test_refresh_requests_five_day_fahrenheit_forecast_and_aligns_daily_fields(self):
        payload = {
            "timezone": "America/Phoenix",
            "current": {"time": "2026-10-18T12:00", "temperature_2m": 78, "apparent_temperature": 77,
                        "relative_humidity_2m": 20, "precipitation": 0, "weather_code": 0,
                        "wind_speed_10m": 4, "is_day": 1},
            "daily": {"time": ["2026-10-18", "2026-10-19"], "weather_code": [0, 61],
                      "temperature_2m_max": [90, 80], "temperature_2m_min": [60, 55],
                      "precipitation_probability_max": [0, 40]},
        }
        opener = self.refresh(payload)
        query = parse_qs(urlparse(opener.call_args.args[0].full_url).query)
        self.assertEqual(query["daily"], ["weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"])
        self.assertEqual(query["forecast_days"], ["5"])
        self.assertIn("is_day", query["current"][0].split(","))
        self.assertEqual(query["temperature_unit"], ["fahrenheit"])
        self.assertEqual(query["wind_speed_unit"], ["mph"])
        self.assertEqual(query["timezone"], ["auto"])
        data = self.cache.snapshot(33.3, -111.8, "Gilbert")["data"]
        self.assertEqual(data["is_day"], 1)
        self.assertEqual(data["temperature_f"], 78)
        self.assertEqual(data["precipitation_mm"], 0)
        self.assertEqual(data["high_f"], 90)
        self.assertEqual(data["low_f"], 60)
        self.assertEqual(data["today_precipitation_probability_max"], 0)
        self.assertEqual(data["forecast_timezone"], "America/Phoenix")
        self.assertEqual(data["forecast"][1], {"date": "2026-10-19", "weather_code": 61, "description": "Light rain",
                                                "high_f": 80, "low_f": 55,
                                                "precipitation_probability_max": 40})

    def test_missing_short_and_invalid_daily_arrays_keep_nulls_and_zero_values(self):
        self.refresh({"current": {"temperature_2m": 0, "weather_code": 999, "is_day": 0},
                      "daily": {"time": ["2026-10-18", "2026-10-19"], "temperature_2m_max": [0, "bad"],
                                "temperature_2m_min": [None], "precipitation_probability_max": [0]}})
        data = self.cache.snapshot(33.3, -111.8, "Gilbert")["data"]
        self.assertEqual(data["temperature_f"], 0)
        self.assertEqual(data["description"], "Weather conditions")
        self.assertEqual(data["forecast"][0]["high_f"], 0)
        self.assertIsNone(data["forecast"][0]["low_f"])
        self.assertEqual(data["forecast"][0]["precipitation_probability_max"], 0)
        self.assertIsNone(data["forecast"][1]["high_f"])
        self.assertIsNone(data["forecast"][1]["weather_code"])
        self.assertEqual(server.WEATHER_LABELS[56], "Light freezing drizzle")
        self.assertEqual(server.WEATHER_LABELS[77], "Snow grains")
        self.assertEqual(server.WEATHER_LABELS[85], "Snow showers")

    def test_invalid_current_temperature_keeps_previous_snapshot_and_sets_error(self):
        old = {"location": "Gilbert", "temperature_f": 75}
        self.cache.entries[self.key] = {"data": old, "updated": time.time() - 12,
            "error": None, "last_attempt": 0, "running": False}
        self.refresh({"current": {"temperature_2m": None}})
        snapshot = self.cache.snapshot(33.3, -111.8, "Gilbert")
        self.assertEqual(snapshot["data"]["temperature_f"], 75)
        self.assertIn("valid current temperature", snapshot["error"])

    def test_cache_is_location_scoped_and_stale_data_survives_failure(self):
        old = {"location": "Gilbert", "temperature_f": 75}
        self.cache.entries[self.key] = {"data": old, "updated": time.time() - server.WEATHER_REFRESH_SECONDS - 3,
            "error": None, "last_attempt": 0, "running": False}
        self.refresh({"current": {"temperature_2m": "invalid"}})
        snapshot = self.cache.snapshot(33.3, -111.8, "Gilbert")
        self.assertEqual(snapshot["data"]["temperature_f"], 75)
        self.assertTrue(snapshot["data"]["stale"])
        self.assertIsNotNone(snapshot["error"])
        self.assertIsNone(self.cache.snapshot(33.3, -111.8, "Mesa")["data"])

    def test_nonfinite_weather_values_are_null_and_invalid_dates_are_skipped(self):
        self.refresh({"current": {"temperature_2m": 72, "apparent_temperature": math.nan,
                                   "relative_humidity_2m": math.inf, "weather_code": math.inf,
                                   "wind_speed_10m": -math.inf},
                      "daily": {"time": ["not-a-date", "2026-10-19", None, "2026-02-30"],
                                "weather_code": [0, 61, 63, 65],
                                "temperature_2m_max": [90, math.inf, 88, 86],
                                "temperature_2m_min": [60, 55, 54, 53],
                                "precipitation_probability_max": [0, math.nan, 30, 20]}})
        data = self.cache.snapshot(33.3, -111.8, "Gilbert")["data"]
        self.assertEqual(data["temperature_f"], 72)
        self.assertIsNone(data["apparent_temperature_f"])
        self.assertIsNone(data["humidity_percent"])
        self.assertIsNone(data["weather_code"])
        self.assertIsNone(data["wind_mph"])
        self.assertEqual(len(data["forecast"]), 1)
        self.assertEqual(data["forecast"][0]["date"], "2026-10-19")
        self.assertIsNone(data["forecast"][0]["high_f"])
        self.assertIsNone(data["forecast"][0]["precipitation_probability_max"])

    def test_refresh_scheduler_suppresses_recent_attempts_for_same_location(self):
        key = self.key
        self.cache.entries[key] = {"data": None, "updated": None, "error": None,
                                   "last_attempt": time.time(), "running": False}
        with patch.object(threading, "Thread") as thread:
            self.cache.request_refresh(33.3, -111.8, "Gilbert")
            thread.assert_not_called()


if __name__ == "__main__":
    unittest.main()
