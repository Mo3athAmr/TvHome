package com.moath.tvhome.sports

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/** The owner's sports app. It has no deep links into channels, so we can only open it. */
object CasperFlix {
    const val PACKAGE = "com.mbm_soft.casperflix"
    const val ACTION_TUNE = "com.moath.tvhome.CASPER_TUNE"
    const val EXTRA_CHANNEL = "channel"

    /**
     * Opens Casper on [channel] (e.g. "beIN SPORTS 1 HD"): the system service walks Casper's screens to it.
     * Without the service running, just opens the app.
     */
    fun watch(context: Context, channel: String?) {
        if (com.moath.tvhome.health.Health.serviceEnabled(context)) {
            context.sendBroadcast(Intent(ACTION_TUNE).setPackage(context.packageName).putExtra(EXTRA_CHANNEL, channel))
        } else {
            launchIntent(context)?.let { runCatching { context.startActivity(it) } }
        }
    }
    @Volatile private var icon: ImageBitmap? = null
    @Volatile private var checked = false

    fun launchIntent(context: Context): Intent? {
        val pm = context.packageManager
        return (pm.getLeanbackLaunchIntentForPackage(PACKAGE) ?: pm.getLaunchIntentForPackage(PACKAGE))
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** The app's icon, or null when it isn't installed. Cached for the process. */
    fun icon(context: Context): ImageBitmap? {
        if (!checked) {
            icon = runCatching { context.packageManager.getApplicationIcon(PACKAGE).toBitmap(64, 64).asImageBitmap() }.getOrNull()
            checked = true
        }
        return icon
    }
}
