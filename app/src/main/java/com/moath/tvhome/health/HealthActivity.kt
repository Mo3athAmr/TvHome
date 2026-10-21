package com.moath.tvhome.health

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.moath.tvhome.design.LiquidGlass.liquidGlass
import com.moath.tvhome.design.TvFonts
import com.moath.tvhome.design.UiDirection
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Something stopped working": the features that are down, each with a fix button, plus Fix all and
 * Later. Opened by Home when a check fails, and from Customize → Feature status.
 */
class HealthActivity : ComponentActivity() {
    private var items by mutableStateOf<List<HealthItem>>(emptyList())
    private var allGood by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refresh()
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides UiDirection.layout) {
                Dialog(items, allGood, onFix = ::fix, onFixAll = ::fixAll, onLater = {
                    HealthWatch.snooze(this, items)
                    finish()
                })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        items = Health.check(this).filter { it.level != Level.Ok }
        if (items.none { it.level == Level.Broken } && intent.getBooleanExtra(EXTRA_FROM_HOME, false)) {
            allGood = true
            lifecycleScope.launch { delay(1_500); finish() }
        }
    }

    private fun fix(item: HealthItem) {
        when (item.id) {
            "mic" -> requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1)
            "launcher" -> if (Build.VERSION.SDK_INT >= 29) {
                val rm = getSystemService(RoleManager::class.java)
                runCatching { startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), 2) }
                    .onFailure { Health.fix(this, "launcher")?.let { i -> runCatching { startActivity(i) } } }
            } else Health.fix(this, "launcher")?.let { runCatching { startActivity(it) } }
            else -> Health.fix(this, item.id)?.let { runCatching { startActivity(it) } }
        }
        lifecycleScope.launch { delay(1_200); refresh() }
    }

    private fun fixAll() {
        items.filter { it.fixable && it.id != "launcher" && it.id != "alarms" }.forEach(::fix)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    companion object {
        const val EXTRA_FROM_HOME = "from_home"
        fun intent(context: Context, fromHome: Boolean) =
            Intent(context, HealthActivity::class.java).putExtra(EXTRA_FROM_HOME, fromHome).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

private fun text(size: Int, color: Color = Color.White, weight: FontWeight = FontWeight.Medium) =
    TextStyle(fontFamily = TvFonts.Text, fontSize = size.sp, lineHeight = (size * 1.4f).sp, fontWeight = weight, color = color)

@Composable
private fun Dialog(items: List<HealthItem>, allGood: Boolean, onFix: (HealthItem) -> Unit, onFixAll: () -> Unit, onLater: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(items.size) { delay(60); runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color(0xB3000000)), contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(28.dp)
        Column(
            Modifier.width(760.dp).background(Color(0xF0141418), shape).liquidGlass(shape, intensity = 0.5f).padding(28.dp),
        ) {
            if (allGood || items.isEmpty()) {
                BasicText("✅ كل الميزات تعمل", style = text(26, weight = FontWeight.Bold))
                return@Column
            }
            BasicText("⚠️ في ميزات متوقفة", style = text(26, weight = FontWeight.Bold))
            Spacer(Modifier.height(4.dp))
            BasicText("اضغط «إصلاح» بجانب كل ميزة، أو «إصلاح الكل».", style = text(15, Color(0x99FFFFFF), FontWeight.Normal))
            Spacer(Modifier.height(18.dp))
            items.forEachIndexed { i, it ->
                Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.padding(top = 2.dp).width(14.dp).height(14.dp).background(if (it.level == Level.Broken) Color(0xFFFF453A) else Color(0xFFFF9F0A), RoundedCornerShape(7.dp)))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        BasicText(it.title, style = text(18, weight = FontWeight.SemiBold))
                        BasicText(it.detail, style = text(14, Color(0xB3FFFFFF), FontWeight.Normal))
                    }
                    if (it.fixable) Button("إصلاح", modifier = if (i == 0) Modifier.focusRequester(first) else Modifier) { onFix(it) }
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (items.any { it.fixable }) Button("إصلاح الكل", primary = true, modifier = if (items.none { it.fixable }) Modifier.focusRequester(first) else Modifier, onClick = onFixAll)
                Button("لاحقاً", modifier = if (items.none { it.fixable }) Modifier.focusRequester(first) else Modifier, onClick = onLater)
            }
        }
    }
}

@Composable
private fun Button(label: String, modifier: Modifier = Modifier, primary: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "btn")
    Box(
        modifier.scale(scale).onFocusChanged { focused = it.isFocused }
            .background(if (focused) Color.White else if (primary) Color(0x40FFFFFF) else Color(0x1FFFFFFF), RoundedCornerShape(22.dp))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 10.dp),
    ) { BasicText(label, style = text(16, if (focused) Color.Black else Color.White, FontWeight.SemiBold)) }
}
