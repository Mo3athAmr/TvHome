package com.moath.tvhome.sports

enum class MatchState { Upcoming, Live, Finished, Other }

data class Club(val id: String, val name: String, val abbr: String, val logo: String?)

/** One fixture as the shelf and widget need it. Everything comes from ESPN's public scoreboard feed. */
data class Match(
    val id: String,
    /** ESPN league slug, e.g. "uefa.champions". */
    val league: String,
    val leagueName: String,
    val kickoffMs: Long,
    val state: MatchState,
    /** Live: the clock ("67'", "HT"); finished: "FT"; otherwise empty. */
    val statusText: String,
    val home: Club,
    val away: Club,
    val homeScore: Int?,
    val awayScore: Int?,
    val venue: String?,
    val city: String?,
    /** Channels ESPN lists (US-centric); the Saudi channel comes from the owner's settings. */
    val broadcasts: List<String>,
    /** The MENA channels for this match, from [ChannelGuide] (e.g. "beIN SPORTS 2 HD"); empty when unknown. */
    val tv: List<String> = emptyList(),
) {
    val involvedIds: Set<String> get() = setOf(home.id, away.id)
}

/** A competition the owner can follow. [allMatches] = every fixture is shown, not only the big clubs'. */
data class LeagueInfo(val slug: String, val nameAr: String, val allMatches: Boolean, val defaultOn: Boolean)

object Leagues {
    val all = listOf(
        LeagueInfo("uefa.champions", "دوري أبطال أوروبا", allMatches = false, defaultOn = true),
        LeagueInfo("esp.1", "الدوري الإسباني", allMatches = false, defaultOn = true),
        LeagueInfo("eng.1", "الدوري الإنجليزي", allMatches = false, defaultOn = true),
        LeagueInfo("esp.copa_del_rey", "كأس ملك إسبانيا", allMatches = false, defaultOn = true),
        LeagueInfo("esp.super_cup", "كأس السوبر الإسباني", allMatches = true, defaultOn = true),
        LeagueInfo("fifa.cwc", "كأس العالم للأندية", allMatches = true, defaultOn = true),
        LeagueInfo("fifa.world", "كأس العالم", allMatches = true, defaultOn = true),
        LeagueInfo("uefa.europa", "الدوري الأوروبي", allMatches = false, defaultOn = false),
        LeagueInfo("ita.1", "الدوري الإيطالي", allMatches = false, defaultOn = false),
        LeagueInfo("ger.1", "الدوري الألماني", allMatches = false, defaultOn = false),
        LeagueInfo("fra.1", "الدوري الفرنسي", allMatches = false, defaultOn = false),
        LeagueInfo("ksa.1", "دوري روشن السعودي", allMatches = true, defaultOn = false),
    )
    val defaultSlugs: Set<String> = all.filter { it.defaultOn }.map { it.slug }.toSet()
    fun bySlug(slug: String): LeagueInfo? = all.firstOrNull { it.slug == slug }
}

/** A club the owner can pick. [big] clubs are always followed in leagues that only show big clubs. */
data class CatalogClub(val id: String, val nameAr: String, val big: Boolean)

object Clubs {
    const val REAL_MADRID = "86"
    const val BARCELONA = "83"

    /** Always first in the widget and the shelf. */
    val priority: Set<String> = setOf(REAL_MADRID, BARCELONA)

    val catalog = listOf(
        CatalogClub("86", "ريال مدريد", true),
        CatalogClub("83", "برشلونة", true),
        CatalogClub("1068", "أتلتيكو مدريد", true),
        CatalogClub("382", "مانشستر سيتي", true),
        CatalogClub("364", "ليفربول", true),
        CatalogClub("359", "أرسنال", true),
        CatalogClub("360", "مانشستر يونايتد", true),
        CatalogClub("363", "تشيلسي", true),
        CatalogClub("367", "توتنهام", true),
        CatalogClub("361", "نيوكاسل", true),
        CatalogClub("132", "بايرن ميونخ", true),
        CatalogClub("124", "بوروسيا دورتموند", true),
        CatalogClub("131", "باير ليفركوزن", true),
        CatalogClub("160", "باريس سان جيرمان", true),
        CatalogClub("111", "يوفنتوس", true),
        CatalogClub("110", "إنتر ميلان", true),
        CatalogClub("103", "ميلان", true),
        CatalogClub("114", "نابولي", true),
        CatalogClub("104", "روما", true),
        CatalogClub("1929", "بنفيكا", true),
        CatalogClub("437", "بورتو", true),
        CatalogClub("2250", "سبورتينغ لشبونة", true),
        CatalogClub("929", "الهلال", false),
        CatalogClub("817", "النصر", false),
        CatalogClub("2276", "الاتحاد", false),
        CatalogClub("8346", "الأهلي", false),
        CatalogClub("362", "أستون فيلا", false),
        CatalogClub("112", "لاتسيو", false),
        CatalogClub("105", "أتالانتا", false),
        CatalogClub("243", "إشبيلية", false),
        CatalogClub("102", "فياريال", false),
        CatalogClub("93", "أتلتيك بيلباو", false),
    )

    /** The default followed set: the big clubs. */
    val big: Set<String> = catalog.filter { it.big }.map { it.id }.toSet()

    fun nameAr(id: String): String? = catalog.firstOrNull { it.id == id }?.nameAr
}

/** What the owner chose in Customize → Matches. */
data class SportsConfig(
    val leagues: Set<String> = Leagues.defaultSlugs,
    /** The clubs the owner follows (ESPN team ids); Real Madrid and Barcelona are always first. */
    val clubs: Set<String> = Clubs.big,
)
