package com.moath.tvhome.sports

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsTest {
    private fun team(id: String, abbr: String) = Club(id, abbr, abbr, null)
    private fun match(
        id: String, league: String = "uefa.champions", home: Club, away: Club, state: MatchState = MatchState.Upcoming,
        kickoff: Long = NOW + 3_600_000,
    ) = Match(id, league, league, kickoff, state, "", home, away, null, null, null, null, emptyList())

    private val rma = team("86", "RMA")
    private val bar = team("83", "BAR")
    private val liv = team("364", "LIV")
    private val nobody = team("999", "XXX")
    private val nobody2 = team("998", "YYY")
    private val cfg = SportsConfig()

    @Test fun `parses a live scoreboard event`() {
        val json = JSONObject(
            """{"leagues":[{"name":"UEFA Champions League"}],"events":[{"id":"1","date":"2026-10-20T19:00Z","competitions":[{
              "status":{"displayClock":"67'","type":{"state":"in","name":"STATUS_IN_PROGRESS","shortDetail":"67'"}},
              "venue":{"fullName":"Parc des Princes","address":{"city":"Paris"}},
              "broadcasts":[{"market":"national","names":["Paramount+"]}],
              "competitors":[
                {"homeAway":"away","score":"1","team":{"id":"83","displayName":"Barcelona","abbreviation":"BAR","logo":"https://x/83.png"}},
                {"homeAway":"home","score":"2","team":{"id":"160","displayName":"Paris Saint-Germain","abbreviation":"PSG","logo":"https://x/160.png"}}]}]}]}""",
        )
        val m = EspnParser.parseScoreboard(json, "uefa.champions").single()
        assertEquals(MatchState.Live, m.state)
        assertEquals("67'", m.statusText)
        assertEquals("PSG", m.home.abbr); assertEquals(2, m.homeScore); assertEquals(1, m.awayScore)
        assertEquals("Parc des Princes", m.venue); assertEquals(listOf("Paramount+"), m.broadcasts)
        assertEquals(1_792_522_800_000L, m.kickoffMs)
    }

    @Test fun `upcoming and postponed matches carry no score`() {
        val base = """{"events":[{"id":"2","date":"2026-10-21T18:00Z","competitions":[{"status":{"type":{"state":"pre","name":"%s"}},"competitors":[
            {"homeAway":"home","score":"0","team":{"id":"1","displayName":"A","abbreviation":"A"}},{"homeAway":"away","score":"0","team":{"id":"2","displayName":"B","abbreviation":"B"}}]}]}]}"""
        val up = EspnParser.parseScoreboard(JSONObject(base.format("STATUS_SCHEDULED")), "x").single()
        assertEquals(MatchState.Upcoming, up.state); assertEquals(null, up.homeScore)
        val pp = EspnParser.parseScoreboard(JSONObject(base.format("STATUS_POSTPONED")), "x").single()
        assertEquals(MatchState.Other, pp.state)
    }

    @Test fun `followed clubs decide the matches, real madrid and barcelona always count`() {
        assertTrue(SportsSelection.isFollowed(match("a", home = rma, away = nobody), cfg))
        assertTrue(SportsSelection.isFollowed(match("b", home = liv, away = nobody), cfg)) // big clubs are followed by default
        assertFalse(SportsSelection.isFollowed(match("c", home = nobody, away = nobody2), cfg))
        assertTrue(SportsSelection.isFollowed(match("d", home = nobody, away = nobody2), cfg.copy(clubs = setOf("999"))))
        assertFalse(SportsSelection.isFollowed(match("e", home = liv, away = nobody), cfg.copy(clubs = emptySet()))) // unfollowed
        assertTrue(SportsSelection.isFollowed(match("f", home = bar, away = nobody), cfg.copy(clubs = emptySet())))
    }

    @Test fun `player names come out of the feed text`() {
        assertEquals("Arda Güler", EspnSummaryParser.playerOf("Goal! Real Madrid 1, Sevilla 0. Arda Güler (Real Madrid) left footed shot from the centre of the box.", EventKind.Goal))
        assertEquals("Marc Cucurella", EspnSummaryParser.playerOf("Marc Cucurella (Real Madrid) is shown the yellow card for a bad foul.", EventKind.Yellow))
    }

    @Test fun `all-matches competitions show everything and disabled leagues nothing`() {
        assertTrue(SportsSelection.isFollowed(match("a", league = "fifa.world", home = nobody, away = nobody2), cfg))
        assertFalse(SportsSelection.isFollowed(match("b", league = "fra.1", home = rma, away = bar), cfg)) // not enabled by default
    }

    @Test fun `widget shows today's matches only, live and priority clubs first, three at most`() {
        val hour = 3_600_000L
        val matches = listOf(
            match("liv", home = liv, away = nobody, state = MatchState.Live, kickoff = NOW - hour),
            match("rma", league = "esp.1", home = rma, away = nobody, kickoff = NOW + 2 * hour),
            match("bar", league = "esp.1", home = nobody2, away = bar, state = MatchState.Live, kickoff = NOW - hour / 2),
            match("ucl2", home = liv, away = nobody2, kickoff = NOW + hour / 2),
            match("tomorrow", league = "esp.1", home = bar, away = nobody, kickoff = NOW + 30 * hour),
        )
        val picks = SportsSelection.widgetMatches(matches, cfg, NOW).map { it.id }
        assertEquals(listOf("bar", "liv", "rma"), picks) // live BAR, live LIV, then RMA; tomorrow's and the 4th are out
    }

    @Test fun `la liga matches without real madrid or barcelona stay out of the widget`() {
        val atm = team("1068", "ATM")
        val m = match("x", league = "esp.1", home = atm, away = nobody, kickoff = NOW + 3_600_000)
        assertTrue(SportsSelection.isFollowed(m, cfg)) // on the shelf (big club)
        assertTrue(SportsSelection.widgetMatches(listOf(m), cfg, NOW).isEmpty())
    }

    @Test fun `no match today means no widget`() {
        val later = match("later", league = "esp.1", home = rma, away = nobody, kickoff = NOW + 5L * 24 * 3_600_000)
        assertTrue(SportsSelection.widgetMatches(listOf(later), cfg, NOW).isEmpty())
    }

    @Test fun `a finished match from earlier today stays but ranks last`() {
        val fin = match("fin", home = liv, away = nobody, state = MatchState.Finished, kickoff = NOW - 2 * 3_600_000L)
        val up = match("up", home = liv, away = nobody2, kickoff = NOW + 3_600_000L)
        assertEquals(listOf("up", "fin"), SportsSelection.widgetMatches(listOf(fin, up), cfg, NOW).map { it.id })
        assertEquals(listOf("fin"), SportsSelection.rowMatches(listOf(fin), cfg, NOW).map { it.id }) // the shelf keeps 24 h
    }

    @Test fun `live polling only when something is on or about to start`() {
        assertFalse(SportsSelection.needsLivePolling(listOf(match("a", home = rma, away = nobody)), NOW)) // in an hour
        assertTrue(SportsSelection.needsLivePolling(listOf(match("a", home = rma, away = nobody, kickoff = NOW + 600_000)), NOW))
        assertTrue(SportsSelection.needsLivePolling(listOf(match("a", home = rma, away = nobody, state = MatchState.Live)), NOW))
    }

    @Test fun `summary parser reads formations, starters, goals and cards`() {
        val json = JSONObject(
            """{"gameInfo":{"attendance":70000},
            "rosters":[{"homeAway":"home","formation":"4-2-3-1","team":{"id":"86"},"roster":[
               {"starter":true,"jersey":"1","athlete":{"displayName":"Courtois"},"position":{"abbreviation":"G"}},
               {"starter":false,"jersey":"13","athlete":{"displayName":"Lunin"},"position":{"abbreviation":"G"}}]},
              {"homeAway":"away","formation":"4-4-2","team":{"id":"243"},"roster":[]}],
            "keyEvents":[{"type":{"type":"goal"},"clock":{"displayValue":"30'"},"team":{"id":"86"},"text":"Goal!"},
              {"type":{"type":"substitution"},"clock":{"displayValue":"56'"},"team":{"id":"243"},"text":"Sub"},
              {"type":{"type":"yellow-card"},"clock":{"displayValue":"69'"},"team":{"id":"243"},"text":"Yellow"}]}""",
        )
        val d = EspnSummaryParser.parse(json, team("86", "RMA"), team("243", "SEV"))
        assertEquals("4-2-3-1", d.homeFormation); assertEquals(1, d.homeStarters.size); assertEquals("Courtois", d.homeStarters[0].name)
        assertEquals(70000, d.attendance)
        assertEquals(listOf(EventKind.Goal, EventKind.Yellow), d.events.map { it.kind }) // substitutions are not listed
        assertEquals(null, d.referee)
    }

    private companion object { const val NOW = 1_792_500_000_000L }
}
