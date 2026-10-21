package com.moath.tvhome.system

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Google's ears, Rafiq's brain. The Bluetooth remote's microphone streams only into Google's assistant
 * (its audio service is guarded by a privileged-only permission), so when the mic button brings Google
 * up we let it listen and show its own panel — nothing of ours is drawn over it — read the words it
 * recognises, close it before it acts, and hand the words to Rafiq.
 *
 * The turn is over when Google's panel falls back to its idle screen (suggestion chips), when its window
 * closes, or — as a last resort — when the words stop changing for a moment.
 */
class GoogleEar(private val service: AccessibilityService, private val listener: Listener) {
    interface Listener {
        /** Google's panel came up and is listening. */
        fun onListening()
        /** The words recognised so far. */
        fun onWords(text: String)
        /** The turn is over: the final words, or null if none could be read. */
        fun onHeard(text: String?)
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private var job: Job? = null
    private val audio = service.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Voice-recognition recordings open right now (only seen when Google records through the TV's own mic). */
    @Volatile private var recordings = 0
    @Volatile private var sawRecording = false

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            recordings = configs.count { it.clientAudioSource == MediaRecorder.AudioSource.VOICE_RECOGNITION }
            if (recordings > 0) sawRecording = true
        }
    }

    init {
        runCatching { audio.registerAudioRecordingCallback(recordingCallback, main) }
    }

    val active: Boolean get() = job?.isActive == true

    /** When the last listening ended: Google's closing fires window events of its own, which must not restart us. */
    @Volatile var finishedAt = 0L
        private set

    fun start() {
        if (active) return
        sawRecording = recordings > 0
        listener.onListening()
        job = scope.launch {
            var stillOpen = false
            var text: String? = null
            try {
                text = ScreenAccess.use(service) { reader ->
                    if (reader == null) null else listen(reader).also { stillOpen = closeGoogle(reader) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "listening failed", e)
            }
            Log.i(TAG, "heard: $text (google still open=$stillOpen)")
            delay(120)
            finishedAt = SystemClock.uptimeMillis()
            listener.onHeard(text)
        }
    }

    fun destroy() {
        job?.cancel()
        runCatching { audio.unregisterAudioRecordingCallback(recordingCallback) }
    }

    private suspend fun listen(reader: ScreenReaderService): String? {
        val start = SystemClock.uptimeMillis()
        var last = ""
        var lastChange = start
        var seenGoogle = false
        var recEndedAt = 0L
        var lastDump = ""
        while (true) {
            // Wake on Google's own screen changes (fast); the timeout only covers the no-event cases.
            withTimeoutOrNull(WAKE_MS) { ScreenReaderService.katnissChanged.receive() }
            val now = SystemClock.uptimeMillis()
            val root = googleWindow(reader)
            if (root == null) {
                // Google closed by itself (finished, or dismissed): whatever we have is the answer.
                // Never seen at all: a stray window event, not a real press — give up quickly.
                if (seenGoogle || now - start > 1_500) return last.ifBlank { null }
                continue
            }
            seenGoogle = true
            val texts = textsOf(root)
            val dump = texts.joinToString(" ‖ ") { "${it.top}:${it.text}" }
            if (dump != lastDump) { lastDump = dump; Log.d(TAG, "panel: $dump") }
            val spoken = pickTranscriptLine(texts)
            val line = spoken?.text
            if (line != null && line != last) { last = line; lastChange = now; listener.onWords(line) }
            // Google put something under the words (video results, an answer): it has heard enough —
            // that is the end of the sentence, and the moment to take over before it acts.
            if (spoken != null && last.isNotBlank() && texts.any { t ->
                    !t.interactive && t.top > spoken.top + 40 && t.text.length > 2 && PROMPTS.none { p -> t.text.contains(p, ignoreCase = true) }
                }
            ) return last
            // Google went back to its idle screen (suggestion chips, no words of its own): it has finished
            // its turn, so what we heard is final — act now, before it does anything else.
            if (line == null && last.isNotBlank() && texts.count { it.interactive } >= 2) return last
            if (sawRecording && recordings == 0) {
                if (recEndedAt == 0L) recEndedAt = now
                if (now - recEndedAt > SETTLE_MS && (last.isNotBlank() || now - recEndedAt > 2_000)) return last.ifBlank { null }
            } else {
                recEndedAt = 0L
            }
            // No other signal: words, then quiet.
            if (!sawRecording && last.isNotBlank() && now - lastChange > STABLE_MS) return last
            if (now - start > MAX_MS) return last.ifBlank { null }
        }
    }

    /**
     * Google must not act on what it heard: send Back until its window is gone (its results screen needs
     * more than one), and only while it is there — Back would otherwise leave the app below.
     * Returns whether it was open when we started.
     */
    private suspend fun closeGoogle(reader: ScreenReaderService): Boolean {
        val wasOpen = googleWindow(reader) != null
        var tries = 0
        while (tries < 5 && googleWindow(reader) != null) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            tries++
            delay(280)
        }
        if (tries > 1) Log.i(TAG, "google needed $tries Back presses")
        return wasOpen
    }

    private var lastWindowLog = 0L

    private fun googleWindow(reader: ScreenReaderService): AccessibilityNodeInfo? {
        val roots = runCatching { reader.windows.mapNotNull { it.root } }.getOrDefault(emptyList()) +
            listOfNotNull(runCatching { reader.rootInActiveWindow }.getOrNull())
        val hit = roots.firstOrNull { it.packageName == GOOGLE }
        val now = SystemClock.uptimeMillis()
        if (hit == null && now - lastWindowLog > 1_500) {
            lastWindowLog = now
            Log.d(TAG, "no google window; windows=" + runCatching { reader.windows.joinToString { "${it.type}/${it.root?.packageName}" } }.getOrNull())
        }
        return hit
    }

    private fun textsOf(root: AccessibilityNodeInfo): List<TextLine> {
        val out = ArrayList<TextLine>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 25) return
            val t = n.text?.toString()?.trim()
            if (!t.isNullOrEmpty()) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                out += TextLine(r.top, t, n.isClickable || n.isFocusable)
            }
            for (i in 0 until n.childCount) walk(runCatching { n.getChild(i) }.getOrNull(), depth + 1)
        }
        walk(root, 0)
        return out
    }

    data class TextLine(val top: Int, val text: String, val interactive: Boolean)

    companion object {
        private const val TAG = "GoogleEar"
        const val GOOGLE = "com.google.android.katniss"
        /** Longest wait between looks at Google's panel when no change event arrives. */
        private const val WAKE_MS = 150L
        /** After Google's recording ends: time for its last words to appear. */
        private const val SETTLE_MS = 450L
        /** Only when no other signal exists: words, then this much quiet. */
        private const val STABLE_MS = 1_200L
        private const val MAX_MS = 25_000L

        /** Google's own lines on the panel (its prompts and hints, not the owner's words). */
        private val PROMPTS = listOf(
            "يمكنك طرح سؤال", "تحدث الآن", "تحدّث الآن", "جارٍ الاستماع", "جار الاستماع", "أستمع", "كيف يمكنني", "اسأل",
            "اضغط على زر الميكروفون", "اضغط على الميكروفون", "انقر على زر الميكروفون", "لم أسمع", "عذرًا", "عذراً",
            "Listening", "Speak now", "Ask me", "How can I help", "Try saying", "Press the mic", "Tap the mic", "Sorry",
        )

        /** The recognised words: the topmost non-interactive line that isn't one of Google's own prompts. */
        fun pickTranscript(lines: List<TextLine>): String? = pickTranscriptLine(lines)?.text

        fun pickTranscriptLine(lines: List<TextLine>): TextLine? =
            lines.filter { !it.interactive }.sortedBy { it.top }
                .firstOrNull { l -> PROMPTS.none { l.text.contains(it, ignoreCase = true) } }
    }
}
