package com.moath.tvhome.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.moath.tvhome.design.TvShapes
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val ArtSize = 250.dp

/** Brushed aluminium: a sweep of light and dark bands around the centre, like Apple's gear. */
private fun brushed(center: Offset) = Brush.sweepGradient(
    0.00f to Color(0xFFF4F4F7),
    0.10f to Color(0xFFBDBDC4),
    0.22f to Color(0xFFE9E9ED),
    0.36f to Color(0xFFA9A9B0),
    0.50f to Color(0xFFF1F1F4),
    0.62f to Color(0xFFB4B4BB),
    0.76f to Color(0xFFE4E4E9),
    0.88f to Color(0xFFA4A4AB),
    1.00f to Color(0xFFF4F4F7),
    center = center,
)

/** Top-lit sheen laid over metal so it reads as a curved, lit surface. */
private fun sheen(top: Float, bottom: Float) = Brush.verticalGradient(
    0f to Color(0x40FFFFFF),
    0.5f to Color(0x00FFFFFF),
    1f to Color(0x33000000),
    startY = top,
    endY = bottom,
)

private val ShadowColor = Color(0x59000000)

@Composable
internal fun SettingsArt(art: Art, modifier: Modifier = Modifier) {
    Box(modifier.size(ArtSize), contentAlignment = Alignment.Center) {
        when (art) {
            is Art.Tile -> Unit
            else -> IconTile(art, ArtSize * 0.86f)
        }
        when (art) {
            is Art.Tile -> Image(
                art.image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = ArtSize * 1.2f, height = ArtSize * 1.2f * 9f / 16f)
                    .graphicsLayer {
                        shadowElevation = 18.dp.toPx()
                        shape = TvShapes.Large
                        clip = true
                    },
            )
            else -> Unit
        }
    }
}

/** The tvOS Settings gear: fine outer teeth, a recessed channel, an inner ring and six spokes. */
@Composable
private fun GearArt(modifier: Modifier) {
    Box(
        modifier.drawWithCache {
            val c = Offset(size.width / 2, size.height / 2)
            val r = min(size.width, size.height) / 2 * 0.96f
            val outer = toothedRing(c, r, teeth = 36, rootR = r * 0.90f, holeR = r * 0.79f)
            // One unioned path so the sheen is applied once (overlaps would show seams).
            val innerParts = Path.combine(
                PathOperation.Union,
                Path.combine(PathOperation.Union, ring(c, r * 0.69f, r * 0.58f), spokes(c, 6, r * 0.16f, r * 0.62f, r * 0.13f)),
                Path().apply { addOval(Rect(c, r * 0.24f)) },
            )
            val metal = brushed(c)
            val light = sheen(c.y - r, c.y + r)
            val channel = Brush.radialGradient(
                0f to Color(0xFF151517),
                0.8f to Color(0xFF2B2B30),
                1f to Color(0xFF4A4A50),
                center = c,
                radius = r * 0.8f,
            )
            val shadowDy = r * 0.03f
            onDrawBehind {
                translate(top = shadowDy) {
                    drawPath(outer, ShadowColor)
                    drawCircle(ShadowColor, r * 0.79f, c)
                }
                drawCircle(channel, r * 0.79f, c)
                translate(top = shadowDy * 0.7f) { drawPath(innerParts, Color(0x80000000)) }
                for (p in listOf(outer, innerParts)) {
                    drawPath(p, metal)
                    drawPath(p, light)
                }
                drawCircle(Color(0xFF26262A), r * 0.09f, c)
                drawCircle(Color(0x33FFFFFF), r * 0.79f, c, style = Stroke(r * 0.008f))
                drawCircle(Color(0x26000000), r * 0.995f, c, style = Stroke(r * 0.01f))
            }
        },
    )
}

private fun polar(c: Offset, radius: Float, angle: Double) =
    Offset(c.x + radius * cos(angle).toFloat(), c.y + radius * sin(angle).toFloat())

private fun toothedRing(c: Offset, tipR: Float, teeth: Int, rootR: Float, holeR: Float): Path = Path().apply {
    fillType = PathFillType.EvenOdd
    val pitch = 2 * PI / teeth
    for (i in 0 until teeth) {
        val a = i * pitch - PI / 2
        val p0 = polar(c, rootR, a - pitch * 0.30)
        if (i == 0) moveTo(p0.x, p0.y) else lineTo(p0.x, p0.y)
        polar(c, tipR, a - pitch * 0.17).let { lineTo(it.x, it.y) }
        polar(c, tipR, a + pitch * 0.17).let { lineTo(it.x, it.y) }
        polar(c, rootR, a + pitch * 0.30).let { lineTo(it.x, it.y) }
    }
    close()
    addOval(Rect(c, holeR))
}

private fun ring(c: Offset, outerR: Float, innerR: Float): Path = Path().apply {
    fillType = PathFillType.EvenOdd
    addOval(Rect(c, outerR))
    addOval(Rect(c, innerR))
}

private fun spokes(c: Offset, count: Int, from: Float, to: Float, width: Float): Path = Path().apply {
    for (i in 0 until count) {
        val a = i * 2 * PI / count - PI / 2
        val n = a + PI / 2
        val hw = width / 2
        val dx = (cos(n) * hw).toFloat()
        val dy = (sin(n) * hw).toFloat()
        val s = polar(c, from, a)
        val e = polar(c, to, a)
        moveTo(s.x - dx, s.y - dy)
        lineTo(e.x - dx * 1.25f, e.y - dy * 1.25f)
        lineTo(e.x + dx * 1.25f, e.y + dy * 1.25f)
        lineTo(s.x + dx, s.y + dy)
        close()
    }
}

// ---- Section glyphs ------------------------------------------------------------------------------

/** Anodised aluminium for flat parts: soft vertical bands across the width. */
private fun aluminium(left: Float, right: Float) = Brush.horizontalGradient(
    0f to Color(0xFFB4B4BB),
    0.18f to Color(0xFFE6E6EA),
    0.42f to Color(0xFFCFCFD5),
    0.62f to Color(0xFFF2F2F5),
    0.85f to Color(0xFFD2D2D8),
    1f to Color(0xFFA9A9B0),
    startX = left,
    endX = right,
)

private fun DrawScope.metalRoundRect(topLeft: Offset, size: Size, radius: Float) {
    drawRoundRect(ShadowColor, topLeft + Offset(0f, radius * 0.25f + 2f), size, CornerRadius(radius))
    drawRoundRect(aluminium(topLeft.x, topLeft.x + size.width), topLeft, size, CornerRadius(radius))
    drawRoundRect(sheen(topLeft.y, topLeft.y + size.height), topLeft, size, CornerRadius(radius))
}

/** A set-top box seen from slightly above. */
private fun DrawScope.drawDevice() {
    val w = size.width
    val boxW = w * 0.82f
    val boxH = w * 0.2f
    val left = (w - boxW) / 2
    val top = size.height * 0.42f
    val r = w * 0.05f
    drawOval(Color(0x66000000), Offset(left + boxW * 0.04f, top + boxH * 0.75f), Size(boxW * 0.92f, boxH * 0.5f))
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFF3A3A3E), Color(0xFF111113)), top, top + boxH),
        Offset(left, top),
        Size(boxW, boxH),
        CornerRadius(r),
    )
    // Glossy top face.
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFF5A5A60), Color(0xFF242427)), top, top + boxH * 0.42f),
        Offset(left, top),
        Size(boxW, boxH * 0.42f),
        CornerRadius(r),
    )
    drawCircle(Color(0xFFEDEDF0), w * 0.008f, Offset(left + boxW * 0.9f, top + boxH * 0.68f))
}

/** A Siri-style remote: aluminium body, black clickpad, two button columns. */
private fun DrawScope.drawRemote() {
    val bodyW = size.width * 0.3f
    val bodyH = size.height * 0.94f
    val left = (size.width - bodyW) / 2
    val top = (size.height - bodyH) / 2
    val r = bodyW * 0.32f
    metalRoundRect(Offset(left, top), Size(bodyW, bodyH), r)
    val pad = bodyW * 0.84f
    val padC = Offset(size.width / 2, top + bodyW * 0.08f + pad / 2)
    drawCircle(Color(0xFF1B1B1E), pad / 2, padC)
    drawCircle(Color(0xFF34343A), pad * 0.36f, padC, style = Stroke(pad * 0.012f))
    val btn = bodyW * 0.15f
    val colL = size.width / 2 - bodyW * 0.22f
    val colR = size.width / 2 + bodyW * 0.22f
    var y = padC.y + pad / 2 + btn * 2.2f
    repeat(3) {
        drawCircle(Color(0xFF1B1B1E), btn, Offset(colL, y))
        drawCircle(Color(0xFF1B1B1E), btn, Offset(colR, y))
        y += btn * 2.7f
    }
}

/** Nine app tiles in a 3×3 grid. */
private fun DrawScope.drawAppGrid() {
    val cell = size.width * 0.25f
    val gap = size.width * 0.06f
    val total = cell * 3 + gap * 2
    val start = Offset((size.width - total) / 2, (size.height - total) / 2)
    for (row in 0 until 3) for (col in 0 until 3) {
        metalRoundRect(start + Offset(col * (cell + gap), row * (cell + gap)), Size(cell, cell), cell * 0.22f)
    }
}

/** A television: aluminium bezel, dark glass with a soft reflection, a stand. */
private fun DrawScope.drawDisplay() {
    val w = size.width * 0.92f
    val h = w * 9f / 16f
    val left = (size.width - w) / 2
    val top = size.height * 0.16f
    val bezel = w * 0.025f
    metalRoundRect(Offset(left, top), Size(w, h), w * 0.03f)
    val glassTL = Offset(left + bezel, top + bezel)
    val glass = Size(w - bezel * 2, h - bezel * 2)
    drawRoundRect(
        Brush.linearGradient(listOf(Color(0xFF26324A), Color(0xFF0B0D14)), glassTL, glassTL + Offset(glass.width, glass.height)),
        glassTL,
        glass,
        CornerRadius(w * 0.012f),
    )
    val sheenPath = Path().apply {
        moveTo(glassTL.x, glassTL.y)
        lineTo(glassTL.x + glass.width * 0.55f, glassTL.y)
        lineTo(glassTL.x + glass.width * 0.25f, glassTL.y + glass.height)
        lineTo(glassTL.x, glassTL.y + glass.height)
        close()
    }
    drawPath(sheenPath, Color(0x14FFFFFF))
    val neckW = w * 0.08f
    metalRoundRect(Offset(size.width / 2 - neckW / 2, top + h), Size(neckW, size.height * 0.1f), neckW * 0.2f)
    val footW = w * 0.36f
    metalRoundRect(Offset(size.width / 2 - footW / 2, top + h + size.height * 0.1f), Size(footW, size.height * 0.035f), size.height * 0.017f)
}

/** An aerial photograph: dusk sky, sun and layered ridges inside a rounded frame. */
private fun DrawScope.drawLandscape() {
    val w = size.width * 0.94f
    val h = w * 0.66f
    val left = (size.width - w) / 2
    val top = (size.height - h) / 2
    val r = w * 0.06f
    drawRoundRect(ShadowColor, Offset(left, top + r * 0.4f), Size(w, h), CornerRadius(r))
    val clip = Path().apply {
        addRoundRect(androidx.compose.ui.geometry.RoundRect(left, top, left + w, top + h, CornerRadius(r)))
    }
    drawPath(clip, Brush.verticalGradient(listOf(Color(0xFF3B6FB6), Color(0xFF9CC3E6), Color(0xFFF2C89B)), top, top + h * 0.75f))
    drawCircle(Color(0xFFFFF1D6), w * 0.07f, Offset(left + w * 0.7f, top + h * 0.42f))
    fun ridge(base: Float, peaks: List<Pair<Float, Float>>, color: Color) {
        val p = Path().apply {
            moveTo(left, top + h * base)
            peaks.forEach { (x, y) -> lineTo(left + w * x, top + h * y) }
            lineTo(left + w, top + h * base)
            lineTo(left + w, top + h)
            lineTo(left, top + h)
            close()
        }
        drawPath(p, color)
    }
    ridge(0.62f, listOf(0.18f to 0.45f, 0.36f to 0.58f, 0.55f to 0.38f, 0.8f to 0.56f), Color(0xFF5C6E8C))
    ridge(0.78f, listOf(0.12f to 0.62f, 0.4f to 0.74f, 0.66f to 0.58f, 0.9f to 0.72f), Color(0xFF34405A))
    ridge(0.9f, listOf(0.3f to 0.8f, 0.6f to 0.88f, 0.85f to 0.8f), Color(0xFF1B2232))
    drawPath(clip, Color(0x26FFFFFF), style = Stroke(w * 0.006f))
}

/** Wi-Fi fan: three arcs over a dot, in brushed metal. */
private fun DrawScope.drawWifi() {
    val c = Offset(size.width / 2, size.height * 0.72f)
    val stroke = size.width * 0.085f
    val metal = brushed(Offset(size.width / 2, size.height / 2))
    for (i in 1..3) {
        val rr = size.width * (0.14f + i * 0.13f)
        val tl = Offset(c.x - rr, c.y - rr)
        drawArc(ShadowColor, 225f, 90f, false, tl + Offset(0f, 4f), Size(rr * 2, rr * 2), style = Stroke(stroke, cap = StrokeCap.Round))
        drawArc(metal, 225f, 90f, false, tl, Size(rr * 2, rr * 2), style = Stroke(stroke, cap = StrokeCap.Round))
    }
    drawCircle(ShadowColor, stroke * 0.85f, c + Offset(0f, 4f))
    drawCircle(metal, stroke * 0.85f, c)
}

/** A home screen: dark glass with a top shelf band and two rows of tiles. */
private fun DrawScope.drawHomeGrid() {
    val w = size.width * 0.94f
    val h = w * 9f / 16f
    val left = (size.width - w) / 2
    val top = (size.height - h) / 2
    metalRoundRect(Offset(left, top), Size(w, h), w * 0.04f)
    val inset = w * 0.025f
    val glassTL = Offset(left + inset, top + inset)
    val gw = w - inset * 2
    val gh = h - inset * 2
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFF3A2F5C), Color(0xFF101014)), glassTL.y, glassTL.y + gh),
        glassTL,
        Size(gw, gh),
        CornerRadius(w * 0.02f),
    )
    val cols = 5
    val gap = gw * 0.03f
    val tileW = (gw - gap * (cols + 1)) / cols
    val tileH = tileW * 0.6f
    val tints = listOf(Color(0xFFE5E5EA), Color(0xFFFF453A), Color(0xFF0A84FF), Color(0xFF30D158), Color(0xFFFFD60A))
    for (row in 0 until 2) for (col in 0 until cols) {
        val x = glassTL.x + gap + col * (tileW + gap)
        val y = glassTL.y + gh * 0.42f + row * (tileH + gap)
        val tint = if (row == 0) tints[col] else Color(0xFF8E8E93)
        drawRoundRect(tint.copy(alpha = if (row == 0) 0.92f else 0.5f), Offset(x, y), Size(tileW, tileH), CornerRadius(tileW * 0.1f))
    }
}

/** tvOS picker checkmark. */
@Composable
internal fun Checkmark(color: Color, modifier: Modifier = Modifier, width: Dp = 12.dp) {
    Canvas(modifier.size(width = width, height = width * 0.8f)) {
        val s = size
        val stroke = 2.dp.toPx()
        val p = Path().apply {
            moveTo(stroke, s.height * 0.55f)
            lineTo(s.width * 0.38f, s.height - stroke)
            lineTo(s.width - stroke, stroke)
        }
        drawPath(p, color, style = Stroke(stroke, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}
