package com.moath.tvhome.aerial

import java.io.IOException

/**
 * Reader for Apple binary property lists (bplist00), used for the Localizable.nocache.strings
 * inside TVIdleScreenStrings.bundle. Supports the object kinds that appear in strings files
 * (dicts, arrays, strings, ints, bools); anything else decodes to null.
 */
internal class AerialPlist private constructor(private val b: ByteArray) {
    private val offsetSize: Int
    private val refSize: Int
    private val offsets: LongArray

    init {
        if (b.size < 40 || String(b, 0, 8, Charsets.US_ASCII) != "bplist00") throw IOException("Not a binary plist")
        val t = b.size - 32
        offsetSize = b[t + 6].toInt() and 0xFF
        refSize = b[t + 7].toInt() and 0xFF
        val count = uint(t + 8, 8)
        val tableAt = uint(t + 24, 8)
        if (count <= 0 || count > MAX_OBJECTS || offsetSize !in 1..8 || refSize !in 1..8) {
            throw IOException("Corrupt plist trailer")
        }
        if (tableAt + count * offsetSize > t) throw IOException("Corrupt plist offset table")
        offsets = LongArray(count.toInt()) { uint((tableAt + it * offsetSize).toInt(), offsetSize) }
    }

    private val top: Int = uint(b.size - 32 + 16, 8).toInt()

    private fun uint(at: Int, len: Int): Long {
        var v = 0L
        for (i in at until at + len) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }

    private fun obj(ref: Int, depth: Int): Any? {
        if (ref !in offsets.indices || depth > MAX_DEPTH) throw IOException("Bad plist ref")
        val at = offsets[ref].toInt()
        val marker = b[at].toInt() and 0xFF
        val kind = marker shr 4
        val info = marker and 0x0F
        return when (kind) {
            0x0 -> when (info) { 0x8 -> false; 0x9 -> true; else -> null }
            0x1 -> uint(at + 1, 1 shl info)
            0x5 -> length(at, info).let { (start, n) -> String(b, start, n, Charsets.US_ASCII) }
            0x6 -> length(at, info).let { (start, n) -> String(b, start, n * 2, Charsets.UTF_16BE) }
            0x7 -> length(at, info).let { (start, n) -> String(b, start, n, Charsets.UTF_8) }
            0xA -> length(at, info).let { (start, n) -> List(n) { obj(ref(start + it * refSize), depth + 1) } }
            0xD -> length(at, info).let { (start, n) ->
                val values = start + n * refSize
                (0 until n).associate { i ->
                    obj(ref(start + i * refSize), depth + 1).toString() to obj(ref(values + i * refSize), depth + 1)
                }
            }
            else -> null
        }
    }

    private fun ref(at: Int): Int = uint(at, refSize).toInt()

    /** Returns (payload start, element count) for a sized object at [at]. */
    private fun length(at: Int, info: Int): Pair<Int, Int> {
        if (info != 0x0F) return at + 1 to info
        val intMarker = b[at + 1].toInt() and 0xFF
        if (intMarker shr 4 != 0x1) throw IOException("Bad plist length")
        val width = 1 shl (intMarker and 0x0F)
        return at + 2 + width to uint(at + 2, width).toInt()
    }

    companion object {
        private const val MAX_OBJECTS = 1_000_000L
        private const val MAX_DEPTH = 32

        fun parse(bytes: ByteArray): Any? = AerialPlist(bytes).let { it.obj(it.top, 0) }

        /** A strings file: flat dict of key → localized text. */
        fun parseStrings(bytes: ByteArray): Map<String, String> {
            val root = parse(bytes) as? Map<*, *> ?: throw IOException("Strings plist is not a dict")
            return root.entries.mapNotNull { (k, v) -> (v as? String)?.let { k.toString() to it } }.toMap()
        }
    }
}
