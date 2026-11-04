FROM python:3.12-alpine@sha256:1b668429b3511ab407d8e00648891631b0b1a4d7e15e3ca70f38ab5b91ad4ab4

# zoneinfo needs the IANA database for school-local dates and daylight saving time.
RUN apk add --no-cache tzdata
WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt
COPY server.py config_store.py calendar_feed.py school_calendar.py lunch_calendar.py index.html admin.html ./
# Lunch menus are private household data: mounted at runtime, not baked in.
RUN mkdir -p /app/lunch-calendars
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
# Match the HomeLab operator's UID so the mounted config directory stays editable.
USER 1000:1000
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD python3 -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/api/data', timeout=3).read()"
CMD ["python3", "server.py", "--host", "0.0.0.0", "--config", "/config/config.json"]
