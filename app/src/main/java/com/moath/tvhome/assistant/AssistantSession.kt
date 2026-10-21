package com.moath.tvhome.assistant

import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Process-wide state for the assistant (lives in :system with the accessibility service): the
 * conversation, and what the screen showed the moment the remote button was pressed.
 */
object AssistantSession {
    private const val IDLE_RESET_MS = 4 * 60_000L
    const val EXTRA_VOICE = "voice"
    const val EXTRA_TEXT = "text"
    /** The text came from the owner's voice (so a "voice only" reply setting speaks the answer). */
    const val EXTRA_SPOKEN = "spoken"
    /** The assistant button pressed while the panel is up: listen, or send what was said. */
    const val EXTRA_TOGGLE = "toggle"
    /** The button released after a hold (remotes that report holds): send what was said. */
    const val EXTRA_STOP = "stop"
    /** Test hook only: path of a recorded WAV to treat as if spoken. */
    const val EXTRA_WAV = "wav"
    /** Sent by our own search screen right before it opens Google's voice input on purpose. */
    const val ACTION_OWN_VOICE = "com.moath.tvhome.OWN_VOICE"
    const val GOOGLE_ASSISTANT = "com.google.android.katniss"

    @Volatile var screen: ScreenInfo? = null
    /** The panel is on screen: the button only re-targets it (no new screenshot of our own panel). */
    @Volatile var visible = false
    private var engine: AssistantEngine? = null
    private var tools: AssistantTools? = null

    fun tools(context: Context): AssistantTools =
        tools ?: AssistantTools(context.applicationContext) { screen }.also { tools = it }
    private var lastUse = 0L

    fun engine(context: Context): AssistantEngine {
        val app = context.applicationContext
        val now = SystemClock.elapsedRealtime()
        val e = engine ?: AssistantEngine(app, tools(app)).also { engine = it }
        if (now - lastUse > IDLE_RESET_MS) e.reset()
        lastUse = now
        return e
    }

    fun intent(context: Context, voice: Boolean, text: String? = null, wav: String? = null, toggle: Boolean = false, stop: Boolean = false): Intent =
        Intent(context, AssistantActivity::class.java)
            .putExtra(EXTRA_VOICE, voice)
            .putExtra(EXTRA_TOGGLE, toggle)
            .putExtra(EXTRA_STOP, stop)
            .apply { text?.let { putExtra(EXTRA_TEXT, it) } }
            .apply { wav?.let { putExtra(EXTRA_WAV, it) } }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
}
