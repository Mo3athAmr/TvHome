package com.moath.tvhome.search

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * SF Symbols stand-ins drawn as vectors (magnifyingglass, space, delete.left, mic.fill), so the
 * keyboard never depends on a glyph the bundled SF fonts may not carry.
 */

@Composable
fun MagnifierGlyph(color: Color, size: Dp = 16.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val stroke = this.size.minDimension * 0.13f
        val r = this.size.minDimension * 0.34f
        val c = Offset(r + stroke, r + stroke)
        drawCircle(color, r, c, style = Stroke(stroke))
        val d = r * 0.72f
        drawLine(color, Offset(c.x + d, c.y + d), Offset(this.size.width - stroke / 2, this.size.height - stroke / 2), stroke * 1.15f, StrokeCap.Round)
    }
}

@Composable
fun SpaceGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 22.dp, height = 10.dp)) {
        val s = 1.9.dp.toPx()
        val p = Path().apply {
            moveTo(s, 0f)
            lineTo(s, size.height - s)
            lineTo(size.width - s, size.height - s)
            lineTo(size.width - s, 0f)
        }
        drawPath(p, color, style = Stroke(s, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun DeleteGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 24.dp, height = 17.dp)) {
        val s = 1.8.dp.toPx()
        val w = size.width
        val h = size.height
        val tip = h * 0.42f
        val outline = Path().apply {
            moveTo(s, h / 2)
            lineTo(tip, s)
            lineTo(w - s * 2, s)
            quadraticTo(w - s, s, w - s, s * 2)
            lineTo(w - s, h - s * 2)
            quadraticTo(w - s, h - s, w - s * 2, h - s)
            lineTo(tip, h - s)
            close()
        }
        drawPath(outline, color, style = Stroke(s, join = StrokeJoin.Round))
        val cx = (tip + w) / 2 + s * 0.2f
        val k = h * 0.17f
        drawLine(color, Offset(cx - k, h / 2 - k), Offset(cx + k, h / 2 + k), s, StrokeCap.Round)
        drawLine(color, Offset(cx - k, h / 2 + k), Offset(cx + k, h / 2 - k), s, StrokeCap.Round)
    }
}

@Composable
fun MicGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 12.dp, height = 17.dp)) {
        val s = 1.6.dp.toPx()
        val w = size.width
        val capsuleW = w * 0.5f
        val capsuleH = size.height * 0.6f
        drawRoundRect(
            color,
            topLeft = Offset((w - capsuleW) / 2, 0f),
            size = Size(capsuleW, capsuleH),
            cornerRadius = CornerRadius(capsuleW / 2),
        )
        val arc = Path().apply {
            moveTo(s, capsuleH * 0.55f)
            cubicTo(s, capsuleH * 1.35f, w - s, capsuleH * 1.35f, w - s, capsuleH * 0.55f)
        }
        drawPath(arc, color, style = Stroke(s, cap = StrokeCap.Round))
        val stemTop = capsuleH * 1.15f
        drawLine(color, Offset(w / 2, stemTop), Offset(w / 2, size.height - s / 2), s, StrokeCap.Round)
    }
}
