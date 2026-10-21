package com.moath.tvhome.system

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.LayoutDirection
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

/**
 * Receives remote keys for a shown overlay. The accessibility service forwards keys here straight
 * from onKeyEvent, so navigation never depends on which window the platform thinks is focused.
 */
class KeySink {
    var handler: ((KeyEvent) -> Boolean)? = null
    fun dispatch(event: KeyEvent): Boolean = handler?.invoke(event) ?: false
}

/**
 * One full-screen, focusable TYPE_ACCESSIBILITY_OVERLAY window hosting Compose. Owns its own
 * lifecycle / saved-state / view-model owners (a service has none) and tears all of them down in
 * [destroy]. One instance per show — never reused.
 */
class OverlayWindow(private val service: AccessibilityService) :
    LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: ComposeView? = null

    val keys = KeySink()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    val isShown: Boolean get() = view != null

    fun show(content: @Composable () -> Unit): Boolean {
        if (view != null) return true
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        val compose = ComposeView(service).apply {
            setViewTreeLifecycleOwner(this@OverlayWindow)
            setViewTreeSavedStateRegistryOwner(this@OverlayWindow)
            setViewTreeViewModelStoreOwner(this@OverlayWindow)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            isFocusable = true
            isFocusableInTouchMode = true
            setContent {
                // Arabic UI: right-to-left.
                CompositionLocalProvider(LocalLayoutDirection provides com.moath.tvhome.design.UiDirection.layout) { content() }
            }
        }
        val added = runCatching { wm.addView(compose, layoutParams()) }
            .onFailure { Log.e(TAG, "Overlay addView failed", it) }
            .isSuccess
        if (!added) {
            destroyOwners()
            return false
        }
        view = compose
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        compose.requestFocus()
        return true
    }

    fun destroy() {
        val v = view ?: return destroyOwners()
        view = null
        keys.handler = null
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        v.disposeComposition()
        runCatching { wm.removeViewImmediate(v) }.onFailure { Log.w(TAG, "Overlay removeView failed", it) }
        destroyOwners()
    }

    private fun destroyOwners() {
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.CREATED)) {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        }
        store.clear()
    }

    private fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        title = "tvOS overlay"
        windowAnimations = 0
    }

    private companion object {
        const val TAG = "SystemUi"
    }
}
