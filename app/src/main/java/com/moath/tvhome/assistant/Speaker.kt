package com.moath.tvhome.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.moath.tvhome.BuildConfig
import com.moath.tvhome.health.Health
import com.moath.tvhome.prefs.LauncherPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

/** A voice the owner can pick: cloud voices by name, system voices by their engine name. */
data class VoiceOption(val id: String, val label: String)

/**
 * Reads the assistant's replies aloud. Cloud: OpenAI's tts-1 through Experiential (clear Arabic and
 * English, ~$15 per million characters ≈ a third of a cent per reply, no chat tokens). System: the
 * TV's own text-to-speech, free and offline. A cloud failure falls back to the system voice.
 */
class Speaker(context: Context) {
    private val ctx = context.applicationContext
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady: Boolean? = null
    private var focus: AudioFocusRequest? = null

    companion object {
        const val CLOUD_MODEL = "tts-1"
        private const val MAX_CHARS = 600
        val cloudVoices = listOf(
            VoiceOption("nova", "نوفا — أنثى، دافئ"),
            VoiceOption("shimmer", "شيمر — أنثى، ناعم"),
            VoiceOption("alloy", "ألوي — محايد"),
            VoiceOption("echo", "إيكو — ذكر"),
            VoiceOption("onyx", "أونيكس — ذكر، عميق"),
            VoiceOption("fable", "فيبل — ذكر، هادئ"),
        )
        const val SAMPLE = "هلا والله! أنا رفيق، مساعدك على التلفزيون. Hello, I'm your TV assistant."

        /** Drops emoji and symbols that a voice would read out or stumble on. */
        fun speakable(text: String): String = text
            .replace(Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]"), "")
            .replace(Regex("[*_#`]"), "")
            .replace("•", " ")
            .replace(Regex("\\s+"), " ")
            .replace(Regex(" ([.,!?؟،])"), "$1").trim().take(MAX_CHARS)

        fun isArabic(text: String) = text.count { it in '؀'..'ۿ' } >= text.count { it.isLetter() } / 3
    }

    /** Starts the TV's voice engine ahead of time so the first reply doesn't wait for it. */
    suspend fun warm() {
        if (LauncherPrefs.get(ctx).state.value.ttsEngine != "cloud") engine()
    }

    suspend fun speak(text: String, engine: String? = null, voice: String? = null) {
        stop()
        val say = speakable(text)
        if (say.isEmpty()) return
        val p = LauncherPrefs.get(ctx).state.value
        val useCloud = (engine ?: p.ttsEngine) == "cloud" && BuildConfig.EXPLABS_API_KEY.isNotBlank()
        if (useCloud) {
            try {
                playCloud(say, voice ?: p.ttsCloudVoice)
                Health.recover(ctx, "tts")
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logW("cloud tts failed", e)
                Health.report(ctx, "tts", e.message ?: "خطأ")
            }
        }
        speakSystem(say, voice.takeIf { engine == "system" })
    }

    private suspend fun playCloud(text: String, voice: String) {
        val bytes = withContext(Dispatchers.IO) {
            val body = JSONObject().put("model", CLOUD_MODEL).put("input", text).put("voice", voice).put("response_format", "mp3")
            val req = Request.Builder().url("https://api.experientiallabs.ai/v1/audio/speech")
                .header("Authorization", "Bearer ${BuildConfig.EXPLABS_API_KEY}")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            Http.client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw AiException("HTTP ${r.code}: ${r.body?.string().orEmpty().take(200)}")
                r.body?.bytes() ?: throw AiException("empty audio")
            }
        }
        val file = File(ctx.cacheDir, "reply.mp3").apply { writeBytes(bytes) }
        suspendCancellableCoroutine { cont ->
            val mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener { release(); if (cont.isActive) cont.resume(Unit) }
            mp.setOnErrorListener { _, _, _ -> release(); if (cont.isActive) cont.resume(Unit); true }
            mp.setOnPreparedListener { requestFocus(); it.start() }
            mp.prepareAsync()
            cont.invokeOnCancellation { stop() }
        }
    }

    private suspend fun engine(): TextToSpeech? {
        ttsReady?.let { return if (it) tts else null }
        return suspendCancellableCoroutine { cont ->
            var engine: TextToSpeech? = null
            engine = TextToSpeech(ctx) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                tts = engine
                if (cont.isActive) cont.resume(if (ttsReady == true) engine else null)
            }
        }
    }

    /** The system voices for Arabic and English, best quality first. */
    suspend fun systemVoices(): List<VoiceOption> {
        val e = engine() ?: return emptyList()
        return runCatching { e.voices.orEmpty() }.getOrDefault(emptySet())
            .filter { (it.locale.language == "ar" || it.locale.language == "en") && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedWith(compareBy<Voice>({ it.locale.language != "ar" }, { -it.quality }, { it.name }))
            .map { v -> VoiceOption(v.name, "${if (v.locale.language == "ar") "عربي" else "English"} · ${v.locale.country.ifBlank { "-" }} · ${v.name.substringAfterLast('-')}${if (v.isNetworkConnectionRequired) " · إنترنت" else ""}") }
    }

    private suspend fun speakSystem(text: String, voiceOverride: String?) {
        val e = engine() ?: return
        val p = LauncherPrefs.get(ctx).state.value
        val arabic = isArabic(text)
        val chosen = voiceOverride ?: if (arabic) p.ttsSystemVoiceAr else p.ttsSystemVoiceEn
        val voice = runCatching { e.voices?.firstOrNull { it.name == chosen } }.getOrNull()
        if (voice != null) {
            e.voice = voice
        } else {
            val r = e.setLanguage(if (arabic) Locale("ar") else Locale.US)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                // No such voice installed on the TV: the cloud voice covers it (if a key exists).
                logW("system voice missing for ${if (arabic) "ar" else "en"}")
                if (BuildConfig.EXPLABS_API_KEY.isNotBlank()) runCatching { playCloud(text, p.ttsCloudVoice) }
                return
            }
        }
        requestFocus()
        suspendCancellableCoroutine { cont ->
            e.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { abandonFocus(); if (cont.isActive) cont.resume(Unit) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { abandonFocus(); if (cont.isActive) cont.resume(Unit) }
            })
            e.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply")
            cont.invokeOnCancellation { e.stop(); abandonFocus() }
        }
    }

    private fun requestFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .build()
        focus = req
        am.requestAudioFocus(req)
    }

    private fun abandonFocus() {
        focus?.let { am.abandonAudioFocusRequest(it) }
        focus = null
    }

    private fun release() {
        player?.runCatching { release() }
        player = null
        abandonFocus()
    }

    fun stop() {
        player?.runCatching { stop() }
        release()
        tts?.stop()
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        ttsReady = null
    }
}
