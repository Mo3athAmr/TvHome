package com.moath.tvhome.system

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.moath.tvhome.AppEntry
import com.moath.tvhome.AppRepository
import com.moath.tvhome.OrderStore
import com.moath.tvhome.assistant.AssistantSession
import com.moath.tvhome.assistant.ScreenInfo
import com.moath.tvhome.notify.NoticeKind
import com.moath.tvhome.notify.Notifier
import com.moath.tvhome.prayer.AdhanController
import com.moath.tvhome.prayer.PrayerAlerts
import com.moath.tvhome.prayer.PrayerLockActivity
import com.moath.tvhome.prefs.LauncherPrefs
import com.moath.tvhome.screensaver.Screensaver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * System-wide tvOS chrome, drawn as accessibility overlays over any app:
 * hold HOME → Control Center, double-press HOME → App Switcher, single press → normal Home.
 */
class SystemUiService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope = MainScope()
    private lateinit var controls: SystemControls
    private lateinit var usage: UsageSource

    private val router = HomeKeyRouter(
        timers = object : HomeKeyRouter.Timers {
            override fun schedule(delayMs: Long, block: () -> Unit): HomeKeyRouter.Cancelable {
                val r = Runnable(block)
                main.postDelayed(r, delayMs)
                return HomeKeyRouter.Cancelable { main.removeCallbacks(r) }
            }
        },
        actions = object : HomeKeyRouter.Actions {
            override fun onSinglePress() = goHome()
            override fun onDoublePress() = showSwitcher()
            override fun onLongPress() = showControlCenter()
        },
    )

    private var overlay: OverlayWindow? = null
    private var overlayState: OverlayState? = null
    /** Key codes whose DOWN an overlay consumed: their UP must not leak to the app below. */
    private val swallowUps = HashSet<Int>()
    private var homeClosesOverlay = false
    /** Our own GLOBAL_ACTION_HOME may be re-delivered as an injected key; let that one through. */
    private var passHomeUntil = 0L

    /** Foreground packages seen this session, newest first — real recency without usage access. */
    private val session = ArrayDeque<Usage>()
    private val dismissedAt = HashMap<String, Long>()
    private var catalog: Deferred<List<AppEntry>>? = null

    private var notices: NoticeOverlay? = null
    private var casper: CasperTuner? = null
    private var stremio: StremioTuner? = null
    private var plate: RafiqPlate? = null
    private var turn: VoiceTurn? = null
    private val stremioReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { stremio?.pick(intent.getStringExtra(com.moath.tvhome.assistant.StremioFlow.EXTRA_LINK)) }
    }
    /** The app in front (ours included), from window events. */
    private var foreground: String? = null
    private val casperReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            casper?.tune(intent.getStringExtra(com.moath.tvhome.sports.CasperFlix.EXTRA_CHANNEL))
        }
    }
    /** Assistant button: press time, and whether the long-press (voice) already fired. */
    private var assistDown = false
    private var assistLongFired = false
    /** Until when Google's voice screen is expected (our search asked for it) and left alone. */
    private var ownVoiceUntil = 0L
    private var lastReplaceAt = 0L
    private val ownVoiceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            ownVoiceUntil = SystemClock.uptimeMillis() + OWN_VOICE_MS
        }
    }
    private val assistLong = Runnable {
        assistLongFired = true
        if (AssistantSession.visible) sendToPanel(toggle = true) else openAssistant(voice = true)
    }
    private var dreamOverlay: DreamOverlay? = null
    private var matchJob: kotlinx.coroutines.Job? = null

    /** Our widgets ride on top of Google's photo screen saver, which we cannot draw into. */
    private val dreamReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_DREAMING_STARTED -> {
                    val p = LauncherPrefs.get(context).state.value
                    val photos = Screensaver.current(context) == Screensaver.BACKDROP
                    if (photos && p.photoWidgets.isNotEmpty()) dreamOverlay?.show()
                }
                Intent.ACTION_DREAMING_STOPPED -> dreamOverlay?.hide()
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON) {
                AdhanController.onScreenOn(applicationContext)
                main.postDelayed({ fridayReminder() }, 8_000)
            }
        }
    }

    private val noticeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            notices?.post(
                intent.getStringExtra(Notifier.EXTRA_TITLE).orEmpty(),
                intent.getStringExtra(Notifier.EXTRA_BODY).orEmpty(),
                intent.getStringExtra(Notifier.EXTRA_KIND) ?: NoticeKind.INFO,
                intent.getLongExtra(Notifier.EXTRA_DURATION, 7_000),
            )
        }
    }

    /** Backs up the adhan alarm while the TV is on (alarms can be inexact without the exact-alarm grant). */
    private val prayerTick = object : Runnable {
        override fun run() {
            PrayerAlerts.current(System.currentTimeMillis(), jumuahBefore = PrayerAlerts.jumuahBefore(applicationContext))?.let { AdhanController.start(applicationContext, it) }
            main.postDelayed(this, PRAYER_TICK_MS)
        }
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            catalog = null
            AssistantSession.tools(context).invalidateApps()
            scope.launch { runCatching { AssistantSession.tools(applicationContext).warm() } }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        scope = MainScope()
        controls = SystemControls(this)
        usage = UsageSource(this)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packageReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        instance = this
        notices = NoticeOverlay(this)
        dreamOverlay = DreamOverlay(this)
        ContextCompat.registerReceiver(
            this, dreamReceiver,
            IntentFilter().apply { addAction(Intent.ACTION_DREAMING_STARTED); addAction(Intent.ACTION_DREAMING_STOPPED) },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(this, noticeReceiver, IntentFilter(Notifier.ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        casper = CasperTuner(applicationContext)
        scope.launch { runCatching { AssistantSession.tools(applicationContext).warm() } }
        plate = RafiqPlate(this)
        turn = VoiceTurn(applicationContext, plate!!)
        stremio = StremioTuner(applicationContext)
        ear = GoogleEar(this, object : GoogleEar.Listener {
            override fun onListening() {
                noteForeground()
                turn?.listening()
            }
            override fun onWords(text: String) { turn?.words(text) }
            override fun onHeard(text: String?) {
                // The words read from Google's panel go straight to Rafiq; with none, Rafiq's panel opens for typing.
                if (text.isNullOrBlank()) {
                    turn?.cancel()
                    runCatching { startActivity(AssistantSession.intent(this@SystemUiService, voice = false)) }
                } else {
                    turn?.handle(text)
                }
            }
        })
        ContextCompat.registerReceiver(this, stremioReceiver, IntentFilter(com.moath.tvhome.assistant.StremioFlow.ACTION_PLAY), ContextCompat.RECEIVER_NOT_EXPORTED)
        ScreenAccess.reset(applicationContext)
        ContextCompat.registerReceiver(this, casperReceiver, IntentFilter(com.moath.tvhome.sports.CasperFlix.ACTION_TUNE), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, ownVoiceReceiver, IntentFilter(AssistantSession.ACTION_OWN_VOICE), ContextCompat.RECEIVER_NOT_EXPORTED)
        PrayerAlerts.schedule(applicationContext)
        AdhanController.resume(applicationContext)
        main.post(prayerTick)
        main.postDelayed({ fridayReminder() }, 20_000)
        val alerts = com.moath.tvhome.sports.MatchAlerts(applicationContext)
        matchJob = scope.launch {
            while (true) {
                val next = try {
                    alerts.poll()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Match poll failed", e)
                    com.moath.tvhome.sports.MatchAlerts.SOON_MS
                }
                kotlinx.coroutines.delay(next)
            }
        }
    }

    override fun onDestroy() {
        router.reset()
        dismissOverlayNow()
        runCatching { unregisterReceiver(packageReceiver) }
        runCatching { unregisterReceiver(noticeReceiver) }
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { unregisterReceiver(casperReceiver) }
        runCatching { unregisterReceiver(stremioReceiver) }
        stremio?.stop()
        turn?.cancel()
        plate?.destroy()
        casper?.stop()
        ear?.destroy()
        runCatching { unregisterReceiver(ownVoiceReceiver) }
        runCatching { unregisterReceiver(dreamReceiver) }
        dreamOverlay?.hide()
        dreamOverlay = null
        main.removeCallbacks(prayerTick)
        notices?.destroy()
        notices = null
        if (instance === this) instance = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onInterrupt() {
        router.reset()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The cached banners are ~1 MB each; the box has 2 GB. Rebuild on next open instead.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && overlay == null) catalog = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // The prayer lock holds the screen: anything that comes up over it sends it back.
        // Any screen but the lock itself (our own Home included) sends it back while the lock holds.
        if (AdhanController.isLocked && pkg !in TRANSIENT_PACKAGES && event.className?.toString() != PrayerLockActivity::class.java.name) showPrayerLock()
        if (pkg !in TRANSIENT_PACKAGES && event.className?.toString()?.startsWith("com.moath.tvhome.assistant") != true) foreground = pkg
        if (pkg == AssistantSession.GOOGLE_ASSISTANT) replaceGoogleAssistant()
        if (pkg == packageName || pkg in TRANSIENT_PACKAGES) return
        session.removeAll { it.packageName == pkg }
        session.addFirst(Usage(pkg, System.currentTimeMillis()))
        while (session.size > SESSION_LIMIT) session.removeLast()
    }

    // ---- Key routing -------------------------------------------------------------------------

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (plate?.visible == true && code == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) turn?.cancel()
            return true
        }
        if (AdhanController.isLocked && code == KeyEvent.KEYCODE_HOME) {
            if (event.action == KeyEvent.ACTION_UP) showPrayerLock()
            return true
        }
        // Over the photo screen saver, OK opens its settings (the dream itself can't take keys for us).
        if (code in ASSIST_KEYS && LauncherPrefs.get(this).state.value.aiRemoteKey) return onAssistKey(event)
        if (code == KeyEvent.KEYCODE_HOME) return onHomeKey(event)
        val shown = overlay
        if (shown == null) {
            return event.action == KeyEvent.ACTION_UP && swallowUps.remove(code)
        }
        return when {
            code in CLOSE_KEYS -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    swallowUps += code
                    if (event.repeatCount == 0) closeOverlay()
                } else {
                    swallowUps.remove(code)
                }
                true
            }
            code in OWNED_KEYS -> {
                if (event.action == KeyEvent.ACTION_DOWN) swallowUps += code else swallowUps.remove(code)
                shown.keys.dispatch(event)
                true
            }
            // Volume, media, power and anything else keep working underneath.
            else -> shown.keys.dispatch(event)
        }
    }

    private fun onHomeKey(event: KeyEvent): Boolean {
        val injectedBySystem = (event.flags and KeyEvent.FLAG_FROM_SYSTEM) != 0
        if (injectedBySystem && SystemClock.uptimeMillis() < passHomeUntil) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> when {
                overlay != null && !router.isTracking -> if (event.repeatCount == 0) homeClosesOverlay = true
                else -> router.onDown(event.repeatCount, (event.flags and KeyEvent.FLAG_LONG_PRESS) != 0)
            }
            KeyEvent.ACTION_UP -> when {
                homeClosesOverlay -> {
                    homeClosesOverlay = false
                    closeOverlay()
                }
                else -> router.onUp(event.isCanceled)
            }
        }
        return true
    }

    /**
     * The assistant button. This TV's remote sends a press as down+up 230 ms apart however long it is
     * held, so: first press → the panel for typing; pressed again while the panel is up → listen, and
     * once more → send what was said. Remotes that do report a hold get push-to-talk (hold, release sends).
     */
    private fun onAssistKey(event: KeyEvent): Boolean {
        Log.i(TAG, "assistant key ${event.keyCode} action=${event.action} repeat=${event.repeatCount}")
        if (AdhanController.isLocked) return true
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0 && !assistDown) {
                assistDown = true
                assistLongFired = false
                main.postDelayed(assistLong, ASSIST_LONG_MS)
            }
            KeyEvent.ACTION_UP -> {
                main.removeCallbacks(assistLong)
                if (assistDown && !event.isCanceled) when {
                    assistLongFired -> sendToPanel(stop = true)
                    AssistantSession.visible -> sendToPanel(toggle = true)
                    else -> openAssistant(voice = true)
                }
                assistDown = false
            }
        }
        return true
    }

    private fun sendToPanel(toggle: Boolean = false, stop: Boolean = false) {
        runCatching { startActivity(AssistantSession.intent(this, voice = false, toggle = toggle, stop = stop)) }
    }

    /**
     * Android 12 hands the ASSIST / VOICE_ASSIST buttons straight to Google's assistant, before any
     * accessibility service sees them. When that screen comes up anyway, close it and listen ourselves.
     */
    private fun replaceGoogleAssistant() {
        if (!LauncherPrefs.get(this).state.value.aiRemoteKey || AdhanController.isLocked) return
        val now = SystemClock.uptimeMillis()
        if (now < ownVoiceUntil || now - lastReplaceAt < 1_200) return
        lastReplaceAt = now
        if (ear?.active == true) return
        if (LauncherPrefs.get(this).state.value.voiceMode == "google") {
            if (SystemClock.uptimeMillis() - (ear?.finishedAt ?: 0L) < 3_000) return // Google closing, not a new press
            Log.i(TAG, "Google assistant came up; it listens, Rafiq answers")
            ear?.start()
        } else {
            Log.i(TAG, "Google assistant came up; closing it, Rafiq listens")
            performGlobalAction(GLOBAL_ACTION_BACK)
            main.postDelayed({ if (AssistantSession.visible) sendToPanel(toggle = true) else openAssistant(voice = true) }, 200)
        }
    }

    private var ear: GoogleEar? = null

    /** Once each Friday (after 8 am, not during a prayer lock): a gentle reminder of Surah al-Kahf. */
    private fun fridayReminder() {
        val now = System.currentTimeMillis()
        val friday = com.moath.tvhome.quran.KahfTime.fridayOf(now) ?: return
        val hour = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Riyadh")).apply { timeInMillis = now }[java.util.Calendar.HOUR_OF_DAY]
        val isFriday = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Riyadh")).apply { timeInMillis = now }[java.util.Calendar.DAY_OF_WEEK] == java.util.Calendar.FRIDAY
        if (!isFriday || hour < 8 || AdhanController.isLocked || !LauncherPrefs.get(this).state.value.kahfHero) return
        val state = getSharedPreferences("system_state", MODE_PRIVATE)
        if (state.getString("kahf_reminded", null) == friday) return
        state.edit().putString("kahf_reminded", friday).apply()
        Notifier.post(this, "جمعة مباركة 🌿", "سورة الكهف في أول الشاشة الرئيسية — أو قل للمساعد: شغّل سورة الكهف", NoticeKind.INFO, 12_000)
    }

    /** Turns remote-key filtering on or off at runtime (every key otherwise round-trips through this process). */
    fun setKeyFiltering(on: Boolean) {
        val info = serviceInfo ?: return
        info.flags = if (on) info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        else info.flags and android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS.inv()
        serviceInfo = info
        Log.i(TAG, "key filtering $on")
    }

    /** Opens the assistant panel, first noting what is on screen (for "what's this?"). */
    /** Which app is in front (from window events), for answers like "what is this?". */
    private fun noteForeground() {
        val pkg = foreground
        val label = pkg?.let { p -> runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString() }.getOrNull() }
        AssistantSession.screen = ScreenInfo(pkg, label, "", null)
    }

    /** A fresh screenshot as JPEG, with Rafiq's own plate out of the picture. Null below Android 11 or on failure. */
    suspend fun screenshotJpeg(): ByteArray? {
        if (android.os.Build.VERSION.SDK_INT < 30) return null
        plate?.setShown(false)
        kotlinx.coroutines.delay(180)
        val jpeg = kotlinx.coroutines.withTimeoutOrNull(2_500) {
            kotlinx.coroutines.suspendCancellableCoroutine<ByteArray?> { cont ->
                takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) { cont.resume(runCatching { ScreenJpeg.encode(result) }.getOrNull()) {} }
                    override fun onFailure(errorCode: Int) { cont.resume(null) {} }
                })
            }
        }
        plate?.setShown(true)
        return jpeg
    }

    /** Test hook: runs a whole spoken request as if Google had just heard [text]. */
    fun simulateHeard(text: String) {
        noteForeground()
        turn?.listening()
        turn?.words(text)
        turn?.handle(text)
    }

    fun openAssistant(voice: Boolean, text: String? = null) {
        dismissOverlayNow()
        val launch = Runnable { runCatching { startActivity(AssistantSession.intent(this, voice, text)) }.onFailure { Log.w(TAG, "Cannot open assistant", it) } }
        if (AssistantSession.visible) { launch.run(); return }
        // Which app is in front, from the window events we already get (no screen reading here).
        val pkg = foreground
        val label = pkg?.let { p -> runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString() }.getOrNull() }
        AssistantSession.screen = ScreenInfo(pkg, label, "", null)
        var launched = false
        val once = Runnable { if (!launched) { launched = true; launch.run() } }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            runCatching {
                takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        scope.launch {
                            val jpeg = withContext(Dispatchers.Default) { runCatching { ScreenJpeg.encode(result) }.getOrNull() }
                            AssistantSession.screen = AssistantSession.screen?.copy(jpeg = jpeg)
                        }
                        once.run()
                    }
                    override fun onFailure(errorCode: Int) = once.run()
                })
            }.onFailure { once.run() }
            main.postDelayed(once, 700)
        } else {
            once.run()
        }
    }

    private fun showPrayerLock() {
        runCatching {
            startActivity(Intent(this, PrayerLockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }.onFailure { Log.w(TAG, "Cannot show prayer lock", it) }
    }

    private fun goHome() {
        passHomeUntil = SystemClock.uptimeMillis() + PASS_HOME_MS
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    // ---- Overlays ----------------------------------------------------------------------------

    private fun showOverlay(content: @Composable (OverlayState) -> Unit): Boolean {
        if (overlay != null) return false
        val window = OverlayWindow(this)
        val state = OverlayState(window.keys) { onOverlayDismissed(window) }
        overlay = window
        overlayState = state
        if (!window.show { content(state) }) {
            overlay = null
            overlayState = null
            return false
        }
        return true
    }

    private fun closeOverlay() {
        overlayState?.close() ?: dismissOverlayNow()
    }

    /** The composable finished its exit spring. Tear down outside its own composition. */
    private fun onOverlayDismissed(window: OverlayWindow) {
        if (overlay === window) {
            overlay = null
            overlayState = null
        }
        main.post { window.destroy() }
    }

    private fun dismissOverlayNow() {
        val window = overlay ?: return
        overlay = null
        overlayState = null
        window.destroy()
    }

    private fun showControlCenter() {
        showOverlay { state ->
            ControlCenter(state, controls) { kind -> onControlSelected(kind) }
        }
    }

    private fun onControlSelected(kind: CcKind) {
        when (kind) {
            // The display is about to go dark: an exit animation would stall until wake.
            CcKind.Sleep, CcKind.ScreenSaver -> {
                dismissOverlayNow()
                controls.perform(kind)
            }
            else -> {
                controls.perform(kind)
                closeOverlay()
            }
        }
    }

    private fun showSwitcher() {
        if (overlay != null) return
        val cards = mutableStateOf<List<SwitcherCard>>(listOf(SwitcherCard.Home(emptyList())))
        val pending = catalogAsync()
        val sessionSnapshot = session.toList()
        val dismissedSnapshot = dismissedAt.toMap()
        // First load renders every banner (slow on the box): open at once and fill in.
        var opened = !pending.isCompleted && openSwitcher(cards)
        scope.launch {
            val entries = runCatching { pending.await() }
                .onFailure { Log.e(TAG, "App catalog failed", it) }
                .getOrDefault(emptyList())
            cards.value = withContext(Dispatchers.IO) { buildCards(entries, sessionSnapshot, dismissedSnapshot) }
            if (!opened) opened = openSwitcher(cards)
        }
    }

    private fun openSwitcher(cards: State<List<SwitcherCard>>): Boolean =
        showOverlay { state ->
            AppSwitcher(
                state = state,
                cards = cards.value,
                onOpen = { card -> openCard(card) },
                onClosed = { card -> closeApp(card.entry) },
            )
        }

    private fun buildCards(entries: List<AppEntry>, seen: List<Usage>, dismissed: Map<String, Long>): List<SwitcherCard> {
        val hidden = LauncherPrefs.get(this).state.value.hiddenApps
        val arranged = OrderStore(this).arrange(entries).filter { it.key !in hidden }
        val excluded = usage.homePackages()
        val apps = arranged.filter { !it.isPseudo && it.packageName !in excluded }
        val recents = orderRecents(apps, { it.packageName }, usage.recent(), seen, dismissed)
        return listOf(SwitcherCard.Home(arranged)) + recents.map { SwitcherCard.App(it) }
    }

    private fun openCard(card: SwitcherCard) {
        when (card) {
            is SwitcherCard.Home -> goHome()
            is SwitcherCard.App -> runCatching { startActivity(card.entry.launchIntent) }
                .onFailure { Log.w(TAG, "Cannot open ${card.entry.packageName}", it) }
        }
        closeOverlay()
    }

    /** tvOS "swipe up to close": hide the card until next use and stop the app if we may. */
    private fun closeApp(entry: AppEntry) {
        dismissedAt[entry.packageName] = System.currentTimeMillis()
        session.removeAll { it.packageName == entry.packageName }
        val canKill = ContextCompat.checkSelfPermission(this, Manifest.permission.KILL_BACKGROUND_PROCESSES) ==
            PackageManager.PERMISSION_GRANTED
        if (canKill) {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            runCatching { am.killBackgroundProcesses(entry.packageName) }
                .onFailure { Log.w(TAG, "killBackgroundProcesses failed", it) }
        }
    }

    private fun catalogAsync(): Deferred<List<AppEntry>> =
        catalog ?: scope.async(Dispatchers.Default) { AppRepository(this@SystemUiService).load() }
            .also { catalog = it }

    companion object {
        /** The running service (this process only), for features that need its window powers. */
        @Volatile var instance: SystemUiService? = null
            private set

        private const val PRAYER_TICK_MS = 20_000L
        const val TAG = "SystemUi"
        private const val PASS_HOME_MS = 500L
        private const val SESSION_LIMIT = 30
        val ASSIST_KEYS = setOf(KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_VOICE_ASSIST)
        const val ASSIST_LONG_MS = 450L
        private const val OWN_VOICE_MS = 20_000L
        val CLOSE_KEYS = setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE)
        val OWNED_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
        )
        /** Windows that come and go over an app without being "the app the user is in". */
        val TRANSIENT_PACKAGES = setOf("com.android.systemui", "android")
    }
}
