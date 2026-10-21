package com.moath.tvhome.assistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AssistantLogicTest {
    private fun entry(slug: String, inNano: Long, outNano: Long, tools: Boolean = true, audio: Boolean = false, status: String = "active", providers: String? = null) = JSONObject(
        """{"model":{"slug":"$slug","display_name":"$slug","status":"active",
            "input_modalities":${if (audio) "[\"text\",\"audio\"]" else "[\"text\"]"},"supported_params":{"tools":$tools}},
           "providers":${providers ?: """[{"status":"$status","input_nano_usd_per_million":$inNano,"output_nano_usd_per_million":$outNano}]"""}}""",
    )

    @Test fun `catalog keeps models at or under the price cap`() {
        val ok = ModelCatalog.parse(entry("cheap", 100_000_000, 750_000_000))
        assertEquals("cheap", ok!!.first.id)
        assertEquals(0.75, ok.first.outUsd!!, 1e-9)
        assertTrue(ok.second)
    }

    @Test fun `catalog drops models over the cap, batch and unpriced ones`() {
        assertNull(ModelCatalog.parse(entry("pricey", 100_000_000, 760_000_000)))
        assertNull(ModelCatalog.parse(entry("x-batch", 10_000_000, 10_000_000)))
        assertNull(ModelCatalog.parse(entry("free", 0, 0)))
        assertNull(ModelCatalog.parse(entry("off", 10_000_000, 10_000_000, status = "disabled")))
    }

    @Test fun `the most expensive active route decides`() {
        val two = """[{"status":"active","input_nano_usd_per_million":100000000,"output_nano_usd_per_million":200000000},
                      {"status":"active","input_nano_usd_per_million":900000000,"output_nano_usd_per_million":200000000},
                      {"status":"disabled","input_nano_usd_per_million":9000000000,"output_nano_usd_per_million":9000000000}]"""
        assertNull(ModelCatalog.parse(entry("mixed", 0, 0, providers = two)))
    }

    @Test fun `audio models without tools are kept for transcription only`() {
        val a = ModelCatalog.parse(entry("listen", 100_000_000, 400_000_000, tools = false, audio = true))!!
        assertTrue(a.first.audio)
        assertFalse(a.second)
    }

    @Test fun `speech models come from transcription aliases, priced per minute`() {
        val whisper = JSONObject("""{"id":"whisper-1","pricing":{"units":{"kind":"audio_second","rates":{"":100000}}}}""")
        assertEquals(0.006, ModelCatalog.speech(whisper)!!.perMinuteUsd!!, 1e-9)
        val pricey = JSONObject("""{"id":"gpt-4o-mini-transcribe","pricing":{"input_nano_usd_per_million_tokens":1250000000,"output_nano_usd_per_million_tokens":5000000000}}""")
        assertNull(ModelCatalog.speech(pricey))
        val chat = JSONObject("""{"id":"gemini-2.5-flash-lite","pricing":{"input_nano_usd_per_million_tokens":100000000,"output_nano_usd_per_million_tokens":400000000}}""")
        assertNull(ModelCatalog.speech(chat))
    }

    @Test fun `parses youtube search results`() {
        val html = """<script>var ytInitialData = {"contents":{"x":[
            {"videoRenderer":{"videoId":"abcdefghijk","title":{"runs":[{"text":"الجزيرة "},{"text":"مباشر"}]},
              "ownerText":{"runs":[{"text":"Al Jazeera"}]},"badges":[{"metadataBadgeRenderer":{"label":"LIVE"}}]}},
            {"channelRenderer":{"channelId":"UCfiwzLy-8yKzIbsmZTzxDgw","title":{"simpleText":"قناة الجزيرة"}}},
            {"videoRenderer":{"videoId":"bad"}}
          ]}};</script>"""
        val (videos, channels) = YoutubeLookup.parseSearch(html)
        assertEquals(1, videos.size)
        assertEquals("الجزيرة مباشر", videos[0].title)
        assertTrue(videos[0].live)
        assertEquals("UCfiwzLy-8yKzIbsmZTzxDgw", channels.single().id)
    }

    @Test fun `live page yields the stream only when live`() {
        val canonical = """<link rel="canonical" href="https://www.youtube.com/watch?v=abcdefghijk">"""
        assertEquals("abcdefghijk", YoutubeLookup.parseLive("$canonical \"isLiveNow\":true"))
        assertNull(YoutubeLookup.parseLive(canonical))
    }

    @Test fun `wav header describes 16 kHz mono pcm`() {
        val wav = VoiceInput.wav(ByteArray(3200))
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(36 + 3200, b.getInt(4))
        assertEquals(16_000, b.getInt(24))
        assertEquals(3200, b.getInt(40))
        assertEquals(44 + 3200, wav.size)
    }

    @Test fun `safety filter blocks explicit requests only`() {
        assertTrue(Safety.blocked("شغل لي سكس"))
        assertTrue(Safety.blocked("PORN video"))
        assertFalse(Safety.blocked("شغل الجزيرة مباشر"))
        assertFalse(Safety.blocked("آخر مقطع لقناة التقنية"))
    }

    @Test fun `speech text drops emoji and markdown, detects arabic`() {
        assertEquals("أبشر! شغّلت لك الجزيرة.", Speaker.speakable("أبشر! 📺 شغّلت لك **الجزيرة**."))
        assertTrue(Speaker.isArabic("الجو حار اليوم 31 درجة"))
        assertFalse(Speaker.isArabic("The weather is sunny"))
    }
}

class RetryAfterTest {
    @org.junit.Test fun `reads google retry delay`() {
        org.junit.Assert.assertEquals(33_237_000L, retryAfter("""{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay": "33237s"}"""))
        org.junit.Assert.assertEquals(12_000L, retryAfter(""""retryDelay":"12.5s""""))
        org.junit.Assert.assertNull(retryAfter("""{"error":"slow down"}"""))
    }
}

class GoogleEarTest {
    private fun l(top: Int, t: String, interactive: Boolean = false) = com.moath.tvhome.system.GoogleEar.TextLine(top, t, interactive)

    @org.junit.Test fun `picks the spoken words, not google's prompt or buttons`() {
        val prompt = l(84, "يمكنك طرح سؤال أو تجربة أحد الاقتراحات.")
        val chip = l(208, "البحث عن برامج مسلّية", interactive = true)
        org.junit.Assert.assertNull(com.moath.tvhome.system.GoogleEar.pickTranscript(listOf(prompt, chip)))
        org.junit.Assert.assertEquals(
            "شغل لي الجزيرة مباشر",
            com.moath.tvhome.system.GoogleEar.pickTranscript(listOf(chip, l(90, "شغل لي الجزيرة مباشر"), l(300, "نتيجة"))),
        )
    }

    @org.junit.Test fun `google's idle hint is never taken as the owner's words`() {
        val hint = l(84, "اضغط على زر الميكروفون للتحدّث")
        val chips = listOf(l(208, "البحث عن برامج مسلّية", true), l(212, "معرفة حالة الطقس", true))
        org.junit.Assert.assertNull(com.moath.tvhome.system.GoogleEar.pickTranscript(listOf(hint) + chips))
        org.junit.Assert.assertEquals("كيف حالك", com.moath.tvhome.system.GoogleEar.pickTranscript(listOf(l(84, "كيف حالك"))))
    }
}
