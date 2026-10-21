package com.moath.tvhome.system

import org.junit.Assert.assertEquals
import org.junit.Test

class OrderRecentsTest {
    private val apps = listOf("a", "b", "c", "d")
    private fun order(usage: List<Usage>?, session: List<Usage> = emptyList(), dismissed: Map<String, Long> = emptyMap()) =
        orderRecents(apps, { it }, usage, session, dismissed)

    @Test
    fun `usage access orders by last use and drops unused apps`() {
        val usage = listOf(Usage("b", 10), Usage("d", 30), Usage("zz", 99))
        assertEquals(listOf("d", "b"), order(usage))
    }

    @Test
    fun `session sightings beat stale usage stats`() {
        val usage = listOf(Usage("b", 10), Usage("d", 30))
        assertEquals(listOf("b", "d"), order(usage, session = listOf(Usage("b", 50))))
    }

    @Test
    fun `without usage access only session apps get recency and the rest keep launcher order`() {
        assertEquals(listOf("c", "a", "b", "d"), order(null, session = listOf(Usage("c", 5))))
    }

    @Test
    fun `dismissed apps stay hidden until used again`() {
        val usage = listOf(Usage("b", 10), Usage("d", 30))
        assertEquals(listOf("b"), order(usage, dismissed = mapOf("d" to 40)))
        assertEquals(listOf("d", "b"), order(usage + Usage("d", 41), dismissed = mapOf("d" to 40)))
    }
}
