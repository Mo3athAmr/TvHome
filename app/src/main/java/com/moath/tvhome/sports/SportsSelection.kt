package com.moath.tvhome.sports

/** Which fixtures reach the shelf and the widget. Pure, so the rules are unit-tested. */
object SportsSelection {
    const val WIDGET_MAX = 3
    const val ROW_MAX = 20
    private const val HOUR = 60L * 60 * 1000
    private val RIYADH: java.time.ZoneId = java.time.ZoneId.of("Asia/Riyadh")

    /** A fixture the owner follows: every match in "all matches" competitions, else any chosen club. */
    fun isFollowed(m: Match, cfg: SportsConfig): Boolean {
        val league = Leagues.bySlug(m.league) ?: return false
        if (m.league !in cfg.leagues) return false
        if (league.allMatches) return true
        return m.involvedIds.any { it in Clubs.priority || it in cfg.clubs }
    }

    fun involvesPriority(m: Match): Boolean = m.involvedIds.any { it in Clubs.priority }

    /** Shown in the widget: Real Madrid / Barcelona in any competition, plus Champions League's big matches. */
    fun isWidgetWorthy(m: Match, cfg: SportsConfig): Boolean =
        m.league in cfg.leagues && (involvesPriority(m) || (m.league == "uefa.champions" && isFollowed(m, cfg)))

    private fun isRecentOrSoon(m: Match, now: Long, backH: Long, aheadH: Long): Boolean = when (m.state) {
        MatchState.Live -> true
        MatchState.Upcoming -> m.kickoffMs in (now - HOUR)..(now + aheadH * HOUR)
        MatchState.Finished -> now - m.kickoffMs in 0..(backH * HOUR)
        MatchState.Other -> false
    }

    /** Priority clubs first, then live ones, then by kickoff. */
    private fun ranking(): Comparator<Match> = compareBy<Match>(
        { if (involvesPriority(it)) 0 else 1 },
        { if (it.state == MatchState.Live) 0 else 1 },
        { it.kickoffMs },
    )

    /** The shelf: live, finished in the last 24 h, and everything in the next 7 days. */
    fun rowMatches(all: List<Match>, cfg: SportsConfig, now: Long): List<Match> =
        all.filter { isFollowed(it, cfg) && isRecentOrSoon(it, now, backH = 24, aheadH = 7 * 24) }
            .sortedWith(ranking()).take(ROW_MAX)

    /**
     * The widget: today's matches only (Riyadh date), up to three. A live match, or one with Real Madrid or
     * Barcelona, comes first; then the rest by kickoff, finished ones last. Nothing today → no widget.
     */
    fun widgetMatches(all: List<Match>, cfg: SportsConfig, now: Long): List<Match> {
        val today = java.time.Instant.ofEpochMilli(now).atZone(RIYADH).toLocalDate()
        fun isToday(m: Match) = m.state == MatchState.Live || java.time.Instant.ofEpochMilli(m.kickoffMs).atZone(RIYADH).toLocalDate() == today
        return all.filter { isWidgetWorthy(it, cfg) && it.state != MatchState.Other && isToday(it) }
            .sortedWith(
                compareBy<Match>(
                    { if (it.state == MatchState.Live || involvesPriority(it)) 0 else 1 },
                    { if (it.state == MatchState.Live) 0 else 1 },
                    { if (involvesPriority(it)) 0 else 1 },
                    { if (it.state == MatchState.Finished) 1 else 0 },
                    { it.kickoffMs },
                ),
            ).take(WIDGET_MAX)
    }

    /** True when something is on or about to start, so live polling is worth the requests. */
    fun needsLivePolling(all: List<Match>, now: Long): Boolean = all.any {
        it.state == MatchState.Live || (it.state == MatchState.Upcoming && it.kickoffMs in (now - HOUR)..(now + HOUR / 2))
    }
}
