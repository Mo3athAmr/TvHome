package com.moath.tvhome.sports

import java.time.LocalDate

/**
 * Which channel carries a match, read from FilGoal's public fixtures page (the only free source that
 * names the MENA channels with their number, e.g. "beIN SPORTS 2 HD"). Fixtures are matched by club
 * name in Arabic, so only clubs in [Clubs.catalog] (plus the national teams' own names) get a channel.
 */
object ChannelGuide {
    data class Entry(val date: LocalDate, val teams: List<String>, val channels: List<String>)

    private val block = Regex("""<div class="cin_cntnr">(.*?)(?=<div class="cin_cntnr">|<div class="mc-block">|</body>|$)""", RegexOption.DOT_MATCHES_ALL)
    private val team = Regex("""<strong>([^<]+)</strong>""")
    private val channel = Regex("""#fb_screen"></use></svg>\s*([^<]+?)\s*</span>""")

    fun parse(html: String, date: LocalDate): List<Entry> = block.findAll(html).mapNotNull { m ->
        val body = m.groupValues[1]
        val teams = team.findAll(body).map { it.groupValues[1].trim() }.toList()
        if (teams.size < 2) return@mapNotNull null
        Entry(date, teams.take(2), channel.findAll(body).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.distinct().toList())
    }.toList()

    /** Arabic spelling differences (alef forms, ya/alef maqsura, ta marbuta, marks) folded away. */
    fun normalize(s: String): String = s
        .replace(Regex("[\\u064B-\\u0652\\u0640]"), "")
        .replace(Regex("[أإآٱ]"), "ا")
        .replace('ى', 'ي').replace('ة', 'ه')
        .replace("نادي", "")
        .replace(Regex("\\s+"), " ").trim().lowercase()

    private val aliases: Map<String, List<String>> = mapOf(
        "86" to listOf("ريال مدريد"), "83" to listOf("برشلونة"), "1068" to listOf("أتلتيكو مدريد", "اتلتيكو مدريد"),
        "382" to listOf("مانشستر سيتي"), "364" to listOf("ليفربول"), "359" to listOf("أرسنال", "ارسنال"),
        "360" to listOf("مانشستر يونايتد"), "363" to listOf("تشيلسي"), "367" to listOf("توتنهام", "توتنهام هوتسبير"),
        "361" to listOf("نيوكاسل", "نيوكاسل يونايتد"), "132" to listOf("بايرن ميونخ", "بايرن ميونيخ"),
        "124" to listOf("بوروسيا دورتموند", "دورتموند"), "131" to listOf("باير ليفركوزن", "ليفركوزن"),
        "160" to listOf("باريس سان جيرمان", "باريس سان جيرمان"), "111" to listOf("يوفنتوس"),
        "110" to listOf("إنتر ميلان", "انتر ميلان", "إنتر", "انتر"), "103" to listOf("ميلان", "ايه سي ميلان"),
        "114" to listOf("نابولي"), "104" to listOf("روما", "أس روما"), "1929" to listOf("بنفيكا"),
        "437" to listOf("بورتو"), "2250" to listOf("سبورتينغ لشبونة", "سبورتنج لشبونة", "سبورتينج لشبونة"),
        "929" to listOf("الهلال"), "817" to listOf("النصر"), "2276" to listOf("الاتحاد"), "8346" to listOf("الأهلي", "الاهلي"),
        "362" to listOf("أستون فيلا", "استون فيلا"), "112" to listOf("لاتسيو"), "105" to listOf("أتالانتا", "اتالانتا"),
        "243" to listOf("إشبيلية", "اشبيلية"), "102" to listOf("فياريال"), "93" to listOf("أتلتيك بيلباو", "اتلتيك بيلباو"),
    ).mapValues { (_, v) -> v.map(::normalize) }

    /** The channels listed for [m], or empty when no fixture on the page is clearly the same match. */
    fun channelsFor(m: Match, entries: List<Entry>, matchDate: LocalDate): List<String> {
        val homeAliases = aliases[m.home.id]
        val awayAliases = aliases[m.away.id]
        if (homeAliases == null && awayAliases == null) return emptyList()
        return entries.firstOrNull { e ->
            val days = kotlin.math.abs(e.date.toEpochDay() - matchDate.toEpochDay())
            if (days > 1) return@firstOrNull false
            val names = e.teams.map(::normalize)
            fun hit(a: List<String>?) = a != null && names.any { it in a }
            val h = hit(homeAliases)
            val a = hit(awayAliases)
            // Both known clubs must appear; when only one is in the catalogue, it must appear and the other must not be.
            when {
                homeAliases != null && awayAliases != null -> h && a
                homeAliases != null -> h
                else -> a
            }
        }?.channels.orEmpty()
    }
}
