package com.moath.tvhome.prayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class PrayerScheduleTest {
    private fun at(h: Int, m: Int): Long = Calendar.getInstance(TimeZone.getTimeZone("Asia/Riyadh")).apply {
        clear(); set(2026, Calendar.OCTOBER, 19, h, m)
    }.timeInMillis

    @Test fun `afternoon counts down to maghrib`() {
        val s = PrayerSchedule.at(at(17, 0))
        assertEquals(Prayer.Maghrib, s.next)
        assertEquals(8, s.minutesUntilNext) // Maghrib 17:08
        assertNull(s.current)
    }

    @Test fun `just after maghrib it is current`() {
        val s = PrayerSchedule.at(at(17, 15))
        assertEquals(Prayer.Maghrib, s.current)
        assertEquals(Prayer.Isha, s.next)
    }

    @Test fun `after isha the next prayer is tomorrow's fajr`() {
        val s = PrayerSchedule.at(at(23, 0))
        assertEquals(Prayer.Fajr, s.next)
        // Tomorrow's Fajr is ~04:22: one hour to midnight plus ~262 minutes.
        assertTrue(s.minutesUntilNext in 318..326)
    }

    @Test fun `sunrise is never shown as now`() {
        val s = PrayerSchedule.at(at(6, 15))
        assertNull(s.current)
        assertEquals(Prayer.Dhuhr, s.next)
    }
}
