package com.moath.tvhome.assistant

import com.moath.tvhome.tmdb.NsfwFilter
import com.moath.tvhome.tmdb.TmdbItem
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * What the assistant asked the film library for, in plain words ("korean", "horror", "new"), turned
 * into TMDB discover filters. Kept free of Android so the mapping can be tested.
 */
data class LibraryQuery(
    /** "movie", "tv" or "any". */
    val kind: String = "any",
    val genres: List<String> = emptyList(),
    val origin: String? = null,
    val era: String? = null,
    /** Safe for the whole family (US PG / TV-PG and below). */
    val family: Boolean = false,
    /** No sexual scenes but violence is fine (PG-13 / TV-14 and below). */
    val clean: Boolean = false,
    /** "popular", "top_rated" or "newest". */
    val sort: String = "popular",
)

object Library {
    private val movieGenres = mapOf(
        "action" to "28", "adventure" to "12", "animation" to "16", "comedy" to "35", "crime" to "80",
        "documentary" to "99", "drama" to "18", "family" to "10751", "fantasy" to "14", "history" to "36",
        "horror" to "27", "music" to "10402", "mystery" to "9648", "romance" to "10749", "scifi" to "878",
        "thriller" to "53", "war" to "10752", "western" to "37", "kids" to "10751",
    )

    /** TV has fewer genres: the nearest one stands in. Horror has no TV genre at all — see [tvKeywords]. */
    private val tvGenres = mapOf(
        "action" to "10759", "adventure" to "10759", "animation" to "16", "comedy" to "35", "crime" to "80",
        "documentary" to "99", "drama" to "18", "family" to "10751", "fantasy" to "10765", "history" to "36",
        "mystery" to "9648", "romance" to "18", "scifi" to "10765", "thriller" to "9648|80",
        "war" to "10768", "western" to "37", "kids" to "10762", "reality" to "10764", "talk" to "10767",
    )

    /** TMDB keywords (any of): horror, supernatural, ghost, zombie, supernatural horror, zombie apocalypse. */
    private val tvKeywords = mapOf("horror" to "315058|6152|162846|12377|256183|186565")

    /** Daily talk, news, soaps and reality would crowd out real series unless asked for. */
    private const val TV_NOISE = "10767,10763,10766,10764"

    private data class Origin(val languages: String? = null, val countries: String? = null, val anime: Boolean = false)

    private val origins = mapOf(
        "korean" to Origin("ko"), "japanese" to Origin("ja"), "anime" to Origin("ja", anime = true),
        "chinese" to Origin("zh|cn"), "asian" to Origin("ko|ja|zh|cn|th"), "thai" to Origin("th"),
        "arabic" to Origin("ar"), "egyptian" to Origin(countries = "EG"), "gulf" to Origin(countries = "SA|AE|KW|QA|BH|OM"),
        "turkish" to Origin("tr"), "indian" to Origin("hi|ta|te|ml"), "english" to Origin("en"),
        "american" to Origin(countries = "US"), "british" to Origin(countries = "GB"),
        "spanish" to Origin("es"), "french" to Origin("fr"),
    )

    val genreNames: Set<String> get() = movieGenres.keys + tvGenres.keys + tvKeywords.keys
    val originNames: Set<String> get() = origins.keys

    fun normGenre(g: String): String = g.trim().lowercase().replace("-", "").replace(" ", "").let {
        when (it) {
            "sciencefiction", "sci_fi" -> "scifi"
            "animated", "cartoon" -> "animation"
            "children" -> "kids"
            else -> it
        }
    }

    /** Discover parameters for one media type ("movie" or "tv"). */
    fun params(q: LibraryQuery, type: String, today: LocalDate = LocalDate.now()): Map<String, String> {
        val p = LinkedHashMap<String, String>()
        p["include_adult"] = "false"
        p["without_keywords"] = NsfwFilter.WITHOUT_KEYWORDS
        val table = if (type == "tv") tvGenres else movieGenres
        val origin = q.origin?.lowercase()?.let { origins[it] }
        val genreIds = (q.genres.map(::normGenre).mapNotNull { table[it] } + listOfNotNull("16".takeIf { origin?.anime == true }))
            .distinct()
        // AND between the asked genres; a single entry may itself be an OR ("9648|10765").
        if (genreIds.isNotEmpty()) p["with_genres"] = genreIds.joinToString(",")
        if (type == "tv") {
            q.genres.map(::normGenre).firstNotNullOfOrNull { tvKeywords[it] }?.let { p["with_keywords"] = it }
            val asked = genreIds.flatMap { it.split('|', ',') }.toSet()
            val noise = TV_NOISE.split(',').filterNot { it in asked }
            if (noise.isNotEmpty()) p["without_genres"] = noise.joinToString(",")
        }
        origin?.languages?.let { p["with_original_language"] = it }
        origin?.countries?.let { p["with_origin_country"] = it }
        if (q.family || q.clean) {
            p["certification_country"] = "US"
            p["certification.lte"] = when {
                type == "tv" && q.family -> "TV-PG"
                type == "tv" -> "TV-14"
                q.family -> "PG"
                else -> "PG-13"
            }
        }
        val dateField = if (type == "tv") "first_air_date" else "primary_release_date"
        eraRange(q.era, today)?.let { (from, to) ->
            from?.let { p["$dateField.gte"] = it.toString() }
            p["$dateField.lte"] = (to ?: today).toString()
        } ?: run { p["$dateField.lte"] = today.toString() }
        when (q.sort) {
            "top_rated" -> { p["sort_by"] = "vote_average.desc"; p["vote_count.gte"] = "300" }
            "newest" -> { p["sort_by"] = "$dateField.desc"; p["vote_count.gte"] = "5" }
            else -> { p["sort_by"] = "popularity.desc"; p["vote_count.gte"] = if (q.era == "new") "10" else "60" }
        }
        return p
    }

    /** "new" (last 18 months), "recent" (5 years), "classic" (before 2000), "90s", "2010s", "2015", "2000-2010". */
    fun eraRange(era: String?, today: LocalDate): Pair<LocalDate?, LocalDate?>? {
        val e = era?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "any" } ?: return null
        Regex("""^(\d{4})\s*-\s*(\d{4})$""").find(e)?.let { m ->
            return LocalDate.of(m.groupValues[1].toInt(), 1, 1) to LocalDate.of(m.groupValues[2].toInt(), 12, 31)
        }
        Regex("""^(\d{4})$""").find(e)?.let { m -> val y = m.groupValues[1].toInt(); return LocalDate.of(y, 1, 1) to LocalDate.of(y, 12, 31) }
        Regex("""^(\d{2,4})s$""").find(e)?.let { m ->
            val n = m.groupValues[1].toInt()
            val start = when { n >= 1900 -> n; n >= 30 -> 1900 + n; else -> 2000 + n }
            return LocalDate.of(start, 1, 1) to LocalDate.of(start + 9, 12, 31)
        }
        return when (e) {
            "new", "latest" -> today.minusMonths(18) to today
            "recent" -> today.minusYears(5) to today
            "classic", "old" -> null to LocalDate.of(1999, 12, 31)
            else -> null
        }
    }

    // The picks travel to the Search screen (another process) inside the intent.

    fun toJson(items: List<TmdbItem>): String = JSONArray().apply {
        items.forEach { t ->
            put(
                JSONObject().put("id", t.id).put("type", t.mediaType).put("title", t.title).put("overview", t.overview)
                    .put("backdrop", t.backdropPath ?: "").put("poster", t.posterPath ?: "").put("year", t.year ?: "")
                    .put("rating", t.rating).put("genres", JSONArray(t.genres)),
            )
        }
    }.toString()

    fun fromJson(s: String?): List<TmdbItem> = runCatching {
        val arr = JSONArray(s ?: return emptyList())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val g = o.optJSONArray("genres") ?: JSONArray()
            TmdbItem(
                id = o.getLong("id"), mediaType = o.getString("type"), title = o.getString("title"), overview = o.optString("overview"),
                backdropPath = o.optString("backdrop").ifEmpty { null }, posterPath = o.optString("poster").ifEmpty { null },
                year = o.optString("year").ifEmpty { null }, genres = (0 until g.length()).map { g.getString(it) }, rating = o.optDouble("rating"),
            )
        }
    }.getOrDefault(emptyList())
}
