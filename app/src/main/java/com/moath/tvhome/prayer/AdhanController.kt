package com.moath.tvhome.prayer

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.moath.tvhome.R
import com.moath.tvhome.notify.NoticeKind
import com.moath.tvhome.notify.Notifier
import com.moath.tvhome.prefs.LauncherPrefs

/** The current prayer: when the adhan was, when the iqama is, and until when the TV stays locked. */
data class PrayerSession(val slot: AdhanSlot, val iqamaAtMs: Long, val lockUntilMs: Long, val locked: Boolean)

/**
 * What happens at the adhan: wake the screen, then either lock the TV to the prayer screen (media
 * paused) or, with the lock off, show a notice and duck whatever is playing. The adhan itself
 * (Sheikh Mohammed Qassas, Masjid an-Nabawi) plays on top. Lives in the always-on system process.
 */
object AdhanController {
    private const val TAG = "Adhan"
    private const val STANDBY_AFTER_MS = 60_000L
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var restoreMusic: Int? = null
    private var restoreAlarm: Int? = null

    @Volatile var session: PrayerSession? = null
        private set

    val isLocked: Boolean get() = session?.let { it.locked && System.currentTimeMillis() < it.lockUntilMs } == true

    /**
     * After a reboot (TCL's standby is a full power-off) or a crash, a lock whose window is still open
     * comes back; nothing replays the adhan.
     */
    fun resume(context: Context, now: Long = System.currentTimeMillis()) {
        if (session != null) return
        val slot = PrayerAlerts.activeLock(now, LauncherPrefs.get(context).state.value) ?: return
        Log.i(TAG, "Resuming the lock of ${slot.prayer} (jumuah=${slot.jumuah})")
        start(context, slot, resumed = true)
    }

    fun start(context: Context, slot: AdhanSlot, resumed: Boolean = false) {
        if (session?.slot?.atMs == slot.atMs) return
        val p = LauncherPrefs.get(context).state.value
        if (slot.jumuah) return startJumuah(context, slot)
        val iqama = slot.atMs + p.iqamaFor(slot.key) * 60_000L
        val s = PrayerSession(slot, iqama, iqama + p.lockAfterIqama * 60_000L, locked = p.prayerLock)
        session = s
        Log.i(TAG, "Adhan ${slot.prayer} lock=${s.locked}")
        if (p.wakeForAdhan && !resumed) wake(context)
        if (s.locked) {
            pauseMedia(context)
            context.startActivity(Intent(context, PrayerLockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        } else {
            Notifier.post(
                context,
                "حان الآن موعد أذان ${slot.prayer.arabic}",
                "الإقامة بعد ${p.iqamaFor(slot.key)} دقيقة",
                NoticeKind.ADHAN,
                durationMs = 20_000,
            )
        }
        if (p.adhanSound && !resumed) playAdhan(context, duckOthers = !s.locked)
        // Clear the session once the lock window is over (also ends a lock nobody dismissed).
        main.postDelayed({ if (session === s) end(context) }, (s.lockUntilMs - System.currentTimeMillis()).coerceAtLeast(60_000))
    }

    /**
     * Jumu'ah: from [AdhanSlot.atMs] (before the adhan) until after the prayer, the TV is locked; media
     * pauses, the Jumu'ah screen shows, and after a minute the TV goes to standby. No adhan sound or wake:
     * the family is on its way to the mosque. Waking the TV inside the window brings the lock back.
     */
    private fun startJumuah(context: Context, slot: AdhanSlot) {
        val p = LauncherPrefs.get(context).state.value
        val s = PrayerSession(slot, slot.adhanMs, slot.adhanMs + p.jumuahAfter * 60_000L, locked = true)
        session = s
        Log.i(TAG, "Jumu'ah lock until ${s.lockUntilMs}")
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        Log.i(TAG, "Jumu'ah start, screen on=${pm.isInteractive}")
        if (pm.isInteractive) {
            pauseMedia(context)
            context.startActivity(Intent(context, PrayerLockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            scheduleStandby(context)
        }
        main.postDelayed({ if (session === s) end(context) }, (s.lockUntilMs - System.currentTimeMillis()).coerceAtLeast(60_000))
    }

    private val standby = Runnable {
        val s = session
        Log.i(TAG, "Jumu'ah standby check: session=${s != null} jumuah=${s?.slot?.jumuah} locked=$isLocked")
        if (s == null || !s.slot.jumuah || !isLocked) return@Runnable
        goToStandby()
    }

    /** Puts the TV to standby (the accessibility "lock screen" action sleeps the device). */
    fun goToStandby(): Boolean {
        val ok = com.moath.tvhome.system.SystemUiService.instance
            ?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) == true
        Log.i(TAG, "standby requested ok=$ok")
        return ok
    }

    private fun scheduleStandby(context: Context) {
        main.removeCallbacks(standby)
        val on = LauncherPrefs.get(context).state.value.jumuahStandby
        Log.i(TAG, "Jumu'ah standby scheduled=$on")
        if (on) main.postDelayed(standby, STANDBY_AFTER_MS)
    }

    /** The screen came on: inside a Jumu'ah lock that means the lock again, and standby again later. */
    fun onScreenOn(context: Context) {
        val s = session ?: return
        if (!isLocked) return
        if (s.slot.jumuah) {
            pauseMedia(context)
            scheduleStandby(context)
        }
        context.startActivity(Intent(context, PrayerLockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    /** The emergency exit (Back held 5 s) or the end of the lock window. */
    fun end(context: Context) {
        main.removeCallbacks(standby)
        session = null
        stopAdhan(context)
        context.sendBroadcast(Intent(PrayerLockActivity.ACTION_FINISH).setPackage(context.packageName))
    }

    /** Leave the lock early but keep the session (so the minute check doesn't start it again). */
    fun unlock(context: Context) {
        main.removeCallbacks(standby)
        session = session?.copy(locked = false)
        stopAdhan(context)
        context.sendBroadcast(Intent(PrayerLockActivity.ACTION_FINISH).setPackage(context.packageName))
    }

    private fun wake(context: Context) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isInteractive) return
        @Suppress("DEPRECATION")
        runCatching {
            pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "tvhome:adhan").acquire(15_000)
        }.onFailure { Log.w(TAG, "Wake failed", it) }
    }

    private fun pauseMedia(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
    }

    private fun playAdhan(context: Context, duckOthers: Boolean) {
        stopAdhan(context)
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val music = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val musicMax = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val usage: Int
        if (duckOthers) {
            // Whatever is playing drops to a quarter; the adhan rides the alarm stream at the old level.
            restoreMusic = music
            am.setStreamVolume(AudioManager.STREAM_MUSIC, music / 4, 0)
            val alarmMax = am.getStreamMaxVolume(AudioManager.STREAM_ALARM).coerceAtLeast(1)
            restoreAlarm = am.getStreamVolume(AudioManager.STREAM_ALARM)
            val level = ((music.toFloat() / musicMax) * alarmMax).toInt().coerceIn(alarmMax / 3, alarmMax)
            am.setStreamVolume(AudioManager.STREAM_ALARM, level, 0)
            usage = AudioAttributes.USAGE_ALARM
        } else {
            // Locked: everything else is paused; make sure the adhan is audible.
            val floor = musicMax / 5
            if (music < floor) {
                restoreMusic = music
                am.setStreamVolume(AudioManager.STREAM_MUSIC, floor, 0)
            }
            usage = AudioAttributes.USAGE_MEDIA
        }
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                context.resources.openRawResourceFd(R.raw.adhan_qassas).use { setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                setOnCompletionListener { stopAdhan(context) }
                prepare()
                start()
            }
        }.onFailure { Log.w(TAG, "Adhan audio failed", it) }.getOrNull()
    }

    private fun stopAdhan(context: Context) {
        player?.runCatching { stop(); release() }
        player = null
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        restoreMusic?.let { am.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) }
        restoreAlarm?.let { am.setStreamVolume(AudioManager.STREAM_ALARM, it, 0) }
        restoreMusic = null
        restoreAlarm = null
    }
}
