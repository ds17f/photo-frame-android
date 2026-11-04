import copy
from datetime import date
import unittest

from rotation_calendar import four_day_calendar


class FourDayCalendarTests(unittest.TestCase):
    def setUp(self):
        self.config = {
            "school_calendar_source": {"first_day": "2026-10-19", "last_day": "2026-11-30"},
            "school_dates": {"2026-10-20": 5, "2026-11-11": "no_school",
                             "2026-11-25": "no_school", "2026-11-26": "no_school",
                             "2026-11-27": "no_school"},
            "children": [{"name": "Child", "days": {"3": {"activity": "Art", "sneakers": False}}}],
        }

    def test_anchor_wrap_weekend_holiday_and_break(self):
        before = copy.deepcopy(self.config)
        updated = four_day_calendar(self.config, date(2026, 10, 20), 3)
        days = updated["school_dates"]
        self.assertEqual([days[f"2026-10-{d}"] for d in (19, 20, 21, 22, 23, 26)], [2, 3, 4, 1, 2, 3])
        self.assertNotIn("2026-10-24", days)
        self.assertEqual(days["2026-11-11"], "no_school")
        self.assertEqual(days["2026-11-12"], days["2026-11-10"] % 4 + 1)
        self.assertEqual(days["2026-11-30"], days["2026-11-24"] % 4 + 1)
        self.assertEqual(updated["children"], before["children"])
        self.assertEqual(self.config, before)
        self.assertEqual(set(days.values()), {1, 2, 3, 4, "no_school"})

    def test_reject_closed_weekend_and_outside_term_anchors(self):
        for anchor in [date(2026, 11, 11), date(2026, 10, 24), date(2026, 12, 1)]:
            with self.subTest(anchor=anchor), self.assertRaises(ValueError):
                four_day_calendar(self.config, anchor, 3)

    def test_reject_invalid_cycle_day(self):
        for day in [0, 5, True]:
            with self.subTest(day=day), self.assertRaises(ValueError):
                four_day_calendar(self.config, date(2026, 10, 20), day)
