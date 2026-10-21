package com.moath.tvhome.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/** Line glyphs drawn in code (no icon font on the box). Each fits a square [size]. */
enum class Glyph {
    Sun, Moon, PartlyCloudy, Cloud, Fog, Rain, Snow, Thunder, Mosque, Calendar, Crescent,
    /** Prayer-time line glyphs, drawn in the text colour. */
    Dawn, Noon, Afternoon, Sunset, Night,
}

/** WMO weather code → glyph. Night swaps the clear sun for a moon. */
fun weatherGlyph(code: Int, isDay: Boolean = true): Glyph = when (code) {
    0 -> if (isDay) Glyph.Sun else Glyph.Moon
    1, 2 -> Glyph.PartlyCloudy
    3 -> Glyph.Cloud
    45, 48 -> Glyph.Fog
    in 51..67, in 80..82 -> Glyph.Rain
    in 71..77, 85, 86 -> Glyph.Snow
    in 95..99 -> Glyph.Thunder
    else -> Glyph.Cloud
}

/** SF Symbols "multicolor" palette for weather: yellow sun, white cloud, cyan precipitation. */
object GlyphColors {
    val Sun = Color(0xFFFFD60A)
    val Cloud = Color(0xFFF2F2F7)
    val Drop = Color(0xFF64D2FF)
}

@Composable
fun GlyphIcon(glyph: Glyph, size: Dp, color: Color = Color.White, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val stroke = Stroke(width = this.size.minDimension * 0.085f, cap = StrokeCap.Round)
        val cloud = GlyphColors.Cloud
        when (glyph) {
            Glyph.Sun -> sun(center, this.size.minDimension * 0.2f, GlyphColors.Sun, stroke)
            Glyph.Moon -> moon(cloud)
            Glyph.PartlyCloudy -> {
                sun(Offset(this.size.width * 0.38f, this.size.height * 0.36f), this.size.minDimension * 0.15f, GlyphColors.Sun, stroke)
                cloud(cloud, dx = 0.08f, dy = 0.1f, scale = 0.8f)
            }
            Glyph.Cloud -> cloud(cloud)
            Glyph.Fog -> {
                cloud(cloud, dy = -0.08f, scale = 0.85f)
                line(cloud, stroke, 0.22f, 0.88f, 0.78f)
            }
            Glyph.Rain -> {
                cloud(cloud, dy = -0.1f, scale = 0.85f)
                for (x in listOf(0.36f, 0.52f, 0.68f)) {
                    drawLine(GlyphColors.Drop, Offset(this.size.width * x, this.size.height * 0.78f), Offset(this.size.width * (x - 0.05f), this.size.height * 0.92f), stroke.width, StrokeCap.Round)
                }
            }
            Glyph.Snow -> {
                cloud(cloud, dy = -0.1f, scale = 0.85f)
                for (x in listOf(0.36f, 0.52f, 0.68f)) drawCircle(cloud, stroke.width * 0.8f, Offset(this.size.width * x, this.size.height * 0.86f))
            }
            Glyph.Thunder -> {
                cloud(cloud, dy = -0.1f, scale = 0.85f)
                val p = Path().apply {
                    moveTo(this@Canvas.size.width * 0.55f, this@Canvas.size.height * 0.66f)
                    lineTo(this@Canvas.size.width * 0.44f, this@Canvas.size.height * 0.82f)
                    lineTo(this@Canvas.size.width * 0.56f, this@Canvas.size.height * 0.82f)
                    lineTo(this@Canvas.size.width * 0.46f, this@Canvas.size.height * 0.97f)
                }
                drawPath(p, GlyphColors.Sun, style = stroke)
            }
            Glyph.Dawn -> horizonSun(color, stroke, rising = true)
            Glyph.Noon -> sunOutline(center, this.size.minDimension * 0.2f, color, stroke, rays = 8)
            Glyph.Afternoon -> sunOutline(Offset(center.x, this.size.height * 0.56f), this.size.minDimension * 0.17f, color, stroke, rays = 5)
            Glyph.Sunset -> horizonSun(color, stroke, rising = false)
            Glyph.Night -> {
                drawPath(crescentPath(), color, style = stroke)
                drawCircle(color, stroke.width * 0.75f, Offset(this.size.width * 0.8f, this.size.height * 0.22f))
                drawCircle(color, stroke.width * 0.55f, Offset(this.size.width * 0.66f, this.size.height * 0.1f))
            }
            Glyph.Mosque -> mosque(color, stroke)
            Glyph.Calendar -> calendar(color, stroke)
            Glyph.Crescent -> crescentOutline(color, stroke)
        }
    }
}

private fun DrawScope.sun(c: Offset, r: Float, color: Color, stroke: Stroke) {
    drawCircle(color, r, c)
    for (i in 0 until 8) {
        val a = Math.toRadians(i * 45.0)
        val from = Offset(c.x + cos(a).toFloat() * r * 1.55f, c.y + sin(a).toFloat() * r * 1.55f)
        val to = Offset(c.x + cos(a).toFloat() * r * 2.1f, c.y + sin(a).toFloat() * r * 2.1f)
        drawLine(color, from, to, stroke.width, StrokeCap.Round)
    }
}

private fun DrawScope.sunOutline(c: Offset, r: Float, color: Color, stroke: Stroke, rays: Int) {
    drawCircle(color, r, c, style = stroke)
    val step = if (rays == 8) 45.0 else 36.0
    val start = if (rays == 8) 0.0 else 180.0
    for (i in 0 until rays) {
        val a = Math.toRadians(start + i * step)
        val from = Offset(c.x + cos(a).toFloat() * r * 1.6f, c.y + sin(a).toFloat() * r * 1.6f)
        val to = Offset(c.x + cos(a).toFloat() * r * 2.15f, c.y + sin(a).toFloat() * r * 2.15f)
        drawLine(color, from, to, stroke.width, StrokeCap.Round)
    }
}

/** Half sun on the horizon with an arrow: up for dawn, down for sunset (SF "sunrise"/"sunset"). */
private fun DrawScope.horizonSun(color: Color, stroke: Stroke, rising: Boolean) {
    val w = size.width
    val h = size.height
    val y = h * 0.72f
    val r = w * 0.2f
    drawArc(color, 180f, 180f, false, Offset(w / 2 - r, y - r), Size(r * 2, r * 2), style = stroke)
    drawLine(color, Offset(w * 0.1f, y), Offset(w * 0.9f, y), stroke.width, StrokeCap.Round)
    for (deg in listOf(200.0, 270.0, 340.0)) {
        val a = Math.toRadians(deg)
        drawLine(
            color,
            Offset(w / 2 + cos(a).toFloat() * r * 1.5f, y + sin(a).toFloat() * r * 1.5f),
            Offset(w / 2 + cos(a).toFloat() * r * 1.95f, y + sin(a).toFloat() * r * 1.95f),
            stroke.width,
            StrokeCap.Round,
        )
    }
    val ay = h * 0.9f
    val tip = if (rising) ay - h * 0.08f else ay + h * 0.06f
    val base = if (rising) ay + h * 0.02f else ay - h * 0.04f
    drawLine(color, Offset(w * 0.42f, base), Offset(w / 2, tip), stroke.width, StrokeCap.Round)
    drawLine(color, Offset(w * 0.58f, base), Offset(w / 2, tip), stroke.width, StrokeCap.Round)
}

private fun DrawScope.cloudPath(dx: Float, dy: Float, scale: Float): Path {
    val w = size.width
    val h = size.height
    fun cx(f: Float) = w * (0.5f + (f - 0.5f) * scale + dx)
    fun cy(f: Float) = h * (0.5f + (f - 0.5f) * scale + dy)
    val a = Path().apply { addOval(Rect(Offset(cx(0.36f), cy(0.58f)), w * 0.17f * scale)) }
    val b = Path().apply { addOval(Rect(Offset(cx(0.58f), cy(0.48f)), w * 0.23f * scale)) }
    val base = Path().apply {
        addRoundRect(RoundRect(Rect(cx(0.18f), cy(0.56f), cx(0.86f), cy(0.78f)), CornerRadius(h * 0.11f * scale)))
    }
    return Path().apply {
        op(a, b, PathOperation.Union)
        op(this, base, PathOperation.Union)
    }
}

private fun DrawScope.cloud(color: Color, dx: Float = 0f, dy: Float = 0f, scale: Float = 1f) {
    drawPath(cloudPath(dx, dy, scale), color, style = Fill)
}

private fun DrawScope.line(color: Color, stroke: Stroke, from: Float, y: Float, to: Float) {
    drawLine(color, Offset(size.width * from, size.height * y), Offset(size.width * to, size.height * y), stroke.width, StrokeCap.Round)
}

private fun DrawScope.crescentPath(): Path {
    val r = size.minDimension * 0.36f
    val outer = Path().apply { addOval(Rect(center, r)) }
    val cut = Path().apply { addOval(Rect(Offset(center.x + r * 0.55f, center.y - r * 0.35f), r * 0.85f)) }
    return Path().apply { op(outer, cut, PathOperation.Difference) }
}

private fun DrawScope.moon(color: Color) = drawPath(crescentPath(), color)

private fun DrawScope.crescentOutline(color: Color, stroke: Stroke) = drawPath(crescentPath(), color, style = stroke)

private fun DrawScope.calendar(color: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    drawRoundRect(color, Offset(w * 0.14f, h * 0.2f), Size(w * 0.72f, h * 0.66f), CornerRadius(w * 0.12f), style = stroke)
    drawLine(color, Offset(w * 0.14f, h * 0.4f), Offset(w * 0.86f, h * 0.4f), stroke.width)
    for (x in listOf(0.34f, 0.66f)) drawLine(color, Offset(w * x, h * 0.1f), Offset(w * x, h * 0.28f), stroke.width, StrokeCap.Round)
    for (row in 0..1) for (col in 0..2) {
        drawCircle(color, stroke.width * 0.55f, Offset(w * (0.32f + col * 0.18f), h * (0.56f + row * 0.15f)))
    }
}

private fun DrawScope.mosque(color: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    // Base, central dome with finial, and two minarets.
    drawLine(color, Offset(w * 0.1f, h * 0.88f), Offset(w * 0.9f, h * 0.88f), stroke.width, StrokeCap.Round)
    val dome = Path().apply {
        moveTo(w * 0.3f, h * 0.88f)
        lineTo(w * 0.3f, h * 0.58f)
        cubicTo(w * 0.3f, h * 0.36f, w * 0.5f, h * 0.34f, w * 0.5f, h * 0.24f)
        cubicTo(w * 0.5f, h * 0.34f, w * 0.7f, h * 0.36f, w * 0.7f, h * 0.58f)
        lineTo(w * 0.7f, h * 0.88f)
    }
    drawPath(dome, color, style = stroke)
    drawLine(color, Offset(w * 0.5f, h * 0.24f), Offset(w * 0.5f, h * 0.12f), stroke.width, StrokeCap.Round)
    for (x in listOf(0.14f, 0.86f)) drawLine(color, Offset(w * x, h * 0.88f), Offset(w * x, h * 0.36f), stroke.width, StrokeCap.Round)
    drawLine(color, Offset(w * 0.44f, h * 0.88f), Offset(w * 0.44f, h * 0.72f), stroke.width)
    drawLine(color, Offset(w * 0.56f, h * 0.88f), Offset(w * 0.56f, h * 0.72f), stroke.width)
}
