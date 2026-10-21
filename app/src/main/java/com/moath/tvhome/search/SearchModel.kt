package com.moath.tvhome.search

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.moath.tvhome.AppEntry
import com.moath.tvhome.AppRepository
import com.moath.tvhome.tmdb.TmdbClient
import com.moath.tvhome.tmdb.TmdbItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SectionKind { Landscape, Apps, Posters }

sealed interface SearchHit {
    val key: String
    val title: String
}

data class AppHit(val app: AppEntry) : SearchHit {
    override val key get() = "app:${app.key}"
    override val title get() = app.label
}

data class MediaHit(val item: TmdbItem) : SearchHit {
    override val key get() = "${item.mediaType}:${item.id}"
    override val title get() = item.title
}

data class ResultSection(val id: String, val title: String, val kind: SectionKind, val hits: List<SearchHit>)

sealed interface ResultsState {
    /** Nothing loaded yet — draw nothing rather than a spinner, as tvOS does. */
    data object Idle : ResultsState
    data class Ready(val query: String, val sections: List<ResultSection>) : ResultsState
    data class Message(val query: String, val text: String) : ResultsState
}

/**
 * Query + results for the Search app. Typing is debounced; [run] uses collectLatest so a newer
 * query cancels the in-flight one and stale results can never land on screen.
 */
class SearchModel(private val context: Context) {
    private val tmdb = TmdbClient.get(context)

    var query by mutableStateOf("")
        private set
    var results by mutableStateOf<ResultsState>(ResultsState.Idle)
        private set
    private var apps by mutableStateOf<List<AppEntry>>(emptyList())
    private var trendingCache: List<TmdbItem>? = null

    /** Titles the assistant picked for the user: shown instead of the trending row while nothing is typed. */
    var picks: ResultSection? = null

    fun type(text: String) {
        if (query.length + text.length <= MAX_QUERY) query += text
    }

    fun deleteLast() {
        query = query.dropLast(1)
    }

    /** Long-press on a letter: the lowercase letter typed on key-down becomes uppercase. */
    fun uppercaseLast() {
        val last = query.lastOrNull() ?: return
        query = query.dropLast(1) + last.uppercaseChar()
    }

    fun replace(text: String) {
        query = text.take(MAX_QUERY)
    }

    suspend fun run() = coroutineScope {
        launch { apps = loadApps() }
        snapshotFlow { query.trim() to apps }
            .distinctUntilChanged()
            .collectLatest { (q, installed) ->
                if (q.isNotEmpty()) delay(DEBOUNCE_MS)
                results = if (q.isEmpty()) picks?.let { ResultsState.Ready("", listOf(it)) } ?: popular() else search(q, installed)
            }
    }

    private suspend fun loadApps(): List<AppEntry> = withContext(Dispatchers.Default) {
        try {
            AppRepository(context).load().filter { it.key != AppRepository.SEARCH_KEY }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not load installed apps", e)
            emptyList()
        }
    }

    private suspend fun popular(): ResultsState {
        val items = trendingCache ?: safely { tmdb.trending() }.also { if (it.isNotEmpty()) trendingCache = it }
        if (items.isEmpty()) return ResultsState.Message("", unavailableText() ?: "")
        return ResultsState.Ready("", listOf(ResultSection("popular", "أفلام ومسلسلات شائعة", SectionKind.Posters, items.map(::MediaHit))))
    }

    private suspend fun search(q: String, installed: List<AppEntry>): ResultsState {
        val appHits = matchApps(q, installed)
        val media = safely { tmdb.search(q) }
        if (appHits.isEmpty() && media.isEmpty()) {
            return ResultsState.Message(q, unavailableText() ?: "لا توجد نتائج")
        }
        val sections = ArrayList<ResultSection>()
        val top = buildList {
            appHits.firstOrNull()?.takeIf { it.app.label.startsWith(q, ignoreCase = true) }?.let(::add)
            addAll(media.take(TOP_COUNT - size).map(::MediaHit))
        }
        if (top.isNotEmpty()) sections += ResultSection("top", "أفضل النتائج", SectionKind.Landscape, top)
        if (appHits.isNotEmpty()) sections += ResultSection("apps", "التطبيقات", SectionKind.Apps, appHits)
        val movies = media.filter { it.mediaType == "movie" }
        if (movies.isNotEmpty()) sections += ResultSection("movies", "أفلام", SectionKind.Posters, movies.map(::MediaHit))
        val shows = media.filter { it.mediaType == "tv" }
        if (shows.isNotEmpty()) sections += ResultSection("tv", "مسلسلات", SectionKind.Posters, shows.map(::MediaHit))
        return ResultsState.Ready(q, sections)
    }

    private fun matchApps(q: String, installed: List<AppEntry>): List<AppHit> =
        installed.mapNotNull { app ->
            val label = app.label.lowercase()
            val needle = q.lowercase()
            val score = when {
                label.startsWith(needle) -> 0
                label.split(' ', '-', '_', '.').any { it.startsWith(needle) } -> 1
                needle.length >= 2 && needle in label -> 2
                else -> return@mapNotNull null
            }
            score to app
        }.sortedWith(compareBy({ it.first }, { it.second.label.lowercase() })).map { AppHit(it.second) }

    /** TmdbClient already swallows I/O errors; this guards against anything else it might throw. */
    private suspend fun safely(block: suspend () -> List<TmdbItem>): List<TmdbItem> =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            Log.w(TAG, "TMDB lookup failed", e)
            emptyList()
        }

    private fun unavailableText(): String? = when {
        !tmdb.isConfigured -> "نتائج الأفلام والمسلسلات غير متاحة."
        !isOnline() -> "الجهاز غير متصل بالإنترنت."
        else -> null
    }

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private companion object {
        const val TAG = "TvSearch"
        const val DEBOUNCE_MS = 300L
        const val MAX_QUERY = 64
        const val TOP_COUNT = 5
    }
}
