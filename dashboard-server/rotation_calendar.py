"""Materialize the household's four-day rotation using confirmed school closures."""
import argparse
import copy
from datetime import date, timedelta
from pathlib import Path

from config_store import ConfigStore


def four_day_calendar(config, anchor_date, anchor_day):
    """Replace rotation dates for the term; weekends and closures consume no day."""
    if type(anchor_day) is not int or not 1 <= anchor_day <= 4:
        raise ValueError("Anchor day must be 1–4")
    result = copy.deepcopy(config)
    source = result["school_calendar_source"]
    first, last = map(date.fromisoformat, (source["first_day"], source["last_day"]))
    closures = {key for key, value in result.get("school_dates", {}).items() if value == "no_school"}
    school_days = []
    day = first
    while day <= last:
        if day.weekday() < 5 and day.isoformat() not in closures:
            school_days.append(day)
        day += timedelta(days=1)
    if anchor_date not in school_days:
        raise ValueError("Anchor date must be a school day within the term")
    anchor_index = school_days.index(anchor_date)
    result["school_dates"] = {key: "no_school" for key in sorted(closures)}
    for index, day in enumerate(school_days):
        result["school_dates"][day.isoformat()] = (anchor_day - 1 + index - anchor_index) % 4 + 1
    source["rotation"] = {
        "cycle_days": 4,
        "anchor_date": anchor_date.isoformat(),
        "anchor_day": anchor_day,
        "source": "Household-confirmed four-day specials rotation",
        "rule": "Advance on weekdays only, skipping configured no_school dates; early release counts.",
    }
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--anchor-date", type=date.fromisoformat, required=True)
    parser.add_argument("--anchor-day", type=int, required=True)
    args = parser.parse_args()
    store = ConfigStore(args.config)
    snapshot = store.snapshot()
    updated = four_day_calendar(snapshot["config"], args.anchor_date, args.anchor_day)
    store.save(updated, snapshot["revision"])
    print(f"Saved four-day rotation anchored to {args.anchor_date}: Day {args.anchor_day}; previous configuration backed up.")
