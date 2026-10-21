package com.moath.tvhome

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.Drawable

object Bitmaps {
    const val TILE_W = 600
    const val TILE_H = 360
    private const val BACKDROP_W = 96
    private const val BACKDROP_H = 54
    private const val BLUR_RADIUS = 5
    private const val BLUR_PASSES = 3
    private const val BASE_VALUE = 0.07f
    private const val MAX_GLOW_VALUE = 0.62f

    fun render(drawable: Drawable, width: Int, height: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        return bmp
    }

    /** A tvOS-style plate for an app that only has a square icon: tinted gradient, icon centred. */
    fun iconPlate(icon: Drawable, accent: Int): Bitmap {
        val bmp = Bitmap.createBitmap(TILE_W, TILE_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, TILE_H.toFloat(),
                shade(accent, 1.18f), shade(accent, 0.72f), Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, TILE_W.toFloat(), TILE_H.toFloat(), paint)
        val size = (TILE_H * 0.56f).toInt()
        val left = (TILE_W - size) / 2
        val top = (TILE_H - size) / 2
        icon.setBounds(left, top, left + size, top + size)
        icon.draw(canvas)
        return bmp
    }

    /** The Settings plate: Apple's light-grey brushed gradient with a dark gear. */
    fun settingsPlate(gear: Drawable): Bitmap {
        val bmp = Bitmap.createBitmap(TILE_W, TILE_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, TILE_H.toFloat(),
                Color.rgb(0xE9, 0xE9, 0xEE), Color.rgb(0x9C, 0x9C, 0xA3), Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, TILE_W.toFloat(), TILE_H.toFloat(), paint)
        val size = (TILE_H * 0.62f).toInt()
        val left = (TILE_W - size) / 2
        val top = (TILE_H - size) / 2
        gear.setBounds(left, top, left + size, top + size)
        gear.draw(canvas)
        return bmp
    }

    /** The Search plate: tvOS's near-black gradient with a white magnifier. */
    fun searchPlate(glass: Drawable): Bitmap {
        val bmp = Bitmap.createBitmap(TILE_W, TILE_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, TILE_H.toFloat(),
                Color.rgb(0x48, 0x48, 0x4E), Color.rgb(0x16, 0x16, 0x19), Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, TILE_W.toFloat(), TILE_H.toFloat(), paint)
        val size = (TILE_H * 0.5f).toInt()
        val left = (TILE_W - size) / 2
        val top = (TILE_H - size) / 2
        glass.setBounds(left, top, left + size, top + size)
        glass.draw(canvas)
        return bmp
    }

    fun averageColor(src: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(src, 12, 12, true)
        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        for (y in 0 until 12) for (x in 0 until 12) {
            val c = small.getPixel(x, y)
            if (Color.alpha(c) < 128) continue
            r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
        }
        if (n == 0L) return Color.rgb(0x2C, 0x2C, 0x2E)
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    /**
     * The Top Shelf's static fallback: the app art flipped, blurred and reduced to its colour.
     * Neutral pixels (white or black banner grounds) collapse to a near-black base and only the artwork's vibrant colours survive as glows, so a white YouTube
     * banner reads as a dark room lit red rather than a grey wash.
     */
    fun blurred(src: Bitmap): Bitmap {
        val w = BACKDROP_W
        val h = BACKDROP_H
        val px = IntArray(w * h)
        Bitmap.createScaledBitmap(src, w, h, true).getPixels(px, 0, w, 0, 0, w, h)
        flipVertically(px, w, h)
        val hsv = FloatArray(3)
        for (i in px.indices) {
            Color.colorToHSV(px[i], hsv)
            val keep = (hsv[1] * hsv[1] * 1.6f).coerceAtMost(1f) * (hsv[2] * 1.4f).coerceAtMost(1f)
            hsv[2] = (hsv[2].coerceAtMost(MAX_GLOW_VALUE)) * keep + BASE_VALUE * (1f - keep)
            hsv[1] = hsv[1] * keep
            px[i] = Color.HSVToColor(hsv)
        }
        repeat(BLUR_PASSES) {
            boxPass(px, w, h, BLUR_RADIUS, horizontal = true)
            boxPass(px, w, h, BLUR_RADIUS, horizontal = false)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** tvOS builds an app's static Top Shelf fallback by flipping and blurring its art. */
    private fun flipVertically(px: IntArray, w: Int, h: Int) {
        val row = IntArray(w)
        for (y in 0 until h / 2) {
            val top = y * w
            val bottom = (h - 1 - y) * w
            System.arraycopy(px, top, row, 0, w)
            System.arraycopy(px, bottom, px, top, w)
            System.arraycopy(row, 0, px, bottom, w)
        }
    }

    private fun boxPass(px: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val out = IntArray(len)
        val n = 2 * r + 1
        for (line in 0 until lines) {
            for (i in 0 until len) {
                var a = 0; var rr = 0; var g = 0; var b = 0
                for (k in -r..r) {
                    val j = (i + k).coerceIn(0, len - 1)
                    val c = if (horizontal) px[line * w + j] else px[j * w + line]
                    a += c ushr 24; rr += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
                }
                out[i] = ((a / n) shl 24) or ((rr / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
            for (i in 0 until len) {
                if (horizontal) px[line * w + i] = out[i] else px[i * w + line] = out[i]
            }
        }
    }

    private fun shade(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) * factor).toInt().coerceIn(0, 255),
        (Color.green(color) * factor).toInt().coerceIn(0, 255),
        (Color.blue(color) * factor).toInt().coerceIn(0, 255),
    )
}
