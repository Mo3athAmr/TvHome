package com.moath.tvhome.health

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.moath.tvhome.notify.NoticeKind
import com.moath.tvhome.notify.Notifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Runs the checks when Home comes back: repairs silently what it can, and asks about the rest. */
object HealthWatch {
    private const val SNOOZE_MS = 6 * 3_600_000L
    private var lastRun = 0L

    private fun state(context: Context) = context.getSharedPreferences("health_ui", Context.MODE_PRIVATE)

    private fun signature(items: List<HealthItem>) = items.filter { it.level == Level.Broken }.map { it.id }.sorted().joinToString(",")

    fun snooze(context: Context, items: List<HealthItem>) {
        state(context).edit().putString("sig", signature(items)).putLong("until", System.currentTimeMillis() + SNOOZE_MS).apply()
    }

    suspend fun onHomeResumed(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastRun < 30_000) return
        lastRun = now
        val app = context.applicationContext
        val (fixed, items) = withContext(Dispatchers.Default) { Health.autoFix(app) to Health.check(app) }
        if (fixed) {
            // The service needs a moment to come back before it can draw the notice.
            Handler(Looper.getMainLooper()).postDelayed({
                Notifier.post(app, "تمت إعادة تفعيل خدمة النظام ✅", "توقفت الخدمة وأعدنا تشغيلها تلقائياً", NoticeKind.INFO, 8_000)
            }, 4_000)
        }
        val sig = signature(items)
        if (sig.isEmpty()) return
        val s = state(app)
        if (s.getString("sig", null) == sig && now < s.getLong("until", 0)) return
        runCatching { context.startActivity(HealthActivity.intent(context, fromHome = true)) }
    }
}
