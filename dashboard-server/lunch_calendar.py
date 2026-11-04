"""Load local school lunch menus and export them as an iCalendar feed."""
from __future__ import annotations

import json
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

from icalendar import Calendar, Event


def load_lunch_days(directory: Path) -> dict[str, dict]:
    """Merge the published lunch calendar files into records keyed by ISO date."""
    days: dict[str, dict] = {}
    for path in sorted(Path(directory).glob("*.json")):
        data = json.loads(path.read_text(encoding="utf-8"))
        for day, record in data.get("days", {}).items():
            days[day] = {"school": data["school"], "month": data["month"],
                         "source_file": data["source_file"], "source_url": data["source_url"],
                         **record}
    return days


def lunch_for_day(directory: Path, day_iso: str) -> dict:
    """Return a lunch record, including its school, or an explicit unknown record."""
    day = load_lunch_days(directory).get(day_iso)
    if day is None:
        return {"school": "", "status": "unknown",
                "summary": "Menu information not provided", "items": []}
    return day


def export_lunch_calendar(directory: Path) -> bytes:
    """Export known menu dates and explicit no-school dates as all-day events."""
    calendar = Calendar()
    days = load_lunch_days(directory)
    school = next((record["school"] for record in days.values() if record.get("school")), "")
    calendar.add("prodid", "-//Family Photo Frame//School Lunch Calendar//EN")
    calendar.add("version", "2.0")
    calendar.add("x-wr-calname", f"{school} Lunch" if school else "School Lunch")
    for day_iso, record in sorted(days.items()):
        status = record["status"]
        if status == "unknown":
            continue
        event = Event()
        start = date.fromisoformat(day_iso)
        event.add("uid", f"lunch-{day_iso}@photo-frame")
        event.add("dtstamp", datetime.now(timezone.utc))
        event.add("dtstart", start)
        event.add("dtend", start + timedelta(days=1))
        if status == "menu":
            event.add("categories", ["LUNCH"])
            event.add("summary", "Lunch: " + record["summary"])
            details = "\n".join(record["items"])
        elif status == "no_school":
            event.add("summary", record["summary"])
            details = "No school lunch."
        else:
            continue
        details += f"\n\nSource: {record['source_file']}\n{record['source_url']}"
        event.add("description", details)
        calendar.add_component(event)
    return calendar.to_ical()
