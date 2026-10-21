package com.moath.tvhome.system

import android.view.KeyEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moath.tvhome.R
import com.moath.tvhome.design.TvColors
import com.moath.tvhome.design.TvMotion
import com.moath.tvhome.design.TvType
import com.moath.tvhome.design.UiDirection
import com.moath.tvhome.design.LiquidGlass.liquidGlass
import com.moath.tvhome.design.tvLift
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PANEL_WIDTH = 360.dp
private val PANEL_INSET = 24.dp
private val PANEL_PADDING = 20.dp
private val PANEL_SHAPE = RoundedCornerShape(22.dp)
private val TILE_GAP = 12.dp
private val SQUARE_HEIGHT = 84.dp
private val WIDE_HEIGHT = 64.dp
private val TILE_SHAPE = RoundedCornerShape(16.dp)
private val TILE_PADDING = 14.dp
private val GLYPH = 21.dp
private val TRACK_HEIGHT = 6.dp
private const val STATUS_POLL_MS = 1_500L
private const val SCRIM_LEFT_ALPHA = 0.35f
private const val PANEL_GLASS = 0.3f
private const val TILE_GLASS = 0.4f
private const val PRESSED_SCALE = 0.96f
private val SELECT_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
private val TRACK_IDLE = Color(0x40FFFFFF)
private val TRACK_FOCUSED = Color(0x26000000)

/** A focus position inside the tile grid: row, then column within that row. */
private data class Cell(val row: Int, val col: Int)

/** Squares pack two per row; a wide tile takes a row of its own. */
private fun packRows(kinds: List<CcKind>): List<List<CcKind>> {
    val rows = mutableListOf<List<CcKind>>()
    var pending = mutableListOf<CcKind>()
    for (kind in kinds) {
        if (kind.wide) {
            if (pending.isNotEmpty()) rows += pending.also { pending = mutableListOf() }
            rows += listOf(kind)
        } else {
            pending += kind
            if (pending.size == 2) rows += pending.also { pending = mutableListOf() }
        }
    }
    if (pending.isNotEmpty()) rows += pending
    return rows
}

@Composable
fun ControlCenter(state: OverlayState, controls: SystemControls, onSelect: (CcKind) -> Unit) {
    val rows = remember { packRows(controls.availableKinds()) }
    var focus by remember { mutableStateOf(Cell(0, 0)) }
    // Column to return to after passing through a full-width row (tvOS keeps your lane).
    var lane by remember { mutableStateOf(0) }
    var pressed by remember { mutableStateOf(false) }
    var network by remember { mutableStateOf(controls.networkStatus()) }
    var volume by remember { mutableStateOf(controls.volume()) }
    val presence = rememberPresence(state)

    LaunchedEffect(Unit) {
        while (true) {
            delay(STATUS_POLL_MS)
            network = controls.networkStatus()
            volume = controls.volume()
        }
    }

    OverlayKeys(state) { event ->
        if (rows.isEmpty()) return@OverlayKeys true
        val row = rows[focus.row]
        val kind = row[focus.col]
        if (event.keyCode in SELECT_KEYS) {
            // HIG "highlighted": the tile dips while held; the action runs on release.
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0 && kind != CcKind.Volume) pressed = true
                KeyEvent.ACTION_UP -> if (pressed) {
                    pressed = false
                    onSelect(kind)
                }
            }
            return@OverlayKeys true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return@OverlayKeys true
        pressed = false
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (focus.row > 0) {
                focus = Cell(focus.row - 1, lane.coerceAtMost(rows[focus.row - 1].lastIndex))
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (focus.row < rows.lastIndex) {
                focus = Cell(focus.row + 1, lane.coerceAtMost(rows[focus.row + 1].lastIndex))
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val step = UiDirection.step(leftKey = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT)
                if (kind == CcKind.Volume) volume = controls.stepVolume(step)
                else if (focus.col + step in 0..row.lastIndex) focus = focus.copy(col = focus.col + step).also { lane = it.col }
            }
            else -> return@OverlayKeys false
        }
        true
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = presence.value.coerceIn(0f, 1f) }
                .background(
                    Brush.horizontalGradient(
                        0f to TvColors.Scrim.copy(alpha = TvColors.Scrim.alpha * SCRIM_LEFT_ALPHA),
                        1f to TvColors.Scrim,
                    ),
                ),
        )
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = PANEL_INSET, end = PANEL_INSET)
                .width(PANEL_WIDTH)
                .graphicsLayer {
                    translationX = (1f - presence.value) * (PANEL_WIDTH + PANEL_INSET).toPx()
                    shadowElevation = 24.dp.toPx()
                    shape = PANEL_SHAPE
                }
                // A dark body keeps labels legible over bright video; the glass rides on top.
                .background(TvColors.MaterialThick, PANEL_SHAPE)
                .liquidGlass(PANEL_SHAPE, PANEL_GLASS)
                .padding(PANEL_PADDING),
        ) {
            Clock()
            Spacer(Modifier.height(18.dp))
            Column(verticalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                rows.forEachIndexed { r, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                        row.forEachIndexed { c, kind ->
                            val focused = focus == Cell(r, c)
                            val held = focused && pressed
                            val tileModifier = if (kind.wide) Modifier.fillMaxWidth() else Modifier.weight(1f)
                            when (kind) {
                                CcKind.Volume -> VolumeTile(volume, focused, tileModifier)
                                CcKind.Network -> SquareTile(kind, focused, held, tileModifier, network = network)
                                else -> SquareTile(kind, focused, held, tileModifier)
                            }
                        }
                        // A lone square keeps its half width, like the tvOS grid.
                        if (!row.first().wide && row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun Clock() {
    val context = LocalContext.current
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(MINUTE_MS - value % MINUTE_MS)
            value = System.currentTimeMillis()
        }
    }
    val is24h = android.text.format.DateFormat.is24HourFormat(context)
    // Arabic UI: time and date follow the Arabic locale.
    val time = remember(now, is24h) {
        SimpleDateFormat(if (is24h) "H:mm" else "h:mm", ARABIC).format(Date(now))
    }
    val date = remember(now) { SimpleDateFormat("EEEE، d MMMM", ARABIC).format(Date(now)) }
    BasicText(time, style = TvType.Title1, maxLines = 1)
    BasicText(date, style = TvType.Subhead, maxLines = 1)
}

private const val MINUTE_MS = 60_000L
private val ARABIC = Locale("ar")

@Composable
private fun tileColors(focused: Boolean): Triple<Color, Color, Color> {
    // Idle tiles show the glass underneath; focus floods them white (HIG: illuminate).
    val bg by animateColorAsState(if (focused) TvColors.FocusedFill else Color.Transparent, tween(150), label = "ccBg")
    val fg by animateColorAsState(if (focused) TvColors.FocusedLabel else TvColors.Label, tween(150), label = "ccFg")
    val sub by animateColorAsState(
        if (focused) TvColors.FocusedSecondaryLabel else TvColors.SecondaryLabel, tween(150), label = "ccSub",
    )
    return Triple(bg, fg, sub)
}

@Composable
private fun SquareTile(kind: CcKind, focused: Boolean, held: Boolean, modifier: Modifier, network: NetworkStatus? = null) {
    val (bg, fg, sub) = tileColors(focused)
    val title = when (network) {
        NetworkStatus.Ethernet -> stringResource(R.string.sys_cc_network)
        else -> stringResource(kind.label)
    }
    val subtitle = when (network) {
        null -> null
        is NetworkStatus.Wifi -> network.ssid ?: stringResource(R.string.sys_cc_wifi_connected)
        NetworkStatus.Ethernet -> stringResource(R.string.sys_cc_ethernet)
        NetworkStatus.Disconnected -> stringResource(R.string.sys_cc_wifi_off)
    }
    // A live connection reads in the system accent, the way tvOS tints an active module.
    val glyphTint = if (!focused && network != null && network != NetworkStatus.Disconnected) TvColors.Blue else fg
    Column(
        modifier
            .height(SQUARE_HEIGHT)
            .tvLift(focused, TILE_SHAPE, scale = if (held) PRESSED_SCALE else TvMotion.BUTTON_FOCUS_SCALE)
            .liquidGlass(TILE_SHAPE, TILE_GLASS)
            .background(bg)
            .padding(TILE_PADDING),
    ) {
        Image(
            painterResource(kind.glyph),
            contentDescription = null,
            colorFilter = ColorFilter.tint(glyphTint),
            modifier = Modifier.size(GLYPH),
        )
        Spacer(Modifier.weight(1f))
        BasicText(
            title,
            style = TvType.Body.copy(color = fg),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            BasicText(
                subtitle,
                style = TvType.Caption2.copy(color = sub),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun VolumeTile(level: VolumeLevel, focused: Boolean, modifier: Modifier) {
    val (bg, fg, sub) = tileColors(focused)
    val fill by animateFloatAsState(level.fraction, TvMotion.move(), label = "volume")
    val percent = (level.fraction * 100).toInt()
    Column(
        modifier
            .height(WIDE_HEIGHT)
            .tvLift(focused, TILE_SHAPE, scale = TvMotion.ROW_FOCUS_SCALE)
            .liquidGlass(TILE_SHAPE, TILE_GLASS)
            .background(bg)
            .padding(horizontal = TILE_PADDING, vertical = 12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painterResource(CcKind.Volume.glyph),
                contentDescription = null,
                colorFilter = ColorFilter.tint(fg),
                modifier = Modifier.size(GLYPH),
            )
            Spacer(Modifier.width(10.dp))
            BasicText(stringResource(CcKind.Volume.label), style = TvType.Body.copy(color = fg), modifier = Modifier.weight(1f))
            BasicText("$percent%", style = TvType.Caption1.copy(color = sub))
        }
        // Drawn, not laid out: the fill changes every step and must not trigger a relayout.
        Box(
            Modifier
                .fillMaxWidth()
                .height(TRACK_HEIGHT)
                .drawBehind {
                    val radius = CornerRadius(size.height / 2f)
                    drawRoundRect(if (focused) TRACK_FOCUSED else TRACK_IDLE, cornerRadius = radius)
                    val w = size.width * fill.coerceIn(0f, 1f)
                    if (w > 0f) drawRoundRect(fg, size = Size(maxOf(w, size.height), size.height), cornerRadius = radius)
                },
        )
    }
}
