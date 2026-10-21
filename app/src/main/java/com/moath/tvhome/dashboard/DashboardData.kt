package com.moath.tvhome.dashboard

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class DayForecast(val weekday: Int, val code: Int, val max: Int, val min: Int)

data class CityWeather(
    val name: String,
    val currentTemp: Int,
    val currentCode: Int,
    val isDay: Boolean,
    val days: List<DayForecast>,
)

/**
 * Public Instagram profile numbers. Logged-out Instagram only exposes the rounded follower figure
 * ("14K"), so [followersExact] is false unless the count is small enough to be shown in full.
 */
data class InstagramStats(
    val handle: String,
    val followers: Long,
    val followersExact: Boolean,
    val posts: Long?,
    /** Change versus the snapshot closest to 24 h ago; null until there is history or no change. */
    val change24h: Long?,
)

private data class City(val name: String, val lat: Double, val lon: Double)

/**
 * Live data for the dashboard: Open-Meteo forecasts and the public Instagram profile figures. Both keyless.
 * The last good response is cached so a short outage keeps showing real (recent) numbers; anything
 * older than [MAX_AGE_MS] is dropped rather than shown as current.
 */
class DashboardData(context: Context) {
    private val healthContext = context.applicationContext
    private val prefs = context.getSharedPreferences("dashboard", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun weather(): List<CityWeather> = withContext(Dispatchers.IO) {
        CITIES.mapNotNull { city ->
            val key = "wx_${city.name}"
            val fresh = fetch(
                "https://api.open-meteo.com/v1/forecast?latitude=${city.lat}&longitude=${city.lon}" +
                    "&current=temperature_2m,weather_code,is_day" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
                    "&timezone=Asia%2FRiyadh&forecast_days=7",
            )
            if (city == CITIES.first()) {
                if (fresh != null) com.moath.tvhome.health.Health.recover(healthContext, "feed.weather")
                else com.moath.tvhome.health.Health.report(healthContext, "feed.weather", "Open-Meteo لا يرد")
            }
            val body = fresh?.also { save(key, it) } ?: cached(key)
            body?.let { runCatching { parseWeather(city.name, JSONObject(it)) }.getOrNull() }
        }
    }

    suspend fun instagram(handle: String): InstagramStats? = withContext(Dispatchers.IO) {
        val html = fetch("https://www.instagram.com/$handle/", BROWSER_UA)
        val parsed = html?.let(::parseOgDescription)?.also { save(KEY_IG, "${it.first}|${it.second}|${it.third ?: ""}") }
            ?: cached(KEY_IG)?.split('|')?.let { p -> Triple(p[0].toLong(), p[1].toBoolean(), p.getOrNull(2)?.toLongOrNull()) }
            ?: return@withContext null
        val (followers, exact, posts) = parsed
        InstagramStats(handle, followers, exact, posts, recordAndDiff(followers))
    }

    /** og:description looks like "14K Followers, 331 Following, 81 Posts - …" or "1,234 Followers, …". */
    private fun parseOgDescription(html: String): Triple<Long, Boolean, Long?>? {
        val content = Regex("""property="og:description" content="([^"]+)"""").find(html)?.groupValues?.get(1) ?: return null
        val followers = Regex("""([\d.,]+)([KkMm]?) Followers""").find(content) ?: return null
        val posts = Regex("""([\d.,]+)([KkMm]?) Posts""").find(content)?.let { scaled(it.groupValues[1], it.groupValues[2]) }
        val suffix = followers.groupValues[2]
        return Triple(scaled(followers.groupValues[1], suffix), suffix.isEmpty(), posts)
    }

    private fun scaled(number: String, suffix: String): Long {
        val base = number.replace(",", "").toDouble()
        val mult = when (suffix.lowercase()) { "k" -> 1_000.0; "m" -> 1_000_000.0; else -> 1.0 }
        return Math.round(base * mult)
    }

    /** Keeps one snapshot per hour for two days and returns the change against ~24 h ago. */
    private fun recordAndDiff(followers: Long): Long? {
        val now = System.currentTimeMillis()
        val hour = now / HOUR_MS
        val history = prefs.getString(KEY_IG_HISTORY, "").orEmpty()
            .split(';').mapNotNull { e -> e.split(':').takeIf { it.size == 2 }?.let { it[0].toLong() to it[1].toLong() } }
            .filter { it.first > hour - 48 }
            .toMutableList()
        if (history.none { it.first == hour }) history += hour to followers
        prefs.edit().putString(KEY_IG_HISTORY, history.joinToString(";") { "${it.first}:${it.second}" }).apply()
        val past = history.filter { it.first <= hour - 20 }.maxByOrNull { it.first } ?: return null
        return (followers - past.second).takeIf { it != 0L }
    }

    private fun parseWeather(name: String, o: JSONObject): CityWeather {
        val cur = o.getJSONObject("current")
        val daily = o.getJSONObject("daily")
        val dates = daily.getJSONArray("time")
        val codes = daily.getJSONArray("weather_code")
        val max = daily.getJSONArray("temperature_2m_max")
        val min = daily.getJSONArray("temperature_2m_min")
        val days = (0 until dates.length()).map { i ->
            val (y, m, d) = dates.getString(i).split("-").map(String::toInt)
            val cal = java.util.GregorianCalendar(y, m - 1, d)
            DayForecast(
                weekday = cal[java.util.Calendar.DAY_OF_WEEK] - 1,
                code = codes.getInt(i),
                max = Math.round(max.getDouble(i)).toInt(),
                min = Math.round(min.getDouble(i)).toInt(),
            )
        }
        return CityWeather(
            name = name,
            currentTemp = Math.round(cur.getDouble("temperature_2m")).toInt(),
            currentCode = cur.getInt("weather_code"),
            isDay = cur.optInt("is_day", 1) == 1,
            days = days,
        )
    }

    private fun fetch(url: String, userAgent: String? = null): String? = try {
        val req = Request.Builder().url(url).header("Accept", if (userAgent == null) "application/json" else "text/html")
            .apply { if (userAgent != null) header("User-Agent", userAgent) }
            .build()
        http.newCall(req).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null.also { Log.w(TAG, "HTTP ${r.code} for $url") }
        }
    } catch (e: IOException) {
        Log.w(TAG, "Fetch failed: $url", e)
        null
    }

    private fun save(key: String, body: String) {
        prefs.edit().putString(key, body).putLong("${key}_at", System.currentTimeMillis()).apply()
    }

    private fun cached(key: String): String? {
        val at = prefs.getLong("${key}_at", 0L)
        return if (System.currentTimeMillis() - at < MAX_AGE_MS) prefs.getString(key, null) else null
    }

    private companion object {
        const val TAG = "Dashboard"
        const val KEY_IG = "ig"
        const val KEY_IG_HISTORY = "ig_history"
        const val HOUR_MS = 60L * 60 * 1000
        const val BROWSER_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15"
        const val MAX_AGE_MS = 12L * 60 * 60 * 1000
        val CITIES = listOf(
            City("الخبر", 26.2794, 50.2083),
            City("الدمام", 26.4207, 50.0888),
        )
    }
}
