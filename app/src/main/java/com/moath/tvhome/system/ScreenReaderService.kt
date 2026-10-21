package com.moath.tvhome.system

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.moath.tvhome.BuildConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Reads other apps' screens. Never left on: while any service can read window content, every Compose
 * app on the TV builds accessibility trees each frame (Home measured 45% janky frames). [ScreenAccess]
 * switches it on for a task and off a few seconds after the last one.
 */
class ScreenReaderService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Google's panel changed: wake whoever is reading it now, instead of polling the tree.
        if (event?.packageName == GOOGLE) katnissChanged.trySend(Unit)
    }
    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** The visible text of the frontmost app window that isn't ours. */
    fun foregroundText(limit: Int = 1500): Pair<String?, String> {
        val win = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .firstOrNull { it.root?.packageName?.toString() != packageName }
        val root = win?.root ?: rootInActiveWindow
        val out = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 30 || out.length > limit) return
            listOfNotNull(n.text, n.contentDescription).map { it.toString().trim() }.filter { it.isNotEmpty() }.forEach { out.append(it).append(" | ") }
            for (i in 0 until n.childCount) walk(runCatching { n.getChild(i) }.getOrNull(), depth + 1)
        }
        runCatching { walk(root, 0) }
        return root?.packageName?.toString() to out.toString().take(limit)
    }

    companion object {
        private const val TAG = "ScreenReader"
        private const val GOOGLE = "com.google.android.katniss"
        @Volatile var instance: ScreenReaderService? = null
            private set

        /** Something on Google's assistant panel changed (conflated: many changes are one wake-up). */
        val katnissChanged = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    }
}

/** Switches [ScreenReaderService] on for the duration of a task (WRITE_SECURE_SETTINGS, granted by adb). */
object ScreenAccess {
    private const val TAG = "ScreenAccess"
    private const val LINGER_MS = 8_000L
    private val main = Handler(Looper.getMainLooper())
    private var users = 0
    private val reader get() = ComponentName(BuildConfig.APPLICATION_ID, ScreenReaderService::class.java.name)

    private fun setEnabled(context: Context, on: Boolean) {
        runCatching {
            val cr = context.contentResolver
            val list = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                .split(':').filter { it.isNotBlank() && ComponentName.unflattenFromString(it) != reader }
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (if (on) list + reader.flattenToString() else list).joinToString(":"))
        }.onFailure { Log.w(TAG, "cannot switch reader", it) }
    }

    @Volatile private var disabledAt = 0L
    private var pendingOff: Runnable? = null

    /** Runs [block] with the reader connected (null when it couldn't start within a few seconds). */
    suspend fun <T> use(context: Context, block: suspend (ScreenReaderService?) -> T): T {
        val app = context.applicationContext
        users++
        pendingOff?.let { main.removeCallbacks(it) }
        // A switch-off issued a moment ago is still tearing the service down: let it finish, then start afresh.
        if (SystemClock.uptimeMillis() - disabledAt < 3_000) {
            withTimeoutOrNull(2_000) { while (ScreenReaderService.instance != null) delay(50) }
        }
        if (ScreenReaderService.instance == null) setEnabled(app, true)
        val svc = withTimeoutOrNull(4_000) { while (ScreenReaderService.instance == null) delay(100); ScreenReaderService.instance }
        try {
            return block(svc)
        } finally {
            users--
            if (users == 0) {
                val off = Runnable { if (users == 0) { disabledAt = SystemClock.uptimeMillis(); setEnabled(app, false) } }
                pendingOff = off
                main.postDelayed(off, LINGER_MS)
            }
        }
    }

    /** At start-up: a reader left on by a crash is switched off. */
    fun reset(context: Context) = setEnabled(context.applicationContext, false)
}
