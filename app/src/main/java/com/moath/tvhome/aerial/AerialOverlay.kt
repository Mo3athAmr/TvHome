package com.moath.tvhome.aerial

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.moath.tvhome.R
import com.moath.tvhome.design.TvDims
import com.moath.tvhome.prayer.PrayerPanel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * tvOS Aerial chrome: the location caption bottom-left and the clock top-right, both inside the
 * tvOS safe area. Plain Views: nothing here redraws per frame — the caption animates alpha only
 * and the clock ticks once a minute.
 */
internal class AerialOverlay(context: Context, showCaption: Boolean, showClock: Boolean, showPrayer: Boolean = false) {
    private val res = context.resources
    private val main = Handler(Looper.getMainLooper())
    private val is24h = DateFormat.is24HourFormat(context)
    // Western digits and LTR on purpose: the box runs an Arabic locale, the UI is English.
    private val clockFormat = SimpleDateFormat(if (is24h) "HH:mm" else "h:mm", Locale.US)

    private val gradient = View(context).apply {
        background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(GRADIENT_BOTTOM, 0))
        alpha = 0f
        visibility = if (showCaption) View.VISIBLE else View.GONE
    }

    private val caption = TextView(context).apply {
        typeface = ResourcesCompat.getFont(context, R.font.plex_arabic_medium)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, CAPTION_SP)
        setTextColor(LABEL_90)
        setShadowLayer(dp(6f), 0f, dp(1f), SHADOW)
        setLineSpacing(0f, 1.12f)
        maxWidth = (res.displayMetrics.widthPixels * 0.42f).toInt()
        textDirection = View.TEXT_DIRECTION_LTR
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        alpha = 0f
        visibility = if (showCaption) View.VISIBLE else View.GONE
    }

    private val clock = TextView(context).apply {
        typeface = ResourcesCompat.getFont(context, R.font.plex_arabic_semibold)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, CLOCK_SP)
        setTextColor(LABEL_90)
        setShadowLayer(dp(8f), 0f, dp(1f), SHADOW)
        textDirection = View.TEXT_DIRECTION_LTR
        includeFontPadding = false
        visibility = if (showClock) View.VISIBLE else View.GONE
    }

    private val prayer: PrayerPanel? = if (showPrayer) PrayerPanel(context) else null

    private val hideCaption = Runnable { fade(caption, 0f, FADE_OUT_MS); fade(gradient, 0f, FADE_OUT_MS) }
    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            clock.text = clockFormat.format(Date(now))
            prayer?.update(now)
            main.postDelayed(this, MINUTE_MS - now % MINUTE_MS + 50)
        }
    }

    fun attachTo(root: FrameLayout) {
        val safeH = dp(TvDims.SafeH.value).toInt()
        val safeV = dp(TvDims.SafeV.value).toInt()
        root.addView(gradient, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(GRADIENT_DP).toInt(), Gravity.BOTTOM))
        root.addView(caption, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.LEFT).apply {
            leftMargin = safeH
            bottomMargin = safeV
        })
        root.addView(clock, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.RIGHT).apply {
            rightMargin = safeH
            topMargin = safeV
        })
        prayer?.let { root.addView(it.view, it.layoutParams(safeH, safeV + dp(PRAYER_TOP_DP).toInt())) }
    }

    fun startClock() {
        if (clock.visibility != View.VISIBLE && prayer == null) return
        main.removeCallbacks(tick)
        tick.run()
    }

    /** Fades [text] in, holds it ~6 s, then fades it out — tvOS behaviour at clip start and POI changes. */
    fun showCaption(text: String) {
        if (caption.visibility != View.VISIBLE || text.isBlank()) return
        main.removeCallbacks(hideCaption)
        val swap = Runnable {
            caption.text = text
            fade(caption, 1f, FADE_IN_MS)
            fade(gradient, 1f, FADE_IN_MS)
            main.postDelayed(hideCaption, FADE_IN_MS + HOLD_MS)
        }
        if (caption.alpha > 0.01f) {
            caption.animate().cancel()
            caption.animate().alpha(0f).setDuration(SWAP_OUT_MS).withEndAction(swap).start()
        } else {
            swap.run()
        }
    }

    fun clearCaption() {
        main.removeCallbacks(hideCaption)
        hideCaption.run()
    }

    fun release() {
        main.removeCallbacksAndMessages(null)
        caption.animate().cancel()
        gradient.animate().cancel()
    }

    private fun fade(view: View, to: Float, ms: Long) {
        view.animate().cancel()
        view.animate().alpha(to).setDuration(ms).start()
    }

    private fun dp(v: Float) = v * res.displayMetrics.density

    private companion object {
        const val CAPTION_SP = 15f
        const val CLOCK_SP = 32f
        const val PRAYER_TOP_DP = 62f
        const val GRADIENT_DP = 150f
        const val LABEL_90 = 0xE6FFFFFF.toInt()
        const val SHADOW = 0x73000000
        const val GRADIENT_BOTTOM = 0x59000000
        const val FADE_IN_MS = 1_000L
        const val FADE_OUT_MS = 1_200L
        const val SWAP_OUT_MS = 400L
        const val HOLD_MS = 6_000L
        const val MINUTE_MS = 60_000L
    }
}
