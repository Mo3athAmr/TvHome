package com.moath.tvhome.sports

import androidx.compose.runtime.Immutable
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Immutable
data class TeamTag(val logo: String?, val abbr: String, val name: String)

/** A match as the shelf card, the widget and the match page display it. */
@Immutable
data class MatchCard(
    val id: String,
    val league: String,
    val leagueName: String,
    val home: TeamTag,
    val away: TeamTag,
    /** "2 - 1" once it has started, otherwise the kickoff time ("22:00"). */
    val center: String,
    /** The minute while live, "انتهت", or the day ("اليوم", "غداً", weekday). */
    val sub: String,
    val live: Boolean,
    val state: MatchState,
    /** "beIN SPORTS 2 HD" (several are joined with " / "), or null when no channel is known. */
    val channel: String?,
) {
    companion object {
        private val zone: ZoneId = ZoneId.of("Asia/Riyadh")
        private val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

        fun of(m: Match, cfg: SportsConfig, now: Long): MatchCard {
            val kickoff = Instant.ofEpochMilli(m.kickoffMs).atZone(zone)
            val center = when (m.state) {
                MatchState.Upcoming -> clock.format(kickoff)
                MatchState.Live, MatchState.Finished -> "${m.homeScore ?: 0} - ${m.awayScore ?: 0}"
                MatchState.Other -> "—"
            }
            val sub = when (m.state) {
                MatchState.Live -> if (m.statusText == "HT") "استراحة" else m.statusText
                MatchState.Finished -> "انتهت"
                MatchState.Other -> "مؤجلة"
                MatchState.Upcoming -> dayLabel(kickoff.toLocalDate(), Instant.ofEpochMilli(now).atZone(zone).toLocalDate())
            }
            return MatchCard(
                id = m.id,
                league = m.league,
                leagueName = Leagues.bySlug(m.league)?.nameAr ?: m.leagueName,
                home = TeamTag(m.home.logo, m.home.abbr, Clubs.nameAr(m.home.id) ?: m.home.name),
                away = TeamTag(m.away.logo, m.away.abbr, Clubs.nameAr(m.away.id) ?: m.away.name),
                center = center,
                sub = sub,
                live = m.state == MatchState.Live,
                state = m.state,
                channel = m.tv.takeIf { it.isNotEmpty() }?.joinToString(" / "),
            )
        }

        fun dayLabel(day: java.time.LocalDate, today: java.time.LocalDate): String = when (day) {
            today -> "اليوم"
            today.plusDays(1) -> "غداً"
            else -> DayOfWeek.from(day).getDisplayName(TextStyle.FULL, Locale("ar"))
        }
    }
}
