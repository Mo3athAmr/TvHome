package com.moath.tvhome.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Settings section artwork as Apple app-icon tiles: a continuous-corner squircle in a system
 * colour with a white glyph (Settings itself is the light-grey tile with a graphite gear).
 * Colours are Apple's dark-appearance system colours; one per section, used as identity only.
 */
private object TileColors {
    val Grey = listOf(Color(0xFFE6E6EB), Color(0xFFA9A9B0))
    val Graphite = listOf(Color(0xFF6E6E74), Color(0xFF3A3A3E))
    val Blue = listOf(Color(0xFF4AA3FF), Color(0xFF0A6CF0))
    val Cyan = listOf(Color(0xFF6AD4FF), Color(0xFF1F8EF0))
    val Indigo = listOf(Color(0xFF8C8AFF), Color(0xFF5450E0))
    val Teal = listOf(Color(0xFF5ED6E0), Color(0xFF1C9AB0))
    val Gear = Color(0xFF3C3C41)
}

/** Apple's continuous corner, approximated: ~22.5% of the side. */
private const val CORNER = 0.225f

@Composable
internal fun IconTile(art: Art, size: Dp, modifier: Modifier = Modifier) {
    val colors = when (art) {
        Art.Gear -> TileColors.Grey
        Art.Display -> TileColors.Blue
        Art.Wifi -> TileColors.Blue
        Art.AppGrid -> TileColors.Cyan
        Art.HomeGrid -> TileColors.Indigo
        Art.Landscape -> TileColors.Teal
        else -> TileColors.Graphite
    }
    Canvas(
        modifier
            .size(size)
            .graphicsLayer {
                shadowElevation = 28.dp.toPx()
                shape = RoundedCornerShape(percent = (CORNER * 100).toInt())
                clip = false
            },
    ) {
        val s = this.size.width
        val tile = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(Offset.Zero, this@Canvas.size), CornerRadius(s * CORNER))) }
        clipPath(tile) {
            drawRect(Brush.verticalGradient(colors))
            // Top sheen, as on Apple's icons.
            drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.18f), 0.5f to Color.Transparent))
            when (art) {
                Art.Gear -> gear(TileColors.Gear)
                Art.Display -> display()
                Art.Wifi -> wifi()
                Art.AppGrid -> appGrid()
                Art.HomeGrid -> homeGrid()
                Art.Remote -> remote()
                Art.Landscape -> landscape()
                else -> device()
            }
        }
    }
}

private val White = Color.White
private fun DrawScope.u(f: Float) = size.width * f
private fun DrawScope.line() = Stroke(width = u(0.055f), cap = StrokeCap.Round)

/** Settings gear: an outer toothed ring and a smaller inner gear, graphite on grey. */
private fun DrawScope.gear(color: Color) {
    val c = center
    drawPath(toothed(c, u(0.40f), u(0.345f), u(0.27f), teeth = 30, toothWidth = 0.5f), color)
    drawPath(toothed(c, u(0.215f), u(0.18f), u(0.085f), teeth = 12, toothWidth = 0.5f), color)
}

private fun toothed(c: Offset, tip: Float, root: Float, hole: Float, teeth: Int, toothWidth: Float): Path {
    val p = Path()
    val step = 2 * PI / teeth
    for (i in 0 until teeth) {
        val a0 = i * step
        val a1 = a0 + step * (1 - toothWidth) / 2
        val a2 = a0 + step * (1 + toothWidth) / 2
        val a3 = a0 + step
        fun at(r: Float, a: Double) = Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
        if (i == 0) at(root, a0).let { p.moveTo(it.x, it.y) }
        at(root, a1).let { p.lineTo(it.x, it.y) }
        at(tip, a1 + step * 0.04).let { p.lineTo(it.x, it.y) }
        at(tip, a2 - step * 0.04).let { p.lineTo(it.x, it.y) }
        at(root, a2).let { p.lineTo(it.x, it.y) }
        at(root, a3).let { p.lineTo(it.x, it.y) }
    }
    p.close()
    p.addOval(Rect(c, hole))
    p.fillType = PathFillType.EvenOdd
    return p
}

private fun DrawScope.display() {
    drawRoundRect(White, Offset(u(0.2f), u(0.27f)), Size(u(0.6f), u(0.38f)), CornerRadius(u(0.05f)), style = line())
    drawLine(White, Offset(u(0.5f), u(0.65f)), Offset(u(0.5f), u(0.73f)), u(0.055f), StrokeCap.Round)
    drawLine(White, Offset(u(0.38f), u(0.75f)), Offset(u(0.62f), u(0.75f)), u(0.055f), StrokeCap.Round)
}

private fun DrawScope.wifi() {
    val c = Offset(u(0.5f), u(0.7f))
    for ((i, r) in listOf(0.14f, 0.26f, 0.38f).withIndex()) {
        drawArc(White, 225f, 90f, false, Offset(c.x - u(r), c.y - u(r)), Size(u(r) * 2, u(r) * 2), style = line())
        if (i == 0) Unit
    }
    drawCircle(White, u(0.045f), c)
}

private fun DrawScope.appGrid() {
    val cell = u(0.15f)
    val gap = u(0.055f)
    val start = (size.width - cell * 3 - gap * 2) / 2
    for (r in 0..2) for (col in 0..2) {
        drawRoundRect(White, Offset(start + col * (cell + gap), start + r * (cell + gap)), Size(cell, cell), CornerRadius(cell * 0.28f))
    }
}

private fun DrawScope.homeGrid() {
    val cell = u(0.2f)
    val gap = u(0.07f)
    val left = (size.width - cell * 2 - gap) / 2
    val top = u(0.22f)
    for (r in 0..1) for (col in 0..1) {
        drawRoundRect(White, Offset(left + col * (cell + gap), top + r * (cell + gap)), Size(cell, cell), CornerRadius(cell * 0.26f), style = line())
    }
    drawLine(White, Offset(u(0.3f), u(0.8f)), Offset(u(0.7f), u(0.8f)), u(0.055f), StrokeCap.Round)
}

private fun DrawScope.remote() {
    drawRoundRect(White, Offset(u(0.38f), u(0.16f)), Size(u(0.24f), u(0.68f)), CornerRadius(u(0.12f)), style = line())
    drawCircle(White, u(0.07f), Offset(u(0.5f), u(0.33f)), style = Stroke(u(0.04f)))
    drawCircle(White, u(0.028f), Offset(u(0.5f), u(0.52f)))
    drawCircle(White, u(0.028f), Offset(u(0.5f), u(0.63f)))
}

private fun DrawScope.device() {
    drawRoundRect(White, Offset(u(0.22f), u(0.3f)), Size(u(0.56f), u(0.4f)), CornerRadius(u(0.09f)), style = line())
    drawCircle(White, u(0.03f), Offset(u(0.68f), u(0.62f)))
}

private fun DrawScope.landscape() {
    drawCircle(Color(0xFFFFE08A), u(0.08f), Offset(u(0.66f), u(0.32f)))
    val far = Path().apply {
        moveTo(0f, u(0.78f)); lineTo(u(0.3f), u(0.48f)); lineTo(u(0.5f), u(0.64f)); lineTo(u(0.72f), u(0.44f)); lineTo(u(1f), u(0.72f)); lineTo(u(1f), u(1f)); lineTo(0f, u(1f)); close()
    }
    drawPath(far, White.copy(alpha = 0.55f))
    val near = Path().apply {
        moveTo(0f, u(0.86f)); lineTo(u(0.34f), u(0.66f)); lineTo(u(0.6f), u(0.82f)); lineTo(u(1f), u(0.62f)); lineTo(u(1f), u(1f)); lineTo(0f, u(1f)); close()
    }
    drawPath(near, White.copy(alpha = 0.9f))
}
