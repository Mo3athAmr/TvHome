package com.moath.tvhome.aerial

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.util.Log
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import androidx.media3.common.util.UnstableApi
import com.moath.tvhome.prefs.LauncherPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * tvOS-style Aerial screensaver: Apple's aerial footage, shuffled, fading through black between
 * clips, with the tvOS location caption and clock. Falls back to a dark ambient gradient + clock
 * when the network is unavailable. Any key wakes (DreamService default, non-interactive).
 */
@UnstableApi
class AerialDreamService : DreamService() {
    private val main = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private var playback: AerialPlayback? = null
    private var overlay: AerialOverlay? = null
    private lateinit var surface: SurfaceView
    private lateinit var scrim: View
    private lateinit var ambient: AerialAmbientView
    private var showLocation = true
    private var prefer4k = false
    private var lastCaption: String? = null
    private var clips: List<AerialClip> = emptyList()

    private val captionTicker = object : Runnable {
        override fun run() {
            val p = playback ?: return
            val clip = p.current
            if (clip != null && p.isClipVisible) {
                val text = clip.captionAt(p.positionMs / 1000)
                if (text != lastCaption) {
                    lastCaption = text
                    overlay?.showCaption(text)
                }
            }
            main.postDelayed(this, TICK_MS)
        }
    }

    private val retry = Runnable { startPlaybackOrAmbient() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true

        val settings = LauncherPrefs.get(this).state.value
        showLocation = settings.aerialShowLocation
        prefer4k = settings.aerialQuality4k

        val root = FrameLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            keepScreenOn = true
            setBackgroundColor(BLACK)
        }
        surface = SurfaceView(this)
        ambient = AerialAmbientView(this).apply { visibility = View.GONE }
        scrim = View(this).apply { setBackgroundColor(BLACK) }
        val fill = FrameLayout.LayoutParams.MATCH_PARENT
        root.addView(surface, FrameLayout.LayoutParams(fill, fill))
        root.addView(ambient, FrameLayout.LayoutParams(fill, fill))
        root.addView(scrim, FrameLayout.LayoutParams(fill, fill))
        overlay = AerialOverlay(this, settings.aerialShowLocation, settings.aerialShowClock, settings.aerialShowPrayerTimes).also { it.attachTo(root) }
        setContentView(root)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        overlay?.startClock()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        val catalogue = AerialCatalogue(this)
        s.launch {
            clips = runCatching { catalogue.load() }
                .onFailure { Log.e(TAG, "Could not load any catalogue", it) }
                .getOrDefault(emptyList())
            Log.i(TAG, "Catalogue ready: ${clips.size} clips")
            startPlaybackOrAmbient()
            // Weekly refresh in the background; the new list is used from the next dream on.
            if (catalogue.isStale()) catalogue.refresh()
        }
    }

    override fun onDreamingStopped() {
        teardown()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        teardown()
        super.onDetachedFromWindow()
    }

    private fun startPlaybackOrAmbient() {
        main.removeCallbacks(retry)
        if (clips.isEmpty() || !isOnline()) {
            Log.w(TAG, if (clips.isEmpty()) "No clips available" else "Offline: showing ambient fallback")
            showAmbient()
            return
        }
        ambient.stop()
        scrim.alpha = 1f
        playback = AerialPlayback(this, surface, scrim, clips, prefer4k, object : AerialPlayback.Listener {
            override fun onClipStarted(clip: AerialClip) {
                lastCaption = clip.captionAt(0)
                overlay?.showCaption(clip.captionAt(0))
            }

            override fun onClipEnding() {
                overlay?.clearCaption()
            }

            override fun onUnavailable() {
                Log.w(TAG, "Aerial playback unavailable after repeated failures; showing ambient fallback")
                stopPlayback()
                showAmbient()
            }
        }).also { it.start() }
        if (showLocation) {
            main.removeCallbacks(captionTicker)
            main.postDelayed(captionTicker, TICK_MS)
        }
    }

    private fun showAmbient() {
        overlay?.clearCaption()
        scrim.animate().cancel()
        scrim.alpha = 0f
        ambient.start()
        main.postDelayed(retry, RETRY_MS)
    }

    private fun stopPlayback() {
        main.removeCallbacks(captionTicker)
        playback?.release()
        playback = null
        lastCaption = null
    }

    private fun teardown() {
        main.removeCallbacksAndMessages(null)
        stopPlayback()
        scope?.cancel()
        scope = null
        overlay?.release()
        if (::ambient.isInitialized) ambient.stop()
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private companion object {
        const val TAG = "Aerial"
        const val BLACK = 0xFF000000.toInt()
        const val TICK_MS = 1_000L
        const val RETRY_MS = 3L * 60 * 1000
    }
}
