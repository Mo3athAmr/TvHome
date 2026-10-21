package com.moath.tvhome.tmdb

/**
 * Keeps clearly sexual content (hentai, ecchi, porn, softcore, erotica) out of every shelf, search
 * result and title page. It is deliberately narrower than "rated 18+": violent or mature mainstream
 * titles stay. TMDB only sets its `adult` flag on explicit films, so animated and TV titles are
 * caught by their keywords (found via TMDB's keyword search) and, as a last net, by their text.
 */
object NsfwFilter {
    /** TMDB keyword ids: hentai, ecchi, porn*, softcore, erotic/erotica*, sexploitation, hardcore. */
    val KEYWORD_IDS: Set<Int> = setOf(
        198385, 384581, // hentai, hentai adaptation
        195669, 285672, // ecchi, etchi
        385955, 155139, 7344, 15197, 445, 238355, 6443, 378816, // porn, parody, star, director, pornography, gay, child, animated porn
        155477, 382870, // softcore, gay softcore
        256466, 155691, 302868, 298666, 325693, 350793, 334900, 238059, // erotic*, erotica*
        10053, 335048, // sexploitation
        375524, // hardcore
    )

    /** For discover queries: `without_keywords` with `|` means "none of these". */
    val WITHOUT_KEYWORDS: String = KEYWORD_IDS.joinToString("|")

    private val text = Regex("""\b(hentai|ecchi|porn\w*|xxx|erotic\w*|softcore)\b""", RegexOption.IGNORE_CASE)

    fun blockedByText(title: String, overview: String): Boolean =
        text.containsMatchIn(title) || text.containsMatchIn(overview)

    fun blockedByKeywords(ids: Collection<Int>): Boolean = ids.any { it in KEYWORD_IDS }
}
