import json
from pathlib import Path
import tempfile
import unittest

from icalendar import Calendar

import lunch_calendar


def write_month(directory: Path, month: str, days: dict) -> None:
    data = {"school": "Example Elementary", "month": month,
            "source_file": f"menu-{month}.pdf", "source_url": "https://example.test/menu",
            "days": days}
    (directory / f"{month}.json").write_text(json.dumps(data), encoding="utf-8")


class LunchCalendarTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.dir = Path(temp.name)
        write_month(self.dir, "2026-10", {
            "2026-10-05": {"status": "no_school", "summary": "No school lunch — Fall break", "items": []},
            "2026-10-19": {"status": "menu", "summary": "Crispy Chicken Sandwich; Cheese Pizza",
                           "items": ["Crispy Chicken Sandwich", "Cheese Pizza", "Baby Carrots"],
                           "choices": ["Crispy Chicken Sandwich", "Cheese Pizza"]},
        })
        write_month(self.dir, "2026-11", {
            "2026-11-04": {"status": "menu", "summary": "Pancakes", "items": ["Pancakes", "Maple Syrup"]},
            "2026-11-11": {"status": "unknown", "summary": "Menu information not provided", "items": []},
        })

    def test_months_are_merged_and_unknown_days_are_preserved(self):
        days = lunch_calendar.load_lunch_days(self.dir)
        self.assertEqual(days["2026-10-19"]["status"], "menu")
        self.assertEqual(days["2026-10-19"]["choices"], ["Crispy Chicken Sandwich", "Cheese Pizza"])
        self.assertEqual(days["2026-10-19"]["school"], "Example Elementary")
        self.assertIn("Maple Syrup", days["2026-11-04"]["items"])
        self.assertEqual(days["2026-11-11"]["status"], "unknown")
        self.assertEqual(lunch_calendar.lunch_for_day(self.dir, "2026-10-03")["status"], "unknown")

    def test_empty_directory_gives_unknown_days_and_empty_calendar(self):
        empty = self.dir / "empty"
        empty.mkdir()
        self.assertEqual(lunch_calendar.lunch_for_day(empty, "2026-10-19")["status"], "unknown")
        calendar = Calendar.from_ical(lunch_calendar.export_lunch_calendar(empty))
        self.assertEqual(list(calendar.walk("VEVENT")), [])
        self.assertEqual(str(calendar["X-WR-CALNAME"]), "School Lunch")

    def test_icalendar_has_known_dates_and_skips_unknown(self):
        calendar = Calendar.from_ical(lunch_calendar.export_lunch_calendar(self.dir))
        self.assertEqual(str(calendar["X-WR-CALNAME"]), "Example Elementary Lunch")
        by_day = {c.decoded("DTSTART").isoformat(): c for c in calendar.walk("VEVENT")}
        self.assertEqual(sorted(by_day), ["2026-10-05", "2026-10-19", "2026-11-04"])
        self.assertEqual(str(by_day["2026-10-19"]["SUMMARY"]), "Lunch: Crispy Chicken Sandwich; Cheese Pizza")
        self.assertIn("Baby Carrots", str(by_day["2026-10-19"]["DESCRIPTION"]))
        self.assertEqual(by_day["2026-10-19"].decoded("CATEGORIES")[0], "LUNCH")
        self.assertEqual(str(by_day["2026-10-05"]["SUMMARY"]), "No school lunch — Fall break")
        self.assertNotIn("2026-11-11", by_day)


if __name__ == "__main__":
    unittest.main()
