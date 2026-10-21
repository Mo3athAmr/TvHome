package com.moath.tvhome.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import com.moath.tvhome.HomeViewModel
import com.moath.tvhome.design.LiquidGlass.liquidGlass
import com.moath.tvhome.design.TvColors
import com.moath.tvhome.design.TvDims
import com.moath.tvhome.design.TvMotion
import com.moath.tvhome.design.TvShapes
import com.moath.tvhome.home.HeroItem
import com.moath.tvhome.home.ShelfImages
import com.moath.tvhome.home.ShelfNav
import com.moath.tvhome.home.Tab
import com.moath.tvhome.home.TabPage
import com.moath.tvhome.home.Zone

private const val PRESS_DIP = 0.95f

/**
 * The hero's text and controls, bottom-leading in the safe area: attribution, title logo, meta,
 * a two-line synopsis, Play / More Info in Liquid Glass, and page dots. The artwork itself is the
 * [Backdrop] behind the whole page; this block scrolls away with the page.
 */
@Composable
fun HeroDetails(vm: HomeViewModel, page: TabPage, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(HomeDims.HeroH)) {
        val item = page.heroItem
        if (item == null) {
            if (page.heroLoading) HeroPlaceholder(Modifier.align(Alignment.BottomStart))
            return@Box
        }
        val focused by remember(page) { derivedStateOf { page.row == Zone.HERO } }
        val button = if (focused) page.heroButton else -1
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = TvDims.SafeH, bottom = HomeDims.HeroBottom),
        ) {
            TitleSwap(item)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                HeroButton(
                    if (item.title.mediaType == HomeViewModel.QURAN_TYPE) "استمع" else "تشغيل",
                    focused = button == ShelfNav.PLAY,
                    pressed = vm.pressedKey == HomeViewModel.KEY_PLAY,
                    glyph = true,
                )
                HeroButton(
                    if (item.title.mediaType == HomeViewModel.YOUTUBE_TYPE || item.title.mediaType == HomeViewModel.QURAN_TYPE) "إخفاء" else "مزيد من المعلومات",
                    focused = button == ShelfNav.MORE_INFO,
                    pressed = vm.pressedKey == HomeViewModel.KEY_MORE_INFO,
                )
            }
        }
        PageDots(
            count = page.hero.size,
            current = page.heroPage,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = TvDims.SafeH, bottom = HomeDims.HeroBottom + 13.dp),
        )
        // Today's matches, tucked under the page dots: out of the way of the artwork and the buttons.
        if (page.tab == Tab.Home) {
            MatchWidget(
                vm.widget,
                Modifier.align(Alignment.BottomEnd).padding(end = TvDims.SafeH, bottom = 2.dp),
            )
        }
    }
}

/**
 * Crossfades the title block when the page changes — the outgoing block fades in the first half,
 * the incoming over the whole fade. Alpha is modulated per draw (no offscreen layers).
 */
@Composable
private fun TitleSwap(item: HeroItem) {
    var current by remember { mutableStateOf(item) }
    var outgoing by remember { mutableStateOf<HeroItem?>(null) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(item) {
        if (item.title.id == current.title.id) {
            current = item
            return@LaunchedEffect
        }
        outgoing = current
        current = item
        fade.snapTo(0f)
        fade.animateTo(1f, tween(Motion.HERO_FADE_MS))
        outgoing = null
    }
    Box(contentAlignment = Alignment.BottomStart) {
        outgoing?.let { old ->
            TitleBlock(old, Modifier.graphicsLayer {
                alpha = (1f - fade.value * 2f).coerceAtLeast(0f)
                compositingStrategy = CompositingStrategy.ModulateAlpha
            })
        }
        TitleBlock(current, Modifier.graphicsLayer {
            alpha = fade.value
            compositingStrategy = CompositingStrategy.ModulateAlpha
        })
    }
}

@Composable
private fun TitleBlock(item: HeroItem, modifier: Modifier = Modifier) {
    val t = item.title
    Column(modifier.width(HomeDims.OverviewW)) {
        BasicText(item.attribution, style = Type.ShelfAttribution, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        TitleArt(item)
        if (t.meta.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            BasicText(t.meta, style = Type.ShelfMeta, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (t.overview.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            BasicText(
                t.overview,
                style = Type.ShelfOverview,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** tvOS shows the title's logo, not text; Title 1 (emphasized) is only the fallback. */
@Composable
private fun TitleArt(item: HeroItem) {
    val logo = item.logoUrl ?: return TitleText(item.title.name)
    val context = LocalContext.current
    val request = remember(logo) { ShelfImages.logo(context, logo) }
    val painter = rememberAsyncImagePainter(request, contentScale = ContentScale.Fit)
    val state by painter.state.collectAsState()
    if (state is AsyncImagePainter.State.Error) return TitleText(item.title.name)
    Image(
        painter = painter,
        contentDescription = item.title.name,
        contentScale = ContentScale.Fit,
        alignment = Alignment.BottomStart,
        modifier = Modifier.size(HomeDims.LogoW, HomeDims.LogoH),
    )
}

@Composable
private fun TitleText(title: String) {
    Box(Modifier.width(HomeDims.OverviewW).height(HomeDims.LogoH), contentAlignment = Alignment.BottomStart) {
        BasicText(title, style = Type.ShelfTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * HIG button: Liquid Glass at rest; focused it turns white, lifts and scales (shape, scale and
 * shadow change too, so colour is never the only cue); selected it dips briefly before opening.
 */
@Composable
private fun HeroButton(label: String, focused: Boolean, pressed: Boolean, glyph: Boolean = false) {
    val f by animateFloatAsState(if (focused) 1f else 0f, TvMotion.focus(), label = "heroBtn")
    val fg = { lerp(TvColors.Label, TvColors.FocusedLabel, f.coerceIn(0f, 1f)) }
    Row(
        Modifier
            .graphicsLayer {
                val s = (1f + (TvMotion.BUTTON_FOCUS_SCALE - 1f) * f) * if (pressed) PRESS_DIP else 1f
                scaleX = s
                scaleY = s
                shadowElevation = (16f * f).dp.toPx()
                shape = TvShapes.Capsule
                clip = true
            }
            .liquidGlass(TvShapes.Capsule, 0.4f)
            .drawBehind {
                if (f > 0.01f) drawRoundRect(TvColors.FocusedFill, cornerRadius = CornerRadius(size.height / 2), alpha = f.coerceIn(0f, 1f))
            }
            .height(TvDims.ButtonH)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (glyph) PlayGlyph(fg)
        BasicText(label, style = Type.ShelfButton, maxLines = 1, color = fg)
    }
}

@Composable
private fun PlayGlyph(color: () -> Color) {
    Canvas(Modifier.size(10.dp)) {
        val path = Path().apply {
            moveTo(size.width * 0.12f, 0f)
            lineTo(size.width, size.height / 2f)
            lineTo(size.width * 0.12f, size.height)
            close()
        }
        drawPath(path, color())
    }
}

@Composable
private fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    if (count < 2) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        repeat(count) { i ->
            Box(Modifier.size(5.dp).background(if (i == current) Color.White else Palette.Dot, CircleShape))
        }
    }
}

/** While the first page loads: flat glass shapes where the title and buttons will be. */
@Composable
private fun HeroPlaceholder(modifier: Modifier = Modifier) {
    Column(modifier.padding(start = TvDims.SafeH, bottom = HomeDims.HeroBottom)) {
        Box(Modifier.size(120.dp, 10.dp).background(Palette.Placeholder, TvShapes.Capsule))
        Spacer(Modifier.height(14.dp))
        Box(Modifier.size(260.dp, 60.dp).background(Palette.Placeholder, TvShapes.Small))
        Spacer(Modifier.height(14.dp))
        Box(Modifier.size(HomeDims.OverviewW, 10.dp).background(Palette.Placeholder, TvShapes.Capsule))
        Spacer(Modifier.height(8.dp))
        Box(Modifier.size(300.dp, 10.dp).background(Palette.Placeholder, TvShapes.Capsule))
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(96.dp, TvDims.ButtonH).background(Palette.Placeholder, TvShapes.Capsule))
            Box(Modifier.size(124.dp, TvDims.ButtonH).background(Palette.Placeholder, TvShapes.Capsule))
        }
    }
}
