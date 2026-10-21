package com.moath.tvhome.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import com.moath.tvhome.AppEntry
import com.moath.tvhome.HomeViewModel
import com.moath.tvhome.design.TvDims
import com.moath.tvhome.design.TvMotion
import com.moath.tvhome.design.TvShapes
import com.moath.tvhome.home.CardStyle
import com.moath.tvhome.home.ContentRow
import com.moath.tvhome.home.RowScroll
import com.moath.tvhome.home.ShelfImages
import com.moath.tvhome.home.Tab
import com.moath.tvhome.home.TabPage
import com.moath.tvhome.home.Title
import kotlin.math.abs
import kotlinx.coroutines.flow.collectLatest

/** Rows within this distance of the focused one load their artwork; the rest stay flat glass. */
private const val IMAGE_ROWS_AHEAD = 2
private const val APP_FOCUS_SCALE = Motion.FOCUS_SCALE

/** One kind of row: its card size and how many cards fit the 880 dp safe width. */
private enum class RowKind(val cardW: Dp, val cardH: Dp, val columns: Int, val scale: Float) {
    Apps(TvDims.IconW, TvDims.IconH, 5, APP_FOCUS_SCALE),
    Landscape(HomeDims.LandscapeW, HomeDims.LandscapeH, 4, TvMotion.CARD_FOCUS_SCALE),
    Poster(HomeDims.PosterW, HomeDims.PosterH, 7, TvMotion.CARD_FOCUS_SCALE),
}

private val CardStyle.kind: RowKind
    get() = when (this) {
        CardStyle.Landscape -> RowKind.Landscape
        CardStyle.Poster -> RowKind.Poster
    }

/** Row pitches in dp, in page order — the same list the page scroll is computed from. */
fun pageRowHeights(vm: HomeViewModel, page: TabPage): List<Float> = buildList {
    if (vm.showsAppsRow(page)) add(HomeDims.rowHeight(TvDims.IconH).value)
    page.rows.forEach { add(HomeDims.rowHeight(it.style.kind.cardH).value) }
}

/**
 * A tab's scrolling page: hero details (or the apps-first inset), then the rows. The whole page
 * moves with one graphicsLayer translation, so vertical scrolling is draw-only.
 */
@Composable
fun PageContent(vm: HomeViewModel, page: TabPage, scroll: () -> Float, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .wrapContentHeight(Alignment.Top, unbounded = true)
            .graphicsLayer { translationY = -scroll().dp.toPx() },
    ) {
        if (page.hasHero) HeroDetails(vm, page) else Spacer(Modifier.height(HomeDims.AppsFirstTop))
        val appsRow = vm.showsAppsRow(page)
        if (appsRow) AppsRow(vm, page)
        val offset = if (appsRow) 1 else 0
        page.rows.forEachIndexed { i, row ->
            key(row.id) { TitleRow(vm, page, row, index = i + offset) }
        }
        if (page.rowsLoading && page.rows.isEmpty()) {
            PlaceholderRow(RowKind.Landscape)
            PlaceholderRow(RowKind.Poster)
        } else if (page.rows.isEmpty() && page.hero.isEmpty() && page.tab != Tab.Home) {
            Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
                BasicText("${page.tab.label} will appear here when you're connected to the internet.", style = Type.Quiet)
            }
        }
    }
}

@Composable
private fun rowActive(page: TabPage, index: Int) = remember(page, index) { derivedStateOf { page.row == index } }

@Composable
private fun rowNear(page: TabPage, index: Int) =
    remember(page, index) { derivedStateOf { abs(page.row.coerceAtLeast(0) - index) <= IMAGE_ROWS_AHEAD } }

@Composable
private fun RowFrame(heading: String, kind: RowKind, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().height(HomeDims.rowHeight(kind.cardH))) {
        BasicText(
            heading,
            style = Type.RowTitle,
            maxLines = 1,
            modifier = Modifier.padding(start = TvDims.SafeH).height(HomeDims.RowTitleH),
        )
        Spacer(Modifier.height(HomeDims.RowTitleGap))
        content()
    }
}

/** Scrolls a row as little as possible to keep its focused card on screen (draw + row layout only). */
@Composable
private fun FollowFocus(state: LazyListState, col: MutableIntState, count: Int, columns: Int) {
    LaunchedEffect(state, count) {
        var first = state.firstVisibleItemIndex
        snapshotFlow { col.intValue }.collectLatest { focus ->
            val next = RowScroll.firstVisible(first, focus, count, columns)
            if (next == first) return@collectLatest
            first = next
            state.animateScrollToItem(next)
        }
    }
}

@Composable
private fun AppsRow(vm: HomeViewModel, page: TabPage) {
    val active by rowActive(page, 0)
    val col = page.col(HomeViewModel.APPS_ROW)
    val state = rememberLazyListState()
    val apps = vm.apps
    FollowFocus(state, col, apps.size, RowKind.Apps.columns)
    RowFrame("تطبيقاتك", RowKind.Apps) {
        LazyRow(
            state = state,
            userScrollEnabled = false,
            contentPadding = PaddingValues(horizontal = TvDims.SafeH),
            horizontalArrangement = Arrangement.spacedBy(HomeDims.Gap),
        ) {
            itemsIndexed(apps, key = { _, a -> a.key }) { i, app ->
                val focused = active && col.intValue == i
                AppIcon(vm, app, focused, pressed = focused && vm.pressedKey == app.key)
            }
        }
    }
}

@Composable
private fun TitleRow(vm: HomeViewModel, page: TabPage, row: ContentRow, index: Int) {
    val active by rowActive(page, index)
    val near by rowNear(page, index)
    val col = page.col(row.id)
    val state = rememberLazyListState()
    val kind = row.style.kind
    FollowFocus(state, col, row.items.size, kind.columns)
    RowFrame(row.heading, kind) {
        LazyRow(
            state = state,
            userScrollEnabled = false,
            contentPadding = PaddingValues(horizontal = TvDims.SafeH),
            horizontalArrangement = Arrangement.spacedBy(HomeDims.Gap),
        ) {
            itemsIndexed(row.items, key = { _, t -> t.id }) { i, title ->
                val focused = active && col.intValue == i
                TitleCard(
                    vm,
                    title,
                    kind,
                    focused = focused,
                    pressed = focused && vm.pressedKey == HomeViewModel.cardKey(row.id, title.id),
                    loadImage = near,
                )
            }
        }
    }
}

@Composable
private fun TitleCard(vm: HomeViewModel, title: Title, kind: RowKind, focused: Boolean, pressed: Boolean, loadImage: Boolean) {
    val m = rememberFocusMotion(focused, pressed, { vm.lastDir }, if (focused) vm.nudge else null)
    val idle = LocalIdle.current
    val url = if (kind == RowKind.Poster) title.posterUrl else title.cardUrl
    val painter = if (loadImage && url != null) cardPainter(url, kind) else null
    val loaded = painter != null && painter.state.collectAsState().value is AsyncImagePainter.State.Success
    Column(Modifier.width(kind.cardW)) {
        Box(
            Modifier
                .size(kind.cardW, kind.cardH)
                .focusLayer(m, kind.scale, Shapes.Card, elevation = 18f, idle = idle)
                // Flat glass only until the art lands — never paint the card area twice.
                .then(if (loaded) Modifier else Modifier.background(Palette.Placeholder))
                .focusSurface(m),
        ) {
            val match = title.match
            if (match != null) {
                MatchTile(match)
            } else if (painter != null) {
                Image(painter, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        BasicText(
            title.name,
            style = Type.CardLabel,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(top = 10.dp)
                .graphicsLayer { alpha = m.lift.value.coerceIn(0f, 1f) },
        )
    }
}

/** Decoded at the focused size (HIG: supply art at the larger size so focus stays sharp). */
@Composable
private fun cardPainter(url: String, kind: RowKind): AsyncImagePainter {
    val context = LocalContext.current
    val density = LocalDensity.current
    val request = remember(url) {
        with(density) {
            ShelfImages.card(context, url, (kind.cardW * kind.scale).roundToPx(), (kind.cardH * kind.scale).roundToPx())
        }
    }
    return rememberAsyncImagePainter(request, contentScale = ContentScale.Crop)
}

@Composable
private fun AppIcon(vm: HomeViewModel, app: AppEntry, focused: Boolean, pressed: Boolean) {
    val m = rememberFocusMotion(focused, pressed, { vm.lastDir }, if (focused) vm.nudge else null)
    val idle = LocalIdle.current
    Box(Modifier.width(TvDims.IconW)) {
        Box(
            Modifier
                .size(TvDims.IconW, TvDims.IconH)
                .focusLayer(m, RowKind.Apps.scale, Shapes.Tile, elevation = 22f, idle = idle)
                .focusSurface(m),
        ) {
            Image(
                bitmap = app.tile,
                contentDescription = app.label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        BasicText(
            app.label,
            style = Type.TileLabel.copy(textAlign = TextAlign.Center),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(top = TvDims.IconH + Dims.LabelTop)
                .requiredWidth(TvDims.IconW + 36.dp)
                .align(Alignment.TopCenter)
                .graphicsLayer { alpha = m.lift.value.coerceIn(0f, 1f) },
        )
    }
}

@Composable
private fun PlaceholderRow(kind: RowKind) {
    Column(Modifier.fillMaxWidth().height(HomeDims.rowHeight(kind.cardH))) {
        Box(
            Modifier
                .padding(start = TvDims.SafeH, top = 6.dp)
                .size(140.dp, 10.dp)
                .background(Palette.Placeholder, TvShapes.Capsule),
        )
        Spacer(Modifier.height(HomeDims.RowTitleGap + 6.dp))
        Row(
            Modifier.padding(start = TvDims.SafeH).wrapContentWidth(Alignment.Start, unbounded = true),
            horizontalArrangement = Arrangement.spacedBy(HomeDims.Gap)) {
            repeat(kind.columns + 1) {
                Box(Modifier.size(kind.cardW, kind.cardH).background(Palette.Placeholder, Shapes.Card))
            }
        }
    }
}
