package com.moath.tvhome.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.moath.tvhome.AppEntry
import com.moath.tvhome.AppRepository
import com.moath.tvhome.R
import com.moath.tvhome.prefs.LauncherPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** A screen in the Settings hierarchy. [key] identifies it for focus memory. */
internal sealed class Dest(val key: String) {
    data object Root : Dest("root")
    data object General : Dest("general")
    data object About : Dest("about")
    data object Remotes : Dest("remotes")
    data object Apps : Dest("apps")
    data class App(val pkg: String) : Dest("app:$pkg")
    data object VideoAudio : Dest("video")
    data object ScreenSaver : Dest("screensaver")
    data object ScreenSaverType : Dest("screensaver.type")
    data object StartAfter : Dest("screensaver.start")
    data object Network : Dest("network")
    data object HomeScreen : Dest("home")
    data object Region : Dest("home.region")
    data object System : Dest("system")
    data object Customize : Dest("customize")
    data object HeroSource : Dest("customize.hero")
    data object SportsLeagues : Dest("customize.leagues")
    data object SportsClubs : Dest("customize.clubs")
    data object Saver : Dest("customize.saver")
    data object SaverMode : Dest("customize.saver.mode")
    data object SaverBackground : Dest("customize.saver.bg")
    data object Prayer : Dest("customize.prayer")
    data object Iqama : Dest("customize.iqama")
    data object Notices : Dest("customize.notices")
    data object Assistant : Dest("customize.ai")
    data object AiPrimary : Dest("customize.ai.primary")
    data class AiModel(val provider: String) : Dest("customize.ai.model:$provider")
    data object AiVoice : Dest("customize.ai.voice")
    data object Jumuah : Dest("customize.jumuah")

    companion object {
        private val fixed by lazy {
            listOf(
                Root, General, About, Remotes, Apps, VideoAudio, ScreenSaver, ScreenSaverType,
                StartAfter, Network, HomeScreen, Region, System, Customize, HeroSource,
                SportsLeagues, SportsClubs, Saver, SaverMode, SaverBackground, Prayer, Iqama, Notices, Assistant, AiPrimary, AiVoice, Jumuah,
            ).associateBy { it.key }
        }

        fun fromKey(key: String): Dest? =
            when {
                key.startsWith("app:") -> App(key.removePrefix("app:"))
                key.startsWith("customize.ai.model:") -> AiModel(key.removePrefix("customize.ai.model:"))
                else -> fixed[key]
            }
    }
}

/** The big artwork on the left half of every page. */
internal sealed interface Art {
    data object Gear : Art
    data object Device : Art
    data object Remote : Art
    data object AppGrid : Art
    data object Display : Art
    data object Landscape : Art
    data object Wifi : Art
    data object HomeGrid : Art
    data class Tile(val image: ImageBitmap) : Art
}

internal sealed interface Entry {
    val id: String
}

internal data class Header(override val id: String, val text: String) : Entry

/** A paragraph in the list (not focusable). */
internal data class Note(override val id: String, val text: String) : Entry

/**
 * A row. [onClick] null = informational row (still focusable, like tvOS's About rows).
 * [checked] non-null turns the row into a picker option with a leading checkmark slot.
 * [footer] is shown under the artwork while the row has focus.
 */
internal data class Item(
    override val id: String,
    val title: String,
    val value: String? = null,
    val chevron: Boolean = false,
    val checked: Boolean? = null,
    val icon: ImageBitmap? = null,
    val footer: String? = null,
    val onClick: (() -> Unit)? = null,
) : Entry

internal data class PageSpec(val title: String, val art: Art, val entries: List<Entry>, val caption: String? = null)

internal data class AlertButton(val label: String, val destructive: Boolean = false, val action: () -> Unit = {})

internal data class AlertSpec(val title: String, val message: String?, val buttons: List<AlertButton>, val focusIndex: Int = 0)

internal data class FocusMemo(val id: String, val index: Int)

/**
 * Navigation stack, alert, and the bridge to the platform. One per activity.
 * [tick] bumps on every resume so pages re-read system values the user may have changed
 * in Android's own settings screens.
 */
@Stable
internal class SettingsController(val context: Context, private val scope: CoroutineScope) {
    val info = SystemInfo(context)
    val prefs = LauncherPrefs.get(context)
    private val pm: PackageManager = context.packageManager

    val stack = mutableStateListOf<Dest>(Dest.Root)
    var forward by mutableStateOf(true)
        private set
    var alert by mutableStateOf<AlertSpec?>(null)
    var tick by mutableIntStateOf(0)
        private set
    var apps by mutableStateOf<List<AppEntry>?>(null)
        private set

    val focusMemory = HashMap<String, FocusMemo>()
    private var appsJob: Job? = null

    fun push(dest: Dest) {
        forward = true
        stack.add(dest)
    }

    /** Opened from the profile picture: Back leaves Settings from the Customize page directly. */
    var standalone = false

    /** Returns false at the root (or at Customize when [standalone]). */
    fun pop(): Boolean {
        if (stack.size <= 1 || (standalone && stack.size <= 2)) return false
        forward = false
        stack.removeAt(stack.lastIndex)
        return true
    }

    /** Deep link, e.g. "screensaver" or "general,about": replaces the stack below the root. */
    fun openPath(path: String) {
        val dests = path.split(',').mapNotNull { Dest.fromKey(it.trim()) }.filter { it != Dest.Root }
        if (dests.isEmpty()) return
        if (dests.any { it == Dest.Apps || it is Dest.App }) loadApps()
        forward = true
        stack.removeRange(1, stack.size)
        stack.addAll(dests)
    }

    fun onResume() {
        tick++
        if (apps != null) loadApps()
        val top = stack.lastOrNull()
        if (top is Dest.App && !info.isInstalled(top.pkg)) pop()
    }

    fun loadApps() {
        if (appsJob?.isActive == true) return
        appsJob = scope.launch {
            val loaded = withContext(Dispatchers.Default) {
                runCatching { AppRepository(context).load() }
                    .onFailure { Log.w(TAG, "App list failed", it) }
                    .getOrDefault(emptyList())
            }
            apps = loaded.filterNot { it.isPseudo }.sortedBy { it.label.lowercase() }
        }
    }

    /** String resource, formatted with [Locale.US] so numbers stay Western on the Arabic box. */
    fun s(@StringRes id: Int, vararg args: Any): String =
        if (args.isEmpty()) context.getString(id) else String.format(Locale.US, context.getString(id), *args)

    fun onOff(on: Boolean): String = s(if (on) R.string.settings_on else R.string.settings_off)

    /** True when [intent] resolves to an activity we're allowed to start. */
    fun resolves(intent: Intent): Boolean {
        val info = pm.resolveActivity(intent, 0)?.activityInfo ?: return false
        return info.exported || info.packageName == context.packageName
    }

    fun resolverClass(intent: Intent): String? = pm.resolveActivity(intent, 0)?.activityInfo?.name

    fun firstResolvable(vararg intents: Intent): Intent? = intents.firstOrNull(::resolves)

    fun open(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Cannot start $intent", e)
            Toast.makeText(context, R.string.settings_cant_open, Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Log.w(TAG, "Not allowed to start $intent", e)
            Toast.makeText(context, R.string.settings_cant_open, Toast.LENGTH_SHORT).show()
        }
    }

    fun refresh() {
        tick++
    }

    /** Voices of the TV's own text-to-speech (loaded on first visit), and the preview speaker. */
    var systemVoices: List<com.moath.tvhome.assistant.VoiceOption>? = null
        private set
    val speaker by lazy { com.moath.tvhome.assistant.Speaker(context) }
    private var previewJob: Job? = null

    fun loadSystemVoices() {
        if (systemVoices != null) return
        systemVoices = emptyList()
        scope.launch {
            systemVoices = runCatching { speaker.systemVoices() }.getOrDefault(emptyList())
            tick++
        }
    }

    fun previewVoice(engine: String, voice: String) {
        previewJob?.cancel()
        previewJob = scope.launch { speaker.speak(com.moath.tvhome.assistant.Speaker.SAMPLE, engine, voice) }
    }

    /** Model lists per provider ("gemini", "xpl", "xpl.audio", "anthropic"), fetched on first visit. */
    val aiModels = HashMap<String, List<com.moath.tvhome.assistant.ModelOption>>()
    val aiModelErrors = HashMap<String, String>()
    private val aiJobs = HashMap<String, Job>()

    fun loadModels(provider: String) {
        val key = provider.substringBefore('.')
        if (aiJobs[key]?.isActive == true || provider in aiModels) return
        aiJobs[key] = scope.launch {
            runCatching {
                when (key) {
                    "gemini" -> mapOf("gemini" to com.moath.tvhome.assistant.ModelCatalog.gemini())
                    "xpl" -> com.moath.tvhome.assistant.ModelCatalog.experiential().let { (chat, audio) -> mapOf("xpl" to chat, "xpl.audio" to audio) }
                    else -> mapOf("anthropic" to com.moath.tvhome.assistant.ModelCatalog.anthropic())
                }
            }.onSuccess { aiModels.putAll(it); aiModelErrors.remove(provider) }
                .onFailure { aiModelErrors[provider] = it.message ?: "خطأ في الاتصال" }
            tick++
        }
    }

    private companion object {
        const val TAG = "TvSettings"
    }
}
