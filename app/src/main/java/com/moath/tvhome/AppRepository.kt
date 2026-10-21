package com.moath.tvhome

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.util.Log
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import com.moath.tvhome.search.SearchActivity
import com.moath.tvhome.settings.SettingsActivity

class AppRepository(private val context: Context) {
    private val pm: PackageManager = context.packageManager

    fun load(): List<AppEntry> {
        val seen = HashSet<String>()
        val entries = ArrayList<AppEntry>()
        val candidates = query(Intent.CATEGORY_LEANBACK_LAUNCHER).map { it to true } +
            query(Intent.CATEGORY_LAUNCHER).map { it to false }
        for ((info, isLeanback) in candidates) {
            val pkg = info.activityInfo.packageName
            if (pkg == context.packageName || !seen.add(pkg)) continue
            runCatching { build(info, isLeanback) }
                .onSuccess { entries += it }
                .onFailure { Log.w(TAG, "Skipping $pkg", it) }
        }
        settingsEntry()?.let { entries += it }
        searchEntry()?.let { entries += it }
        return entries
    }

    private fun query(category: String): List<ResolveInfo> =
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)

    private fun build(info: ResolveInfo, isLeanback: Boolean): AppEntry {
        val activity = info.activityInfo
        val app = activity.applicationInfo
        val banner = if (isLeanback) activity.loadBanner(pm) ?: app.loadBanner(pm) else null
        val tile = if (banner != null) {
            Bitmaps.render(banner, BANNER_W, BANNER_H)
        } else {
            val iconBmp = Bitmaps.render(info.loadIcon(pm), ICON_PX, ICON_PX)
            Bitmaps.iconPlate(info.loadIcon(pm), Bitmaps.averageColor(iconBmp))
        }
        val category = Intent.CATEGORY_LEANBACK_LAUNCHER.takeIf { isLeanback } ?: Intent.CATEGORY_LAUNCHER
        val launch = Intent(Intent.ACTION_MAIN)
            .addCategory(category)
            .setClassName(activity.packageName, activity.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return AppEntry(
            key = activity.packageName,
            packageName = activity.packageName,
            label = AppNames.arabic(activity.packageName) ?: info.loadLabel(pm).toString(),
            category = ApplicationInfo.getCategoryTitle(context, app.category)?.toString(),
            tile = tile.asImageBitmap(),
            backdrop = Bitmaps.blurred(tile).asImageBitmap(),
            launchIntent = launch,
            isRemovable = (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0,
        )
    }

    private fun searchEntry(): AppEntry? {
        val glass = ContextCompat.getDrawable(context, R.drawable.ic_search) ?: return null
        val plate = Bitmaps.searchPlate(glass)
        return AppEntry(
            key = SEARCH_KEY,
            packageName = SEARCH_KEY,
            label = "بحث",
            category = null,
            tile = plate.asImageBitmap(),
            backdrop = Bitmaps.blurred(plate).asImageBitmap(),
            launchIntent = Intent(context, SearchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            isRemovable = false,
            isPseudo = true,
        )
    }

    private fun settingsEntry(): AppEntry? {
        val intent = Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val gear = ContextCompat.getDrawable(context, R.drawable.ic_gear) ?: return null
        val plate = Bitmaps.settingsPlate(gear)
        return AppEntry(
            key = SETTINGS_KEY,
            packageName = SETTINGS_KEY,
            label = "الإعدادات",
            category = null,
            tile = plate.asImageBitmap(),
            backdrop = Bitmaps.blurred(plate).asImageBitmap(),
            launchIntent = intent,
            isRemovable = false,
            isPseudo = true,
        )
    }

    companion object {
        const val SETTINGS_KEY = "tvhome:settings"
        const val SEARCH_KEY = "tvhome:search"
        private const val TAG = "TvHome"
        private const val BANNER_W = 640
        private const val BANNER_H = 360
        private const val ICON_PX = 192
    }
}

/** Arabic display names for well-known apps whose own label is English-only on this box. */
private object AppNames {
    private val names = mapOf(
        "com.google.android.youtube.tv" to "يوتيوب",
        "com.android.vending" to "متجر Play",
        "com.stremio.one" to "ستريميو",
        "com.tcl.browser" to "متصفح TCL",
        "com.esaba.downloader" to "المُنزِّل",
        "dev.dworks.apps.anexplorer" to "مستكشف الملفات",
        "com.google.android.videos" to "Google TV",
        "com.google.android.play.games" to "ألعاب Play",
        "com.google.android.youtube.tvmusic" to "يوتيوب ميوزك",
        "com.yodo1.crossyroad" to "كروسي رود",
    )

    fun arabic(pkg: String): String? = names[pkg]
}
