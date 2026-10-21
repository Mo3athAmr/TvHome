package com.moath.tvhome.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.moath.tvhome.Nudge
import kotlinx.coroutines.launch

private const val NUDGE_DP = 9f

/** The rubber-band bump tvOS plays at an edge. Values are in dp; read them in a graphicsLayer. */
class NudgeOffset internal constructor() {
    internal val x = Animatable(0f)
    internal val y = Animatable(0f)
    val dx: Float get() = x.value
    val dy: Float get() = y.value
}

@Composable
fun rememberNudge(nudge: Nudge?, active: Boolean): NudgeOffset {
    val n = remember { NudgeOffset() }
    LaunchedEffect(nudge?.id) {
        if (!active || nudge == null) return@LaunchedEffect
        launch {
            n.x.animateTo(nudge.dir.dx * NUDGE_DP, tween(80))
            n.x.animateTo(0f, spring(dampingRatio = 0.38f, stiffness = 700f))
        }
        launch {
            n.y.animateTo(nudge.dir.dy * NUDGE_DP, tween(80))
            n.y.animateTo(0f, spring(dampingRatio = 0.38f, stiffness = 700f))
        }
    }
    return n
}
