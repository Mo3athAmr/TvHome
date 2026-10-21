package com.moath.tvhome.aerial

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.moath.tvhome.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * The Aerial catalogue, sourced from Apple's own feed (the same tarball tvOS downloads):
 * `https://sylvan.apple.com/Aerials/resources-16.tar` → entries.json (clips, URLs, POI keys) +
 * TVIdleScreenStrings.bundle/en.lproj/Localizable.nocache.strings (caption text).
 *
 * Resolution order: on-disk cache (refreshed weekly) → bundled snapshot of the same feed
 * (res/raw/aerial_catalogue.json, generated from resources-16.tar). Both use one compact format.
 */
@UnstableApi
internal class AerialCatalogue(context: Context) {
    private val app = context.applicationContext
    private val file = File(app.filesDir, "aerial_catalogue.json")

    /** Fast, offline: the cached feed if present, else the bundled snapshot. */
    suspend fun load(): List<AerialClip> = withContext(Dispatchers.IO) {
        readCached()?.takeIf { it.clips.isNotEmpty() }?.clips ?: readBundled()
    }

    fun isStale(now: Long = System.currentTimeMillis()): Boolean {
        val fetchedAt = runCatching { if (file.exists()) JSONObject(file.readText()).optLong("fetchedAt") else 0L }.getOrDefault(0L)
        return now - fetchedAt > REFRESH_MS || now < fetchedAt
    }

    /** Downloads and parses Apple's feed; returns the new clips, or null on failure (logged). */
    suspend fun refresh(): List<AerialClip>? = withContext(Dispatchers.IO) {
        for (version in FEED_VERSIONS) {
            val url = "https://sylvan.apple.com/Aerials/resources-$version.tar"
            try {
                val clips = fetchFeed(url)
                if (clips.isEmpty()) throw IOException("feed has no playable clips")
                writeCache(clips, "resources-$version")
                Log.i(TAG, "Catalogue refreshed from $url: ${clips.size} clips")
                return@withContext clips
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Catalogue fetch failed for $url", e)
            }
        }
        null
    }

    private fun fetchFeed(url: String): List<AerialClip> {
        val request = Request.Builder().url(url).build()
        AerialNetwork.client(app).newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("empty body")
            val parts = body.byteStream().buffered().use { AerialTar.extract(it, setOf(ENTRIES, STRINGS)) }
            val entries = parts[ENTRIES] ?: throw IOException("entries.json missing")
            val strings = parts[STRINGS]?.let(AerialPlist::parseStrings).orEmpty()
            if (strings.isEmpty()) Log.w(TAG, "No caption strings in feed; captions fall back to location names")
            return parseEntries(String(entries, Charsets.UTF_8), strings)
        }
    }

    private fun readCached(): Snapshot? = runCatching {
        if (file.exists()) decode(file.readText()) else null
    }.onFailure { Log.w(TAG, "Cached catalogue unreadable; using bundled", it) }.getOrNull()

    private fun readBundled(): List<AerialClip> =
        app.resources.openRawResource(R.raw.aerial_catalogue).use { decode(it.readBytes().toString(Charsets.UTF_8)).clips }

    private fun writeCache(clips: List<AerialClip>, source: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(encode(clips, source, System.currentTimeMillis()))
        if (!tmp.renameTo(file)) throw IOException("could not replace catalogue cache")
    }

    private class Snapshot(val clips: List<AerialClip>)

    companion object {
        private const val TAG = "Aerial"
        private const val REFRESH_MS = 7L * 24 * 60 * 60 * 1000
        private val FEED_VERSIONS = listOf(16, 15)
        private const val ENTRIES = "entries.json"
        private const val STRINGS = "TVIdleScreenStrings.bundle/en.lproj/Localizable.nocache.strings"

        /** Apple entries.json → clips with resolved captions. Clips without an H.264 URL are dropped. */
        fun parseEntries(json: String, strings: Map<String, String>): List<AerialClip> {
            val assets = JSONObject(json).optJSONArray("assets") ?: return emptyList()
            return (0 until assets.length()).mapNotNull { i ->
                val a = assets.optJSONObject(i) ?: return@mapNotNull null
                val h264 = a.optString("url-1080-H264").takeIf { it.startsWith("https://") } ?: return@mapNotNull null
                val poiJson = a.optJSONObject("pointsOfInterest")
                val pois = poiJson?.keys()?.asSequence()?.mapNotNull { key ->
                    val at = key.toLongOrNull() ?: return@mapNotNull null
                    strings[poiJson.optString(key)]?.let { AerialPoi(at, it) }
                }?.sortedBy { it.atSec }?.toList().orEmpty()
                AerialClip(
                    id = a.optString("id", h264),
                    label = a.optString("accessibilityLabel"),
                    url1080 = h264,
                    url4k = a.optString("url-4K-SDR").takeIf { it.startsWith("https://") },
                    pois = pois,
                )
            }
        }

        private fun decode(text: String): Snapshot {
            val clips = JSONObject(text).getJSONArray("clips")
            return Snapshot((0 until clips.length()).mapNotNull { i ->
                val c = clips.getJSONObject(i)
                val url = c.optString("h264").takeIf { it.startsWith("https://") } ?: return@mapNotNull null
                val poi = c.optJSONObject("poi")
                AerialClip(
                    id = c.optString("id", url),
                    label = c.optString("label"),
                    url1080 = url,
                    url4k = c.optString("hevc4k").takeIf { it.startsWith("https://") },
                    pois = poi?.keys()?.asSequence()?.mapNotNull { k ->
                        k.toLongOrNull()?.let { AerialPoi(it, poi.optString(k)) }
                    }?.sortedBy { it.atSec }?.toList().orEmpty(),
                )
            })
        }

        private fun encode(clips: List<AerialClip>, source: String, fetchedAt: Long): String {
            val arr = JSONArray()
            clips.forEach { c ->
                arr.put(JSONObject().apply {
                    put("id", c.id)
                    put("label", c.label)
                    put("h264", c.url1080)
                    c.url4k?.let { put("hevc4k", it) }
                    put("poi", JSONObject().apply { c.pois.forEach { put(it.atSec.toString(), it.text) } })
                })
            }
            return JSONObject().put("v", 1).put("source", source).put("fetchedAt", fetchedAt).put("clips", arr).toString()
        }
    }
}
