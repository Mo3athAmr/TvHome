package com.moath.tvhome.home

import com.moath.tvhome.tmdb.TmdbItem

/** Pure formatting helpers for Top Shelf content, kept free of Android so they unit-test. */
object ShelfFormat {
    const val MAX_ITEMS = 8
    private const val MAX_GENRES = 2
    private const val SEPARATOR = " · "

    /** "مسلسل · 2024 · دراما، جريمة" — missing parts are skipped, never guessed. */
    fun meta(item: TmdbItem): String = listOfNotNull(
        when (item.mediaType) {
            "tv" -> "مسلسل"
            "movie" -> "فيلم"
            else -> null
        },
        item.year,
        item.genres.take(MAX_GENRES).joinToString("، ").ifEmpty { null },
    ).joinToString(SEPARATOR)

    /** Alternates two result lists (tv, movie, tv, …) so a provider's shelf is not all one kind. */
    fun <T> interleave(a: List<T>, b: List<T>): List<T> {
        val out = ArrayList<T>(a.size + b.size)
        for (i in 0 until maxOf(a.size, b.size)) {
            a.getOrNull(i)?.let(out::add)
            b.getOrNull(i)?.let(out::add)
        }
        return out
    }

    /** Only titles with real artwork make the shelf; duplicates and people are dropped. */
    fun usable(items: List<TmdbItem>): List<TmdbItem> =
        items
            .filter { it.backdropPath != null && (it.mediaType == "tv" || it.mediaType == "movie") }
            .distinctBy { "${it.mediaType}/${it.id}" }
            .take(MAX_ITEMS)
}
