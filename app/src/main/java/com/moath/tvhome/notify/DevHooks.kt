package com.moath.tvhome.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import com.moath.tvhome.prayer.AdhanController
import com.moath.tvhome.prayer.AdhanSlot
import com.moath.tvhome.prayer.Prayer

/**
 * Test hooks for adb only (the receiver requires android.permission.DUMP, which only the shell and
 * the system hold): `adb shell am broadcast -a com.moath.tvhome.DEV_ADHAN --es prayer Maghrib`.
 */
class DevHooks : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            "com.moath.tvhome.DEV_NOTICE" -> Notifier.post(
                app,
                intent.getStringExtra("title") ?: "تجربة",
                intent.getStringExtra("body").orEmpty(),
                intent.getStringExtra("kind") ?: NoticeKind.INFO,
            )
            "com.moath.tvhome.DEV_ADHAN" -> {
                val prayer = runCatching { Prayer.valueOf(intent.getStringExtra("prayer") ?: "Maghrib") }.getOrDefault(Prayer.Maghrib)
                val now = System.currentTimeMillis()
                // --ez jumuah true: a Friday lock that started now, adhan in --ei before (default 30) minutes.
                if (intent.getBooleanExtra("jumuah", false)) {
                    AdhanController.start(app, AdhanSlot(Prayer.Dhuhr, now, now + intent.getIntExtra("before", 30) * 60_000L, jumuah = true))
                } else {
                    AdhanController.start(app, AdhanSlot(prayer, now))
                }
            }
            "com.moath.tvhome.DEV_ADHAN_END" -> AdhanController.end(app)
            "com.moath.tvhome.DEV_STANDBY" -> AdhanController.goToStandby()
            // adb shell am broadcast -a com.moath.tvhome.DEV_PLATE --es text "..."  (a whole spoken request, without Google)
            "com.moath.tvhome.DEV_PLATE" -> com.moath.tvhome.system.SystemUiService.instance?.simulateHeard(intent.getStringExtra("text") ?: "مرحبا")
            // adb shell am broadcast -a com.moath.tvhome.DEV_VOICEMODE --es mode google|own
            "com.moath.tvhome.DEV_VOICEMODE" -> com.moath.tvhome.prefs.LauncherPrefs.get(app).update { it.copy(voiceMode = intent.getStringExtra("mode") ?: "google") }
            // adb shell am broadcast -a com.moath.tvhome.DEV_SAY --es text "..."  (speaks through the TV, to test listening)
            "com.moath.tvhome.DEV_SAY" -> kotlinx.coroutines.MainScope().launch {
                com.moath.tvhome.assistant.Speaker(app).speak(intent.getStringExtra("text") ?: "مرحبا", engine = "cloud")
            }
            "com.moath.tvhome.DEV_KEYFILTER" -> com.moath.tvhome.system.SystemUiService.instance?.setKeyFiltering(intent.getBooleanExtra("on", true))
            // adb shell am broadcast -a com.moath.tvhome.DEV_LISTEN  (logs what the recognizer heard)
            "com.moath.tvhome.DEV_LISTEN" -> launchListen(app)
            // adb shell am broadcast -a com.moath.tvhome.DEV_CASPER --es channel "beIN SPORTS 1 HD"
            "com.moath.tvhome.DEV_CASPER" -> com.moath.tvhome.sports.CasperFlix.watch(app, intent.getStringExtra("channel"))
            // adb shell am broadcast -a com.moath.tvhome.DEV_BACK_HOLD --el ms 6000
            "com.moath.tvhome.DEV_BACK_HOLD" -> com.moath.tvhome.prayer.PrayerLockActivity.shown?.simulateBackHold(intent.getLongExtra("ms", 6_000))
            // adb shell am broadcast -a com.moath.tvhome.DEV_SETTINGS --es page customize,customize.jumuah
            "com.moath.tvhome.DEV_SETTINGS" -> app.startActivity(
                Intent(app, com.moath.tvhome.settings.SettingsActivity::class.java)
                    .putExtra(com.moath.tvhome.settings.SettingsActivity.EXTRA_PAGE, intent.getStringExtra("page") ?: "customize")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            // adb shell am broadcast -a com.moath.tvhome.DEV_AI_PRIMARY --es provider anthropic
            "com.moath.tvhome.DEV_AI_PRIMARY" -> {
                val id = intent.getStringExtra("provider") ?: "gemini"
                com.moath.tvhome.prefs.LauncherPrefs.get(app).update { it.copy(aiPrimary = id) }
            }
            // adb shell am broadcast -a com.moath.tvhome.DEV_ASK [--es text "…"] [--ez voice true]
            "com.moath.tvhome.DEV_ASK" -> {
                val voice = intent.getBooleanExtra("voice", false)
                val text = intent.getStringExtra("text")
                val wav = intent.getStringExtra("wav")
                if (wav != null) app.startActivity(com.moath.tvhome.assistant.AssistantSession.intent(app, false, wav = wav))
                else com.moath.tvhome.system.SystemUiService.instance?.openAssistant(voice, text)
                    ?: app.startActivity(com.moath.tvhome.assistant.AssistantSession.intent(app, voice, text))
            }
        }
    }
}

private fun launchListen(app: Context) {
    kotlinx.coroutines.MainScope().launch {
        val h = com.moath.tvhome.assistant.VoiceInput.systemRecognizer(app, onLevel = {}, onPartial = { android.util.Log.i("ListenProbe", "partial $it") })
        android.util.Log.i("ListenProbe", "heard $h")
    }
}
