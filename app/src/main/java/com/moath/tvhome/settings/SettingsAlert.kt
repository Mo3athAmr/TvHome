package com.moath.tvhome.settings

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.moath.tvhome.design.TvButton

private val AlertScrim = Color(0xF2000000)
private val AlertButtonWidth = 330.dp
private val AlertMessageWidth = 520.dp

/** tvOS alert over the current page: dimmed screen, centred title + message, stacked wide buttons. */
@Composable
internal fun SettingsAlertHost(c: SettingsController) {
    val alert = c.alert
    val last = remember { mutableStateOf<AlertSpec?>(null) }
    if (alert != null && last.value != alert) SideEffect { last.value = alert }
    AnimatedVisibility(
        visible = alert != null,
        enter = fadeIn(tween(200)) + scaleIn(tween(240), initialScale = 1.05f),
        exit = fadeOut(tween(160)),
    ) {
        val shown = alert ?: last.value ?: return@AnimatedVisibility
        SettingsAlert(shown) { c.alert = null }
    }
}

@Composable
private fun SettingsAlert(spec: AlertSpec, onDismiss: () -> Unit) {
    val requesters = remember(spec) { spec.buttons.map { FocusRequester() } }
    var focusIndex by remember(spec) { mutableIntStateOf(spec.focusIndex.coerceIn(spec.buttons.indices)) }
    LaunchedEffect(spec) {
        withFrameNanos { }
        runCatching { requesters[focusIndex].requestFocus() }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(AlertScrim)
            // Focus must not escape to the page underneath: route the D-pad ourselves.
            .onPreviewKeyEvent { e ->
                val delta = when (e.key) {
                    Key.DirectionUp -> -1
                    Key.DirectionDown -> 1
                    Key.DirectionLeft, Key.DirectionRight -> 0
                    else -> return@onPreviewKeyEvent false
                }
                if (e.type == KeyEventType.KeyDown && delta != 0) {
                    focusIndex = (focusIndex + delta).coerceIn(spec.buttons.indices)
                    runCatching { requesters[focusIndex].requestFocus() }
                }
                true
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BasicText(spec.title, style = SettingsText.PageTitle.copy(textAlign = TextAlign.Center))
            spec.message?.let {
                BasicText(
                    it,
                    style = SettingsText.Description.copy(textAlign = TextAlign.Center),
                    modifier = Modifier.width(AlertMessageWidth),
                )
            }
            Spacer(Modifier.height(18.dp))
            spec.buttons.forEachIndexed { i, b ->
                TvButton(
                    text = b.label,
                    onClick = {
                        onDismiss()
                        b.action()
                    },
                    modifier = Modifier.focusRequester(requesters[i]),
                    width = AlertButtonWidth,
                    destructive = b.destructive,
                )
            }
        }
    }
}
