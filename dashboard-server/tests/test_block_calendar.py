"""Published six-day dates must survive validation, display, and iCal export."""
import copy
import hashlib
import json
from datetime import date, timedelta
from pathlib import Path
import unittest
from icalendar import Calendar
from config_store import validate_config
from school_calendar import export_school_calendar
from server import schedule_for_day


class SixDayCalendarTests(unittest.TestCase):
    def test_published_calendar_covers_term_and_matches_district_closures(self):
        root = Path(__file__).resolve().parents[1]
        source = json.loads((root / 'school-calendars/cusd-elementary-2026-27.json').read_text())
        dates = source['school_dates']
        first, last = map(date.fromisoformat, source['date_range_inclusive'])
        expected = set()
        day = first
        while day <= last:
            if day.weekday() < 5:
                expected.add(day.isoformat())
            day += timedelta(days=1)
        self.assertEqual(set(dates), expected)
        self.assertEqual(len(dates), 222)
        district = json.loads((root / 'school-calendars/chandler-2026-27.json').read_text())
        self.assertEqual({d: v for d, v in dates.items() if v == 'no_school'}, district['school_dates'])
        self.assertEqual([dates[d] for d in ['2026-07-15', '2026-10-19', '2026-10-20', '2026-10-21', '2027-05-20']], [2, 4, 5, 6, 1])
        self.assertEqual(dates['2026-08-19'], 3)  # Early dismissal still has a cycle day.
        self.assertEqual(dates['2026-12-17'], 1)  # Grading period ends on a school day.
        for cycle in range(1, 7):
            self.assertEqual(sum(v == cycle for v in dates.values()), 30)
        pdf = root.parent / 'docs' / source['source']
        self.assertEqual(hashlib.sha256(pdf.read_bytes()).hexdigest(), source['source_sha256'])

    def test_days_five_and_six_display_and_export_without_inventing_activities(self):
        config = {'school_timezone': 'America/Phoenix',
                  'school_dates': {'2026-10-20': 5, '2026-10-21': 6},
                  'children': [{'name': 'Child', 'days': {'5': {'activity': 'PE', 'sneakers': True}}}]}
        validate_config(config)
        fifth = schedule_for_day(config, date(2026, 10, 20))
        self.assertEqual(fifth['cycle_day'], 5)
        self.assertEqual(fifth['children'][0]['activity'], 'PE')
        self.assertTrue(fifth['children'][0]['sneakers'])
        sixth = schedule_for_day(config, date(2026, 10, 21))
        self.assertEqual(sixth['cycle_day'], 6)
        self.assertEqual(sixth['children'][0]['status'], 'unknown')
        self.assertIsNone(sixth['children'][0]['sneakers'])
        events = [str(e['summary']) for e in Calendar.from_ical(export_school_calendar(config)).walk('VEVENT')]
        self.assertIn('School Day 5', events)
        self.assertIn('School Day 6', events)
        self.assertIn('Child · PE — sneakers needed', events)
        for bad in [0, 7, True, '5']:
            invalid = copy.deepcopy(config)
            invalid['school_dates']['2026-10-20'] = bad
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                validate_config(invalid)
