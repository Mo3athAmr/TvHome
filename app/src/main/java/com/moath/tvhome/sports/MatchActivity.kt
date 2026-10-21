package com.moath.tvhome.sports

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moath.tvhome.design.LiquidGlass.liquidGlass
import com.moath.tvhome.design.TvColors
import com.moath.tvhome.design.TvFonts
import com.moath.tvhome.design.TvShapes
import com.moath.tvhome.design.UiDirection
import com.moath.tvhome.prefs.LauncherPrefs
import com.moath.tvhome.ui.Crest
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One football match: score and minute, goals and cards, the lineups, the channel. */
class MatchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        val league = intent.getStringExtra(EXTRA_LEAGUE).orEmpty()
        val repo = SportsRepository.get(this)
        val prefs = LauncherPrefs.get(this)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides UiDirection.layout) {
                val settings by prefs.state.collectAsState()
                MatchScreen(
                    id, league, repo,
                    SportsConfig(settings.sportsLeagues, settings.sportsClubs),
                    onOpen = ::open,
                    onExit = ::finish,
                )
            }
        }
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No activity for $intent", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "Not allowed: $intent", e)
        }
    }

    companion object {
        const val EXTRA_ID = "match_id"
        const val EXTRA_LEAGUE = "match_league"
        private const val TAG = "MatchPage"
    }
}

private val LiveRed = Color(0xFFFF453A)
private val YellowCard = Color(0xFFFFD60A)
private val Zone: ZoneId = ZoneId.of("Asia/Riyadh")

private fun text(size: Int, color: Color = TvColors.Label, weight: FontWeight = FontWeight.Medium, align: TextAlign = TextAlign.Start) =
    TextStyle(fontFamily = TvFonts.Text, fontSize = size.sp, fontWeight = weight, color = color, textAlign = align)

@Composable
private fun MatchScreen(
    id: String,
    league: String,
    repo: SportsRepository,
    cfg: SportsConfig,
    onOpen: (Intent) -> Unit,
    onExit: () -> Unit,
) {
    var match by remember { mutableStateOf(repo.find(id)) }
    var details by remember { mutableStateOf<MatchDetails?>(null) }
    LaunchedEffect(id) {
        while (true) {
            repo.refreshLive(setOf(league))
            repo.find(id)?.let { m ->
                match = m
                repo.details(m)?.let { details = it }
            }
            delay(30_000)
        }
    }
    BackHandler(onBack = onExit)
    val m = match
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF16181E), Color(0xFF050506)))),
    ) {
        if (m == null) {
            BasicText("المباراة غير متاحة الآن", style = text(22, TvColors.SecondaryLabel), modifier = Modifier.align(Alignment.Center))
            return@Box
        }
        val card = MatchCard.of(m, cfg, System.currentTimeMillis())
        val kickoff = Instant.ofEpochMilli(m.kickoffMs).atZone(Zone)
        Column(
            Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(
                listOf(card.leagueName, "${MatchCard.dayLabel(kickoff.toLocalDate(), Instant.now().atZone(Zone).toLocalDate())} ${kickoff.format(HOUR)}").joinToString("  ·  "),
                style = text(15, TvColors.SecondaryLabel, align = TextAlign.Center),
            )
            val place = listOfNotNull(m.venue, m.city).joinToString("، ")
            if (place.isNotEmpty()) BasicText(place, style = text(13, TvColors.TertiaryLabel, align = TextAlign.Center))
            Spacer(Modifier.height(6.dp))
            Scoreboard(m, card)
            Spacer(Modifier.height(6.dp))
            InfoPills(m, card, details)
            Spacer(Modifier.height(8.dp))
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PitchPanel(card.home.name, details?.lineups?.home, home = true, modifier = Modifier.weight(1f))
                    Events(m, details?.events, Modifier.weight(1.05f))
                    PitchPanel(card.away.name, details?.lineups?.away, home = false, modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(10.dp))
            Buttons(m, onOpen)
        }
    }
}

private val HOUR: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

@Composable
private fun Scoreboard(m: Match, card: MatchCard) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Team(card.home, Modifier.width(220.dp), m.home.logo)
            Column(Modifier.width(220.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(card.center, style = text(44, TvColors.Label, FontWeight.Bold, TextAlign.Center))
                BasicText(card.sub, style = text(18, if (card.live) LiveRed else TvColors.SecondaryLabel, FontWeight.SemiBold, TextAlign.Center))
            }
            Team(card.away, Modifier.width(220.dp), m.away.logo)
        }
    }
}

@Composable
private fun Team(c: TeamTag, modifier: Modifier, logo: String?) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Crest(logo, 70.dp)
        Spacer(Modifier.height(6.dp))
        BasicText(c.name, style = text(16, TvColors.Label, FontWeight.SemiBold, TextAlign.Center), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun InfoPills(m: Match, card: MatchCard, d: MatchDetails?) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        card.channel?.let { Pill("القناة: $it") }
        Pill("الحكم: " + (d?.referee ?: "غير معلن"))
        d?.attendance?.let { Pill("الحضور: " + NumberFormat.getIntegerInstance(Locale.US).format(it)) }
    }
}

@Composable
private fun Pill(s: String) {
    BasicText(
        s,
        style = text(13, TvColors.SecondaryLabel),
        maxLines = 1,
        modifier = Modifier.liquidGlass(TvShapes.Capsule, intensity = 0.3f).padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Composable
private fun Panel(title: String, modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxHeight().liquidGlass(RoundedCornerShape(16.dp), intensity = 0.3f).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        BasicText(title, style = text(13, TvColors.TertiaryLabel, FontWeight.SemiBold), maxLines = 1)
        content()
    }
}

private val PitchDark = Color(0xFF1B6B38)
private val PitchLight = Color(0xFF1F7A40)

/** A starting eleven on a pitch: goalkeeper at the bottom, attack upward; each player a numbered circle with the name below. */
@Composable
private fun PitchPanel(title: String, lineup: Lineup?, home: Boolean, modifier: Modifier) {
    Column(modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(
            // The formation ("4-3-3") sits in an LTR isolate so the right-to-left line does not reorder its digits.
            listOfNotNull(title, lineup?.formation?.let { "\u2066$it\u2069" }, if (lineup?.predicted == true) "متوقعة" else null).joinToString("  ·  "),
            style = text(13, TvColors.SecondaryLabel, FontWeight.SemiBold),
            maxLines = 1,
        )
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PitchDark),
        ) {
            val w = maxWidth
            val h = maxHeight
            Canvas(Modifier.fillMaxSize()) {
                val stripe = size.height / 8
                for (i in 0 until 8 step 2) drawRect(PitchLight, Offset(0f, i * stripe), Size(size.width, stripe))
                val line = Color.White.copy(alpha = 0.45f)
                val sw = 1.5.dp.toPx()
                val inset = 6.dp.toPx()
                drawRect(line, Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset), style = Stroke(sw))
                drawLine(line, Offset(inset, size.height / 2), Offset(size.width - inset, size.height / 2), sw)
                drawCircle(line, 22.dp.toPx(), Offset(size.width / 2, size.height / 2), style = Stroke(sw))
                val boxW = size.width * 0.5f
                val boxH = size.height * 0.15f
                drawRect(line, Offset((size.width - boxW) / 2, inset), Size(boxW, boxH), style = Stroke(sw))
                drawRect(line, Offset((size.width - boxW) / 2, size.height - inset - boxH), Size(boxW, boxH), style = Stroke(sw))
            }
            if (lineup == null) {
                BasicText("التشكيلة لم تُعلن بعد", style = text(13, Color.White.copy(alpha = 0.85f), align = TextAlign.Center), modifier = Modifier.align(Alignment.Center))
            } else {
                lineup.players.forEach { p ->
                    val px = w * (1f - p.x)
                    val py = h * (1f - p.y)
                    Column(
                        Modifier.width(60.dp).offset(x = px - 30.dp, y = py - 17.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier
                                .size(23.dp)
                                .background(if (home) Color.White else Color(0xFF16161A), CircleShape)
                                .border(1.5.dp, if (home) Color(0x33000000) else Color.White, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            BasicText(p.number, style = text(11, if (home) Color(0xFF111111) else Color.White, FontWeight.Bold, TextAlign.Center), maxLines = 1)
                        }
                        BasicText(
                            p.name,
                            style = text(10, Color.White, FontWeight.SemiBold, TextAlign.Center).copy(shadow = Shadow(Color(0xCC000000), Offset(0f, 1f), 3f)),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Events(m: Match, events: List<MatchEvent>?, modifier: Modifier) {
    Panel("الأهداف والبطاقات", modifier) {
        if (events.isNullOrEmpty()) {
            BasicText(if (m.state == MatchState.Upcoming) "لم تبدأ المباراة بعد" else "لا أحداث بعد", style = text(13, TvColors.SecondaryLabel))
        } else {
            events.takeLast(10).forEach { e ->
                val side = if (e.clubId == m.away.id) m.away.abbr else m.home.abbr
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText(e.minute, style = text(12, TvColors.TertiaryLabel, FontWeight.SemiBold, TextAlign.End), modifier = Modifier.width(44.dp))
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.size(width = if (e.kind == EventKind.Goal) 11.dp else 9.dp, height = 11.dp).background(
                            when (e.kind) {
                                EventKind.Goal -> TvColors.Label
                                EventKind.Yellow -> YellowCard
                                else -> LiveRed
                            },
                            if (e.kind == EventKind.Goal) RoundedCornerShape(6.dp) else RoundedCornerShape(2.dp),
                        ),
                    )
                    Spacer(Modifier.width(8.dp))
                    BasicText(
                        e.player.ifEmpty { if (e.kind == EventKind.Goal) "هدف" else "بطاقة" },
                        style = text(13, TvColors.Label),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    BasicText(side, style = text(11, TvColors.TertiaryLabel), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun Buttons(m: Match, onOpen: (Intent) -> Unit) {
    val first = remember { FocusRequester() }
    val context = LocalContext.current
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val query = "${m.home.name} vs ${m.away.name} highlights"
    // Casper Flix carries the beIN/SSC channels; it has no deep links, so this opens the app itself.
    val casper = remember { CasperFlix.launchIntent(context)?.let { it to CasperFlix.icon(context) } }
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (casper != null) {
            val label = "شاهد على Casper Flix" + (m.tv.firstOrNull()?.let { " · $it" } ?: "")
            ActionButton(label, Modifier.focusRequester(first), icon = casper.second) {
                CasperFlix.watch(context, m.tv.firstOrNull())
            }
        }
        ActionButton("ملخص المباراة على يوتيوب", if (casper == null) Modifier.focusRequester(first) else Modifier) {
            onOpen(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

@Composable
private fun ActionButton(label: String, modifier: Modifier = Modifier, icon: androidx.compose.ui.graphics.ImageBitmap? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer { val s = if (focused) 1.08f else 1f; scaleX = s; scaleY = s }
            .then(if (focused) Modifier.background(TvColors.FocusedFill, TvShapes.Capsule) else Modifier.liquidGlass(TvShapes.Capsule, intensity = 0.4f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            androidx.compose.foundation.Image(icon, null, Modifier.size(22.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)))
            Spacer(Modifier.width(10.dp))
        }
        BasicText(label, style = text(15, if (focused) TvColors.FocusedLabel else TvColors.Label, FontWeight.SemiBold), maxLines = 1)
    }
}
