package com.moath.tvhome.tmdb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NsfwFilterTest {
    @Test fun `keywords of the overflow anime are blocked`() =
        // romance, adult humor, based on manga, schoolgirl, softcore, adult animation, ecchi, hentai, anime, erotic, animated porn
        assertTrue(NsfwFilter.blockedByKeywords(listOf(9840, 11192, 13141, 41172, 155477, 161919, 195669, 198385, 210024, 256466, 378816)))

    @Test fun `mainstream mature keywords are not blocked`() {
        // adult animation, nudity, female nudity, violence-ish genres must stay
        assertFalse(NsfwFilter.blockedByKeywords(listOf(161919, 281741, 359980, 9840)))
    }

    @Test fun `text net catches explicit words only`() {
        assertTrue(NsfwFilter.blockedByText("Some Hentai Collection", ""))
        assertTrue(NsfwFilter.blockedByText("Title", "an erotic story of..."))
        assertTrue(NsfwFilter.blockedByText("XXX", ""))
        assertFalse(NsfwFilter.blockedByText("Overflow Of The Sea", "a drama about a flood"))
    }

    @Test fun `discover exclusion lists every keyword`() =
        assertTrue(NsfwFilter.KEYWORD_IDS.all { NsfwFilter.WITHOUT_KEYWORDS.split('|').contains(it.toString()) })
}
