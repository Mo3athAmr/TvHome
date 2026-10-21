package com.moath.tvhome.search

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.moath.tvhome.tmdb.TmdbClient
import com.moath.tvhome.AppEntry
import com.moath.tvhome.tmdb.TmdbItem

/** tvOS Search: linear keyboard, TMDB + installed-app results, product pages. */
class SearchActivity : ComponentActivity(), SearchActions {
    private lateinit var model: SearchModel

    private val voiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val text = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (res.resultCode == RESULT_OK && !text.isNullOrBlank()) model.replace(text)
    }

    override val voiceAvailable: Boolean by lazy {
        SpeechRecognizer.isRecognitionAvailable(this) && voiceIntent().resolveActivity(packageManager) != null
    }

    override val stremioInstalled: Boolean get() = stremioLaunchIntent() != null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = SearchModel(applicationContext)
        com.moath.tvhome.assistant.Library.fromJson(intent.getStringExtra(EXTRA_PICKS)).takeIf { it.isNotEmpty() }?.let { items ->
            model.picks = ResultSection("picks", intent.getStringExtra(EXTRA_PICKS_TITLE) ?: "اقتراحات رفيق", SectionKind.Posters, items.map(::MediaHit))
        }
        val deepLink = intent.getLongExtra(EXTRA_TMDB_ID, -1L).takeIf { it > 0 }?.let { id ->
            DeepLink(id, intent.getStringExtra(EXTRA_TMDB_TYPE) ?: "movie")
        }
        setContent { SearchApp(model, this, deepLink) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_SEARCH || keyCode == KeyEvent.KEYCODE_VOICE_ASSIST) && voiceAvailable) {
            if (event.repeatCount == 0) {
                // Tell the system process this Google voice screen is ours, so it doesn't swap it for the assistant.
                sendBroadcast(Intent(com.moath.tvhome.assistant.AssistantSession.ACTION_OWN_VOICE).setPackage(packageName))
                start(voiceIntent()) { voiceLauncher.launch(it) }
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun launchApp(app: AppEntry) {
        start(app.launchIntent)
    }

    override fun watchTrailer(item: TmdbItem) {
        val query = "${item.title} trailer ${item.year.orEmpty()}".trim()
        val youTubeTv = Intent(Intent.ACTION_SEARCH)
            .setPackage(YOUTUBE_TV)
            .putExtra(SearchManager.QUERY, query)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (start(youTubeTv)) return
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        start(web)
    }

    /** Opens the title's page inside Stremio (by IMDb id); falls back to just launching the app. */
    override fun openStremio(item: TmdbItem) {
        lifecycleScope.launch {
            val imdb = TmdbClient.get(applicationContext).imdbId(item.id, item.mediaType)
            if (imdb != null) {
                val type = if (item.mediaType == "tv") "series" else "movie"
                val deep = Intent(Intent.ACTION_VIEW, Uri.parse("stremio:///detail/$type/$imdb"))
                    .setPackage(STREMIO)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (start(deep)) return@launch
            }
            stremioLaunchIntent()?.let(::start)
        }
    }

    override fun exit() = finish()

    private fun stremioLaunchIntent(): Intent? =
        (packageManager.getLeanbackLaunchIntentForPackage(STREMIO) ?: packageManager.getLaunchIntentForPackage(STREMIO))
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun voiceIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

    private fun start(intent: Intent, launch: (Intent) -> Unit = ::startActivity): Boolean =
        try {
            launch(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No activity for $intent", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Not allowed to start $intent", e)
            false
        }

    companion object {
        const val EXTRA_TMDB_ID = "tmdb_id"
        const val EXTRA_TMDB_TYPE = "tmdb_type"
        /** A list picked by the assistant (JSON from [com.moath.tvhome.assistant.Library.toJson]) and its heading. */
        const val EXTRA_PICKS = "picks"
        const val EXTRA_PICKS_TITLE = "picks_title"
        private const val TAG = "TvSearch"
        private const val YOUTUBE_TV = "com.google.android.youtube.tv"
        private const val STREMIO = "com.stremio.one"
    }
}
