package com.moath.tvhome.system

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.moath.tvhome.R
import com.moath.tvhome.search.SearchActivity
import com.moath.tvhome.settings.SettingsActivity

/** One Control Center function. Only kinds whose action really works on this box are shown. */
enum class CcKind(@StringRes val label: Int, @DrawableRes val glyph: Int, val wide: Boolean = false) {
    Sleep(R.string.sys_cc_sleep, R.drawable.sys_power),
    ScreenSaver(R.string.sys_cc_screensaver, R.drawable.sys_screensaver),
    Network(R.string.sys_cc_wifi, R.drawable.sys_wifi),
    Bluetooth(R.string.sys_cc_bluetooth, R.drawable.sys_bluetooth),
    Search(R.string.sys_cc_search, R.drawable.sys_search),
    Settings(R.string.sys_cc_settings, R.drawable.sys_gear),
    Volume(R.string.sys_cc_volume, R.drawable.sys_speaker, wide = true),
}

sealed interface NetworkStatus {
    /** [ssid] is null when the platform redacts it (no location permission). */
    data class Wifi(val ssid: String?) : NetworkStatus
    data object Ethernet : NetworkStatus
    data object Disconnected : NetworkStatus
}

data class VolumeLevel(val index: Int, val max: Int) {
    val fraction: Float get() = if (max <= 0) 0f else index.toFloat() / max
}

/** The real system functions behind the Control Center tiles. */
class SystemControls(private val service: AccessibilityService) {
    private val pm: PackageManager = service.packageManager
    private val audio = service.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val connectivity = service.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifi = service.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val screenSaverIntent = Intent().setComponent(SOMNAMBULATOR).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    private val bluetoothIntent: Intent? = listOf(Settings.ACTION_BLUETOOTH_SETTINGS, ACTION_TV_CONNECT_INPUT)
        .map { Intent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        .firstOrNull(::resolves)
    private val wifiIntent = Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun availableKinds(): List<CcKind> = CcKind.entries.filter(::isAvailable)

    private fun isAvailable(kind: CcKind): Boolean = when (kind) {
        CcKind.Sleep -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.P || resolves(screenSaverIntent)
        CcKind.ScreenSaver -> resolves(screenSaverIntent)
        CcKind.Network -> resolves(wifiIntent)
        CcKind.Bluetooth -> bluetoothIntent != null
        CcKind.Search, CcKind.Settings -> true
        CcKind.Volume -> !audio.isVolumeFixed && audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) > 0
    }

    /** Runs the tile's action. Volume is adjusted with [stepVolume] instead. */
    fun perform(kind: CcKind) {
        when (kind) {
            CcKind.Sleep -> sleep()
            CcKind.ScreenSaver -> start(screenSaverIntent)
            CcKind.Network -> start(wifiIntent)
            CcKind.Bluetooth -> bluetoothIntent?.let(::start)
            CcKind.Search -> start(Intent(service, SearchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            CcKind.Settings -> start(Intent(service, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            CcKind.Volume -> Unit
        }
    }

    /** API 28+ GLOBAL_ACTION_LOCK_SCREEN puts the box to sleep; older builds start the dream. */
    private fun sleep() {
        val slept = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        if (!slept) start(screenSaverIntent)
    }

    fun networkStatus(): NetworkStatus {
        val caps = connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)
            ?: return NetworkStatus.Disconnected
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkStatus.Ethernet
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkStatus.Wifi(currentSsid())
            else -> NetworkStatus.Disconnected
        }
    }

    @Suppress("DEPRECATION")
    private fun currentSsid(): String? {
        val raw = runCatching { wifi?.connectionInfo?.ssid }.getOrNull() ?: return null
        val ssid = raw.removeSurrounding("\"")
        return ssid.takeUnless { it.isBlank() || it == UNKNOWN_SSID }
    }

    fun volume(): VolumeLevel = VolumeLevel(
        index = audio.getStreamVolume(AudioManager.STREAM_MUSIC),
        max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
    )

    fun stepVolume(direction: Int): VolumeLevel {
        val current = volume()
        val next = (current.index + direction).coerceIn(0, current.max)
        if (next != current.index) {
            runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0) }
                .onFailure { Log.w(TAG, "setStreamVolume failed", it) }
        }
        return volume()
    }

    private fun resolves(intent: Intent): Boolean = intent.resolveActivity(pm) != null

    private fun start(intent: Intent) {
        runCatching { service.startActivity(intent) }.onFailure { Log.w(TAG, "Cannot start $intent", it) }
    }

    private companion object {
        const val TAG = "SystemUi"
        const val UNKNOWN_SSID = "<unknown ssid>"
        /** Android TV Settings' "Remotes & Accessories" pairing screen. */
        const val ACTION_TV_CONNECT_INPUT = "com.google.android.intent.action.CONNECT_INPUT"
        val SOMNAMBULATOR = ComponentName("com.android.systemui", "com.android.systemui.Somnambulator")
    }
}
