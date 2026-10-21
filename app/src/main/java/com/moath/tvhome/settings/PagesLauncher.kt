package com.moath.tvhome.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.moath.tvhome.R
import com.moath.tvhome.prefs.LauncherSettings
import java.util.Locale

private val TIMEOUT_MINUTES = listOf(2, 5, 10, 15, 30)
private const val MINUTE_MS = 60_000

/** Common Top Shelf regions; the current one is added if it isn't listed. */
private val REGIONS = listOf(
    "IQ", "US", "GB", "AE", "SA", "KW", "QA", "JO", "EG", "TR",
    "DE", "FR", "ES", "IT", "NL", "SE", "IN", "CA", "AU", "JP", "KR", "BR", "MX",
)

internal fun appsPage(c: SettingsController, p: LauncherSettings): PageSpec {
    val apps = c.apps
    val entries: List<Entry> = when {
        apps == null -> listOf(Note("loading", c.s(R.string.settings_apps_loading)))
        else -> apps.map { app ->
            Item(
                "app:${app.packageName}",
                app.label,
                value = c.s(R.string.settings_hidden).takeIf { app.packageName in p.hiddenApps },
                chevron = true,
                icon = app.tile,
                onClick = { c.push(Dest.App(app.packageName)) },
            )
        }
    }
    return PageSpec(c.s(R.string.settings_apps), Art.AppGrid, entries)
}

internal fun appPage(c: SettingsController, p: LauncherSettings, pkg: String): PageSpec {
    val app = c.apps?.firstOrNull { it.packageName == pkg }
    val hidden = pkg in p.hiddenApps
    val pkgUri = Uri.fromParts("package", pkg, null)
    return PageSpec(
        app?.label ?: pkg,
        app?.let { Art.Tile(it.tile) } ?: Art.AppGrid,
        listOfNotNull(
            app?.let { Item("open", c.s(R.string.settings_app_open), onClick = { c.open(Intent(it.launchIntent)) }) },
            Item(
                "hide",
                c.s(R.string.settings_app_hide),
                c.onOff(hidden),
                footer = c.s(R.string.settings_app_hide_footer),
                onClick = {
                    c.prefs.update { s -> s.copy(hiddenApps = if (hidden) s.hiddenApps - pkg else s.hiddenApps + pkg) }
                },
            ),
            c.linkRow(
                "info",
                c.s(R.string.settings_app_info),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri),
                footer = c.s(R.string.settings_app_info_footer),
            ),
            app?.takeIf { it.isRemovable }?.let {
                Item(
                    "uninstall",
                    c.s(R.string.settings_app_uninstall),
                    onClick = { c.open(Intent(Intent.ACTION_DELETE, pkgUri)) },
                )
            },
            Header("h.ver", c.s(R.string.settings_about)),
            c.info.versionName(pkg)?.let { Item("version", c.s(R.string.settings_app_version), it) },
            Item("pkg", c.s(R.string.settings_app_package), pkg),
        ),
    )
}

internal fun videoAudioPage(c: SettingsController, p: LauncherSettings): PageSpec {
    val mode = c.info.displayMode()
    val sound = Intent(Settings.ACTION_SOUND_SETTINGS)
    // Android TV 12+ merges display into "Display & Sound"; use it when there is no display screen.
    val display = c.firstResolvable(Intent(Settings.ACTION_DISPLAY_SETTINGS))
        ?: sound.takeIf { c.resolverClass(it)?.contains("displaysound", ignoreCase = true) == true }
    return PageSpec(
        c.s(R.string.settings_video_audio),
        Art.Display,
        listOfNotNull(
            Header("h.video", c.s(R.string.settings_header_video)),
            mode?.let {
                Item(
                    "format",
                    c.s(R.string.settings_format),
                    Fmt.format(it),
                    chevron = display != null,
                    onClick = display?.let { intent -> { c.open(intent) } },
                )
            },
            Header("h.audio", c.s(R.string.settings_header_audio)),
            c.linkRow("audio", c.s(R.string.settings_audio_output), sound),
            Item(
                "clicks",
                c.s(R.string.settings_nav_clicks),
                c.onOff(p.clickSounds),
                footer = c.s(R.string.settings_nav_clicks_footer),
                onClick = { c.prefs.update { it.copy(clickSounds = !it.clickSounds) } },
            ),
        ).dropOrphanHeaders(),
    )
}

/** System screen-saver settings: Android's own screen, or Google TV's "ambient" one. */
private fun SettingsController.dreamSettings(): Intent? =
    firstResolvable(Intent(Settings.ACTION_DREAM_SETTINGS), Intent("com.google.android.tv.settings.ambient"))

internal fun screenSaverPage(c: SettingsController, p: LauncherSettings): PageSpec {
    val i = c.info
    val current = i.currentDream()?.takeIf { i.screensaverEnabled() }
    val typeLabel = when {
        current == null -> c.s(R.string.settings_none)
        current == i.aerialComponent -> c.s(R.string.settings_aerial)
        // A configured dream that is no longer installed never starts: report it as none.
        else -> i.dreamLabel(current) ?: c.s(R.string.settings_none)
    }
    val typeAction: (() -> Unit)? = when {
        i.canWriteSecure() -> ({ c.push(Dest.ScreenSaverType) })
        else -> c.dreamSettings()?.let { { c.open(it) } }
    }
    val startAction: (() -> Unit)? = when {
        i.canWriteSystem() -> ({ c.push(Dest.StartAfter) })
        else -> (c.dreamSettings() ?: manageWriteSettings(c.context).takeIf(c::resolves))?.let { { c.open(it) } }
    }
    val preview = Intent(Intent.ACTION_MAIN).setComponent(SOMNAMBULATOR).takeIf { current != null && c.resolves(it) }
    return PageSpec(
        c.s(R.string.settings_screen_saver),
        Art.Landscape,
        listOfNotNull(
            Item("type", c.s(R.string.settings_type), typeLabel, chevron = typeAction != null, footer = c.s(R.string.settings_type_footer), onClick = typeAction),
            i.screenOffTimeoutMs()?.let {
                Item(
                    "start",
                    c.s(R.string.settings_start_after),
                    timeoutLabel(c, it),
                    chevron = startAction != null,
                    footer = c.s(R.string.settings_start_after_footer),
                    onClick = startAction,
                )
            },
            preview?.let { Item("preview", c.s(R.string.settings_preview), onClick = { c.open(Intent(it)) }) },
            Header("h.aerial", c.s(R.string.settings_header_aerial)),
            Item(
                "location",
                c.s(R.string.settings_show_location),
                c.onOff(p.aerialShowLocation),
                onClick = { c.prefs.update { it.copy(aerialShowLocation = !it.aerialShowLocation) } },
            ),
            Item(
                "clock",
                c.s(R.string.settings_show_clock),
                c.onOff(p.aerialShowClock),
                onClick = { c.prefs.update { it.copy(aerialShowClock = !it.aerialShowClock) } },
            ),
            Item(
                "prayer",
                "مواقيت الصلاة",
                c.onOff(p.aerialShowPrayerTimes),
                footer = "الخبر · طريقة أم القرى — تظهر في شاشة التوقف.",
                onClick = { c.prefs.update { it.copy(aerialShowPrayerTimes = !it.aerialShowPrayerTimes) } },
            ),
            Item(
                "4k",
                c.s(R.string.settings_aerial_4k),
                c.onOff(p.aerialQuality4k),
                footer = c.s(R.string.settings_4k_footer),
                onClick = { c.prefs.update { it.copy(aerialQuality4k = !it.aerialQuality4k) } },
            ),
        ),
    )
}

internal fun screenSaverTypePage(c: SettingsController): PageSpec {
    val i = c.info
    val current = i.currentDream()?.takeIf { i.screensaverEnabled() }
    val dreams = i.dreams()
    return PageSpec(
        c.s(R.string.settings_type),
        Art.Landscape,
        dreams.map { d ->
            val label = if (d.component == i.aerialComponent) c.s(R.string.settings_aerial) else d.label
            Item(
                "dream:${d.component.flattenToShortString()}",
                label,
                checked = d.component == current,
                onClick = {
                    i.setDream(d.component)
                    c.refresh()
                    c.pop()
                },
            )
        },
    )
}

internal fun startAfterPage(c: SettingsController): PageSpec {
    val current = c.info.screenOffTimeoutMs()
    val options = listOf(SystemInfo.NEVER_TIMEOUT) + TIMEOUT_MINUTES.map { it * MINUTE_MS }
    return PageSpec(
        c.s(R.string.settings_start_after),
        Art.Landscape,
        options.map { ms ->
            Item(
                "t:$ms",
                timeoutLabel(c, ms),
                checked = ms == current,
                onClick = {
                    c.info.setScreenOffTimeout(ms)
                    c.refresh()
                    c.pop()
                },
            )
        },
    )
}

internal fun homeScreenPage(c: SettingsController, p: LauncherSettings): PageSpec = PageSpec(
    c.s(R.string.settings_home_screen),
    Art.HomeGrid,
    listOfNotNull(
        Header("h.shelf", c.s(R.string.settings_header_top_shelf)),
        Item(
            "autoplay",
            c.s(R.string.settings_top_shelf_autoplay),
            c.onOff(p.topShelfAutoplay),
            footer = c.s(R.string.settings_autoplay_footer),
            onClick = { c.prefs.update { it.copy(topShelfAutoplay = !it.topShelfAutoplay) } },
        ),
        Item(
            "region",
            c.s(R.string.settings_top_shelf_region),
            countryName(p.contentRegion),
            chevron = true,
            footer = c.s(R.string.settings_region_footer),
            onClick = { c.push(Dest.Region) },
        ),
        Header("h.layout", c.s(R.string.settings_header_layout)),
        Item(
            "reset",
            c.s(R.string.settings_reset_layout),
            footer = c.s(R.string.settings_reset_footer),
            onClick = { c.alert = resetAlert(c) },
        ),
        homeAppRow(c),
    ),
)

private fun homeAppRow(c: SettingsController): Item {
    val chooser = Intent(Settings.ACTION_HOME_SETTINGS).takeIf(c::resolves)
    return Item(
        "defaulthome",
        c.s(R.string.settings_default_home),
        c.info.defaultHomeLabel() ?: c.s(R.string.settings_none),
        chevron = chooser != null,
        footer = c.s(R.string.settings_default_home_footer),
        onClick = chooser?.let { { c.open(it) } },
    )
}

private fun resetAlert(c: SettingsController) = AlertSpec(
    title = c.s(R.string.settings_reset_title),
    message = c.s(R.string.settings_reset_message),
    buttons = listOf(
        AlertButton(c.s(R.string.settings_reset_confirm), destructive = true) {
            // OrderStore's file: dropping it makes Home fall back to its default arrangement.
            c.context.getSharedPreferences(HOME_ORDER_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        },
        AlertButton(c.s(R.string.settings_cancel)),
    ),
    focusIndex = 1,
)

internal fun regionPage(c: SettingsController, p: LauncherSettings): PageSpec {
    val codes = (REGIONS + p.contentRegion).distinct().sortedBy { countryName(it) }
    return PageSpec(
        c.s(R.string.settings_top_shelf_region),
        Art.HomeGrid,
        codes.map { code ->
            Item(
                "r:$code",
                countryName(code),
                checked = code == p.contentRegion,
                onClick = {
                    c.prefs.update { it.copy(contentRegion = code) }
                    c.pop()
                },
            )
        },
    )
}

private fun countryName(code: String): String =
    Locale("", code).getDisplayCountry(Locale.US).ifBlank { code }

private fun timeoutLabel(c: SettingsController, ms: Int): String = when {
    ms >= SystemInfo.NEVER_TIMEOUT || ms < 0 -> c.s(R.string.settings_never)
    ms == MINUTE_MS -> c.s(R.string.settings_minute)
    ms % MINUTE_MS == 0 -> c.s(R.string.settings_minutes, ms / MINUTE_MS)
    else -> c.s(R.string.settings_seconds, ms / 1000)
}

private fun manageWriteSettings(context: Context) =
    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))

private const val HOME_ORDER_PREFS = "home"
