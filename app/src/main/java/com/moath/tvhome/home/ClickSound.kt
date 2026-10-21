package com.moath.tvhome.home

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * The faint tick tvOS makes as focus moves. Synthesised once (a 12 ms decaying ~3 kHz sine with
 * a little low body), written as a tiny WAV to the cache and played through SoundPool, which is
 * built for low-latency, rapidly retriggered effects like held-arrow auto-repeat.
 */
class ClickSound(context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    @Volatile private var ready = false
    private var soundId = 0

    init {
        try {
            val file = File(context.cacheDir, FILE_NAME)
            if (!file.exists()) file.writeBytes(wav(synthesize()))
            pool.setOnLoadCompleteListener { _, id, status -> if (status == 0 && id == soundId) ready = true }
            soundId = pool.load(file.path, 1)
        } catch (e: IOException) {
            Log.w(TAG, "Click sound unavailable", e)
        }
    }

    fun play() {
        if (ready) pool.play(soundId, VOLUME, VOLUME, 1, 0, 1f)
    }

    fun release() {
        ready = false
        pool.release()
    }

    private fun synthesize(): ShortArray {
        val n = SAMPLE_RATE * DURATION_MS / 1000
        val attack = SAMPLE_RATE * ATTACK_MS / 1000f
        return ShortArray(n) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            val env = exp(-t / DECAY_S) * min(1f, i / attack)
            val v = 0.72 * sin(2 * PI * TONE_HZ * t) + 0.28 * sin(2 * PI * BODY_HZ * t)
            (v * env * AMPLITUDE * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun wav(pcm: ShortArray): ByteArray {
        val dataBytes = pcm.size * 2
        val buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
        buf.put("data".toByteArray()).putInt(dataBytes)
        pcm.forEach { buf.putShort(it) }
        return buf.array()
    }

    private companion object {
        const val TAG = "ClickSound"
        const val FILE_NAME = "focus_tick_v1.wav"
        const val SAMPLE_RATE = 44_100
        const val DURATION_MS = 12
        const val ATTACK_MS = 0.4f
        const val DECAY_S = 0.0026
        const val TONE_HZ = 3_000.0
        const val BODY_HZ = 1_400.0
        const val AMPLITUDE = 0.55
        const val VOLUME = 0.16f
    }
}
