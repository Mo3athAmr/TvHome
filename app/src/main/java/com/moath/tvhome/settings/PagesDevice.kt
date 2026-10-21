package com.moath.tvhome.settings

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import com.moath.tvhome.R
import com.moath.tvhome.prefs.LauncherSettings

/** Builds the page for [dest]. Plain function: re-run whenever prefs, apps or [SettingsController.tick] change. */
internal fun buildPage(dest: Dest, c: SettingsController, p: LauncherSettings): PageSpec = when (dest) {
    Dest.Root -> rootPage(c)
    Dest.General -> generalPage(c)
    Dest.About -> aboutPage(c)
    Dest.Remotes -> remotesPage(c)
    Dest.Network -> networkPage(c)
    Dest.System -> systemPage(c)
    Dest.Apps -> appsPage(c, p)
    is Dest.App -> appPage(c, p, dest.pkg)
    Dest.VideoAudio -> videoAudioPage(c, p)
    Dest.ScreenSaver -> screenSaverPage(c, p)
    Dest.ScreenSaverType -> screenSaverTypePage(c)
    Dest.StartAfter -> startAfterPage(c)
    Dest.HomeScreen -> homeScreenPage(c, p)
    Dest.Region -> regionPage(c, p)
    Dest.Customize -> customizePage(c, p)
    Dest.HeroSource -> heroSourcePage(c, p)
    Dest.SportsLeagues -> sportsLeaguesPage(c, p)
    Dest.SportsClubs -> sportsClubsPage(c, p)
    Dest.Saver -> saverPage(c, p)
    Dest.SaverMode -> saverModePage(c, p)
    Dest.SaverBackground -> saverBackgroundPage(c, p)
    Dest.Prayer -> prayerPage(c, p)
    Dest.Iqama -> iqamaPage(c, p)
    Dest.Notices -> noticesPage(c, p)
    Dest.Assistant -> assistantPage(c, p)
    Dest.AiPrimary -> aiPrimaryPage(c, p)
    is Dest.AiModel -> aiModelPage(c, p, dest.provider)
    Dest.AiVoice -> aiVoicePage(c, p)
    Dest.Jumuah -> jumuahPage(c, p)
}

/** A navigation row: chevron, opens [intent] if it resolves, otherwise the row is left out. */
internal fun SettingsController.linkRow(
    id: String,
    title: String,
    intent: Intent?,
    value: String? = null,
    footer: String? = null,
): Item? {
    val target = intent?.takeIf(::resolves) ?: return null
    return Item(id, title, value = value, chevron = true, footer = footer, onClick = { open(target) })
}

private fun rootPage(c: SettingsController): PageSpec {
    fun nav(id: String, title: Int, dest: Dest, value: String? = null, before: () -> Unit = {}) =
        Item(id, c.s(title), value = value, chevron = true, onClick = { before(); c.push(dest) })
    val i = c.info
    val net = i.network()
    val netValue = when (net.link) {
        Link.WIFI -> net.ssid ?: "Wi-Fi"
        Link.ETHERNET -> "Ethernet"
        Link.NONE -> "غير متصل"
        Link.OTHER -> null
    }
    val dream = i.currentDream()?.let { i.dreamLabel(it) }
    return PageSpec(
        c.s(R.string.settings_title),
        Art.Gear,
        listOf(
            nav("general", R.string.settings_general, Dest.General),
            nav("video", R.string.settings_video_audio, Dest.VideoAudio, i.displayMode()?.let(Fmt::format)),
            nav("screensaver", R.string.settings_screen_saver, Dest.ScreenSaver, dream),
            nav("home", R.string.settings_home_screen, Dest.HomeScreen),
            nav("remotes", R.string.settings_remotes, Dest.Remotes),
            nav("apps", R.string.settings_apps, Dest.Apps) { c.loadApps() },
            nav("network", R.string.settings_network, Dest.Network, netValue),
            nav("system", R.string.settings_system, Dest.System),
        ),
        caption = "${i.deviceName()}\nAndroid ${i.androidVersion()}",
    )
}

private fun generalPage(c: SettingsController): PageSpec {
    val i = c.info
    return PageSpec(
        c.s(R.string.settings_general),
        Art.Gear,
        listOfNotNull(
            Item("about", c.s(R.string.settings_about), chevron = true, onClick = { c.push(Dest.About) }),
            Header("h.lang", c.s(R.string.settings_language_region)),
            c.linkRow("lang", c.s(R.string.settings_language), Intent(Settings.ACTION_LOCALE_SETTINGS), i.languageLabel()),
            c.linkRow("date", c.s(R.string.settings_date_time), Intent(Settings.ACTION_DATE_SETTINGS), i.timeZoneLabel()),
            c.linkRow("keyboard", c.s(R.string.settings_keyboard), Intent(Settings.ACTION_INPUT_METHOD_SETTINGS), i.keyboardLabel()),
        ).dropOrphanHeaders(),
    )
}

private fun aboutPage(c: SettingsController): PageSpec {
    val i = c.info
    val storage = i.storage()
    val mode = i.displayMode()
    val nameIntent = Intent(ACTION_DEVICE_NAME).takeIf(c::resolves)
    val storageIntent = Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS).takeIf(c::resolves)
    return PageSpec(
        c.s(R.string.settings_about),
        Art.Device,
        listOfNotNull(
            Item("name", c.s(R.string.settings_name), i.deviceName(), chevron = nameIntent != null, onClick = nameIntent?.let { { c.open(it) } }),
            Item("model", c.s(R.string.settings_model), i.model()),
            Header("h.sw", c.s(R.string.settings_header_software)),
            Item("android", c.s(R.string.settings_android_version), i.androidVersion()),
            i.securityPatch()?.let { Item("patch", c.s(R.string.settings_security_patch), it) },
            Item("build", c.s(R.string.settings_build), i.buildNumber()),
            Header("h.hw", c.s(R.string.settings_header_hardware)),
            storage?.let {
                Item(
                    "storage",
                    c.s(R.string.settings_storage),
                    c.s(R.string.settings_storage_value, Fmt.bytes(it.usedBytes), Fmt.bytes(it.totalBytes)),
                    chevron = storageIntent != null,
                    onClick = storageIntent?.let { intent -> { c.open(intent) } },
                )
            },
            storage?.let { Item("free", c.s(R.string.settings_available), Fmt.bytes(it.freeBytes)) },
            i.totalMemory()?.let { Item("memory", c.s(R.string.settings_memory), Fmt.bytes(it)) },
            mode?.let { Item("resolution", c.s(R.string.settings_resolution), Fmt.resolution(it)) },
        ),
    )
}

private fun remotesPage(c: SettingsController): PageSpec {
    val bt = c.info.bluetoothEnabled()
    val paired = c.info.pairedDevices().orEmpty()
    return PageSpec(
        c.s(R.string.settings_remotes),
        Art.Remote,
        listOfNotNull(
            c.linkRow("pair", c.s(R.string.settings_pair_accessory), Intent(ACTION_CONNECT_INPUT), footer = c.s(R.string.settings_pair_footer)),
            c.linkRow(
                "bluetooth",
                c.s(R.string.settings_bluetooth),
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
                value = bt?.let(c::onOff),
                footer = c.s(R.string.settings_bluetooth_footer),
            ),
            Header("h.paired", c.s(R.string.settings_header_paired)).takeIf { paired.isNotEmpty() },
        ) + paired.mapIndexed { n, name -> Item("paired.$n", name) },
    )
}

private fun networkPage(c: SettingsController): PageSpec {
    val n = c.info.network()
    val connection = when (n.link) {
        Link.WIFI -> c.s(R.string.settings_wifi)
        Link.ETHERNET -> c.s(R.string.settings_ethernet)
        Link.OTHER -> c.s(R.string.settings_connected)
        Link.NONE -> c.s(R.string.settings_not_connected)
    }
    val signal = n.signalLevel?.let {
        c.s(
            when (it) {
                3 -> R.string.settings_signal_excellent
                2 -> R.string.settings_signal_good
                1 -> R.string.settings_signal_fair
                else -> R.string.settings_signal_weak
            },
        )
    }
    return PageSpec(
        c.s(R.string.settings_network),
        Art.Wifi,
        listOfNotNull(
            Item("connection", c.s(R.string.settings_connection), connection),
            n.ssid?.let { Item("ssid", c.s(R.string.settings_network_name), it) },
            signal?.let { Item("signal", c.s(R.string.settings_signal), it) },
            n.ipv4?.let { Item("ip", c.s(R.string.settings_ip), it) },
            n.router?.let { Item("router", c.s(R.string.settings_router), it) },
            n.dns.takeIf { it.isNotEmpty() }?.let { Item("dns", c.s(R.string.settings_dns), it.joinToString(", ")) },
            c.linkRow("wifi", c.s(R.string.settings_configure_wifi), Intent(Settings.ACTION_WIFI_SETTINGS)),
        ),
    )
}

private fun systemPage(c: SettingsController): PageSpec {
    val controlsOn = c.info.appleTvControlsEnabled()
    return PageSpec(
        c.s(R.string.settings_system),
        Art.Gear,
        listOfNotNull(
            c.linkRow("update", c.s(R.string.settings_software_update), Intent(ACTION_SYSTEM_UPDATE)),
            c.linkRow(
                "a11y",
                c.s(R.string.settings_accessibility),
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                footer = c.s(if (controlsOn) R.string.settings_accessibility_on_footer else R.string.settings_accessibility_footer),
            ),
            c.linkRow("dev", c.s(R.string.settings_developer), Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                ?.takeIf { c.info.developerOptionsEnabled() },
            Header("h.maint", c.s(R.string.settings_header_maintenance)),
            c.linkRow("restart", c.s(R.string.settings_restart), Intent(ACTION_REBOOT_DEVICE)),
            c.linkRow(
                "android",
                c.s(R.string.settings_android_settings),
                Intent(Settings.ACTION_SETTINGS),
                footer = c.s(R.string.settings_android_settings_footer),
            ),
        ).dropOrphanHeaders(),
    )
}

/** Removes headers that ended up with no rows below them (their rows didn't resolve). */
internal fun List<Entry>.dropOrphanHeaders(): List<Entry> =
    filterIndexed { i, e -> e !is Header || getOrNull(i + 1).let { it != null && it !is Header } }

internal val SOMNAMBULATOR = ComponentName("com.android.systemui", "com.android.systemui.Somnambulator")
private const val ACTION_DEVICE_NAME = "android.settings.DEVICE_NAME"
private const val ACTION_CONNECT_INPUT = "com.google.android.intent.action.CONNECT_INPUT"
private const val ACTION_SYSTEM_UPDATE = "android.settings.SYSTEM_UPDATE_SETTINGS"
private const val ACTION_REBOOT_DEVICE = "com.android.tv.settings.REBOOT_DEVICE"
