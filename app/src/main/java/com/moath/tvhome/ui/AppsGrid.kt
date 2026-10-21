package com.moath.tvhome.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.moath.tvhome.GridNav
import com.moath.tvhome.HomeViewModel
import com.moath.tvhome.home.TabPage

/** Grid scroll (dp) for the focused grid row: row 0 sits under the tab bar, deeper rows ride up. */
fun gridScroll(page: TabPage): Float {
    if (page.row < 0) return 0f
    val r = page.gridFocus / GridNav.COLUMNS
    if (r == 0) return 0f
    return (HomeDims.GridTop - HomeDims.RowTop - 10.dp + Dims.Pitch * (r - 1)).value
}

/**
 * The Apps tab: every visible app in the HIG five-column grid on a clean dark field. Jiggle-to-
 * move and the action sheet live here; the order is persisted through the view model.
 */
@Composable
fun AppsGrid(vm: HomeViewModel, page: TabPage, scroll: () -> Float, modifier: Modifier = Modifier) {
    val focus = if (page.row >= 0) page.gridFocus else -1
    val editing = vm.editing
    val nudge = vm.nudge
    val pressed = vm.pressedKey
    Box(
        modifier
            .fillMaxSize()
            .wrapContentHeight(Alignment.Top, unbounded = true)
            .graphicsLayer { translationY = -scroll().dp.toPx() },
    ) {
        vm.apps.forEachIndexed { i, app ->
            key(app.key) {
                val focused = i == focus
                AppTile(
                    app = app,
                    index = i,
                    x = Dims.tileX(i % GridNav.COLUMNS),
                    y = HomeDims.GridTop + Dims.Pitch * (i / GridNav.COLUMNS),
                    focused = focused,
                    hidden = false,
                    editing = editing,
                    pressed = focused && pressed == app.key,
                    nudge = if (focused) nudge else null,
                )
            }
        }
    }
}
