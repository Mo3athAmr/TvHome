package com.moath.tvhome.aerial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class AerialParsingTest {

    // plutil -convert binary1 of {"A":"Hello","B":"Café x\nline","C":"","D":"Traveling over … Red Sea"}
    private val stringsPlist = hex(
        "62706c6973743030d4010203040506070851415144514251435548656c6c" +
            "6f5f105b54726176656c696e67206f76657220746865204d656469746572" +
            "72616e65616e2053656120746f7761726420746865204e696c6520526976" +
            "65722c20456779707420616e64206265796f6e6420746f20746865205265" +
            "64205365616b00430061006600e900200078000a006c0069006e00655008" +
            "11131517191f7d9400000000000001010000000000000009000000000000" +
            "00000000000000000095",
    )

    @Test
    fun `binary plist strings decode ascii, utf16, long and empty values`() {
        val strings = AerialPlist.parseStrings(stringsPlist)

        assertEquals("Hello", strings["A"])
        assertEquals("Café x\nline", strings["B"])
        assertEquals("", strings["C"])
        assertEquals(
            "Traveling over the Mediterranean Sea toward the Nile River, Egypt and beyond to the Red Sea",
            strings["D"],
        )
    }

    @Test(expected = java.io.IOException::class)
    fun `non plist bytes are rejected`() {
        AerialPlist.parseStrings(ByteArray(64) { 1 })
    }

    @Test
    fun `tar extracts only wanted entries including prefixed and long names`() {
        val longName = "TVIdleScreenStrings.bundle/" + "x".repeat(120) + "/Localizable.nocache.strings"
        val tar = Tar()
            .file("TVIdleScreenStrings.bundle/de.lproj/Localizable.nocache.strings", "nope")
            .file("entries.json", "{\"assets\":[]}")
            .gnuLongName(longName, "long")
            .file("./TVIdleScreenStrings.bundle/en.lproj/Localizable.nocache.strings", "english")
            .bytes()

        val found = AerialTar.extract(
            ByteArrayInputStream(tar),
            setOf("entries.json", "TVIdleScreenStrings.bundle/en.lproj/Localizable.nocache.strings", longName),
        )

        assertEquals("{\"assets\":[]}", String(found.getValue("entries.json")))
        assertEquals("english", String(found.getValue("TVIdleScreenStrings.bundle/en.lproj/Localizable.nocache.strings")))
        assertEquals("long", String(found.getValue(longName)))
        assertEquals(3, found.size)
    }

    @Test
    fun `tar returns partial results when an entry is missing`() {
        val tar = Tar().file("entries.json", "{}").bytes()
        val found = AerialTar.extract(ByteArrayInputStream(tar), setOf("entries.json", "missing"))
        assertEquals(setOf("entries.json"), found.keys)
    }

    @Test
    fun `shuffle never repeats back to back and covers every clip each cycle`() {
        val size = 7
        val shuffle = AerialShuffle(size, Random(42))
        var previous = -1
        repeat(50) {
            val cycle = (0 until size).map {
                val peeked = shuffle.peek()
                shuffle.next().also { got ->
                    assertEquals(peeked, got)
                    assertTrue("immediate repeat of $got", got != previous)
                    previous = got
                }
            }
            assertEquals((0 until size).toSet(), cycle.toSet())
        }
    }

    @Test
    fun `single clip shuffle keeps returning it`() {
        val shuffle = AerialShuffle(1)
        repeat(3) { assertEquals(0, shuffle.next()) }
    }

    @Test
    fun `caption follows points of interest and falls back to label`() {
        val clip = AerialClip(
            id = "x", label = "Los Angeles", url1080 = "h264", url4k = "hevc",
            pois = listOf(AerialPoi(0, "Over Santa Monica"), AerialPoi(365, "Passing the Pier")),
        )
        assertEquals("Over Santa Monica", clip.captionAt(10))
        assertEquals("Passing the Pier", clip.captionAt(365))
        assertEquals("Los Angeles", clip.copy(pois = emptyList()).captionAt(5))
        assertNull(clip.copy(pois = listOf(AerialPoi(30, "later"))).poiAt(5))
        assertEquals("hevc", clip.url(prefer4k = true))
        assertEquals("h264", clip.copy(url4k = null).url(prefer4k = true))
        assertFalse(clip.url(prefer4k = false) == "hevc")
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Writes ustar archives for tests. */
    private class Tar {
        private val out = ByteArrayOutputStream()

        fun file(name: String, body: String) = entry(name, body.toByteArray(), '0')

        fun gnuLongName(name: String, body: String): Tar {
            entry("././@LongLink", name.toByteArray() + 0, 'L')
            return entry(name.take(99), body.toByteArray(), '0')
        }

        fun bytes(): ByteArray = out.toByteArray() + ByteArray(1024)

        private fun entry(name: String, body: ByteArray, type: Char): Tar {
            val h = ByteArray(512)
            val (prefix, short) = if (name.length > 100) name.substringBeforeLast('/') to name.substringAfterLast('/') else "" to name
            short.toByteArray().copyInto(h, 0)
            String.format("%011o", body.size).toByteArray().copyInto(h, 124)
            h[156] = type.code.toByte()
            "ustar".toByteArray().copyInto(h, 257)
            if (type != 'L') prefix.take(155).toByteArray().copyInto(h, 345)
            out.write(h)
            out.write(body)
            out.write(ByteArray((512 - body.size % 512) % 512))
            return this
        }
    }
}
