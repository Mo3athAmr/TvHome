package com.moath.tvhome.sports

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
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/**
 * Fixtures and live scores from ESPN's public scoreboard feed (no key). The schedule is read every
 * half hour; during a match only the competitions that have one are polled, every ~45 s. The last
 * state is cached so Home has something to show at boot.
 */
class SportsRepository private constructor(context: Context) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    // Home and the system process (assistant, notices) each keep their own cache file: two processes
    // must never write one SharedPreferences file.
    private val prefs = context.getSharedPreferences(
        if (android.app.Application.getProcessName().endsWith(":system")) "sports_system" else "sports",
        Context.MODE_PRIVATE,
    )
    @Volatile private var matches: Map<String, Match> = loadCache()
    /** Match id → channels (from [ChannelGuide]); kept apart from [matches] so a score refresh never loses them. */
    @Volatile private var tv: Map<String, List<String>> = loadTv()
    private val fotMobIds = HashMap<String, String>()

    fun all(): List<Match> = matches.values.map(::withTv)

    fun find(id: String): Match? = matches[id]?.let(::withTv)

    private fun withTv(m: Match): Match = tv[m.id]?.let { m.copy(tv = it) } ?: m

    /** Reads the next week of every followed competition (and yesterday, for late finishes). */
    suspend fun refreshSchedule(leagues: Collection<String>): Boolean = withContext(Dispatchers.IO) {
        if (leagues.isEmpty()) return@withContext false
        val today = LocalDate.now(ZoneOffset.UTC)
        val gate = Semaphore(6)
        val jobs = coroutineScope {
            leagues.flatMap { slug -> (-1L..7L).map { d -> slug to today.plusDays(d) } }
                .map { (slug, day) -> async { gate.withPermit { fetch(slug, day) } } }
                .awaitAll()
        }
        val ok = jobs.any { it != null }
        if (ok) merge(jobs.filterNotNull().flatten())
        ok
    }

    /** Re-reads today's (and yesterday's) scoreboard for the competitions that have a match on. */
    suspend fun refreshLive(leagues: Collection<String>): Boolean = withContext(Dispatchers.IO) {
        if (leagues.isEmpty()) return@withContext false
        val today = LocalDate.now(ZoneOffset.UTC)
        val gate = Semaphore(4)
        val jobs = coroutineScope {
            leagues.flatMap { slug -> listOf(slug to today, slug to today.minusDays(1)) }
                .map { (slug, day) -> async { gate.withPermit { fetch(slug, day) } } }
                .awaitAll()
        }
        val ok = jobs.any { it != null }
        if (ok) merge(jobs.filterNotNull().flatten())
        ok
    }

    /** Goals and cards (ESPN) plus the pitch lineups and referee (FotMob) for the match page. */
    suspend fun details(m: Match): MatchDetails? = withContext(Dispatchers.IO) {
        val espn = getJson("$BASE/${m.league}/summary?event=${m.id}")?.let { json ->
            runCatching { EspnSummaryParser.parse(json, m.home, m.away) }
                .onFailure { Log.w(TAG, "Bad summary for ${m.id}", it) }
                .getOrNull()
        }
        val fot = lineups(m)
        if (espn == null && fot == null) return@withContext null
        (espn ?: MatchDetails(null, null, null, emptyList(), emptyList(), emptyList(), null))
            .copy(referee = fot?.referee ?: espn?.referee, lineups = fot)
    }

    private fun lineups(m: Match): LineupInfo? {
        val id = fotMobIds[m.id] ?: run {
            val day = java.time.Instant.ofEpochMilli(m.kickoffMs).atZone(java.time.ZoneId.of("Asia/Riyadh")).toLocalDate().toString().replace("-", "")
            val list = getJson("$FOTMOB/matches?date=$day&timezone=Asia/Riyadh&ccode3=SAU", FOTMOB_UA) ?: return null
            FotMobParser.findMatchId(list, m.home.name, m.away.name)?.also { fotMobIds[m.id] = it } ?: return null
        }
        val json = getJson("$FOTMOB/matchDetails?matchId=$id", FOTMOB_UA) ?: return null
        return runCatching { FotMobParser.parseDetails(json) }.onFailure { Log.w(TAG, "Bad FotMob details $id", it) }.getOrNull()
    }

    /** Reads the channel guide for the next week and attaches the channels to the cached matches. */
    suspend fun refreshChannels(): Boolean = withContext(Dispatchers.IO) {
        val zone = java.time.ZoneId.of("Asia/Riyadh")
        val today = LocalDate.now(zone)
        val pages = (0L..7L).map { today.plusDays(it) }.mapNotNull { day ->
            html("$FILGOAL${day}")?.let { ChannelGuide.parse(it, day) }
        }
        if (pages.isEmpty()) return@withContext false
        val entries = pages.flatten()
        val next = HashMap<String, List<String>>()
        for (m in matches.values) {
            val date = java.time.Instant.ofEpochMilli(m.kickoffMs).atZone(zone).toLocalDate()
            val ch = ChannelGuide.channelsFor(m, entries, date)
            if (ch.isNotEmpty()) next[m.id] = ch else tv[m.id]?.let { if (date < today) next[m.id] = it } // keep finished matches' channel
        }
        tv = next
        prefs.edit().putString(K_TV, JSONObject(next as Map<*, *>).toString()).apply()
        true
    }

    // ── Network ──────────────────────────────────────────────────────────────────────────────

    private fun fetch(slug: String, day: LocalDate): List<Match>? {
        val json = getJson("$BASE/$slug/scoreboard?dates=${day.toString().replace("-", "")}") ?: return null
        return runCatching { EspnParser.parseScoreboard(json, slug) }
            .onFailure { Log.w(TAG, "Bad scoreboard for $slug", it) }
            .getOrNull()
    }

    private fun getJson(url: String, userAgent: String? = null): JSONObject? = try {
        http.newCall(Request.Builder().url(url).header("Accept", "application/json").apply { userAgent?.let { header("User-Agent", it) } }.build()).execute().use { res ->
            if (res.isSuccessful) res.body?.string()?.let(::JSONObject) else {
                Log.w(TAG, "HTTP ${res.code} for $url")
                null
            }
        }
    } catch (e: IOException) {
        Log.w(TAG, "Request failed: $url", e)
        null
    } catch (e: org.json.JSONException) {
        Log.w(TAG, "Bad JSON: $url", e)
        null
    }

    private fun html(url: String): String? = try {
        http.newCall(Request.Builder().url(url).header("User-Agent", FOTMOB_UA).header("Accept-Language", "ar").build()).execute().use { res ->
            if (res.isSuccessful) res.body?.string() else null
        }
    } catch (e: IOException) {
        Log.w(TAG, "Page failed: $url", e)
        null
    }

    // ── Cache ────────────────────────────────────────────────────────────────────────────────

    @Synchronized
    private fun merge(fresh: List<Match>) {
        val now = System.currentTimeMillis()
        val next = LinkedHashMap(matches)
        for (m in fresh) next[m.id] = m
        next.values.removeAll { now - it.kickoffMs > KEEP_MS }
        matches = next
        saveCache(next.values)
    }

    private fun loadCache(): Map<String, Match> = runCatching {
        val arr = JSONArray(prefs.getString(K_CACHE, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Match(
                id = o.getString("id"), league = o.getString("lg"), leagueName = o.optString("ln"),
                kickoffMs = o.getLong("k"), state = MatchState.valueOf(o.getString("s")), statusText = o.optString("t"),
                home = club(o.getJSONObject("h")), away = club(o.getJSONObject("a")),
                homeScore = if (o.has("hs")) o.getInt("hs") else null, awayScore = if (o.has("as")) o.getInt("as") else null,
                venue = o.optString("v").takeIf { it.isNotBlank() }, city = o.optString("c").takeIf { it.isNotBlank() },
                broadcasts = o.optJSONArray("b")?.let { b -> (0 until b.length()).map { b.getString(it) } }.orEmpty(),
            )
        }.associateBy { it.id }
    }.getOrDefault(emptyMap())

    private fun loadTv(): Map<String, List<String>> = runCatching {
        val o = JSONObject(prefs.getString(K_TV, "{}"))
        o.keys().asSequence().associateWith { k -> o.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) } } }
    }.getOrDefault(emptyMap())

    private fun club(o: JSONObject) = Club(o.getString("i"), o.optString("n"), o.optString("a"), o.optString("l").takeIf { it.isNotBlank() })

    private fun saveCache(list: Collection<Match>) {
        val arr = JSONArray()
        list.forEach { m ->
            arr.put(
                JSONObject().put("id", m.id).put("lg", m.league).put("ln", m.leagueName).put("k", m.kickoffMs)
                    .put("s", m.state.name).put("t", m.statusText)
                    .put("h", clubJson(m.home)).put("a", clubJson(m.away))
                    .also { o ->
                        m.homeScore?.let { o.put("hs", it) }; m.awayScore?.let { o.put("as", it) }
                        m.venue?.let { o.put("v", it) }; m.city?.let { o.put("c", it) }
                        o.put("b", JSONArray(m.broadcasts))
                    },
            )
        }
        prefs.edit().putString(K_CACHE, arr.toString()).apply()
    }

    private fun clubJson(c: Club) = JSONObject().put("i", c.id).put("n", c.name).put("a", c.abbr).put("l", c.logo ?: "")

    companion object {
        private const val TAG = "Sports"
        private const val BASE = "https://site.api.espn.com/apis/site/v2/sports/soccer"
        private const val K_CACHE = "cache"
        private const val K_TV = "tv"
        private const val FOTMOB = "https://www.fotmob.com/api/data"
        private const val FILGOAL = "https://www.filgoal.com/matches/?date="
        private const val FOTMOB_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15"
        private const val KEEP_MS = 3L * 24 * 60 * 60 * 1000

        @Volatile private var instance: SportsRepository? = null

        fun get(context: Context): SportsRepository =
            instance ?: synchronized(this) {
                instance ?: SportsRepository(context.applicationContext).also { instance = it }
            }
    }
}
