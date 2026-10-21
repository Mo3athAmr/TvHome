package com.moath.tvhome.screensaver

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.moath.tvhome.dashboard.PrayerDashboardDream
import com.moath.tvhome.prefs.LauncherPrefs
import com.moath.tvhome.prefs.LauncherSettings
import java.io.File

/**
 * The two screen-saver modes. Photos is Google TV's own ambient mode (it alone may read the
 * linked account's Google Photos albums; Google closed that API to other apps in March 2025), with
 * our widgets drawn over it. Prayer is our dashboard over Aerial video or the owner's picture/video.
 */
object Screensaver {
    private const val TAG = "Screensaver"
    val BACKDROP = ComponentName("com.google.android.apps.tv.dreamx", "com.google.android.apps.tv.dreamx.service.Backdrop")
    private val SOMNAMBULATOR = ComponentName("com.android.systemui", "com.android.systemui.Somnambulator")
    /** Google TV's ambient settings: album, photo speed, clock (album choice lives here). */
    const val AMBIENT_SETTINGS = "com.google.android.tv.settings.ambient"
    val CUSTOM_FILES = listOf("screensaver.mp4", "screensaver.jpg", "screensaver.jpeg", "screensaver.png", "screensaver.webp")

    fun prayerComponent(context: Context) = ComponentName(context.packageName, PrayerDashboardDream::class.java.name)

    fun photosAvailable(context: Context): Boolean =
        runCatching { context.packageManager.getServiceInfo(BACKDROP, 0) }.isSuccess

    fun componentFor(context: Context, mode: String): ComponentName =
        if (mode == LauncherSettings.SAVER_PHOTOS && photosAvailable(context)) BACKDROP else prayerComponent(context)

    /** Makes the chosen mode the system screen saver (needs WRITE_SECURE_SETTINGS, granted by adb). */
    fun apply(context: Context, mode: String = LauncherPrefs.get(context).state.value.screensaverMode): Boolean = runCatching {
        val cr = context.contentResolver
        Settings.Secure.putString(cr, "screensaver_components", componentFor(context, mode).flattenToString()) &&
            Settings.Secure.putInt(cr, "screensaver_enabled", 1)
    }.onFailure { Log.w(TAG, "Cannot set screen saver", it) }.getOrDefault(false)

    fun current(context: Context): ComponentName? =
        Settings.Secure.getString(context.contentResolver, "screensaver_components")
            ?.split(',')?.firstNotNullOfOrNull { ComponentName.unflattenFromString(it.trim()) }

    /** The intent that starts the screen saver now (the system's own "start dreaming" activity). */
    fun startIntent(): Intent = Intent(Intent.ACTION_MAIN).setComponent(SOMNAMBULATOR).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun start(context: Context) {
        apply(context)
        runCatching { context.startActivity(startIntent()) }.onFailure { Log.w(TAG, "Cannot start screen saver", it) }
    }

    /** The owner's own background for prayer mode, pushed to the app's files folder. */
    fun customFile(context: Context): File? {
        val dir = context.getExternalFilesDir(null) ?: return null
        return CUSTOM_FILES.map { File(dir, it) }.firstOrNull { it.isFile && it.length() > 0 }
    }

    /** Opens Customize at the screen-saver section. */
    fun settingsIntent(context: Context): Intent =
        Intent(context, com.moath.tvhome.settings.SettingsActivity::class.java)
            .putExtra(com.moath.tvhome.settings.SettingsActivity.EXTRA_PAGE, "customize,customize.saver")
            .putExtra(com.moath.tvhome.settings.SettingsActivity.EXTRA_STANDALONE, false)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
