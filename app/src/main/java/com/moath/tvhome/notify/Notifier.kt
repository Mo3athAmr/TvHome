package com.moath.tvhome.notify

import android.content.Context
import android.content.Intent
import com.moath.tvhome.prefs.LauncherPrefs

/** Kinds of notice; each can be switched off in Customize → Notifications. */
object NoticeKind {
    const val INFO = "info"
    const val ADHAN = "adhan"
    const val MATCH = "match"
    const val MATCH_SOON = "match_soon"
    const val ASSISTANT = "assistant"
}

/**
 * Posts a pop-up notice that slides up from the bottom of the screen, over any app. Callable from
 * any process: it is a broadcast that the always-on system process turns into the overlay.
 */
object Notifier {
    const val ACTION = "com.moath.tvhome.NOTICE"
    const val EXTRA_TITLE = "title"
    const val EXTRA_BODY = "body"
    const val EXTRA_KIND = "kind"
    const val EXTRA_DURATION = "duration"

    /** Whether the owner's settings let a notice of [kind] through. */
    fun allowed(context: Context, kind: String): Boolean {
        // The assistant's own replies were asked for; they always show.
        if (kind == NoticeKind.ASSISTANT) return true
        val p = LauncherPrefs.get(context).state.value
        if (!p.notifications) return false
        return when (kind) {
            NoticeKind.ADHAN -> p.notifyAdhan
            NoticeKind.MATCH -> p.notifyMatches
            NoticeKind.MATCH_SOON -> p.matchReminderMin > 0
            else -> true
        }
    }

    fun post(context: Context, title: String, body: String, kind: String = NoticeKind.INFO, durationMs: Long = 7_000) {
        if (!allowed(context, kind)) return
        context.sendBroadcast(
            Intent(ACTION).setPackage(context.packageName)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_BODY, body)
                .putExtra(EXTRA_KIND, kind)
                .putExtra(EXTRA_DURATION, durationMs),
        )
    }
}
