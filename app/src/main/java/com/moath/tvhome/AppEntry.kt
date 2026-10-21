package com.moath.tvhome

import android.content.Intent
import androidx.compose.ui.graphics.ImageBitmap

/**
 * One tile on the home screen.
 *
 * [tile] is the artwork the grid shows (an app banner, or a generated tvOS-style plate for apps
 * that only ship an icon). [backdrop] is a tiny pre-blurred copy of it: API 28 has no
 * RenderEffect, so the Top Shelf's frosted field is blurred once on the CPU at load time and then
 * simply stretched — bilinear upscaling of a 96 px image is the blur.
 */
data class AppEntry(
    val key: String,
    val packageName: String,
    val label: String,
    val category: String?,
    val tile: ImageBitmap,
    val backdrop: ImageBitmap,
    val launchIntent: Intent,
    val isRemovable: Boolean,
    val isPseudo: Boolean = false,
)
