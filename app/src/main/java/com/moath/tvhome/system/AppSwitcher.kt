package com.moath.tvhome.system

import android.view.KeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moath.tvhome.R
import com.moath.tvhome.design.TvColors
import com.moath.tvhome.design.TvMotion
import com.moath.tvhome.design.TvType
import com.moath.tvhome.design.UiDirection
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

private val CARD_W = 480.dp
private val CARD_H = 270.dp
private val CARD_GAP = 30.dp
private val CARD_SHAPE = RoundedCornerShape(18.dp)
private val LABEL_GAP = 14.dp
private val DISMISS_TRAVEL = 620.dp
private const val NEIGHBOR_SCALE = 0.78f
/** No blur on API 28: the app underneath is pushed far back with a near-opaque wash instead. */
private const val SCRIM_ALPHA = 0.92f
private const val PRESSED_SCALE = 0.96f
private val SELECT_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
private const val ENTER_SCALE = 0.92f
private const val VISIBLE_RANGE = 2.6f
private const val HOME_PREVIEW_COLUMNS = 5
private const val HOME_PREVIEW_ROWS = 2

@Composable
fun AppSwitcher(
    state: OverlayState,
    cards: List<SwitcherCard>,
    onOpen: (SwitcherCard) -> Unit,
    onClosed: (SwitcherCard.App) -> Unit,
) {
    val presence = rememberPresence(state)
    var removed by remember { mutableStateOf(emptySet<String>()) }
    val visible = remember(cards, removed) { cards.filter { it.key !in removed } }
    var focus by remember { mutableStateOf(0) }
    var userMoved by remember { mutableStateOf(false) }
    var dismissing by remember { mutableStateOf<String?>(null) }
    var pressed by remember { mutableStateOf(false) }

    // tvOS lands on the most recent app, not on Home. Cards may arrive after the overlay opens.
    LaunchedEffect(visible.size) {
        if (!userMoved && visible.size > 1) focus = 1
        focus = focus.coerceIn(0, (visible.size - 1).coerceAtLeast(0))
    }
    val focusPos by animateFloatAsState(focus.toFloat(), TvMotion.move(), label = "switcherFocus")

    OverlayKeys(state) { event ->
        if (visible.isEmpty()) return@OverlayKeys true
        if (event.keyCode in SELECT_KEYS) {
            // HIG "highlighted": the card dips while held and opens on release.
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0 && dismissing == null) pressed = true
                KeyEvent.ACTION_UP -> if (pressed) {
                    pressed = false
                    onOpen(visible[focus])
                }
            }
            return@OverlayKeys true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return@OverlayKeys true
        pressed = false
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val next = focus + UiDirection.step(leftKey = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT)
                if (next in 0..visible.lastIndex) { focus = next; userMoved = true }
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                val card = visible[focus]
                if (card is SwitcherCard.App && dismissing == null && event.repeatCount == 0) {
                    dismissing = card.key
                    userMoved = true
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> Unit
            else -> return@OverlayKeys false
        }
        true
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = presence.value.coerceIn(0f, 1f) }
                .background(TvColors.Scrim.copy(alpha = SCRIM_ALPHA)),
        )
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = presence.value.coerceIn(0f, 1f)
                    alpha = p
                    scaleX = ENTER_SCALE + (1f - ENTER_SCALE) * p
                    scaleY = scaleX
                },
            contentAlignment = Alignment.Center,
        ) {
            visible.forEachIndexed { index, card ->
                key(card.key) {
                    val slot by animateFloatAsState(index.toFloat(), TvMotion.move(), label = "slot")
                    val d = slot - focusPos
                    if (abs(d) < VISIBLE_RANGE) {
                        SwitcherItem(
                            card = card,
                            offset = d,
                            dismissing = dismissing == card.key,
                            held = pressed && index == focus,
                            onDismissed = {
                                removed = removed + card.key
                                dismissing = null
                                if (focus > visible.lastIndex - 1) focus = (visible.lastIndex - 1).coerceAtLeast(0)
                                if (card is SwitcherCard.App) onClosed(card)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Horizontal centre offset, in card units, for a card [d] slots from focus. */
private fun slotX(d: Float, pitch: Float, farPitch: Float): Float {
    val a = abs(d)
    return sign(d) * (pitch * min(a, 1f) + farPitch * (a - 1f).coerceAtLeast(0f))
}

@Composable
private fun SwitcherItem(card: SwitcherCard, offset: Float, dismissing: Boolean, held: Boolean, onDismissed: () -> Unit) {
    val press by animateFloatAsState(if (held) PRESSED_SCALE else 1f, TvMotion.focus(), label = "press")
    val lift = remember { Animatable(0f) }
    LaunchedEffect(dismissing) {
        if (dismissing) {
            lift.animateTo(1f, TvMotion.move())
            onDismissed()
        }
    }
    val nearness = 1f - min(abs(offset), 1f)
    val scale = (NEIGHBOR_SCALE + (1f - NEIGHBOR_SCALE) * nearness) * press
    Column(
        Modifier
            .width(CARD_W)
            .graphicsLayer {
                val cardW = CARD_W.toPx()
                val gap = CARD_GAP.toPx()
                val pitch = cardW / 2f + gap + cardW * NEIGHBOR_SCALE / 2f
                translationX = UiDirection.mirror(slotX(offset, pitch, cardW * NEIGHBOR_SCALE + gap))
                translationY = -lift.value * DISMISS_TRAVEL.toPx()
                scaleX = scale
                scaleY = scale
                alpha = 1f - lift.value
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val label = when (card) {
            is SwitcherCard.Home -> stringResource(R.string.sys_switcher_home)
            is SwitcherCard.App -> card.entry.label
        }
        BasicText(
            label,
            style = TvType.Headline.copy(textAlign = TextAlign.Center),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(LABEL_GAP))
        Box(
            Modifier
                .size(CARD_W, CARD_H)
                .graphicsLayer {
                    shadowElevation = (8.dp + 22.dp * nearness).toPx()
                    shape = CARD_SHAPE
                    clip = true
                },
        ) {
            when (card) {
                is SwitcherCard.Home -> HomeSnapshot(card)
                is SwitcherCard.App -> Image(
                    card.entry.tile,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** A miniature of the tvOS Home screen built from the real apps: shelf glow over two app rows. */
@Composable
private fun HomeSnapshot(card: SwitcherCard.Home) {
    Box(Modifier.fillMaxSize().background(TvColors.AppBackground)) {
        card.preview.firstOrNull()?.let { top ->
            Image(
                top.backdrop,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(CARD_H * 0.62f),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 22.dp, end = 22.dp, top = CARD_H * 0.5f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            card.preview.take(HOME_PREVIEW_COLUMNS * HOME_PREVIEW_ROWS).chunked(HOME_PREVIEW_COLUMNS).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { app ->
                        Image(
                            app.tile,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .graphicsLayer {
                                    shape = RoundedCornerShape(5.dp)
                                    clip = true
                                },
                        )
                    }
                    repeat(HOME_PREVIEW_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
