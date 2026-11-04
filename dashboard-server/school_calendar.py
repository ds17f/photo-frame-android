"""Export the local school calendar as a portable Google-importable iCalendar file."""
from datetime import date, datetime, timedelta, timezone
from icalendar import Calendar, Event


def export_school_calendar(config):
    calendar = Calendar()
    calendar.add('prodid', '-//Family Photo Frame//School Calendar//EN')
    calendar.add('version', '2.0')
    calendar.add('x-wr-calname', 'School calendar')
    calendar.add('x-wr-timezone', config['school_timezone'])
    source = config.get('school_calendar_source', {})
    description = 'School calendar exported from Family Frame.'
    if source.get('url'):
        description += '\nSource: ' + source['url']

    def add(day, key, title):
        event = Event()
        start = date.fromisoformat(day)
        event.add('uid', f'school-{key}-{day}@photo-frame')
        event.add('dtstamp', datetime.now(timezone.utc))
        event.add('dtstart', start)
        event.add('dtend', start + timedelta(days=1))
        event.add('summary', title)
        event.add('description', description)
        calendar.add_component(event)

    for day, cycle in sorted(config.get('school_dates', {}).items()):
        if cycle == 'no_school':
            add(day, 'closed', 'No school')
        elif type(cycle) is int and 1 <= cycle <= 6:
            add(day, 'cycle', f'School Day {cycle}')
            for i, child in enumerate(config.get('children', [])):
                details = child.get('days', {}).get(str(cycle), {})
                if details.get('activity') or details.get('sneakers') is True:
                    title = child['name'] + ' · ' + (details.get('activity') or 'School reminder')
                    if details.get('sneakers') is True:
                        title += ' — sneakers needed'
                    add(day, f'child-{i}', title)
    if source.get('first_day'):
        add(source['first_day'], 'first', 'First day of school')
    if source.get('last_day'):
        add(source['last_day'], 'last', 'Last day of school — early release')
    return calendar.to_ical()
