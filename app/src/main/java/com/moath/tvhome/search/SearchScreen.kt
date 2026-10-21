package com.moath.tvhome.search

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.moath.tvhome.AppEntry
import com.moath.tvhome.design.TvColors
import com.moath.tvhome.design.TvDims
import com.moath.tvhome.design.TvMotion
import com.moath.tvhome.tmdb.TmdbClient
import com.moath.tvhome.tmdb.TmdbItem

interface SearchActions {
    val voiceAvailable: Boolean
    val stremioInstalled: Boolean
    fun launchApp(app: AppEntry)
    fun watchTrailer(item: TmdbItem)
    fun openStremio(item: TmdbItem)
    fun exit()
}

/** Where the focused results row's caption settles once the keyboard has scrolled away. */
private val RowAnchor = 34.dp
private const val KEYBOARD_ROW = -1

/** A title handed over by another surface (Top Shelf "More Info"); Back from it leaves Search. */
data class DeepLink(val id: Long, val mediaType: String)

@Composable
fun SearchApp(model: SearchModel, actions: SearchActions, deepLink: DeepLink? = null) {
    // Arabic UI: right-to-left.
    CompositionLocalProvider(LocalLayoutDirection provides com.moath.tvhome.design.UiDirection.layout) {
        Box(Modifier.fillMaxSize().background(TvColors.AppBackground)) {
            SearchScreen(model, actions, deepLink)
        }
    }
}

@Composable
private fun SearchScreen(model: SearchModel, actions: SearchActions, deepLink: DeepLink?) {
    val keyboardFocus = remember { FocusRequester() }
    val keyboard = remember { KeyboardState() }
    var keyboardFocused by remember { mutableStateOf(false) }
    var focusedRow by remember { mutableIntStateOf(KEYBOARD_ROW) }
    var product by remember { mutableStateOf<TmdbItem?>(null) }
    var shownProduct by remember { mutableStateOf<TmdbItem?>(null) }
    val results = model.results
    val sections = (results as? ResultsState.Ready)?.sections.orEmpty()
    val rowFocus = remember(results) { List(sections.size) { RowFocus() } }
    val rowTops = remember(results) { HashMap<Int, Float>() }
    val scroll = remember { Animatable(0f) }
    val anchorPx = with(LocalDensity.current) { RowAnchor.toPx() }

    val context = LocalContext.current
    LaunchedEffect(Unit) { model.run() }
    LaunchedEffect(deepLink) {
        if (deepLink == null) return@LaunchedEffect
        val item = TmdbClient.get(context).details(deepLink.id, deepLink.mediaType)
        if (item != null) {
            shownProduct = item
            product = item
        }
    }
    LaunchedEffect(Unit) { if (model.picks == null) runCatching { keyboardFocus.requestFocus() } }
    // The assistant's picks: land on the first poster, ready to choose.
    var picksFocused by remember { mutableStateOf(false) }
    LaunchedEffect(results) {
        if (model.picks == null || picksFocused || results !is ResultsState.Ready) return@LaunchedEffect
        repeat(20) {
            kotlinx.coroutines.delay(50)
            if (rowFocus.getOrNull(0)?.restore() == true) { picksFocused = true; return@LaunchedEffect }
        }
        runCatching { keyboardFocus.requestFocus() }
    }
    LaunchedEffect(focusedRow, results) {
        val top = rowTops[focusedRow]
        val target = if (focusedRow == KEYBOARD_ROW || top == null) 0f else minOf(0f, anchorPx - top)
        scroll.animateTo(target, TvMotion.move())
    }

    fun focusKeyboard() = runCatching { keyboardFocus.requestFocus() }
    fun focusRow(i: Int) = rowFocus.getOrNull(i)?.restore() ?: false

    BackHandler {
        when {
            product != null && deepLink != null -> actions.exit()
            product != null -> {
                product = null
                if (!focusRow(focusedRow)) focusKeyboard()
            }
            focusedRow != KEYBOARD_ROW -> {
                // Back lands on the letters, ready to type; Up keeps its spatial target.
                keyboard.onModeRow = false
                focusKeyboard()
            }
            else -> actions.exit()
        }
    }

    // Opened straight onto a title: never draw the search page underneath while the title loads.
    val contentAlpha by animateFloatAsState(if (product == null && deepLink == null) 1f else 0f, tween(220), label = "content")
    Box(Modifier.fillMaxSize().clipToBounds().graphicsLayer { alpha = contentAlpha }) {
        Column(
            Modifier
                .fillMaxWidth()
                .wrapContentHeight(Alignment.Top, unbounded = true)
                .graphicsLayer { translationY = scroll.value },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(TvDims.SafeV + 6.dp))
            SearchField(model.query, showCaret = keyboardFocused, voiceAvailable = actions.voiceAvailable)
            Spacer(Modifier.height(24.dp))
            LinearKeyboard(
                state = keyboard,
                focusRequester = keyboardFocus,
                onType = model::type,
                onDelete = model::deleteLast,
                onUppercaseLast = model::uppercaseLast,
                onExitDown = { focusRow(0) },
                onFocused = { focusedRow = KEYBOARD_ROW },
                modifier = Modifier
                    .padding(horizontal = TvDims.SafeH)
                    .onFocusChanged { keyboardFocused = it.hasFocus },
            )
            Spacer(Modifier.height(30.dp))
            when (results) {
                ResultsState.Idle -> Unit
                is ResultsState.Message -> ResultsMessage(results.text)
                is ResultsState.Ready -> sections.forEachIndexed { i, section ->
                    ResultRow(
                        section = section,
                        focus = rowFocus[i],
                        onFocused = { focusedRow = i },
                        onSelect = { hit ->
                            when (hit) {
                                is AppHit -> actions.launchApp(hit.app)
                                is MediaHit -> {
                                    shownProduct = hit.item
                                    product = hit.item
                                }
                            }
                        },
                        modifier = Modifier
                            .onGloballyPositioned { rowTops[i] = it.positionInParent().y }
                            .padding(bottom = 10.dp)
                            .onPreviewKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (e.nativeKeyEvent.keyCode) {
                                    // Between rows the geometric focus search picks the card
                                    // nearest the one you're on, as tvOS does; only the edges
                                    // are handled here.
                                    AndroidKeyEvent.KEYCODE_DPAD_UP -> (i == 0).also { if (it) focusKeyboard() }
                                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> i == sections.lastIndex
                                    else -> false
                                }
                            },
                    )
                }
            }
            Spacer(Modifier.height(80.dp))
        }
    }

    AnimatedVisibility(visible = product != null, enter = fadeIn(tween(260)), exit = fadeOut(tween(200))) {
        shownProduct?.let { item ->
            ProductPage(
                item = item,
                onTrailer = { actions.watchTrailer(item) },
                stremioInstalled = actions.stremioInstalled,
                onStremio = { actions.openStremio(item) },
            )
        }
    }
}
