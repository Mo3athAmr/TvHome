package com.moath.tvhome.prayer

import org.junit.Assert.assertTrue
import org.junit.Test

/** Reference values: api.aladhan.com method 4 (Umm al-Qura) for Al Khobar 26.2794, 50.2083. */
class PrayerTimesTest {
    private fun hm(s: String) = s.substring(0, 2).toInt() * 60 + s.substring(3).toInt()

    private fun assertDay(y: Int, m: Int, d: Int, expected: Map<Prayer, String>) {
        val got = PrayerTimes.forDay(y, m, d, PrayerConfig.AlKhobarUmmAlQura)
        expected.forEach { (p, want) ->
            val diff = kotlin.math.abs(got.getValue(p) - hm(want))
            assertTrue("$p on $y-$m-$d: got ${got[p]} want $want", diff <= 1)
        }
    }

    @Test fun `al khobar autumn matches aladhan`() = assertDay(
        2026, 10, 19,
        mapOf(
            Prayer.Fajr to "04:21", Prayer.Sunrise to "05:40", Prayer.Dhuhr to "11:24",
            Prayer.Asr to "14:42", Prayer.Maghrib to "17:08", Prayer.Isha to "18:38",
        ),
    )

    @Test fun `al khobar summer matches aladhan`() = assertDay(
        2026, 6, 1,
        mapOf(
            Prayer.Fajr to "03:15", Prayer.Sunrise to "04:47", Prayer.Dhuhr to "11:37",
            Prayer.Asr to "15:04", Prayer.Maghrib to "18:27", Prayer.Isha to "19:57",
        ),
    )
}
