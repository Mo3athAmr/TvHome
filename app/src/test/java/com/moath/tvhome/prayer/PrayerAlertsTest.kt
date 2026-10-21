package com.moath.tvhome.prayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class PrayerAlertsTest {
    private val now = ZonedDateTime.of(2026, 10, 20, 9, 0, 0, 0, ZoneId.of("Asia/Riyadh")).toInstant().toEpochMilli()

    @Test fun `fajr is never alerted`() {
        val slots = PrayerAlerts.upcoming(now)
        assertEquals(8, slots.size)
        assertFalse(slots.any { it.prayer == Prayer.Fajr })
        assertEquals(slots.sortedBy { it.atMs }, slots)
    }

    @Test fun `next after morning is dhuhr, current only right at the adhan`() {
        val next = PrayerAlerts.next(now)!!
        assertEquals(Prayer.Dhuhr, next.prayer)
        assertNull(PrayerAlerts.current(now))
        assertNotNull(PrayerAlerts.current(next.atMs + 30_000))
        assertNull(PrayerAlerts.current(next.atMs + 5 * 60_000))
    }
}
