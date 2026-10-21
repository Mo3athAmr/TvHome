package com.moath.tvhome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GridNavTest {
    @Test fun `right stops at the end of a row`() = assertNull(GridNav.move(4, 12, Dir.Right))
    @Test fun `right stops at the last item`() = assertNull(GridNav.move(5, 6, Dir.Right))
    @Test fun `left stops at the first column`() = assertNull(GridNav.move(5, 12, Dir.Left))
    @Test fun `up from the dock is an edge`() = assertNull(GridNav.move(2, 12, Dir.Up))
    @Test fun `down moves a full row`() = assertEquals(7, GridNav.move(2, 12, Dir.Down))
    @Test fun `down into a short row lands on its last item`() = assertEquals(5, GridNav.move(3, 6, Dir.Down))
    @Test fun `down from the last row is an edge`() = assertNull(GridNav.move(5, 6, Dir.Down))
    @Test fun `reorder moves without mutating`() {
        val src = listOf("a", "b", "c")
        assertEquals(listOf("b", "c", "a"), GridNav.reorder(src, 0, 2))
        assertEquals(listOf("a", "b", "c"), src)
    }
}
