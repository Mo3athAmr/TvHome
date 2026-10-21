package com.moath.tvhome.search

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.moath.tvhome.design.TvColors
import com.moath.tvhome.design.TvDims
import com.moath.tvhome.design.TvMotion
import com.moath.tvhome.design.TvShapes
import com.moath.tvhome.design.TvType
import com.moath.tvhome.design.TvType.overArt
import com.moath.tvhome.design.tvLift
import com.moath.tvhome.tmdb.TmdbClient

/** Remembers the last focused card of one row so Up/Down and Back land where you left. */
class RowFocus {
    var last = 0
    val requesters = HashMap<Int, FocusRequester>()

    fun restore(): Boolean {
        val target = requesters[last] ?: requesters.minByOrNull { it.key }?.value ?: return false
        return runCatching { target.requestFocus() }.isSuccess
    }
}

private data class CardSize(val width: Dp, val height: Dp)

// HIG grid widths (unfocused): 7-column posters 217pt, 5-column app icons 320pt, 4-column 410pt.
private val PosterSize = CardSize(108.dp, 162.dp)
private val AppSize = CardSize(TvDims.IconW, TvDims.IconH)
private val TopMediaSize = CardSize(205.dp, 115.dp)
private val TopAppSize = CardSize(192.dp, 115.dp)
private val SectionStyle = TvType.Headline.copy(color = TvColors.SecondaryLabel)
private const val CROSSFADE_MS = 160
private val AppShape = RoundedCornerShape(TvDims.IconCorner)

@Composable
fun ResultRow(
    section: ResultSection,
    focus: RowFocus,
    onFocused: () -> Unit,
    onSelect: (SearchHit) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        BasicText(section.title, style = SectionStyle, modifier = Modifier.padding(start = TvDims.SafeH))
        LazyRow(
            contentPadding = PaddingValues(horizontal = TvDims.SafeH, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(TvDims.GridHSpacing),
        ) {
            itemsIndexed(section.hits, key = { i, hit -> "${hit.key}#$i" }) { i, hit ->
                val requester = remember { FocusRequester() }
                DisposableEffect(requester) {
                    focus.requesters[i] = requester
                    onDispose { if (focus.requesters[i] === requester) focus.requesters.remove(i) }
                }
                HitCard(
                    hit = hit,
                    kind = section.kind,
                    requester = requester,
                    onFocus = {
                        focus.last = i
                        onFocused()
                    },
                    onClick = { onSelect(hit) },
                )
            }
        }
    }
}

@Composable
private fun HitCard(hit: SearchHit, kind: SectionKind, requester: FocusRequester, onFocus: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val size = when (kind) {
        SectionKind.Posters -> PosterSize
        SectionKind.Apps -> AppSize
        SectionKind.Landscape -> if (hit is AppHit) TopAppSize else TopMediaSize
    }
    val titleInside = kind == SectionKind.Landscape && hit is MediaHit
    val shape = if (hit is AppHit) AppShape else TvShapes.Small
    val titleAlpha by animateFloatAsState(if (focused && !titleInside) 1f else 0f, tween(140), label = "title")
    Column(Modifier.width(size.width), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .focusRequester(requester)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocus()
                }
                .size(size.width, size.height)
                .tvLift(focused, shape, scale = TvMotion.CARD_FOCUS_SCALE, elevation = 20.dp)
                .background(TvColors.IdleFill)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        ) {
            when (hit) {
                is AppHit -> Image(hit.app.tile, contentDescription = hit.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                is MediaHit -> MediaArt(hit, kind, titleInside)
            }
        }
        BasicText(
            hit.title,
            style = TvType.Caption1.copy(color = TvColors.Label, textAlign = TextAlign.Center),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .requiredWidth(size.width + 34.dp)
                .padding(top = 13.dp)
                .graphicsLayer { alpha = titleAlpha },
        )
    }
}

@Composable
private fun MediaArt(hit: MediaHit, kind: SectionKind, titleInside: Boolean) {
    val item = hit.item
    val url = if (kind == SectionKind.Landscape) {
        TmdbClient.image(item.backdropPath, "w780")
    } else {
        TmdbClient.image(item.posterPath, "w342")
    }
    if (url == null) {
        Box(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.Center) {
            BasicText(item.title, style = TvType.Caption1.copy(color = TvColors.Label, textAlign = TextAlign.Center), maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        return
    }
    val context = LocalContext.current
    val request = remember(url) { ImageRequest.Builder(context).data(url).crossfade(CROSSFADE_MS).build() }
    AsyncImage(request, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    if (titleInside) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xB3000000))),
        )
        Box(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp), contentAlignment = Alignment.BottomStart) {
            BasicText(item.title, style = TvType.Caption1.copy(color = TvColors.Label).overArt(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "No Results" and friends: quiet, centred, secondary — never an error dialog. */
@Composable
fun ResultsMessage(text: String, modifier: Modifier = Modifier) {
    if (text.isEmpty()) return
    Box(modifier.fillMaxWidth().padding(top = 70.dp), contentAlignment = Alignment.Center) {
        BasicText(text, style = TvType.Headline.copy(color = TvColors.SecondaryLabel))
    }
}
