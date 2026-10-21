package com.moath.tvhome.aerial

import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Minimal streaming tar reader: enough for Apple's resources-NN.tar (ustar, optionally with pax
 * or GNU long-name records). Pulls out only the requested entries and stops reading once all are
 * found, so the rest of the download is never buffered.
 */
internal object AerialTar {
    private const val BLOCK = 512
    private const val MAX_ENTRY_BYTES = 16 * 1024 * 1024

    fun extract(input: InputStream, wanted: Set<String>): Map<String, ByteArray> {
        val found = HashMap<String, ByteArray>()
        val header = ByteArray(BLOCK)
        var overrideName: String? = null
        while (found.size < wanted.size) {
            if (!readBlock(input, header)) break
            if (header.all { it == 0.toByte() }) break
            val size = parseOctal(header, 124, 12)
            if (size < 0) throw IOException("Corrupt tar header")
            val type = header[156].toInt().toChar()
            val name = overrideName ?: headerName(header)
            overrideName = null
            when {
                type == 'x' || type == 'L' -> {
                    val body = readBody(input, size)
                    overrideName = if (type == 'L') String(body, Charsets.UTF_8).trimEnd('\u0000') else paxPath(body)
                }
                name.removePrefix("./") in wanted && (type == '0' || type == '\u0000') -> {
                    found[name.removePrefix("./")] = readBody(input, size)
                }
                else -> skipFully(input, padded(size))
            }
        }
        return found
    }

    private fun headerName(h: ByteArray): String {
        val name = cString(h, 0, 100)
        val isUstar = String(h, 257, 5, Charsets.US_ASCII) == "ustar"
        val prefix = if (isUstar) cString(h, 345, 155) else ""
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun paxPath(body: ByteArray): String? =
        String(body, Charsets.UTF_8).lineSequence()
            .map { it.substringAfter(' ', "") }
            .firstOrNull { it.startsWith("path=") }
            ?.removePrefix("path=")

    private fun readBody(input: InputStream, size: Long): ByteArray {
        if (size > MAX_ENTRY_BYTES) throw IOException("Tar entry too large: $size")
        val out = ByteArray(size.toInt())
        readFully(input, out, out.size)
        skipFully(input, padded(size) - size)
        return out
    }

    private fun padded(size: Long): Long = (size + BLOCK - 1) / BLOCK * BLOCK

    private fun cString(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    /** Octal (space/NUL padded) or GNU base-256 for large sizes. */
    internal fun parseOctal(b: ByteArray, off: Int, len: Int): Long {
        if (b[off].toInt() and 0x80 != 0) {
            var v = 0L
            for (i in off + 1 until off + len) v = (v shl 8) or (b[i].toLong() and 0xFF)
            return v
        }
        var v = 0L
        for (i in off until off + len) {
            val c = b[i].toInt().toChar()
            if (c == ' ' || c == '\u0000') { if (v != 0L) break else continue }
            if (c !in '0'..'7') return -1
            v = v * 8 + (c - '0')
        }
        return v
    }

    private fun readBlock(input: InputStream, block: ByteArray): Boolean {
        var read = 0
        while (read < block.size) {
            val n = input.read(block, read, block.size - read)
            if (n < 0) { if (read == 0) return false else throw EOFException("Truncated tar") }
            read += n
        }
        return true
    }

    private fun readFully(input: InputStream, out: ByteArray, len: Int) {
        var read = 0
        while (read < len) {
            val n = input.read(out, read, len - read)
            if (n < 0) throw EOFException("Truncated tar entry")
            read += n
        }
    }

    private fun skipFully(input: InputStream, count: Long) {
        val scratch = ByteArray(BLOCK * 8)
        var left = count
        while (left > 0) {
            val n = input.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
            if (n < 0) throw EOFException("Truncated tar")
            left -= n
        }
    }
}
