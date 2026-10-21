package com.moath.tvhome.ui

import com.moath.tvhome.design.UiDirection
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.moath.tvhome.Dir
import com.moath.tvhome.Nudge
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** How far a card leans toward the move (dp). 2D on purpose: a perspective tilt would force
 * Skia off its analytic rounded-rect clip on low-end TV GPUs. */
private const val SWAY_DP = 3.5f
private const val PRESSED_LIFT = 0.55f
private const val IDLE_EXPAND = 0.035f
private const val IDLE_DIM = 0.42f
private const val SHINE_MS = 750

/**
 * Inactivity (HIG: "out-of-focus content dims and the focused element expands"): 0 while the
 * remote is in use, 1 a few seconds after the last press. One shared animation, read in draw.
 */
val LocalIdle = staticCompositionLocalOf<() -> Float> { { 0f } }

/**
 * The tvOS focused state as one-shot motion — never an idle loop: lift (scale + shadow), a
 * gentle sway toward the direction focus arrived from, and a single shine sweep. Everything is
 * an [Animatable] read in the draw phase, so a card at rest renders nothing.
 */
@Stable
class FocusMotion(focused: Boolean) {
    val lift = Animatable(if (focused) 1f else 0f)
    val swayX = Animatable(0f)
    val swayY = Animatable(0f)
    val shine = Animatable(1f)
}

@Composable
fun rememberFocusMotion(focused: Boolean, pressed: Boolean, arrivedFrom: () -> Dir, nudge: Nudge?): FocusMotion {
    val m = remember { FocusMotion(focused) }
    LaunchedEffect(focused, pressed) {
        launch {
            m.lift.animateTo(
                when {
                    pressed -> PRESSED_LIFT
                    focused -> 1f
                    else -> 0f
                },
                Motion.focus(),
            )
        }
        if (focused && !pressed) {
            launch { sway(m, arrivedFrom()) }
            launch {
                m.shine.snapTo(0f)
                m.shine.animateTo(1f, tween(SHINE_MS, easing = FastOutSlowInEasing))
            }
        }
    }
    // Pushing against an edge: the focused card leans toward it and springs back.
    LaunchedEffect(nudge?.id) {
        if (focused && nudge != null) sway(m, nudge.dir)
    }
    return m
}

private suspend fun sway(m: FocusMotion, dir: Dir) = coroutineScope {
    launch {
        m.swayY.animateTo(dir.dx * SWAY_DP, tween(90))
        m.swayY.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 260f))
    }
    launch {
        m.swayX.animateTo(dir.dy * SWAY_DP, tween(90))
        m.swayX.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 260f))
    }
}

/** Scale, shadow and sway for a focusable card, clipped to [shape]. */
fun Modifier.focusLayer(m: FocusMotion, focusScale: Float, shape: Shape, elevation: Float, idle: () -> Float): Modifier =
    graphicsLayer {
        val l = m.lift.value
        val s = 1f + (focusScale - 1f) * l + IDLE_EXPAND * idle() * l.coerceIn(0f, 1f)
        scaleX = s
        scaleY = s
        translationX = UiDirection.mirror(m.swayY.value.dp.toPx())
        translationY = m.swayX.value.dp.toPx()
        // Inactivity dims what isn't focused by modulating each draw's alpha: no extra fill.
        alpha = 1f - IDLE_DIM * idle() * (1f - l.coerceIn(0f, 1f))
        compositingStrategy = CompositingStrategy.ModulateAlpha
        shadowElevation = (elevation * l.coerceIn(0f, 1f)).dp.toPx()
        this.shape = shape
        clip = true
    }

/**
 * The illuminated surface: a soft top gloss while focused and one diagonal shine sweep as focus
 * lands.
 */
fun Modifier.focusSurface(m: FocusMotion): Modifier = drawWithContent {
    drawContent()
    val l = m.lift.value.coerceIn(0f, 1f)
    if (l <= 0.01f) return@drawWithContent
    drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.14f * l), 0.5f to Color.Transparent))
    val t = m.shine.value
    if (t < 1f) {
        val w = size.width
        val x = -w * 0.6f + (w * 2.2f) * t
        drawRect(
            Brush.linearGradient(
                0f to Color.Transparent,
                0.5f to Color.White.copy(alpha = 0.22f * l * (1f - t)),
                1f to Color.Transparent,
                start = Offset(x - w * 0.35f, 0f),
                end = Offset(x + w * 0.35f, size.height),
            ),
        )
    }
}
