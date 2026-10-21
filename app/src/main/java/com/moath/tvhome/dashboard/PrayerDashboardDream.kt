package com.moath.tvhome.dashboard

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.service.dreams.DreamService
import android.util.Log
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import androidx.media3.common.util.UnstableApi
import com.moath.tvhome.aerial.AerialAmbientView
import com.moath.tvhome.aerial.AerialCatalogue
import com.moath.tvhome.aerial.AerialClip
import com.moath.tvhome.aerial.AerialPlayback
import com.moath.tvhome.prefs.LauncherPrefs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The prayer-times dashboard as a screen saver, laid over Apple's Aerial footage (dark ambient
 * gradient when offline). Compose redraws only when a value changes — the
 * clock ticks on the minute, prices every 10 minutes, weather every 30 — so it sits idle between.
 */
@UnstableApi
class PrayerDashboardDream : DreamService(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    private var scope: CoroutineScope? = null
    private lateinit var data: DashboardData
    private var state by mutableStateOf<DashboardState?>(null)
    private var weather: List<CityWeather> = emptyList()
    private var instagram: InstagramStats? = null
    private var cards: List<com.moath.tvhome.screensaver.SaverCard> = emptyList()
    private val hint = false
    private var playback: AerialPlayback? = null
    private lateinit var surface: SurfaceView
    private lateinit var scrim: View
    private lateinit var ambient: AerialAmbientView

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        data = DashboardData(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Interactive so OK can open the screen-saver settings; any other key wakes, as before.
        isInteractive = true
        isFullscreen = true
        isScreenBright = true
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@PrayerDashboardDream)
            setViewTreeSavedStateRegistryOwner(this@PrayerDashboardDream)
            setViewTreeViewModelStoreOwner(this@PrayerDashboardDream)
            setContent { state?.let { DashboardScreen(it) } }
        }
        surface = SurfaceView(this)
        ambient = AerialAmbientView(this).apply { visibility = View.GONE }
        scrim = View(this).apply { setBackgroundColor(BLACK) }
        val fill = FrameLayout.LayoutParams.MATCH_PARENT
        val root = FrameLayout(this).apply {
            keepScreenOn = true
            setBackgroundColor(BLACK)
            addView(surface, FrameLayout.LayoutParams(fill, fill))
            addView(ambient, FrameLayout.LayoutParams(fill, fill))
            addView(scrim, FrameLayout.LayoutParams(fill, fill))
            addView(view, FrameLayout.LayoutParams(fill, fill))
            setViewTreeLifecycleOwner(this@PrayerDashboardDream)
            setViewTreeSavedStateRegistryOwner(this@PrayerDashboardDream)
            setViewTreeViewModelStoreOwner(this@PrayerDashboardDream)
        }
        setContentView(root)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        refresh()
        start()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // Any key (OK included) just ends the screen saver and shows whatever was open underneath.
        if (event.action == android.view.KeyEvent.ACTION_UP) finish()
        return true
    }

    /** The owner's picture or video instead of Aerial, when chosen and present. */
    private fun startCustomBackground(): Boolean {
        if (LauncherPrefs.get(this).state.value.prayerBackground != "custom") return false
        val file = com.moath.tvhome.screensaver.Screensaver.customFile(this) ?: return false
        val root = surface.parent as? FrameLayout ?: return false
        scrim.alpha = 0f
        val fill = FrameLayout.LayoutParams.MATCH_PARENT
        if (file.name.endsWith(".mp4")) {
            val video = android.widget.VideoView(this)
            root.addView(video, 1, FrameLayout.LayoutParams(fill, fill))
            video.setVideoPath(file.path)
            video.setOnPreparedListener { mp -> mp.isLooping = true; mp.setVolume(0f, 0f); video.start() }
        } else {
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
            val bmp = android.graphics.BitmapFactory.decodeFile(file.path, opts) ?: return false
            val image = android.widget.ImageView(this).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                setImageBitmap(bmp)
            }
            root.addView(image, 1, FrameLayout.LayoutParams(fill, fill))
        }
        return true
    }

    private fun loadCards() {
        val p = LauncherPrefs.get(this).state.value
        if (!p.rotatingWidget) {
            cards = emptyList()
            return
        }
        val now = System.currentTimeMillis()
        val cfg = com.moath.tvhome.sports.SportsConfig(p.sportsLeagues, p.sportsClubs)
        val matches = com.moath.tvhome.sports.SportsSelection.widgetMatches(com.moath.tvhome.sports.SportsRepository.get(this).all(), cfg, now)
            .map { com.moath.tvhome.sports.MatchCard.of(it, cfg, now) }
        val video = com.moath.tvhome.youtube.YoutubeRepository.get(this).unseen(1).firstOrNull()
        cards = buildList {
            weather.firstOrNull()?.let { add(com.moath.tvhome.screensaver.SaverCard.Weather(it)) }
            if (matches.isNotEmpty()) add(com.moath.tvhome.screensaver.SaverCard.Matches(matches))
            video?.let { add(com.moath.tvhome.screensaver.SaverCard.Video(it.title, it.channel, "https://i.ytimg.com/vi/${it.id}/hqdefault.jpg")) }
        }
    }

    private fun start() {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        s.launch {
            delay(8_000)
            refresh()
        }
        s.launch {
            while (true) {
                loadCards()
                refresh()
                delay(5 * MINUTE_MS)
            }
        }
        s.launch {
            if (startCustomBackground()) return@launch
            val catalogue = AerialCatalogue(this@PrayerDashboardDream)
            val clips = runCatching { catalogue.load() }.getOrDefault(emptyList())
            startVideo(clips)
            if (catalogue.isStale()) catalogue.refresh()
        }
        s.launch {
            while (true) {
                refresh()
                val now = System.currentTimeMillis()
                delay(MINUTE_MS - now % MINUTE_MS + 200)
            }
        }
        s.launch {
            while (true) {
                // The account is set in local.properties (INSTAGRAM_HANDLE); blank = no Instagram block.
                val handle = com.moath.tvhome.BuildConfig.INSTAGRAM_HANDLE
                if (handle.isNotBlank()) instagram = data.instagram(handle) ?: instagram
                refresh()
                delay(INSTAGRAM_MS)
            }
        }
        s.launch {
            while (true) {
                weather = data.weather().ifEmpty { weather }
                loadCards()
                refresh()
                delay(WEATHER_MS)
            }
        }
    }

    private fun refresh() {
        val now = System.currentTimeMillis()
        val minute = (now / MINUTE_MS).toInt()
        val shift = SHIFTS[minute.mod(SHIFTS.size)]
        state = DashboardState(DashboardClock.at(now), weather, instagram, shift.first.dp to shift.second.dp, cards, hint)
    }

    private fun startVideo(clips: List<AerialClip>) {
        if (clips.isEmpty() || !isOnline()) {
            Log.w(TAG, "No aerial video; showing ambient ground")
            showAmbient()
            return
        }
        val prefer4k = LauncherPrefs.get(this).state.value.aerialQuality4k
        playback = AerialPlayback(this, surface, scrim, clips, prefer4k, object : AerialPlayback.Listener {
            override fun onClipStarted(clip: AerialClip) = Unit
            override fun onClipEnding() = Unit
            override fun onUnavailable() {
                playback?.release()
                playback = null
                showAmbient()
            }
        }).also { it.start() }
    }

    private fun showAmbient() {
        scrim.animate().cancel()
        scrim.alpha = 0f
        ambient.start()
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun onDetachedFromWindow() {
        playback?.release()
        playback = null
        if (::ambient.isInitialized) ambient.stop()
        scope?.cancel()
        scope = null
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        super.onDetachedFromWindow()
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "PrayerDashboard"
        const val BLACK = 0xFF000000.toInt()
        const val MINUTE_MS = 60_000L
        /** Logged-out Instagram rate-limits hard; one profile read every 30 minutes stays well clear. */
        const val INSTAGRAM_MS = 30 * MINUTE_MS
        const val WEATHER_MS = 30 * MINUTE_MS
        /** A small orbit so no pixel stays lit in the same place for hours. */
        val SHIFTS = listOf(0 to 0, 3 to 2, 5 to 0, 3 to -2, 0 to -3, -3 to -2, -5 to 0, -3 to 2)
    }
}
