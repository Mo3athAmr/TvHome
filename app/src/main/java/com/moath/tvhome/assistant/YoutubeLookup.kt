package com.moath.tvhome.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class YtVideo(val id: String, val title: String, val channel: String, val length: String, val live: Boolean)
data class YtChannel(val id: String, val name: String)

/**
 * YouTube without an API key: the public search page (its `ytInitialData` JSON), the channel
 * feed for the latest upload, and the channel's `/live` page for a live stream. Pure parsing is
 * split out so it unit-tests.
 */
object YoutubeLookup {
    private const val UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15"

    private suspend fun page(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            Http.client.newCall(Request.Builder().url(url).header("User-Agent", UA).header("Accept-Language", "ar,en;q=0.8").build()).execute().use { r ->
                if (r.isSuccessful) r.body?.string() else null
            }
        }.getOrNull()
    }

    /** [newest]: YouTube's "upload date" order instead of relevance. */
    suspend fun search(query: String, newest: Boolean = false): Pair<List<YtVideo>, List<YtChannel>> {
        val url = "https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode(query, "UTF-8") + if (newest) "&sp=CAI%253D" else ""
        val html = page(url) ?: return emptyList<YtVideo>() to emptyList()
        return parseSearch(html)
    }

    fun parseSearch(html: String): Pair<List<YtVideo>, List<YtChannel>> {
        val json = initialData(html) ?: return emptyList<YtVideo>() to emptyList()
        val videos = ArrayList<YtVideo>()
        val channels = ArrayList<YtChannel>()
        walk(json) { key, obj ->
            when (key) {
                "videoRenderer" -> {
                    val id = obj.optString("videoId")
                    if (id.length == 11 && videos.none { it.id == id }) {
                        val badges = obj.optJSONArray("badges")?.toString().orEmpty() + obj.optJSONArray("thumbnailOverlays")?.toString().orEmpty()
                        videos += YtVideo(
                            id, runs(obj.optJSONObject("title")), runs(obj.optJSONObject("ownerText")),
                            obj.optJSONObject("lengthText")?.optString("simpleText").orEmpty(),
                            live = badges.contains("LIVE_NOW") || badges.contains("\"LIVE\"") || badges.contains("مباشر"),
                        )
                    }
                }
                "channelRenderer" -> {
                    val id = obj.optString("channelId")
                    if (id.startsWith("UC") && channels.none { it.id == id }) {
                        channels += YtChannel(id, obj.optJSONObject("title")?.optString("simpleText").orEmpty())
                    }
                }
            }
        }
        return videos.take(8) to channels.take(3)
    }

    /**
     * The newest upload of a channel, from its public feed (its last 15 videos). With [matching], the
     * newest one of that show/series — "آخر حلقة أنا أقطع" is the newest video titled so, not one named "the last episode".
     */
    suspend fun latest(channelId: String, matching: String? = null): YtVideo? {
        val xml = page("https://www.youtube.com/feeds/videos.xml?channel_id=$channelId") ?: return null
        val all = com.moath.tvhome.youtube.YoutubeFeed.parse(xml).sortedByDescending { it.publishedMs }
        val v = (if (matching.isNullOrBlank()) all.firstOrNull() else all.firstOrNull { titleMatches(it.title, matching) }) ?: return null
        return YtVideo(v.id, v.title, v.channel, "", live = false)
    }

    /** Every word of [show] (ignoring spelling variants of Arabic letters) appears in [title]. */
    fun titleMatches(title: String, show: String): Boolean {
        val t = normAr(title)
        val words = normWords(show).split(' ').filter { it.length >= 2 }
        return words.isNotEmpty() && words.all { t.contains(it) }
    }

    /** The channel's live stream right now, or null. */
    suspend fun live(channelId: String): String? = livePath("channel/$channelId")

    /** [path] is "channel/UC…" or "@handle". */
    suspend fun livePath(path: String): String? {
        val html = page("https://www.youtube.com/$path/live") ?: return null
        return parseLive(html)
    }

    fun parseLive(html: String): String? {
        val canonical = Regex("""<link rel="canonical" href="https://www\.youtube\.com/watch\?v=([0-9A-Za-z_-]{11})""").find(html)?.groupValues?.get(1)
            ?: return null
        val isLive = html.contains("\"isLiveNow\":true") || html.contains("\"isLive\":true")
        return canonical.takeIf { isLive }
    }

    private fun initialData(html: String): JSONObject? {
        val start = html.indexOf("ytInitialData").takeIf { it >= 0 } ?: return null
        val brace = html.indexOf('{', start).takeIf { it >= 0 } ?: return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in brace until html.length) {
            val c = html[i]
            if (inString) {
                if (escape) escape = false else if (c == '\\') escape = true else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> if (--depth == 0) return runCatching { JSONObject(html.substring(brace, i + 1)) }.getOrNull()
            }
        }
        return null
    }

    private fun runs(o: JSONObject?): String {
        o ?: return ""
        o.optString("simpleText").takeIf { it.isNotEmpty() }?.let { return it }
        val r = o.optJSONArray("runs") ?: return ""
        return (0 until r.length()).joinToString("") { r.getJSONObject(it).optString("text") }
    }

    private fun walk(node: Any?, visit: (String, JSONObject) -> Unit) {
        when (node) {
            is JSONObject -> node.keys().forEach { k ->
                val v = node.opt(k)
                if (v is JSONObject) visit(k, v)
                walk(v, visit)
            }
            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), visit)
        }
    }
}
