package com.moath.tvhome.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onPlaced
import androidx.compose.runtime.mutableStateMapOf
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.format.DateFormat
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.moath.tvhome.HomeViewModel
import com.moath.tvhome.R
import com.moath.tvhome.design.LiquidGlass.liquidGlass
import com.moath.tvhome.design.TvColors
import com.moath.tvhome.design.TvDims
import com.moath.tvhome.design.TvMotion
import com.moath.tvhome.design.TvShapes
import com.moath.tvhome.home.Bar
import com.moath.tvhome.home.Tab
import com.moath.tvhome.home.Zone
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private const val BAR_SLIDE_DP = 22f
private const val PRESS_DIP = 0.94f

/**
 * tvOS 26's floating Liquid Glass tab bar, top-centre: Search · Home · Movies · TV Shows · Apps ·
 * Settings, with the time at the top-right. It is there while focus is on it or on the hero and
 * slides away as the page scrolls into the rows. [shown] is derived from the page scroll target.
 */
@Composable
fun TabBar(vm: HomeViewModel, shown: State<Boolean>, modifier: Modifier = Modifier) {
    val reveal = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        snapshotFlow { shown.value }.collect { visible ->
            reveal.animateTo(if (visible) 1f else 0f, tween(if (visible) 260 else 180))
        }
    }
    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = reveal.value
                translationY = -(1f - reveal.value) * BAR_SLIDE_DP.dp.toPx()
            },
    ) {
        val bounds = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = HomeDims.BarTop)
                .height(HomeDims.BarH)
                .liquidGlass(TvShapes.Capsule, intensity = 0.35f)
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            SlidingPills(vm, bounds, Modifier.matchParentSize())
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                BarGlyph(vm, Bar.SEARCH, R.drawable.ic_search, bounds)
                for (tab in Tab.entries) BarTab(vm, tab, bounds)
                BarGlyph(vm, Bar.SETTINGS, R.drawable.ic_gear, bounds)
            }
        }
        Avatar(
            vm,
            Modifier
                .align(Alignment.TopStart)
                .padding(top = HomeDims.BarTop + (HomeDims.BarH - AVATAR_SIZE) / 2, start = TvDims.SafeH),
        )
        Clock(Modifier.align(Alignment.TopEnd).padding(top = HomeDims.BarTop + 8.dp, end = TvDims.SafeH))
    }
}

/** 0 idle, 1 focused. */
@Composable
private fun slotFocus(vm: HomeViewModel, slot: Int): State<Float> {
    val focused by remember(slot) { derivedStateOf { vm.page.row == Zone.BAR && vm.barSlot == slot } }
    return animateFloatAsState(if (focused) 1f else 0f, TvMotion.focus(), label = "barFocus")
}

/** 1 while the bar itself holds focus (the selected tab then casts a shadow). */
@Composable
private fun barFocus(vm: HomeViewModel): State<Float> {
    val focused by remember { derivedStateOf { vm.page.row == Zone.BAR } }
    return animateFloatAsState(if (focused) 1f else 0f, tween(200), label = "barHasFocus")
}

@Composable
private fun BarTab(vm: HomeViewModel, tab: Tab, bounds: MutableMap<Int, Pair<Float, Float>>) {
    val slot = Bar.slotOf(tab)
    val focus by slotFocus(vm, slot)
    val isCurrent by remember(tab) { derivedStateOf { vm.tab == tab } }
    val selected by animateFloatAsState(if (isCurrent) 1f else 0f, tween(200), label = "barSel")
    val pressed = vm.pressedKey == HomeViewModel.barKey(slot)
    val bar by barFocus(vm)
    Box(
        Modifier
            .height(HomeDims.BarItemH)
            .reportBounds(slot, bounds)
            .pressDip(pressed)
            .padding(horizontal = HomeDims.BarItemPadH),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            tab.label,
            style = Type.BarLabel,
            maxLines = 1,
            color = { labelColor(focus * bar, selected) },
        )
    }
}

@Composable
private fun BarGlyph(vm: HomeViewModel, slot: Int, @DrawableRes icon: Int, bounds: MutableMap<Int, Pair<Float, Float>>) {
    val focus by slotFocus(vm, slot)
    val pressed = vm.pressedKey == HomeViewModel.barKey(slot)
    val painter = painterResource(icon)
    Box(
        Modifier
            .size(HomeDims.BarItemH)
            .reportBounds(slot, bounds)
            .pressDip(pressed)
            .drawBehind {
                val g = HomeDims.BarGlyph.toPx()
                translate((size.width - g) / 2, (size.height - g) / 2) {
                    with(painter) { draw(Size(g, g), colorFilter = ColorFilter.tint(labelColor(focus, 0f))) }
                }
            },
    )
}

/** Black on the opaque selected/focused pill, secondary on the translucent bar. */
private fun labelColor(focus: Float, selected: Float): Color =
    lerp(TvColors.SecondaryLabel, TvColors.FocusedLabel, maxOf(focus, selected).coerceIn(0f, 1f))

/**
 * HIG tab bar states with one moving highlight, as on Apple TV: the selected tab's opaque pill and
 * the white focus pill each spring from item to item instead of fading in place. Both are drawn in
 * the bar's draw phase from animated bounds, so a move never relayouts the bar.
 */
@Composable
private fun SlidingPills(vm: HomeViewModel, bounds: Map<Int, Pair<Float, Float>>, modifier: Modifier) {
    val focusSlot = vm.barSlot
    val selectedSlot = Bar.slotOf(vm.tab)
    val barFocused by barFocus(vm)
    val fx = animateFloatAsState(bounds[focusSlot]?.first ?: 0f, PILL_SPRING, label = "fx")
    val fw = animateFloatAsState(bounds[focusSlot]?.second ?: 0f, PILL_SPRING, label = "fw")
    val sx = animateFloatAsState(bounds[selectedSlot]?.first ?: 0f, PILL_SPRING, label = "sx")
    val sw = animateFloatAsState(bounds[selectedSlot]?.second ?: 0f, PILL_SPRING, label = "sw")
    val itemH = HomeDims.BarItemH
    Box(
        modifier
            .drawBehind {
                val h = itemH.toPx()
                val top = (size.height - h) / 2
                val r = CornerRadius(h / 2)
                if (sw.value > 0f) {
                    drawRoundRect(Palette.SelectedTab, Offset(sx.value, top), Size(sw.value, h), r)
                }
                if (fw.value > 0f && barFocused > 0.01f) {
                    // Soft lift under the focus pill, then the pill itself.
                    drawRoundRect(Color.Black.copy(alpha = 0.18f * barFocused), Offset(fx.value, top + 2.dp.toPx()), Size(fw.value, h), r)
                    drawRoundRect(TvColors.FocusedFill, Offset(fx.value, top), Size(fw.value, h), r, alpha = barFocused)
                }
            },
    )
}

private fun Modifier.reportBounds(slot: Int, bounds: MutableMap<Int, Pair<Float, Float>>): Modifier =
    onPlaced { c ->
        val x = c.positionInParent().x
        val w = c.size.width.toFloat()
        if (bounds[slot] != x to w) bounds[slot] = x to w
    }

private fun Modifier.pressDip(pressed: Boolean): Modifier = graphicsLayer {
    val dip = if (pressed) PRESS_DIP else 1f
    scaleX = dip
    scaleY = dip
}

private val PILL_SPRING = spring<Float>(dampingRatio = 0.86f, stiffness = 520f)

/** The time, top-right, ticking once a minute (one frame per minute, nothing in between). */
@Composable
private fun Clock(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
    val format = remember(pattern) { SimpleDateFormat(pattern, Locale("ar")) }
    var now by remember { mutableStateOf(format.format(Date())) }
    LaunchedEffect(format) {
        while (true) {
            now = format.format(Date())
            delay(MINUTE_MS - System.currentTimeMillis() % MINUTE_MS + 50)
        }
    }
    BasicText(now, style = Type.Clock, modifier = modifier, maxLines = 1)
}

private const val MINUTE_MS = 60_000L

/**
 * The owner's profile picture at the start corner (top-right in Arabic). It is the first stop of the
 * bar: focused it grows and its ring thickens; OK opens Customize. Without a picture file a plain
 * glass disc stands in, so the stop is never invisible.
 */
@Composable
private fun Avatar(vm: HomeViewModel, modifier: Modifier = Modifier) {
    val image = rememberAvatar()
    val focus by slotFocus(vm, Bar.AVATAR)
    val bar by barFocus(vm)
    val pressed = vm.pressedKey == HomeViewModel.barKey(Bar.AVATAR)
    val f = focus * bar
    val ring = if (f > 0.5f) 4.dp else 2.dp
    Box(
        modifier
            .size(AVATAR_SIZE)
            .graphicsLayer {
                val s = (1f + 0.14f * f) * if (pressed) PRESS_DIP else 1f
                scaleX = s
                scaleY = s
                shadowElevation = (14f * f).dp.toPx()
                shape = CircleShape
                clip = false
            }
            .clip(CircleShape)
            .border(ring, Color.White.copy(alpha = 0.55f + 0.45f * f), CircleShape),
    ) {
        if (image != null) {
            Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().liquidGlass(CircleShape, intensity = 0.5f))
        }
    }
}

/** The owner's profile picture, at the start corner (top-right in the Arabic layout) (a 1:1 image the owner pushed to the app's files dir). */
private val AVATAR_SIZE = 56.dp
private const val AVATAR_FILE = "avatar.png"

/**
 * Decodes the avatar once. Looks in the app's external files dir (`adb push` target, no root needed)
 * then its internal files dir. Returns null when there is no image, so the avatar is simply hidden.
 */
@Composable
private fun rememberAvatar(): ImageBitmap? {
    val context = LocalContext.current
    return remember {
        runCatching {
            val file = listOfNotNull(context.getExternalFilesDir(null), context.filesDir)
                .map { java.io.File(it, AVATAR_FILE) }
                .firstOrNull { it.isFile && it.length() > 0 }
                ?: return@runCatching null
            val opts = BitmapFactory.Options().apply { inSampleSize = 1 }
            BitmapFactory.decodeFile(file.path, opts)?.let { bmp ->
                val side = minOf(bmp.width, bmp.height)
                val square = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
                val target = Bitmap.createScaledBitmap(square, 256, 256, true)
                target.asImageBitmap()
            }
        }.getOrNull()
    }
}
