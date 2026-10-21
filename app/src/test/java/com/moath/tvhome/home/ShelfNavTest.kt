package com.moath.tvhome.home

import com.moath.tvhome.Dir
import com.moath.tvhome.home.ShelfNav.MORE_INFO
import com.moath.tvhome.home.ShelfNav.PLAY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShelfNavTest {
    @Test fun `right from play moves to more info on the same page`() =
        assertEquals(ShelfCursor(2, MORE_INFO), ShelfNav.move(ShelfCursor(2, PLAY), 8, Dir.Right))

    @Test fun `right from more info pages forward and keeps the button`() =
        assertEquals(ShelfCursor(3, MORE_INFO), ShelfNav.move(ShelfCursor(2, MORE_INFO), 8, Dir.Right))

    @Test fun `left from play pages back`() =
        assertEquals(ShelfCursor(1, PLAY), ShelfNav.move(ShelfCursor(2, PLAY), 8, Dir.Left))

    @Test fun `left from more info returns to play`() =
        assertEquals(ShelfCursor(2, PLAY), ShelfNav.move(ShelfCursor(2, MORE_INFO), 8, Dir.Left))

    @Test fun `edges nudge`() {
        assertNull(ShelfNav.move(ShelfCursor(0, PLAY), 8, Dir.Left))
        assertNull(ShelfNav.move(ShelfCursor(7, MORE_INFO), 8, Dir.Right))
        assertNull(ShelfNav.move(ShelfCursor(3, PLAY), 8, Dir.Up))
    }

    @Test fun `provider ids map to the apps that play them`() {
        assertNull(Providers.packageFor(8))
        assertEquals("com.amazon.amazonvideo.livingroom", Providers.packageFor(119))
        assertNull(Providers.packageFor(-1))
    }
}
