package com.moath.tvhome.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LibraryTest {
    private val today = LocalDate.of(2026, 10, 21)

    @Test fun koreanHorrorSeriesUsesTvGenresAndLanguage() {
        val p = Library.params(LibraryQuery(kind = "tv", genres = listOf("horror"), origin = "korean"), "tv", today)
        assertNull(p["with_genres"])
        assertEquals("315058|6152|162846|12377|256183|186565", p["with_keywords"])
        assertEquals("ko", p["with_original_language"])
        assertEquals("10767,10763,10766,10764", p["without_genres"])
        assertEquals("false", p["include_adult"])
    }

    @Test fun animeAddsAnimationAndJapanese() {
        val p = Library.params(LibraryQuery(kind = "tv", genres = listOf("action"), origin = "anime"), "tv", today)
        assertEquals("10759,16", p["with_genres"])
        assertEquals("ja", p["with_original_language"])
    }

    @Test fun familyMoviesAreRatedPgAndClean() {
        val fam = Library.params(LibraryQuery(kind = "movie", family = true), "movie", today)
        assertEquals("PG", fam["certification.lte"])
        assertEquals("US", fam["certification_country"])
        assertEquals("PG-13", Library.params(LibraryQuery(clean = true), "movie", today)["certification.lte"])
        assertEquals("TV-PG", Library.params(LibraryQuery(family = true), "tv", today)["certification.lte"])
        assertNull(Library.params(LibraryQuery(), "movie", today)["certification.lte"])
    }

    @Test fun erasAndSorts() {
        val newest = Library.params(LibraryQuery(era = "new", sort = "newest"), "movie", today)
        assertEquals("2025-04-21", newest["primary_release_date.gte"])
        assertEquals("primary_release_date.desc", newest["sort_by"])
        assertEquals(LocalDate.of(1990, 1, 1) to LocalDate.of(1999, 12, 31), Library.eraRange("90s", today))
        assertEquals(LocalDate.of(2010, 1, 1) to LocalDate.of(2019, 12, 31), Library.eraRange("2010s", today))
        assertEquals(null to LocalDate.of(1999, 12, 31), Library.eraRange("classic", today))
        assertEquals(LocalDate.of(2000, 1, 1) to LocalDate.of(2010, 12, 31), Library.eraRange("2000-2010", today))
        assertEquals("vote_average.desc", Library.params(LibraryQuery(sort = "top_rated"), "tv", today)["sort_by"])
    }

    @Test fun askedTalkShowsAreNotExcluded() {
        val p = Library.params(LibraryQuery(genres = listOf("talk")), "tv", today)
        assertEquals("10763,10766,10764", p["without_genres"])
    }

    @Test fun picksRoundTrip() {
        val item = com.moath.tvhome.tmdb.TmdbItem(5, "tv", "ون بيس", "x", "/b.jpg", "/p.jpg", "1999", listOf("أنمي"), 8.7)
        assertEquals(listOf(item), Library.fromJson(Library.toJson(listOf(item))))
        assertTrue(Library.fromJson("not json").isEmpty())
    }

    @Test fun showMatchingIgnoresSpellingVariants() {
        assertTrue(YoutubeLookup.titleMatches("أنا أقطع | الحلقة 12 مع محمد عدنان", "انا اقطع"))
        assertFalse(YoutubeLookup.titleMatches("فلوق السفر الجديد", "انا اقطع"))
        assertFalse(YoutubeLookup.titleMatches("anything", ""))
    }

    @Test fun plainTextHistoryNeverShowsToolCalls() {
        val m = ChatMsg("assistant", "", toolCalls = listOf(ToolCall("1", "find_titles", org.json.JSONObject())))
        assertFalse(m.asPlainText().contains("find_titles"))
        assertFalse(ChatMsg("tool", "x", toolName = "find_titles").asPlainText().contains("find_titles"))
    }
}
