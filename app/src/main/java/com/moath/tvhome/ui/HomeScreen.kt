package com.moath.tvhome.ui

import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import android.view.KeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.moath.tvhome.Dir
import com.moath.tvhome.HomeViewModel
import com.moath.tvhome.design.UiDirection
import com.moath.tvhome.home.RowScroll
import com.moath.tvhome.home.ShelfImages
import com.moath.tvhome.home.Tab
import com.moath.tvhome.home.TabPage
import com.moath.tvhome.home.Zone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val LONG_PRESS_MS = 550L
private const val VIEWPORT_DP = 540f
private const val IDLE_AFTER_MS = 4_000L
private const val IDLE_IN_MS = 700
private const val IDLE_OUT_MS = 140
private const val PREFETCH_SETTLE_MS = 80L
/** Build the other tabs after Home is on screen, one per step, so startup never stalls. */
private const val PREWARM_AFTER_MS = 1_500L
private const val PREWARM_STEP_MS = 400L

/**
 * Home, Apple TV app style: full-bleed hero behind a floating Liquid Glass tab bar, rows below.
 * Layering is deliberately flat — one backdrop draw node, one page, the bar — and every animation
 * is one-shot and read in the draw phase, so at rest the screen renders nothing at all.
 */
@Composable
fun HomeScreen(vm: HomeViewModel) {
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val keys = remember { KeyRouter(vm, scope) }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val (shownTab, contentAlpha) = rememberShownTab(vm)
    // Every tab page stays composed once built; a switch only changes which layer is drawn, so it
    // never composes a whole page (hero + rows + images) inside one frame.
    val composed = remember { mutableStateListOf(vm.tab) }
    if (shownTab !in composed) composed += shownTab
    LaunchedEffect(Unit) {
        delay(PREWARM_AFTER_MS)
        vm.prefetchTabs()
        for (t in Tab.entries) {
            if (t !in composed) {
                composed += t
                delay(PREWARM_STEP_MS)
            }
        }
    }
    val scrolls = remember { Tab.entries.associateWith { Animatable(0f) } }
    val page = vm.pages.getValue(shownTab)
    val scroll = scrolls.getValue(shownTab)
    val barShown = remember(page) { derivedStateOf { page.row == Zone.BAR || scrollTarget(vm, page) == 0f } }
    val idle = rememberIdle(vm)

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { keys.handle(it.nativeKeyEvent) },
    ) {
        HeroBackdrop(page, scroll)
        CompositionLocalProvider(LocalIdle provides { idle.value }) {
            for (tab in Tab.entries) {
                if (tab !in composed) continue
                key(tab) {
                    PageLayer(vm, vm.pages.getValue(tab), scrolls.getValue(tab), { tab == shownTab }, contentAlpha)
                }
            }
        }
        TabBar(vm, barShown)
        EditHint(vm)
        ActionSheetOverlay(vm.sheet)
        HeroAutoplay(vm, page)
    }
}

/** One tab's content. Hidden layers keep their composition but draw nothing (alpha 0 is skipped). */
@Composable
private fun PageLayer(
    vm: HomeViewModel,
    page: TabPage,
    scroll: Animatable<Float, *>,
    visible: () -> Boolean,
    contentAlpha: Animatable<Float, *>,
) {
    val target = remember(page) { derivedStateOf { scrollTarget(vm, page) } }
    LaunchedEffect(page) {
        scroll.snapTo(target.value)
        snapshotFlow { target.value }.collectLatest { scroll.animateTo(it, Motion.scroll()) }
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = if (visible()) contentAlpha.value else 0f
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
    ) {
        if (page.tab == Tab.Apps) {
            AppsGrid(vm, page, { scroll.value })
        } else {
            PageContent(vm, page, { scroll.value })
        }
    }
}

/**
 * HIG inactivity: a few seconds after the last press, out-of-focus content dims and the focused
 * item expands — one 700 ms animation, then nothing. Any press restores it at once.
 */
@Composable
private fun rememberIdle(vm: HomeViewModel): Animatable<Float, *> {
    val idle = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        snapshotFlow { vm.inputSeq }.collectLatest {
            if (idle.value > 0f) idle.animateTo(0f, tween(IDLE_OUT_MS))
            delay(IDLE_AFTER_MS)
            if (!vm.editing && vm.sheet == null) idle.animateTo(1f, tween(IDLE_IN_MS))
        }
    }
    return idle
}

/** Tab switches fade the old page out and the new one in — never both at once (one page drawn). */
@Composable
private fun rememberShownTab(vm: HomeViewModel): Pair<Tab, Animatable<Float, *>> {
    var shown by remember { mutableStateOf(vm.tab) }
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        snapshotFlow { vm.tab }.collectLatest { next ->
            if (next != shown) {
                alpha.animateTo(0f, tween(Motion.TAB_OUT_MS))
                shown = next
            }
            alpha.animateTo(1f, tween(Motion.TAB_IN_MS))
        }
    }
    return shown to alpha
}

private fun scrollTarget(vm: HomeViewModel, page: TabPage): Float {
    if (page.tab == Tab.Apps) return gridScroll(page)
    val heights = pageRowHeights(vm, page)
    val top = if (page.hasHero) HomeDims.HeroH.value else HomeDims.AppsFirstTop.value
    // Apps-first (no hero): the first row already sits under the bar, so it doesn't scroll.
    val rowTop = if (!page.hasHero && page.row == 0) top else HomeDims.RowTop.value
    return RowScroll.pageScroll(page.row, top, heights, rowTop, VIEWPORT_DP)
}

@Composable
private fun HeroBackdrop(page: TabPage, scroll: Animatable<Float, *>) {
    val url = if (page.tab == Tab.Apps) null else page.heroItem?.title?.heroUrl
    Backdrop(
        url = url,
        plain = page.tab == Tab.Apps,
        scroll = { if (page.tab == Tab.Apps) 0f else scroll.value },
        heroH = HomeDims.HeroH.value - HomeDims.RowTop.value,
    )
    // Warm only the next page (baked art + logo) once the current crossfade is done, so paging
    // never waits and the bake never competes with a running fade.
    val context = LocalContext.current
    LaunchedEffect(page, page.heroPage, page.hero) {
        val hero = page.hero
        if (hero.size < 2) return@LaunchedEffect
        val next = hero[(page.heroPage + 1) % hero.size]
        ShelfImages.prefetchLogo(context, next)
        delay(Motion.HERO_FADE_MS.toLong() + PREFETCH_SETTLE_MS)
        next.title.heroUrl?.let { HeroArt.get(context, it) }
    }
}

/** While the hero or bar has focus the carousel pages itself every ~8 s, for one loop at most. */
@Composable
private fun HeroAutoplay(vm: HomeViewModel, page: TabPage) {
    val running by remember(page) {
        derivedStateOf {
            vm.autoplay && vm.resumed && vm.sheet == null && vm.tab == page.tab && page.row < 0 &&
                page.hero.size > 1 && page.autoAdvances < page.hero.size
        }
    }
    LaunchedEffect(page, page.heroPage, running) {
        if (!running) return@LaunchedEffect
        delay(Motion.HERO_AUTOPLAY_MS)
        vm.autoAdvance()
    }
}

@Composable
private fun EditHint(vm: HomeViewModel) {
    val visible = vm.editing && vm.sheet == null
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(240), label = "hint")
    if (!visible && alpha == 0f) return
    Box(Modifier.fillMaxSize().padding(bottom = 22.dp), contentAlignment = Alignment.BottomCenter) {
        BasicText(
            "استخدم الأسهم للتحريك  ·  اضغط OK عند الانتهاء  ·  اضغط مطولاً على OK لمزيد من الخيارات",
            style = Type.Hint,
            modifier = Modifier.graphicsLayer { this.alpha = alpha },
        )
    }
}

/**
 * Remote input. Select is resolved on key-up so that holding it can mean something else (tvOS's
 * jiggle mode) without a stray launch; arrows act on every down, auto-repeat included.
 */
private class KeyRouter(private val vm: HomeViewModel, private val scope: CoroutineScope) {
    private var longJob: Job? = null
    private var longFired = false
    private var selectDown = false

    fun handle(ev: KeyEvent): Boolean {
        val down = ev.action == KeyEvent.ACTION_DOWN
        return when (ev.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> arrow(down, Dir.Up)
            KeyEvent.KEYCODE_DPAD_DOWN -> arrow(down, Dir.Down)
            KeyEvent.KEYCODE_DPAD_LEFT -> arrow(down, if (UiDirection.isRtl) Dir.Right else Dir.Left)
            KeyEvent.KEYCODE_DPAD_RIGHT -> arrow(down, if (UiDirection.isRtl) Dir.Left else Dir.Right)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> select(ev, down)
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_BUTTON_Y -> {
                if (down && ev.repeatCount == 0) vm.onMenu()
                true
            }
            else -> false
        }
    }

    private fun arrow(down: Boolean, dir: Dir): Boolean {
        if (down) vm.onDirection(dir)
        return true
    }

    private fun select(ev: KeyEvent, down: Boolean): Boolean {
        if (down) {
            if (ev.repeatCount == 0) {
                selectDown = true
                longFired = false
                longJob?.cancel()
                longJob = scope.launch {
                    delay(LONG_PRESS_MS)
                    longFired = true
                    vm.onLongSelect()
                }
            } else if (ev.isLongPress && !longFired) {
                longJob?.cancel()
                longFired = true
                vm.onLongSelect()
            }
        } else if (selectDown) {
            selectDown = false
            longJob?.cancel()
            if (!longFired) vm.onSelect()
        }
        return true
    }
}
