package com.moath.tvhome.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchAlertsTest {
    private val home = Club("86", "Real Madrid", "RMA", null)
    private val away = Club("999", "Getafe", "GET", null)
    private fun m(state: MatchState, h: Int?, a: Int?, status: String = "") =
        Match("1", "esp.1", "LaLiga", 0, state, status, home, away, h, a, null, null, emptyList())

    @Test fun `kickoff, goal and full time each give one notice`() {
        assertEquals("بدأت المباراة ⚽", MatchAlerts.diff(m(MatchState.Upcoming, null, null), m(MatchState.Live, 0, 0)).single().title)
        val goal = MatchAlerts.diff(m(MatchState.Live, 0, 0), m(MatchState.Live, 1, 0, "23'")).single()
        assertTrue(goal.title.contains("ريال مدريد"))
        assertTrue(goal.body.contains("1 - 0"))
        assertEquals("انتهت المباراة 🏁", MatchAlerts.diff(m(MatchState.Live, 1, 0), m(MatchState.Finished, 1, 0)).single().title)
    }

    @Test fun `nothing changes, nothing is posted`() {
        assertTrue(MatchAlerts.diff(m(MatchState.Live, 1, 0), m(MatchState.Live, 1, 0, "50'")).isEmpty())
    }

    @Test fun `reminds about added clubs only, within the lead time`() {
        val now = 1_000_000_000_000L
        fun up(id: String, home: Club, away: Club, inMin: Int) = Match(id, "esp.1", "LaLiga", now + inMin * 60_000L, MatchState.Upcoming, "", home, away, null, null, null, null, emptyList())
        val other = Club("777", "Sevilla", "SEV", null)
        val added = Club("364", "Liverpool", "LIV", null)
        val all = listOf(up("a", home, away, 10), up("b", other, Club("778", "Betis", "BET", null), 10), up("c", added, other, 14), up("d", home, other, 40))
        val cfg = SportsConfig(clubs = setOf("364"))
        assertEquals(listOf("a", "c"), MatchAlerts.soonMatches(all, cfg, 15, now).map { it.id })
        assertTrue(MatchAlerts.reminder(all[0], 15).title.contains("بعد 15 دقيقة"))
    }
}
