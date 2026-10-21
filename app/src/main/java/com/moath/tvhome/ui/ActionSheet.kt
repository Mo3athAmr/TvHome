package com.moath.tvhome.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.moath.tvhome.Sheet
import com.moath.tvhome.SheetAction

/** tvOS alert: dimmed screen, centred title, a stack of wide rounded buttons. */
@Composable
fun ActionSheetOverlay(sheet: Sheet?) {
    val last = remember { mutableStateOf<Sheet?>(null) }
    if (sheet != null && last.value != sheet) SideEffect { last.value = sheet }
    val shown = sheet ?: last.value

    AnimatedVisibility(
        visible = sheet != null,
        enter = fadeIn(tween(200)) + scaleIn(tween(240), initialScale = 1.04f),
        exit = fadeOut(tween(160)),
    ) {
        if (shown == null) return@AnimatedVisibility
        Box(Modifier.fillMaxSize().background(Palette.Scrim), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BasicText(shown.app.label, style = Type.SheetTitle)
                Spacer(Modifier.height(14.dp))
                shown.actions.forEachIndexed { i, action ->
                    SheetButton(action, focused = i == shown.focus)
                }
            }
        }
    }
}

@Composable
private fun SheetButton(action: SheetAction, focused: Boolean) {
    val scale by animateFloatAsState(if (focused) 1.07f else 1f, Motion.focus(), label = "btnScale")
    val lift by animateFloatAsState(if (focused) 1f else 0f, Motion.focus(), label = "btnLift")
    val bg by animateColorAsState(if (focused) Palette.ButtonFocused else Palette.ButtonIdle, tween(160), label = "btnBg")
    val fg by animateColorAsState(
        when {
            action.destructive && focused -> Palette.DestructiveOnFocused
            action.destructive -> Palette.Destructive
            focused -> Palette.OnFocused
            else -> Palette.Label
        },
        tween(160),
        label = "btnFg",
    )
    Box(
        Modifier
            .size(Dims.SheetButtonW, Dims.SheetButtonH)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                shadowElevation = (lift * 14f).dp.toPx()
                shape = Shapes.SheetButton
                clip = true
            }
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(action.label, style = Type.SheetButton.copy(color = fg))
    }
}
