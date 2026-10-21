package com.moath.tvhome.system

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.moath.tvhome.assistant.StremioFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * After the deep link opened an episode's page, picks a stream so it actually plays. The page lists
 * streams in a sideways list: some are "Subscription" shortcuts (Netflix, Crunchyroll…) that don't help,
 * the Torrentio ones are further along — so the list is scrolled both ways until good ones appear, then
 * the best (1080p before 720p before 4K, for a box this size) is opened.
 */
class StremioTuner(private val context: Context) {
    private val scope = MainScope()
    private var job: Job? = null

    /** [link]: the deep link just opened, re-sent once if Stremio is stuck on its loading screen. */
    fun pick(link: String? = null) {
        job?.cancel()
        job = scope.launch {
            // The screensaver's idle clock must not run out while we're doing the clicking for the user.
            launch { LocalAdb.key(context, KeyEvent.KEYCODE_UNKNOWN) }
            ScreenAccess.use(context) { reader ->
                if (reader == null) Log.w(TAG, "no screen reader") else walk(reader, link)
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    private data class Card(val node: AccessibilityNodeInfo, val text: String)

    private suspend fun walk(reader: ScreenReaderService, link: String?) {
        val start = SystemClock.uptimeMillis()
        val deadline = start + TIMEOUT_MS
        var retried = link == null
        var scrolls = 0
        var forward = true
        var scrollsSinceGood = 0
        while (SystemClock.uptimeMillis() < deadline) {
            delay(STEP_MS)
            val root = runCatching { reader.rootInActiveWindow }.getOrNull() ?: continue
            if (root.packageName != StremioFlow.PACKAGE) continue
            val cards = cards(root)
            if (cards.isEmpty()) {
                // Started cold, Stremio can sit on its loading logo for good with an episode link. Opened again
                // from behind (it is warm by now) it goes straight to the episode.
                if (!retried && SystemClock.uptimeMillis() - start > STUCK_MS) {
                    retried = true
                    Log.i(TAG, "stuck loading; opening the episode again")
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    delay(1_200)
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link)).setPackage(StremioFlow.PACKAGE).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                continue // the page is still loading
            }
            val good = cards.filter { isGood(it.text) }
            Log.d(TAG, "cards=${cards.size} good=${good.size}: " + cards.joinToString(" ¦ ") { it.text.take(40) })
            val best = good.maxByOrNull { score(it.text) }
            if (best != null) {
                // A well-seeded 1080p one is the target; otherwise look a little further before settling.
                if (isTarget(best.text) || scrollsSinceGood >= MAX_SCROLLS_AFTER_GOOD) return open(best)
                scrollsSinceGood++
            }
            // Move along the list: first one way, then back the other. Some pages don't take the scroll
            // action — there the remote's own Left/Right walks the cards, as a person would.
            val list = scrollable(cards.first().node)
            var moved = list?.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == true
            if (!moved && scrolls < MAX_KEY_STEPS) {
                moved = LocalAdb.key(context, if (forward) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
            }
            scrolls++
            if (!moved || scrolls >= MAX_SCROLLS) {
                if (forward) { forward = false; scrolls = 0 } else {
                    // Both ways done: take the best we can see now.
                    val fallback = good.maxByOrNull { score(it.text) }
                    if (fallback != null) open(fallback) else Log.i(TAG, "no good stream found")
                    return
                }
            }
        }
        Log.i(TAG, "gave up")
    }

    private fun open(card: Card) {
        Log.i(TAG, "opening stream: ${card.text}")
        if (!card.node.isVisibleToUser) card.node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        card.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** The stream cards: big clickable blocks in the lower half of the page (the chips above are small). */
    private fun cards(root: AccessibilityNodeInfo): List<Card> {
        val out = ArrayList<Card>()
        fun texts(n: AccessibilityNodeInfo?, into: MutableList<String>, depth: Int) {
            if (n == null || depth > 12) return
            n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(into::add)
            for (i in 0 until n.childCount) texts(runCatching { n.getChild(i) }.getOrNull(), into, depth + 1)
        }
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 32) return
            val r = Rect().also { n.getBoundsInScreen(it) }
            if (n.isClickable && r.height() >= MIN_CARD_H && r.top >= MIN_CARD_TOP) {
                val parts = ArrayList<String>().also { texts(n, it, 0) }
                if (parts.isNotEmpty()) out += Card(n, parts.joinToString(" ‖ "))
                return
            }
            for (i in 0 until n.childCount) walk(runCatching { n.getChild(i) }.getOrNull(), depth + 1)
        }
        walk(root, 0)
        return out
    }

    private fun scrollable(from: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = from.parent
        while (n != null && !n.isScrollable) n = n.parent
        return n
    }

    companion object {
        private const val TAG = "StremioTuner"
        private const val STEP_MS = 600L
        private const val TIMEOUT_MS = 60_000L
        private const val STUCK_MS = 14_000L
        private const val MAX_SCROLLS = 12
        private const val MAX_SCROLLS_AFTER_GOOD = 3
        private const val MIN_CARD_H = 200
        private const val MIN_CARD_TOP = 540
        private const val RANK_1080 = 3
        private const val MAX_KEY_STEPS = 10

        /** Not a shortcut into another subscription app. Pure, for tests. */
        fun isGood(text: String): Boolean {
            val t = text.lowercase()
            return text.isNotBlank() && !t.contains("subscription") && !t.contains("اشتراك")
        }

        /** Seeders ("👤 1012") and size in GB ("💾 1.35 GB"), when the card shows them. */
        fun seeders(text: String): Int? = Regex("""👤\s*(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()

        fun sizeGb(text: String): Double? = Regex("""💾\s*([\d.]+)\s*(GB|MB)""", RegexOption.IGNORE_CASE).find(text)?.let {
            val v = it.groupValues[1].toDoubleOrNull() ?: return null
            if (it.groupValues[2].equals("MB", true)) v / 1024 else v
        }

        /** Quality first, then how well it is seeded; huge files and near-dead torrents lose. Pure, for tests. */
        fun score(text: String): Int {
            val seeds = seeders(text)
            val size = sizeGb(text) ?: 0.0
            return rank(text) * 1000 + (seeds ?: 50).coerceAtMost(500) -
                (if (size > 8) 600 else 0) - (if (seeds != null && seeds < 3) 800 else 0)
        }

        /** Good enough to open at once: 1080p, alive, not a 10 GB remux. */
        fun isTarget(text: String): Boolean =
            rank(text) >= RANK_1080 && (seeders(text) ?: 10) >= 10 && (sizeGb(text) ?: 0.0) <= 8

        /** 1080p best, then 720p, then anything else; 4K last (heavy for this box). Pure, for tests. */
        fun rank(text: String): Int {
            val t = text.lowercase()
            return when {
                "1080" in t -> RANK_1080
                "720" in t -> 2
                "2160" in t || "4k" in t -> 0
                else -> 1
            }
        }
    }
}
