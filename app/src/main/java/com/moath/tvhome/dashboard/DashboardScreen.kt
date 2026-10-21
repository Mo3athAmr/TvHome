package com.moath.tvhome.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moath.tvhome.R
import com.moath.tvhome.design.TvDims
import com.moath.tvhome.prayer.Prayer

/*
 * Direction: photographic ground (Apple Aerial video) · no cards, no borders · white-only type with
 * opacity hierarchy (100 / 78 / 58 / 40) · one dominant element = next prayer · the day as a quiet
 * timeline · colour only where semantic (SF-Symbol weather, price up/down) · zero motion.
 */
private object Ink {
    val Primary = Color.White
    val Secondary = Color.White.copy(alpha = 0.78f)
    val Tertiary = Color.White.copy(alpha = 0.58f)
    val Passed = Color.White.copy(alpha = 0.40f)
    val Track = Color.White.copy(alpha = 0.22f)
    val Up = Color(0xFF30D158)
    val Down = Color(0xFFFF453A)
    val ScrimTop = Color.Black.copy(alpha = 0.55f)
    val ScrimBottom = Color.Black.copy(alpha = 0.78f)
    val TextShadow = Shadow(Color.Black.copy(alpha = 0.45f), Offset(0f, 2f), 12f)
    /** Dark Liquid Glass: neutral, never tinted — the footage supplies the colour. */
    val GlassBody = Color.Black.copy(alpha = 0.30f)
    val GlassSheen = Color.White.copy(alpha = 0.07f)
    val GlassRimTop = Color.White.copy(alpha = 0.22f)
    val GlassRimBottom = Color.White.copy(alpha = 0.05f)
    val GlassBodyDim = Color.Black.copy(alpha = 0.18f)
    val FocusFill = Color.White
    val OnFocus = Color(0xFF0B0B0C)
    val FocusTrack = Color(0x1F000000)
}



private object Faces {
    val Arabic = FontFamily(
        Font(R.font.plex_arabic_regular, FontWeight.Normal),
        Font(R.font.plex_arabic_medium, FontWeight.Medium),
        Font(R.font.plex_arabic_semibold, FontWeight.SemiBold),
        Font(R.font.plex_arabic_bold, FontWeight.Bold),
    )
}

private fun type(size: TextUnit, weight: FontWeight, color: Color) =
    TextStyle(fontFamily = Faces.Arabic, fontWeight = weight, fontSize = size, color = color, shadow = Ink.TextShadow)

private object Scale {
    val Hero = type(72.sp, FontWeight.SemiBold, Ink.Primary)
    val HeroTime = type(30.sp, FontWeight.Medium, Ink.Secondary)
    val HeroCountdown = type(22.sp, FontWeight.Medium, Ink.Primary)
    val Eyebrow = type(14.sp, FontWeight.Medium, Ink.Tertiary)
    val Clock = type(44.sp, FontWeight.Medium, Ink.Primary)
    val Meridiem = type(18.sp, FontWeight.Medium, Ink.Secondary)
    val Line = type(15.sp, FontWeight.Medium, Ink.Secondary)
    val Small = type(13.sp, FontWeight.Medium, Ink.Tertiary)
    val Temp = type(52.sp, FontWeight.Normal, Ink.Primary)
    val City = type(15.sp, FontWeight.SemiBold, Ink.Secondary)
    val DayName = type(11.5.sp, FontWeight.Medium, Ink.Tertiary)
    val DayTemp = type(13.sp, FontWeight.SemiBold, Ink.Secondary)
    val RowTitle = type(16.sp, FontWeight.SemiBold, Ink.Secondary)
    val FocusTime = type(40.sp, FontWeight.Bold, Ink.Primary)
    val FocusDetail = TextStyle(fontFamily = Faces.Arabic, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Color(0x99000000))
    val TileName = type(16.sp, FontWeight.Medium, Ink.Primary)
    val TileTime = type(32.sp, FontWeight.SemiBold, Ink.Primary)
    val TileMeridiem = type(15.sp, FontWeight.Medium, Ink.Primary)
    val SlotName = type(14.sp, FontWeight.Medium, Ink.Secondary)
    val SlotTime = type(18.sp, FontWeight.SemiBold, Ink.Secondary)
}

data class DashboardState(
    val moment: DashboardMoment,
    val weather: List<CityWeather>,
    val instagram: InstagramStats?,
    /** Burn-in guard: the whole layout drifts a few dp each minute. */
    val shift: Pair<Dp, Dp>,
    /** Pages of the rotating glass card (weather, today's matches, newest video); empty = no card. */
    val cards: List<com.moath.tvhome.screensaver.SaverCard> = emptyList(),
    /** Shows "OK to customise" for the first seconds. */
    val hint: Boolean = false,
)

/** Prayer-times idle screen drawn over the Aerial video. RTL, Arabic-Indic digits. */
@Composable
fun DashboardScreen(state: DashboardState) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(220.dp).background(Brush.verticalGradient(listOf(Ink.ScrimTop, Color.Transparent))))
            Box(
                Modifier.fillMaxWidth().height(340.dp).align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Ink.ScrimBottom))),
            )
            Box(
                Modifier.fillMaxSize()
                    .offset(state.shift.first, state.shift.second)
                    .padding(horizontal = TvDims.SafeH, vertical = TvDims.SafeV),
            ) {
                ClockBlock(state, Modifier.align(Alignment.TopStart))
                state.instagram?.let { InstagramBlock(it, Modifier.align(Alignment.TopEnd)) }
                if (state.instagram == null) {
                    Column(Modifier.align(Alignment.TopEnd), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (state.hint) com.moath.tvhome.screensaver.SettingsHint()
                        com.moath.tvhome.screensaver.RotatingCard(state.cards)
                    }
                }
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
                    BasicText("مواقيت الصلاة · الخبر", style = Scale.RowTitle)
                    Spacer(Modifier.height(14.dp))
                    PrayerTiles(state.moment)
                }
            }
        }
    }
}

/*
 * The two top blocks mirror each other line for line: a 44sp figure, then two 15sp lines.
 * Same styles, same spacing, so the band reads as one calm row across the screen.
 */
@Composable
private fun ClockBlock(state: DashboardState, modifier: Modifier) {
    val m = state.moment
    Column(modifier, verticalArrangement = Arrangement.spacedBy(BLOCK_GAP)) {
        Row(verticalAlignment = Alignment.Bottom) {
            BasicText(ArabicText.clock(m.minuteOfDay), style = Scale.Clock)
            Spacer(Modifier.width(8.dp))
            BasicText(ArabicText.meridiem(m.minuteOfDay), style = Scale.Meridiem, modifier = Modifier.padding(bottom = 8.dp))
        }
        BasicText(if (m.hijri.isNotEmpty()) "${m.gregorian} · ${m.hijri}" else m.gregorian, style = Scale.Line)
        state.weather.firstOrNull()?.let { w ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(w.name, style = Scale.Line)
                Spacer(Modifier.width(8.dp))
                GlyphIcon(weatherGlyph(w.currentCode, w.isDay), 17.dp)
                Spacer(Modifier.width(6.dp))
                BasicText("${ArabicText.degrees(w.currentTemp)}  ${condition(w.currentCode)}", style = Scale.Line)
            }
        }
    }
}

@Composable
private fun InstagramBlock(ig: InstagramStats, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(BLOCK_GAP)) {
        Row(verticalAlignment = Alignment.Bottom) {
            BasicText(ArabicText.followers(ig.followers, ig.followersExact), style = Scale.Clock)
            Spacer(Modifier.width(8.dp))
            BasicText("متابع", style = Scale.Meridiem, modifier = Modifier.padding(bottom = 8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            InstagramGlyph(15.dp)
            Spacer(Modifier.width(8.dp))
            BasicText(ig.handle, style = Scale.Line.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr))
        }
        val change = ig.change24h
        val detail = when {
            change != null && change > 0 -> "▲ ${ArabicText.grouped(change)} خلال ٢٤ ساعة"
            change != null && change < 0 -> "▼ ${ArabicText.grouped(-change)} خلال ٢٤ ساعة"
            ig.posts != null -> "${ArabicText.grouped(ig.posts)} منشورًا"
            else -> null
        }
        detail?.let {
            BasicText(
                it,
                style = Scale.Line.copy(
                    color = when {
                        change != null && change > 0 -> Ink.Up
                        change != null && change < 0 -> Ink.Down
                        else -> Ink.Secondary
                    },
                ),
            )
        }
    }
}

/** Instagram's camera glyph as a white outline: rounded square, lens, flash dot. */
@Composable
private fun InstagramGlyph(size: Dp) {
    Canvas(Modifier.width(size).height(size)) {
        val w = this.size.width
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.1f)
        drawRoundRect(
            Ink.Secondary,
            topLeft = Offset(w * 0.06f, w * 0.06f),
            size = androidx.compose.ui.geometry.Size(w * 0.88f, w * 0.88f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.26f),
            style = stroke,
        )
        drawCircle(Ink.Secondary, w * 0.2f, Offset(w * 0.5f, w * 0.5f), style = stroke)
        drawCircle(Ink.Secondary, w * 0.06f, Offset(w * 0.74f, w * 0.26f))
    }
}

/**
 * The day as a tvOS shelf: five glass tiles; the next prayer is the focused tile — white, black
 * type, wider — and carries its countdown and a progress bar from the previous prayer.
 */
@Composable
private fun PrayerTiles(m: DashboardMoment) {
    val isNow = m.minutesSincePrevious < NOW_WINDOW_MIN
    val highlight = if (isNow) m.previous else m.next
    val highlightIndex = m.prayers.indexOfFirst { it.first == highlight }
    Row(Modifier.fillMaxWidth().height(TILE_HEIGHT), horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
        m.prayers.forEachIndexed { i, (prayer, at) ->
            if (i == highlightIndex) {
                FocusedTile(prayer, at, m, isNow, Modifier.weight(FOCUS_WEIGHT))
            } else {
                QuietTile(prayer, at, passed = i < highlightIndex, modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun glyphFor(p: Prayer): Glyph = when (p) {
    Prayer.Fajr -> Glyph.Dawn
    Prayer.Dhuhr -> Glyph.Noon
    Prayer.Asr -> Glyph.Afternoon
    Prayer.Maghrib -> Glyph.Sunset
    else -> Glyph.Night
}

@Composable
private fun QuietTile(prayer: Prayer, at: Int, passed: Boolean, modifier: Modifier) {
    val ink = if (passed) Ink.Passed else Ink.Primary
    Column(
        modifier.fillMaxHeight().tileGlass(passed).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        TileHeader(prayer, ink)
        TimeLine(at, ink, Scale.TileTime)
    }
}

@Composable
private fun FocusedTile(prayer: Prayer, at: Int, m: DashboardMoment, isNow: Boolean, modifier: Modifier) {
    val ink = Ink.OnFocus
    val progress = if (isNow) 1f else {
        val span = (m.minutesSincePrevious + m.minutesToNext).coerceAtLeast(1)
        (m.minutesSincePrevious.toFloat() / span).coerceIn(0f, 1f)
    }
    Column(
        modifier
            .fillMaxHeight()
            .graphicsLayer {
                shadowElevation = 24.dp.toPx()
                shape = TileShape
                clip = false
            }
            .background(Ink.FocusFill, TileShape)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        TileHeader(prayer, ink)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TimeLine(at, ink, Scale.FocusTime)
            BasicText(
                if (isNow) "حان الآن" else "بعد ${ArabicText.duration(m.minutesToNext)}",
                style = Scale.FocusDetail,
                maxLines = 1,
            )
            ProgressCapsule(progress)
        }
    }
}

@Composable
private fun TileHeader(prayer: Prayer, ink: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        GlyphIcon(glyphFor(prayer), 20.dp, ink)
        Spacer(Modifier.width(8.dp))
        BasicText(prayer.arabic, style = Scale.TileName.copy(color = ink, shadow = null))
    }
}

@Composable
private fun TimeLine(at: Int, ink: Color, style: TextStyle) {
    Row(verticalAlignment = Alignment.Bottom) {
        BasicText(ArabicText.clock(at), style = style.copy(color = ink, shadow = null))
        Spacer(Modifier.width(6.dp))
        BasicText(
            ArabicText.meridiem(at),
            style = Scale.TileMeridiem.copy(color = ink.copy(alpha = ink.alpha * 0.65f), shadow = null),
            modifier = Modifier.padding(bottom = 5.dp),
        )
    }
}

/** Elapsed share of the gap between the previous prayer and this one. */
@Composable
private fun ProgressCapsule(progress: Float) {
    Canvas(Modifier.fillMaxWidth().height(4.dp)) {
        val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
        drawRoundRect(Ink.FocusTrack, cornerRadius = r)
        drawRoundRect(
            Ink.OnFocus,
            topLeft = Offset(size.width * (1f - progress), 0f), // RTL: fills from the right
            size = androidx.compose.ui.geometry.Size(size.width * progress, size.height),
            cornerRadius = r,
        )
    }
}

private val TileShape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)

private fun Modifier.tileGlass(dim: Boolean): Modifier = this
    .background(if (dim) Ink.GlassBodyDim else Ink.GlassBody, TileShape)
    .background(Brush.verticalGradient(listOf(Ink.GlassSheen, Color.Transparent)), TileShape)
    .border(1.dp, Brush.verticalGradient(listOf(Ink.GlassRimTop, Ink.GlassRimBottom)), TileShape)

internal fun condition(code: Int): String = when (code) {
    0 -> "صافٍ"
    1 -> "صافٍ غالبًا"
    2 -> "غائم جزئيًا"
    3 -> "غائم"
    45, 48 -> "ضباب"
    in 51..57 -> "رذاذ"
    in 61..67 -> "مطر"
    in 71..77, 85, 86 -> "ثلج"
    in 80..82 -> "زخات مطر"
    in 95..99 -> "عاصفة رعدية"
    else -> "غائم"
}

private val BLOCK_GAP = 6.dp
private val TILE_GAP = 20.dp
private val TILE_HEIGHT = 150.dp
private const val FOCUS_WEIGHT = 1.7f
private const val NOW_WINDOW_MIN = 20
