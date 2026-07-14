package com.photoframe.settings

import java.time.LocalTime

/**
 * Power/kiosk schedule (DESIGN §10): quiet hours ("scheduled off"), the charging rule, and boot
 * auto-start. Times are minutes-of-day (0..1439) so they're trivially serialized and testable.
 */
data class ScheduleSettings(
    val quietHoursEnabled: Boolean = false,
    /** Start of the nightly quiet window, minutes past midnight (default 23:00). */
    val quietStartMinute: Int = 23 * 60,
    /** End of the quiet window (default 07:00). May be < start, meaning the window spans midnight. */
    val quietEndMinute: Int = 7 * 60,
    /** Optionally fake-off whenever the tablet is unplugged (default off). */
    val chargingFakeOffEnabled: Boolean = false,
    /** Relaunch the slideshow after a device reboot (default on). */
    val startOnBoot: Boolean = true,
)

/** Pure quiet-hours evaluation (DESIGN §10). Kept Android-free so it's unit-testable. */
object QuietHours {

    /**
     * Whether [nowMinute] (0..1439) falls in the quiet window `[start, end)`. Handles the common
     * overnight case where `start > end` (e.g. 23:00–07:00). A zero-length window is never quiet.
     */
    fun isQuiet(nowMinute: Int, startMinute: Int, endMinute: Int): Boolean {
        if (startMinute == endMinute) return false
        return if (startMinute < endMinute) {
            nowMinute in startMinute until endMinute
        } else {
            nowMinute >= startMinute || nowMinute < endMinute
        }
    }

    fun isQuiet(now: LocalTime, settings: ScheduleSettings): Boolean =
        settings.quietHoursEnabled &&
            isQuiet(now.hour * 60 + now.minute, settings.quietStartMinute, settings.quietEndMinute)
}

/** Format a minutes-of-day value as "H:MM AM/PM" for the UI. */
fun Int.toClockLabel(): String {
    val h24 = (this / 60) % 24
    val m = this % 60
    val period = if (h24 < 12) "AM" else "PM"
    val h12 = when (h24 % 12) { 0 -> 12; else -> h24 % 12 }
    return "%d:%02d %s".format(h12, m, period)
}
