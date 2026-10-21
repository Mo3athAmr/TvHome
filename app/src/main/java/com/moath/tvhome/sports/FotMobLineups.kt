package com.moath.tvhome.sports

import org.json.JSONObject

data class PitchPlayer(val number: String, val name: String, val x: Float, val y: Float)

/** A starting eleven laid out on the pitch: GK near y = 0.1, forwards near 0.87, x across (FotMob's vertical layout). */
data class Lineup(val formation: String?, val players: List<PitchPlayer>, val predicted: Boolean)

data class LineupInfo(val referee: String?, val home: Lineup?, val away: Lineup?)

/** Reads FotMob's match details: the referee and where each starter stands. Used only on the match page. */
object FotMobParser {
    fun parseDetails(root: JSONObject): LineupInfo {
        val content = root.optJSONObject("content")
        val referee = content?.optJSONObject("matchFacts")?.optJSONObject("infoBox")?.optJSONObject("Referee")?.optString("text")?.takeIf { it.isNotBlank() }
        val lineup = content?.optJSONObject("lineup")
        val predicted = lineup?.optString("lineupType").orEmpty().let { it.contains("pred", true) || it.contains("prob", true) }
        return LineupInfo(referee, side(lineup?.optJSONObject("homeTeam"), predicted), side(lineup?.optJSONObject("awayTeam"), predicted))
    }

    private fun side(team: JSONObject?, predicted: Boolean): Lineup? {
        team ?: return null
        val starters = team.optJSONArray("starters") ?: return null
        val players = (0 until starters.length()).mapNotNull { i ->
            val p = starters.getJSONObject(i)
            val v = p.optJSONObject("verticalLayout") ?: return@mapNotNull null
            PitchPlayer(
                number = p.optString("shirtNumber"),
                name = p.optString("lastName").ifBlank { p.optString("name").substringAfterLast(' ') },
                x = v.optDouble("x").toFloat(),
                y = v.optDouble("y").toFloat(),
            )
        }
        if (players.size < 11) return null
        return Lineup(team.optString("formation").takeIf { it.isNotBlank() }, players, predicted)
    }

    /** Same club, spelled alike: accent- and punctuation-free, equal or one containing the other. */
    fun sameClub(a: String, b: String): Boolean {
        fun n(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase().replace(Regex("[^a-z0-9]"), "")
        val x = n(a)
        val y = n(b)
        return x.isNotEmpty() && y.isNotEmpty() && (x == y || x.contains(y) || y.contains(x))
    }

    /** The FotMob id of the match between [home] and [away] in `data/matches?date=` JSON, or null. */
    fun findMatchId(list: JSONObject, home: String, away: String): String? {
        val leagues = list.optJSONArray("leagues") ?: return null
        for (i in 0 until leagues.length()) {
            val matches = leagues.getJSONObject(i).optJSONArray("matches") ?: continue
            for (j in 0 until matches.length()) {
                val m = matches.getJSONObject(j)
                if (sameClub(m.optJSONObject("home")?.optString("name").orEmpty(), home) &&
                    sameClub(m.optJSONObject("away")?.optString("name").orEmpty(), away)
                ) return m.opt("id")?.toString()
            }
        }
        return null
    }
}
