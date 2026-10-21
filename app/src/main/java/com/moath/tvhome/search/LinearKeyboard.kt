package com.moath.tvhome.search

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moath.tvhome.design.LiquidGlass.liquidGlass
import com.moath.tvhome.design.TvColors
import com.moath.tvhome.design.TvFonts
import com.moath.tvhome.design.TvShapes
import com.moath.tvhome.design.TvType
import com.moath.tvhome.design.UiDirection

enum class KeyMode(val label: String, val chars: String) {
    Arabic("ع", "ابتثجحخدذرزسشصضطظعغفقكلمنهويىءآأإؤئة"),
    Lower("abc", "abcdefghijklmnopqrstuvwxyz"),
    Upper("ABC", "ABCDEFGHIJKLMNOPQRSTUVWXYZ"),
    Digits("123", "1234567890-/:;()&@.,?!'\""),
    Symbols("#+=", "[]{}#%^*+=_\\|~<>$€£¥•.,?!'"),
}

sealed interface Key {
    val units: Int
    data class Text(val text: String) : Key { override val units = 1 }
    data object Space : Key { override val units = 2 }
    data object Delete : Key { override val units = 2 }
}

private fun KeyMode.keys(): List<Key> = listOf(Key.Space) + chars.map { Key.Text(it.toString()) } + Key.Delete

/** Survives focus leaving the keyboard, so Up from results lands on the key you left. */
class KeyboardState {
    var mode by mutableStateOf(KeyMode.Arabic)
    var index by mutableIntStateOf(1)
    var onModeRow by mutableStateOf(false)
    var modeIndex by mutableIntStateOf(0)
}

private val KeyStyle = TvType.Title3.copy(fontFamily = TvFonts.Display, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
private const val KEY_FOCUS_SCALE = 1.4f
private val KeyRowH = 46.dp
private val PillH = 44.dp
private val LetterPillW = 40.dp
private fun <T> pillSpring() = spring<T>(dampingRatio = 0.82f, stiffness = 1100f)

/**
 * tvOS linear keyboard: one row of keys spanning the screen plus the mode segment below. The
 * whole keyboard is a single focus target that handles the D-pad itself, so key repeat moves
 * the pill at the remote's repeat rate with no focus-search cost per step.
 */
@Composable
fun LinearKeyboard(
    state: KeyboardState,
    focusRequester: FocusRequester,
    onType: (String) -> Unit,
    onDelete: () -> Unit,
    onUppercaseLast: () -> Unit,
    onExitDown: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val keys = remember(state.mode) { state.mode.keys() }

    fun press(repeat: Int) {
        if (state.onModeRow) {
            if (repeat == 0) {
                state.mode = KeyMode.entries[state.modeIndex]
                state.index = state.index.coerceAtMost(state.mode.keys().lastIndex)
            }
            return
        }
        when (val key = keys[state.index]) {
            Key.Delete -> onDelete()
            Key.Space -> if (repeat == 0) onType(" ")
            is Key.Text -> when {
                repeat == 0 -> onType(key.text)
                repeat == 1 && state.mode == KeyMode.Lower && key.text[0].isLetter() -> onUppercaseLast()
            }
        }
    }

    fun handle(event: KeyEvent): Boolean {
        val native = event.nativeKeyEvent
        val code = native.keyCode
        if (event.type != KeyEventType.KeyDown) return code in HANDLED_KEYS
        when (code) {
            AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                val left = code == AndroidKeyEvent.KEYCODE_DPAD_LEFT
                // Like the system keyboard: moving past either end wraps round to the other end.
                if (state.onModeRow) {
                    state.modeIndex = (state.modeIndex + UiDirection.step(left)).mod(KeyMode.entries.size)
                } else {
                    // Latin/digit keys stay left-to-right; only the Arabic layout runs right-to-left.
                    val d = if (left == (state.mode == KeyMode.Arabic && UiDirection.isRtl)) 1 else -1
                    state.index = (state.index + d).mod(keys.size)
                }
            }
            AndroidKeyEvent.KEYCODE_DPAD_DOWN ->
                if (state.onModeRow) {
                    onExitDown()
                } else {
                    state.modeIndex = state.mode.ordinal
                    state.onModeRow = true
                }
            AndroidKeyEvent.KEYCODE_DPAD_UP -> state.onModeRow = false
            AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER ->
                press(native.repeatCount)
            AndroidKeyEvent.KEYCODE_DEL -> onDelete()
            else -> {
                val ch = native.unicodeChar
                if (ch == 0 || Character.isISOControl(ch)) return false
                onType(String(Character.toChars(ch)))
            }
        }
        return true
    }

    Column(
        modifier
            .focusRequester(focusRequester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .onKeyEvent(::handle)
            .focusable(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val keyDirection = if (state.mode == KeyMode.Arabic) UiDirection.layout else androidx.compose.ui.unit.LayoutDirection.Ltr
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalLayoutDirection provides keyDirection,
        ) { KeyRow(keys, state.index, active = focused && !state.onModeRow) }
        Spacer(Modifier.height(10.dp))
        ModeRow(state.mode, state.modeIndex, active = focused && state.onModeRow)
    }
}

private val HANDLED_KEYS = setOf(
    AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_DPAD_UP,
    AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER,
    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER, AndroidKeyEvent.KEYCODE_DEL,
)

@Composable
private fun KeyRow(keys: List<Key>, index: Int, active: Boolean) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(KeyRowH)) {
        val unit = maxWidth / keys.sumOf { it.units }
        val lefts = remember(keys, unit) {
            var acc = 0.dp
            keys.map { k -> acc.also { acc += unit * k.units } }
        }
        val focusedKey = keys[index]
        val keyW = unit * focusedKey.units
        val targetW = if (focusedKey is Key.Text) maxOf(LetterPillW, keyW) else keyW + 8.dp
        val center by animateDpAsState(lefts[index] + keyW / 2, pillSpring(), label = "pillX")
        val pillW by animateDpAsState(targetW, pillSpring(), label = "pillW")
        val pillAlpha by animateFloatAsState(if (active) 1f else 0f, tween(160), label = "pillA")

        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset((center - pillW / 2).roundToPx(), 0) }
                .width(pillW)
                .height(PillH)
                .graphicsLayer {
                    alpha = pillAlpha
                    scaleX = 0.92f + 0.08f * pillAlpha
                    scaleY = scaleX
                }
                .liquidGlass(TvShapes.Regular, intensity = 1f),
        )
        keys.forEachIndexed { i, key ->
            KeyCell(key, focused = active && i == index, left = lefts[i], width = unit * key.units)
        }
    }
}

@Composable
private fun KeyCell(key: Key, focused: Boolean, left: Dp, width: Dp) {
    val scale by animateFloatAsState(if (focused) KEY_FOCUS_SCALE else 1f, pillSpring(), label = "keyScale")
    val color = if (focused) TvColors.Label else TvColors.SecondaryLabel
    Box(
        Modifier
            .offset(x = left)
            .width(width)
            .fillMaxHeight()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        contentAlignment = Alignment.Center,
    ) {
        when (key) {
            is Key.Text -> BasicText(key.text, style = KeyStyle.copy(color = color), maxLines = 1)
            Key.Space -> SpaceGlyph(color)
            Key.Delete -> DeleteGlyph(color)
        }
    }
}

@Composable
private fun ModeRow(mode: KeyMode, focusIndex: Int, active: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        KeyMode.entries.forEachIndexed { i, m ->
            val focused = active && i == focusIndex
            val scale by animateFloatAsState(if (focused) 1.12f else 1f, pillSpring(), label = "modeScale")
            val fill by animateFloatAsState(if (focused) 1f else 0f, tween(140), label = "modeFill")
            val color = when {
                focused || m == mode -> TvColors.Label
                else -> TvColors.TertiaryLabel
            }
            Box(
                Modifier
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .height(26.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = fill }
                        .liquidGlass(TvShapes.Capsule, intensity = 1f),
                )
                BasicText(
                    m.label,
                    style = TvType.Caption1.copy(color = color, fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
            }
        }
    }
}

/** The field above the keyboard: magnifier, typed text in Title3, soft blinking caret. */
@Composable
fun SearchField(query: String, showCaret: Boolean, voiceAvailable: Boolean, modifier: Modifier = Modifier) {
    // HIG: text and search fields take a focus ring (collections take a highlight).
    val ringAlpha by animateFloatAsState(if (showCaret) 0.6f else 0f, tween(180), label = "ring")
    Row(
        modifier
            .width(560.dp)
            .height(46.dp)
            .background(TvColors.IdleFill, TvShapes.Regular)
            .border(FieldRingW, TvColors.Label.copy(alpha = ringAlpha), TvShapes.Regular)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MagnifierGlyph(TvColors.SecondaryLabel, 17.dp)
        Spacer(Modifier.width(10.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (query.isEmpty() && !showCaret) {
                BasicText("بحث", style = TvType.Title3.copy(color = TvColors.SecondaryLabel), maxLines = 1)
            } else {
                if (query.isNotEmpty()) {
                    BasicText(
                        query.takeLast(VISIBLE_CHARS),
                        style = TvType.Title3,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                if (showCaret) Caret()
                if (query.isEmpty()) {
                    BasicText("بحث", style = TvType.Title3.copy(color = TvColors.TertiaryLabel), maxLines = 1, modifier = Modifier.padding(start = 3.dp))
                }
            }
        }
        if (voiceAvailable) {
            Spacer(Modifier.width(10.dp))
            MicGlyph(TvColors.SecondaryLabel)
        }
    }
}

private const val VISIBLE_CHARS = 36
private val FieldRingW = 1.5.dp

@Composable
private fun Caret() {
    val t = rememberInfiniteTransition(label = "caret")
    val a by t.animateFloat(1f, 0.15f, infiniteRepeatable(tween(560), RepeatMode.Reverse), label = "caretA")
    Box(
        Modifier
            .padding(start = 2.dp)
            .width(2.5.dp)
            .height(28.dp)
            .drawBehind { drawRoundRect(TvColors.Label.copy(alpha = a), cornerRadius = CornerRadius(size.width / 2)) },
    )
}
