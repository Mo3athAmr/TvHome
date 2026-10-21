package com.moath.tvhome.home

import android.content.Context
import android.os.Process
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.size.Scale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * Every home image request is built here so a prefetch and the later on-screen load share one
 * memory-cache key (Coil keys on size). Decodes are capped to what is drawn: hero backdrops at
 * 1280×720, cards at their exact on-screen pixel size, logos at 600×240. Hardware bitmaps keep
 * decoded pixels out of the 2 GB box's Java heap, and the memory cache is held to ~40 MB.
 */
object ShelfImages {
    private const val BACKDROP_W = 1280
    private const val BACKDROP_H = 720
    private const val LOGO_W = 600
    private const val LOGO_H = 240
    /** Cards only (heroes keep their own baked pages). Small on purpose: this box has 1.8 GB and swaps. */
    private const val MEMORY_BYTES = 24L * 1024 * 1024
    private const val DECODE_THREADS = 2

    /**
     * Two background-priority threads for decoding and baking art, so image work never competes
     * with the UI and render threads on the box's four A53 cores.
     */
    val background: CoroutineDispatcher = Executors.newFixedThreadPool(DECODE_THREADS) { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "home-images")
    }.asCoroutineDispatcher()

    fun install() {
        SingletonImageLoader.setSafe { ctx ->
            ImageLoader.Builder(ctx)
                .memoryCache { MemoryCache.Builder().maxSizeBytes(MEMORY_BYTES).build() }
                .allowHardware(true)
                .decoderCoroutineContext(background)
                .build()
        }
    }

    /**
     * Hero source pixels: a software bitmap the hero bakes its scrims into (then uploads once as
     * a hardware bitmap). Not memory-cached here — the hero keeps its own tiny cache of baked art.
     */
    fun backdrop(context: Context, url: String): ImageRequest =
        ImageRequest.Builder(context)
            .data(url)
            .size(BACKDROP_W, BACKDROP_H)
            .precision(Precision.INEXACT)
            .allowHardware(false)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build()

    /** A card decoded to exactly [widthPx]×[heightPx], cropped to fill. */
    fun card(context: Context, url: String, widthPx: Int, heightPx: Int): ImageRequest =
        ImageRequest.Builder(context)
            .data(url)
            .size(widthPx, heightPx)
            .scale(Scale.FILL)
            .precision(Precision.EXACT)
            .allowHardware(true)
            .build()

    fun logo(context: Context, url: String): ImageRequest =
        ImageRequest.Builder(context)
            .data(url)
            .size(LOGO_W, LOGO_H)
            .precision(Precision.INEXACT)
            .allowHardware(true)
            .build()

    /** Warms the next hero page's title logo (its artwork is warmed by the hero itself). */
    fun prefetchLogo(context: Context, item: HeroItem?) {
        val url = item?.logoUrl ?: return
        SingletonImageLoader.get(context).enqueue(logo(context, url))
    }
}
