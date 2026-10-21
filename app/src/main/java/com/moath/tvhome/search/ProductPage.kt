package com.moath.tvhome.search

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Path
import com.moath.tvhome.design.LiquidGlass.liquidGlass
import com.moath.tvhome.design.TvDims
import com.moath.tvhome.design.UiDirection
import com.moath.tvhome.design.TvShapes
import com.moath.tvhome.design.TvType.emphasized
import com.moath.tvhome.design.tvLift
import com.moath.tvhome.design.TvColors
import com.moath.tvhome.design.TvType
import com.moath.tvhome.design.TvType.overArt
import com.moath.tvhome.tmdb.TmdbClient
import com.moath.tvhome.tmdb.TmdbItem
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

private sealed interface Logo {
    data object Loading : Logo
    data object None : Logo
    data class Found(val url: String) : Logo
}

private const val LOGO_TIMEOUT_MS = 2500L

/**
 * tvOS product page: full-bleed backdrop, darkened from the left, title logo, meta line,
 * overview and the action buttons. D-pad is trapped here so focus can't leak to the results
 * composed underneath (they stay composed so Back restores focus instantly).
 */
@Composable
fun ProductPage(
    item: TmdbItem,
    onTrailer: () -> Unit,
    stremioInstalled: Boolean = false,
    onStremio: () -> Unit = {},
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val context = LocalContext.current
    val tmdb = remember { TmdbClient.get(context) }
    var logo by remember(item) { mutableStateOf<Logo>(Logo.Loading) }
    LaunchedEffect(item) {
        val path = withTimeoutOrNull(LOGO_TIMEOUT_MS) { tmdb.logoPath(item) }
        logo = TmdbClient.image(path, "w500")?.let(Logo::Found) ?: Logo.None
    }

    val actions = buildList {
        add("مشاهدة الإعلان" to onTrailer)
        if (stremioInstalled) add("فتح Stremio" to onStremio)
    }
    val requesters = remember(actions.size) { List(actions.size) { FocusRequester() } }
    var focusedButton by remember { mutableIntStateOf(0) }
    LaunchedEffect(item) { runCatching { requesters[0].requestFocus() } }
    var overview by remember(item) { mutableStateOf(item.overview) }
    LaunchedEffect(item) { if (overview.isBlank()) overview = tmdb.withOverview(item).overview }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { e ->
                val code = e.nativeKeyEvent.keyCode
                val isNav = code == AndroidKeyEvent.KEYCODE_DPAD_LEFT || code == AndroidKeyEvent.KEYCODE_DPAD_RIGHT ||
                    code == AndroidKeyEvent.KEYCODE_DPAD_UP || code == AndroidKeyEvent.KEYCODE_DPAD_DOWN
                if (isNav && e.type == KeyEventType.KeyDown) {
                    val d = when (code) {
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> UiDirection.step(leftKey = true)
                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> UiDirection.step(leftKey = false)
                        else -> 0
                    }
                    val next = (focusedButton + d).coerceIn(0, requesters.lastIndex)
                    if (next != focusedButton) runCatching { requesters[next].requestFocus() }
                }
                isNav
            },
    ) {
        val backdrop = TmdbClient.image(item.backdropPath, "w1280")
        if (backdrop != null) {
            val request = remember(backdrop) { ImageRequest.Builder(context).data(backdrop).crossfade(260).build() }
            AsyncImage(request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Color(0xEB000000),
                    0.36f to Color(0xB3000000),
                    0.72f to Color.Transparent,
                    // The text column sits on the start side, so the scrim runs from there.
                    startX = if (rtl) Float.POSITIVE_INFINITY else 0f,
                    endX = if (rtl) 0f else Float.POSITIVE_INFINITY,
                ),
            ),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xD9000000))))

        Column(Modifier.align(Alignment.BottomStart).padding(start = 64.dp, bottom = 58.dp).width(480.dp)) {
            Box(Modifier.fillMaxWidth().height(128.dp), contentAlignment = Alignment.BottomStart) {
                when (val l = logo) {
                    Logo.Loading -> Unit
                    Logo.None -> TitleText(item.title)
                    is Logo.Found -> {
                        val request = remember(l.url) { ImageRequest.Builder(context).data(l.url).crossfade(200).build() }
                        AsyncImage(
                            request,
                            contentDescription = item.title,
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.BottomStart,
                            onError = { logo = Logo.None },
                            modifier = Modifier.width(400.dp).height(128.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            BasicText(metaLine(item), style = TvType.Subhead.copy(color = Color(0xD9FFFFFF)).overArt(), maxLines = 1)
            if (overview.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                BasicText(
                    overview,
                    style = TvType.Body.copy(color = Color(0xD9FFFFFF)).overArt(),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(26.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                actions.forEachIndexed { i, (label, action) ->
                    ProductButton(
                        label,
                        primary = i == 0,
                        onClick = action,
                        modifier = Modifier
                            .focusRequester(requesters[i])
                            .onFocusChanged { if (it.isFocused) focusedButton = i },
                    )
                }
            }
        }
    }
}

/**
 * Top Shelf carousel-style action: the primary ("Play"-style) carries a play glyph. Idle buttons
 * sit on Liquid Glass over the artwork; focus turns them white with dark label and lifts them.
 */
@Composable
private fun ProductButton(label: String, primary: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val fg = if (focused) TvColors.FocusedLabel else TvColors.Label
    Row(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .height(TvDims.ButtonH)
            .tvLift(focused, TvShapes.Capsule)
            .then(
                if (focused) Modifier.background(TvColors.FocusedFill, TvShapes.Capsule)
                else Modifier.liquidGlass(TvShapes.Capsule, intensity = 0.4f),
            )
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (primary) PlayGlyph(fg, mirrored = LocalLayoutDirection.current == LayoutDirection.Rtl)
        BasicText(label, style = TvType.Body.emphasized().copy(color = fg), maxLines = 1)
    }
}

@Composable
private fun PlayGlyph(color: Color, mirrored: Boolean) {
    Canvas(Modifier.size(width = 10.dp, height = 12.dp).graphicsLayer { scaleX = if (mirrored) -1f else 1f }) {
        val p = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, size.height / 2)
            lineTo(0f, size.height)
            close()
        }
        drawPath(p, color)
    }
}

@Composable
private fun TitleText(title: String) {
    BasicText(title, style = TvType.Title1.overArt(), maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/** "Movie · 2024 · Sci-Fi · ★ 7.8" — only real TMDB values; Western digits whatever the locale. */
private fun metaLine(item: TmdbItem): String = listOfNotNull(
    if (item.mediaType == "tv") "مسلسل" else "فيلم",
    item.year,
    item.genres.firstOrNull(),
    item.rating.takeIf { it > 0.0 }?.let { "★ " + String.format(Locale.US, "%.1f", it) },
).joinToString("  ·  ")
