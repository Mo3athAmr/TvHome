package com.moath.tvhome.system

import android.view.KeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.moath.tvhome.design.TvMotion

/**
 * Shared between the service and an overlay's composable: the service asks it to close (BACK,
 * HOME, an action ran); the composable plays its exit spring and then reports [onDismissed].
 */
class OverlayState(val keys: KeySink, val onDismissed: () -> Unit) {
    var closing by mutableStateOf(false)
        private set

    fun close() {
        closing = true
    }
}

/**
 * Drives an overlay's 0→1 presence: springs in on first composition and back out once
 * [OverlayState.closing] flips, then calls [OverlayState.onDismissed].
 */
@Composable
fun rememberPresence(state: OverlayState): Animatable<Float, *> {
    val presence = remember { Animatable(0f) }
    LaunchedEffect(state.closing) {
        if (state.closing) {
            presence.animateTo(0f, TvMotion.panel())
            state.onDismissed()
        } else {
            presence.animateTo(1f, TvMotion.panel())
        }
    }
    return presence
}

/** Installs [onKey] as the overlay's key handler for as long as the composable is shown. */
@Composable
fun OverlayKeys(state: OverlayState, onKey: (KeyEvent) -> Boolean) {
    val latest by rememberUpdatedState(onKey)
    DisposableEffect(state.keys) {
        state.keys.handler = { event -> !state.closing && latest(event) }
        onDispose { state.keys.handler = null }
    }
}
