package com.moath.tvhome.sports

import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime

/** Reads ESPN's public scoreboard JSON (`site.api.espn.com/apis/site/v2/sports/soccer/<slug>/scoreboard`). */
object EspnParser {
    fun parseScoreboard(root: JSONObject, slug: String): List<Match> {
        val events = root.optJSONArray("events") ?: return emptyList()
        val leagueName = root.optJSONArray("leagues")?.optJSONObject(0)?.optString("name").orEmpty()
        return (0 until events.length()).mapNotNull { parseEvent(events.getJSONObject(it), slug, leagueName) }
    }

    fun parseEvent(e: JSONObject, slug: String, leagueName: String): Match? {
        val comp = e.optJSONArray("competitions")?.optJSONObject(0) ?: return null
        val competitors = comp.optJSONArray("competitors") ?: return null
        var home: JSONObject? = null
        var away: JSONObject? = null
        for (i in 0 until competitors.length()) {
            val c = competitors.getJSONObject(i)
            if (c.optString("homeAway") == "home") home = c else if (c.optString("homeAway") == "away") away = c
        }
        home ?: return null
        away ?: return null
        val kickoff = runCatching { OffsetDateTime.parse(e.optString("date")).toInstant().toEpochMilli() }.getOrNull() ?: return null
        val status = comp.optJSONObject("status")
        val type = status?.optJSONObject("type")
        val state = when {
            type?.optString("name").orEmpty().let { it.contains("POSTPONED") || it.contains("CANCELED") || it.contains("SUSPENDED") || it.contains("ABANDONED") } -> MatchState.Other
            type?.optString("state") == "in" -> MatchState.Live
            type?.optString("state") == "post" -> MatchState.Finished
            type?.optString("state") == "pre" -> MatchState.Upcoming
            else -> MatchState.Other
        }
        val statusText = when (state) {
            MatchState.Live -> liveText(status)
            MatchState.Finished -> "FT"
            else -> ""
        }
        val venue = comp.optJSONObject("venue")
        return Match(
            id = e.optString("id"),
            league = slug,
            leagueName = leagueName,
            kickoffMs = kickoff,
            state = state,
            statusText = statusText,
            home = club(home),
            away = club(away),
            homeScore = if (state == MatchState.Upcoming || state == MatchState.Other) null else score(home),
            awayScore = if (state == MatchState.Upcoming || state == MatchState.Other) null else score(away),
            venue = venue?.optString("fullName")?.takeIf { it.isNotBlank() },
            city = venue?.optJSONObject("address")?.optString("city")?.takeIf { it.isNotBlank() },
            broadcasts = broadcasts(comp.optJSONArray("broadcasts")),
        )
    }

    /** "67'" while playing; "HT"/"ET" from the status detail when the clock is stopped. */
    private fun liveText(status: JSONObject?): String {
        val detail = status?.optJSONObject("type")?.optString("shortDetail").orEmpty()
        if (detail.equals("HT", true) || detail.equals("Halftime", true)) return "HT"
        val clock = status?.optString("displayClock").orEmpty()
        return clock.ifBlank { detail }
    }

    private fun club(c: JSONObject): Club {
        val t = c.optJSONObject("team") ?: JSONObject()
        return Club(
            id = t.optString("id"),
            name = t.optString("displayName"),
            abbr = t.optString("abbreviation"),
            logo = t.optString("logo").takeIf { it.startsWith("http") },
        )
    }

    private fun score(c: JSONObject): Int? = c.opt("score").let { s ->
        when (s) {
            is Number -> s.toInt()
            is String -> s.toIntOrNull()
            is JSONObject -> s.optString("displayValue").toIntOrNull()
            else -> null
        }
    }

    private fun broadcasts(arr: JSONArray?): List<String> {
        arr ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (i in 0 until arr.length()) {
            val names = arr.optJSONObject(i)?.optJSONArray("names") ?: continue
            for (j in 0 until names.length()) names.optString(j).takeIf { it.isNotBlank() }?.let(out::add)
        }
        return out.toList()
    }
}
