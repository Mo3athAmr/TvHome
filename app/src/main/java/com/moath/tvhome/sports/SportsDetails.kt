package com.moath.tvhome.sports

import org.json.JSONObject

data class PlayerLine(val jersey: String, val name: String, val position: String)

enum class EventKind { Goal, Yellow, Red, Sub, Other }

data class MatchEvent(val minute: String, val kind: EventKind, val clubId: String?, val player: String)

/** The extras of the match page, from ESPN's `summary` feed. The referee is not in any free feed. */
data class MatchDetails(
    val attendance: Int?,
    val homeFormation: String?,
    val awayFormation: String?,
    val homeStarters: List<PlayerLine>,
    val awayStarters: List<PlayerLine>,
    val events: List<MatchEvent>,
    val referee: String?,
    /** Pitch lineups and the referee from FotMob; null when it has no such match. */
    val lineups: LineupInfo? = null,
)

object EspnSummaryParser {
    fun parse(root: JSONObject, home: Club, away: Club): MatchDetails {
        val rosters = root.optJSONArray("rosters")
        var homeSide: JSONObject? = null
        var awaySide: JSONObject? = null
        if (rosters != null) for (i in 0 until rosters.length()) {
            val r = rosters.getJSONObject(i)
            val id = r.optJSONObject("team")?.optString("id")
            when {
                r.optString("homeAway") == "home" || id == home.id -> homeSide = r
                r.optString("homeAway") == "away" || id == away.id -> awaySide = r
            }
        }
        val events = root.optJSONArray("keyEvents")
        val parsedEvents = buildList {
            if (events != null) for (i in 0 until events.length()) {
                val e = events.getJSONObject(i)
                val type = e.optJSONObject("type")?.optString("type").orEmpty()
                val kind = when {
                    type.contains("goal") || type.startsWith("penalty---scored") || type == "own-goal" -> EventKind.Goal
                    type == "yellow-card" -> EventKind.Yellow
                    type == "red-card" -> EventKind.Red
                    type == "substitution" -> EventKind.Sub
                    else -> EventKind.Other
                }
                if (kind == EventKind.Other || kind == EventKind.Sub) continue // the page lists goals and cards
                add(
                    MatchEvent(
                        minute = e.optJSONObject("clock")?.optString("displayValue").orEmpty(),
                        kind = kind,
                        clubId = e.optJSONObject("team")?.optString("id"),
                        player = playerOf(e.optString("text"), kind),
                    ),
                )
            }
        }
        return MatchDetails(
            attendance = root.optJSONObject("gameInfo")?.optInt("attendance")?.takeIf { it > 0 },
            homeFormation = homeSide?.optString("formation")?.takeIf { it.isNotBlank() },
            awayFormation = awaySide?.optString("formation")?.takeIf { it.isNotBlank() },
            homeStarters = starters(homeSide),
            awayStarters = starters(awaySide),
            events = parsedEvents,
            referee = root.optJSONObject("gameInfo")?.optJSONArray("officials")?.optJSONObject(0)?.optString("fullName")?.takeIf { it.isNotBlank() },
        )
    }

    /** "Goal! A 1, B 0. Arda Güler (A) left footed shot…" / "Marc Cucurella (A) is shown the yellow card." → the player. */
    internal fun playerOf(text: String, kind: EventKind): String {
        val t = text.trim()
        val name = when (kind) {
            EventKind.Goal -> Regex("""\.\s+([^.()]+?)\s+\(""").find(t)?.groupValues?.get(1)
            else -> Regex("""^([^()]+?)\s+\(""").find(t)?.groupValues?.get(1)
        }
        return name?.trim().orEmpty()
    }

    private fun starters(side: JSONObject?): List<PlayerLine> {
        val roster = side?.optJSONArray("roster") ?: return emptyList()
        return (0 until roster.length()).mapNotNull { i ->
            val p = roster.getJSONObject(i)
            if (!p.optBoolean("starter")) return@mapNotNull null
            PlayerLine(
                jersey = p.optString("jersey"),
                name = p.optJSONObject("athlete")?.optString("displayName").orEmpty(),
                position = p.optJSONObject("position")?.optString("abbreviation").orEmpty(),
            )
        }
    }
}
