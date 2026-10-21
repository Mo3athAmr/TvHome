package com.moath.tvhome.system

/**
 * Disambiguates the remote's HOME button into tvOS gestures:
 *  - hold ≥ [LONG_PRESS_MS]               → [Actions.onLongPress]   (Control Center)
 *  - two presses, second within [DOUBLE_PRESS_MS] of the first release → [Actions.onDoublePress] (App Switcher)
 *  - otherwise, [DOUBLE_PRESS_MS] after release → [Actions.onSinglePress] (normal Home)
 *
 * Pure state machine: time is only observed through [Timers], so it is unit-testable.
 */
class HomeKeyRouter(private val timers: Timers, private val actions: Actions) {

    interface Actions {
        fun onSinglePress()
        fun onDoublePress()
        fun onLongPress()
    }

    fun interface Cancelable { fun cancel() }

    interface Timers { fun schedule(delayMs: Long, block: () -> Unit): Cancelable }

    private var pressed = false
    private var longFired = false
    private var isSecondPress = false
    private var longTimer: Cancelable? = null
    private var singleTimer: Cancelable? = null

    /** True between a HOME down and its up: the up belongs to the router, not to an overlay. */
    val isTracking: Boolean get() = pressed

    /** [systemLongPress] is the platform's FLAG_LONG_PRESS on a repeat, honoured if it comes first. */
    fun onDown(repeatCount: Int, systemLongPress: Boolean) {
        if (repeatCount > 0 || pressed) {
            if (pressed && systemLongPress) fireLong()
            return
        }
        pressed = true
        longFired = false
        isSecondPress = singleTimer != null
        singleTimer?.cancel()
        singleTimer = null
        longTimer = timers.schedule(LONG_PRESS_MS) { fireLong() }
    }

    fun onUp(canceled: Boolean = false) {
        if (!pressed) return
        pressed = false
        longTimer?.cancel()
        longTimer = null
        val second = isSecondPress
        isSecondPress = false
        when {
            longFired || canceled -> Unit
            second -> actions.onDoublePress()
            else -> singleTimer = timers.schedule(DOUBLE_PRESS_MS) {
                singleTimer = null
                actions.onSinglePress()
            }
        }
    }

    /** Drops any half-finished gesture (service interrupted / torn down). */
    fun reset() {
        longTimer?.cancel()
        singleTimer?.cancel()
        longTimer = null
        singleTimer = null
        pressed = false
        longFired = false
        isSecondPress = false
    }

    private fun fireLong() {
        if (longFired || !pressed) return
        longFired = true
        longTimer?.cancel()
        longTimer = null
        actions.onLongPress()
    }

    companion object {
        const val LONG_PRESS_MS = 600L
        const val DOUBLE_PRESS_MS = 350L
    }
}
