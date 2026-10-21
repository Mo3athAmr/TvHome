package com.moath.tvhome.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * Records one utterance from the remote's microphone: 16 kHz mono PCM, stops after the speaker
 * goes quiet (or at [MAX_MS]). Returns a WAV file, or null when nothing but silence came in
 * (the remote's mic sometimes only opens for Google's own recognizer — see [systemRecognizer]).
 */
object VoiceInput {
    private const val RATE = 16_000
    private const val MAX_MS = 12_000
    private const val LEAD_MS = 5_000
    private const val TAIL_MS = 1_300
    private const val SPEECH_LEVEL = 900

    @SuppressLint("MissingPermission")
    suspend fun record(onLevel: (Float) -> Unit): ByteArray? = withContext(Dispatchers.IO) {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE))
        }.getOrNull() ?: return@withContext null
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return@withContext null }
        val pcm = ByteArrayOutputStream()
        val buf = ShortArray(RATE / 20) // 50 ms
        var heard = false
        var quietMs = 0
        var elapsed = 0
        var peak = 0
        try {
            rec.startRecording()
            while (isActive && elapsed < MAX_MS) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) break
                var level = 0
                for (i in 0 until n) level = maxOf(level, abs(buf[i].toInt()))
                peak = maxOf(peak, level)
                onLevel((level / 12_000f).coerceIn(0f, 1f))
                val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until n) bb.putShort(buf[i])
                pcm.write(bb.array())
                val ms = n * 1000 / RATE
                elapsed += ms
                if (level > SPEECH_LEVEL) { heard = true; quietMs = 0 } else quietMs += ms
                if (heard && quietMs > TAIL_MS) break
                if (!heard && elapsed > LEAD_MS) break
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        if (!heard || peak < SPEECH_LEVEL) null else wav(pcm.toByteArray())
    }

    fun wav(pcm: ByteArray): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(pcm.size)
        return h.array() + pcm
    }

    /** What the system recognizer made of the utterance. */
    sealed interface Heard {
        data class Text(val text: String) : Heard
        /** Listening worked but no words came (timeout / no match). */
        data object Nothing : Heard
        /** The recognizer itself is unavailable or failed: try the app's own recording instead. */
        data class Failed(val code: Int) : Heard
    }

    @Volatile private var active: SpeechRecognizer? = null

    /** Ends the current listening now; whatever was said so far is recognised and returned. */
    fun finishListening() = runCatching { active?.stopListening() }

    /**
     * Google's recognizer (Katniss) — the same path Google Assistant uses, so it opens the remote's
     * microphone, which apps can't reach directly on this TV. Partial words stream to [onPartial].
     * Main thread only.
     */
    suspend fun systemRecognizer(context: Context, onLevel: (Float) -> Unit, onPartial: (String) -> Unit = {}): Heard {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return Heard.Failed(-1)
        // Google TV's own recognizer (the assistant's): it gets the TV's processed far-field audio. The
        // system default here is Speech Services, which reads the raw microphone and hears very little.
        val katniss = katnissRecognizer(context)
        if (katniss != null) {
            val h = listenWith(context, katniss, onLevel, onPartial)
            if (h !is Heard.Failed) return h
            logW("Katniss recognizer failed (${h.code}); trying the default one")
        }
        return listenWith(context, null, onLevel, onPartial)
    }

    private val KATNISS = android.content.ComponentName(
        "com.google.android.katniss", "com.google.android.apps.tvsearch.voice.recognition.KatnissRecognitionService",
    )

    private fun katnissRecognizer(context: Context): android.content.ComponentName? =
        KATNISS.takeIf { runCatching { context.packageManager.getServiceInfo(it, 0) }.isSuccess }

    private suspend fun listenWith(
        context: Context,
        component: android.content.ComponentName?,
        onLevel: (Float) -> Unit,
        onPartial: (String) -> Unit,
    ): Heard {
        return suspendCancellableCoroutine { cont ->
            val sr = if (component != null) SpeechRecognizer.createSpeechRecognizer(context, component) else SpeechRecognizer.createSpeechRecognizer(context)
            active = sr
            fun done(h: Heard) {
                if (cont.isActive) cont.resume(h)
                if (active === sr) active = null
                runCatching { sr.destroy() }
            }
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) = onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) = done(
                    if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) Heard.Nothing else Heard.Failed(error),
                )
                override fun onResults(results: Bundle?) {
                    val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    done(if (t.isNullOrBlank()) Heard.Nothing else Heard.Text(t))
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(onPartial)
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            sr.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA")
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    // Don't cut the owner off at the first breath: end only after a real pause
                    // (pressing the assistant button again sends at once).
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_500)
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2_000)
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3_000),
            )
            cont.invokeOnCancellation { runCatching { sr.cancel(); sr.destroy() }; if (active === sr) active = null }
        }
    }
}
