package com.photoframe.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietHoursTest {

    private fun m(h: Int, min: Int = 0) = h * 60 + min

    @Test fun overnightWindow_isQuietLateNightAndEarlyMorning() {
        val start = m(23); val end = m(7) // 23:00–07:00
        assertTrue(QuietHours.isQuiet(m(23, 30), start, end)) // 23:30
        assertTrue(QuietHours.isQuiet(m(2), start, end))      // 02:00
        assertTrue(QuietHours.isQuiet(m(6, 59), start, end))  // 06:59
    }

    @Test fun overnightWindow_isNotQuietDuringDay() {
        val start = m(23); val end = m(7)
        assertFalse(QuietHours.isQuiet(m(7), start, end))     // 07:00 = end (exclusive)
        assertFalse(QuietHours.isQuiet(m(12), start, end))    // noon
        assertFalse(QuietHours.isQuiet(m(22, 59), start, end))// just before start
    }

    @Test fun sameDayWindow_worksInclusiveStartExclusiveEnd() {
        val start = m(13); val end = m(14) // 13:00–14:00
        assertTrue(QuietHours.isQuiet(m(13), start, end))
        assertTrue(QuietHours.isQuiet(m(13, 59), start, end))
        assertFalse(QuietHours.isQuiet(m(14), start, end))
        assertFalse(QuietHours.isQuiet(m(12, 59), start, end))
    }

    @Test fun zeroLengthWindow_isNeverQuiet() {
        assertFalse(QuietHours.isQuiet(m(9), m(9), m(9)))
    }

    @Test fun disabledSettings_neverQuiet() {
        val s = ScheduleSettings(quietHoursEnabled = false, quietStartMinute = m(0), quietEndMinute = m(23, 59))
        assertFalse(QuietHours.isQuiet(java.time.LocalTime.of(3, 0), s))
    }

    @Test fun clockLabel_formats12Hour() {
        assertEquals("11:00 PM", m(23).toClockLabel())
        assertEquals("7:00 AM", m(7).toClockLabel())
        assertEquals("12:00 AM", m(0).toClockLabel())
        assertEquals("12:30 PM", m(12, 30).toClockLabel())
    }
}
