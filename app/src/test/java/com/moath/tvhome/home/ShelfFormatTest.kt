package com.moath.tvhome.home

import com.moath.tvhome.tmdb.TmdbItem
import org.junit.Assert.assertEquals
import org.junit.Test

class ShelfFormatTest {
    private fun item(
        id: Long = 1,
        type: String = "tv",
        year: String? = "2024",
        genres: List<String> = listOf("دراما", "جريمة", "غموض"),
        backdrop: String? = "/b.jpg",
    ) = TmdbItem(id, type, "T$id", "", backdrop, null, year, genres, 7.0)

    @Test fun `meta reads like tvOS with at most two genres`() =
        assertEquals("مسلسل · 2024 · دراما، جريمة", ShelfFormat.meta(item()))

    @Test fun `meta skips missing parts`() =
        assertEquals("فيلم", ShelfFormat.meta(item(type = "movie", year = null, genres = emptyList())))

    @Test fun `interleave alternates and keeps leftovers`() =
        assertEquals(listOf(1, 10, 2, 3), ShelfFormat.interleave(listOf(1, 2, 3), listOf(10)))

    @Test fun `usable drops items without artwork, people and duplicates`() {
        val out = ShelfFormat.usable(
            listOf(item(1), item(1), item(2, backdrop = null), item(3, type = "person"), item(4, type = "movie")),
        )
        assertEquals(listOf("tv/1", "movie/4"), out.map { "${it.mediaType}/${it.id}" })
    }

    @Test fun `usable caps the shelf`() =
        assertEquals(ShelfFormat.MAX_ITEMS, ShelfFormat.usable((1L..20L).map { item(it) }).size)
}
