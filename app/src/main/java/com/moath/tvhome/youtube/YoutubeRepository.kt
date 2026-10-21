package com.moath.tvhome.youtube

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * "New on YouTube": the latest uploads of the channels listed in `youtube_channels.txt` (in the
 * app's files folder on the box), read from YouTube's public channel feeds — no account, no API key.
 * The last result is cached so Home shows something right away, even offline. Videos the owner has
 * opened (or dismissed) are remembered and never shown again.
 */
class YoutubeRepository private constructor(private val context: Context) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    // Shorts answer 200 on /shorts/<id>; ordinary videos redirect away from it.
    private val noRedirect = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val prefs = context.getSharedPreferences("youtube", Context.MODE_PRIVATE)

    /** Where the owner drops the channel list (`adb push`, or a Takeout subscriptions.csv). */
    fun channelFiles(): List<File> {
        val dir = context.getExternalFilesDir(null) ?: return emptyList()
        return CHANNEL_FILES.map { File(dir, it) }.filter { it.isFile && it.length() > 0 }
    }

    /** Channel id → name, from the channel list's "# name" comments and the cached videos. */
    fun channelNames(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        channelFiles().forEach { f ->
            runCatching { f.readLines() }.getOrDefault(emptyList()).forEach { line ->
                val id = Regex("UC[0-9A-Za-z_-]{22}").find(line)?.value ?: return@forEach
                val name = line.substringAfter('#', "").substringAfter(',', line.substringAfter('#', "")).trim()
                if (name.isNotEmpty()) out[id] = name
            }
        }
        loadCache().forEach { v -> if (v.channelId.isNotEmpty() && v.channel.isNotEmpty()) out.putIfAbsent(v.channelId, v.channel) }
        return out
    }

    fun channelCount(): Int = runCatching {
        val parsed = parseChannels()
        parsed.ids.size + parsed.handles.size
    }.getOrDefault(0)

    private fun parseChannels(): YoutubeChannels.Parsed {
        val text = channelFiles().joinToString("\n") { runCatching { it.readText() }.getOrDefault("") }
        return YoutubeChannels.parse(text)
    }

    /** Fetches every channel's feed and updates the cache. Returns false when nothing could be loaded. */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        val parsed = parseChannels()
        if (parsed.ids.isEmpty() && parsed.handles.isEmpty()) {
            saveCache(emptyList()) // no channel list: nothing to show
            return@withContext false
        }
        val limit = Semaphore(PARALLEL)
        val ids = LinkedHashSet(parsed.ids)
        coroutineScope {
            parsed.handles.map { h -> async { limit.withPermit { resolveHandle(h) } } }.awaitAll()
                .forEach { if (it != null) ids += it }
        }
        val now = System.currentTimeMillis()
        val fetched = coroutineScope {
            ids.map { id -> async { limit.withPermit { channelFeed(id) } } }.awaitAll()
        }
        val ok = fetched.any { it != null }
        val previous = loadCache().associateBy { it.id }
        val merged = LinkedHashMap<String, YoutubeVideo>()
        for (v in fetched.filterNotNull().flatten()) merged[v.id] = previous[v.id]?.let { v.copy(isShort = it.isShort) } ?: v
        // A channel that failed this round keeps what we knew about it.
        for (v in previous.values) if (v.channelId in ids) merged.putIfAbsent(v.id, v) // not the ones of channels no longer on the list
        val fresh = merged.values
            .filter { now - it.publishedMs < MAX_AGE_MS }
            .sortedByDescending { it.publishedMs }
            .take(KEEP)
        val checked = coroutineScope {
            fresh.map { v ->
                async { limit.withPermit { if (v.isShort == null) v.copy(isShort = isShort(v.id)) else v } }
            }.awaitAll()
        }
        saveCache(checked)
        ok
    }

    /** Newest first, Shorts and already-seen videos removed. Works from the cache, so it is instant. */
    fun unseen(max: Int): List<YoutubeVideo> {
        val seen = seenIds()
        return loadCache().filter { it.isShort != true && it.id !in seen }.take(max)
    }

    fun markSeen(id: String) {
        val seen = LinkedHashSet(prefs.getString(K_SEEN, "").orEmpty().split(',').filter { it.isNotEmpty() })
        seen.remove(id)
        seen.add(id)
        prefs.edit().putString(K_SEEN, seen.toList().takeLast(MAX_SEEN).joinToString(",")).apply()
    }

    fun resetSeen() {
        prefs.edit().remove(K_SEEN).apply()
    }

    private fun seenIds(): Set<String> = prefs.getString(K_SEEN, "").orEmpty().split(',').filter { it.isNotEmpty() }.toSet()

    // ── Network ──────────────────────────────────────────────────────────────────────────────

    private fun channelFeed(channelId: String): List<YoutubeVideo>? = body("https://www.youtube.com/feeds/videos.xml?channel_id=$channelId")
        ?.let(YoutubeFeed::parse)

    private fun resolveHandle(handle: String): String? {
        prefs.getString("h:$handle", null)?.let { return it }
        val html = body("https://www.youtube.com/$handle") ?: return null
        val id = Regex("\"channelId\":\"(UC[0-9A-Za-z_-]{22})\"").find(html)?.groupValues?.get(1)
            ?: Regex("youtube\\.com/channel/(UC[0-9A-Za-z_-]{22})").find(html)?.groupValues?.get(1)
        if (id != null) prefs.edit().putString("h:$handle", id).apply()
        return id
    }

    private fun isShort(videoId: String): Boolean = try {
        noRedirect.newCall(Request.Builder().url("https://www.youtube.com/shorts/$videoId").head().build())
            .execute().use { it.code == 200 }
    } catch (e: IOException) {
        false
    }

    private fun body(url: String): String? = try {
        http.newCall(Request.Builder().url(url).header("Accept-Language", "ar").build()).execute().use { res ->
            if (res.isSuccessful) res.body?.string() else {
                Log.w(TAG, "HTTP ${res.code} for $url")
                null
            }
        }
    } catch (e: IOException) {
        Log.w(TAG, "Request failed: $url", e)
        null
    }

    // ── Cache ────────────────────────────────────────────────────────────────────────────────

    private fun loadCache(): List<YoutubeVideo> = runCatching {
        val arr = JSONArray(prefs.getString(K_CACHE, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            YoutubeVideo(
                id = o.getString("id"),
                title = o.getString("t"),
                channelId = o.optString("ci"),
                channel = o.optString("c"),
                publishedMs = o.getLong("p"),
                isShort = if (o.has("s")) o.getBoolean("s") else null,
            )
        }
    }.getOrDefault(emptyList())

    private fun saveCache(videos: List<YoutubeVideo>) {
        val arr = JSONArray()
        videos.forEach { v ->
            arr.put(
                JSONObject().put("id", v.id).put("t", v.title).put("ci", v.channelId).put("c", v.channel).put("p", v.publishedMs)
                    .also { o -> v.isShort?.let { o.put("s", it) } },
            )
        }
        prefs.edit().putString(K_CACHE, arr.toString()).apply()
    }

    companion object {
        private const val TAG = "Youtube"
        private const val PARALLEL = 6
        private const val KEEP = 60
        private const val MAX_SEEN = 600
        private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
        private const val K_CACHE = "cache"
        private const val K_SEEN = "seen"
        private val CHANNEL_FILES = listOf("youtube_channels.txt", "subscriptions.csv")

        @Volatile private var instance: YoutubeRepository? = null

        fun get(context: Context): YoutubeRepository =
            instance ?: synchronized(this) {
                instance ?: YoutubeRepository(context.applicationContext).also { instance = it }
            }
    }
}
