package com.moath.tvhome.system

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
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
import com.moath.tvhome.dashboard.CityWeather
import com.moath.tvhome.dashboard.DashboardClock
import com.moath.tvhome.dashboard.DashboardData
import com.moath.tvhome.design.TvDims
import com.moath.tvhome.design.UiDirection
import com.moath.tvhome.prefs.LauncherPrefs
import com.moath.tvhome.screensaver.PhotoWidgets
import com.moath.tvhome.screensaver.SettingsHint
import com.moath.tvhome.sports.MatchCard
import com.moath.tvhome.sports.SportsConfig
import com.moath.tvhome.sports.SportsRepository
import com.moath.tvhome.sports.SportsSelection
import kotlinx.coroutines.delay

/**
 * Our widgets over Google TV's photo screen saver (which we cannot draw into): a full-screen,
 * untouchable overlay that comes and goes with the dream. The minute clock is its only animation.
 */
class DreamOverlay(private val service: AccessibilityService) : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: ComposeView? = null

    init {
        saved.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    val isShown: Boolean get() = view != null

    fun show() {
        if (view != null) return
        val prefs = LauncherPrefs.get(service)
        val data = DashboardData(service)
        val compose = ComposeView(service).apply {
            setViewTreeLifecycleOwner(this@DreamOverlay)
            setViewTreeSavedStateRegistryOwner(this@DreamOverlay)
            setViewTreeViewModelStoreOwner(this@DreamOverlay)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                CompositionLocalProvider(LocalLayoutDirection provides UiDirection.layout) {
                    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                    var weather by remember { mutableStateOf<CityWeather?>(null) }
                    val settings = prefs.state.value
                    LaunchedEffect(Unit) { weather = data.weather().firstOrNull() }
                    LaunchedEffect(Unit) {
                        while (true) {
                            delay(60_000 - System.currentTimeMillis() % 60_000 + 200)
                            now = System.currentTimeMillis()
                        }
                    }
                    val matches = remember(now) {
                        val cfg = SportsConfig(settings.sportsLeagues, settings.sportsClubs)
                        SportsSelection.widgetMatches(SportsRepository.get(service).all(), cfg, now).map { MatchCard.of(it, cfg, now) }
                    }
                    Box(Modifier.fillMaxSize().padding(horizontal = TvDims.SafeH, vertical = TvDims.SafeV)) {
                        PhotoWidgets(settings.photoWidgets, DashboardClock.at(now), weather, matches, Modifier.align(Alignment.BottomStart))

                    }
                }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply { title = "tvhome photo widgets" }
        if (runCatching { wm.addView(compose, params) }.onFailure { Log.w("DreamOverlay", "addView failed", it) }.isFailure) return
        view = compose
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun hide() {
        val v = view ?: return
        view = null
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        v.disposeComposition()
        runCatching { wm.removeViewImmediate(v) }
    }
}
