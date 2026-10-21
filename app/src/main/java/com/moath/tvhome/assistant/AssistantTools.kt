package com.moath.tvhome.assistant

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import com.moath.tvhome.AppRepository
import com.moath.tvhome.dashboard.ArabicText
import com.moath.tvhome.dashboard.DashboardClock
import com.moath.tvhome.prefs.LauncherPrefs
import com.moath.tvhome.screensaver.Screensaver
import com.moath.tvhome.search.SearchActivity
import com.moath.tvhome.sports.MatchState
import com.moath.tvhome.sports.SportsConfig
import com.moath.tvhome.sports.SportsRepository
import com.moath.tvhome.tmdb.NsfwFilter
import com.moath.tvhome.tmdb.TmdbClient
import com.moath.tvhome.youtube.YoutubeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Something the panel shows under the answer; OK on it acts. */
sealed interface AssistantCard {
    data class Video(val id: String, val title: String, val channel: String, val live: Boolean) : AssistantCard {
        val thumb get() = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
    }
    data class Show(val tmdbId: Long, val type: String, val name: String, val poster: String?) : AssistantCard
}

/**
 * [say]: for an action that already happened, the short friendly confirmation to show and speak as is —
 * no second round trip to the model just to phrase "done".
 */
data class ToolResult(val text: String, val cards: List<AssistantCard> = emptyList(), val image: ByteArray? = null, val opened: Boolean = false, val say: String? = null)

/** What the TV is showing when the assistant was called up. */
data class ScreenInfo(val packageName: String?, val appLabel: String?, val text: String, val jpeg: ByteArray?)

/**
 * The assistant's hands: everything it can do on the TV. Each tool returns a short text the model
 * reads back (plus cards for the panel). Actions that start something set [ToolResult.opened].
 */
class AssistantTools(private val ctx: Context, private val screen: () -> ScreenInfo?) {
    private val zone = ZoneId.of("Asia/Riyadh")

    private fun schema(vararg props: Triple<String, String, String>, required: List<String> = props.map { it.first }): JSONObject {
        val p = JSONObject()
        props.forEach { (name, type, desc) -> p.put(name, JSONObject().put("type", type).put("description", desc)) }
        return JSONObject().put("type", "object").put("properties", p).put("required", JSONArray(required))
    }

    val specs: List<ToolSpec> = listOf(
        ToolSpec("open_app", "Open an installed TV app by its name (Arabic or English), e.g. YouTube, Stremio, Casper Flix, stc tv, Netflix.", schema(Triple("name", "string", "App name as the user said it"))),
        ToolSpec("youtube_search", "Search YouTube and show results as cards. Set play=true to start the best match right away.", schema(Triple("query", "string", "What to search"), Triple("play", "boolean", "Play the first result now"), required = listOf("query"))),
        ToolSpec(
            "youtube_channel_latest",
            "Play the newest video a YouTube channel uploaded, e.g. 'آخر مقطع لقناة فلان'. With show, the newest episode of that show/series " +
                "on the channel ('آخر حلقة من برنامج سوالف لقناة فلان' → channel=فلان, show=سوالف). Searches the user's subscriptions first.",
            schema(Triple("channel", "string", "Channel or creator name"), Triple("show", "string", "Show/series name on that channel, optional"), required = listOf("channel")),
        ),
        ToolSpec("youtube_channel_live", "Open the live stream of a YouTube channel right now, e.g. 'الجزيرة مباشر', 'العربية مباشر', 'قناة القرآن الكريم مباشر'.", schema(Triple("channel", "string", "Channel name"))),
        ToolSpec("play_youtube_video", "Play a YouTube video by its 11-character id.", schema(Triple("video_id", "string", "Video id"))),
        ToolSpec(
            "play_quran",
            "Play a surah of the Quran full screen (e.g. سورة الكهف on Friday). Reciters: مشاري العفاسي, ماهر المعيقلي, عبدالرحمن السديس.",
            schema(Triple("surah", "string", "Surah name in Arabic or its number"), Triple("reciter", "string", "Reciter name, optional"), required = listOf("surah")),
        ),
        ToolSpec(
            "stremio_play",
            "Open a series/anime episode or a movie in Stremio and start playing it. Pass the ORIGINAL title in English (ون بيس → One Piece). " +
                "season and episode: a number or \"last\" (nothing given = the newest aired episode). anime=true for Japanese animation. " +
                "For an episode number counted from the start (e.g. episode 1000) give only episode.",
            schema(
                Triple("title", "string", "Original title in English"),
                Triple("kind", "string", "series or movie"),
                Triple("anime", "boolean", "true for anime"),
                Triple("season", "string", "number or last, optional"),
                Triple("episode", "string", "number or last, optional"),
                required = listOf("title"),
            ),
        ),
        ToolSpec(
            "find_titles",
            "Search the whole film/series/anime library (TMDB) and show the picks as posters on the TV; returns the list with ids. " +
                "Use for every recommendation or 'what should I watch' request — never suggest titles from memory. Sexual content is always filtered out. " +
                "genres (comma separated): ${Library.genreNames.sorted().joinToString(", ")}. " +
                "origin: ${Library.originNames.sorted().joinToString(", ")}. era: new | recent | classic | 90s | 2010s | 2015 | 2000-2010. " +
                "family=true: safe for the whole family incl. children (PG). clean=true: no sexual scenes, violence OK (PG-13). " +
                "similar_to: a title the user liked ('شي مثل Breaking Bad').",
            schema(
                Triple("kind", "string", "movie | tv | any (series and anime are tv)"),
                Triple("genres", "string", "Comma-separated genre names from the list, optional"),
                Triple("origin", "string", "Country/style from the list, optional"),
                Triple("era", "string", "Time period, optional"),
                Triple("family", "boolean", "Family-safe, optional"),
                Triple("clean", "boolean", "No sexual scenes, optional"),
                Triple("sort", "string", "popular | top_rated | newest, optional"),
                Triple("similar_to", "string", "Title in English to find similar ones, optional"),
                Triple("heading", "string", "Short Arabic heading for the picks page, e.g. أفلام رعب كورية جديدة"),
                Triple("show", "boolean", "Show the picks on the TV (default true)"),
                required = listOf("kind"),
            ),
        ),
        ToolSpec(
            "open_title",
            "Open the page of one movie/series/anime on the TV (overview, trailer, Stremio button). Prefer tmdb_id + kind from find_titles; otherwise give title.",
            schema(Triple("title", "string", "Name, optional when tmdb_id is given"), Triple("tmdb_id", "integer", "Id from find_titles, optional"), Triple("kind", "string", "movie | tv, with tmdb_id"), required = emptyList()),
        ),
        ToolSpec("get_weather", "Current weather and 3-day forecast for a city (default: الخبر).", schema(Triple("city", "string", "City name, optional"), required = emptyList())),
        ToolSpec("get_prayer_times", "Today's prayer times in Al Khobar and the next prayer.", schema()),
        ToolSpec("get_matches", "Football fixtures and live scores of the followed competitions (today and the coming days), optionally for one team.", schema(Triple("team", "string", "Team name in English or Arabic, optional"), required = emptyList())),
        ToolSpec(
            "watch_match",
            "Open the live (or next) match of a team on Casper Flix, tuned to its channel (beIN etc.). Use for 'شغل مباراة برشلونة', 'افتح الكلاسيكو'.",
            schema(Triple("team", "string", "Team name in Arabic or English")),
        ),
        ToolSpec("media_control", "Control what is playing on the TV.", schema(Triple("action", "string", "play | pause | next | previous | stop"))),
        ToolSpec("set_volume", "Change the TV volume. level is 0-100, or use change = up/down/mute.", schema(Triple("level", "integer", "Absolute level 0-100, optional"), Triple("change", "string", "up | down | mute, optional"), required = emptyList())),
        ToolSpec("look_at_screen", "See what is on the TV screen right now (screenshot + visible text + current app). Use for 'وش هذا', 'وش يعرض', questions about what's playing.", schema()),
        ToolSpec("web_search", "Search the web for current facts (news, prices, results, who/what is...). Use for anything that may have changed after your training.", schema(Triple("query", "string", "The question"))),
        ToolSpec("tv_navigation", "Go to the home screen, go back, open settings or start the screen saver.", schema(Triple("action", "string", "home | back | settings | screensaver"))),
    )

    suspend fun run(call: ToolCall, web: suspend (String) -> String?): ToolResult = runCatching {
        val a = call.args
        when (call.name) {
            "open_app" -> openApp(a.optString("name"))
            "youtube_search" -> youtubeSearch(a.optString("query"), a.optBoolean("play"))
            "youtube_channel_latest" -> channelLatest(a.optString("channel"), a.optString("show"))
            "youtube_channel_live" -> channelLive(a.optString("channel"))
            "play_youtube_video" -> playVideo(a.optString("video_id"), "")
            "open_title" -> openTitle(a.optString("title"), a.optLong("tmdb_id"), a.optString("kind"))
            "find_titles" -> findTitles(a)
            "stremio_play" -> stremioPlay(a)
            "play_quran" -> playQuran(a.optString("surah"), a.optString("reciter"))
            "get_weather" -> weather(a.optString("city").ifBlank { "الخبر" })
            "get_prayer_times" -> prayerTimes()
            "get_matches" -> matches(a.optString("team"))
            "media_control" -> media(a.optString("action"))
            "watch_match" -> watchMatch(a.optString("team"))
            "set_volume" -> volume(if (a.has("level")) a.optInt("level") else null, a.optString("change"))
            "look_at_screen" -> lookAtScreen()
            "web_search" -> ToolResult(web(a.optString("query")) ?: "تعذّر البحث في الإنترنت الآن.")
            "tv_navigation" -> navigation(a.optString("action"))
            else -> ToolResult("أداة غير معروفة: ${call.name}")
        }
    }.getOrElse { ToolResult("فشلت الأداة ${call.name}: ${it.message}") }

    // ── Apps & navigation ─────────────────────────────────────────────────────────────────

    private fun norm(s: String) = normAr(s)

    private val aliases = mapOf(
        "كاسبر" to "casper", "كاسبرفليكس" to "casper", "كاسبرفلكس" to "casper", "يوتيوب" to "youtube", "ستريميو" to "stremio",
        "نتفليكس" to "netflix", "نتفلكس" to "netflix", "شاهد" to "shahid", "اس تي سي" to "stc", "جوجل بلاي" to "play", "المتجر" to "play",
        "الاعدادات" to "settings", "متصفح" to "browser", "في ال سي" to "vlc",
    ).mapKeys { norm(it.key) }

    private var appCache: Pair<Long, List<com.moath.tvhome.AppEntry>>? = null

    private suspend fun apps(): List<com.moath.tvhome.AppEntry> {
        // Kept until an app is installed or removed ([invalidateApps]): building it takes seconds on this box.
        appCache?.let { return it.second }
        return withContext(Dispatchers.Default) { AppRepository(ctx).load() }.filterNot { it.isPseudo }
            .also { appCache = SystemClock.uptimeMillis() to it }
    }

    /** Loads the installed-apps list ahead of time, so the first spoken "open …" is instant. */
    suspend fun warm() { apps() }

    fun invalidateApps() { appCache = null }

    /** [strict]: the name must be the app's name (or part of it), not a phrase that merely mentions it. */
    private suspend fun findApp(name: String, strict: Boolean = false): com.moath.tvhome.AppEntry? {
        val apps = apps()
        val q = norm(name).let { aliases[it] ?: it }
        if (q.length < 2) return null
        return apps.firstOrNull { norm(it.label) == q }
            ?: apps.firstOrNull { norm(it.label).contains(q) || (!strict && q.contains(norm(it.label))) }
            ?: apps.firstOrNull { norm(it.packageName).contains(q) && (!strict || q.length >= 4) }
    }

    /** Whether [name] is an installed app (the instant-command path only opens apps it is sure about). */
    suspend fun isApp(name: String): Boolean = findApp(name, strict = true) != null

    private suspend fun openApp(name: String): ToolResult {
        val hit = findApp(name) ?: return ToolResult("ما لقيت تطبيق باسم «$name». المثبتة: ${apps().joinToString("، ") { it.label }}")
        ctx.startActivity(Intent(hit.launchIntent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ToolResult("فُتح تطبيق ${hit.label}.", opened = true, say = "أبشر، فتحت ${hit.label} ✅")
    }

    private suspend fun stremioPlay(a: org.json.JSONObject): ToolResult {
        if (ctx.packageManager.getLaunchIntentForPackage(StremioFlow.PACKAGE) == null && ctx.packageManager.getLeanbackLaunchIntentForPackage(StremioFlow.PACKAGE) == null) {
            return ToolResult("ستريميو غير مثبّت على الشاشة.")
        }
        val title = a.optString("title")
        val t = StremioFlow.resolve(title, a.optString("kind").ifBlank { null }, a.optBoolean("anime"), a.optString("season").ifBlank { null }, a.optString("episode").ifBlank { null })
            ?: return ToolResult("ما لقيت «$title» في ستريميو.")
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(t.deepLink)).setPackage(StremioFlow.PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        if (a.optBoolean("autoplay", true)) ctx.sendBroadcast(Intent(StremioFlow.ACTION_PLAY).setPackage(ctx.packageName).putExtra(StremioFlow.EXTRA_LINK, t.deepLink))
        val where = if (t.season != null) "الموسم ${t.season} الحلقة ${t.episode}" else ""
        return ToolResult(
            "فتحت ${t.name} $where في ستريميو وأختار لك رابط مشاهدة.",
            opened = true,
            say = "فتحت ${t.name}${if (where.isNotEmpty()) " — $where" else ""} في ستريميو ▶️",
        )
    }

    private fun navigation(action: String): ToolResult {
        val i = when (action) {
            "home" -> Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            "settings" -> Intent(ctx, com.moath.tvhome.settings.SettingsActivity::class.java)
            "screensaver" -> { Screensaver.apply(ctx); Screensaver.startIntent() }
            "back" -> return ToolResult(if (com.moath.tvhome.system.SystemUiService.instance?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK) == true) "رجعت خطوة." else "تعذّر الرجوع.", opened = true)
            else -> return ToolResult("إجراء غير معروف.")
        }
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ToolResult("تم.", opened = true, say = "تم ✅")
    }

    // ── YouTube ─────────────────────────────────────────────────────────────────────────────

    private fun playVideo(id: String, title: String): ToolResult {
        if (!Regex("[0-9A-Za-z_-]{11}").matches(id)) return ToolResult("معرّف الفيديو غير صحيح.")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$id")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (ctx.packageManager.getLaunchIntentForPackage(YOUTUBE) != null || ctx.packageManager.getLeanbackLaunchIntentForPackage(YOUTUBE) != null) intent.setPackage(YOUTUBE)
        ctx.startActivity(intent)
        return ToolResult(if (title.isNotEmpty()) "يشتغل الآن: $title" else "يشتغل الفيديو الآن.", opened = true, say = "شغّلته لك ▶️")
    }

    private suspend fun youtubeSearch(query: String, play: Boolean): ToolResult {
        if (NsfwFilter.blockedByText(query, "") || Safety.blocked(query)) return ToolResult("هذا البحث غير مسموح.")
        val (videos, _) = YoutubeLookup.search(query)
        val clean = videos.filterNot { NsfwFilter.blockedByText(it.title, "") || Safety.blocked(it.title) }
        if (clean.isEmpty()) return ToolResult("ما طلعت نتائج لـ «$query».")
        val cards = clean.take(6).map { AssistantCard.Video(it.id, it.title, it.channel, it.live) }
        if (play) {
            val v = clean.first()
            return playVideo(v.id, v.title).copy(cards = cards)
        }
        return ToolResult("النتائج: " + clean.take(6).joinToString(" | ") { "${it.title} — ${it.channel} (${it.id})" }, cards)
    }

    private suspend fun resolveChannel(name: String): Pair<String, String>? {
        val q = norm(name)
        val subs = YoutubeRepository.get(ctx).channelNames()
        subs.entries.firstOrNull { norm(it.value) == q }?.let { return it.key to it.value }
        subs.entries.firstOrNull { norm(it.value).contains(q) || q.contains(norm(it.value).take(12)) }?.let { return it.key to it.value }
        val (_, channels) = YoutubeLookup.search(name)
        return channels.firstOrNull()?.let { it.id to it.name }
    }

    private suspend fun channelLatest(name: String, show: String): ToolResult {
        val (id, display) = resolveChannel(name) ?: return ToolResult("ما لقيت قناة باسم «$name».")
        val series = show.trim().takeIf { it.isNotEmpty() }
        val v = YoutubeLookup.latest(id, series)
            // Not among the channel's last 15 uploads: YouTube's newest-first search, kept to this channel and show.
            ?: series?.let { s ->
                YoutubeLookup.search("$display $s", newest = true).first.firstOrNull { v ->
                    YoutubeLookup.titleMatches(v.title, s) && (norm(v.channel).contains(norm(display)) || norm(display).contains(norm(v.channel)))
                }
            }
            ?: return ToolResult(if (series != null) "ما لقيت حلقات «$series» في قناة $display." else "ما قدرت أجيب آخر مقطع لقناة $display.")
        val what = if (series != null) "آخر حلقة من $series" else "آخر مقطع لـ$display"
        return playVideo(v.id, "${v.title} — $display").copy(cards = listOf(AssistantCard.Video(v.id, v.title, display, false)), say = "شغّلت $what ▶️")
    }

    /**
     * The official live channels people ask for by name. Several copycat channels use the same names
     * (there are many "قناة الجزيرة مباشر"), so these are pinned. Al Jazeera Mubasher is now "الجزيرة الآن".
     */
    private val liveChannels = listOf(
        listOf("الجزيرة مباشر", "الجزيرة الآن", "الجزيرة مباشر 24") to "channel/UCCv1Pd24oPErw5S7zJWltnQ",
        listOf("الجزيرة", "قناة الجزيرة", "al jazeera", "aljazeera") to "@aljazeera",
        listOf("العربية", "قناة العربية", "al arabiya", "alarabiya") to "@AlArabiya",
        listOf("الحدث", "قناة الحدث", "alhadath") to "@AlHadath",
        listOf("سكاي نيوز", "سكاي نيوز عربية", "sky news arabia") to "@SkyNewsArabia",
        listOf("الإخبارية", "الاخبارية", "قناة الإخبارية", "الإخبارية السعودية") to "channel/UCV01ajGl6nt09h40iDoHDNg",
        listOf("القرآن", "قناة القرآن", "قناة القرآن الكريم", "القرآن الكريم", "الحرم المكي", "مكة مباشر") to "channel/UCos52azQNBgW63_9uDJoPDA",
        listOf("السنة النبوية", "قناة السنة", "قناة السنة النبوية", "الحرم النبوي", "المدينة مباشر") to "channel/UCROKYPep-UuODNwyipe6JMw",
    ).flatMap { (names, path) -> names.map { norm(it) to path } }.toMap()

    private suspend fun channelLive(name: String): ToolResult {
        val q = norm(name).removePrefix("بث").removeSuffix("مباشر").let { if (it.length < 3) norm(name) else it }
        val pinned = liveChannels[norm(name)] ?: liveChannels[q]
        if (pinned != null) YoutubeLookup.livePath(pinned)?.let { return playVideo(it, "البث المباشر — $name").copy(say = "شغّلت $name مباشر 📺") }
        // Search: a live (or "مباشر"-titled) video whose channel or title carries the name.
        val (videos, _) = YoutubeLookup.search("$name بث مباشر")
        fun matches(v: YtVideo) = norm(v.channel).contains(q) || norm(v.title).contains(q)
        val hit = videos.firstOrNull { it.live && matches(it) }
            ?: videos.firstOrNull { matches(it) && (it.title.contains("مباشر") || it.title.contains("live", ignoreCase = true) || it.title.contains("البث")) }
        if (hit != null) return playVideo(hit.id, "${hit.title} — ${hit.channel}").copy(cards = listOf(AssistantCard.Video(hit.id, hit.title, hit.channel, hit.live)), say = "شغّلت $name مباشر 📺")
        val (id, display) = resolveChannel(name) ?: return ToolResult("ما لقيت قناة باسم «$name».")
        YoutubeLookup.live(id)?.let { return playVideo(it, "البث المباشر — $display").copy(say = "شغّلت $display مباشر 📺") }
        return ToolResult("قناة $display ما عندها بث مباشر الآن.")
    }

    private fun playQuran(surahName: String, reciterName: String): ToolResult {
        val surah = com.moath.tvhome.quran.QuranAudio.find(surahName) ?: return ToolResult("ما عرفت السورة «$surahName».")
        val q = norm(reciterName)
        val reciter = com.moath.tvhome.quran.QuranAudio.reciters.firstOrNull { q.isNotEmpty() && (norm(it.name).contains(q) || q.contains(norm(it.name.substringAfterLast(' ')))) }
        ctx.startActivity(com.moath.tvhome.quran.QuranPlayerActivity.intent(ctx, surah, reciter?.id))
        val said = "تشتغل الآن سورة ${com.moath.tvhome.quran.QuranAudio.name(surah)}" + (reciter?.let { " بصوت ${it.name}" } ?: "")
        return ToolResult(said, opened = true, say = "$said 🌿")
    }

    // ── Movies & series ──────────────────────────────────────────────────────────────────────

    private suspend fun openTitle(title: String, tmdbId: Long, kind: String): ToolResult {
        val tmdb = TmdbClient.get(ctx)
        val exact = if (tmdbId > 0) tmdb.details(tmdbId, if (kind == "tv") "tv" else "movie") else null
        val hits = if (exact != null) listOf(exact) else tmdb.search(title)
        val t = hits.firstOrNull() ?: return ToolResult("ما لقيت فيلم أو مسلسل باسم «$title».")
        ctx.startActivity(
            Intent(ctx, SearchActivity::class.java)
                .putExtra(SearchActivity.EXTRA_TMDB_ID, t.id).putExtra(SearchActivity.EXTRA_TMDB_TYPE, t.mediaType)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val cards = hits.take(5).map { AssistantCard.Show(it.id, it.mediaType, it.title, TmdbClient.image(it.posterPath, "w342")) }
        return ToolResult("فتحت صفحة ${t.title} (${if (t.mediaType == "tv") "مسلسل" else "فيلم"} ${t.year.orEmpty()}). ${t.overview.take(200)}", cards, opened = true, say = "فتحت صفحة ${t.title} 🎬")
    }

    private suspend fun findTitles(a: JSONObject): ToolResult {
        val tmdb = TmdbClient.get(ctx)
        val kind = a.optString("kind").lowercase().let { if (it == "series" || it == "anime") "tv" else it }
        val q = LibraryQuery(
            kind = kind,
            genres = a.optString("genres").split(',', '،', '|').map { it.trim() }.filter { it.isNotEmpty() },
            origin = a.optString("origin").trim().ifEmpty { null },
            era = a.optString("era").trim().ifEmpty { null },
            family = a.optBoolean("family"),
            clean = a.optBoolean("clean"),
            sort = a.optString("sort").ifBlank { "popular" },
        )
        val types = when (kind) { "movie" -> listOf("movie"); "tv" -> listOf("tv"); else -> listOf("movie", "tv") }
        val similar = a.optString("similar_to").trim()
        val found: List<com.moath.tvhome.tmdb.TmdbItem> = if (similar.isNotEmpty()) {
            val base = tmdb.search(similar).firstOrNull() ?: return ToolResult("ما لقيت «$similar» عشان أجيب شي يشبهه.")
            tmdb.recommendations(base.id, base.mediaType).filter { it.mediaType in types }
        } else {
            suspend fun run(query: LibraryQuery) = coroutineScope {
                val lists = types.map { t -> async { tmdb.discover(t, Library.params(query, t), pages = 2) } }.map { it.await() }
                // Films and series side by side, not one block after the other.
                (0 until (lists.maxOfOrNull { it.size } ?: 0)).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }
            }
            val rated = run(q)
            // Korean dramas and anime often carry no US rating, so the rating filter alone finds few:
            // top up from the same search without it (for family, kept to family/kids genres).
            if ((q.family || q.clean) && rated.size < 10) {
                rated + run(q.copy(family = false, clean = false, genres = if (q.family) q.genres + "family" else q.genres))
            } else rated
        }
        val items = found.filter { it.posterPath != null && !Safety.blocked(it.title) }.distinctBy { "${it.mediaType}/${it.id}" }.take(20)
        if (items.isEmpty()) return ToolResult("ما لقيت شي بهالمواصفات في المكتبة. جرّب تصنيف أو فترة ثانية.")
        val show = !a.has("show") || a.optBoolean("show", true)
        if (show) {
            ctx.startActivity(
                Intent(ctx, SearchActivity::class.java)
                    .putExtra(SearchActivity.EXTRA_PICKS, Library.toJson(items))
                    .putExtra(SearchActivity.EXTRA_PICKS_TITLE, a.optString("heading").ifBlank { "اقتراحات رفيق" })
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
        }
        val cards = items.take(8).map { AssistantCard.Show(it.id, it.mediaType, it.title, TmdbClient.image(it.posterPath, "w342")) }
        val lines = items.take(10).mapIndexed { i, t ->
            "${i + 1}) ${t.title} (${t.year ?: "؟"}) — ${if (t.mediaType == "tv") "مسلسل" else "فيلم"} — ⭐${"%.1f".format(Locale.US, t.rating)} — ${t.genres.take(3).joinToString("/")} — tmdb_id=${t.id} kind=${t.mediaType}"
        }
        return ToolResult(
            (if (show) "عُرضت ${items.size} نتيجة على الشاشة (يختار المستخدم منها بالريموت). " else "") + "أبرزها:\n" + lines.joinToString("\n"),
            cards,
            opened = show,
        )
    }

    // ── Weather, prayer, matches ─────────────────────────────────────────────────────────────

    private suspend fun json(url: String): JSONObject? = withContext(Dispatchers.IO) {
        runCatching { Http.client.newCall(Request.Builder().url(url).build()).execute().use { r -> r.body?.string()?.let(::JSONObject) } }.getOrNull()
    }

    private suspend fun weather(city: String): ToolResult {
        val geo = json("https://geocoding-api.open-meteo.com/v1/search?count=1&language=ar&name=" + Uri.encode(city))
            ?.optJSONArray("results")?.optJSONObject(0) ?: return ToolResult("ما لقيت مدينة باسم $city.")
        val lat = geo.getDouble("latitude")
        val lon = geo.getDouble("longitude")
        val w = json(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,weather_code" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&forecast_days=3&timezone=auto",
        ) ?: return ToolResult("خدمة الطقس لا ترد الآن.")
        // Phrased here, in one line: no second model call, and it reads well aloud.
        val cur = w.optJSONObject("current")
        val daily = w.optJSONObject("daily")
        val said = if (cur != null && daily != null) {
            fun d(key: String, i: Int) = daily.optJSONArray(key)?.optDouble(i)?.let { Math.round(it).toInt() }
            "الجو في ${geo.optString("name")} الحين: ${com.moath.tvhome.dashboard.condition(cur.optInt("weather_code"))}، " +
                "${Math.round(cur.optDouble("temperature_2m"))}° وتحس بـ${Math.round(cur.optDouble("apparent_temperature"))}°، " +
                "والرطوبة ${Math.round(cur.optDouble("relative_humidity_2m"))}٪. " +
                "بكرة: ${com.moath.tvhome.dashboard.condition(daily.optJSONArray("weather_code")?.optInt(1) ?: 0)}، " +
                "العظمى ${d("temperature_2m_max", 1)}° والصغرى ${d("temperature_2m_min", 1)}° ☀️"
        } else null
        return ToolResult(said ?: "المدينة: ${geo.optString("name")}. الحالي: $cur. الأيام: $daily", say = said)
    }

    private fun prayerTimes(): ToolResult {
        val m = DashboardClock.at(System.currentTimeMillis())
        return ToolResult(
            "مواقيت اليوم في الخبر (أم القرى): " + m.prayers.joinToString("، ") { (p, at) -> "${p.arabic} ${ArabicText.time(at)}" } +
                ". الصلاة القادمة: ${m.next.arabic} بعد ${ArabicText.duration(m.minutesToNext)}. التاريخ: ${m.gregorian} · ${m.hijri}",
        )
    }

    private suspend fun matches(team: String): ToolResult {
        val repo = SportsRepository.get(ctx)
        val p = LauncherPrefs.get(ctx).state.value
        if (repo.all().none { it.kickoffMs > System.currentTimeMillis() - 86_400_000 }) repo.refreshSchedule(p.sportsLeagues)
        val live = repo.all().filter { it.state == MatchState.Live }.map { it.league }.toSet()
        if (live.isNotEmpty()) repo.refreshLive(live)
        val q = norm(team)
        val fmt = DateTimeFormatter.ofPattern("EEEE d/M HH:mm", Locale("ar"))
        val list = repo.all()
            .filter { it.kickoffMs > System.currentTimeMillis() - 86_400_000 && it.kickoffMs < System.currentTimeMillis() + 8 * 86_400_000 }
            .filter { q.isEmpty() || norm(it.home.name).contains(q) || norm(it.away.name).contains(q) || listOfNotNull(com.moath.tvhome.sports.Clubs.nameAr(it.home.id), com.moath.tvhome.sports.Clubs.nameAr(it.away.id)).any { n -> norm(n).contains(q) } }
            .filter { q.isNotEmpty() || com.moath.tvhome.sports.SportsSelection.isFollowed(it, SportsConfig(p.sportsLeagues, p.sportsClubs)) }
            .sortedBy { it.kickoffMs }.take(15)
        if (list.isEmpty()) return ToolResult("ما فيه مباريات مطابقة في البطولات المتابعة خلال الأسبوع.")
        return ToolResult(list.joinToString("\n") { m ->
            val score = if (m.homeScore != null) " ${m.homeScore}-${m.awayScore}" else ""
            "${m.leagueName}: ${m.home.name} × ${m.away.name}$score — ${m.state} ${m.statusText} — ${fmt.format(Instant.ofEpochMilli(m.kickoffMs).atZone(zone))} (توقيت السعودية)" +
                (m.tv.takeIf { it.isNotEmpty() }?.let { " — القناة: ${it.joinToString("/")}" } ?: "")
        })
    }

    private fun watchMatch(team: String): ToolResult {
        val q = norm(team)
        val now = System.currentTimeMillis()
        val m = SportsRepository.get(ctx).all()
            .filter { norm(it.home.name).contains(q) || norm(it.away.name).contains(q) || listOfNotNull(com.moath.tvhome.sports.Clubs.nameAr(it.home.id), com.moath.tvhome.sports.Clubs.nameAr(it.away.id)).any { n -> norm(n).contains(q) } }
            .filter { it.state == MatchState.Live || (it.state == MatchState.Upcoming && it.kickoffMs - now < 12 * 3_600_000L) }
            .minByOrNull { if (it.state == MatchState.Live) 0L else it.kickoffMs }
            ?: return ToolResult("ما فيه مباراة لـ «$team» الآن أو خلال الساعات الجاية.")
        val channel = m.tv.firstOrNull()
        com.moath.tvhome.sports.CasperFlix.watch(ctx, channel)
        return ToolResult("فتحت كاسبر فليكس على ${channel ?: "القنوات"} لمباراة ${m.home.name} × ${m.away.name}.", opened = true, say = "فتحت كاسبر على ${channel ?: "القنوات"} ⚽")
    }

    // ── Media & volume ──────────────────────────────────────────────────────────────────────

    private fun media(action: String): ToolResult {
        val code = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> return ToolResult("إجراء غير معروف.")
        }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
        return ToolResult("تم.", opened = true, say = "تم ✅")
    }

    private fun volume(level: Int?, change: String): ToolResult {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        when {
            level != null -> am.setStreamVolume(AudioManager.STREAM_MUSIC, (level.coerceIn(0, 100) * max / 100f).toInt(), AudioManager.FLAG_SHOW_UI)
            change == "up" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            change == "down" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            change == "mute" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
        }
        val now = am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max.coerceAtLeast(1)
        return ToolResult("الصوت الآن $now٪.", say = "الصوت الآن $now٪ 🔊")
    }

    private suspend fun lookAtScreen(): ToolResult {
        val s = screen() ?: return ToolResult("ما قدرت أشوف الشاشة الآن.")
        // The texts are read now, through the on-demand reader (it is never left on: it slows every app).
        val text = com.moath.tvhome.system.ScreenAccess.use(ctx) { it?.foregroundText()?.second }.orEmpty()
        val jpeg = com.moath.tvhome.system.SystemUiService.instance?.screenshotJpeg() ?: s.jpeg
        return ToolResult(
            "التطبيق الظاهر: ${s.appLabel ?: s.packageName ?: "غير معروف"}. النصوص الظاهرة: ${text.ifBlank { "(لا نصوص مقروءة؛ اعتمد على الصورة)" }}",
            image = jpeg,
        )
    }

    private companion object {
        const val YOUTUBE = "com.google.android.youtube.tv"
    }
}

/** Requests the assistant turns down before asking any model: explicit sexual content and the like. */
object Safety {
    private val words = listOf("سكس", "اباحي", "إباحي", "اباحية", "إباحية", "هنتاي", "بورن", "عاري", "عارية", "نيك", "porn", "hentai", "xxx", "nude", "onlyfans")
    fun blocked(text: String): Boolean {
        val t = text.lowercase(Locale.ROOT)
        return words.any { t.contains(it) }
    }
}
