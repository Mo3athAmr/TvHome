package com.moath.tvhome.home

import com.moath.tvhome.Dir

/** Where focus sits inside the focused Top Shelf: which page, and which of its two buttons. */
data class ShelfCursor(val page: Int, val button: Int = ShelfNav.PLAY)

/**
 * D-pad movement inside the carousel. tvOS pages with a swipe; on a D-pad, Left past Play and
 * Right past More Info page the carousel instead. Returns null at an edge (play the nudge).
 */
object ShelfNav {
    const val PLAY = 0
    const val MORE_INFO = 1

    fun move(c: ShelfCursor, pageCount: Int, dir: Dir): ShelfCursor? = when (dir) {
        Dir.Left -> when {
            c.button == MORE_INFO -> c.copy(button = PLAY)
            c.page > 0 -> c.copy(page = c.page - 1)
            else -> null
        }
        Dir.Right -> when {
            c.button == PLAY -> c.copy(button = MORE_INFO)
            c.page < pageCount - 1 -> c.copy(page = c.page + 1)
            else -> null
        }
        Dir.Up, Dir.Down -> null
    }
}
