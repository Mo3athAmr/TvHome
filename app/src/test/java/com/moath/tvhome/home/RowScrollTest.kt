package com.moath.tvhome.home

import org.junit.Assert.assertEquals
import org.junit.Test

class RowScrollTest {
    @Test fun `focus inside the visible window does not scroll`() =
        assertEquals(0, RowScroll.firstVisible(current = 0, focus = 3, count = 20, columns = 4))

    @Test fun `stepping past the right edge scrolls by one card`() =
        assertEquals(1, RowScroll.firstVisible(current = 0, focus = 4, count = 20, columns = 4))

    @Test fun `stepping past the left edge brings the card to the front`() =
        assertEquals(2, RowScroll.firstVisible(current = 3, focus = 2, count = 20, columns = 4))

    @Test fun `the row never scrolls past its last card`() =
        assertEquals(16, RowScroll.firstVisible(current = 16, focus = 19, count = 20, columns = 4))

    @Test fun `short rows never scroll`() =
        assertEquals(0, RowScroll.firstVisible(current = 0, focus = 2, count = 3, columns = 5))

    @Test fun `hero and bar sit at the top`() =
        assertEquals(0f, RowScroll.pageScroll(Zone.HERO, 466f, listOf(180f, 200f), 34f, 540f))

    @Test fun `a row scrolls up to the row line`() =
        assertEquals(432f, RowScroll.pageScroll(0, 466f, listOf(180f, 200f, 200f), 34f, 540f))

    @Test fun `the last row stops where the content ends`() =
        assertEquals(506f, RowScroll.pageScroll(2, 466f, listOf(180f, 200f, 200f), 34f, 540f))
}
