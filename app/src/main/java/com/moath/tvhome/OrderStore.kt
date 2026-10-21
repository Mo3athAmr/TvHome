package com.moath.tvhome

import android.content.Context

/** Persists the user's arrangement. Apps it has never seen are appended in a sensible default order. */
class OrderStore(context: Context) {
    private val prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE)

    fun arrange(apps: List<AppEntry>): List<AppEntry> {
        val saved = prefs.getString(KEY, null)?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()
        val byKey = apps.associateBy { it.key }
        val known = saved.mapNotNull { byKey[it] }
        val fresh = apps.filter { it.key !in saved }.sortedBy { defaultRank(it) }
        return known + fresh
    }

    fun save(apps: List<AppEntry>) {
        prefs.edit().putString(KEY, apps.joinToString("\n") { it.key }).apply()
    }

    private fun defaultRank(app: AppEntry): Int {
        val idx = PREFERRED.indexOf(app.packageName)
        return if (idx >= 0) idx else PREFERRED.size
    }

    private companion object {
        const val KEY = "order"
        val PREFERRED = listOf(
            "com.google.android.youtube.tv",
            "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus",
            "com.apple.atve.androidtv.appletv",
            "com.android.vending",
            AppRepository.SETTINGS_KEY,
            AppRepository.SEARCH_KEY,
        )
    }
}
