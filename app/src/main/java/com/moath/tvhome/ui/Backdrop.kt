package com.moath.tvhome.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.request.SuccessResult
import coil3.toBitmap
import com.moath.tvhome.design.TvColors
import com.moath.tvhome.home.ShelfImages
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.withContext

private const val PARALLAX = 0.35f
/** Fully scrolled into the rows the art has faded to black, so rows draw over nothing at all. */
private const val MAX_DIM = 1f
private const val DIM_STEPS = 48

@Stable
private class BackdropState {
    var current by mutableStateOf<ImageBitmap?>(null)
    var previous by mutableStateOf<ImageBitmap?>(null)
    val fade = Animatable(1f)
    private val filters = arrayOfNulls<ColorFilter>(DIM_STEPS + 1)

    /** Darkening as a lighting filter on the bitmap itself — no extra full-screen scrim layer. */
    fun filter(dim: Float): ColorFilter? {
        val step = (dim.coerceIn(0f, 1f) * DIM_STEPS).roundToInt()
        if (step == 0) return null
        return filters[step] ?: run {
            val k = 1f - MAX_DIM * step / DIM_STEPS
            ColorFilter.lighting(Color(k, k, k), Color.Black)
        }.also { filters[step] = it }
    }

    suspend fun show(next: ImageBitmap?) {
        if (next == current && previous == null) return
        previous = current
        current = next
        fade.snapTo(0f)
        fade.animateTo(1f, tween(Motion.HERO_FADE_MS))
        previous = null
    }
}

/**
 * The full-bleed hero artwork behind the tab bar and rows. One opaque bitmap draw at rest (two
 * only during a short crossfade); its scrims are baked in by [HeroArt]. Scrolling into the rows
 * darkens it through a colour filter and slides it up — both read in the draw phase only, so the
 * page scroll never recomposes or relayouts anything here.
 *
 * [scroll] is the page scroll in dp; [heroH] is the scroll at which the hero is fully dimmed.
 * [plain] draws the Apps tab's clean dark field instead.
 */
@Composable
fun Backdrop(url: String?, plain: Boolean, scroll: () -> Float, heroH: Float, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state = remember { BackdropState() }
    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect state.show(null)
        val art = HeroArt.get(context, url) ?: return@LaunchedEffect
        state.show(art)
    }
    Spacer(
        modifier
            .fillMaxSize()
            .drawBehind {
                val s = scroll()
                val dim = (s / heroH).coerceIn(0f, 1f)
                val ty = -s.dp.toPx() * PARALLAX
                val f = state.fade.value
                val cur = state.current
                val prev = state.previous
                // The baked art is opaque: at rest it is the only full-screen draw. The base is
                // painted only where the art doesn't cover (fades, Apps tab, the parallax gap).
                when {
                    plain && (cur == null || f < 1f) -> drawRect(TvColors.AppBackground)
                    cur == null || (f < 1f && prev == null) -> drawRect(Color.Black)
                    ty != 0f -> drawRect(Color.Black, topLeft = Offset(0f, size.height + ty), size = Size(size.width, -ty))
                }
                if (dim >= 0.99f) {
                    drawRect(Color.Black)
                    return@drawBehind
                }
                val filter = state.filter(dim)
                prev?.let { drawCover(it, ty, if (cur == null) 1f - f else 1f, filter) }
                cur?.let { drawCover(it, ty, f, filter) }
            },
    )
}

private fun DrawScope.drawCover(image: ImageBitmap, ty: Float, alpha: Float, filter: ColorFilter?) {
    if (alpha <= 0f) return
    val scale = max(size.width / image.width, size.height / image.height)
    val w = (image.width * scale).roundToInt()
    val h = (image.height * scale).roundToInt()
    drawImage(
        image,
        dstOffset = IntOffset(((size.width - w) / 2).roundToInt(), ty.roundToInt()),
        dstSize = IntSize(w, h),
        alpha = alpha,
        colorFilter = filter,
    )
}

/**
 * Hero artwork with its legibility scrims baked in once, off the main thread, then uploaded as an
 * opaque hardware bitmap. Drawing it costs one opaque full-screen pass per frame on low-end TV GPUs
 * instead of a bitmap plus three blended gradients. Holds the last few pages (~3.7 MB each).
 */
object HeroArt {
    private val cache = LruCache<String, ImageBitmap>(CACHE_PAGES)

    /** Drops the baked pages (Home is hidden; the memory is worth more to the app in front). */
    fun clear() = cache.evictAll()

    suspend fun get(context: Context, url: String): ImageBitmap? {
        cache.get(url)?.let { return it }
        val result = SingletonImageLoader.get(context).execute(ShelfImages.backdrop(context, url))
        val src = (result as? SuccessResult)?.image?.toBitmap() ?: return null
        // The source isn't memory-cached (see ShelfImages.backdrop): free its pixels now rather than at some
        // later GC — on this 1.8 GB box native memory left for the collector means swapping.
        val baked = withContext(ShelfImages.background) { bake(src).also { if (!src.isRecycled) src.recycle() } }.asImageBitmap()
        cache.put(url, baked)
        return baked
    }

    private fun bake(src: Bitmap): Bitmap {
        val w = src.width.toFloat()
        val h = src.height.toFloat()
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(src, 0f, 0f, null)
        val paint = Paint()
        fun shade(x0: Float, y0: Float, x1: Float, y1: Float, colors: IntArray, stops: FloatArray, rect: RectF) {
            paint.shader = LinearGradient(x0, y0, x1, y1, colors, stops, Shader.TileMode.CLAMP)
            canvas.drawRect(rect, paint)
        }
        // Leading edge, behind the title block.
        shade(0f, 0f, w * 0.62f, 0f, intArrayOf(black(0.62f), black(0.30f), 0), floatArrayOf(0f, 0.45f, 1f), RectF(0f, 0f, w * 0.62f, h))
        // Bottom, where the details and the first row sit.
        shade(0f, h * 0.42f, 0f, h, intArrayOf(0, black(0.55f), black(0.88f)), floatArrayOf(0f, 0.55f, 1f), RectF(0f, h * 0.42f, w, h))
        // A thin band under the tab bar and clock.
        shade(0f, 0f, 0f, h * 0.2f, intArrayOf(black(0.45f), 0), floatArrayOf(0f, 1f), RectF(0f, 0f, w, h * 0.2f))
        out.setHasAlpha(false)
        val hardware = out.copy(Bitmap.Config.HARDWARE, false) ?: return out
        out.recycle()
        return hardware
    }

    private fun black(alpha: Float): Int = android.graphics.Color.argb((alpha * 255).roundToInt(), 0, 0, 0)

    private const val CACHE_PAGES = 4
}
