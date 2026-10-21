package com.moath.tvhome

enum class Dir(val dx: Int, val dy: Int) { Left(-1, 0), Right(1, 0), Up(0, -1), Down(0, 1) }

/** tvOS grid movement. Returns the next index, or null at an edge (the caller plays the nudge). */
object GridNav {
    const val COLUMNS = 5

    fun move(index: Int, count: Int, dir: Dir): Int? {
        if (count <= 0) return null
        val col = index % COLUMNS
        val row = index / COLUMNS
        val lastRow = (count - 1) / COLUMNS
        return when (dir) {
            Dir.Left -> if (col > 0) index - 1 else null
            Dir.Right -> if (col < COLUMNS - 1 && index + 1 < count) index + 1 else null
            Dir.Up -> if (row > 0) index - COLUMNS else null
            Dir.Down -> when {
                index + COLUMNS < count -> index + COLUMNS
                row < lastRow -> count - 1
                else -> null
            }
        }
    }

    fun <T> reorder(items: List<T>, from: Int, to: Int): List<T> =
        items.toMutableList().apply { add(to, removeAt(from)) }.toList()
}
