package com.moath.tvhome.assistant

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant

/**
 * "Open One Piece, the last episode of the last season, in Stremio": the title is found in Stremio's own
 * catalogue (Cinemeta, so the ids match what Stremio shows), the episode is picked from its episode list,
 * and the deep link opens exactly that page. [com.moath.tvhome.system.StremioTuner] then chooses a stream.
 */
object StremioFlow {
    const val PACKAGE = "com.stremio.one"
    const val ACTION_PLAY = "com.moath.tvhome.STREMIO_PLAY"
    /** The deep link that was opened, so the tuner can open it again if Stremio hangs on a cold start. */
    const val EXTRA_LINK = "link"
    private const val TAG = "StremioFlow"
    private const val API = "https://v3-cinemeta.strem.io"

    data class Target(val type: String, val imdb: String, val videoId: String, val name: String, val season: Int?, val episode: Int?) {
        val deepLink: String get() = "stremio:///detail/$type/$imdb/$videoId"
    }

    data class Meta(val id: String, val name: String)
    data class Ep(val id: String, val season: Int, val episode: Int, val released: String?)

    /** Newest aired episode, a given one, or the Nth aired overall — see [pickEpisode]. */
    suspend fun resolve(title: String, kind: String?, anime: Boolean, season: String?, episode: String?): Target? = withContext(Dispatchers.IO) {
        val t0 = SystemClock.uptimeMillis()
        val types = when (kind) { "movie" -> listOf("movie"); "series" -> listOf("series"); else -> listOf("series", "movie") }
        val metas = HashMap<String, JSONObject?>() // each (big) episode list is downloaded once
        for (type in types) {
            val found = search(title, type)
            Log.i(TAG, "$type search '$title': ${found.size} hits (${SystemClock.uptimeMillis() - t0} ms)")
            val pick = chooseMeta(title, found, anime) { m -> metas.getOrPut("$type/${m.id}") { fetchMeta(type, m.id) } } ?: continue
            if (type == "movie") return@withContext Target("movie", pick.id, pick.id, pick.name, null, null)
            val meta = metas.getOrPut("series/${pick.id}") { fetchMeta("series", pick.id) }
            val videos = episodes(meta)
            val ep = pickEpisode(videos, Instant.now().toString(), season, episode)
            Log.i(TAG, "series ${pick.id} '${pick.name}': ${videos.size} episodes -> ${ep?.id} (${SystemClock.uptimeMillis() - t0} ms)")
            // A series was found: never fall back to a movie of the same name.
            return@withContext ep?.let { Target("series", pick.id, it.id, pick.name, it.season, it.episode) }
        }
        null
    }

    /** One retry: the catalogue sometimes times out once, and "not found" must mean really not found. */
    private suspend fun search(title: String, type: String): List<Meta> {
        val q = URLEncoder.encode(title, "UTF-8").replace("+", "%20")
        repeat(2) {
            val found = runCatching {
                val arr = Http.get("$API/catalog/$type/top/search=$q.json").optJSONArray("metas")
                (0 until (arr?.length() ?: 0)).map { arr!!.getJSONObject(it) }.map { Meta(it.getString("id"), it.optString("name")) }
            }.getOrNull()
            if (!found.isNullOrEmpty()) return found
        }
        return emptyList()
    }

    private suspend fun fetchMeta(type: String, id: String): JSONObject? = runCatching {
        Http.get("$API/meta/$type/$id.json").optJSONObject("meta")
    }.getOrNull()

    /** Exact name first; for anime, the animated one (a live-action remake shares the title). Pure but for [fetch]. */
    internal suspend fun chooseMeta(title: String, metas: List<Meta>, anime: Boolean, fetch: suspend (Meta) -> JSONObject?): Meta? {
        if (metas.isEmpty()) return null
        val exact = metas.filter { normAr(it.name) == normAr(title) }
        val candidates = exact.ifEmpty { metas }.take(4)
        if (!anime) return candidates.first()
        for (c in candidates) {
            val m = fetch(c) ?: continue
            val genres = m.optJSONArray("genres")?.let { g -> (0 until g.length()).map { g.getString(it) } }.orEmpty()
            if (genres.any { it.equals("Animation", true) || it.equals("Anime", true) }) return c
        }
        return candidates.first()
    }

    internal fun episodes(meta: JSONObject?): List<Ep> {
        val arr = meta?.optJSONArray("videos") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.mapNotNull { v ->
            val s = v.optInt("season", -1)
            val e = v.optInt("episode", v.optInt("number", -1))
            if (s < 0 || e < 0) null else Ep(v.optString("id"), s, e, v.optString("released").ifBlank { null })
        }
    }

    /**
     * [season]/[episode]: a number, "last" or null. Nothing given = the newest aired episode. A season with
     * "last" = that season's newest aired; an episode number alone = that episode counted from the start
     * (how fans count One Piece: "episode 1000").
     */
    fun pickEpisode(videos: List<Ep>, nowIso: String, season: String?, episode: String?): Ep? {
        val aired = videos.filter { it.season > 0 && (it.released == null || it.released <= nowIso) }
        val order = compareBy<Ep>({ it.season }, { it.episode })
        val s = season?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val e = episode?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val seasonNum = s?.toIntOrNull()
        val epNum = e?.toIntOrNull()
        val last = { list: List<Ep> -> list.maxWithOrNull(order) }
        return when {
            seasonNum != null && epNum != null -> videos.firstOrNull { it.season == seasonNum && it.episode == epNum }
            seasonNum != null && (e == null || e == "first") -> aired.filter { it.season == seasonNum }.minWithOrNull(order)
            seasonNum != null -> last(aired.filter { it.season == seasonNum })
            epNum != null -> aired.sortedWith(order).getOrNull(epNum - 1)
            else -> last(aired)
        }
    }
}
