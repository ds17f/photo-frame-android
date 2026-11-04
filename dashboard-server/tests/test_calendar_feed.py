import threading
import time
import unittest
from datetime import date, timedelta
from unittest.mock import Mock, patch

import calendar_feed


class CalendarParsingTests(unittest.TestCase):
    def test_recurrence_exdate_override_all_day_and_local_timed_date(self):
        raw = b'''BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Photo Frame//EN\r\nBEGIN:VEVENT\r\nUID:weekly\r\nDTSTART;TZID=America/New_York:20260301T090000\r\nDTEND;TZID=America/New_York:20260301T100000\r\nRRULE:FREQ=DAILY;COUNT=4\r\nEXDATE;TZID=America/New_York:20260302T090000\r\nSUMMARY:Practice\r\nEND:VEVENT\r\nBEGIN:VEVENT\r\nUID:weekly\r\nRECURRENCE-ID;TZID=America/New_York:20260303T090000\r\nDTSTART;TZID=America/New_York:20260304T110000\r\nDTEND;TZID=America/New_York:20260304T120000\r\nSUMMARY:Moved practice\r\nEND:VEVENT\r\nBEGIN:VEVENT\r\nUID:all-day\r\nDTSTART;VALUE=DATE:20260302\r\nDTEND;VALUE=DATE:20260305\r\nSUMMARY:Trip\r\nEND:VEVENT\r\nBEGIN:VEVENT\r\nUID:utc\r\nDTSTART:20260305T013000Z\r\nDTEND:20260305T023000Z\r\nSUMMARY:Late meeting\r\nDESCRIPTION:Menu\\nPancakes\\, fruit\r\nEND:VEVENT\r\nBEGIN:VEVENT\r\nUID:cancelled\r\nDTSTART;VALUE=DATE:20260303\r\nDTEND;VALUE=DATE:20260304\r\nSTATUS:CANCELLED\r\nSUMMARY:Cancelled\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n'''
        rows = calendar_feed._event_rows(raw, "America/Phoenix", date(2026, 3, 1))
        self.assertEqual(rows, [
            {"date": "2026-03-01", "title": "Practice", "time": "07:00"},
            {"date": "2026-03-02", "title": "Trip"},
            {"date": "2026-03-03", "title": "Trip"},
            {"date": "2026-03-04", "title": "Late meeting", "time": "18:30", "description": "Menu\nPancakes, fruit"},
            {"date": "2026-03-04", "title": "Moved practice", "time": "09:00"},
            {"date": "2026-03-04", "title": "Practice", "time": "07:00"},
            {"date": "2026-03-04", "title": "Trip"},
        ])

    def test_window_includes_day_45_and_clips_an_overlapping_multiday_event(self):
        today = date(2026, 3, 1)
        through = today + timedelta(days=45)
        previous = today - timedelta(days=2)
        after = today + timedelta(days=1)
        after_window = through + timedelta(days=1)
        raw = (
            "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n"
            "BEGIN:VEVENT\r\nUID:overlap\r\nDTSTART;VALUE=DATE:{}\r\nDTEND;VALUE=DATE:{}\r\nSUMMARY:Long event\r\nEND:VEVENT\r\n"
            "BEGIN:VEVENT\r\nUID:last-day\r\nDTSTART;VALUE=DATE:{}\r\nDTEND;VALUE=DATE:{}\r\nSUMMARY:Last day\r\nEND:VEVENT\r\n"
            "END:VCALENDAR\r\n"
        ).format(previous.strftime("%Y%m%d"), after.strftime("%Y%m%d"),
                 through.strftime("%Y%m%d"), after_window.strftime("%Y%m%d")).encode()
        rows = calendar_feed._event_rows(raw, "America/Phoenix", today)
        self.assertEqual(rows, [
            {"date": today.isoformat(), "title": "Long event"},
            {"date": through.isoformat(), "title": "Last day"},
        ])


class CalendarUrlTests(unittest.TestCase):
    def test_url_validation_rejects_non_https_credentials_fragments_and_bad_ports(self):
        for url in (
            "http://calendar.google.com/feed.ics", "https://user:pass@example.com/feed.ics",
            "https://example.com/feed.ics#fragment", "https://example.com:bad/feed.ics",
            "https:///no-host",
        ):
            with self.subTest(url=url):
                self.assertFalse(calendar_feed.is_valid_calendar_url(url))
        self.assertTrue(calendar_feed.is_valid_calendar_url("https://calendar.google.com/calendar/ical/private/basic.ics"))

    def test_private_dns_answer_is_rejected(self):
        answer = (None, None, None, None, ("192.168.1.4", 0))
        with patch.object(calendar_feed.socket, "getaddrinfo", return_value=[answer]):
            with self.assertRaisesRegex(ValueError, "not public"):
                calendar_feed._assert_public_host("calendar.example")

    def test_fetch_rejects_private_ip_before_opening_connection(self):
        with patch.object(calendar_feed, "build_opener", side_effect=AssertionError("must not connect")):
            with self.assertRaisesRegex(ValueError, "not public"):
                calendar_feed._fetch("https://127.0.0.1/private.ics")

    def test_pinned_connection_connects_to_validated_ip(self):
        connection = calendar_feed._PinnedHTTPSConnection(
            "calendar.example:443", pinned_ip="203.0.113.9", timeout=8,
        )
        socket_mock = Mock(return_value=object())
        with patch.object(calendar_feed.socket, "create_connection", socket_mock):
            connection._create_connection(("calendar.example", 443), 8, None)
        socket_mock.assert_called_once_with(("203.0.113.9", 443), 8, None)


class CalendarCacheTests(unittest.TestCase):
    def test_disabled_snapshot_never_fetches(self):
        cache = calendar_feed.CalendarFeedCache()
        with patch.object(calendar_feed, "_fetch", side_effect=AssertionError("must not fetch")):
            self.assertEqual(cache.snapshot("", "America/Phoenix"), {
                "events": [], "status": "disabled", "updated_at": None, "error": None,
            })

    def test_fetch_is_background_and_errors_never_echo_secret_url(self):
        cache = calendar_feed.CalendarFeedCache()
        finished = threading.Event()
        secret_url = "https://example.com/private-token-abc/calendar.ics?key=SECRET"

        def fail(_url):
            time.sleep(0.03)
            finished.set()
            raise RuntimeError("request failed for " + secret_url)

        with patch.object(calendar_feed, "_assert_public_host"), patch.object(calendar_feed, "_fetch", side_effect=fail):
            first = cache.snapshot(secret_url, "America/Phoenix")
            self.assertEqual(first["status"], "loading")
            self.assertNotIn("SECRET", str(first))
            self.assertTrue(finished.wait(1))
            deadline = time.monotonic() + 1
            while time.monotonic() < deadline:
                result = cache.snapshot(secret_url, "America/Phoenix")
                if result["status"] == "error":
                    break
                time.sleep(0.01)
            self.assertEqual(result["status"], "error")
            self.assertNotIn("SECRET", str(result))
            self.assertNotIn("private-token", str(result))
            self.assertEqual(result["error"], "Calendar feed could not be parsed")

    def test_cached_events_are_stale_after_refresh_failure(self):
        cache = calendar_feed.CalendarFeedCache()
        url = "https://example.com/feed.ics"
        key = cache._key(url, "America/Phoenix")
        cache.entries[key] = {"events": [{"date": "2026-03-01", "title": "Cached"}],
                              "updated": time.time() - 700, "error": None,
                              "last_attempt": 0, "running": False}
        finished = threading.Event()
        def fail(_url):
            finished.set()
            raise OSError("private transport details")
        with patch.object(calendar_feed, "_assert_public_host"), patch.object(calendar_feed, "_fetch", side_effect=fail):
            result = cache.snapshot(url, "America/Phoenix")
            self.assertEqual(result["status"], "stale")
            self.assertEqual(result["events"][0]["title"], "Cached")
            self.assertTrue(finished.wait(1))
            deadline = time.monotonic() + 1
            while time.monotonic() < deadline:
                result = cache.snapshot(url, "America/Phoenix")
                if result["error"]:
                    break
                time.sleep(0.01)
            self.assertEqual(result["status"], "stale")
            self.assertEqual(result["error"], "Calendar feed could not be fetched")


if __name__ == "__main__":
    unittest.main()
