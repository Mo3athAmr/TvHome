package com.moath.tvhome.prayer

import java.util.Calendar
import java.util.TimeZone

/** What the panel shows at a given instant: today's times, which row is next, and the countdown. */
data class PrayerSnapshot(
    val times: Map<Prayer, Int>,
    /** The next prayer (Sunrise included, as tvOS-style schedules list it), or tomorrow's Fajr. */
    val next: Prayer,
    val minutesUntilNext: Int,
    /** A prayer whose time began within [NOW_WINDOW_MIN] minutes — shown as "now". */
    val current: Prayer?,
) {
    companion object { const val NOW_WINDOW_MIN = 20 }
}

object PrayerSchedule {
    private val saudi: TimeZone = TimeZone.getTimeZone("Asia/Riyadh")

    fun at(millis: Long, cfg: PrayerConfig = PrayerConfig.AlKhobarUmmAlQura): PrayerSnapshot {
        val cal = Calendar.getInstance(saudi).apply { timeInMillis = millis }
        val today = PrayerTimes.forDay(cal[Calendar.YEAR], cal[Calendar.MONTH] + 1, cal[Calendar.DAY_OF_MONTH], cfg)
        val nowMin = cal[Calendar.HOUR_OF_DAY] * 60 + cal[Calendar.MINUTE]

        val upcoming = Prayer.entries.firstOrNull { today.getValue(it) > nowMin }
        val (next, until) = if (upcoming != null) {
            upcoming to today.getValue(upcoming) - nowMin
        } else {
            val tomorrow = (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
            val t = PrayerTimes.forDay(tomorrow[Calendar.YEAR], tomorrow[Calendar.MONTH] + 1, tomorrow[Calendar.DAY_OF_MONTH], cfg)
            Prayer.Fajr to (24 * 60 - nowMin) + t.getValue(Prayer.Fajr)
        }
        val current = Prayer.entries.lastOrNull { p ->
            p.isPrayer && nowMin >= today.getValue(p) && nowMin - today.getValue(p) < PrayerSnapshot.NOW_WINDOW_MIN
        }
        return PrayerSnapshot(today, next, until, current)
    }
}
