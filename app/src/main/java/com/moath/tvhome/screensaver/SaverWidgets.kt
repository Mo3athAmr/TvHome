package com.moath.tvhome.screensaver

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.moath.tvhome.dashboard.ArabicText
import com.moath.tvhome.dashboard.CityWeather
import com.moath.tvhome.dashboard.DashboardMoment
import com.moath.tvhome.dashboard.GlyphIcon
import com.moath.tvhome.dashboard.condition
import com.moath.tvhome.dashboard.weatherGlyph
import com.moath.tvhome.design.LiquidGlass.liquidGlass
import com.moath.tvhome.design.TvColors
import com.moath.tvhome.design.TvFonts
import com.moath.tvhome.sports.MatchCard
import com.moath.tvhome.ui.MatchWidget
import kotlinx.coroutines.delay

/** One page of the rotating glass card. */
sealed interface SaverCard {
    data class Weather(val w: CityWeather) : SaverCard
    data class Matches(val cards: List<MatchCard>) : SaverCard
    data class Video(val title: String, val channel: String, val thumb: String) : SaverCard
}

internal fun saverText(size: Int, color: Color = TvColors.Label, weight: FontWeight = FontWeight.Medium) =
    TextStyle(fontFamily = TvFonts.Text, fontSize = size.sp, fontWeight = weight, color = color)

private val CardShape = RoundedCornerShape(24.dp)

/** Liquid Glass like the tab bar, darkened enough to read over any picture. */
@Composable
internal fun Glass(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.background(Color(0x66000000), CardShape).liquidGlass(CardShape, intensity = 0.45f).padding(horizontal = 20.dp, vertical = 16.dp)) {
        content()
    }
}

private val weekdays = listOf("الأحد", "الإثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت")

/** Cycles its pages every [periodMs] (30 s) with a soft crossfade; nothing animates in between. */
@Composable
fun RotatingCard(cards: List<SaverCard>, modifier: Modifier = Modifier, periodMs: Long = 30_000) {
    if (cards.isEmpty()) return
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(cards.size) {
        while (true) {
            delay(periodMs)
            index = (index + 1) % cards.size
        }
    }
    val card = cards[index.coerceIn(0, cards.lastIndex)]
    Glass(modifier.width(360.dp)) {
        AnimatedContent(card, transitionSpec = { fadeIn(tween(700)) togetherWith fadeOut(tween(500)) }, label = "rotating") { c ->
            when (c) {
                is SaverCard.Weather -> WeatherPage(c.w)
                is SaverCard.Matches -> MatchesPage(c.cards)
                is SaverCard.Video -> VideoPage(c)
            }
        }
    }
}

@Composable
private fun WeatherPage(w: CityWeather) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(weatherGlyph(w.currentCode, w.isDay), 34.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                BasicText("${w.name}  ${ArabicText.degrees(w.currentTemp)}", style = saverText(24, weight = FontWeight.SemiBold))
                BasicText(condition(w.currentCode), style = saverText(14, TvColors.SecondaryLabel))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            w.days.drop(1).take(3).forEach { d ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BasicText(weekdays[d.weekday.mod(7)], style = saverText(12, TvColors.SecondaryLabel))
                    GlyphIcon(weatherGlyph(d.code), 22.dp)
                    BasicText("${ArabicText.digits(d.max)}° / ${ArabicText.digits(d.min)}°", style = saverText(13))
                }
            }
        }
    }
}

@Composable
private fun MatchesPage(cards: List<MatchCard>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText("مباريات اليوم", style = saverText(14, TvColors.SecondaryLabel, FontWeight.SemiBold))
        MatchWidget(cards)
    }
}

@Composable
private fun VideoPage(v: SaverCard.Video) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText("جديد يوتيوب", style = saverText(14, TvColors.SecondaryLabel, FontWeight.SemiBold))
        AsyncImage(
            model = v.thumb, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(14.dp)),
        )
        BasicText(v.title, style = saverText(15, weight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
        BasicText(v.channel, style = saverText(12, TvColors.SecondaryLabel), maxLines = 1)
    }
}

/** The widgets drawn over Google Photos: a glass block with the chosen pieces. */
@Composable
fun PhotoWidgets(widgets: Set<String>, moment: DashboardMoment, weather: CityWeather?, matches: List<MatchCard>, modifier: Modifier = Modifier) {
    Glass(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if ("clock" in widgets) {
                Row(verticalAlignment = Alignment.Bottom) {
                    BasicText(ArabicText.clock(moment.minuteOfDay), style = saverText(46, weight = FontWeight.SemiBold))
                    Spacer(Modifier.width(6.dp))
                    BasicText(ArabicText.meridiem(moment.minuteOfDay), style = saverText(16), modifier = Modifier.padding(bottom = 8.dp))
                }
                BasicText(if (moment.hijri.isNotEmpty()) "${moment.gregorian} · ${moment.hijri}" else moment.gregorian, style = saverText(14, TvColors.SecondaryLabel))
            }
            if ("prayer" in widgets) {
                BasicText(
                    "${moment.next.arabic} ${ArabicText.time(moment.nextAt)} · بعد ${ArabicText.duration(moment.minutesToNext)}",
                    style = saverText(15),
                )
            }
            if ("weather" in widgets && weather != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlyphIcon(weatherGlyph(weather.currentCode, weather.isDay), 18.dp)
                    Spacer(Modifier.width(6.dp))
                    BasicText("${weather.name} ${ArabicText.degrees(weather.currentTemp)} ${condition(weather.currentCode)}", style = saverText(15))
                }
            }
            if ("matches" in widgets && matches.isNotEmpty()) MatchWidget(matches)
        }
    }
}

/** "⚙ OK للتخصيص": shown for a few seconds when the screen saver starts. */
@Composable
fun SettingsHint(modifier: Modifier = Modifier) {
    Row(
        modifier.background(Color(0x66000000), RoundedCornerShape(50)).liquidGlass(RoundedCornerShape(50), intensity = 0.35f)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText("⚙", style = saverText(15))
        Spacer(Modifier.width(6.dp))
        BasicText("اضغط OK للتخصيص", style = saverText(13, TvColors.SecondaryLabel))
    }
}

internal val Dot = Modifier.size(1.dp)
