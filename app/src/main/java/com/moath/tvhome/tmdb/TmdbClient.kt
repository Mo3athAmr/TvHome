package com.moath.tvhome.tmdb

import android.content.Context
import android.util.Log
import com.moath.tvhome.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.LocalDate
import okhttp3.Cache
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class TmdbItem(
    val id: Long,
    /** "movie" or "tv". */
    val mediaType: String,
    val title: String,
    val overview: String,
    val backdropPath: String?,
    val posterPath: String?,
    val year: String?,
    val genres: List<String>,
    val rating: Double,
    /** TMDB's own adult flag (explicit films only; see [NsfwFilter] for the rest). */
    val adult: Boolean = false,
    /** Release date (movies) or the newest episode's air date (the "latest series" row), yyyy-mm-dd. */
    val releaseDate: String? = null,
    /** "الموسم ٢ · الحلقة ٥" for the newest aired episode. */
    val episode: String? = null,
    /** Set on the "latest" rows: their meta line is about freshness ("اليوم", "أمس") rather than genre. */
    val fresh: Boolean = false,
)

/**
 * Thin TMDB v3 client (real data only — Top Shelf, Search). All calls are suspend, run on IO,
 * and return empty results instead of throwing when the network is down; failures are logged.
 */
class TmdbClient private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val http = OkHttpClient.Builder()
        .cache(Cache(File(context.cacheDir, "tmdb"), CACHE_BYTES))
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
    private val genreNames = HashMap<Int, String>()
    private val logoCache = HashMap<String, String?>()
    /** "movie/123" → true when its keywords are clean. Only verified answers are kept. */
    private val cleanCache = HashMap<String, Boolean>()
    private val keywordGate = Semaphore(8)

    /**
     * Safety verdicts, logo paths and genre names hardly ever change: kept on disk so a launcher start
     * doesn't repeat dozens of TMDB requests (one keyword check per title).
     */
    private val disk = context.getSharedPreferences("tmdb_cache", Context.MODE_PRIVATE)

    init {
        runCatching {
            for ((k, v) in disk.all) when {
                k.startsWith("c:") && v is Boolean -> cleanCache[k.removePrefix("c:")] = v
                k.startsWith("l:") && v is String -> logoCache[k.removePrefix("l:")] = v.ifEmpty { null }
                k.startsWith("g:") && v is String -> k.removePrefix("g:").toIntOrNull()?.let { genreNames[it] = v }
            }
        }
    }

    val isConfigured: Boolean get() = BuildConfig.TMDB_READ_TOKEN.isNotBlank()

    suspend fun trending(mediaType: String = "all", window: String = "week"): List<TmdbItem> =
        list("trending/$mediaType/$window", emptyMap(), mediaType.takeIf { it != "all" })

    /** Titles streaming on a provider (Prime Video 9/119, Disney+ 337, Apple TV+ 350). */
    suspend fun onProvider(providerId: Int, mediaType: String, region: String): List<TmdbItem> =
        list(
            "discover/$mediaType",
            mapOf(
                "with_watch_providers" to providerId.toString(),
                "watch_region" to region,
                "sort_by" to "popularity.desc",
                "with_watch_monetization_types" to "flatrate",
                "without_keywords" to NsfwFilter.WITHOUT_KEYWORDS,
            ),
            mediaType,
        )

    /** Any discover query (the assistant's library search builds the filters). Re-read at most hourly. */
    suspend fun discover(mediaType: String, params: Map<String, String>, pages: Int = 1): List<TmdbItem> = coroutineScope {
        (1..pages).map { page -> async { list("discover/$mediaType", params + ("page" to page.toString()), mediaType, maxAgeSec = 3600) } }
            .awaitAll().flatten().distinctBy { it.id }
    }

    /** Titles TMDB recommends for people who liked [id] ("something like Breaking Bad"). */
    suspend fun recommendations(id: Long, mediaType: String): List<TmdbItem> =
        list("$mediaType/$id/recommendations", emptyMap(), mediaType)

    /** TMDB's popularity chart for "movie" or "tv". */
    suspend fun popular(mediaType: String): List<TmdbItem> = list("$mediaType/popular", emptyMap(), mediaType)

    /** Highest-rated "movie" or "tv" titles. */
    suspend fun topRated(mediaType: String): List<TmdbItem> = list("$mediaType/top_rated", emptyMap(), mediaType)

    /** Popular titles in one genre (discover with_genres), ignoring titles with too few votes. */
    suspend fun byGenre(mediaType: String, genreId: Int): List<TmdbItem> =
        list(
            "discover/$mediaType",
            mapOf(
                "with_genres" to genreId.toString(),
                "sort_by" to "popularity.desc",
                "vote_count.gte" to "150",
                "without_keywords" to NsfwFilter.WITHOUT_KEYWORDS,
            ),
            mediaType,
        )

    /** New releases, most popular first: movies released / series premiered in the last [days] days. */
    suspend fun latest(mediaType: String, days: Long = 75): List<TmdbItem> {
        val today = LocalDate.now()
        val from = today.minusDays(days).toString()
        val field = if (mediaType == "tv") "first_air_date" else "primary_release_date"
        return list(
            "discover/$mediaType",
            mapOf(
                "$field.gte" to from,
                "$field.lte" to today.toString(),
                "sort_by" to "popularity.desc",
                "include_adult" to "false",
                "without_keywords" to NsfwFilter.WITHOUT_KEYWORDS,
            ),
            mediaType,
        )
    }

    /** Films released in the last [days] days — the popular ones — newest first. Re-read at most every 5 minutes. */
    suspend fun newestMovies(days: Long = 21): List<TmdbItem> {
        val today = LocalDate.now()
        return list(
            "discover/movie",
            mapOf(
                "primary_release_date.gte" to today.minusDays(days).toString(),
                "primary_release_date.lte" to today.toString(),
                "sort_by" to "popularity.desc",
                "include_adult" to "false",
                "without_keywords" to NsfwFilter.WITHOUT_KEYWORDS,
            ),
            "movie",
            maxAgeSec = 300,
        ).map { it.copy(fresh = true) }.sortedByDescending { it.releaseDate.orEmpty() }
    }

    /**
     * Series (anime included) that aired an episode in the last [days] days, the newest episode first, each
     * carrying that episode ("الموسم ٢٣ · الحلقة ٢٥"). A new episode shows up here the day it airs.
     */
    suspend fun newestEpisodes(days: Long = 2): List<TmdbItem> {
        val today = LocalDate.now()
        val window = mapOf(
            "air_date.gte" to today.minusDays(days).toString(),
            "air_date.lte" to today.toString(),
            "sort_by" to "popularity.desc",
            "include_adult" to "false",
            "without_keywords" to NsfwFilter.WITHOUT_KEYWORDS,
        )
        // Talk shows, news and soaps air every day and would fill the row; anime gets its own pull so
        // new episodes of One Piece and the like always show up.
        val (shows, anime) = coroutineScope {
            val a = async { list("discover/tv", window + ("without_genres" to "10767,10763,10766,10764"), "tv", maxAgeSec = 300) }
            val b = async { list("discover/tv", window + ("with_genres" to "16") + ("with_original_language" to "ja"), "tv", maxAgeSec = 300) }
            a.await() to b.await()
        }
        val aired = (anime.take(12) + shows.take(24)).distinctBy { it.id }
        val gate = Semaphore(6)
        return coroutineScope {
            aired.map { item -> async { gate.withPermit { withLastEpisode(item) } } }.awaitAll()
        }.filterNotNull().sortedByDescending { it.releaseDate.orEmpty() }
    }

    private suspend fun withLastEpisode(item: TmdbItem): TmdbItem? {
        val last = get(url("tv/${item.id}", emptyMap()), 600)?.optJSONObject("last_episode_to_air") ?: return null
        val air = last.optString("air_date").takeIf { it.length == 10 } ?: return null
        val s = last.optInt("season_number")
        val e = last.optInt("episode_number")
        return item.copy(releaseDate = air, episode = "الموسم $s · الحلقة $e", fresh = true)
    }

    /** Subscription (flatrate) provider ids for one title in [region], best-known first. */
    suspend fun watchProviders(id: Long, mediaType: String, region: String): List<Int> {
        val json = get(url("$mediaType/$id/watch/providers", emptyMap())) ?: return emptyList()
        val flatrate = json.optJSONObject("results")?.optJSONObject(region)?.optJSONArray("flatrate")
            ?: return emptyList()
        return (0 until flatrate.length()).map { flatrate.getJSONObject(it).optInt("provider_id") }
    }

    suspend fun search(query: String): List<TmdbItem> =
        if (query.isBlank()) emptyList()
        else list("search/multi", mapOf("query" to query, "include_adult" to "false"), null)
            .filter { it.mediaType == "movie" || it.mediaType == "tv" }

    /** One title by id — used when another surface (Top Shelf "More Info") hands over an id. */
    suspend fun details(id: Long, mediaType: String): TmdbItem? {
        ensureGenres()
        val json = get(url("$mediaType/$id", emptyMap())) ?: return null
        val item = parse(json, mediaType)?.let { withOverview(it) } ?: return null
        return item.takeIf { isAllowed(it) }
    }

    /** Arabic overviews are often missing on TMDB; fall back to the English text rather than show none. */
    suspend fun withOverview(item: TmdbItem): TmdbItem {
        if (item.overview.isNotBlank()) return item
        val en = get(url("${item.mediaType}/${item.id}", emptyMap(), language = "en-US"))?.optString("overview").orEmpty()
        return if (en.isBlank()) item else item.copy(overview = en)
    }

    /** IMDb id ("tt…") of a title: Stremio addresses titles by it. */
    suspend fun imdbId(id: Long, mediaType: String): String? =
        get(url("$mediaType/$id/external_ids", emptyMap()))?.optString("imdb_id")?.takeIf { it.startsWith("tt") }

    /** Best Arabic, else English (or language-neutral), title-logo path, as tvOS Top Shelf shows logos, not text. */
    suspend fun logoPath(item: TmdbItem): String? {
        val key = "${item.mediaType}/${item.id}"
        synchronized(logoCache) { if (key in logoCache) return logoCache[key] }
        val json = get(url("$key/images", mapOf("include_image_language" to "ar,en,null"))) ?: return null
        val logos = json.optJSONArray("logos") ?: JSONArray()
        var best: String? = null
        var bestScore = -1.0
        for (i in 0 until logos.length()) {
            val l = logos.getJSONObject(i)
            val lang = l.optString("iso_639_1")
            val score = l.optDouble("vote_average") + when (lang) { "ar" -> 20; "en" -> 10; else -> 0 }
            if (score > bestScore && !l.optString("file_path").endsWith(".svg")) {
                bestScore = score
                best = l.optString("file_path")
            }
        }
        synchronized(logoCache) { logoCache[key] = best }
        disk.edit().putString("l:$key", best ?: "").apply()
        return best
    }

    private suspend fun list(path: String, params: Map<String, String>, forcedType: String?, maxAgeSec: Int? = null): List<TmdbItem> {
        ensureGenres()
        val json = get(url(path, params), maxAgeSec) ?: return emptyList()
        val results = json.optJSONArray("results") ?: return emptyList()
        val parsed = (0 until results.length()).mapNotNull { parse(results.getJSONObject(it), forcedType) }
            .filter { !it.adult && !NsfwFilter.blockedByText(it.title, it.overview) }
        // Keyword check for the rest; a title that cannot be verified is left out.
        return coroutineScope { parsed.map { item -> async { item.takeIf { isAllowed(it) } } }.awaitAll().filterNotNull() }
    }

    private suspend fun isAllowed(item: TmdbItem): Boolean {
        if (item.adult || NsfwFilter.blockedByText(item.title, item.overview)) return false
        val key = "${item.mediaType}/${item.id}"
        synchronized(cleanCache) { cleanCache[key] }?.let { return it }
        val json = keywordGate.withPermit { get(url("$key/keywords", emptyMap())) } ?: return false
        val arr = json.optJSONArray(if (item.mediaType == "tv") "results" else "keywords") ?: JSONArray()
        val ids = (0 until arr.length()).map { arr.getJSONObject(it).optInt("id") }
        val clean = !NsfwFilter.blockedByKeywords(ids)
        synchronized(cleanCache) { cleanCache[key] = clean }
        disk.edit().putBoolean("c:$key", clean).apply()
        return clean
    }

    private fun parse(o: JSONObject, forcedType: String?): TmdbItem? {
        val type = forcedType ?: o.optString("media_type").ifEmpty { return null }
        val title = o.optString(if (type == "tv") "name" else "title").ifEmpty { return null }
        val date = o.optString(if (type == "tv") "first_air_date" else "release_date")
        val ids = o.optJSONArray("genre_ids") ?: JSONArray().also { arr ->
            // Detail responses carry full genre objects instead of ids.
            val full = o.optJSONArray("genres") ?: return@also
            for (i in 0 until full.length()) arr.put(full.getJSONObject(i).optInt("id"))
        }
        return TmdbItem(
            id = o.optLong("id"),
            mediaType = type,
            title = title,
            overview = o.optString("overview"),
            backdropPath = o.optString("backdrop_path").takeIf { it.startsWith("/") },
            posterPath = o.optString("poster_path").takeIf { it.startsWith("/") },
            year = date.take(4).takeIf { it.length == 4 },
            genres = (0 until ids.length()).mapNotNull { synchronized(genreNames) { genreNames[ids.getInt(it)] } },
            rating = o.optDouble("vote_average", 0.0),
            adult = o.optBoolean("adult", false),
            releaseDate = date.takeIf { it.length == 10 },
        )
    }

    private suspend fun ensureGenres() {
        if (synchronized(genreNames) { genreNames.isNotEmpty() }) return
        for (type in listOf("movie", "tv")) {
            val arr = get(url("genre/$type/list", emptyMap()))?.optJSONArray("genres") ?: continue
            synchronized(genreNames) {
                val edit = disk.edit()
                for (i in 0 until arr.length()) {
                    val g = arr.getJSONObject(i)
                    genreNames[g.getInt("id")] = g.getString("name")
                    edit.putString("g:${g.getInt("id")}", g.getString("name"))
                }
                edit.apply()
            }
        }
    }

    private fun url(path: String, params: Map<String, String>, language: String = LANGUAGE): HttpUrl =
        "$API/$path".toHttpUrl().newBuilder().apply {
            addQueryParameter("language", language)
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()

    /** [maxAgeSec]: accept a cached answer only if younger than this (TMDB's own headers let the HTTP cache keep lists for hours). */
    private suspend fun get(url: HttpUrl, maxAgeSec: Int? = null): JSONObject? = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext null
        val req = Request.Builder().url(url)
            .apply { if (maxAgeSec != null) cacheControl(okhttp3.CacheControl.Builder().maxAge(maxAgeSec, TimeUnit.SECONDS).build()) }
            .header("Authorization", "Bearer ${BuildConfig.TMDB_READ_TOKEN}")
            .header("Accept", "application/json")
            .build()
        try {
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    Log.w(TAG, "TMDB ${res.code} for ${url.encodedPath}")
                    if (res.code == 401) com.moath.tvhome.health.Health.report(appContext, "feed.tmdb", "رمز TMDB مرفوض")
                    null
                } else {
                    com.moath.tvhome.health.Health.recover(appContext, "feed.tmdb")
                    res.body?.string()?.let(::JSONObject)
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "TMDB request failed: ${url.encodedPath}", e)
            null
        } catch (e: org.json.JSONException) {
            Log.w(TAG, "TMDB bad JSON: ${url.encodedPath}", e)
            null
        }
    }

    companion object {
        private const val TAG = "Tmdb"
        /** Titles, overviews and genre names come back in Arabic where TMDB has them. */
        private const val LANGUAGE = "ar-SA"
        private const val API = "https://api.themoviedb.org/3"
        private const val IMG = "https://image.tmdb.org/t/p"
        private const val CACHE_BYTES = 16L * 1024 * 1024

        const val PROVIDER_PRIME = 9
        const val PROVIDER_DISNEY = 337
        const val PROVIDER_APPLE_TV = 350

        /** Sizes: backdrops w780/w1280/original, posters w342/w500, logos w500. */
        fun image(path: String?, size: String = "w1280"): String? = path?.let { "$IMG/$size$it" }

        @Volatile private var instance: TmdbClient? = null

        fun get(context: Context): TmdbClient =
            instance ?: synchronized(this) {
                instance ?: TmdbClient(context.applicationContext).also { instance = it }
            }
    }
}
