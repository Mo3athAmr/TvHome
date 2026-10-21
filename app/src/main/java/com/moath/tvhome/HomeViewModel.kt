package com.moath.tvhome

import android.app.Application
import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moath.tvhome.home.Bar
import com.moath.tvhome.home.CardStyle
import com.moath.tvhome.home.ContentRow
import com.moath.tvhome.home.ContentRepository
import com.moath.tvhome.home.HeroItem
import com.moath.tvhome.home.Providers
import com.moath.tvhome.home.ShelfCursor
import com.moath.tvhome.home.ShelfNav
import com.moath.tvhome.home.Tab
import com.moath.tvhome.home.TabPage
import com.moath.tvhome.home.Title
import com.moath.tvhome.home.Zone
import com.moath.tvhome.prefs.LauncherPrefs
import com.moath.tvhome.prefs.LauncherSettings
import com.moath.tvhome.search.SearchActivity
import com.moath.tvhome.settings.SettingsActivity
import com.moath.tvhome.sports.MatchCard
import com.moath.tvhome.sports.SportsConfig
import com.moath.tvhome.sports.SportsRepository
import com.moath.tvhome.sports.SportsSelection
import com.moath.tvhome.youtube.ArabicTime
import com.moath.tvhome.youtube.YoutubeRepository
import com.moath.tvhome.youtube.YoutubeVideo
import com.moath.tvhome.tmdb.TmdbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SheetAction(val label: String, val destructive: Boolean = false) {
    Move("نقل"),
    Hide("إخفاء التطبيق"),
    Info("معلومات التطبيق"),
    Uninstall("إلغاء التثبيت", destructive = true),
    Cancel("إلغاء"),
}

data class Sheet(val app: AppEntry, val actions: List<SheetAction>, val focus: Int = 0)

data class Nudge(val dir: Dir, val id: Long)

/**
 * Home: a floating tab bar (Search · Home · Movies · TV Shows · Apps · Settings) over per-tab
 * pages. All focus lives here as fine-grained snapshot state (see [TabPage]) so the UI only
 * recomposes the pieces a key press actually changes.
 */
class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = AppRepository(app)
    private val order = OrderStore(app)
    private val prefs = LauncherPrefs.get(app)
    private val tmdb = TmdbClient.get(app)
    private val content = ContentRepository(tmdb)
    private val youtube = YoutubeRepository.get(app)
    private val sports = SportsRepository.get(app)
    private val _intents = MutableSharedFlow<Intent>(extraBufferCapacity = 4)
    val intents: SharedFlow<Intent> = _intents
    private val _ticks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** One event per successful focus move, for the click sound. */
    val ticks: SharedFlow<Unit> = _ticks

    val pages: Map<Tab, TabPage> = Tab.entries.associateWith(::TabPage)
    var tab by mutableStateOf(Tab.Home)
        private set
    var barSlot by mutableIntStateOf(Bar.slotOf(Tab.Home))
        private set
    var apps by mutableStateOf<List<AppEntry>>(emptyList())
        private set
    var editing by mutableStateOf(false)
        private set
    var sheet by mutableStateOf<Sheet?>(null)
        private set
    var pressedKey by mutableStateOf<String?>(null)
        private set
    var nudge by mutableStateOf<Nudge?>(null)
        private set
    var resumed by mutableStateOf(true)
        private set
    var autoplay by mutableStateOf(true)
        private set
    /** Bumped on every key press; the UI's inactivity dim restarts from it. */
    var inputSeq by mutableIntStateOf(0)
        private set
    /** Direction of the last D-pad move (not observed): the newly focused card sways toward it. */
    var lastDir: Dir = Dir.Right
        private set

    val page: TabPage get() = pages.getValue(tab)

    /** Every installed app in the user's order, hidden ones included (they keep their place). */
    private var allApps: List<AppEntry> = emptyList()
    private var settings: LauncherSettings = prefs.state.value
    private var region: String = settings.contentRegion
    private val loads = HashMap<Tab, Job>()
    private var nudgeSeq = 0L

    // The Home page shows TMDB content plus the "new on YouTube" shelf; these hold the TMDB part.
    private var homeHero: List<HeroItem> = emptyList()
    private val contentCache = com.moath.tvhome.home.ContentCache(app)
    private var homeRows: List<ContentRow> = emptyList()
    private var youtubeVideos: List<YoutubeVideo> = emptyList()
    private var youtubePending = false
    private var lastYoutubeRefresh = 0L
    private var youtubeJob: Job? = null
    private var pendingHome: Pair<List<HeroItem>, List<ContentRow>>? = null
    private var sportsCards: List<MatchCard> = emptyList()
    private var sportsPending = false

    /** The widget next to the clock: up to three matches, Real Madrid and Barcelona first. */
    var widget by mutableStateOf<List<MatchCard>>(emptyList())
        private set

    init {
        autoplay = settings.topShelfAutoplay
        pages.getValue(Tab.Home).row = Zone.HERO
        reload()
        viewModelScope.launch { prefs.state.collect(::applySettings) }
        youtubeVideos = youtube.unseen(YOUTUBE_ROW_MAX)
        viewModelScope.launch {
            while (true) {
                if (resumed) refreshYoutube()
                delay(YOUTUBE_REFRESH_MS)
            }
        }
        publishSports(force = true)
        viewModelScope.launch {
            while (true) {
                if (resumed || sports.all().isEmpty()) refreshSportsSchedule()
                delay(SPORTS_SCHEDULE_MS)
            }
        }
        viewModelScope.launch {
            // Live scores keep coming while another app is in front (cheaply, only during a followed match),
            // so Home is never minutes behind when the owner comes back.
            while (true) {
                delay(if (resumed) SPORTS_LIVE_MS else SPORTS_LIVE_BACKGROUND_MS)
                pollSportsLive()
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(HOME_REFRESH_MS)
                if (resumed) refreshHome()
            }
        }
    }

    // ── Matches ──────────────────────────────────────────────────────────────────────────────

    private fun sportsConfig() = SportsConfig(settings.sportsLeagues, settings.sportsClubs)

    private fun refreshSportsSchedule() {
        viewModelScope.launch {
            val ok = sports.refreshSchedule(settings.sportsLeagues)
            val app = getApplication<Application>()
            if (ok) com.moath.tvhome.health.Health.recover(app, "feed.sports") else com.moath.tvhome.health.Health.report(app, "feed.sports", "ESPN لا يرد")
            publishSports()
            sports.refreshChannels()
            publishSports()
        }
    }

    /** During a match (or just before one) only the competitions that have one are re-read. */
    private var liveJob: kotlinx.coroutines.Job? = null

    private fun pollSportsLive() {
        val now = System.currentTimeMillis()
        val all = sports.all()
        if (!SportsSelection.needsLivePolling(all, now) || liveJob?.isActive == true) return
        val leagues = all.filter { it.state == com.moath.tvhome.sports.MatchState.Live || it.kickoffMs in (now - 3_600_000)..(now + 1_800_000) }
            .map { it.league }.filter { it in settings.sportsLeagues }.toSet()
        liveJob = viewModelScope.launch {
            sports.refreshLive(leagues)
            publishSports()
        }
    }

    /** Recomputes the shelf and the widget from the cache. A shelf appearing or vanishing waits while focus is in the rows. */
    private fun publishSports(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val cfg = sportsConfig()
        val all = sports.all()
        val shelf = if (settings.sportsRow) SportsSelection.rowMatches(all, cfg, now).map { MatchCard.of(it, cfg, now) } else emptyList()
        val strip = if (settings.sportsWidget) SportsSelection.widgetMatches(all, cfg, now).map { MatchCard.of(it, cfg, now) } else emptyList()
        if (strip != widget) widget = strip
        if (shelf == sportsCards && !force) return
        val structural = shelf.isEmpty() != sportsCards.isEmpty()
        val home = pages.getValue(Tab.Home)
        if (!force && structural && resumed && tab == Tab.Home && home.row >= 0) {
            sportsPending = true
            return
        }
        sportsCards = shelf
        sportsPending = false
        rebuildHome()
    }

    private fun matchTitle(c: MatchCard) = Title(
        id = MATCH_PREFIX + c.id,
        tmdbId = 0,
        mediaType = MATCH_TYPE,
        name = "${c.home.name} × ${c.away.name}",
        meta = c.leagueName,
        overview = "",
        heroUrl = null,
        cardUrl = null,
        posterUrl = null,
        match = c,
    )

    private fun openMatch(titleId: String, key: String) {
        val id = titleId.removePrefix(MATCH_PREFIX)
        val league = sportsCards.firstOrNull { it.id == id }?.league ?: return
        launch(
            key,
            Intent(getApplication(), com.moath.tvhome.sports.MatchActivity::class.java)
                .putExtra(com.moath.tvhome.sports.MatchActivity.EXTRA_ID, id)
                .putExtra(com.moath.tvhome.sports.MatchActivity.EXTRA_LEAGUE, league)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Re-reads the TMDB shelves (new releases change during the day). Held back while the user is in the rows. */
    private var lastHomeRefresh = 0L

    private fun refreshHome() {
        if (loads[Tab.Home]?.isActive == true) return
        lastHomeRefresh = System.currentTimeMillis()
        val reg = region
        val installed = allApps.mapTo(HashSet()) { it.packageName }
        loads[Tab.Home] = viewModelScope.launch {
            val hero = content.hero(Tab.Home, reg, installed)
            val rows = content.rows(Tab.Home, reg, installed)
            if (reg != region || (hero.isEmpty() && rows.isEmpty())) return@launch // keep what is on screen
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { contentCache.write(Tab.Home, reg, hero, rows) }
            pendingHome = hero to rows
            applyPendingHome()
        }
    }

    private fun applyPendingHome() {
        val next = pendingHome ?: return
        val home = pages.getValue(Tab.Home)
        if (resumed && tab == Tab.Home && home.row >= 0) return
        pendingHome = null
        homeHero = next.first
        homeRows = next.second
        rebuildHome()
    }

    // ── New on YouTube ───────────────────────────────────────────────────────────────────────

    /** Re-reads the feeds (every 12 minutes while Home is showing, and when Settings asks). */
    private fun refreshYoutube() {
        if (youtubeJob?.isActive == true) return
        youtubeJob = viewModelScope.launch {
            val ok = youtube.refresh()
            if (ok) lastYoutubeRefresh = System.currentTimeMillis()
            val app = getApplication<Application>()
            if (ok) com.moath.tvhome.health.Health.recover(app, "feed.youtube") else com.moath.tvhome.health.Health.report(app, "feed.youtube", "يوتيوب لا يرد")
            publishYoutube()
        }
    }

    /**
     * Shows the current unseen list. A change that would move rows under the user's focus waits
     * until focus is back on the bar or hero (or Home is resumed).
     */
    private fun publishYoutube(force: Boolean = false) {
        val next = youtube.unseen(YOUTUBE_ROW_MAX)
        if (next.map { it.id } == youtubeVideos.map { it.id } && !force) return
        val home = pages.getValue(Tab.Home)
        if (!force && resumed && tab == Tab.Home && home.row >= 0) {
            youtubePending = true
            return
        }
        youtubeVideos = next
        youtubePending = false
        rebuildHome()
    }

    private fun rebuildHome() {
        val p = pages.getValue(Tab.Home)
        val videos = youtubeVideos
        val useYoutubeHero = settings.heroSource == LauncherSettings.HERO_YOUTUBE && videos.isNotEmpty()
        val friday = com.moath.tvhome.quran.KahfTime.fridayOf(System.currentTimeMillis())
        kahfShown = friday?.takeIf { settings.kahfHero && it != kahfHiddenFor }
        val hero = listOfNotNull(kahfShown?.let { kahfHero() }) +
            (if (useYoutubeHero) videos.take(YOUTUBE_HERO_MAX).map(::youtubeHero) else homeHero)
        val rows = listOfNotNull(
            videos.takeIf { it.isNotEmpty() }?.let(::youtubeRow),
            sportsCards.takeIf { it.isNotEmpty() }?.let { ContentRow(SPORTS_ROW, "المباريات", CardStyle.Landscape, it.map(::matchTitle)) },
        ) + homeRows
        p.hero = hero
        p.heroPage = p.heroPage.coerceIn(0, (hero.size - 1).coerceAtLeast(0))
        p.rows = rows
        settleFocus(p)
    }

    private fun youtubeTitle(v: YoutubeVideo): Title {
        val thumb = "https://i.ytimg.com/vi/${v.id}/maxresdefault.jpg"
        val meta = listOf(v.channel, ArabicTime.ago(System.currentTimeMillis(), v.publishedMs)).filter { it.isNotEmpty() }
        return Title(
            id = YOUTUBE_PREFIX + v.id,
            tmdbId = 0,
            mediaType = YOUTUBE_TYPE,
            name = v.title,
            meta = meta.joinToString(" · "),
            overview = "",
            heroUrl = thumb,
            cardUrl = thumb,
            posterUrl = null,
        )
    }

    /** The Friday the Kahf page is showing for (null = not shown), and the one the owner hid. */
    private var kahfShown: String? = null
    private var kahfHiddenFor: String? = null

    private fun kahfHero(): HeroItem {
        val art = "file:///android_asset/kahf.jpg"
        val reciter = com.moath.tvhome.quran.QuranAudio.reciter(settings.kahfReciter)
        return HeroItem(
            title = Title(
                id = QURAN_TYPE + "/kahf",
                tmdbId = 0,
                mediaType = QURAN_TYPE,
                name = "سورة الكهف",
                meta = "بصوت الشيخ ${reciter.name} · العفاسي · المعيقلي · السديس",
                overview = "«من قرأ سورة الكهف في يوم الجمعة أضاء له من النور ما بين الجمعتين»",
                heroUrl = art,
                cardUrl = art,
                posterUrl = null,
            ),
            attribution = "جمعة مباركة 🌿",
            logoUrl = null,
            providerPackage = null,
        )
    }

    private fun youtubeHero(v: YoutubeVideo) = HeroItem(
        title = youtubeTitle(v),
        attribution = "جديد على يوتيوب",
        logoUrl = null,
        providerPackage = null,
    )

    private fun youtubeRow(videos: List<YoutubeVideo>) =
        ContentRow(YOUTUBE_ROW, "جديد يوتيوب", CardStyle.Landscape, videos.map(::youtubeTitle))

    /** Opens the video in YouTube and counts it as seen; Home refreshes when the user comes back. */
    private fun playYoutube(titleId: String) {
        val id = titleId.removePrefix(YOUTUBE_PREFIX)
        youtube.markSeen(id)
        youtubePending = true
        val url = Uri.parse("https://www.youtube.com/watch?v=$id")
        val app = installedApp(Providers.YOUTUBE_PACKAGE)
        val watch = Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (app != null) watch.setPackage(app.packageName)
        launch(KEY_PLAY, watch)
    }

    /** Hero's second button for a YouTube video: hide it and bring the next one up. */
    private fun dismissYoutube(titleId: String) {
        youtube.markSeen(titleId.removePrefix(YOUTUBE_PREFIX))
        publishYoutube(force = true)
        tick()
    }

    fun reload() {
        viewModelScope.launch {
            allApps = withContext(Dispatchers.Default) { order.arrange(repo.load()) }
            publishApps()
            ensureContent(Tab.Home)
            delay(PREFETCH_DELAY_MS)
            ensureContent(Tab.Movies)
            ensureContent(Tab.Shows)
        }
    }

    fun onResume() {
        resumed = true
        applySettings(prefs.state.value)
        // Scores first: a match that ended while the owner was away must not show the old minute.
        pollSportsLive()
        // New episodes and films: coming back after a while re-reads the two "latest" rows.
        if (System.currentTimeMillis() - lastHomeRefresh > HOME_REFRESH_MS) refreshHome()
        // Thursday's Maghrib brings the Kahf page; Friday's Maghrib takes it away.
        val friday = com.moath.tvhome.quran.KahfTime.fridayOf(System.currentTimeMillis())?.takeIf { settings.kahfHero && it != kahfHiddenFor }
        if (friday != kahfShown) rebuildHome()
        if (youtubePending) publishYoutube(force = true)
        applyPendingHome()
        if (System.currentTimeMillis() - lastYoutubeRefresh > YOUTUBE_STALE_MS) refreshYoutube()
        // Offline at boot: try again whenever Home comes back.
        if (allApps.isNotEmpty()) Tab.entries.forEach { if (isEmpty(pages.getValue(it))) ensureContent(it) }
    }

    fun onPause() {
        resumed = false
    }

    // ── Settings & content ───────────────────────────────────────────────────────────────────

    private fun applySettings(next: LauncherSettings) {
        val prev = settings
        settings = next
        autoplay = next.topShelfAutoplay
        if (next.heroSource != prev.heroSource || next.kahfHero != prev.kahfHero || next.kahfReciter != prev.kahfReciter) rebuildHome()
        if (next.sportsRow != prev.sportsRow || next.sportsWidget != prev.sportsWidget || next.sportsClubs != prev.sportsClubs ||
            next.sportsLeagues != prev.sportsLeagues || next.sportsRefresh != prev.sportsRefresh
        ) {
            publishSports(force = true)
            if (next.sportsLeagues != prev.sportsLeagues || next.sportsRefresh != prev.sportsRefresh) refreshSportsSchedule()
        }
        if (next.youtubeRefresh != prev.youtubeRefresh) {
            youtubeJob?.cancel()
            refreshYoutube()
        }
        if (next.hiddenApps != prev.hiddenApps) publishApps()
        if (next.contentRegion != region) {
            region = next.contentRegion
            loads.values.forEach { it.cancel() }
            loads.clear()
            Tab.entries.forEach { ensureContent(it, force = true) }
        }
    }

    /** Re-derives the visible apps from [allApps] minus hidden ones, keeping grid focus on the same app. */
    private fun publishApps() {
        val grid = pages.getValue(Tab.Apps)
        val focusKey = apps.getOrNull(grid.gridFocus)?.key
        val visible = allApps.filter { it.key !in settings.hiddenApps }
        val keyIndex = visible.indexOfFirst { it.key == focusKey }
        apps = visible
        grid.gridFocus = if (keyIndex >= 0) keyIndex else grid.gridFocus.coerceIn(0, (visible.size - 1).coerceAtLeast(0))
        editing = editing && visible.isNotEmpty()
        sheet = null
        pages.values.forEach(::settleFocus)
    }

    private fun isEmpty(p: TabPage) = when (p.tab) {
        Tab.Apps -> false
        Tab.Home -> homeHero.isEmpty() && homeRows.isEmpty()
        else -> p.hero.isEmpty() && p.rows.isEmpty()
    }

    /** Loads every content tab up front so switching tabs never waits on the network. */
    fun prefetchTabs() {
        for (t in Tab.entries) ensureContent(t)
    }

    private fun ensureContent(tab: Tab, force: Boolean = false) {
        if (tab == Tab.Apps) return
        val p = pages.getValue(tab)
        if (loads[tab]?.isActive == true) return
        if (!force && !isEmpty(p)) return
        val reg = region
        val installed = allApps.mapTo(HashSet()) { it.packageName }
        // The last copy from disk first: the page is full at once, and the network only runs when it's stale.
        val cached = if (isEmpty(p) && !force) contentCache.read(tab, reg) else null
        if (cached != null) {
            if (tab == Tab.Home) {
                homeHero = cached.hero
                homeRows = cached.rows
                rebuildHome()
            } else {
                p.hero = cached.hero
                p.rows = cached.rows
                p.heroPage = p.heroPage.coerceIn(0, (cached.hero.size - 1).coerceAtLeast(0))
            }
            p.heroLoading = false
            p.rowsLoading = false
            settleFocus(p)
            if (cached.fresh) return
        }
        if (isEmpty(p)) {
            p.heroLoading = true
            p.rowsLoading = true
        }
        loads[tab] = viewModelScope.launch {
            val hero = content.hero(tab, reg, installed)
            if (reg != region) return@launch
            if (tab == Tab.Home) {
                homeHero = hero
                rebuildHome()
            } else {
                p.hero = hero
                p.heroPage = p.heroPage.coerceIn(0, (hero.size - 1).coerceAtLeast(0))
            }
            p.heroLoading = false
            settleFocus(p)
            val rows = content.rows(tab, reg, installed)
            if (reg != region) return@launch
            if (tab == Tab.Home) {
                homeRows = rows
                rebuildHome()
            } else {
                p.rows = rows
            }
            p.rowsLoading = false
            settleFocus(p)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { contentCache.write(tab, reg, hero, rows) }
        }
    }

    /** Keeps a page's focus on something that exists after its content or the app list changed. */
    private fun settleFocus(p: TabPage) {
        if (p.tab == Tab.Apps) {
            if (apps.isEmpty() && p.row >= 0) p.row = Zone.BAR
            return
        }
        val rows = rowCount(p)
        when {
            p.row == Zone.HERO && !p.hasHero -> p.row = if (rows > 0) 0 else Zone.BAR
            p.row >= rows -> p.row = when {
                rows > 0 -> rows - 1
                p.hasHero -> Zone.HERO
                else -> Zone.BAR
            }
        }
    }

    // ── Rows of a page (the UI lays them out in this same order) ─────────────────────────────

    fun showsAppsRow(p: TabPage): Boolean = p.tab == Tab.Home && apps.isNotEmpty()

    private fun rowCount(p: TabPage): Int = (if (showsAppsRow(p)) 1 else 0) + p.rows.size

    private fun rowId(p: TabPage, row: Int): String =
        if (showsAppsRow(p)) {
            if (row == 0) APPS_ROW else p.rows[row - 1].id
        } else {
            p.rows[row].id
        }

    private fun rowSize(p: TabPage, row: Int): Int =
        if (showsAppsRow(p)) {
            if (row == 0) apps.size else p.rows[row - 1].items.size
        } else {
            p.rows[row].items.size
        }

    private fun titleAt(p: TabPage, row: Int, col: Int): Title? {
        val content = if (showsAppsRow(p)) p.rows.getOrNull(row - 1) else p.rows.getOrNull(row)
        return content?.items?.getOrNull(col)
    }

    // ── Input ────────────────────────────────────────────────────────────────────────────────

    fun onDirection(dir: Dir) {
        val p = page
        inputSeq++
        lastDir = dir
        when {
            sheet != null -> moveSheet(dir)
            editing -> moveApp(dir)
            p.row == Zone.BAR -> moveInBar(p, dir)
            p.tab == Tab.Apps -> moveInGrid(p, dir)
            p.row == Zone.HERO -> moveInHero(p, dir)
            else -> moveInRows(p, dir)
        }
        flushYoutube()
    }

    /** Applies a YouTube update that was held back while focus was inside the rows. */
    private fun flushYoutube() {
        if (youtubePending && resumed && tab == Tab.Home && page.row < 0) publishYoutube(force = true)
        if (sportsPending && resumed && tab == Tab.Home && page.row < 0) publishSports(force = true)
        applyPendingHome()
    }

    fun onSelect() {
        val p = page
        inputSeq++
        val s = sheet
        when {
            s != null -> runSheetAction(s.actions[s.focus], s.app)
            editing -> finishEditing()
            p.row == Zone.BAR -> selectInBar(p)
            p.tab == Tab.Apps -> apps.getOrNull(p.gridFocus)?.let { launch(it.key, it.launchIntent) }
            p.row == Zone.HERO -> selectHero(p)
            else -> selectInRows(p)
        }
    }

    fun onLongSelect() {
        val p = page
        inputSeq++
        when {
            sheet != null -> Unit
            editing -> openSheet(apps.getOrNull(p.gridFocus), fromEditing = true)
            p.tab == Tab.Apps && p.row >= 0 && apps.isNotEmpty() -> editing = true
            else -> focusedApp(p)?.let(::startMoving)
        }
    }

    fun onMenu() {
        val p = page
        inputSeq++
        when {
            sheet != null -> sheet = null
            p.tab == Tab.Apps && p.row >= 0 -> openSheet(apps.getOrNull(p.gridFocus), fromEditing = editing)
            else -> openSheet(focusedApp(p), fromEditing = false)
        }
    }

    /** Back unwinds one level and never leaves Home: content → tab bar → Home tab. */
    fun onBack() {
        val p = page
        inputSeq++
        when {
            sheet != null -> sheet = null
            editing -> finishEditing()
            p.row != Zone.BAR -> {
                p.row = Zone.BAR
                p.autoAdvances = 0
                barSlot = Bar.slotOf(tab)
                tick()
                flushYoutube()
            }
            barSlot != Bar.slotOf(tab) -> {
                barSlot = Bar.slotOf(tab)
                tick()
            }
            tab != Tab.Home -> {
                switchTab(Tab.Home)
                barSlot = Bar.slotOf(Tab.Home)
                tick()
            }
            // Already at the top of Home: Back starts the screen saver.
            else -> startScreensaver()
        }
    }

    private fun startScreensaver() {
        val app = getApplication<Application>()
        com.moath.tvhome.screensaver.Screensaver.apply(app)
        _intents.tryEmit(com.moath.tvhome.screensaver.Screensaver.startIntent())
    }

    /** Home pressed while already home: the Home tab, scrolled to the top, everything dismissed. */
    fun onHome() {
        inputSeq++
        // Home pressed on Home, already at the top with nothing open: the screen saver.
        val atTop = resumed && tab == Tab.Home && !editing && sheet == null && page.row < 0 && barSlot == Bar.slotOf(Tab.Home)
        if (atTop) return startScreensaver()
        if (editing) persistOrder(apps)
        editing = false
        sheet = null
        pressedKey = null
        switchTab(Tab.Home)
        barSlot = Bar.slotOf(Tab.Home)
        val home = page
        home.heroButton = ShelfNav.PLAY
        home.autoAdvances = 0
        home.row = when {
            home.hasHero -> Zone.HERO
            rowCount(home) > 0 -> 0
            else -> Zone.BAR
        }
    }

    /** Autoplay: one page per call; stops after one full loop so an idle screen goes still. */
    fun autoAdvance() {
        val p = page
        if (p.hero.size < 2) return
        p.heroPage = (p.heroPage + 1) % p.hero.size
        p.autoAdvances += 1
    }

    // ── Movement ─────────────────────────────────────────────────────────────────────────────

    private fun moveInBar(p: TabPage, dir: Dir) {
        when (dir) {
            Dir.Left, Dir.Right -> {
                val next = barSlot + dir.dx
                if (next !in 0 until Bar.COUNT) return nudge(dir)
                barSlot = next
                Bar.tabAt(next)?.let(::switchTab)
                tick()
            }
            Dir.Down -> enterContent(p)
            Dir.Up -> nudge(dir)
        }
    }

    private fun switchTab(next: Tab) {
        if (next == tab) return
        tab = next
        val p = pages.getValue(next)
        p.row = Zone.BAR
        p.autoAdvances = 0
        ensureContent(next)
    }

    private fun enterContent(p: TabPage) {
        val target = when {
            p.tab == Tab.Apps -> if (apps.isNotEmpty()) 0 else null
            p.hasHero -> Zone.HERO
            rowCount(p) > 0 -> 0
            else -> null
        } ?: return nudge(Dir.Down)
        if (p.tab == Tab.Apps) p.gridFocus = p.gridFocus.coerceIn(0, apps.size - 1)
        p.row = target
        barSlot = Bar.slotOf(p.tab)
        tick()
    }

    private fun leaveToBar(p: TabPage) {
        p.row = Zone.BAR
        barSlot = Bar.slotOf(p.tab)
        tick()
    }

    private fun moveInGrid(p: TabPage, dir: Dir) {
        if (dir == Dir.Up && p.gridFocus < GridNav.COLUMNS) return leaveToBar(p)
        val next = GridNav.move(p.gridFocus, apps.size, dir) ?: return nudge(dir)
        p.gridFocus = next
        tick()
    }

    private fun moveInHero(p: TabPage, dir: Dir) {
        when (dir) {
            Dir.Up -> leaveToBar(p)
            Dir.Down -> if (rowCount(p) > 0) focusRow(p, 0) else nudge(dir)
            Dir.Left, Dir.Right -> {
                if (p.hero.isEmpty()) return nudge(dir)
                val next = ShelfNav.move(ShelfCursor(p.heroPage, p.heroButton), p.hero.size, dir)
                    ?: return nudge(dir)
                // Paging by hand ends autoplay until focus leaves the hero (HIG: "until focused").
                if (next.page != p.heroPage) p.autoAdvances = p.hero.size
                p.heroPage = next.page
                p.heroButton = next.button
                tick()
            }
        }
    }

    private fun moveInRows(p: TabPage, dir: Dir) {
        val r = p.row
        when (dir) {
            Dir.Left, Dir.Right -> {
                val col = p.col(rowId(p, r))
                val next = col.intValue.coerceAtMost(rowSize(p, r) - 1) + dir.dx
                if (next !in 0 until rowSize(p, r)) return nudge(dir)
                col.intValue = next
                tick()
            }
            Dir.Up -> when {
                r > 0 -> focusRow(p, r - 1)
                p.hasHero -> {
                    p.row = Zone.HERO
                    p.heroButton = ShelfNav.PLAY
                    p.autoAdvances = 0
                    tick()
                }
                else -> leaveToBar(p)
            }
            Dir.Down -> if (r + 1 < rowCount(p)) focusRow(p, r + 1) else nudge(dir)
        }
    }

    private fun focusRow(p: TabPage, row: Int) {
        val col = p.col(rowId(p, row))
        col.intValue = col.intValue.coerceIn(0, (rowSize(p, row) - 1).coerceAtLeast(0))
        p.row = row
        tick()
    }

    private fun moveApp(dir: Dir) {
        val p = pages.getValue(Tab.Apps)
        val target = GridNav.move(p.gridFocus, apps.size, dir) ?: return nudge(dir)
        apps = GridNav.reorder(apps, p.gridFocus, target)
        p.gridFocus = target
        tick()
    }

    private fun moveSheet(dir: Dir) {
        val s = sheet ?: return
        val next = s.focus + dir.dy
        if (dir.dy != 0 && next in s.actions.indices) {
            sheet = s.copy(focus = next)
            tick()
        }
    }

    private fun tick() {
        _ticks.tryEmit(Unit)
    }

    private fun nudge(dir: Dir) {
        nudge = Nudge(dir, ++nudgeSeq)
    }

    // ── Selection ────────────────────────────────────────────────────────────────────────────

    private fun selectInBar(p: TabPage) {
        val app = getApplication<Application>()
        when (barSlot) {
            Bar.AVATAR -> launch(
                barKey(Bar.AVATAR),
                Intent(app, SettingsActivity::class.java)
                    .putExtra(SettingsActivity.EXTRA_PAGE, "customize")
                    .putExtra(SettingsActivity.EXTRA_STANDALONE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            Bar.SEARCH -> launch(barKey(Bar.SEARCH), Intent(app, SearchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Bar.SETTINGS -> launch(barKey(Bar.SETTINGS), Intent(app, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            else -> enterContent(p)
        }
    }

    private fun selectHero(p: TabPage) {
        val item = p.heroItem ?: return
        if (item.title.mediaType == QURAN_TYPE) {
            if (p.heroButton == ShelfNav.MORE_INFO) {
                kahfHiddenFor = kahfShown
                rebuildHome()
            } else {
                launch(KEY_PLAY, com.moath.tvhome.quran.QuranPlayerActivity.intent(getApplication()))
            }
            return
        }
        if (item.title.mediaType == YOUTUBE_TYPE) {
            if (p.heroButton == ShelfNav.MORE_INFO) dismissYoutube(item.title.id) else playYoutube(item.title.id)
            return
        }
        if (p.heroButton == ShelfNav.MORE_INFO) launch(KEY_MORE_INFO, productPage(item.title)) else play(item)
    }

    private fun selectInRows(p: TabPage) {
        val r = p.row
        val col = p.col(rowId(p, r)).intValue
        if (showsAppsRow(p) && r == 0) {
            apps.getOrNull(col)?.let { launch(it.key, it.launchIntent) }
            return
        }
        val title = titleAt(p, r, col) ?: return
        if (title.mediaType == YOUTUBE_TYPE) return playYoutube(title.id)
        if (title.mediaType == MATCH_TYPE) return openMatch(title.id, cardKey(rowId(p, r), title.id))
        launch(cardKey(rowId(p, r), title.id), productPage(title))
    }

    private fun focusedApp(p: TabPage): AppEntry? =
        if (showsAppsRow(p) && p.row == 0) apps.getOrNull(p.col(APPS_ROW).intValue) else null

    /** Play opens the streaming app it's featured on, else one that carries it, else its trailer. */
    private fun play(item: HeroItem) {
        installedApp(item.providerPackage)?.let { return launch(KEY_PLAY, it.launchIntent) }
        pressedKey = KEY_PLAY
        viewModelScope.launch {
            val t = item.title
            val ids = tmdb.watchProviders(t.tmdbId, t.mediaType, region)
                .ifEmpty { if (region != FALLBACK_REGION) tmdb.watchProviders(t.tmdbId, t.mediaType, FALLBACK_REGION) else emptyList() }
            val carrier = ids.firstNotNullOfOrNull { installedApp(Providers.packageFor(it)) }
            launch(KEY_PLAY, carrier?.launchIntent ?: trailer(t))
        }
    }

    private fun installedApp(pkg: String?): AppEntry? = pkg?.let { p -> allApps.firstOrNull { it.packageName == p } }

    /**
     * The title's trailer search on YouTube: its search intent if the installed YouTube takes one,
     * else the results URL in YouTube, else the browser, else just YouTube itself.
     */
    private fun trailer(t: Title): Intent {
        val query = "${t.name} trailer"
        val url = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))
        val youTube = installedApp(Providers.YOUTUBE_PACKAGE)
        val candidates = listOfNotNull(
            youTube?.let { Intent(Intent.ACTION_SEARCH).setPackage(it.packageName).putExtra(SearchManager.QUERY, query) },
            youTube?.let { Intent(Intent.ACTION_VIEW, url).setPackage(it.packageName) },
            Intent(Intent.ACTION_VIEW, url),
        )
        val pm = getApplication<Application>().packageManager
        val pick = candidates.firstOrNull { it.resolveActivity(pm) != null } ?: youTube?.launchIntent ?: candidates.last()
        return Intent(pick).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun productPage(t: Title): Intent =
        Intent(getApplication(), SearchActivity::class.java)
            .putExtra(EXTRA_TMDB_ID, t.tmdbId)
            .putExtra(EXTRA_TMDB_TYPE, t.mediaType)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // ── Editing & the action sheet ───────────────────────────────────────────────────────────

    private fun finishEditing() {
        persistOrder(apps)
        editing = false
    }

    /** Saves the visible order; hidden apps keep their relative order after it. */
    private fun persistOrder(visible: List<AppEntry>) {
        val visibleKeys = visible.mapTo(HashSet()) { it.key }
        allApps = visible + allApps.filter { it.key !in visibleKeys }
        order.save(allApps)
    }

    private fun openSheet(app: AppEntry?, fromEditing: Boolean) {
        app ?: return
        val actions = buildList {
            if (!fromEditing) add(SheetAction.Move)
            if (!app.isPseudo) add(SheetAction.Hide)
            if (!app.isPseudo) add(SheetAction.Info)
            if (app.isRemovable) add(SheetAction.Uninstall)
            add(SheetAction.Cancel)
        }
        sheet = Sheet(app, actions)
    }

    private fun runSheetAction(action: SheetAction, app: AppEntry) {
        sheet = null
        when (action) {
            SheetAction.Move -> startMoving(app)
            SheetAction.Hide -> {
                if (editing) finishEditing()
                prefs.update { it.copy(hiddenApps = it.hiddenApps + app.key) }
            }
            SheetAction.Info -> _intents.tryEmit(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            SheetAction.Uninstall -> {
                if (editing) finishEditing()
                _intents.tryEmit(
                    Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            SheetAction.Cancel -> Unit
        }
    }

    /** Moving happens in the Apps grid; from the Home row it jumps there with the app picked up. */
    private fun startMoving(app: AppEntry) {
        val index = apps.indexOfFirst { it.key == app.key }
        if (index < 0) return
        switchTab(Tab.Apps)
        barSlot = Bar.slotOf(Tab.Apps)
        val grid = pages.getValue(Tab.Apps)
        grid.gridFocus = index
        grid.row = 0
        editing = true
    }

    /** tvOS's highlighted state: the target dips and brightens for a beat, then the app opens. */
    private fun launch(pressKey: String, intent: Intent) {
        pressedKey = pressKey
        viewModelScope.launch {
            delay(PRESS_MS)
            _intents.emit(intent)
            delay(PRESS_MS)
            if (pressedKey == pressKey) pressedKey = null
        }
    }

    companion object {
        const val APPS_ROW = "apps"
        const val KEY_PLAY = "hero:play"
        const val KEY_MORE_INFO = "hero:info"
        const val EXTRA_TMDB_ID = "tmdb_id"
        const val EXTRA_TMDB_TYPE = "tmdb_type"
        private const val FALLBACK_REGION = "US"
        const val YOUTUBE_TYPE = "youtube"
        const val QURAN_TYPE = "quran"
        private const val YOUTUBE_PREFIX = "yt/"
        private const val YOUTUBE_ROW = "home/youtube"
        private const val YOUTUBE_HERO_MAX = 10
        private const val YOUTUBE_ROW_MAX = 20
        private const val YOUTUBE_REFRESH_MS = 12L * 60 * 1000
        private const val YOUTUBE_STALE_MS = 10L * 60 * 1000
        private const val HOME_REFRESH_MS = 10L * 60 * 1000
        private const val SPORTS_SCHEDULE_MS = 30L * 60 * 1000
        private const val SPORTS_LIVE_MS = 15L * 1000
        private const val SPORTS_LIVE_BACKGROUND_MS = 60L * 1000
        const val MATCH_TYPE = "match"
        private const val MATCH_PREFIX = "match/"
        private const val SPORTS_ROW = "home/matches"
        private const val PRESS_MS = 120L
        private const val PREFETCH_DELAY_MS = 4_000L

        fun barKey(slot: Int) = "bar:$slot"
        fun cardKey(rowId: String, titleId: String) = "card:$rowId/$titleId"
    }
}
