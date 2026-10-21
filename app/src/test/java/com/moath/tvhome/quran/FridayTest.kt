package com.moath.tvhome.quran

import com.moath.tvhome.prayer.Prayer
import com.moath.tvhome.prayer.PrayerAlerts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class FridayTest {
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0) =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, ZoneId.of("Asia/Riyadh")).toInstant().toEpochMilli()

    @Test fun `friday dhuhr becomes a jumuah slot that starts before the adhan`() {
        val fridayMorning = at(2026, 10, 23, 9) // Friday
        val slots = PrayerAlerts.upcoming(fridayMorning, jumuahBefore = 30)
        val jumuah = slots.first { it.prayer == Prayer.Dhuhr }
        assertTrue(jumuah.jumuah)
        assertEquals(30 * 60_000L, jumuah.adhanMs - jumuah.atMs)
        // Saturday's Dhuhr (also in the list) is an ordinary one.
        assertFalse(slots.last { it.prayer == Prayer.Dhuhr }.jumuah)
        // Without the setting Friday is like any day.
        assertFalse(PrayerAlerts.upcoming(fridayMorning).first { it.prayer == Prayer.Dhuhr }.jumuah)
    }

    @Test fun `al kahf runs from thursday maghrib to friday maghrib`() {
        assertNull(KahfTime.fridayOf(at(2026, 10, 22, 12)))            // Thursday noon
        assertEquals("2026-10-23", KahfTime.fridayOf(at(2026, 10, 22, 20))) // Thursday night
        assertEquals("2026-10-23", KahfTime.fridayOf(at(2026, 10, 23, 10))) // Friday morning
        assertNull(KahfTime.fridayOf(at(2026, 10, 23, 20)))            // Friday after Maghrib
        assertNull(KahfTime.fridayOf(at(2026, 10, 21, 10)))            // Wednesday
    }

    @Test fun `surahs are found by name or number`() {
        assertEquals(18, QuranAudio.find("الكهف"))
        assertEquals(18, QuranAudio.find("سورة الكهف"))
        assertEquals(36, QuranAudio.find("يس"))
        assertEquals(67, QuranAudio.find("سورة الملك"))
        assertEquals(2, QuranAudio.find("2"))
        assertEquals(114, QuranAudio.surahs.size)
        assertEquals("https://cdn.mp3quran.net/audio/mishary-alafasy/r1/018.mp3", QuranAudio.url(18, QuranAudio.reciters[0]))
    }
}

class ResumeLockTest {
    private fun at(d: Int, h: Int, m: Int) = java.time.ZonedDateTime.of(2026, 10, d, h, m, 0, 0, java.time.ZoneId.of("Asia/Riyadh")).toInstant().toEpochMilli()
    private val p = com.moath.tvhome.prefs.LauncherSettings()

    @org.junit.Test fun `a reboot inside the jumuah window finds the lock again`() {
        val friday = com.moath.tvhome.prayer.PrayerAlerts.upcoming(at(23, 6, 0), jumuahBefore = 30).first { it.jumuah }
        val inside = com.moath.tvhome.prayer.PrayerAlerts.activeLock(friday.adhanMs + 10 * 60_000L, p)
        org.junit.Assert.assertTrue(inside!!.jumuah)
        org.junit.Assert.assertNull(com.moath.tvhome.prayer.PrayerAlerts.activeLock(friday.adhanMs + 60 * 60_000L, p))
        org.junit.Assert.assertNull(com.moath.tvhome.prayer.PrayerAlerts.activeLock(friday.atMs - 60_000L, p))
    }

    @org.junit.Test fun `an ordinary lock resumes until the iqama`() {
        val asr = com.moath.tvhome.prayer.PrayerAlerts.upcoming(at(21, 6, 0)).first { it.prayer == com.moath.tvhome.prayer.Prayer.Asr }
        org.junit.Assert.assertEquals(asr, com.moath.tvhome.prayer.PrayerAlerts.activeLock(asr.adhanMs + 19 * 60_000L, p))
        org.junit.Assert.assertNull(com.moath.tvhome.prayer.PrayerAlerts.activeLock(asr.adhanMs + 21 * 60_000L, p))
        // The owner can still ask for extra minutes after the iqama.
        org.junit.Assert.assertEquals(asr, com.moath.tvhome.prayer.PrayerAlerts.activeLock(asr.adhanMs + 25 * 60_000L, p.copy(lockAfterIqama = 10)))
        org.junit.Assert.assertNull(com.moath.tvhome.prayer.PrayerAlerts.activeLock(asr.adhanMs + 5 * 60_000L, p.copy(prayerLock = false)))
    }
}
