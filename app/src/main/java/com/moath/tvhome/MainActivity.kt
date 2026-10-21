package com.moath.tvhome

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.moath.tvhome.home.ClickSound
import com.moath.tvhome.home.ShelfImages
import com.moath.tvhome.prefs.LauncherPrefs
import com.moath.tvhome.ui.HomeScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: HomeViewModel by viewModels()
    private var click: ClickSound? = null

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = vm.reload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShelfImages.install()
        click = ClickSound(this)
        registerReceiver(
            packageReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            },
        )
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = vm.onBack()
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.intents.collect(::start)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                val prefs = LauncherPrefs.get(this@MainActivity)
                vm.ticks.collect { if (prefs.state.value.clickSounds) click?.play() }
            }
        }
        // The UI is Arabic: right-to-left whatever the box's locale is.
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides com.moath.tvhome.design.UiDirection.layout) { HomeScreen(vm) }
        }
        // The hero backdrop paints every pixel itself; skipping the window's black fill saves a
        // full-screen layer per frame on low-end TV GPUs.
        window.setBackgroundDrawable(null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) vm.onHome()
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
        lifecycleScope.launch { com.moath.tvhome.health.HealthWatch.onHomeResumed(this@MainActivity) }
    }

    /**
     * This box has 1.8 GB and swaps hard (kswapd was eating a quarter of a core): when Home is out of
     * sight its decoded images go, so the app being watched keeps its memory. They reload from disk.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            coil3.SingletonImageLoader.get(this).memoryCache?.clear()
            com.moath.tvhome.ui.HeroArt.clear()
        }
    }

    override fun onPause() {
        vm.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        click?.release()
        click = null
        super.onDestroy()
    }

    private fun start(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w("TvHome", "Cannot start $intent", e)
            Toast.makeText(this, "تعذّر فتح هذا التطبيق.", Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Log.w("TvHome", "Not allowed to start $intent", e)
            Toast.makeText(this, "تعذّر فتح هذا التطبيق.", Toast.LENGTH_SHORT).show()
        }
    }
}
