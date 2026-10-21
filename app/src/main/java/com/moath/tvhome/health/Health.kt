package com.moath.tvhome.health

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.moath.tvhome.BuildConfig
import com.moath.tvhome.prefs.LauncherPrefs
import com.moath.tvhome.screensaver.Screensaver
import com.moath.tvhome.system.SystemUiService

enum class Level { Ok, Warn, Broken }

/** One feature's state. [fixable]: the app can repair it itself (or open the right system screen). */
data class HealthItem(val id: String, val title: String, val detail: String, val level: Level, val fixable: Boolean)

/**
 * Checks every feature we built and repairs what it can. Runtime failures (a rejected API key, a
 * data feed down for hours) are recorded by the features themselves through [report]/[recover],
 * from either process.
 */
object Health {
    private const val STORE = "health"
    /** A feed counts as broken only after failing this long (a short outage is normal). */
    private const val FEED_GRACE_MS = 3 * 3_600_000L

    private fun store(context: Context) =
        @Suppress("DEPRECATION") context.applicationContext.getSharedPreferences(STORE, Context.MODE_MULTI_PROCESS)

    /** Records a failure of [key] (first time kept, so we know how long it has been failing). */
    fun report(context: Context, key: String, message: String) {
        val s = store(context)
        if (!s.contains("$key.since")) s.edit().putLong("$key.since", System.currentTimeMillis()).apply()
        s.edit().putString("$key.msg", message.take(160)).apply()
    }

    fun recover(context: Context, key: String) {
        val s = store(context)
        if (s.contains("$key.since")) s.edit().remove("$key.since").remove("$key.msg").apply()
    }

    private fun failing(context: Context, key: String, graceMs: Long = 0): String? {
        val s = store(context)
        val since = s.getLong("$key.since", 0)
        if (since == 0L || System.currentTimeMillis() - since < graceMs) return null
        return s.getString("$key.msg", "") ?: ""
    }

    private val service get() = ComponentName(BuildConfig.APPLICATION_ID, SystemUiService::class.java.name)

    fun serviceEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val listed = enabled.split(':').any { ComponentName.unflattenFromString(it) == service }
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val running = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo?.serviceInfo?.let { si -> si.packageName == service.packageName && si.name == service.className } == true }
        return listed && running
    }

    fun canWriteSecure(context: Context) =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun check(context: Context): List<HealthItem> {
        val p = LauncherPrefs.get(context).state.value
        val out = ArrayList<HealthItem>()
        val serviceOk = serviceEnabled(context)
        out += HealthItem(
            "service", "خدمة النظام",
            if (serviceOk) "تعمل" else "متوقفة: الأذان وقفل الصلاة والإشعارات وزر المساعد وتبديل التطبيقات لا تعمل",
            if (serviceOk) Level.Ok else Level.Broken, fixable = true,
        )
        val home = context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
        val isHome = home?.activityInfo?.packageName == context.packageName
        out += HealthItem("launcher", "الشاشة الرئيسية", if (isHome) "tvhome هي الشاشة الرئيسية" else "شاشة أخرى صارت الرئيسية", if (isHome) Level.Ok else Level.Broken, fixable = true)

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        out += HealthItem("alarms", "توقيت الأذان الدقيق", if (exact) "مفعّل" else "غير مسموح: قد يتأخر الأذان حتى دقيقة والشاشة في وضع الاستعداد", if (exact) Level.Ok else Level.Warn, fixable = true)

        val mic = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        out += HealthItem("mic", "مايك المساعد", if (mic) "مسموح" else "غير مسموح: الأوامر الصوتية لا تعمل", if (mic) Level.Ok else Level.Broken, fixable = true)

        val saverOk = Screensaver.current(context) == Screensaver.componentFor(context, p.screensaverMode) &&
            Settings.Secure.getInt(context.contentResolver, "screensaver_enabled", 0) == 1
        out += HealthItem("saver", "شاشة التوقف", if (saverOk) "مضبوطة" else "النظام يستخدم شاشة توقف أخرى", if (saverOk) Level.Ok else Level.Warn, fixable = true)

        val keys = listOf("gemini" to BuildConfig.GEMINI_API_KEY, "xpl" to BuildConfig.EXPLABS_API_KEY, "anthropic" to BuildConfig.ANTHROPIC_API_KEY)
        if (keys.none { it.second.isNotBlank() }) {
            out += HealthItem("ai", "المساعد الذكي", "لا يوجد أي مفتاح API", Level.Broken, fixable = false)
        } else {
            val rejected = keys.filter { it.second.isNotBlank() }.mapNotNull { (id, _) -> failing(context, "ai.$id")?.let { id } }
            val usable = keys.count { it.second.isNotBlank() } - rejected.size
            out += when {
                rejected.isEmpty() -> HealthItem("ai", "المساعد الذكي", "يعمل", Level.Ok, false)
                usable > 0 -> HealthItem("ai", "المساعد الذكي", "مفتاح ${rejected.joinToString("، ") { name(it) }} مرفوض — يعمل بالمزوّدات الأخرى", Level.Warn, false)
                else -> HealthItem("ai", "المساعد الذكي", "كل المفاتيح مرفوضة", Level.Broken, false)
            }
        }
        keys.filter { it.second.isNotBlank() }.forEach { (id, _) ->
            failing(context, "quota.$id")?.let { out += HealthItem("quota.$id", "حصة ${name(id)}", "الحصة المجانية اليومية انتهت — المساعد يستخدم المزوّد التالي حتى تتجدد", Level.Warn, false) }
        }
        failing(context, "tts")?.let { out += HealthItem("tts", "صوت المساعد", "الصوت السحابي لا يعمل — يُستخدم صوت التلفزيون: $it", Level.Warn, false) }
        listOf("sports" to "المباريات", "youtube" to "جديد يوتيوب", "tmdb" to "الأفلام والمسلسلات", "weather" to "الطقس").forEach { (k, label) ->
            failing(context, "feed.$k", FEED_GRACE_MS)?.let { out += HealthItem("feed.$k", label, "البيانات لا تتحدّث منذ أكثر من ٣ ساعات${if (it.isNotBlank()) " ($it)" else ""}", Level.Warn, false) }
        }
        if (!canWriteSecure(context)) {
            out += HealthItem("secure", "صلاحية الإصلاح التلقائي", "غير ممنوحة: تحتاج إعادة منح من الكمبيوتر (adb)", Level.Warn, false)
        }
        return out
    }

    private fun name(id: String) = when (id) { "xpl" -> "Experiential"; "anthropic" -> "Anthropic"; else -> "Gemini" }

    /** Re-enables the system service by itself (no screen involved). True when something was repaired. */
    fun autoFix(context: Context): Boolean {
        var fixed = false
        if (!serviceEnabled(context) && canWriteSecure(context)) fixed = enableService(context) || fixed
        val p = LauncherPrefs.get(context).state.value
        if (canWriteSecure(context) && Screensaver.current(context) != Screensaver.componentFor(context, p.screensaverMode)) {
            Screensaver.apply(context)
        }
        return fixed
    }

    private fun enableService(context: Context): Boolean = runCatching {
        val cr = context.contentResolver
        val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').filter { it.isNotBlank() && ComponentName.unflattenFromString(it) != service }
        // Removing and re-adding makes the system bind it again even if it was listed but dead.
        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, current.joinToString(":"))
        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (current + service.flattenToString()).joinToString(":"))
        Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
    }.getOrDefault(false)

    /** Repairs [id] directly, or returns the system screen that can. */
    fun fix(context: Context, id: String): Intent? = when (id) {
        "service" -> if (canWriteSecure(context) && enableService(context)) null else Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        "saver" -> if (Screensaver.apply(context)) null else Intent(Settings.ACTION_DREAM_SETTINGS)
        "alarms" -> Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
        "launcher" -> Intent(Settings.ACTION_HOME_SETTINGS)
        else -> null
    }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
