package com.moath.tvhome.assistant

import com.moath.tvhome.system.StremioTuner
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class InstantAndStremioTest {
    private val apps = setOf("يوتيوب", "ستريميو", "كاسبر")
    private fun parse(t: String) = runBlocking {
        FastIntents.parse(t, { n -> apps.any { normAr(it) == normAr(n) } }, ZonedDateTime.of(2026, 10, 21, 13, 45, 0, 0, ZoneId.of("Asia/Riyadh")))
    }
    private fun tool(f: Fast?) = f as Fast.Tool

    @Test fun `common commands run without a model`() {
        assertEquals("up", tool(parse("ارفع الصوت")).args.getString("change"))
        assertEquals("down", tool(parse("خفض الصوت لو سمحت")).args.getString("change"))
        assertEquals(30, tool(parse("الصوت 30")).args.getInt("level"))
        assertEquals(40, tool(parse("خلي الصوت على ٤٠")).args.getInt("level"))
        assertEquals("pause", tool(parse("وقف")).args.getString("action"))
        assertEquals("home", tool(parse("روح للرئيسية")).args.getString("action"))
        assertEquals("الكهف", tool(parse("شغل سورة الكهف")).args.getString("surah"))
    }

    @Test fun `opening an app is instant only for installed apps`() {
        assertEquals("open_app", tool(parse("افتح يوتيوب")).name)
        assertEquals("ستريميو", tool(parse("يا رفيق ودني لستريميو")).args.getString("name"))
        assertNull(parse("افتح الثلاجة"))
        // A whole request that merely mentions an app is for the assistant.
        assertNull(parse("افتح انمي ون بيس الحلقة الاخيرة من ستريميو"))
    }

    @Test fun `live channels and the time`() {
        val live = tool(parse("شغل الجزيرة مباشر"))
        assertEquals("youtube_channel_live", live.name)
        assertTrue(live.args.getString("channel").endsWith("مباشر"))
        val time = parse("كم الساعة") as Fast.Reply
        assertTrue(time.text.startsWith("الساعة الحين"))
        assertNull(parse("وش الفرق بين القط والكلب"))
    }

    private fun ep(s: Int, e: Int, released: String?) = StremioFlow.Ep("tt1:$s:$e", s, e, released)
    private val eps = listOf(
        ep(1, 1, "2000-01-01T00:00:00.000Z"), ep(1, 2, "2000-01-08T00:00:00.000Z"),
        ep(2, 1, "2001-01-01T00:00:00.000Z"), ep(2, 2, "2001-01-08T00:00:00.000Z"),
        ep(3, 1, "2026-09-27T00:00:00.000Z"), ep(3, 2, "2026-10-04T00:00:00.000Z"), ep(3, 3, "2027-01-01T00:00:00.000Z"),
        ep(0, 1, "1999-01-01T00:00:00.000Z"),
    )
    private val now = "2026-10-21T10:00:00Z"

    @Test fun `the last episode of the last season is the newest one already aired`() {
        assertEquals("tt1:3:2", StremioFlow.pickEpisode(eps, now, null, null)!!.id)
        assertEquals("tt1:3:2", StremioFlow.pickEpisode(eps, now, "last", "last")!!.id)
        assertEquals("tt1:2:2", StremioFlow.pickEpisode(eps, now, "2", "last")!!.id)
        assertEquals("tt1:2:1", StremioFlow.pickEpisode(eps, now, "2", null)!!.id)
        assertEquals("tt1:1:2", StremioFlow.pickEpisode(eps, now, "1", "2")!!.id)
        // "Episode 4" counted from the start (specials, season 0, don't count).
        assertEquals("tt1:2:2", StremioFlow.pickEpisode(eps, now, null, "4")!!.id)
        assertNull(StremioFlow.pickEpisode(eps, now, "9", "1"))
    }

    @Test fun `anime picks the animated title over a live-action remake`() = runBlocking {
        val metas = listOf(StremioFlow.Meta("tt-live", "One Piece"), StremioFlow.Meta("tt-anime", "One Piece"))
        val genres = mapOf("tt-live" to "Action", "tt-anime" to "Animation")
        val pick = StremioFlow.chooseMeta("one piece", metas, anime = true) { JSONObject().put("genres", org.json.JSONArray(listOf(genres[it.id]))) }
        assertEquals("tt-anime", pick!!.id)
        assertEquals("tt-live", StremioFlow.chooseMeta("One Piece", metas, anime = false) { null }!!.id)
    }

    @Test fun `stream choice skips subscription shortcuts and prefers 1080p`() {
        assertFalse(StremioTuner.isGood("Netflix ‖ Subscription"))
        assertTrue(StremioTuner.isGood("Torrentio 4k ‖ [ToonsHub] One Piece EP1180 2160p"))
        val streams = listOf("Torrentio 4k ‖ One Piece 2160p", "Torrentio 720p ‖ One Piece 720p", "Torrentio 1080p ‖ One Piece 1080p")
        assertEquals(streams[2], streams.maxByOrNull { StremioTuner.rank(it) })
        assertTrue(StremioTuner.rank("x 720p") > StremioTuner.rank("x 4k"))
        assertNotNull(streams.firstOrNull { StremioTuner.isGood(it) })
    }

    @Test fun streamScoringPrefersSeededReasonable1080p() {
        val dead4k = "Torrentio 4k HDR ‖ Paddington.2.2160p ‖ 👤 5 💾 14.05 GB ⚙️ ThePirateBay"
        val good = "Torrentio 1080p ‖ Paddington.2.1080p ‖ 👤 240 💾 2.1 GB ⚙️ YTS"
        val deadHd = "Torrentio 1080p ‖ x ‖ 👤 1 💾 1.9 GB"
        assertTrue(StremioTuner.score(good) > StremioTuner.score(dead4k))
        assertTrue(StremioTuner.score(good) > StremioTuner.score(deadHd))
        assertTrue(StremioTuner.isTarget(good))
        assertFalse(StremioTuner.isTarget(deadHd))
        assertEquals(1.35, StremioTuner.sizeGb("💾 1.35 GB")!!, 0.001)
        assertEquals(1012, StremioTuner.seeders("👤 1012 💾"))
    }
}
