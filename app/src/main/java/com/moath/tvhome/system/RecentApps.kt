package com.moath.tvhome.system

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import com.moath.tvhome.AppEntry

/** A card in the App Switcher. */
sealed interface SwitcherCard {
    val key: String

    /** tvOS keeps Home at the far left; [preview] is the real app row it miniaturises. */
    data class Home(val preview: List<AppEntry>) : SwitcherCard {
        override val key: String get() = HOME_KEY
    }

    data class App(val entry: AppEntry) : SwitcherCard {
        override val key: String get() = entry.key
    }

    companion object {
        const val HOME_KEY = "switcher:home"
    }
}

/** A package and the last time it was in the foreground (epoch ms). */
data class Usage(val packageName: String, val lastUsedAt: Long)

/**
 * Orders the switcher's app cards. With [usage] (UsageStatsManager) the order is real recency;
 * without it ([usage] == null) only apps actually seen in the foreground this session ([session])
 * get recency, and the rest follow the launcher's own order — never invented timestamps.
 * Apps the user swiped away stay hidden until they are used again after [dismissedAt].
 */
fun <T> orderRecents(
    apps: List<T>,
    packageOf: (T) -> String,
    usage: List<Usage>?,
    session: List<Usage>,
    dismissedAt: Map<String, Long>,
): List<T> {
    val byPackage = LinkedHashMap<String, T>()
    for (app in apps) byPackage.putIfAbsent(packageOf(app), app)
    val latest = HashMap<String, Long>()
    for (u in session + usage.orEmpty()) {
        if (u.packageName in byPackage) latest[u.packageName] = maxOf(latest[u.packageName] ?: 0L, u.lastUsedAt)
    }
    val recent = latest.entries
        .filter { (pkg, usedAt) -> (dismissedAt[pkg] ?: Long.MIN_VALUE) < usedAt }
        .sortedByDescending { it.value }
        .mapNotNull { byPackage[it.key] }
    if (usage != null) return recent
    val rest = byPackage.filterKeys { it !in latest && it !in dismissedAt }.values
    return recent + rest
}

/** Reads foreground recency from UsageStatsManager when the user granted usage access. */
class UsageSource(private val context: Context) {

    fun hasAccess(): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Null when usage access is missing, so callers can tell "no data" from "no recent apps". */
    fun recent(now: Long = System.currentTimeMillis()): List<Usage>? {
        if (!hasAccess()) return null
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val since = now - WINDOW_MS
        return runCatching {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, since, now)
                .filter { it.lastTimeUsed >= since && it.totalTimeInForeground > 0 }
                .map { Usage(it.packageName, it.lastTimeUsed) }
        }.onFailure { Log.w(TAG, "queryUsageStats failed", it) }.getOrNull()
    }

    /** Every package that can act as a launcher — the Home card stands for all of them. */
    fun homePackages(): Set<String> =
        context.packageManager
            .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }
            .toSet() + context.packageName

    private companion object {
        const val TAG = "SystemUi"
        const val WINDOW_MS = 3L * 24 * 60 * 60 * 1000
    }
}
