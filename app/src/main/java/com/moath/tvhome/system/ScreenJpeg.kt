package com.moath.tvhome.system

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream

/** A screenshot shrunk to 1024 px wide JPEG — enough for a model to read the screen, small to upload. */
internal object ScreenJpeg {
    @RequiresApi(Build.VERSION_CODES.R)
    fun encode(result: AccessibilityService.ScreenshotResult): ByteArray? {
        val buffer = result.hardwareBuffer
        try {
            val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return null
            val soft = hw.copy(Bitmap.Config.ARGB_8888, false)
            hw.recycle()
            val w = 1024
            val h = (soft.height * w / soft.width.toFloat()).toInt()
            val small = Bitmap.createScaledBitmap(soft, w, h, true)
            if (small !== soft) soft.recycle()
            val out = ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.JPEG, 72, out)
            small.recycle()
            return out.toByteArray()
        } finally {
            buffer.close()
        }
    }
}
