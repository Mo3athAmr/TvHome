package com.moath.tvhome.aerial

import kotlin.random.Random

/** One Apple Aerial clip. Captions are already resolved to display text. */
data class AerialClip(
    val id: String,
    /** Apple's accessibilityLabel, e.g. "Los Angeles". Used when a clip has no caption. */
    val label: String,
    /** 1080p H.264 SDR — the safe choice for low-end TV boxes. */
    val url1080: String,
    /** 4K HEVC SDR, only used when the user opted into 4K. */
    val url4k: String?,
    /** Points of interest sorted by start time. */
    val pois: List<AerialPoi>,
) {
    fun url(prefer4k: Boolean): String = if (prefer4k && !url4k.isNullOrBlank()) url4k else url1080

    /** The caption that applies at [positionSec], or null before the first one. */
    fun poiAt(positionSec: Long): AerialPoi? = pois.lastOrNull { it.atSec <= positionSec }

    /** Caption text at [positionSec]; falls back to the location name. */
    fun captionAt(positionSec: Long): String = poiAt(positionSec)?.text ?: label
}

data class AerialPoi(val atSec: Long, val text: String)

/**
 * Shuffled deck over [size] items: every item plays once per cycle and the same item never plays
 * twice in a row, including across a reshuffle.
 */
class AerialShuffle(private val size: Int, private val random: Random = Random.Default) {
    private val deck = ArrayDeque<Int>()
    private var last = -1

    init {
        require(size > 0) { "AerialShuffle needs at least one item" }
    }

    /** The index [next] will return, without consuming it. */
    fun peek(): Int {
        if (deck.isEmpty()) refill()
        return deck.first()
    }

    fun next(): Int {
        val index = peek()
        deck.removeFirst()
        last = index
        return index
    }

    private fun refill() {
        val order = (0 until size).shuffled(random).toMutableList()
        if (size > 1 && order.first() == last) {
            val swapWith = 1 + random.nextInt(size - 1)
            order[0] = order[swapWith].also { order[swapWith] = order[0] }
        }
        deck.addAll(order)
    }
}
