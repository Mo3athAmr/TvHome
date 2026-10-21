package com.moath.tvhome.home

import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Slots of the top bar in key order (the first is at the start edge — the right in Arabic): the
 * profile picture (opens Customize), Search, the four tabs, Settings.
 */
object Bar {
    const val AVATAR = 0
    const val SEARCH = 1
    const val SETTINGS = 6
    const val COUNT = 7

    fun slotOf(tab: Tab): Int = tab.ordinal + 2
    fun tabAt(slot: Int): Tab? = Tab.entries.getOrNull(slot - 2)
}

/** Vertical focus positions above the content rows (rows themselves are 0, 1, 2 …). */
object Zone {
    const val BAR = -2
    const val HERO = -1
}

/**
 * Focus and content of one tab. Each value is its own snapshot state so a move only invalidates
 * what reads it: a step along a row touches that row's column state and nothing else.
 */
@Stable
class TabPage(val tab: Tab) {
    var hero by mutableStateOf<List<HeroItem>>(emptyList())
    var rows by mutableStateOf<List<ContentRow>>(emptyList())
    /** True until the first hero load finishes (then the layout settles, even if empty). */
    var heroLoading by mutableStateOf(tab != Tab.Apps)
    var rowsLoading by mutableStateOf(tab != Tab.Apps)

    var row by mutableIntStateOf(Zone.BAR)
    var heroPage by mutableIntStateOf(0)
    var heroButton by mutableIntStateOf(ShelfNav.PLAY)
    /** Apps tab only: focused index in the app grid. */
    var gridFocus by mutableIntStateOf(0)
    /** Hero pages advanced automatically since the last key press (autoplay stops after a loop). */
    var autoAdvances by mutableIntStateOf(0)

    private val cols = HashMap<String, MutableIntState>()

    /** Focused column of one row, remembered per row like tvOS. */
    fun col(rowId: String): MutableIntState = cols.getOrPut(rowId) { mutableIntStateOf(0) }

    /** Whether the hero carousel occupies the top of the page (also while it is still loading). */
    val hasHero: Boolean get() = tab != Tab.Apps && (hero.isNotEmpty() || heroLoading)

    val heroItem: HeroItem? get() = hero.getOrNull(heroPage.coerceIn(0, (hero.size - 1).coerceAtLeast(0)))
}

/** Pure row/page geometry, kept free of Compose so it unit-tests. */
object RowScroll {
    /**
     * First fully visible card after focus moves to [focus], scrolling as little as possible
     * (tvOS keeps the focused card on screen; the row only moves when focus would leave it).
     */
    fun firstVisible(current: Int, focus: Int, count: Int, columns: Int): Int {
        val maxFirst = (count - columns).coerceAtLeast(0)
        val first = when {
            focus < current -> focus
            focus > current + columns - 1 -> focus - columns + 1
            else -> current
        }
        return first.coerceIn(0, maxFirst)
    }

    /**
     * Page scroll (dp) that brings row [row] to [rowTop] from the top of the screen. Rows start at
     * [contentTop]; [rowHeights] are each row's full pitch. Hero and bar positions scroll to 0.
     */
    fun pageScroll(row: Int, contentTop: Float, rowHeights: List<Float>, rowTop: Float, viewport: Float): Float {
        if (row < 0 || rowHeights.isEmpty()) return 0f
        val r = row.coerceAtMost(rowHeights.size - 1)
        var y = contentTop
        for (i in 0 until r) y += rowHeights[i]
        val maxScroll = (contentTop + rowHeights.sum() - viewport).coerceAtLeast(0f)
        return (y - rowTop).coerceAtMost(maxScroll).coerceAtLeast(0f)
    }
}
