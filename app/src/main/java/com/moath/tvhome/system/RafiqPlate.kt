package com.moath.tvhome.system

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.moath.tvhome.assistant.Orb
import com.moath.tvhome.assistant.Phase
import com.moath.tvhome.design.TvFonts
import com.moath.tvhome.design.UiDirection
import kotlinx.coroutines.delay

/**
 * Rafiq's one screen for a spoken request, from the first word to the answer. It sits at the top where
 * Google's own listening panel appears and fully covers it, so Google can keep listening (it is the only
 * one that can use the remote's microphone) without ever being seen. Shows what is being said, then the
 * answer underneath, typed out as it is spoken. Never takes focus.
 */
class RafiqPlate(private val service: AccessibilityService) : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: ComposeView? = null
    private val phase = mutableStateOf(Phase.Listening)
    private val words = mutableStateOf("")
    private val answer = mutableStateOf("")
    /** While Google's panel is up underneath, the whole screen is dimmed so none of it (results included) is seen. */
    private val coverAll = mutableStateOf(true)

    init {
        saved.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    val visible: Boolean get() = view != null

    fun listening() { words.value = ""; answer.value = ""; phase.value = Phase.Listening; coverAll.value = true; ensure() }

    /** Google's panel is gone: the dim goes, only the plate at the top stays. */
    fun uncover() { coverAll.value = false }
    fun words(text: String) { if (phase.value == Phase.Listening) words.value = text }
    fun thinking(text: String) { words.value = text; answer.value = ""; phase.value = Phase.Thinking; ensure() }
    fun answer(text: String) { answer.value = text; phase.value = Phase.Answer; ensure() }

    /** Invisible but still there (a screenshot of what is underneath), then back. */
    fun setShown(on: Boolean) { view?.alpha = if (on) 1f else 0f }

    fun hide() {
        val v = view ?: return
        view = null
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        v.disposeComposition()
        runCatching { wm.removeViewImmediate(v) }
    }

    private fun ensure() {
        if (view != null) return
        val compose = ComposeView(service).apply {
            setViewTreeLifecycleOwner(this@RafiqPlate)
            setViewTreeSavedStateRegistryOwner(this@RafiqPlate)
            setViewTreeViewModelStoreOwner(this@RafiqPlate)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { CompositionLocalProvider(LocalLayoutDirection provides UiDirection.layout) { Plate(phase.value, words.value, answer.value, coverAll.value) } }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP; title = "rafiq plate" }
        if (runCatching { wm.addView(compose, params) }.onFailure { Log.w(TAG, "plate addView failed", it) }.isSuccess) {
            view = compose
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }
    }

    fun destroy() {
        hide()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }

    companion object { private const val TAG = "RafiqPlate" }
}

private fun style(size: Int, color: Color, weight: FontWeight = FontWeight.Medium) =
    TextStyle(fontFamily = TvFonts.Text, fontSize = size.sp, lineHeight = (size * 1.45f).sp, fontWeight = weight, color = color)

@Composable
private fun Plate(phase: Phase, words: String, answer: String, coverAll: Boolean) {
    // The answer is typed out (about 70 characters a second), in step with it being spoken.
    var shown by remember(answer) { mutableIntStateOf(0) }
    LaunchedEffect(answer) {
        while (shown < answer.length) { shown = (shown + 2).coerceAtMost(answer.length); delay(28) }
    }
    val shape = RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp)
    Box(Modifier.fillMaxSize().then(if (coverAll) Modifier.background(Color(0xF70A0A0E)) else Modifier)) {
    Box(
        Modifier.align(Alignment.TopStart).fillMaxWidth().heightIn(min = 190.dp).background(Color(0xFC0A0A0E), shape)
            .padding(start = 72.dp, end = 72.dp, top = 40.dp, bottom = 34.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Orb(phase, 0.45f, Modifier.size(38.dp))
                Spacer(Modifier.width(18.dp))
                BasicText(
                    words.ifBlank { "أسمعك… 🎙️" }.takeLast(220),
                    style = style(30, if (words.isBlank()) Color(0xB3FFFFFF) else Color.White),
                    maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
            }
            if (answer.isNotEmpty()) {
                BasicText(
                    answer.take(shown),
                    style = style(26, Color(0xE6FFFFFF), FontWeight.Normal),
                    maxLines = 6, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 56.dp),
                )
            }
        }
    }
    }
}
