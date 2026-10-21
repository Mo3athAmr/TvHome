package com.moath.tvhome.home

import android.util.Log
import com.moath.tvhome.tmdb.TmdbClient
import com.moath.tvhome.tmdb.TmdbItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** A streaming service we can both query on TMDB and open on the box. */
data class Provider(val tmdbId: Int, val name: String, val packageName: String)

object Providers {
    val all = listOf(
        Provider(TmdbClient.PROVIDER_PRIME, "Prime Video", "com.amazon.amazonvideo.livingroom"),
        Provider(TmdbClient.PROVIDER_DISNEY, "Disney+", "com.disney.disneyplus"),
        Provider(TmdbClient.PROVIDER_APPLE_TV, "Apple TV+", "com.apple.atve.androidtv.appletv"),
    )

    /** TMDB provider id → package, including ids that share an app (Prime's 119). */
    private val packages = all.associate { it.tmdbId to it.packageName } + mapOf(
        PRIME_ALT to "com.amazon.amazonvideo.livingroom",
        YOUTUBE to YOUTUBE_PACKAGE,
    )

    fun packageFor(tmdbProviderId: Int): String? = packages[tmdbProviderId]

    const val YOUTUBE_PACKAGE = "com.google.android.youtube.tv"
    private const val PRIME_ALT = 119
    private const val YOUTUBE = 192
}

/**
 * Builds each tab's hero carousel and rows from TMDB. Never throws: a dead network or a missing
 * token yields empty lists, which the UI turns into its apps-first layout.
 */
class ContentRepository(private val tmdb: TmdbClient) {

    suspend fun hero(tab: Tab, region: String, installed: Set<String>): List<HeroItem> = safe {
        val picks = when (tab) {
            Tab.Home -> homeHero(region, installed)
            Tab.Movies -> tmdb.trending("movie").usable(HERO_COUNT).map { it to null }
            Tab.Shows -> tmdb.trending("tv").usable(HERO_COUNT).map { it to null }
            Tab.Apps -> emptyList()
        }
        coroutineScope {
            picks.map { (item, provider) -> async { heroItem(item, provider) } }.awaitAll()
        }
    }

    suspend fun rows(tab: Tab, region: String, installed: Set<String>): List<ContentRow> = safe {
        coroutineScope {
            specs(tab, installed).map { spec -> async { spec.load(region) } }.awaitAll().filterNotNull()
        }
    }

    // ── Hero ─────────────────────────────────────────────────────────────────────────────────

    /** Trending titles interleaved with what's featured on the streaming apps actually installed. */
    private suspend fun homeHero(region: String, installed: Set<String>): List<Pair<TmdbItem, Provider?>> =
        coroutineScope {
            val trending = async { tmdb.trending("all").usable(HERO_COUNT).map { it to null as Provider? } }
            val featured = Providers.all.filter { it.packageName in installed }.map { p ->
                async { providerTitles(p.tmdbId, region).usable(PICKS_PER_PROVIDER).map { it to p as Provider? } }
            }
            val picks = ShelfFormat.interleave(trending.await(), featured.awaitAll().flatten())
            picks.distinctBy { (item, _) -> key(item) }.take(HERO_COUNT)
        }

    private suspend fun heroItem(item: TmdbItem, provider: Provider?): HeroItem = HeroItem(
        title = title(item),
        attribution = provider?.let { "متوفر على ${it.name}" } ?: "الأكثر رواجاً",
        logoUrl = TmdbClient.image(tmdb.logoPath(item), LOGO_SIZE),
        providerPackage = provider?.packageName,
    )

    // ── Rows ─────────────────────────────────────────────────────────────────────────────────

    private inner class RowSpec(
        val id: String,
        val heading: String,
        val style: CardStyle,
        val fetch: suspend (region: String) -> List<TmdbItem>,
    ) {
        suspend fun load(region: String): ContentRow? {
            val items = fetch(region)
                .filter { if (style == CardStyle.Poster) it.posterPath != null else it.backdropPath != null }
                .distinctBy(::key)
                .take(ROW_MAX)
                .map(::title)
            return if (items.size < ROW_MIN) null else ContentRow(id, heading, style, items)
        }
    }

    private fun specs(tab: Tab, installed: Set<String>): List<RowSpec> = when (tab) {
        Tab.Home -> buildList {
            add(RowSpec("home/latest-movies", "أحدث الأفلام", CardStyle.Landscape) { tmdb.newestMovies() })
            add(RowSpec("home/latest-tv", "أحدث المسلسلات", CardStyle.Landscape) { tmdb.newestEpisodes() })
            for (p in Providers.all.take(2)) {
                if (p.packageName !in installed) continue
                add(RowSpec("home/p${p.tmdbId}", "الأفضل على ${p.name}", CardStyle.Landscape) { providerTitles(p.tmdbId, it) })
            }
            add(RowSpec("home/movies", "أفلام شائعة", CardStyle.Poster) { tmdb.popular("movie") })
            add(RowSpec("home/tv", "مسلسلات شائعة", CardStyle.Poster) { tmdb.popular("tv") })
        }
        Tab.Movies -> listOf(
            RowSpec("movie/trending", "أفلام رائجة", CardStyle.Landscape) { tmdb.trending("movie", "day") },
            RowSpec("movie/top", "الأعلى تقييماً", CardStyle.Poster) { tmdb.topRated("movie") },
        ) + genreRows("movie", MOVIE_GENRES)
        Tab.Shows -> listOf(
            RowSpec("tv/trending", "مسلسلات رائجة", CardStyle.Landscape) { tmdb.trending("tv", "day") },
            RowSpec("tv/top", "الأعلى تقييماً", CardStyle.Poster) { tmdb.topRated("tv") },
        ) + genreRows("tv", TV_GENRES)
        Tab.Apps -> emptyList()
    }

    private fun genreRows(type: String, genres: List<Genre>) = genres.map { g ->
        RowSpec("$type/g${g.id}", g.name, g.style) { tmdb.byGenre(type, g.id) }
    }

    /** A provider's catalogue in [region], falling back to the US one when the region has none. */
    private suspend fun providerTitles(providerId: Int, region: String): List<TmdbItem> {
        val local = providerMix(providerId, region)
        return if (local.isNotEmpty() || region == FALLBACK_REGION) local else providerMix(providerId, FALLBACK_REGION)
    }

    private suspend fun providerMix(id: Int, region: String): List<TmdbItem> = coroutineScope {
        val tv = async { tmdb.onProvider(id, "tv", region) }
        val movie = async { tmdb.onProvider(id, "movie", region) }
        ShelfFormat.interleave(tv.await(), movie.await())
    }

    // ── Formatting ───────────────────────────────────────────────────────────────────────────

    private fun title(item: TmdbItem) = Title(
        id = key(item),
        tmdbId = item.id,
        mediaType = item.mediaType,
        name = item.title,
        meta = if (item.fresh) freshMeta(item) else ShelfFormat.meta(item),
        overview = item.overview,
        heroUrl = TmdbClient.image(item.backdropPath, HERO_SIZE),
        cardUrl = TmdbClient.image(item.backdropPath, CARD_SIZE),
        posterUrl = TmdbClient.image(item.posterPath, POSTER_SIZE),
    )

    private fun key(item: TmdbItem) = "${item.mediaType}/${item.id}"

    /** "الموسم ٢٣ · الحلقة ٢٥ · اليوم" for a new episode, "نزل أمس" for a new film. */
    private fun freshMeta(item: TmdbItem): String {
        val days = item.releaseDate?.let { runCatching { java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(it), java.time.LocalDate.now()) }.getOrNull() }
        val when_ = when {
            days == null -> null
            days <= 0 -> "اليوم"
            days == 1L -> "أمس"
            days == 2L -> "قبل يومين"
            else -> "قبل $days أيام"
        }
        return if (item.episode != null) listOfNotNull(item.episode, when_).joinToString(" · ")
        else listOfNotNull(if (when_ != null) "نزل $when_" else null, ShelfFormat.meta(item).takeIf { it.isNotBlank() }).joinToString(" · ")
    }

    private fun List<TmdbItem>.usable(max: Int) =
        filter { it.backdropPath != null && (it.mediaType == "movie" || it.mediaType == "tv") }
            .distinctBy(::key)
            .take(max)

    private suspend fun <T> safe(block: suspend () -> List<T>): List<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Content load failed", e)
        emptyList()
    }

    private data class Genre(val id: Int, val name: String, val style: CardStyle = CardStyle.Landscape)

    private companion object {
        const val TAG = "HomeContent"
        const val FALLBACK_REGION = "US"
        const val HERO_COUNT = 8
        const val PICKS_PER_PROVIDER = 2
        const val ROW_MAX = 20
        const val ROW_MIN = 4
        const val HERO_SIZE = "w1280"
        const val CARD_SIZE = "w780"
        const val POSTER_SIZE = "w342"
        const val LOGO_SIZE = "w500"

        val MOVIE_GENRES = listOf(
            Genre(28, "أكشن"),
            Genre(35, "كوميديا"),
            Genre(878, "خيال علمي", CardStyle.Poster),
            Genre(18, "دراما"),
            Genre(16, "رسوم متحركة"),
        )
        val TV_GENRES = listOf(
            Genre(18, "دراما"),
            Genre(35, "كوميديا"),
            Genre(80, "جريمة", CardStyle.Poster),
            Genre(10765, "خيال علمي وفانتازيا"),
            Genre(16, "رسوم متحركة"),
        )
    }
}
