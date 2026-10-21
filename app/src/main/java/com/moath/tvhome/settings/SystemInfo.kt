package com.moath.tvhome.settings

import android.Manifest
import android.app.ActivityManager
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.service.dreams.DreamService
import android.util.Log
import android.view.Display
import android.view.inputmethod.InputMethodManager
import com.moath.tvhome.aerial.AerialDreamService
import com.moath.tvhome.system.SystemUiService
import java.net.Inet4Address
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

internal data class StorageInfo(val totalBytes: Long, val freeBytes: Long) {
    val usedBytes: Long get() = totalBytes - freeBytes
}

internal enum class Link { WIFI, ETHERNET, OTHER, NONE }

internal data class NetworkInfo(
    val link: Link,
    val ssid: String?,
    val signalLevel: Int?,
    val ipv4: String?,
    val router: String?,
    val dns: List<String>,
)

internal data class Dream(val component: ComponentName, val label: String)

/**
 * Read-only (plus the two writable screen-saver keys) view of the device. Every value comes from
 * the platform; anything the app is not allowed to read returns null so the row is hidden.
 * All numbers are formatted with [Locale.US] — the box runs an Arabic locale but this UI is English.
 */
internal class SystemInfo(private val context: Context) {
    private val cr = context.contentResolver
    private val pm: PackageManager = context.packageManager

    val aerialComponent = ComponentName(context.packageName, AerialDreamService::class.java.name)
    private val controlsComponent = ComponentName(context.packageName, SystemUiService::class.java.name)

    // ---- About -------------------------------------------------------------------------------

    fun deviceName(): String =
        Settings.Global.getString(cr, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL

    fun model(): String {
        val maker = Build.MANUFACTURER.replaceFirstChar { it.titlecase(Locale.US) }
        return if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL else "$maker ${Build.MODEL}"
    }

    fun androidVersion(): String = Build.VERSION.RELEASE

    fun buildNumber(): String = Build.DISPLAY

    fun securityPatch(): String? {
        val raw = Build.VERSION.SECURITY_PATCH.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(raw) ?: return raw
            SimpleDateFormat("d MMMM yyyy", Locale.US).format(date)
        }.getOrDefault(raw)
    }

    fun storage(): StorageInfo? = runCatching {
        val fs = StatFs(Environment.getDataDirectory().path)
        StorageInfo(fs.totalBytes, fs.availableBytes)
    }.onFailure { Log.w(TAG, "StatFs failed", it) }.getOrNull()

    fun totalMemory(): Long? {
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        return ActivityManager.MemoryInfo().also(am::getMemoryInfo).totalMem.takeIf { it > 0 }
    }

    fun displayMode(): Display.Mode? =
        context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.mode

    // ---- General -----------------------------------------------------------------------------

    fun languageLabel(): String {
        val locale = context.resources.configuration.locales[0]
        return locale.getDisplayName(Locale.US).ifBlank { locale.toLanguageTag() }
    }

    fun timeZoneLabel(): String = TimeZone.getDefault().getDisplayName(false, TimeZone.LONG, Locale.US)

    fun keyboardLabel(): String? {
        val id = Settings.Secure.getString(cr, Settings.Secure.DEFAULT_INPUT_METHOD) ?: return null
        val imm = context.getSystemService(InputMethodManager::class.java) ?: return null
        return imm.inputMethodList.firstOrNull { it.id == id }?.loadLabel(pm)?.toString()
    }

    // ---- Remotes -----------------------------------------------------------------------------

    /** Null when the app may not read Bluetooth state (needs BLUETOOTH ≤ API 30, BLUETOOTH_CONNECT ≥ 31). */
    fun bluetoothEnabled(): Boolean? = runCatching {
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled
    }.getOrNull()

    fun pairedDevices(): List<String>? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !granted(Manifest.permission.BLUETOOTH_CONNECT)) return null
        return runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices
                ?.mapNotNull { it.name?.takeIf(String::isNotBlank) }
                ?.sorted()
        }.getOrNull()
    }

    // ---- Network -----------------------------------------------------------------------------

    @Suppress("DEPRECATION")
    fun network(): NetworkInfo {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val active = cm?.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        val link = when {
            caps == null -> Link.NONE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Link.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Link.WIFI
            else -> Link.OTHER
        }
        val props = active?.let { cm.getLinkProperties(it) }
        val ipv4 = props?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address }?.hostAddress
        val router = props?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
        val dns = props?.dnsServers?.filterIsInstance<Inet4Address>()?.mapNotNull { it.hostAddress }.orEmpty()

        var ssid: String? = null
        var level: Int? = null
        if (link == Link.WIFI) {
            val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            val conn = runCatching { wifi?.connectionInfo }.getOrNull()
            // Without location permission Android returns "<unknown ssid>"; hide it rather than show junk.
            ssid = conn?.ssid?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID.removeSurrounding("\"") && it != "<unknown ssid>" }
            level = conn?.rssi?.takeIf { it in -127..0 && it != -127 }?.let { WifiManager.calculateSignalLevel(it, 4) }
        }
        return NetworkInfo(link, ssid, level, ipv4, router, dns)
    }

    // ---- Screen saver ------------------------------------------------------------------------

    fun dreams(): List<Dream> =
        pm.queryIntentServices(Intent(DreamService.SERVICE_INTERFACE), PackageManager.GET_META_DATA)
            .map { Dream(ComponentName(it.serviceInfo.packageName, it.serviceInfo.name), it.loadLabel(pm).toString()) }
            .distinctBy { it.component }
            .sortedBy { it.label.lowercase(Locale.US) }

    fun screensaverEnabled(): Boolean = Settings.Secure.getInt(cr, KEY_SCREENSAVER_ENABLED, 1) != 0

    fun currentDream(): ComponentName? =
        Settings.Secure.getString(cr, KEY_SCREENSAVER_COMPONENTS)
            ?.split(',')
            ?.firstNotNullOfOrNull { ComponentName.unflattenFromString(it.trim()) }

    fun dreamLabel(component: ComponentName): String? = runCatching {
        pm.getServiceInfo(component, 0).loadLabel(pm).toString()
    }.getOrNull()

    fun canWriteSecure(): Boolean = granted(Manifest.permission.WRITE_SECURE_SETTINGS)

    fun canWriteSystem(): Boolean = Settings.System.canWrite(context)

    fun setDream(component: ComponentName): Boolean = runCatching {
        Settings.Secure.putString(cr, KEY_SCREENSAVER_COMPONENTS, component.flattenToString()) &&
            Settings.Secure.putInt(cr, KEY_SCREENSAVER_ENABLED, 1)
    }.onFailure { Log.w(TAG, "Cannot set screensaver", it) }.getOrDefault(false)

    fun screenOffTimeoutMs(): Int? = runCatching {
        Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT)
    }.getOrNull()

    fun setScreenOffTimeout(ms: Int): Boolean = runCatching {
        Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, ms)
    }.onFailure { Log.w(TAG, "Cannot set timeout", it) }.getOrDefault(false)

    // ---- System ------------------------------------------------------------------------------

    fun appleTvControlsEnabled(): Boolean =
        Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')
            ?.any { ComponentName.unflattenFromString(it) == controlsComponent } == true

    fun developerOptionsEnabled(): Boolean =
        Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    /** Label of the current default home app, or null if the user hasn't picked one. */
    fun defaultHomeLabel(): String? {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val info = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo ?: return null
        if (info.packageName == "android") return null // the chooser: no default set
        return if (info.packageName == context.packageName) "الرئيسية" else info.loadLabel(pm).toString()
    }

    fun versionName(pkg: String): String? = runCatching { pm.getPackageInfo(pkg, 0).versionName }.getOrNull()

    fun isInstalled(pkg: String): Boolean = runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess

    private fun granted(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "TvSettings"
        private const val KEY_SCREENSAVER_COMPONENTS = "screensaver_components"
        private const val KEY_SCREENSAVER_ENABLED = "screensaver_enabled"
        const val NEVER_TIMEOUT = Int.MAX_VALUE
    }
}

/** Number formatting that ignores the box's Arabic locale. */
internal object Fmt {
    fun bytes(bytes: Long): String {
        val gb = bytes / 1_000_000_000.0
        return when {
            gb >= 100 -> String.format(Locale.US, "%.0f GB", gb)
            gb >= 1 -> String.format(Locale.US, "%.1f GB", gb).replace(".0 GB", " GB")
            else -> String.format(Locale.US, "%.0f MB", bytes / 1_000_000.0)
        }
    }

    fun resolution(mode: Display.Mode): String =
        String.format(Locale.US, "%d × %d, %d Hz", mode.physicalWidth, mode.physicalHeight, mode.refreshRate.roundToInt())

    /** tvOS-style format name: "1080p 60Hz", "4K 60Hz". */
    fun format(mode: Display.Mode): String {
        val h = minOf(mode.physicalWidth, mode.physicalHeight)
        val name = when {
            h >= 2160 -> "4K"
            else -> "${h}p"
        }
        return String.format(Locale.US, "%s %dHz", name, mode.refreshRate.roundToInt())
    }
}
