package com.moath.tvhome.system

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.moath.tvhome.sports.CasperFlix
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where a broadcaster lives in Casper Flix: its category row, and the channel row inside it (null = stop at the category). */
data class CasperTarget(val category: String, val channel: String?)

/**
 * Opens Casper Flix on a match's channel. Casper has no deep links, so this walks its own screens for a
 * few seconds after the owner asked: Home → «القنوات» → OK (shows the list) → the category (e.g.
 * "Bein Sports HD") → the channel ("Bein Sports HD 1"). The screen is read through the on-demand
 * [ScreenReaderService]; anything unexpected and it simply stops where it is.
 */
class CasperTuner(private val context: Context) {
    private val scope = MainScope()
    private var job: Job? = null

    fun tune(tvChannel: String?) {
        stop()
        val launch = CasperFlix.launchIntent(context) ?: return
        val target = tvChannel?.let(::targetFor)
        Log.i(TAG, "tune '$tvChannel' → $target")
        context.startActivity(Intent(launch).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        if (target == null) return // No known channel: Casper's own home is the best we can do.
        job = scope.launch {
            ScreenAccess.use(context) { reader ->
                if (reader == null) { Log.w(TAG, "no screen reader"); return@use }
                walk(reader, target)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun walk(reader: ScreenReaderService, t: CasperTarget) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        var inChannels = false
        var scrolls = 0
        var lastOk = 0L
        var clickedLive = false
        while (SystemClock.uptimeMillis() < deadline) {
            delay(STEP_MS)
            val root = runCatching { reader.rootInActiveWindow }.getOrNull() ?: continue
            if (root.packageName != CasperFlix.PACKAGE) continue
            val packages = root.findAccessibilityNodeInfosByViewId(id("rv_packages")).firstOrNull()
            val channels = root.findAccessibilityNodeInfosByViewId(id("rv_channels")).firstOrNull()
            when {
                packages != null && channels != null && !inChannels -> {
                    val row = rowWithText(packages) { it.equals(t.category, true) || it.startsWith(t.category, true) }
                    if (row != null) {
                        row.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (t.channel == null) { Log.i(TAG, "category opened"); return }
                        inChannels = true
                        scrolls = 0
                    } else if (scrolls++ < MAX_SCROLLS) packages.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                }
                channels != null && inChannels -> {
                    val row = rowWithText(channels) { it.equals(t.channel, true) }
                    if (row != null) {
                        row.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Log.i(TAG, "tuned to ${t.channel}")
                        return
                    } else if (scrolls++ < MAX_SCROLLS) channels.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                }
                root.findAccessibilityNodeInfosByViewId(id("surface_video")).isNotEmpty() -> {
                    // The live player with the list hidden: only OK shows it (this TV has no touchscreen).
                    if (SystemClock.uptimeMillis() - lastOk > 3_000) {
                        lastOk = SystemClock.uptimeMillis()
                        LocalAdb.key(context, KeyEvent.KEYCODE_DPAD_CENTER)
                    }
                }
                !clickedLive -> root.findAccessibilityNodeInfosByViewId(id("lil_live")).firstOrNull()?.let {
                    clickedLive = it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            }
        }
        Log.i(TAG, "gave up")
    }

    private fun id(name: String) = "${CasperFlix.PACKAGE}:id/$name"

    /** The clickable row whose channel-name text satisfies [match], inside [list]. */
    private fun rowWithText(list: AccessibilityNodeInfo, match: (String) -> Boolean): AccessibilityNodeInfo? {
        val name = list.findAccessibilityNodeInfosByViewId(id("tv_channel_name"))
            .firstOrNull { n -> n.text?.toString()?.substringBefore('|')?.trim()?.let(match) == true } ?: return null
        var n: AccessibilityNodeInfo? = name
        while (n != null && !n.isClickable) n = n.parent
        return n
    }

    companion object {
        private const val TAG = "CasperTuner"
        private const val STEP_MS = 600L
        /** Long enough for the first-time "Allow debugging?" prompt to be answered. */
        private const val TIMEOUT_MS = 40_000L
        private const val MAX_SCROLLS = 6

        /** FilGoal's channel name → Casper's rows. Pure, for tests. */
        fun targetFor(tv: String): CasperTarget? {
            val s = tv.lowercase()
            val n = Regex("(\\d+)").find(s)?.value
            return when {
                "bein" in s && "xtra" in s && n != null -> CasperTarget("Bein Sports HD", "Bein Xtra HD $n")
                "bein" in s && "english" in s && n != null -> CasperTarget("Bein Sports HD", "Bein Sports English HD $n")
                "bein" in s && "french" in s && n != null -> CasperTarget("Bein Sports HD", "Bein Sports French HD $n")
                "bein" in s && "news" in s -> CasperTarget("Bein Sports HD", "Bein Sport News")
                "bein" in s && n != null -> CasperTarget("Bein Sports HD", "Bein Sports HD $n")
                "bein" in s -> CasperTarget("Bein Sports HD", null)
                "ssc" in s || "thmanyah" in s || "ثمانية" in tv -> CasperTarget("Thmanyah", null)
                "ad sport" in s || "abu dhabi" in s || "أبوظبي" in tv -> CasperTarget("StarzPlay AD Sport", null)
                "shahid" in s || "شاهد" in tv -> CasperTarget("Shahid Sport", null)
                "kass" in s || "الكأس" in tv -> CasperTarget("Al Kass Sports", null)
                else -> null
            }
        }
    }
}
