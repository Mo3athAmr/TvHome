package com.moath.tvhome.ui

import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.moath.tvhome.design.TvColors
import com.moath.tvhome.design.TvDims
import com.moath.tvhome.design.TvMotion
import com.moath.tvhome.design.TvType
import com.moath.tvhome.design.TvType.emphasized
import com.moath.tvhome.design.TvType.overArt

/**
 * App-icon geometry per HIG › Layout (five-column grid), halved into dp (the box is 320 dpi, so
 * 1 dp = 2 px): 320 pt icons with 40 pt gutters exactly fill the 80 pt side safe area
 * (5·160 + 4·20 + 2·40 = 960 dp), rows ≥100 pt apart. Colours, type and springs come from the
 * shared design kit.
 */
object Dims {
    val TileW: Dp = TvDims.IconW
    val TileH: Dp = TvDims.IconH
    val Gap: Dp = TvDims.GridHSpacing

    /** The label sits inside the HIG's vertical spacing, clear of the focused icon's growth. */
    val LabelTop: Dp = 15.dp
    val Pitch: Dp = TvDims.IconH + TvDims.GridVSpacing

    val SheetButtonW: Dp = 300.dp
    val SheetButtonH: Dp = 40.dp

    fun tileX(col: Int): Dp = TvDims.SafeH + (TileW + Gap) * col
}

/**
 * Apple TV app (tvOS 26) home geometry, halved to dp. Every row follows the HIG grid inside the
 * 80 pt side safe area: 4 columns of 16:9 cards (410 pt), 5 of app icons (320 pt), 7 of 2:3
 * posters (217 pt), always 40 pt apart — so the cards peeking past either edge are symmetrical.
 */
object HomeDims {
    /** HIG tab bar: 68 pt tall, top edge 46 pt from the top of the screen. */
    val BarTop: Dp = 23.dp
    val BarH: Dp = 34.dp
    val BarItemH: Dp = 28.dp
    val BarItemPadH: Dp = 15.dp
    val BarGlyph: Dp = 15.dp

    /** The hero fills the screen but for the first row's peek (its title and the top of its cards). */
    val HeroH: Dp = 470.dp
    val HeroBottom: Dp = 30.dp
    val OverviewW: Dp = 430.dp
    val LogoW: Dp = 290.dp
    val LogoH: Dp = 88.dp

    /** Where a focused row's title sits once the page has scrolled into the rows. */
    val RowTop: Dp = 30.dp
    /** Apps-first layout (no hero, e.g. offline): rows start under the tab bar. */
    val AppsFirstTop: Dp = 96.dp

    val RowTitleH: Dp = 22.dp
    val RowTitleGap: Dp = 10.dp
    val CardLabelH: Dp = 32.dp
    val RowGap: Dp = 14.dp
    val Gap: Dp = TvDims.GridHSpacing

    val LandscapeW: Dp = 205.dp
    val LandscapeH: Dp = 115.dp
    val PosterW: Dp = 108.5.dp
    val PosterH: Dp = 163.dp
    val CardRadius: Dp = 8.dp

    val GridTop: Dp = 104.dp

    fun rowHeight(cardH: Dp): Dp = RowTitleH + RowTitleGap + cardH + CardLabelH + RowGap
}

object Shapes {
    val Card = RoundedCornerShape(HomeDims.CardRadius)
    val Tile = RoundedCornerShape(TvDims.IconCorner)
    val SheetButton = RoundedCornerShape(TvDims.Corner)
}

object Motion {
    const val FOCUS_SCALE = 1.16f
    const val EDIT_SCALE = 1.2f
    const val PRESS_SCALE = 1.07f

    fun <T> focus() = TvMotion.focus<T>()
    fun <T> move() = TvMotion.move<T>()
    fun <T> scroll() = spring<T>(dampingRatio = 0.92f, stiffness = 260f)
    const val HERO_AUTOPLAY_MS = 8_000L
    const val HERO_FADE_MS = 380
    const val TAB_OUT_MS = 110
    const val TAB_IN_MS = 200
}

object Palette {
    val Label = TvColors.Label
    val Hint = Color(0xB3FFFFFF)
    val ButtonIdle = TvColors.IdleFill
    val ButtonFocused = TvColors.FocusedFill
    val OnFocused = TvColors.FocusedLabel
    val Destructive = TvColors.Red
    val DestructiveOnFocused = Color(0xFFE5342A)
    val Scrim = Color(0xE0000000)
    val Dot = Color(0x59FFFFFF)
    /** Flat glass placeholder for artwork that hasn't arrived — never shimmer, never fake. */
    val Placeholder = Color(0x1FFFFFFF)
    /** The selected tab is the one opaque thing on the translucent bar. */
    val SelectedTab = Color(0xFFDCDCE0)
}

/** Every home-screen string uses a HIG tvOS text style unchanged; only colour/shadow vary. */
object Type {
    val ShelfAttribution = TvType.Caption1.copy(
        color = TvColors.Label,
        shadow = androidx.compose.ui.graphics.Shadow(androidx.compose.ui.graphics.Color(0xD9000000), androidx.compose.ui.geometry.Offset(0f, 1f), 8f),
    )
    val ShelfTitle = TvType.Title1.emphasized().overArt()
    val ShelfMeta = TvType.Caption1.copy(color = TvColors.Label).overArt()
    val ShelfOverview = TvType.Body.copy(color = Color(0xE0FFFFFF)).overArt()
    val ShelfButton = TvType.Body
    val TileLabel = TvType.Caption1.copy(color = TvColors.Label).overArt()
    val SheetTitle = TvType.Title3
    val SheetButton = TvType.Body
    val Hint = TvType.Caption2.copy(color = Palette.Hint).overArt()
    val RowTitle = TvType.Callout.copy(color = Color(0xE6FFFFFF)).overArt()
    val CardLabel = TvType.Caption1.copy(color = TvColors.Label).overArt()
    val BarLabel = TvType.Body
    val Clock = TvType.Body.copy(color = TvColors.Label).overArt()
    val Quiet = TvType.Callout.copy(color = TvColors.SecondaryLabel)
}
