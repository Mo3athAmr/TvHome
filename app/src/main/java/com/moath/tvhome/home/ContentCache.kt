package com.moath.tvhome.home

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The last hero and rows of each tab, on disk. Home opens on them instantly instead of re-asking TMDB
 * (a few dozen requests with the safety checks) every time the launcher starts; they are refreshed in
 * the background once older than [FRESH_MS]. Matches are never stored here (they change by the minute).
 */
class ContentCache(context: Context) {
    private val dir = File(context.filesDir, "content").apply { mkdirs() }

    data class Entry(val hero: List<HeroItem>, val rows: List<ContentRow>, val savedAt: Long) {
        val fresh: Boolean get() = System.currentTimeMillis() - savedAt < FRESH_MS
    }

    private fun file(tab: Tab, region: String) = File(dir, "${tab.name}_$region.json")

    fun read(tab: Tab, region: String): Entry? = runCatching {
        val o = JSONObject(file(tab, region).readText())
        Entry(
            o.getJSONArray("hero").objects().map(::hero),
            o.getJSONArray("rows").objects().map(::row),
            o.getLong("at"),
        )
    }.getOrNull()

    fun write(tab: Tab, region: String, hero: List<HeroItem>, rows: List<ContentRow>) {
        if (hero.isEmpty() && rows.isEmpty()) return // Never overwrite good content with an offline failure.
        runCatching {
            val o = JSONObject()
                .put("at", System.currentTimeMillis())
                .put("hero", JSONArray().apply { hero.forEach { put(heroJson(it)) } })
                .put("rows", JSONArray().apply { rows.forEach { put(rowJson(it)) } })
            val tmp = File(dir, "${tab.name}_$region.tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(file(tab, region))
        }
    }

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    private fun titleJson(t: Title) = JSONObject()
        .put("id", t.id).put("tmdb", t.tmdbId).put("type", t.mediaType).put("name", t.name).put("meta", t.meta)
        .put("overview", t.overview).put("hero", t.heroUrl ?: "").put("card", t.cardUrl ?: "").put("poster", t.posterUrl ?: "")

    private fun title(o: JSONObject) = Title(
        id = o.getString("id"), tmdbId = o.getLong("tmdb"), mediaType = o.getString("type"), name = o.getString("name"),
        meta = o.optString("meta"), overview = o.optString("overview"),
        heroUrl = o.optString("hero").ifEmpty { null }, cardUrl = o.optString("card").ifEmpty { null }, posterUrl = o.optString("poster").ifEmpty { null },
    )

    private fun heroJson(h: HeroItem) = JSONObject().put("title", titleJson(h.title)).put("attr", h.attribution)
        .put("logo", h.logoUrl ?: "").put("pkg", h.providerPackage ?: "")

    private fun hero(o: JSONObject) = HeroItem(title(o.getJSONObject("title")), o.optString("attr"), o.optString("logo").ifEmpty { null }, o.optString("pkg").ifEmpty { null })

    private fun rowJson(r: ContentRow) = JSONObject().put("id", r.id).put("heading", r.heading).put("style", r.style.name)
        .put("items", JSONArray().apply { r.items.filter { it.match == null }.forEach { put(titleJson(it)) } })

    private fun row(o: JSONObject) = ContentRow(o.getString("id"), o.getString("heading"), CardStyle.valueOf(o.getString("style")), o.getJSONArray("items").objects().map(::title))

    companion object {
        /** A saved page shows at once, and is re-read from TMDB in the background when older than this. */
        const val FRESH_MS = 15 * 60_000L
    }
}
