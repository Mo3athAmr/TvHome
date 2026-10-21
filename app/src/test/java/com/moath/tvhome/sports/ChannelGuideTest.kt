package com.moath.tvhome.sports

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ChannelGuideTest {
    private val day = LocalDate.of(2026, 10, 20)
    private val html = """
        <div class="cin_cntnr"> <a href="/matches/1"> <div class="c-i-next"> <div class="f"> <a href="/teams/143/x"> <strong>روما</strong> </a> </div>
        <div class="s"> <a href="/teams/1324/y"> <strong>سلوفان براتيسلافا</strong> </a> </div> </div>
        <div class="match-aux"> <span><svg><use xlink:href="#fb_screen"></use></svg>beIN SPORTS 6 HD</span> <span>20-10-2026 - 22:00</span> </div> </a> </div>
        <div class="cin_cntnr"> <a href="/matches/2"> <div class="c-i-next"> <div class="f"> <a><strong>برشلونة</strong></a> </div>
        <div class="s"> <a><strong>باريس سان جيرمان</strong></a> </div> </div>
        <div class="match-aux"> <span><svg><use xlink:href="#fb_screen"></use></svg>beIN SPORTS 1 HD</span> </div> </a> </div>
        <div class="cin_cntnr"> <a href="/matches/3"> <div class="c-i-next"> <div class="f"> <a><strong>ريال مدريد</strong></a> </div>
        <div class="s"> <a><strong>لايبزيغ</strong></a> </div> </div> <div class="match-aux"> <span>22:00</span> </div> </a> </div>
    """.trimIndent()

    private fun club(id: String, name: String) = Club(id, name, name, null)
    private fun match(home: Club, away: Club) =
        Match("m", "uefa.champions", "UCL", 0, MatchState.Upcoming, "", home, away, null, null, null, null, emptyList())

    @Test fun `parses fixtures with their channels`() {
        val e = ChannelGuide.parse(html, day)
        assertEquals(3, e.size)
        assertEquals(listOf("روما", "سلوفان براتيسلافا"), e[0].teams)
        assertEquals(listOf("beIN SPORTS 6 HD"), e[0].channels)
        assertTrue(e[2].channels.isEmpty()) // not announced yet
    }

    @Test fun `matches a fixture by catalogue club even when the opponent is not in the catalogue`() {
        val entries = ChannelGuide.parse(html, day)
        assertEquals(listOf("beIN SPORTS 6 HD"), ChannelGuide.channelsFor(match(club("104", "AS Roma"), club("1324", "Slovan")), entries, day))
    }

    @Test fun `both known clubs must match, and a different pairing gets nothing`() {
        val entries = ChannelGuide.parse(html, day)
        assertEquals(listOf("beIN SPORTS 1 HD"), ChannelGuide.channelsFor(match(club("160", "PSG"), club("83", "Barcelona")), entries, day))
        assertTrue(ChannelGuide.channelsFor(match(club("86", "Real Madrid"), club("83", "Barcelona")), entries, day).isEmpty())
    }

    @Test fun `unknown clubs and far-away dates never match`() {
        val entries = ChannelGuide.parse(html, day)
        assertTrue(ChannelGuide.channelsFor(match(club("1", "A"), club("2", "B")), entries, day).isEmpty())
        assertTrue(ChannelGuide.channelsFor(match(club("104", "Roma"), club("1324", "Slovan")), entries, day.plusDays(3)).isEmpty())
    }

    @Test fun `arabic spelling variants fold together`() =
        assertEquals(ChannelGuide.normalize("أتلتيكو مدريد"), ChannelGuide.normalize("اتلتيكو  مدريد"))

    @Test fun `fotmob club names compare loosely`() {
        assertTrue(FotMobParser.sameClub("Atlético Madrid", "Atletico Madrid"))
        assertTrue(FotMobParser.sameClub("Inter", "Internazionale"))
        assertFalse(FotMobParser.sameClub("Real Madrid", "Barcelona"))
    }

    @Test fun `fotmob details give referee and a laid out lineup`() {
        fun p(n: Int, x: Double, y: Double) = """{"shirtNumber":"$n","lastName":"P$n","name":"First P$n","verticalLayout":{"x":$x,"y":$y}}"""
        val starters = (1..11).joinToString(",") { p(it, it / 12.0, 0.1 + it * 0.07) }
        val json = JSONObject(
            """{"content":{"matchFacts":{"infoBox":{"Referee":{"text":"Michael Oliver"}}},
              "lineup":{"lineupType":"predicted","homeTeam":{"formation":"4-3-3","starters":[$starters]},"awayTeam":{"formation":"4-4-2","starters":[$starters]}}}}""",
        )
        val info = FotMobParser.parseDetails(json)
        assertEquals("Michael Oliver", info.referee)
        assertEquals("4-3-3", info.home?.formation)
        assertEquals(11, info.home?.players?.size)
        assertTrue(info.home!!.predicted)
        assertNull(FotMobParser.parseDetails(JSONObject("""{"content":{}}""")).home)
    }

    @Test fun `fotmob match list lookup finds the fixture`() {
        val list = JSONObject("""{"leagues":[{"matches":[{"id":6106234,"home":{"name":"Paris Saint-Germain"},"away":{"name":"Barcelona"}}]}]}""")
        assertEquals("6106234", FotMobParser.findMatchId(list, "Paris Saint-Germain", "Barcelona"))
        assertNull(FotMobParser.findMatchId(list, "Real Madrid", "Barcelona"))
    }
}
