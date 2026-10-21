package com.moath.tvhome.youtube

import java.time.OffsetDateTime

/** One upload from a subscribed channel. Everything here comes from the channel's public feed. */
data class YoutubeVideo(
    val id: String,
    val title: String,
    val channelId: String,
    val channel: String,
    val publishedMs: Long,
    /** null = not checked yet. Shorts are left out of the shelf. */
    val isShort: Boolean? = null,
)

/**
 * Parser for YouTube's public channel feed (`feeds/videos.xml?channel_id=…`, Atom). Plain regexes:
 * the format is fixed, and this keeps the parser a pure function that unit-tests off-device.
 */
object YoutubeFeed {
    private val entry = Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL)
    private val videoId = Regex("<yt:videoId>([0-9A-Za-z_-]{11})</yt:videoId>")
    private val channelId = Regex("<yt:channelId>(UC[0-9A-Za-z_-]{22})</yt:channelId>")
    private val title = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
    private val author = Regex("<author>\\s*<name>(.*?)</name>", RegexOption.DOT_MATCHES_ALL)
    private val published = Regex("<published>(.*?)</published>")

    fun parse(xml: String): List<YoutubeVideo> = entry.findAll(xml).mapNotNull { m ->
        val e = m.groupValues[1]
        val id = videoId.find(e)?.groupValues?.get(1) ?: return@mapNotNull null
        val name = title.find(e)?.groupValues?.get(1)?.let(::unescape)?.trim().orEmpty().ifEmpty { return@mapNotNull null }
        val at = published.find(e)?.groupValues?.get(1)?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
            ?: return@mapNotNull null
        YoutubeVideo(
            id = id,
            title = name,
            channelId = channelId.find(e)?.groupValues?.get(1).orEmpty(),
            channel = author.find(e)?.groupValues?.get(1)?.let(::unescape)?.trim().orEmpty(),
            publishedMs = at,
        )
    }.toList()

    internal fun unescape(s: String): String {
        val numeric = Regex("&#(x?)([0-9A-Fa-f]+);")
        return numeric.replace(s) { m ->
            val code = m.groupValues[2].toIntOrNull(if (m.groupValues[1].isEmpty()) 10 else 16)
            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
        }
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
            .replace("&amp;", "&")
    }
}

/**
 * Reads the list of channels to follow from a text file: a Google Takeout `subscriptions.csv`,
 * or any text with one channel per line (channel id `UC…`, a `/channel/UC…` link, or an `@handle`).
 */
object YoutubeChannels {
    data class Parsed(val ids: List<String>, val handles: List<String>)

    private val id = Regex("UC[0-9A-Za-z_-]{22}")
    private val handle = Regex("@[0-9A-Za-z._-]{3,30}")

    fun parse(text: String): Parsed {
        val ids = LinkedHashSet<String>()
        val handles = LinkedHashSet<String>()
        for (line in text.lineSequence()) {
            val byId = id.find(line)?.value
            if (byId != null) ids += byId else handle.find(line)?.value?.let { handles += it }
        }
        return Parsed(ids.toList(), handles.toList())
    }
}

/** "منذ 3 ساعات" — Arabic relative time with correct dual/plural forms. */
object ArabicTime {
    fun ago(nowMs: Long, thenMs: Long): String {
        val minutes = ((nowMs - thenMs) / 60_000).coerceAtLeast(0)
        return when {
            minutes < 1 -> "الآن"
            minutes < 60 -> "منذ " + unit(minutes, "دقيقة", "دقيقتين", "دقائق", "دقيقة")
            minutes < 60 * 24 -> "منذ " + unit(minutes / 60, "ساعة", "ساعتين", "ساعات", "ساعة")
            minutes < 60 * 24 * 30 -> "منذ " + unit(minutes / (60 * 24), "يوم", "يومين", "أيام", "يوماً")
            else -> "منذ " + unit(minutes / (60 * 24 * 30), "شهر", "شهرين", "أشهر", "شهراً")
        }
    }

    /** 1 → singular, 2 → dual, 3–10 → plural, 11+ → counted noun in the accusative. */
    private fun unit(n: Long, one: String, two: String, few: String, many: String): String = when {
        n == 1L -> one
        n == 2L -> two
        n in 3..10 -> "$n $few"
        else -> "$n $many"
    }
}
