package com.moath.tvhome.aerial

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout

/**
 * Offline fallback: a slow, dark, drifting gradient — no imagery. Two hardware layers crossfade
 * between palettes every [STEP_MS]; only alpha/scale animate, so the GPU just recomposites.
 */
internal class AerialAmbientView(context: Context) : FrameLayout(context) {
    private val main = Handler(Looper.getMainLooper())
    private val layers = List(2) { View(context).also { addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) } }
    private var front = 0
    private var paletteIndex = 0
    private var running = false

    private val step = object : Runnable {
        override fun run() {
            paletteIndex = (paletteIndex + 1) % PALETTES.size
            val back = layers[1 - front]
            paint(back, paletteIndex)
            back.scaleX = 1.15f; back.scaleY = 1.15f
            back.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(STEP_MS).start()
            layers[front].animate().alpha(0f).setDuration(STEP_MS).start()
            front = 1 - front
            main.postDelayed(this, STEP_MS)
        }
    }

    init {
        setBackgroundColor(0xFF000000.toInt())
        layers.forEach { it.setLayerType(LAYER_TYPE_HARDWARE, null); it.alpha = 0f }
        paint(layers[0], 0)
        layers[0].alpha = 1f
    }

    fun start() {
        if (running) return
        running = true
        visibility = VISIBLE
        alpha = 0f
        animate().alpha(1f).setDuration(2_000).start()
        main.postDelayed(step, STEP_MS)
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        layers.forEach { it.animate().cancel() }
        animate().cancel()
        visibility = GONE
    }

    private fun paint(view: View, index: Int) {
        val (a, b, c) = PALETTES[index]
        val orientation = if (index % 2 == 0) GradientDrawable.Orientation.TL_BR else GradientDrawable.Orientation.BL_TR
        view.background = GradientDrawable(orientation, intArrayOf(a, b, c))
    }

    private companion object {
        const val STEP_MS = 20_000L
        // Deep night tones: dusk navy, ocean teal, aurora plum, ember.
        val PALETTES = listOf(
            Triple(0xFF0E1A33.toInt(), 0xFF070B16.toInt(), 0xFF020306.toInt()),
            Triple(0xFF0A2629.toInt(), 0xFF05121A.toInt(), 0xFF020507.toInt()),
            Triple(0xFF1E1233.toInt(), 0xFF0B0819.toInt(), 0xFF030208.toInt()),
            Triple(0xFF2A1612.toInt(), 0xFF120A0B.toInt(), 0xFF040203.toInt()),
        )
    }
}
