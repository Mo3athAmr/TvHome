package com.moath.tvhome.design

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moath.tvhome.R

/**
 * The shared tvOS design kit. Every surface in the app (home, settings, search, control center,
 * app switcher, screensaver) builds on these tokens — never hardcode colours, sizes or springs.
 *
 * Unit rule: the box is 1920×1080 @ 320 dpi, so 1 dp = 2 px, and a tvOS point at 1080p is 0.5 dp.
 * Every value below is the tvOS HIG number halved.
 */
object TvColors {
    // Dark appearance (tvOS default). Labels per UIKit's dark label colours.
    val Label = Color(0xFFFFFFFF)
    val SecondaryLabel = Color(0x99EBEBF5)
    val TertiaryLabel = Color(0x4DEBEBF5)
    val FocusedFill = Color(0xFFFFFFFF)
    val FocusedLabel = Color(0xFF000000)
    val FocusedSecondaryLabel = Color(0x993C3C43)
    val IdleFill = Color(0x1FFFFFFF)
    val Separator = Color(0x33FFFFFF)

    val Blue = Color(0xFF0A84FF)
    val Red = Color(0xFFFF453A)
    val Green = Color(0xFF30D158)
    val Orange = Color(0xFFFF9F0A)
    val Yellow = Color(0xFFFFD60A)

    /** Approximations of UIBlurEffect materials (API 28 has no RenderEffect blur). */
    val MaterialUltraThin = Color(0x8C1C1C1E)
    val MaterialThin = Color(0xB31C1C1E)
    val MaterialRegular = Color(0xD11C1C1E)
    val MaterialThick = Color(0xEB1C1C1E)
    val Scrim = Color(0xB3000000)

    /** Full-screen app background used by Settings / Search. */
    val AppBackground = Brush.verticalGradient(
        0f to Color(0xFF2A2A2E),
        1f to Color(0xFF0B0B0D),
    )
}

/**
 * The whole UI is Arabic, so every surface is laid out right-to-left. Compose mirrors layouts by
 * itself; this is for what it cannot mirror: D-pad Left/Right semantics and hand-computed x offsets.
 */
object UiDirection {
    val layout = androidx.compose.ui.unit.LayoutDirection.Rtl
    val isRtl: Boolean get() = layout == androidx.compose.ui.unit.LayoutDirection.Rtl

    /** +1 when the D-pad key moves toward the end of a row, -1 toward its start. */
    fun step(leftKey: Boolean): Int = if (leftKey == isRtl) 1 else -1

    /** Flips the sign of an x translation that was computed as if the layout were left-to-right. */
    fun mirror(x: Float): Float = if (isRtl) -x else x
}

object TvFonts {
    val Display = FontFamily(
        Font(R.font.plex_arabic_bold, FontWeight.Bold),
        Font(R.font.plex_arabic_semibold, FontWeight.SemiBold),
    )
    val Text = FontFamily(
        Font(R.font.plex_arabic_regular, FontWeight.Normal),
        Font(R.font.plex_arabic_medium, FontWeight.Medium),
        Font(R.font.plex_arabic_semibold, FontWeight.SemiBold),
        Font(R.font.plex_arabic_bold, FontWeight.Bold),
    )
}

/**
 * tvOS built-in text styles (HIG › Typography), halved to dp/sp: Title 1 76/96pt Medium → 38/48sp.
 * Weights are the HIG defaults (Medium; Subtitle 1 Regular); use [emphasized] for the HIG's
 * "emphasized weight" (Bold / Medium). Tracking follows the HIG tvOS tracking table.
 */
object TvType {
    private val shadow = Shadow(Color(0x59000000), Offset(0f, 2f), 10f)

    private fun style(family: FontFamily, weight: FontWeight, pt: Float, leadingPt: Float, trackingPt: Float, color: Color) =
        TextStyle(
            fontFamily = family,
            fontWeight = weight,
            fontSize = (pt / 2f).sp,
            lineHeight = (leadingPt / 2f).sp,
            letterSpacing = (trackingPt / 2f).sp,
            color = color,
        )

    val Title1 = style(TvFonts.Display, FontWeight.Medium, 76f, 96f, 0.07f, TvColors.Label)
    val Title2 = style(TvFonts.Display, FontWeight.Medium, 57f, 66f, 0.30f, TvColors.Label)
    val Title3 = style(TvFonts.Display, FontWeight.Medium, 48f, 56f, 0.35f, TvColors.Label)
    val Headline = style(TvFonts.Display, FontWeight.Medium, 38f, 46f, 0.37f, TvColors.Label)
    val Subtitle1 = style(TvFonts.Display, FontWeight.Normal, 38f, 46f, 0.37f, TvColors.Label)
    val Callout = style(TvFonts.Text, FontWeight.Medium, 31f, 38f, 0.39f, TvColors.Label)
    val Body = style(TvFonts.Text, FontWeight.Medium, 29f, 36f, 0.40f, TvColors.Label)
    val Caption1 = style(TvFonts.Text, FontWeight.Medium, 25f, 32f, 0.15f, TvColors.SecondaryLabel)
    val Caption2 = style(TvFonts.Text, FontWeight.Medium, 23f, 30f, -0.10f, TvColors.SecondaryLabel)

    /** Not a tvOS style; kept for existing call sites. Prefer Caption2. */
    val Subhead = Body.copy(fontWeight = FontWeight.Normal, color = TvColors.SecondaryLabel)
    val Footnote = Caption2

    /** The HIG's emphasized weight for a style (Bold for most, Medium for Subtitle 1). */
    fun TextStyle.emphasized(): TextStyle =
        copy(fontWeight = if (fontWeight == FontWeight.Normal) FontWeight.Medium else FontWeight.Bold)

    /** For text laid directly over artwork. */
    fun TextStyle.overArt(): TextStyle = copy(shadow = shadow)
}

object TvDims {
    /** HIG › Layout › tvOS safe area: 80pt sides, 60pt top/bottom. */
    val SafeH = 40.dp
    val SafeV = 30.dp

    /** HIG five-column grid: 320pt unfocused width, 40pt horizontal, ≥100pt vertical spacing. */
    val Grid5Width = 160.dp
    val GridHSpacing = 20.dp
    val GridVSpacing = 50.dp

    /** tvOS app icon: 800×480 layout (5:3) → 160×96 dp at the five-column width. */
    val IconW = 160.dp
    val IconH = 96.dp
    val IconCorner = 9.dp
    val ButtonH = 33.dp
    val RowH = 33.dp
    val RowGap = 4.dp
    val CornerSmall = 6.dp
    val Corner = 10.dp
    val CornerLarge = 14.dp
}

object TvShapes {
    val Small = RoundedCornerShape(TvDims.CornerSmall)
    val Regular = RoundedCornerShape(TvDims.Corner)
    val Large = RoundedCornerShape(TvDims.CornerLarge)
    val Capsule = RoundedCornerShape(percent = 50)
}

object TvMotion {
    /** Focus lift for buttons and rows. Icons use their own larger lift in the home screen. */
    const val BUTTON_FOCUS_SCALE = 1.08f
    const val ROW_FOCUS_SCALE = 1.03f
    const val CARD_FOCUS_SCALE = 1.1f

    fun <T> focus() = spring<T>(dampingRatio = 0.74f, stiffness = 420f)
    fun <T> move() = spring<T>(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)
    fun <T> panel() = spring<T>(dampingRatio = 0.9f, stiffness = 300f)
}
