package com.moath.tvhome.aerial

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceView
import android.view.View
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Plays shuffled Aerial clips back to back with a fade through black between them.
 * One ExoPlayer, one decoder: the box has 2 GB of RAM.
 */
@UnstableApi
internal class AerialPlayback(
    context: Context,
    private val surface: SurfaceView,
    /** Black view over the video; its alpha is the fade. */
    private val scrim: View,
    private val clips: List<AerialClip>,
    private val prefer4k: Boolean,
    private val listener: Listener,
) {
    interface Listener {
        fun onClipStarted(clip: AerialClip)
        /** Several clips in a row failed: the network or feed is unusable right now. */
        fun onUnavailable()
        /** The current clip has started fading to black. */
        fun onClipEnding()
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val shuffle = AerialShuffle(clips.size)
    private val cacheFactory = AerialNetwork.cacheDataSourceFactory(app)
    private val precacheExecutor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "aerial-precache") }
    private var precacheJob: Future<*>? = null
    @Volatile private var precacheWriter: CacheWriter? = null

    private var player: ExoPlayer? = null
    var current: AerialClip? = null
        private set
    private var fadingOut = false
    /** True from the clip's first rendered frame until it starts fading out. */
    var isClipVisible = false
        private set
    private var consecutiveErrors = 0
    private var released = false

    private val stallWatchdog = Runnable {
        Log.w(TAG, "Clip ${current?.label} stalled before its first frame; skipping")
        onClipFailed()
    }

    val positionMs: Long get() = player?.currentPosition ?: 0L

    fun start() {
        val exo = ExoPlayer.Builder(app, DefaultRenderersFactory(app).setEnableDecoderFallback(true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheFactory))
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(15_000, 30_000, 2_500, 5_000)
                    .setTargetBufferBytes(BUFFER_BYTES)
                    .setPrioritizeTimeOverSizeThresholds(false)
                    .build(),
            )
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()
        exo.volume = 0f
        exo.repeatMode = Player.REPEAT_MODE_OFF
        exo.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
        exo.setVideoSurfaceView(surface)
        exo.addListener(playerListener)
        player = exo
        scrim.alpha = 1f
        playNext()
    }

    fun release() {
        if (released) return
        released = true
        main.removeCallbacksAndMessages(null)
        scrim.animate().cancel()
        cancelPrecache()
        precacheExecutor.shutdownNow()
        player?.run {
            removeListener(playerListener)
            clearVideoSurfaceView(surface)
            release()
        }
        player = null
    }

    private fun playNext() {
        val exo = player ?: return
        val clip = clips[shuffle.next()]
        current = clip
        fadingOut = false
        isClipVisible = false
        Log.i(TAG, "Playing ${clip.label} (${clip.id})")
        exo.setMediaItem(MediaItem.Builder().setUri(clip.url(prefer4k)).setMediaId(clip.id).build())
        exo.prepare()
        exo.playWhenReady = true
        main.removeCallbacks(stallWatchdog)
        main.postDelayed(stallWatchdog, STALL_TIMEOUT_MS)
    }

    private val playerListener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            main.removeCallbacks(stallWatchdog)
            consecutiveErrors = 0
            isClipVisible = true
            scrim.animate().cancel()
            scrim.animate().alpha(0f).setDuration(FADE_MS).start()
            current?.let(listener::onClipStarted)
            scheduleFadeOut()
            precacheNext()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) fadeOutThenNext()
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "Playback failed for ${current?.label}: ${error.errorCodeName}", error)
            onClipFailed()
        }
    }

    /** Starts the fade exactly FADE_MS before the end using a player message at that position. */
    private fun scheduleFadeOut() {
        val exo = player ?: return
        val duration = exo.duration
        if (duration == C.TIME_UNSET || duration <= FADE_MS * 2) return
        val scheduledFor = current
        exo.createMessage { _, _ -> if (current === scheduledFor) fadeOutThenNext() }
            .setLooper(Looper.getMainLooper())
            .setPosition(duration - FADE_MS)
            .setDeleteAfterDelivery(true)
            .send()
    }

    private fun fadeOutThenNext() {
        if (fadingOut || released) return
        fadingOut = true
        isClipVisible = false
        listener.onClipEnding()
        scrim.animate().cancel()
        scrim.animate().alpha(1f).setDuration(FADE_MS).withEndAction { if (!released) playNext() }.start()
    }

    private fun onClipFailed() {
        main.removeCallbacks(stallWatchdog)
        consecutiveErrors++
        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS || clips.size == 1 && consecutiveErrors > 1) {
            player?.stop()
            listener.onUnavailable()
            return
        }
        fadingOut = false
        main.post { fadeOutThenNext() }
    }

    /**
     * Warms the cache for the next clip: its head (so it starts instantly) and its tail, where
     * Apple's .mov files keep the moov atom. Whole clips are 100–600 MB, so never fully.
     */
    private fun precacheNext() {
        cancelPrecache()
        val uri = Uri.parse(clips[shuffle.peek()].url(prefer4k))
        precacheJob = precacheExecutor.submit {
            try {
                write(DataSpec.Builder().setUri(uri).setPosition(0).setLength(PRECACHE_HEAD_BYTES).build())
                val length = ContentMetadata.getContentLength(AerialNetwork.cache(app).getContentMetadata(uri.toString()))
                if (length > PRECACHE_HEAD_BYTES + PRECACHE_TAIL_BYTES) {
                    write(DataSpec.Builder().setUri(uri).setPosition(length - PRECACHE_TAIL_BYTES).setLength(PRECACHE_TAIL_BYTES).build())
                }
                Log.d(TAG, "Precached head+tail of $uri")
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                if (!released) Log.w(TAG, "Precache failed for $uri: $e")
            }
        }
    }

    private fun write(spec: DataSpec) {
        val writer = CacheWriter(cacheFactory.createDataSourceForDownloading(), spec, null, null)
        precacheWriter = writer
        writer.cache()
    }

    private fun cancelPrecache() {
        precacheWriter?.cancel()
        precacheWriter = null
        precacheJob?.cancel(true)
        precacheJob = null
    }

    private companion object {
        const val TAG = "Aerial"
        const val FADE_MS = 1_000L
        const val STALL_TIMEOUT_MS = 45_000L
        const val MAX_CONSECUTIVE_ERRORS = 4
        const val BUFFER_BYTES = 40 * 1024 * 1024
        const val PRECACHE_HEAD_BYTES = 24L * 1024 * 1024
        const val PRECACHE_TAIL_BYTES = 3L * 1024 * 1024
    }
}
