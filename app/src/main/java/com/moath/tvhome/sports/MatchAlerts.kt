package com.moath.tvhome.sports

import android.content.Context
import com.moath.tvhome.notify.NoticeKind
import com.moath.tvhome.notify.Notifier
import com.moath.tvhome.prefs.LauncherPrefs

/** A notice about one followed match: kickoff, a goal, or the final whistle. */
data class MatchAlert(val title: String, val body: String)

/**
 * Watches the followed matches from the system process and posts a notice when one kicks off,
 * someone scores, or it ends. Off unless the owner turned on Customize → Notifications → Matches.
 */
class MatchAlerts(private val context: Context) {
    private val seen = HashMap<String, Match>()

    private var lastSchedule = 0L
    private val state = context.getSharedPreferences("match_alerts", Context.MODE_PRIVATE)

    /** One poll; returns when the next one should run (ms). */
    suspend fun poll(now: Long = System.currentTimeMillis()): Long {
        val p = LauncherPrefs.get(context).state.value
        val repo = SportsRepository.get(context)
        val cfg = SportsConfig(p.sportsLeagues, p.sportsClubs)
        val wantsLive = p.notifications && p.notifyMatches
        val wantsSoon = p.notifications && p.matchReminderMin > 0
        if (!wantsLive && !wantsSoon) { seen.clear(); return IDLE_MS }
        if (now - lastSchedule > SCHEDULE_MS || repo.all().none { it.kickoffMs > now - DAY }) {
            if (repo.refreshSchedule(cfg.leagues)) lastSchedule = now
        }
        if (wantsSoon) remindSoon(repo.all(), cfg, p.matchReminderMin, now)
        if (!wantsLive) { seen.clear(); return IDLE_MS }
        val watch = repo.all().filter { SportsSelection.isFollowed(it, cfg) && isActive(it, now) }
        if (watch.isEmpty()) return IDLE_MS
        repo.refreshLive(watch.map { it.league }.toSet())
        for (m in repo.all().filter { SportsSelection.isFollowed(it, cfg) && isActive(it, now) || it.id in seen }) {
            val before = seen[m.id]
            seen[m.id] = m
            if (before == null) continue // first sighting: only changes from here on are news
            diff(before, m).forEach { Notifier.post(context, it.title, it.body, NoticeKind.MATCH, 10_000) }
        }
        seen.keys.retainAll(repo.all().filter { now - it.kickoffMs < 4 * HOUR }.map { it.id }.toSet())
        return if (watch.any { it.state == MatchState.Live }) LIVE_MS else SOON_MS
    }

    /** "In 15 minutes": every match of a club the owner added (Real Madrid and Barcelona always), once. */
    private fun remindSoon(all: List<Match>, cfg: SportsConfig, minutes: Int, now: Long) {
        val done = state.getStringSet("reminded", emptySet()).orEmpty()
        val due = soonMatches(all, cfg, minutes, now).filter { it.id !in done }
        if (due.isEmpty()) return
        for (m in due) {
            val a = reminder(m, minutes)
            Notifier.post(context, a.title, a.body, NoticeKind.MATCH_SOON, 15_000)
        }
        // Keep the list short: only ids of matches from the last two days.
        val recent = all.filter { now - it.kickoffMs < 2 * DAY }.map { it.id }.toSet()
        state.edit().putStringSet("reminded", (done.filter { it in recent } + due.map { it.id }).toSet()).apply()
    }

    private fun isActive(m: Match, now: Long) =
        m.state == MatchState.Live || (m.state == MatchState.Upcoming && m.kickoffMs - now in -3 * HOUR..10 * 60_000L)

    companion object {
        const val LIVE_MS = 60_000L
        const val SOON_MS = 120_000L
        const val IDLE_MS = 5 * 60_000L
        private const val SCHEDULE_MS = 3 * 3_600_000L
        private const val HOUR = 3_600_000L
        private const val DAY = 24 * HOUR

        private fun name(c: Club) = Clubs.nameAr(c.id) ?: c.name
        private fun score(m: Match) = "${name(m.home)} ${m.homeScore ?: 0} - ${m.awayScore ?: 0} ${name(m.away)}"

        /** Upcoming matches of the owner's clubs that kick off within [minutes]. Pure, for tests. */
        fun soonMatches(all: List<Match>, cfg: SportsConfig, minutes: Int, now: Long): List<Match> =
            all.filter { m ->
                m.state == MatchState.Upcoming && m.kickoffMs > now && m.kickoffMs - now <= minutes * 60_000L &&
                    m.involvedIds.any { it in cfg.clubs || it in Clubs.priority }
            }

        fun reminder(m: Match, minutes: Int): MatchAlert {
            val time = java.time.format.DateTimeFormatter.ofPattern("h:mm", java.util.Locale.US)
                .format(java.time.Instant.ofEpochMilli(m.kickoffMs).atZone(java.time.ZoneId.of("Asia/Riyadh")))
            val league = Leagues.bySlug(m.league)?.nameAr ?: m.leagueName
            val channel = m.tv.firstOrNull()?.let { " · $it" } ?: ""
            return MatchAlert("⏰ بعد $minutes دقيقة: ${name(m.home)} × ${name(m.away)}", "$league · الساعة $time$channel · شاهدها على Casper Flix")
        }

        /** What changed between two snapshots of the same match, as notices. Pure, for tests. */
        fun diff(before: Match, after: Match): List<MatchAlert> {
            val out = ArrayList<MatchAlert>()
            if (before.state == MatchState.Upcoming && after.state == MatchState.Live) {
                out += MatchAlert("بدأت المباراة ⚽", "${name(after.home)} × ${name(after.away)}" + (after.tv.firstOrNull()?.let { " — $it" } ?: ""))
            }
            val hb = before.homeScore ?: 0
            val ab = before.awayScore ?: 0
            val ha = after.homeScore ?: 0
            val aa = after.awayScore ?: 0
            if (after.state != MatchState.Upcoming && (ha > hb || aa > ab)) {
                val scorer = if (ha > hb) name(after.home) else name(after.away)
                out += MatchAlert("هدف لـ $scorer! 🎉", score(after) + (after.statusText.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""))
            }
            if (before.state == MatchState.Live && after.state == MatchState.Finished) {
                out += MatchAlert("انتهت المباراة 🏁", score(after))
            }
            return out
        }
    }
}
