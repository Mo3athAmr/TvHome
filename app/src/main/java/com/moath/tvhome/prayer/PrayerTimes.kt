package com.moath.tvhome.prayer

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

enum class Prayer(val arabic: String, val english: String, val isPrayer: Boolean = true) {
    Fajr("الفجر", "Fajr"),
    Sunrise("الشروق", "Sunrise", isPrayer = false),
    Dhuhr("الظهر", "Dhuhr"),
    Asr("العصر", "Asr"),
    Maghrib("المغرب", "Maghrib"),
    Isha("العشاء", "Isha"),
}

/** A place plus the angles of a calculation method. */
data class PrayerConfig(
    val latitude: Double,
    val longitude: Double,
    /** Hours from UTC. Saudi Arabia (like Iraq) is a fixed +3, no DST. */
    val utcOffsetHours: Double,
    val fajrAngle: Double,
    val ishaAngle: Double,
    /** Degrees below the horizon for Maghrib; 0 = at sunset. */
    val maghribAngle: Double,
    /** Shadow factor: 1 = standard (Shafi'i/Jafari), 2 = Hanafi. */
    val asrFactor: Int = 1,
    /** When > 0, Isha is this many minutes after Maghrib instead of using [ishaAngle] (Umm al-Qura: 90). */
    val ishaMinutesAfterMaghrib: Double = 0.0,
) {
    companion object {
        /** Al Khobar (Eastern Province, Saudi Arabia) with the Umm al-Qura method: Fajr 18.5°, Maghrib at sunset, Isha 90 min after Maghrib. */
        val AlKhobarUmmAlQura = PrayerConfig(
            latitude = 26.2794,
            longitude = 50.2083,
            utcOffsetHours = 3.0,
            fajrAngle = 18.5,
            ishaAngle = 0.0,
            maghribAngle = 0.0,
            ishaMinutesAfterMaghrib = 90.0,
        )

        /** Baghdad with the Shia Ithna-Ashari (Leva Institute, Qum) method: Fajr 16°, Maghrib 4°, Isha 14°. */
        val BaghdadJafari = PrayerConfig(
            latitude = 33.3152,
            longitude = 44.3661,
            utcOffsetHours = 3.0,
            fajrAngle = 16.0,
            ishaAngle = 14.0,
            maghribAngle = 4.0,
        )
    }
}

/**
 * Astronomical prayer times (the PrayTimes.org algorithm). Pure math, no network: the screensaver
 * must keep showing correct times when the box is offline. Returns minutes after local midnight.
 */
object PrayerTimes {
    private const val SUNRISE_ANGLE = 0.833

    fun forDay(year: Int, month: Int, day: Int, cfg: PrayerConfig): Map<Prayer, Int> {
        val jDate = julian(year, month, day) - cfg.longitude / (15.0 * 24.0)
        // Initial guesses (hours), refined once as in PrayTimes' single iteration.
        var t = doubleArrayOf(5.0, 6.0, 12.0, 13.0, 18.0, 18.0)
        t = compute(t.map { it / 24.0 }.toDoubleArray(), jDate, cfg)
        val adjust = cfg.utcOffsetHours - cfg.longitude / 15.0
        if (cfg.ishaMinutesAfterMaghrib > 0) {
            t[Prayer.Isha.ordinal] = t[Prayer.Maghrib.ordinal] + cfg.ishaMinutesAfterMaghrib / 60.0
        }
        return Prayer.entries.associateWith { p ->
            val hours = fixHour(t[p.ordinal] + adjust)
            Math.round(hours * 60.0).toInt() % (24 * 60)
        }
    }

    private fun compute(portion: DoubleArray, jDate: Double, cfg: PrayerConfig): DoubleArray {
        val lat = cfg.latitude
        return doubleArrayOf(
            sunAngleTime(cfg.fajrAngle, portion[0], jDate, lat, ccw = true),
            sunAngleTime(SUNRISE_ANGLE, portion[1], jDate, lat, ccw = true),
            midDay(portion[2], jDate),
            asrTime(cfg.asrFactor, portion[3], jDate, lat),
            sunAngleTime(if (cfg.maghribAngle > 0) cfg.maghribAngle else SUNRISE_ANGLE, portion[4], jDate, lat, ccw = false),
            sunAngleTime(cfg.ishaAngle, portion[5], jDate, lat, ccw = false),
        )
    }

    private fun midDay(time: Double, jDate: Double): Double = fixHour(12.0 - sun(jDate + time).equation)

    private fun sunAngleTime(angle: Double, time: Double, jDate: Double, lat: Double, ccw: Boolean): Double {
        val decl = sun(jDate + time).declination
        val noon = midDay(time, jDate)
        val t = darccos((-dsin(angle) - dsin(decl) * dsin(lat)) / (dcos(decl) * dcos(lat))) / 15.0
        return noon + if (ccw) -t else t
    }

    private fun asrTime(factor: Int, time: Double, jDate: Double, lat: Double): Double {
        val decl = sun(jDate + time).declination
        val angle = -darccot(factor + dtan(abs(lat - decl)))
        return sunAngleTime(angle, time, jDate, lat, ccw = false)
    }

    private class Sun(val declination: Double, val equation: Double)

    private fun sun(jd: Double): Sun {
        val d = jd - 2451545.0
        val g = fixAngle(357.529 + 0.98560028 * d)
        val q = fixAngle(280.459 + 0.98564736 * d)
        val l = fixAngle(q + 1.915 * dsin(g) + 0.020 * dsin(2 * g))
        val e = 23.439 - 0.00000036 * d
        val ra = darctan2(dcos(e) * dsin(l), dcos(l)) / 15.0
        return Sun(declination = darcsin(dsin(e) * dsin(l)), equation = q / 15.0 - fixHour(ra))
    }

    private fun julian(year: Int, month: Int, day: Int): Double {
        var y = year
        var m = month
        if (m <= 2) { y -= 1; m += 12 }
        val a = floor(y / 100.0)
        val b = 2 - a + floor(a / 4.0)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + day + b - 1524.5
    }

    private fun dsin(d: Double) = sin(Math.toRadians(d))
    private fun dcos(d: Double) = cos(Math.toRadians(d))
    private fun dtan(d: Double) = tan(Math.toRadians(d))
    private fun darcsin(x: Double) = Math.toDegrees(asin(x))
    private fun darccos(x: Double) = Math.toDegrees(acos(x.coerceIn(-1.0, 1.0)))
    private fun darctan2(y: Double, x: Double) = Math.toDegrees(atan2(y, x))
    private fun darccot(x: Double) = Math.toDegrees(atan(1.0 / x))
    private fun fixAngle(a: Double) = ((a % 360.0) + 360.0) % 360.0
    private fun fixHour(h: Double) = ((h % 24.0) + 24.0) % 24.0
}
