import copy
import unittest

from config_store import validate_config


BASE_CONFIG = {
    "school_timezone": "America/Phoenix",
    "school_dates": {},
    "children": [],
    "events": [],
}


class CalendarConfigTests(unittest.TestCase):
    def test_typed_feed_slots_accept_https_or_blank_and_legacy_url_remains_valid(self):
        config = copy.deepcopy(BASE_CONFIG)
        config["calendar_feeds"] = {
            "specials": "https://calendar.google.com/specials.ics?token=secret",
            "lunch": "",
            "events": "https://example.com/events.ics",
        }
        validate_config(config)

        legacy = {**BASE_CONFIG, "calendar_url": "https://example.com/legacy.ics"}
        self.assertIs(validate_config(legacy), legacy)
        validate_config(copy.deepcopy(BASE_CONFIG))

    def test_typed_feed_object_rejects_unknown_keys_and_non_string_values(self):
        for feeds in (
            [], {"other": "https://example.com/feed.ics"}, {"specials": None},
            {"lunch": True}, {"events": 42},
        ):
            with self.subTest(feeds=feeds):
                config = {**BASE_CONFIG, "calendar_feeds": feeds}
                with self.assertRaises(ValueError):
                    validate_config(config)

    def test_typed_and_legacy_feed_urls_reject_invalid_https_forms(self):
        invalid_urls = (
            "http://example.com/feed.ics", " https://example.com/feed.ics",
            "https://user:pass@example.com/feed.ics", "https://example.com/feed.ics#fragment",
            "https://example.com:bad/feed.ics", "https://example.com/" + "x" * 2048,
        )
        for url in invalid_urls:
            for key, value in (("calendar_url", url), ("calendar_feeds", {"events": url})):
                with self.subTest(key=key, url=url[:48]):
                    config = {**BASE_CONFIG, key: value}
                    with self.assertRaises(ValueError):
                        validate_config(config)

    def test_blank_legacy_url_disables_feed_but_malformed_legacy_type_is_rejected(self):
        validate_config({**BASE_CONFIG, "calendar_url": ""})
        for value in (None, False, 123):
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    validate_config({**BASE_CONFIG, "calendar_url": value})


if __name__ == "__main__":
    unittest.main()
