package com.moath.tvhome.prayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.moath.tvhome.design.LiquidGlass.liquidGlass
import com.moath.tvhome.design.TvColors
import com.moath.tvhome.design.TvFonts
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The prayer lock: from the adhan until [PrayerSession.lockUntilMs], the TV shows only this screen.
 * Every key is swallowed except the volume keys; holding Back for five seconds is the way out.
 * Runs in the system process next to [AdhanController].
 */
class PrayerLockActivity : ComponentActivity() {
    private var backDownAt = 0L
    private var backProgress by mutableFloatStateOf(0f)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Ticks while Back is held: fills the bar, and lets the user out once five seconds have passed. */
    private val holdTick = object : Runnable {
        override fun run() {
            if (backDownAt == 0L) return
            val held = SystemClock.uptimeMillis() - backDownAt
            // The bar stays hidden for the first two seconds: only the owner knows the way out.
            backProgress = if (held < REVEAL_MS) 0f else ((held - REVEAL_MS) / (EXIT_HOLD_MS - REVEAL_MS).toFloat()).coerceIn(0f, 1f)
            if (held >= EXIT_HOLD_MS) {
                backDownAt = 0L
                backProgress = 0f
                AdhanController.unlock(this@PrayerLockActivity)
            } else {
                handler.postDelayed(this, 100)
            }
        }
    }

    private val finisher = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ContextCompat.registerReceiver(this, finisher, IntentFilter(ACTION_FINISH), ContextCompat.RECEIVER_NOT_EXPORTED)
        val session = AdhanController.session
        if (session == null || !session.locked) {
            finish()
            return
        }
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                LockScreen(session, { backProgress }) { AdhanController.end(this) }
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(finisher) }
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> return super.dispatchKeyEvent(event)
            KeyEvent.KEYCODE_BACK -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (backDownAt == 0L) {
                        backDownAt = SystemClock.uptimeMillis()
                        handler.post(holdTick)
                    }
                } else {
                    // Released early: start over. (Remotes repeat DOWN while held; the timer does not need it.)
                    backDownAt = 0L
                    backProgress = 0f
                    handler.removeCallbacks(holdTick)
                }
            }
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        shown = this
    }

    override fun onPause() {
        if (shown === this) shown = null
        super.onPause()
    }

    /** Test hook: the key events a remote sends when Back is held for [ms]. */
    fun simulateBackHold(ms: Long) {
        val t = SystemClock.uptimeMillis()
        dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0))
        for (i in 1..(ms / 50).toInt()) handler.postDelayed({ dispatchKeyEvent(KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, i)) }, i * 50L)
        handler.postDelayed({ dispatchKeyEvent(KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0)) }, ms)
    }

    companion object {
        @Volatile var shown: PrayerLockActivity? = null
        const val ACTION_FINISH = "com.moath.tvhome.PRAYER_LOCK_FINISH"
        const val EXIT_HOLD_MS = 5_000L
        private const val REVEAL_MS = 2_000L
    }
}

private val clock = SimpleDateFormat("h:mm", Locale("ar")).apply { timeZone = TimeZone.getTimeZone("Asia/Riyadh") }

private fun style(size: Int, color: Color = TvColors.Label, weight: FontWeight = FontWeight.Medium) =
    TextStyle(fontFamily = TvFonts.Text, fontSize = size.sp, fontWeight = weight, color = color, textAlign = TextAlign.Center)

/** "mm:ss" in Arabic-Indic digits. */
private fun countdown(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale("ar"), "%d:%02d", s / 60, s % 60)
}

@Composable
private fun LockScreen(session: PrayerSession, backProgress: () -> Float, onDone: () -> Unit) {
    var now by androidx.compose.runtime.remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(session) {
        while (true) {
            now = System.currentTimeMillis()
            if (now >= session.lockUntilMs) {
                onDone()
                return@LaunchedEffect
            }
            delay(1_000)
        }
    }
    val beforeIqama = now < session.iqamaAtMs
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0E1A16), Color(0xFF050807)))),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            BasicText("🕌", style = style(54))
            BasicText(
                when {
                    session.slot.jumuah && beforeIqama -> "حان وقت التوجه لصلاة الجمعة"
                    session.slot.jumuah -> "صلاة الجمعة قائمة"
                    beforeIqama -> "حان الآن موعد صلاة ${session.slot.prayer.arabic}"
                    else -> "أقيمت صلاة ${session.slot.prayer.arabic}"
                },
                style = style(40, weight = FontWeight.Bold),
            )
            BasicText(
                if (session.slot.jumuah) "الأذان ${clock.format(Date(session.slot.adhanMs))}  ·  تعود الشاشة ${clock.format(Date(session.lockUntilMs))}"
                else "الأذان ${clock.format(Date(session.slot.adhanMs))}  ·  الإقامة ${clock.format(Date(session.iqamaAtMs))}",
                style = style(18, TvColors.SecondaryLabel),
            )
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.liquidGlass(RoundedCornerShape(26.dp), intensity = 0.4f).padding(horizontal = 34.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BasicText(if (beforeIqama) (if (session.slot.jumuah) "الأذان بعد" else "الإقامة بعد") else "تعود الشاشة بعد", style = style(15, TvColors.SecondaryLabel))
                    BasicText(
                        countdown((if (beforeIqama) session.iqamaAtMs else session.lockUntilMs) - now),
                        style = style(52, weight = FontWeight.Bold),
                    )
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val p = backProgress()
            if (p > 0f) {
                Box(Modifier.width(220.dp).height(4.dp).background(Color(0x33FFFFFF), RoundedCornerShape(2.dp))) {
                    Box(Modifier.fillMaxWidth(p).height(4.dp).background(Color.White, RoundedCornerShape(2.dp)))
                }
                Spacer(Modifier.height(8.dp))
            }
            if (session.slot.jumuah) {
                BasicText("جمعة مباركة 🌿", style = style(13, TvColors.SecondaryLabel))
            }
        }
    }
}
