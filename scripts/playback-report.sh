#!/usr/bin/env bash
# Playback randomness report: pulls the database and the playback log from the connected
# device and shows how well the slideshow roams the library.
#
#   scripts/playback-report.sh [HOURS] [PKG]      (or: make playback-report HOURS=24)
#
# HOURS  how far back to look in the playback log (default 24)
# PKG    application id (default com.photoframe.debug)
#
# Needs: adb, sqlite3. The playback log must be on
# (Settings -> Display -> Diagnostics -> "Log shown photos to file").
set -euo pipefail

HOURS="${1:-24}"
PKG="${2:-com.photoframe.debug}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

for f in photoframe.db photoframe.db-wal photoframe.db-shm; do
  adb exec-out run-as "$PKG" cat "databases/$f" > "$WORK/$f" 2>/dev/null || rm -f "$WORK/$f"
done
adb pull "/sdcard/Android/data/$PKG/files/playback-log.tsv" "$WORK/log.tsv" >/dev/null

# Log rows are "timestamp<TAB>url-or-key"; the remote id is the last path segment.
# Fixture (sample) photos are left out.
awk -F'\t' 'NR > 1 && $2 !~ /fixture/ { n = split($2, p, "/"); print $1 "\t" p[n] }' \
  "$WORK/log.tsv" > "$WORK/shown.tsv"

sqlite3 "$WORK/photoframe.db" <<SQL
.mode tabs
CREATE TEMP TABLE raw(ts TEXT, rid TEXT);
.import $WORK/shown.tsv raw
CREATE TEMP TABLE s AS
  SELECT julianday(ts) AS jd, rid FROM raw
  WHERE julianday(ts) >= julianday('now') - $HOURS / 24.0;
CREATE TEMP TABLE lib AS
  SELECT remoteId AS rid, strftime('%Y', takenAt / 1000, 'unixepoch') AS y FROM photos;

.mode column
.headers on
.print '== Last $HOURS h =='
SELECT COUNT(*) AS slides,
       COUNT(DISTINCT rid) AS distinct_photos,
       COUNT(*) - COUNT(DISTINCT rid) AS repeats,
       printf('%.2f%%', 100.0 * (COUNT(*) - COUNT(DISTINCT rid)) / MAX(COUNT(*), 1)) AS repeat_rate,
       CASE WHEN COUNT(*) = 0 THEN 'NO DATA'
            WHEN 1.0 * (COUNT(*) - COUNT(DISTINCT rid)) / COUNT(*) <= 0.01 THEN 'PASS'
            ELSE 'FAIL' END AS repeat_check
FROM s;

.print
.print '== Repeated photos (gap between shows; < 5 min is usually a restart) =='
SELECT a.rid AS photo, COUNT(*) AS shows,
       printf('%.1f h', 24 * (MAX(a.jd) - MIN(a.jd))) AS first_to_last,
       (SELECT strftime('%Y', takenAt / 1000, 'unixepoch') FROM photos WHERE remoteId = a.rid) AS taken
FROM s a GROUP BY a.rid HAVING COUNT(*) > 1 ORDER BY shows DESC, first_to_last LIMIT 15;

.print
.print '== Years: share of library vs share of slides (bias toward newer is expected) =='
WITH ly AS (SELECT y, COUNT(*) AS n FROM lib GROUP BY y),
     sy AS (SELECT l.y, COUNT(*) AS n FROM s JOIN lib l ON l.rid = s.rid GROUP BY l.y),
     tot AS (SELECT (SELECT COUNT(*) FROM lib) AS lt, (SELECT COUNT(*) FROM s JOIN lib USING (rid)) AS st)
SELECT COALESCE(ly.y, 'undated') AS year, ly.n AS photos,
       printf('%5.1f%%', 100.0 * ly.n / tot.lt) AS library,
       printf('%5.1f%%', 100.0 * COALESCE(sy.n, 0) / MAX(tot.st, 1)) AS slides,
       COALESCE(sy.n, 0) AS shown,
       printf('%.1fx', (1.0 * COALESCE(sy.n, 0) / MAX(tot.st, 1)) / (1.0 * ly.n / tot.lt)) AS ratio
FROM ly LEFT JOIN sy ON sy.y IS ly.y, tot
WHERE ly.n >= 50 OR COALESCE(sy.n, 0) > 0
ORDER BY ly.y;

-- Skew check: the newer-photo boost tops out at 3x, so a year with enough slides to matter that
-- is shown > 4x its library share means playback is not a fair draw from the library.
.print
WITH ly AS (SELECT y, COUNT(*) AS n FROM lib GROUP BY y),
     sy AS (SELECT l.y, COUNT(*) AS n FROM s JOIN lib l ON l.rid = s.rid GROUP BY l.y),
     tot AS (SELECT (SELECT COUNT(*) FROM lib) AS lt, (SELECT COUNT(*) FROM s JOIN lib USING (rid)) AS st),
     bad AS (SELECT COALESCE(ly.y, 'undated') AS y FROM ly JOIN sy ON sy.y IS ly.y, tot
             WHERE sy.n >= 20 AND (1.0 * sy.n / tot.st) > 4.0 * (1.0 * ly.n / tot.lt))
SELECT CASE WHEN (SELECT st FROM tot) = 0 THEN 'NO DATA'
            WHEN COUNT(*) = 0 THEN 'PASS'
            ELSE 'FAIL (' || group_concat(y, ', ') || ')' END AS skew_check
FROM bad;

.print
.print '== Library coverage (all time) =='
SELECT COUNT(*) AS photos,
       SUM(lastShownAt IS NOT NULL) AS ever_shown,
       printf('%.1f%%', 100.0 * SUM(lastShownAt IS NOT NULL) / COUNT(*)) AS coverage,
       SUM(status = 'READY') AS cached_files
FROM photos;

.print
.print '== Last 8 syncs (+indexed / -removed = server listing churn) =='
.mode list
.headers off
SELECT datetime(createdAt / 1000, 'unixepoch', 'localtime') || '  ' || message
FROM sync_log ORDER BY createdAt DESC LIMIT 8;
SQL
