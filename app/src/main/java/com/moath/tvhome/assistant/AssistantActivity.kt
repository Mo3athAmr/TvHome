package com.moath.tvhome.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import coil3.compose.AsyncImage
import com.moath.tvhome.design.LiquidGlass.liquidGlass
import com.moath.tvhome.design.TvFonts
import com.moath.tvhome.design.UiDirection
import com.moath.tvhome.notify.NoticeKind
import com.moath.tvhome.notify.Notifier
import com.moath.tvhome.search.SearchActivity
import com.moath.tvhome.tmdb.TmdbClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class Phase { Typing, Listening, Thinking, Answer }

/**
 * The assistant panel: a glass sheet at the bottom of the screen, over whatever is playing.
 * Short press of the remote's assistant button opens it for typing, a long press opens it listening.
 */
class AssistantActivity : ComponentActivity() {
    private var phase by mutableStateOf(Phase.Typing)
    private var query by mutableStateOf("")
    private var answer by mutableStateOf<AssistantAnswer?>(null)
    private var asked by mutableStateOf("")
    private var status by mutableStateOf<String?>(null)
    private var level by mutableFloatStateOf(0f)
    private var job: Job? = null
    private val engine by lazy { AssistantSession.engine(this) }
    private val speaker by lazy { Speaker(this) }
    private var speakJob: Job? = null
    /** The current question was spoken (so a "voice only" reply setting answers it aloud). */
    private var askedByVoice = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Each opening of the panel is a new conversation; follow-ups inside it keep their context.
        if (savedInstanceState == null) engine.reset()
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides UiDirection.layout) {
                Panel(
                    phase = phase, query = query, asked = asked, answer = answer, status = status, level = level,
                    onQuery = { if (phase != Phase.Thinking && phase != Phase.Listening) { query = it; status = null } },
                    onSend = { askedByVoice = false; submit(query) },
                    onMic = ::listen,
                    onClear = { query = ""; answer = null; status = null; engine.reset(); phase = Phase.Typing },
                    onCard = ::openCard,
                )
            }
        }
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        AssistantSession.visible = true
    }

    override fun onPause() {
        AssistantSession.visible = false
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        intent.getStringExtra(AssistantSession.EXTRA_TEXT)?.let { askedByVoice = intent.getBooleanExtra(AssistantSession.EXTRA_SPOKEN, false); submit(it); return }
        intent.getStringExtra(AssistantSession.EXTRA_WAV)?.let { path ->
            job?.cancel()
            job = lifecycleScope.launch {
                phase = Phase.Thinking
                status = "لحظة أكتب كلامك…"
                val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { java.io.File(path).readBytes() }.getOrNull() }
                val text = bytes?.let { engine.transcribe(it) }
                if (text.isNullOrBlank()) { phase = Phase.Typing; status = "ما سمعتك زين 🤔" } else { askedByVoice = true; submit(text) }
            }
            return
        }
        when {
            intent.getBooleanExtra(AssistantSession.EXTRA_TOGGLE, false) -> toggleVoice()
            intent.getBooleanExtra(AssistantSession.EXTRA_STOP, false) -> VoiceInput.finishListening()
            intent.getBooleanExtra(AssistantSession.EXTRA_VOICE, false) -> listen()
        }
    }

    override fun onDestroy() {
        speakJob?.cancel()
        speaker.shutdown()
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        speaker.stop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && speakJob?.isActive == true) {
            // First Back only silences the reply.
            speakJob?.cancel()
            speaker.stop()
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            when (phase) {
                Phase.Listening, Phase.Thinking -> { job?.cancel(); phase = if (answer != null) Phase.Answer else Phase.Typing; status = null }
                else -> finish()
            }
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.dispatchKeyEvent(event)
    }

    private fun listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        job?.cancel()
        speakJob?.cancel()
        speaker.stop()
        // The TV keyboard (if it was up for typing) gives way to listening.
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(window.decorView.windowToken, 0)
        job = lifecycleScope.launch {
            phase = Phase.Listening
            status = "أسمعك… تكلّم باتجاه الشاشة 🎙️"
            query = ""
            // What's playing pauses while we listen (as with Google's assistant), so the TV's own sound isn't heard.
            val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            val focus = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANT).build())
                .build()
            am.requestAudioFocus(focus)
            // Google's recognizer first: it is the only way to the remote's microphone on this TV.
            var heard = try {
                VoiceInput.systemRecognizer(this@AssistantActivity, onLevel = { level = it }, onPartial = { query = it })
            } finally {
                am.abandonAudioFocusRequest(focus)
            }
            level = 0f
            if (heard is VoiceInput.Heard.Failed && engine.configured) {
                logW("system recognizer failed (${(heard as VoiceInput.Heard.Failed).code}); recording ourselves")
                val wav = VoiceInput.record { level = it }
                level = 0f
                heard = wav?.let { status = "لحظة أكتب كلامك…"; phase = Phase.Thinking; engine.transcribe(it) }
                    ?.let { VoiceInput.Heard.Text(it) } ?: VoiceInput.Heard.Nothing
            }
            when (val h = heard) {
                is VoiceInput.Heard.Text -> { askedByVoice = true; submit(h.text) }
                else -> {
                    query = ""
                    phase = if (answer != null) Phase.Answer else Phase.Typing
                    status = "ما سمعتك زين 🤔 اضغط زر المساعد وتكلّم بعدها مباشرة"
                }
            }
        }
    }

    /** The assistant button again: start listening, or — while listening — stop and send what was said. */
    private fun toggleVoice() {
        if (phase == Phase.Listening) VoiceInput.finishListening() else listen()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) listen()
    }

    private fun submit(text: String) {
        val q = text.trim()
        if (q.isEmpty()) return
        job?.cancel()
        speakJob?.cancel()
        speaker.stop()
        query = q
        asked = q
        status = null
        phase = Phase.Thinking
        job = lifecycleScope.launch {
            val a = engine.ask(q, AssistantSession.screen)
            answer = a
            if (a.opened) {
                // Something now plays on top; the reply follows as a notice and the panel gets out of the way.
                Notifier.post(this@AssistantActivity, "رفيق ✨", a.text, NoticeKind.ASSISTANT, 9_000)
                finish()
            } else {
                phase = Phase.Answer
                query = ""
                val mode = com.moath.tvhome.prefs.LauncherPrefs.get(this@AssistantActivity).state.value.ttsMode
                if (!a.error && (mode == "always" || (mode == "voice" && askedByVoice))) {
                    speakJob = lifecycleScope.launch { speaker.speak(a.text) }
                }
            }
        }
    }

    private fun openCard(card: AssistantCard) {
        val i = when (card) {
            is AssistantCard.Video -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=${card.id}"))
                .apply { if (packageManager.getLeanbackLaunchIntentForPackage(YOUTUBE) != null) setPackage(YOUTUBE) }
            is AssistantCard.Show -> Intent(this, SearchActivity::class.java)
                .putExtra(SearchActivity.EXTRA_TMDB_ID, card.tmdbId).putExtra(SearchActivity.EXTRA_TMDB_TYPE, card.type)
        }
        runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        finish()
    }

    private companion object {
        const val REQ_MIC = 7
        const val YOUTUBE = "com.google.android.youtube.tv"
    }
}

// ── UI ────────────────────────────────────────────────────────────────────────────────────

private fun text(size: Int, color: Color = Color.White, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = TvFonts.Text, fontSize = size.sp, lineHeight = (size * 1.45f).sp, fontWeight = weight, color = color)

private val PanelShape = RoundedCornerShape(28.dp)
private val Dim = Color(0xB3FFFFFF)

@Composable
private fun Panel(
    phase: Phase,
    query: String,
    asked: String,
    answer: AssistantAnswer?,
    status: String?,
    level: Float,
    onQuery: (String) -> Unit,
    onSend: () -> Unit,
    onMic: () -> Unit,
    onClear: () -> Unit,
    onCard: (AssistantCard) -> Unit,
) {
    val fieldFocus = remember { FocusRequester() }
    val ime = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val sendFocus = remember { FocusRequester() }
    val cardFocus = remember { FocusRequester() }
    val shown = remember { androidx.compose.animation.core.MutableTransitionState(false) }.apply { targetState = true }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color(0xE6000000)))) {
        AnimatedVisibility(
            visibleState = shown,
            enter = slideInVertically(spring(dampingRatio = 0.85f, stiffness = 420f)) { it / 2 } + fadeIn(tween(200)),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier
                    .imePadding().padding(bottom = 26.dp)
                    .width(900.dp)
                    .background(Color(0xEB101014), PanelShape)
                    .liquidGlass(PanelShape, intensity = 0.55f)
                    .padding(horizontal = 26.dp, vertical = 20.dp),
            ) {
                // The answer, when there is one.
                if (answer != null && phase != Phase.Listening) {
                    if (asked.isNotEmpty() && phase == Phase.Answer) {
                        BasicText("«$asked»", style = text(14, Color(0x99FFFFFF)), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 48.dp, bottom = 8.dp))
                    }
                    Row(verticalAlignment = Alignment.Top) {
                        Orb(Phase.Answer, 0f, Modifier.size(34.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            BasicText(
                                answer.text,
                                style = text(19, if (answer.error) Color(0xFFFFB4A8) else Color.White),
                                maxLines = 9,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.heightIn(max = 250.dp),
                            )
                            answer.provider?.let {
                                Spacer(Modifier.height(4.dp))
                                BasicText(it, style = text(11, Color(0x66FFFFFF)))
                            }
                        }
                    }
                    if (answer.cards.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            itemsIndexed(answer.cards) { i, c ->
                                CardTile(c, onClick = { onCard(c) }, modifier = if (i == 0) Modifier.focusRequester(cardFocus) else Modifier)
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }

                // The query line.
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).background(Color(0x1AFFFFFF), RoundedCornerShape(28.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Orb(phase, level, Modifier.size(36.dp))
                    Spacer(Modifier.width(12.dp))
                    val editable = phase == Phase.Typing || phase == Phase.Answer
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        val hint = when {
                            status != null -> status
                            phase == Phase.Thinking -> "لحظة أفكر… 🤔"
                            else -> "اكتب سؤالك هنا، أو اضغط زر المساعد وتكلّم 😊"
                        }
                        if (query.isEmpty()) BasicText(hint, style = text(18, Dim), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (editable) {
                            // The TV's own keyboard (with its usual wrap-around navigation); Send on it submits.
                            androidx.compose.foundation.text.BasicTextField(
                                value = query,
                                onValueChange = onQuery,
                                singleLine = true,
                                textStyle = text(18, Color.White, FontWeight.Medium),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { ime?.hide(); onSend() }),
                                modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus),
                            )
                        } else if (query.isNotEmpty()) {
                            // While listening: everything said so far, on up to two lines (the latest words stay visible).
                            BasicText(query.takeLast(160), style = text(18, Color.White, FontWeight.Medium), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (phase == Phase.Thinking) ThinkingDots()
                }
                if (status != null && query.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    BasicText(status, style = text(13, Dim))
                }

                // Actions (hidden while listening).
                if (phase != Phase.Listening) {
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Action("إرسال", primary = true, onClick = onSend, modifier = Modifier.focusRequester(sendFocus))
                        Action("🎙️  تكلّم", onClick = onMic)
                        Action("محادثة جديدة", onClick = onClear)
                    }
                }
            }
        }
    }

    LaunchedEffect(phase, answer) {
        delay(60)
        runCatching {
            when {
                phase == Phase.Answer && answer?.cards?.isNotEmpty() == true -> cardFocus.requestFocus()
                phase == Phase.Typing -> { fieldFocus.requestFocus(); ime?.show() }
                phase == Phase.Answer -> sendFocus.requestFocus()
                else -> Unit
            }
        }
    }
}

@Composable
private fun Action(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "lift")
    Box(
        modifier
            .scale(scale)
            .onFocusChanged { focused = it.isFocused }
            .height(44.dp)
            .background(
                when {
                    focused -> Color.White
                    primary -> Color(0x33FFFFFF)
                    else -> Color(0x1AFFFFFF)
                },
                RoundedCornerShape(22.dp),
            )
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = text(16, if (focused) Color.Black else Color.White, FontWeight.Medium))
    }
}

@Composable
private fun CardTile(card: AssistantCard, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, label = "lift")
    val shape = RoundedCornerShape(14.dp)
    val (image, title, sub, wide) = when (card) {
        is AssistantCard.Video -> listOf(card.thumb, card.title, (if (card.live) "🔴 مباشر · " else "") + card.channel, true)
        is AssistantCard.Show -> listOf(card.poster, card.name, if (card.type == "tv") "مسلسل" else "فيلم", false)
    }
    Column(
        modifier
            .scale(scale)
            .onFocusChanged { focused = it.isFocused }
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .width(if (wide == true) 210.dp else 110.dp),
    ) {
        AsyncImage(
            model = image as String?,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(if (wide == true) 118.dp else 160.dp)
                .clip(shape)
                .background(Color(0x22FFFFFF), shape)
                .then(if (focused) Modifier.border(3.dp, Color.White, shape) else Modifier),
        )
        Spacer(Modifier.height(6.dp))
        BasicText(title as String, style = text(13, Color.White, FontWeight.Medium), maxLines = 2, overflow = TextOverflow.Ellipsis)
        BasicText(sub as String, style = text(11, Dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Four coloured dots that breathe while idle, bounce with the voice while listening and wave while thinking. */
@Composable
internal fun Orb(phase: Phase, level: Float, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "orb")
    val wave by t.animateFloat(0f, 1f, infiniteRepeatable(tween(if (phase == Phase.Thinking) 700 else 1600), RepeatMode.Restart), label = "wave")
    val colors = listOf(Color(0xFF4285F4), Color(0xFFEA4335), Color(0xFFFBBC05), Color(0xFF34A853))
    Canvas(modifier) {
        val w = size.width / 9f
        val gap = (size.width - 4 * w) / 3f
        colors.forEachIndexed { i, c ->
            val phaseShift = ((wave + i * 0.25f) % 1f)
            val bounce = kotlin.math.sin(phaseShift * 2 * Math.PI).toFloat()
            val h = when (phase) {
                Phase.Listening -> w + (size.height - w) * (0.25f + 0.75f * level) * (0.6f + 0.4f * kotlin.math.abs(bounce))
                Phase.Thinking -> w + w * 1.6f * kotlin.math.abs(bounce)
                else -> w * (1f + 0.15f * bounce)
            }
            val x = i * (w + gap)
            drawRoundRect(c, Offset(x, (size.height - h) / 2f), Size(w, h), CornerRadius(w / 2f))
        }
    }
}

@Composable
private fun ThinkingDots() {
    val t = rememberInfiniteTransition(label = "dots")
    val p by t.animateFloat(0f, 3f, infiniteRepeatable(tween(1200), RepeatMode.Restart), label = "p")
    BasicText("•".repeat(p.toInt() + 1), style = text(18, Dim))
}
