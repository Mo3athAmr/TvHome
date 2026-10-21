package com.moath.tvhome.system

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
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
import com.moath.tvhome.design.LiquidGlass.liquidGlass
import com.moath.tvhome.design.TvColors
import com.moath.tvhome.design.TvFonts
import com.moath.tvhome.design.UiDirection
import com.moath.tvhome.notify.NoticeKind

data class Notice(val id: Long, val title: String, val body: String, val kind: String, val durationMs: Long) {
    val visible = MutableTransitionState(false)
}

/**
 * Phone-style notices: glass cards that slide up from the bottom over any app and leave by
 * themselves. The window never takes focus or touches, so whatever is playing keeps the remote.
 */
class NoticeOverlay(private val service: AccessibilityService) : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val notices = mutableStateListOf<Notice>()
    private var view: ComposeView? = null
    private var seq = 0L

    init {
        saved.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun post(title: String, body: String, kind: String, durationMs: Long) {
        if (!ensureView()) return
        val n = Notice(++seq, title, body, kind, durationMs)
        notices.add(n)
        n.visible.targetState = true
        while (notices.count { it.visible.targetState } > MAX_SHOWN) hide(notices.first { it.visible.targetState })
        main.postDelayed({ hide(n) }, durationMs)
    }

    private fun hide(n: Notice) {
        n.visible.targetState = false
        main.postDelayed({
            notices.remove(n)
            if (notices.isEmpty()) removeView()
        }, EXIT_MS)
    }

    private fun ensureView(): Boolean {
        if (view != null) return true
        val compose = ComposeView(service).apply {
            setViewTreeLifecycleOwner(this@NoticeOverlay)
            setViewTreeSavedStateRegistryOwner(this@NoticeOverlay)
            setViewTreeViewModelStoreOwner(this@NoticeOverlay)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                CompositionLocalProvider(LocalLayoutDirection provides UiDirection.layout) { Stack(notices) }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
            title = "tvhome notices"
        }
        val ok = runCatching { wm.addView(compose, params) }.onFailure { Log.e(TAG, "Notice addView failed", it) }.isSuccess
        if (!ok) return false
        view = compose
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        return true
    }

    private fun removeView() {
        val v = view ?: return
        view = null
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        v.disposeComposition()
        runCatching { wm.removeViewImmediate(v) }
    }

    fun destroy() {
        main.removeCallbacksAndMessages(null)
        notices.clear()
        removeView()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }

    private companion object {
        const val TAG = "Notices"
        const val MAX_SHOWN = 3
        const val EXIT_MS = 450L
    }
}

private fun style(size: Int, color: Color, weight: FontWeight) =
    TextStyle(fontFamily = TvFonts.Text, fontSize = size.sp, fontWeight = weight, color = color)

private fun glyph(kind: String) = when (kind) {
    NoticeKind.ADHAN -> "🕌"
    NoticeKind.MATCH, NoticeKind.MATCH_SOON -> "⚽"
    NoticeKind.ASSISTANT -> "✨"
    else -> "🔔"
}

@Composable
private fun Stack(notices: List<Notice>) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        notices.forEach { n ->
            key(n.id) {
                AnimatedVisibility(
                    visibleState = n.visible,
                    enter = slideInVertically(spring(dampingRatio = 0.8f, stiffness = 380f)) { it * 2 } + fadeIn(tween(220)),
                    exit = slideOutVertically(tween(380)) { it * 2 } + fadeOut(tween(300)),
                ) { Card(n) }
            }
        }
    }
}

@Composable
private fun Card(n: Notice) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier
            .width(560.dp)
            .background(Color(0xD9121216), shape)
            .liquidGlass(shape, intensity = 0.5f)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).background(Color(0x22FFFFFF), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            BasicText(glyph(n.kind), style = style(20, TvColors.Label, FontWeight.Normal))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            BasicText(n.title, style = style(16, TvColors.Label, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (n.body.isNotBlank()) {
                BasicText(n.body, style = style(13, TvColors.SecondaryLabel, FontWeight.Medium), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
