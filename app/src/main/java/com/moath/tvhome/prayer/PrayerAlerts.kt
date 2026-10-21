package com.moath.tvhome.prayer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.Calendar
import java.util.TimeZone

/**
 * One adhan the TV reacts to. Fajr is deliberately left out (the owner asked for no Fajr alert).
 * On Friday, Dhuhr becomes Jumu'ah: [atMs] is when the lock starts (before the adhan at [adhanMs]).
 */
data class AdhanSlot(val prayer: Prayer, val atMs: Long, val adhanMs: Long = atMs, val jumuah: Boolean = false) {
    val key: String get() = prayer.name.lowercase()
}

object PrayerAlerts {
    val ALERTED = listOf(Prayer.Dhuhr, Prayer.Asr, Prayer.Maghrib, Prayer.Isha)
    private val zone: TimeZone = TimeZone.getTimeZone("Asia/Riyadh")
    private const val TAG = "PrayerAlerts"
    const val EXTRA_PRAYER = "prayer"
    const val EXTRA_AT = "at"
    const val EXTRA_ADHAN = "adhan"
    const val EXTRA_JUMUAH = "jumuah"

    /**
     * Today's and tomorrow's alerted adhans in epoch millis, in order. Pure apart from the clock.
     * [jumuahBefore] (minutes) turns Friday's Dhuhr into a Jumu'ah slot starting that long before the adhan.
     */
    fun upcoming(nowMs: Long, cfg: PrayerConfig = PrayerConfig.AlKhobarUmmAlQura, jumuahBefore: Int? = null): List<AdhanSlot> {
        val out = ArrayList<AdhanSlot>()
        for (dayOffset in 0..1) {
            val day = Calendar.getInstance(zone).apply { timeInMillis = nowMs; add(Calendar.DAY_OF_MONTH, dayOffset) }
            val times = PrayerTimes.forDay(day[Calendar.YEAR], day[Calendar.MONTH] + 1, day[Calendar.DAY_OF_MONTH], cfg)
            for (p in ALERTED) {
                val minute = times.getValue(p)
                val at = Calendar.getInstance(zone).apply {
                    timeInMillis = day.timeInMillis
                    set(Calendar.HOUR_OF_DAY, minute / 60); set(Calendar.MINUTE, minute % 60); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                val friday = day[Calendar.DAY_OF_WEEK] == Calendar.FRIDAY
                out += if (p == Prayer.Dhuhr && friday && jumuahBefore != null) AdhanSlot(p, at - jumuahBefore * 60_000L, at, jumuah = true)
                else AdhanSlot(p, at)
            }
        }
        return out.sortedBy { it.atMs }
    }

    fun next(nowMs: Long, jumuahBefore: Int? = null): AdhanSlot? = upcoming(nowMs, jumuahBefore = jumuahBefore).firstOrNull { it.atMs > nowMs }

    /** The adhan whose time is [nowMs] give or take [windowMs] (for the in-service minute check). */
    fun current(nowMs: Long, windowMs: Long = 90_000, jumuahBefore: Int? = null): AdhanSlot? =
        upcoming(nowMs - 86_400_000, jumuahBefore = jumuahBefore).plus(upcoming(nowMs, jumuahBefore = jumuahBefore)).firstOrNull { nowMs - it.atMs in 0..windowMs }

    /** The prayer whose lock window contains [now], if its lock is switched on. Pure, for tests. */
    fun activeLock(now: Long, p: com.moath.tvhome.prefs.LauncherSettings): AdhanSlot? {
        val before = if (p.jumuahLock) p.jumuahBefore else null
        return PrayerAlerts.upcoming(now - 86_400_000, jumuahBefore = before).plus(PrayerAlerts.upcoming(now, jumuahBefore = before))
            .lastOrNull { it.atMs <= now && now < lockEnd(p, it) }
            ?.takeIf { it.jumuah || p.prayerLock }
    }

    private fun lockEnd(p: com.moath.tvhome.prefs.LauncherSettings, slot: AdhanSlot): Long =
        if (slot.jumuah) slot.adhanMs + p.jumuahAfter * 60_000L
        else slot.adhanMs + (p.iqamaFor(slot.key) + p.lockAfterIqama) * 60_000L

    /** The owner's Jumu'ah lead time, or null when Friday is handled like any Dhuhr. */
    fun jumuahBefore(context: Context): Int? =
        com.moath.tvhome.prefs.LauncherPrefs.get(context).state.value.let { if (it.jumuahLock) it.jumuahBefore else null }

    /** Arms a wake-up alarm for the next adhan (exact when the owner granted exact alarms). */
    fun schedule(context: Context) {
        val slot = next(System.currentTimeMillis(), jumuahBefore(context)) ?: return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context, 7,
            Intent(context, PrayerAlarmReceiver::class.java).putExtra(EXTRA_PRAYER, slot.prayer.name).putExtra(EXTRA_AT, slot.atMs)
                .putExtra(EXTRA_ADHAN, slot.adhanMs).putExtra(EXTRA_JUMUAH, slot.jumuah),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        runCatching {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, slot.atMs, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, slot.atMs, pi)
        }.onFailure { Log.w(TAG, "Cannot schedule adhan alarm", it) }
        Log.i(TAG, "Next adhan ${slot.prayer} at ${slot.atMs} (exact=$exact)")
    }
}

/** Wakes the system process at the adhan (also from standby) and re-arms the next one. */
class PrayerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prayer = intent.getStringExtra(PrayerAlerts.EXTRA_PRAYER)?.let { runCatching { Prayer.valueOf(it) }.getOrNull() }
        val at = intent.getLongExtra(PrayerAlerts.EXTRA_AT, 0)
        if (prayer != null && System.currentTimeMillis() - at in -60_000..600_000) {
            val adhan = intent.getLongExtra(PrayerAlerts.EXTRA_ADHAN, at)
            AdhanController.start(context.applicationContext, AdhanSlot(prayer, at, adhan, intent.getBooleanExtra(PrayerAlerts.EXTRA_JUMUAH, false)))
        }
        PrayerAlerts.schedule(context.applicationContext)
    }
}
