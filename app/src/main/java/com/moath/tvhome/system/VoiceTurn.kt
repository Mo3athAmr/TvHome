package com.moath.tvhome.system

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.moath.tvhome.assistant.AssistantSession
import com.moath.tvhome.assistant.Fast
import com.moath.tvhome.assistant.FastIntents
import com.moath.tvhome.assistant.Speaker
import com.moath.tvhome.assistant.ToolCall
import com.moath.tvhome.prefs.LauncherPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One spoken request, end to end, on [RafiqPlate]: the common commands run instantly ([FastIntents]),
 * everything else goes to the assistant. The answer is shown typed and read aloud at the same moment,
 * and the plate leaves by itself — quickly when the answer opened an app, so it never covers the video.
 */
class VoiceTurn(private val context: Context, private val plate: RafiqPlate) {
    private val scope = MainScope()
    private var job: Job? = null
    private val speaker = Speaker(context)

    fun listening() {
        job?.cancel()
        speaker.stop()
        plate.listening()
        scope.launch { speaker.warm() } // the voice is ready before the answer is
    }

    fun words(text: String) = plate.words(text)

    fun cancel() {
        job?.cancel()
        speaker.stop()
        plate.hide()
    }

    fun handle(text: String) {
        job?.cancel()
        job = scope.launch {
            try { run(text) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                Log.w(TAG, "turn failed", e)
                plate.answer("صار عندي خطأ بسيط 😕 جرّب مرة ثانية.")
                delay(HOLD_AFTER_MS)
                plate.hide()
            }
        }
    }

    private suspend fun run(text: String) {
        val t0 = SystemClock.uptimeMillis()
        plate.thinking(text)
        plate.uncover() // Google's panel was closed before we got here; the dim has done its job
        val tools = AssistantSession.tools(context)
        val fast = FastIntents.parse(text, { tools.isApp(it) })
        val reply: String
        val opened: Boolean
        when (fast) {
            is Fast.Reply -> { reply = fast.text; opened = false }
            is Fast.Tool -> {
                val r = tools.run(ToolCall("fast", fast.name, fast.args)) { null }
                reply = r.say ?: r.text
                opened = r.opened
            }
            null -> {
                val a = AssistantSession.engine(context).ask(text, AssistantSession.screen, voice = true)
                reply = a.text
                opened = a.opened
            }
        }
        Log.i(TAG, "answered in ${SystemClock.uptimeMillis() - t0} ms (${if (fast != null) "instant" else "assistant"}): $reply")
        plate.answer(reply)
        val speaking = if (LauncherPrefs.get(context).state.value.ttsMode != "off") scope.launch { speaker.speak(reply) } else null
        if (opened) {
            delay(HOLD_OPENED_MS)
        } else {
            withTimeoutOrNull(MAX_SPEECH_MS) { speaking?.join() }
            delay(HOLD_AFTER_MS)
        }
        plate.hide()
    }

    companion object {
        private const val TAG = "VoiceTurn"
        private const val HOLD_OPENED_MS = 2_400L
        private const val HOLD_AFTER_MS = 3_500L
        private const val MAX_SPEECH_MS = 25_000L
    }
}
