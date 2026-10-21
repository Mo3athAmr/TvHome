package com.moath.tvhome.prefs

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Process
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User-facing launcher preferences, shared by Home (reads), Settings (writes), the screensaver
 * and the system overlays. One process-wide instance; observe [state].
 */
data class LauncherSettings(
    val hiddenApps: Set<String> = emptySet(),
    /** TMDB watch-provider region for Top Shelf content (ISO 3166-1). */
    val contentRegion: String = "SA",
    val topShelfAutoplay: Boolean = true,
    val aerialShowLocation: Boolean = true,
    val aerialShowClock: Boolean = true,
    val aerialShowPrayerTimes: Boolean = true,
    val aerialQuality4k: Boolean = false,
    val clickSounds: Boolean = true,
    /** What the Home hero shows: [HERO_YOUTUBE] (new videos from subscriptions) or [HERO_TRENDING]. */
    val heroSource: String = HERO_YOUTUBE,
    /** Bumped by Settings to ask Home to refresh the YouTube feed now. */
    val youtubeRefresh: Int = 0,
    val sportsRow: Boolean = true,
    val sportsWidget: Boolean = true,
    val sportsLeagues: Set<String> = com.moath.tvhome.sports.Leagues.defaultSlugs,
    /** Followed clubs, by ESPN team id (default: the big clubs). */
    val sportsClubs: Set<String> = com.moath.tvhome.sports.Clubs.big,
    val sportsRefresh: Int = 0,

    // ── Notifications ──
    /** Master switch for the pop-up notices at the bottom of the screen. */
    val notifications: Boolean = true,
    val notifyAdhan: Boolean = true,
    /** Goal / kick-off / full-time notices for followed matches (off unless the owner turns it on). */
    val notifyMatches: Boolean = false,

    // ── Prayer ──
    val adhanSound: Boolean = true,
    /** Lock the TV to the prayer screen from the adhan until the iqama (+ [lockAfterIqama] extra minutes, 0 by default). */
    val prayerLock: Boolean = true,
    /** Wake the screen from standby at the adhan. */
    val wakeForAdhan: Boolean = true,
    /** Minutes from adhan to iqama, "dhuhr=20,asr=20,maghrib=10,isha=20" (Fajr is never alerted). */
    val iqamaMinutes: String = DEFAULT_IQAMA,
    val lockAfterIqama: Int = 0,

    // ── Screen saver ──
    /** [SAVER_PRAYER] (prayer times over video/picture) or [SAVER_PHOTOS] (Google Photos ambient). */
    val screensaverMode: String = SAVER_PRAYER,
    /** Widgets drawn over the photos: "clock", "prayer", "weather", "matches". */
    val photoWidgets: Set<String> = setOf("clock", "prayer", "weather"),
    /** Prayer-mode background: "aerial" (Apple's footage) or "custom" (the owner's picture/video). */
    val prayerBackground: String = "aerial",
    /** The glass card that cycles weather / matches / YouTube every 30 seconds. */
    val rotatingWidget: Boolean = true,

    // ── Assistant ──
    /** First provider tried: "gemini", "xpl" (Experiential Labs) or "anthropic". */
    val aiPrimary: String = "anthropic",
    val geminiModel: String = "gemini-2.5-flash",
    val xplModel: String = "claude-haiku-5.5",
    /** Experiential model used to turn speech into text when Gemini fails. */
    val xplAudioModel: String = "whisper-1",
    val anthropicModel: String = "claude-haiku-5-5",
    /** The remote's assistant button opens our assistant instead of Google's. */
    val aiRemoteKey: Boolean = true,
    /** How the mic button listens: "google" (Google hears through the remote's mic, Rafiq answers) or "own" (Rafiq listens, TV mic). */
    val voiceMode: String = "google",
    /** Spoken replies: "always", "voice" (only when asked by voice) or "off". */
    val ttsMode: String = "always",
    /** "cloud" (OpenAI voices through Experiential, a fraction of a cent per reply) or "system" (free, on the TV). */
    val ttsEngine: String = "system",
    val ttsCloudVoice: String = "nova",
    /** Android voice name per language; empty = the engine's default. */
    val ttsSystemVoiceAr: String = "",
    val ttsSystemVoiceEn: String = "",
    // ── Friday ──
    /** Lock (and put to standby) the TV around Jumu'ah instead of the usual Dhuhr handling. */
    val jumuahLock: Boolean = true,
    /** Minutes before the Dhuhr adhan the Jumu'ah lock starts, and after it that it ends. */
    val jumuahBefore: Int = 30,
    val jumuahAfter: Int = 50,
    /** Put the TV to standby during the Jumu'ah lock (it may be woken; the lock screen stays). */
    val jumuahStandby: Boolean = true,
    /** Surah al-Kahf on the Home hero from Thursday's Maghrib to Friday's Maghrib. */
    val kahfHero: Boolean = true,
    val kahfReciter: String = "mishary-alafasy",
    // ── Matches ──
    /** A notice this many minutes before every match of a followed club (0 = off). */
    val matchReminderMin: Int = 15,
) {
    companion object {
        const val HERO_YOUTUBE = "youtube"
        const val HERO_TRENDING = "trending"
        const val SAVER_PRAYER = "prayer"
        const val SAVER_PHOTOS = "photos"
        const val DEFAULT_IQAMA = "dhuhr=20,asr=20,maghrib=10,isha=20"
    }

    /** Minutes from adhan to iqama for a prayer key ("dhuhr", …), falling back to the defaults. */
    fun iqamaFor(prayer: String): Int =
        (parse(iqamaMinutes)[prayer] ?: parse(DEFAULT_IQAMA)[prayer] ?: 15)

    private fun parse(s: String): Map<String, Int> = s.split(',').mapNotNull { part ->
        val (k, v) = part.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
        v.trim().toIntOrNull()?.let { k.trim() to it }
    }.toMap()
}

class LauncherPrefs private constructor(private val context: Context) {
    private val _state = MutableStateFlow(read())
    val state: StateFlow<LauncherSettings> = _state.asStateFlow()

    init {
        // Home, Settings and the always-on system process each hold an instance; a change in one
        // is broadcast so the others re-read the file instead of keeping a stale copy.
        ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    if (i.getIntExtra(EXTRA_PID, -1) != Process.myPid()) _state.value = read()
                }
            },
            IntentFilter(ACTION_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun prefs(): SharedPreferences =
        @Suppress("DEPRECATION") context.getSharedPreferences("launcher", Context.MODE_MULTI_PROCESS)

    fun update(transform: (LauncherSettings) -> LauncherSettings) {
        val next = transform(_state.value)
        prefs().edit()
            .putStringSet(K_HIDDEN, next.hiddenApps)
            .putString(K_REGION, next.contentRegion)
            .putBoolean(K_AUTOPLAY, next.topShelfAutoplay)
            .putBoolean(K_AERIAL_LOC, next.aerialShowLocation)
            .putBoolean(K_AERIAL_CLOCK, next.aerialShowClock)
            .putBoolean(K_AERIAL_PRAYER, next.aerialShowPrayerTimes)
            .putBoolean(K_AERIAL_4K, next.aerialQuality4k)
            .putBoolean(K_CLICKS, next.clickSounds)
            .putString(K_HERO, next.heroSource)
            .putInt(K_YT_REFRESH, next.youtubeRefresh)
            .putBoolean(K_SPORTS_ROW, next.sportsRow)
            .putBoolean(K_SPORTS_WIDGET, next.sportsWidget)
            .putStringSet(K_SPORTS_LEAGUES, next.sportsLeagues)
            .putStringSet(K_SPORTS_CLUBS, next.sportsClubs)
            .putInt(K_SPORTS_REFRESH, next.sportsRefresh)
            .putBoolean("notifications", next.notifications)
            .putBoolean("notify_adhan", next.notifyAdhan)
            .putBoolean("notify_matches", next.notifyMatches)
            .putBoolean("adhan_sound", next.adhanSound)
            .putBoolean("prayer_lock", next.prayerLock)
            .putBoolean("wake_adhan", next.wakeForAdhan)
            .putString("iqama", next.iqamaMinutes)
            .putInt("lock_after_iqama2", next.lockAfterIqama)
            .putString("saver_mode", next.screensaverMode)
            .putStringSet("photo_widgets", next.photoWidgets)
            .putString("prayer_bg", next.prayerBackground)
            .putBoolean("rotating_widget", next.rotatingWidget)
            .putString("ai_primary", next.aiPrimary)
            .putString("gemini_model2", next.geminiModel)
            .putString("xpl_model", next.xplModel)
            .putString("xpl_audio_model2", next.xplAudioModel)
            .putString("anthropic_model", next.anthropicModel)
            .putBoolean("ai_remote_key", next.aiRemoteKey)
            .putString("voice_mode", next.voiceMode)
            .putString("tts_mode", next.ttsMode)
            .putString("tts_engine2", next.ttsEngine)
            .putString("tts_cloud_voice", next.ttsCloudVoice)
            .putString("tts_sys_ar", next.ttsSystemVoiceAr)
            .putString("tts_sys_en", next.ttsSystemVoiceEn)
            .putBoolean("jumuah_lock", next.jumuahLock)
            .putInt("jumuah_before", next.jumuahBefore)
            .putInt("jumuah_after", next.jumuahAfter)
            .putBoolean("jumuah_standby", next.jumuahStandby)
            .putBoolean("kahf_hero", next.kahfHero)
            .putString("kahf_reciter", next.kahfReciter)
            .putInt("match_reminder", next.matchReminderMin)
            .commit()
        _state.value = next
        context.sendBroadcast(Intent(ACTION_CHANGED).setPackage(context.packageName).putExtra(EXTRA_PID, Process.myPid()))
    }

    private fun read(): LauncherSettings {
        val d = LauncherSettings()
        val prefs = prefs()
        return LauncherSettings(
            hiddenApps = prefs.getStringSet(K_HIDDEN, emptySet())?.toSet().orEmpty(),
            contentRegion = prefs.getString(K_REGION, null) ?: d.contentRegion,
            topShelfAutoplay = prefs.getBoolean(K_AUTOPLAY, d.topShelfAutoplay),
            aerialShowLocation = prefs.getBoolean(K_AERIAL_LOC, d.aerialShowLocation),
            aerialShowClock = prefs.getBoolean(K_AERIAL_CLOCK, d.aerialShowClock),
            aerialShowPrayerTimes = prefs.getBoolean(K_AERIAL_PRAYER, d.aerialShowPrayerTimes),
            aerialQuality4k = prefs.getBoolean(K_AERIAL_4K, d.aerialQuality4k),
            clickSounds = prefs.getBoolean(K_CLICKS, d.clickSounds),
            heroSource = prefs.getString(K_HERO, null) ?: d.heroSource,
            youtubeRefresh = prefs.getInt(K_YT_REFRESH, d.youtubeRefresh),
            sportsRow = prefs.getBoolean(K_SPORTS_ROW, d.sportsRow),
            sportsWidget = prefs.getBoolean(K_SPORTS_WIDGET, d.sportsWidget),
            sportsLeagues = prefs.getStringSet(K_SPORTS_LEAGUES, null)?.toSet() ?: d.sportsLeagues,
            sportsClubs = prefs.getStringSet(K_SPORTS_CLUBS, null)?.toSet() ?: d.sportsClubs,
            sportsRefresh = prefs.getInt(K_SPORTS_REFRESH, d.sportsRefresh),
            notifications = prefs.getBoolean("notifications", d.notifications),
            notifyAdhan = prefs.getBoolean("notify_adhan", d.notifyAdhan),
            notifyMatches = prefs.getBoolean("notify_matches", d.notifyMatches),
            adhanSound = prefs.getBoolean("adhan_sound", d.adhanSound),
            prayerLock = prefs.getBoolean("prayer_lock", d.prayerLock),
            wakeForAdhan = prefs.getBoolean("wake_adhan", d.wakeForAdhan),
            iqamaMinutes = prefs.getString("iqama", null) ?: d.iqamaMinutes,
            // New key: the old default (10 minutes after the iqama) was saved on the TV and must not stick.
            lockAfterIqama = prefs.getInt("lock_after_iqama2", d.lockAfterIqama),
            screensaverMode = prefs.getString("saver_mode", null) ?: d.screensaverMode,
            photoWidgets = prefs.getStringSet("photo_widgets", null)?.toSet() ?: d.photoWidgets,
            prayerBackground = prefs.getString("prayer_bg", null) ?: d.prayerBackground,
            rotatingWidget = prefs.getBoolean("rotating_widget", d.rotatingWidget),
            aiPrimary = prefs.getString("ai_primary", null) ?: d.aiPrimary,
            geminiModel = prefs.getString("gemini_model2", null)?.takeIf { it.isNotBlank() } ?: d.geminiModel,
            xplModel = prefs.getString("xpl_model", null)?.takeIf { it.isNotBlank() } ?: d.xplModel,
            xplAudioModel = prefs.getString("xpl_audio_model2", null)?.takeIf { it.isNotBlank() } ?: d.xplAudioModel,
            anthropicModel = prefs.getString("anthropic_model", null)?.takeIf { it.isNotBlank() } ?: d.anthropicModel,
            aiRemoteKey = prefs.getBoolean("ai_remote_key", d.aiRemoteKey),
            voiceMode = prefs.getString("voice_mode", null) ?: d.voiceMode,
            ttsMode = prefs.getString("tts_mode", null) ?: d.ttsMode,
            ttsEngine = prefs.getString("tts_engine2", null) ?: d.ttsEngine,
            ttsCloudVoice = prefs.getString("tts_cloud_voice", null) ?: d.ttsCloudVoice,
            ttsSystemVoiceAr = prefs.getString("tts_sys_ar", null) ?: d.ttsSystemVoiceAr,
            ttsSystemVoiceEn = prefs.getString("tts_sys_en", null) ?: d.ttsSystemVoiceEn,
            jumuahLock = prefs.getBoolean("jumuah_lock", d.jumuahLock),
            jumuahBefore = prefs.getInt("jumuah_before", d.jumuahBefore),
            jumuahAfter = prefs.getInt("jumuah_after", d.jumuahAfter),
            jumuahStandby = prefs.getBoolean("jumuah_standby", d.jumuahStandby),
            kahfHero = prefs.getBoolean("kahf_hero", d.kahfHero),
            kahfReciter = prefs.getString("kahf_reciter", null) ?: d.kahfReciter,
            matchReminderMin = prefs.getInt("match_reminder", d.matchReminderMin),
        )
    }

    companion object {
        const val ACTION_CHANGED = "com.moath.tvhome.PREFS_CHANGED"
        private const val EXTRA_PID = "pid"
        private const val K_HIDDEN = "hidden"
        private const val K_REGION = "region"
        private const val K_AUTOPLAY = "shelf_autoplay"
        private const val K_AERIAL_LOC = "aerial_location"
        private const val K_AERIAL_CLOCK = "aerial_clock"
        private const val K_AERIAL_PRAYER = "aerial_prayer"
        private const val K_AERIAL_4K = "aerial_4k"
        private const val K_CLICKS = "click_sounds"
        private const val K_HERO = "hero_source"
        private const val K_YT_REFRESH = "yt_refresh"
        private const val K_SPORTS_ROW = "sports_row"
        private const val K_SPORTS_WIDGET = "sports_widget"
        private const val K_SPORTS_LEAGUES = "sports_leagues"
        private const val K_SPORTS_CLUBS = "sports_clubs"
        private const val K_SPORTS_REFRESH = "sports_refresh"

        @Volatile private var instance: LauncherPrefs? = null

        fun get(context: Context): LauncherPrefs =
            instance ?: synchronized(this) {
                instance ?: LauncherPrefs(context.applicationContext).also { instance = it }
            }
    }
}
