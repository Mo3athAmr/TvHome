package com.moath.tvhome.system

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeKeyRouterTest {

    private class FakeTimers : HomeKeyRouter.Timers {
        var now = 0L
        private val pending = mutableListOf<Pair<Long, () -> Unit>>()
        override fun schedule(delayMs: Long, block: () -> Unit): HomeKeyRouter.Cancelable {
            val entry = (now + delayMs) to block
            pending += entry
            return HomeKeyRouter.Cancelable { pending.remove(entry) }
        }
        fun advance(ms: Long) {
            val target = now + ms
            while (true) {
                val next = pending.filter { it.first <= target }.minByOrNull { it.first } ?: break
                pending.remove(next)
                now = next.first
                next.second()
            }
            now = target
        }
    }

    private class Log : HomeKeyRouter.Actions {
        val events = mutableListOf<String>()
        override fun onSinglePress() { events += "single" }
        override fun onDoublePress() { events += "double" }
        override fun onLongPress() { events += "long" }
    }

    private val timers = FakeTimers()
    private val log = Log()
    private val router = HomeKeyRouter(timers, log)

    private fun tap(holdMs: Long = 80) {
        router.onDown(0, false)
        timers.advance(holdMs)
        router.onUp()
    }

    @Test
    fun `single press fires home after the double-press window`() {
        tap()
        timers.advance(HomeKeyRouter.DOUBLE_PRESS_MS - 1)
        assertEquals(emptyList<String>(), log.events)
        timers.advance(1)
        assertEquals(listOf("single"), log.events)
    }

    @Test
    fun `two quick presses open the switcher and never fire home`() {
        tap()
        timers.advance(150)
        tap(holdMs = 400) // second press may be held past the window — still a double
        timers.advance(1_000)
        assertEquals(listOf("double"), log.events)
    }

    @Test
    fun `holding fires long press once while held and swallows the release`() {
        router.onDown(0, false)
        timers.advance(HomeKeyRouter.LONG_PRESS_MS)
        assertEquals(listOf("long"), log.events)
        router.onDown(5, true)
        timers.advance(500)
        router.onUp()
        timers.advance(1_000)
        assertEquals(listOf("long"), log.events)
    }

    @Test
    fun `platform long-press flag fires before the timer`() {
        router.onDown(0, false)
        timers.advance(100)
        router.onDown(1, true)
        assertEquals(listOf("long"), log.events)
        router.onUp()
        timers.advance(1_000)
        assertEquals(listOf("long"), log.events)
    }

    @Test
    fun `tracking is true only between down and up`() {
        assertEquals(false, router.isTracking)
        router.onDown(0, false)
        assertEquals(true, router.isTracking)
        router.onUp()
        assertEquals(false, router.isTracking)
    }

    @Test
    fun `presses far apart are two singles`() {
        tap()
        timers.advance(1_000)
        tap()
        timers.advance(1_000)
        assertEquals(listOf("single", "single"), log.events)
    }
}
