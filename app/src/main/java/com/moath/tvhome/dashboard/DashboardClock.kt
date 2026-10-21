package com.moath.tvhome.dashboard

import android.icu.util.IslamicCalendar
import android.icu.util.ULocale
import com.moath.tvhome.prayer.Prayer
import com.moath.tvhome.prayer.PrayerConfig
import com.moath.tvhome.prayer.PrayerTimes
import java.util.Calendar
import java.util.TimeZone

/** Everything time-derived the dashboard shows, computed for one instant in Al Khobar. */
data class DashboardMoment(
    val minuteOfDay: Int,
    val gregorian: String,
    val hijri: String,
    val daysToRamadan: Int?,
    /** The five daily prayers (no sunrise) in order, minutes after midnight. */
    val prayers: List<Pair<Prayer, Int>>,
    val next: Prayer,
    val nextAt: Int,
    val minutesToNext: Int,
    val previous: Prayer,
    val minutesSincePrevious: Int,
)

object DashboardClock {
    private val zone: TimeZone = TimeZone.getTimeZone("Asia/Riyadh")
    private val five = listOf(Prayer.Fajr, Prayer.Dhuhr, Prayer.Asr, Prayer.Maghrib, Prayer.Isha)
    private val hijriMonths = listOf(
        "محرم", "صفر", "ربيع الأول", "ربيع الآخر", "جمادى الأولى", "جمادى الآخرة",
        "رجب", "شعبان", "رمضان", "شوال", "ذو القعدة", "ذو الحجة",
    )
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val RAMADAN = 8

    fun at(millis: Long, cfg: PrayerConfig = PrayerConfig.AlKhobarUmmAlQura): DashboardMoment {
        val cal = Calendar.getInstance(zone).apply { timeInMillis = millis }
        val now = cal[Calendar.HOUR_OF_DAY] * 60 + cal[Calendar.MINUTE]
        val today = timesFor(cal, 0, cfg)
        val prayers = five.map { it to today.getValue(it) }

        val upcoming = prayers.firstOrNull { it.second > now }
        val (next, nextAt, toNext) = upcoming?.let { Triple(it.first, it.second, it.second - now) } ?: run {
            val fajr = timesFor(cal, 1, cfg).getValue(Prayer.Fajr)
            Triple(Prayer.Fajr, fajr, 24 * 60 - now + fajr)
        }
        val past = prayers.lastOrNull { it.second <= now }
        val (previous, sincePrev) = past?.let { it.first to now - it.second } ?: run {
            val isha = timesFor(cal, -1, cfg).getValue(Prayer.Isha)
            Prayer.Isha to now + 24 * 60 - isha
        }

        return DashboardMoment(
            minuteOfDay = now,
            gregorian = ArabicText.gregorian(cal[Calendar.DAY_OF_WEEK] - 1, cal[Calendar.DAY_OF_MONTH], cal[Calendar.MONTH]),
            hijri = hijri(millis),
            daysToRamadan = daysToRamadan(millis),
            prayers = prayers,
            next = next,
            nextAt = nextAt,
            minutesToNext = toNext,
            previous = previous,
            minutesSincePrevious = sincePrev,
        )
    }

    private fun timesFor(base: Calendar, dayOffset: Int, cfg: PrayerConfig): Map<Prayer, Int> {
        val c = (base.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, dayOffset) }
        return PrayerTimes.forDay(c[Calendar.YEAR], c[Calendar.MONTH] + 1, c[Calendar.DAY_OF_MONTH], cfg)
    }

    private fun islamic(millis: Long) = IslamicCalendar(android.icu.util.TimeZone.getTimeZone("Asia/Riyadh"), ULocale("ar")).apply {
        calculationType = IslamicCalendar.CalculationType.ISLAMIC_UMALQURA
        timeInMillis = millis
    }

    private fun hijri(millis: Long): String = runCatching {
        val c = islamic(millis)
        "${ArabicText.digits(c[IslamicCalendar.DAY_OF_MONTH])} ${hijriMonths[c[IslamicCalendar.MONTH]]} ${ArabicText.digits(c[IslamicCalendar.EXTENDED_YEAR])} هـ"
    }.getOrDefault("")

    /** Whole days from today until 1 Ramadan (Umm al-Qura); null during Ramadan itself. */
    private fun daysToRamadan(millis: Long): Int? = runCatching {
        val today = islamic(millis).apply {
            set(IslamicCalendar.HOUR_OF_DAY, 0); set(IslamicCalendar.MINUTE, 0)
            set(IslamicCalendar.SECOND, 0); set(IslamicCalendar.MILLISECOND, 0)
        }
        if (today[IslamicCalendar.MONTH] == RAMADAN) return@runCatching null
        val target = (today.clone() as IslamicCalendar).apply {
            set(IslamicCalendar.MONTH, RAMADAN)
            set(IslamicCalendar.DAY_OF_MONTH, 1)
            if (timeInMillis <= today.timeInMillis) add(IslamicCalendar.EXTENDED_YEAR, 1)
        }
        Math.round((target.timeInMillis - today.timeInMillis).toDouble() / DAY_MS).toInt()
    }.getOrNull()
}
