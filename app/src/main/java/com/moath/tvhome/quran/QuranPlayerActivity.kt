package com.moath.tvhome.quran

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.moath.tvhome.design.LiquidGlass.liquidGlass
import com.moath.tvhome.design.TvFonts
import com.moath.tvhome.design.UiDirection
import com.moath.tvhome.prefs.LauncherPrefs
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Full-screen recitation: the Friday artwork, the surah's name, three reciters to pick from and a
 * progress bar. Streams one MP3; the screen stays on while it plays and Back ends it.
 */
class QuranPlayerActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private var reciter by mutableStateOf(QuranAudio.reciters.first())
    private var playing by mutableStateOf(false)
    private var buffering by mutableStateOf(true)
    private var error by mutableStateOf<String?>(null)
    private var ended by mutableStateOf(false)
    private var surah = QuranAudio.KAHF

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        surah = intent.getIntExtra(EXTRA_SURAH, QuranAudio.KAHF).coerceIn(1, 114)
        reciter = QuranAudio.reciter(intent.getStringExtra(EXTRA_RECITER) ?: LauncherPrefs.get(this).state.value.kahfReciter)
        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
                override fun onPlaybackStateChanged(state: Int) {
                    buffering = state == Player.STATE_BUFFERING
                    ended = state == Player.STATE_ENDED
                    if (state == Player.STATE_READY) error = null
                }
                override fun onPlayerError(e: PlaybackException) {
                    error = "تعذّر تشغيل التلاوة — تأكد من الإنترنت ثم اضغط تشغيل"
                }
            })
        }
        load(reciter)
        val art = runCatching { assets.open("kahf.jpg").use { BitmapFactory.decodeStream(it) }.asImageBitmap() }.getOrNull()
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides UiDirection.layout) {
                Screen(
                    art = art, surah = surah, reciter = reciter, playing = playing, buffering = buffering, ended = ended, error = error,
                    position = { player.currentPosition }, duration = { player.duration.takeIf { it > 0 } ?: 0L },
                    onReciter = { r -> if (r != reciter) { reciter = r; load(r); LauncherPrefs.get(this).update { it.copy(kahfReciter = r.id) } } },
                    onToggle = ::toggle,
                )
            }
        }
    }

    private fun load(r: Reciter) {
        error = null
        ended = false
        player.setMediaItem(MediaItem.fromUri(QuranAudio.url(surah, r)))
        player.prepare()
        player.playWhenReady = true
    }

    private fun toggle() {
        when {
            ended -> { player.seekTo(0); player.play() }
            error != null -> load(reciter)
            player.isPlaying -> player.pause()
            else -> player.play()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> { toggle(); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { player.seekTo(player.currentPosition + 30_000); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { player.seekTo((player.currentPosition - 30_000).coerceAtLeast(0)); return true }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        // Leaving the screen (Home, another app) ends the recitation; it never plays hidden.
        player.pause()
        if (!isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SURAH = "surah"
        const val EXTRA_RECITER = "reciter"

        fun intent(context: Context, surah: Int = QuranAudio.KAHF, reciter: String? = null): Intent =
            Intent(context, QuranPlayerActivity::class.java)
                .putExtra(EXTRA_SURAH, surah)
                .apply { reciter?.let { putExtra(EXTRA_RECITER, it) } }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}

private val Gold = Color(0xFFE8C878)
private fun text(size: Int, color: Color = Color.White, weight: FontWeight = FontWeight.Medium) =
    TextStyle(fontFamily = TvFonts.Text, fontSize = size.sp, lineHeight = (size * 1.35f).sp, fontWeight = weight, color = color)

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

@Composable
private fun Screen(
    art: androidx.compose.ui.graphics.ImageBitmap?,
    surah: Int,
    reciter: Reciter,
    playing: Boolean,
    buffering: Boolean,
    ended: Boolean,
    error: String?,
    position: () -> Long,
    duration: () -> Long,
    onReciter: (Reciter) -> Unit,
    onToggle: () -> Unit,
) {
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { pos = position(); dur = duration(); delay(500) } }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { playFocus.requestFocus() } }

    Box(Modifier.fillMaxSize().background(Color(0xFF0B1F1C))) {
        if (art != null) Image(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xCC000000))))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 72.dp, end = 72.dp, bottom = 64.dp).fillMaxWidth()) {
            BasicText(if (surah == QuranAudio.KAHF) "جمعة مباركة 🌿" else "القرآن الكريم", style = text(18, Gold))
            Spacer(Modifier.height(6.dp))
            BasicText("سورة ${QuranAudio.name(surah)}", style = text(64, weight = FontWeight.Bold))
            Spacer(Modifier.height(4.dp))
            BasicText(
                when {
                    error != null -> error
                    ended -> "انتهت التلاوة ✓ — اضغط تشغيل للإعادة"
                    buffering -> "بصوت الشيخ ${reciter.name} · جارِ التحميل…"
                    else -> "بصوت الشيخ ${reciter.name}"
                },
                style = text(20, if (error != null) Color(0xFFFFB4A8) else Color(0xCCFFFFFF), FontWeight.Normal),
            )
            Spacer(Modifier.height(26.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill(if (playing) "⏸  إيقاف مؤقت" else "▶  تشغيل", selected = true, modifier = Modifier.focusRequester(playFocus), onClick = onToggle)
                Spacer(Modifier.width(18.dp))
                QuranAudio.reciters.forEach { r -> Pill(r.name, selected = r == reciter, onClick = { onReciter(r) }) }
            }
            Spacer(Modifier.height(28.dp))
            val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth().height(6.dp).background(Color(0x33FFFFFF), RoundedCornerShape(3.dp))) {
                Box(Modifier.fillMaxWidth(frac).height(6.dp).background(Gold, RoundedCornerShape(3.dp)))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                BasicText(clock(pos), style = text(15, Color(0x99FFFFFF)))
                BasicText(if (dur > 0) "\u2066-" + clock(dur - pos) + "\u2069" else "", style = text(15, Color(0x99FFFFFF)))
            }
        }
    }
}

@Composable
private fun Pill(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, label = "pill")
    val shape = RoundedCornerShape(26.dp)
    Box(
        modifier
            .scale(scale)
            .onFocusChanged { focused = it.isFocused }
            .then(
                when {
                    focused -> Modifier.background(Color.White, shape)
                    selected -> Modifier.background(Color(0x40E8C878), shape).liquidGlass(shape, intensity = 0.4f)
                    else -> Modifier.background(Color(0x26FFFFFF), shape).liquidGlass(shape, intensity = 0.4f)
                },
            )
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        BasicText(label, style = text(18, if (focused) Color.Black else if (selected) Gold else Color.White, FontWeight.SemiBold))
    }
}
