package com.moath.tvhome.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Liquid Glass (HIG › Materials › tvOS: navigation elements, Top Shelf, Control Center; buttons
 * and images adopt it on focus). API 28 can't refract or blur what's behind, so this approximates
 * the material's visible signature: a translucent tinted body, a bright specular rim that is
 * strongest at the top-leading edge, and a soft inner top highlight.
 *
 * [intensity] 0..1 raises the body and rim — use ~0.4 idle and 1.0 focused.
 */
object LiquidGlass {
    val Tint = Color(0xFF8E8E93)

    fun Modifier.liquidGlass(shape: Shape, intensity: Float = 0.5f, tint: Color = Tint): Modifier {
        val i = intensity.coerceIn(0f, 1f)
        return this
            .background(tint.copy(alpha = 0.18f + 0.16f * i), shape)
            .background(
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.10f + 0.14f * i),
                    0.5f to Color.Transparent,
                ),
                shape,
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    0f to Color.White.copy(alpha = 0.55f + 0.35f * i),
                    0.35f to Color.White.copy(alpha = 0.08f),
                    0.7f to Color.White.copy(alpha = 0.04f),
                    1f to Color.White.copy(alpha = 0.28f + 0.2f * i),
                    start = Offset.Zero,
                    end = Offset.Infinite,
                ),
                shape = shape,
            )
            .drawWithContent { drawContent() }
    }
}
