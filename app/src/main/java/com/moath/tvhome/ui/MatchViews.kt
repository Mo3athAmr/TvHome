package com.moath.tvhome.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.moath.tvhome.design.LiquidGlass.liquidGlass
import com.moath.tvhome.design.TvColors
import com.moath.tvhome.design.TvFonts
import com.moath.tvhome.design.TvShapes
import com.moath.tvhome.sports.MatchCard
import com.moath.tvhome.sports.MatchState

private val LiveRed = Color(0xFFFF453A)

private fun label(size: Int, color: Color, weight: FontWeight = FontWeight.Medium) =
    TextStyle(fontFamily = TvFonts.Text, fontSize = size.sp, fontWeight = weight, color = color, textAlign = TextAlign.Center)

/** A club crest. Crests are logos, never text, so they read the same in both layout directions. */
@Composable
fun Crest(url: String?, size: Dp, modifier: Modifier = Modifier) {
    if (url == null) {
        Box(modifier.size(size).liquidGlass(RoundedCornerShape(size / 2), intensity = 0.3f))
    } else {
        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Fit, modifier = modifier.size(size))
    }
}

/** The face of a shelf card: league, the two crests with score or kickoff time between them, the channel. */
@Composable
fun MatchTile(c: MatchCard) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                if (c.live) {
                    Box(Modifier.size(8.dp).background(LiveRed, RoundedCornerShape(4.dp)))
                    Box(Modifier.width(6.dp))
                }
                BasicText(c.leagueName, style = label(11, TvColors.SecondaryLabel), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Crest(c.home.logo, 46.dp)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BasicText(c.center, style = label(24, TvColors.Label, FontWeight.Bold), maxLines = 1)
                    BasicText(c.sub, style = label(12, if (c.live) LiveRed else TvColors.SecondaryLabel, FontWeight.SemiBold), maxLines = 1)
                }
                Crest(c.away.logo, 46.dp)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    c.channel ?: " ",
                    style = label(11, TvColors.TertiaryLabel),
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                // Casper Flix badge: the match page has the button that opens it.
                val casper = com.moath.tvhome.sports.CasperFlix.icon(androidx.compose.ui.platform.LocalContext.current)
                if (casper != null) {
                    androidx.compose.foundation.Image(casper, "Casper Flix", Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)))
                }
            }
        }
    }
}

/**
 * The widget: one slim Liquid Glass capsule in the tab bar's row, between the clock and the bar, holding up
 * to three matches side by side (crests, score or kickoff time, minute or day). It never covers content.
 */
@Composable
fun MatchWidget(cards: List<MatchCard>, modifier: Modifier = Modifier) {
    if (cards.isEmpty()) return
    // [modifier] carries the screen margins, resolved in the screen's (right-to-left) direction, so it
    // sits outside the left-to-right block that lays out the crests.
    Box(modifier) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                Modifier
                    .height(34.dp)
                    .background(Color(0x4D000000), TvShapes.Capsule)
                    .liquidGlass(TvShapes.Capsule, intensity = 0.35f)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                cards.forEachIndexed { i, c ->
                    if (i > 0) Box(Modifier.width(1.dp).height(16.dp).background(Color(0x33FFFFFF)))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Crest(c.home.logo, 17.dp)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            BasicText(c.center, style = label(11, TvColors.Label, FontWeight.Bold), maxLines = 1)
                            BasicText(
                                c.sub,
                                style = label(8, if (c.live) LiveRed else TvColors.SecondaryLabel, FontWeight.SemiBold),
                                maxLines = 1,
                            )
                        }
                        Crest(c.away.logo, 17.dp)
                    }
                }
            }
        }
    }
}

internal val MatchCard.isUpcoming: Boolean get() = state == MatchState.Upcoming
