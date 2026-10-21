package com.moath.tvhome.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Focus-driven lift: scale + soft drop shadow, clipped to [shape]. Reports focus through
 * [onFocus] so the caller can swap fills and label colours. Use on anything focusable that is
 * not a Home-screen icon.
 */
@Composable
fun Modifier.tvLift(
    focused: Boolean,
    shape: Shape,
    scale: Float = TvMotion.BUTTON_FOCUS_SCALE,
    elevation: Dp = 16.dp,
): Modifier {
    val s by animateFloatAsState(if (focused) scale else 1f, TvMotion.focus(), label = "liftScale")
    val l by animateFloatAsState(if (focused) 1f else 0f, TvMotion.focus(), label = "lift")
    return this.graphicsLayer {
        scaleX = s
        scaleY = s
        shadowElevation = (elevation * l).toPx()
        this.shape = shape
        clip = true
    }
}

/** A standard tvOS button: translucent when idle, white with black text when focused. */
@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = null,
    destructive: Boolean = false,
    shape: Shape = TvShapes.Regular,
) {
    var focused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(if (focused) TvColors.FocusedFill else TvColors.IdleFill, tween(150), label = "bg")
    val fg = when {
        destructive -> TvColors.Red
        focused -> TvColors.FocusedLabel
        else -> TvColors.Label
    }
    Box(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .then(if (width != null) Modifier.width(width) else Modifier)
            .height(TvDims.ButtonH)
            .tvLift(focused, shape)
            .background(bg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = TvType.Body.copy(color = fg), maxLines = 1)
    }
}

/**
 * A tvOS Settings-style list row: title on the left, value + chevron on the right. tvOS does not
 * use switches — boolean settings show "On"/"Off" as the value and toggle on select.
 */
@Composable
fun TvRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    chevron: Boolean = false,
    enabled: Boolean = true,
    leading: (@Composable (focused: Boolean) -> Unit)? = null,
    onFocus: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(if (focused) TvColors.FocusedFill else Color.Transparent, tween(150), label = "rowBg")
    val titleColor = when {
        !enabled -> TvColors.TertiaryLabel
        focused -> TvColors.FocusedLabel
        else -> TvColors.Label
    }
    val valueColor = if (focused) TvColors.FocusedSecondaryLabel else TvColors.SecondaryLabel
    Row(
        modifier
            .fillMaxWidth()
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus?.invoke()
            }
            .height(TvDims.RowH)
            .tvLift(focused, TvShapes.Regular, scale = TvMotion.ROW_FOCUS_SCALE, elevation = 12.dp)
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        leading?.invoke(focused)
        BasicText(
            title,
            style = TvType.Body.copy(color = titleColor),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (value != null) BasicText(value, style = TvType.Body.copy(color = valueColor), maxLines = 1)
        if (chevron) {
            Spacer(Modifier.width(2.dp))
            Chevron(color = valueColor)
        }
    }
}

/** tvOS grouped-list caption: sentence case, secondary label colour, never uppercase. */
@Composable
fun TvSectionHeader(text: String, modifier: Modifier = Modifier) {
    BasicText(text, style = TvType.Footnote, modifier = modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp))
}

@Composable
fun Chevron(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 6.dp, height = 11.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.8.dp.toPx()
        drawLine(color, Offset(stroke, stroke), Offset(w - stroke, h / 2), stroke, StrokeCap.Round)
        drawLine(color, Offset(w - stroke, h / 2), Offset(stroke, h - stroke), stroke, StrokeCap.Round)
    }
}
