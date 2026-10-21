package com.moath.tvhome.youtube

import org.junit.Assert.assertEquals
import org.junit.Test

class YoutubeFeedTest {
    private val xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns="http://www.w3.org/2005/Atom">
         <title>Some Channel</title>
         <author><name>Some Channel</name></author>
         <entry>
          <id>yt:video:abcdefghijk</id>
          <yt:videoId>abcdefghijk</yt:videoId>
          <yt:channelId>UCabcdefghijklmnopqrstuv</yt:channelId>
          <title>Tom &amp; Jerry &#39;live&#39; &quot;show&quot;</title>
          <link rel="alternate" href="https://www.youtube.com/watch?v=abcdefghijk"/>
          <author><name>Some Channel</name></author>
          <published>2026-10-20T10:00:00+00:00</published>
         </entry>
         <entry>
          <yt:videoId>ZZZZZZZZZZZ</yt:videoId>
          <title>Second</title>
          <author><name>Other</name></author>
          <published>2026-10-19T08:30:00+03:00</published>
         </entry>
        </feed>
    """.trimIndent()

    @Test fun `parses entries and unescapes text`() {
        val v = YoutubeFeed.parse(xml)
        assertEquals(2, v.size)
        assertEquals("abcdefghijk", v[0].id)
        assertEquals("Tom & Jerry 'live' \"show\"", v[0].title)
        assertEquals("Some Channel", v[0].channel)
        assertEquals("UCabcdefghijklmnopqrstuv", v[0].channelId)
        assertEquals(1_792_490_400_000L, v[0].publishedMs)
        assertEquals("Second", v[1].title)
    }

    @Test fun `entry without a video id or date is skipped`() {
        assertEquals(0, YoutubeFeed.parse("<entry><title>x</title></entry>").size)
        assertEquals(0, YoutubeFeed.parse("<entry><yt:videoId>abcdefghijk</yt:videoId><title>x</title></entry>").size)
    }

    @Test fun `channel list accepts takeout csv, ids, links and handles`() {
        val text = """
            Channel ID,Channel URL,Channel title
            UCabcdefghijklmnopqrstuv,http://www.youtube.com/channel/UCabcdefghijklmnopqrstuv,First
            https://www.youtube.com/channel/UC0123456789abcdefghijkl
            @some.handle
            https://www.youtube.com/@Other_One
            UCabcdefghijklmnopqrstuv
        """.trimIndent()
        val p = YoutubeChannels.parse(text)
        assertEquals(listOf("UCabcdefghijklmnopqrstuv", "UC0123456789abcdefghijkl"), p.ids)
        assertEquals(listOf("@some.handle", "@Other_One"), p.handles)
    }

    @Test fun `arabic relative time uses singular dual and plural forms`() {
        val now = 10_000_000_000L
        fun ago(min: Long) = ArabicTime.ago(now, now - min * 60_000)
        assertEquals("الآن", ago(0))
        assertEquals("منذ دقيقة", ago(1))
        assertEquals("منذ 5 دقائق", ago(5))
        assertEquals("منذ ساعة", ago(60))
        assertEquals("منذ ساعتين", ago(125))
        assertEquals("منذ 3 ساعات", ago(60 * 3))
        assertEquals("منذ 12 ساعة", ago(60 * 12))
        assertEquals("منذ يومين", ago(60 * 24 * 2))
        assertEquals("منذ 15 يوماً", ago(60 * 24 * 15))
    }
}
