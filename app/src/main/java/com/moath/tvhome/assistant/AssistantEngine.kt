package com.moath.tvhome.assistant

import android.content.Context
import com.moath.tvhome.dashboard.ArabicText
import com.moath.tvhome.dashboard.DashboardClock
import com.moath.tvhome.prefs.LauncherPrefs
import com.moath.tvhome.prefs.LauncherSettings
import kotlinx.coroutines.CancellationException
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One finished exchange: what to say, cards to show, whether something was opened (panel may close). */
data class AssistantAnswer(val text: String, val cards: List<AssistantCard>, val opened: Boolean, val provider: String?, val error: Boolean = false)

/**
 * The agent loop: send the conversation to the primary provider, run any tools it asks for,
 * feed the results back, repeat (at most [MAX_STEPS]). When a provider fails (no key, HTTP error,
 * quota), the same turn goes to the next configured provider so the user still gets an answer.
 */
class AssistantEngine(private val ctx: Context, private val tools: AssistantTools) {
    private val history = ArrayList<ChatMsg>()
    /** Provider id → until when it is skipped after a rate-limit (HTTP 429) answer. */
    private val cooldown = HashMap<String, Long>()

    private fun noteFailure(id: String, e: Exception) {
        if (e.message?.startsWith("HTTP 429") == true) {
            // A daily quota says how long; honour it so every question doesn't first hit the wall.
            val wait = ((e as? AiException)?.retryAfterMs ?: COOLDOWN_MS).coerceIn(COOLDOWN_MS, 24 * 3_600_000L)
            cooldown[id] = android.os.SystemClock.elapsedRealtime() + wait
            if (wait > 3_600_000L) com.moath.tvhome.health.Health.report(ctx, "quota.$id", "انتهت الحصة اليومية المجانية")
        }
        if (e.message?.let { it.startsWith("HTTP 401") || it.startsWith("HTTP 403") } == true) {
            com.moath.tvhome.health.Health.report(ctx, "ai.$id", e.message.orEmpty())
        }
    }

    private fun available(): List<AiProvider> {
        val now = android.os.SystemClock.elapsedRealtime()
        val all = providers()
        return all.filter { (cooldown[it.id] ?: 0) < now }.ifEmpty { all }
    }

    fun providers(p: LauncherSettings = LauncherPrefs.get(ctx).state.value): List<AiProvider> {
        val all = mapOf(
            "gemini" to GeminiProvider(p.geminiModel),
            "xpl" to OpenAiCompatProvider(p.xplModel, p.xplAudioModel),
            "anthropic" to AnthropicProvider(p.anthropicModel),
        )
        // The owner's order: Anthropic, then Experiential, then Gemini (free tier: 20 requests a day).
        val order = listOf(p.aiPrimary) + listOf("anthropic", "xpl", "gemini").filter { it != p.aiPrimary }
        return order.mapNotNull { all[it] }.filter { it.configured }
    }

    val configured: Boolean get() = providers().isNotEmpty()

    fun reset() = history.clear()

    /** Speech → text through the first provider that takes audio. */
    suspend fun transcribe(wav: ByteArray): String? {
        for (p in available()) {
            val t = try { p.transcribe(wav) } catch (e: CancellationException) { throw e } catch (e: Exception) { logW("transcribe ${p.id}", e); noteFailure(p.id, e); null }
            if (t != null) return t.takeUnless { it.isBlank() || it.contains("(لا شيء)") }
        }
        return null
    }

    private suspend fun web(q: String): String? {
        for (p in available()) {
            val r = try { p.webSearch(q) } catch (e: CancellationException) { throw e } catch (e: Exception) { logW("web ${p.id}", e); null }
            if (!r.isNullOrBlank()) return r
        }
        return null
    }

    suspend fun ask(text: String, screen: ScreenInfo?, voice: Boolean = false): AssistantAnswer {
        if (Safety.blocked(text)) {
            return AssistantAnswer("آسف يا صديقي، هذا الطلب ما أقدر أساعد فيه 🙏 اطلب أي شيء ثاني وأبشر.", emptyList(), false, null)
        }
        val list = available()
        if (list.isEmpty()) return AssistantAnswer("المساعد يحتاج مفتاح API أولاً 🔑 — أضفه من الإعدادات ثم جرّب مرة ثانية.", emptyList(), false, null, error = true)
        // Keep the conversation short: the TV assistant is a series of quick asks.
        while (history.size > 24) history.removeAt(0)
        while (history.isNotEmpty() && history.first().role != "user") history.removeAt(0)
        val start = history.size
        history += ChatMsg("user", text, turnStart = true)
        val system = systemPrompt(screen, voice)
        var lastError: Exception? = null
        for (provider in list) {
            val turn = Turn()
            try {
                return runLoop(provider, system, turn).also {
                    lastError = null
                    com.moath.tvhome.health.Health.recover(ctx, "ai.${provider.id}")
                    com.moath.tvhome.health.Health.recover(ctx, "quota.${provider.id}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logW("provider ${provider.id} failed", e)
                noteFailure(provider.id, e)
                lastError = e
                // Something already happened on the TV: never repeat it with the next provider.
                if (turn.opened) {
                    return AssistantAnswer(turn.lastResult ?: "تم ✅", turn.cards, true, "${provider.label} · ${provider.model}")
                }
                // Roll back this turn's partial tool exchange, keep the user's question.
                while (history.size > start + 1) history.removeAt(history.lastIndex)
            }
        }
        return AssistantAnswer("ما قدرت أوصل للمساعد الآن 😕 (${lastError?.message?.take(120)}). تأكد من الإنترنت أو المفاتيح.", emptyList(), false, null, error = true)
    }

    /** What one provider's attempt has done so far, so a failure halfway doesn't redo it. */
    private class Turn {
        val cards = ArrayList<AssistantCard>()
        var opened = false
        var lastResult: String? = null
    }

    private suspend fun runLoop(provider: AiProvider, system: String, turn: Turn): AssistantAnswer {
        val cards = turn.cards
        var calledAny = false
        var nudged = false
        repeat(MAX_STEPS) { step ->
            val reply = provider.chat(system, history, if (step < MAX_STEPS - 1) tools.specs else emptyList()).let(::salvage)
            history += ChatMsg("assistant", reply.text, toolCalls = reply.toolCalls, raw = reply.raw, rawProvider = provider.id)
            // Models sometimes say "I played it" without calling anything: send them back once to really do it.
            if (reply.toolCalls.isEmpty() && !calledAny && !nudged && CLAIMS_ACTION.containsMatchIn(reply.text)) {
                nudged = true
                android.util.Log.i("Assistant", "${provider.id} claimed an action without a tool; nudging")
                history += ChatMsg("user", "(تنبيه من النظام: لم تستدعِ أي أداة، فلم يحدث شيء على التلفزيون. استدعِ الأداة المناسبة الآن لتنفيذ الطلب.)")
                return@repeat
            }
            calledAny = calledAny || reply.toolCalls.isNotEmpty()
            if (reply.toolCalls.isEmpty()) {
                return AssistantAnswer(plain(reply.text).ifBlank { if (turn.opened) "تم ✅" else "…" }, cards, turn.opened, "${provider.label} · ${provider.model}")
            }
            val images = ArrayList<ByteArray>()
            val says = ArrayList<String>()
            var everyCallSaid = true
            for (call in reply.toolCalls) {
                android.util.Log.i("Assistant", "${provider.id} → ${call.name} ${call.args}")
                val r = tools.run(call, ::web)
                android.util.Log.i("Assistant", "   ← ${r.text.take(160)}")
                cards += r.cards
                turn.opened = turn.opened || r.opened
                turn.lastResult = r.text
                r.image?.let(images::add)
                history += ChatMsg("tool", r.text, toolCallId = call.id, toolName = call.name)
                if (r.say != null) says += r.say else everyCallSaid = false
            }
            // Actions that already happened confirm themselves: no second model round trip just to say "done"
            // (it costs seconds, and it is where the model used to narrate its tools).
            if (everyCallSaid && says.isNotEmpty()) {
                val text = says.distinct().joinToString(" · ")
                history += ChatMsg("assistant", text)
                return AssistantAnswer(text, cards, turn.opened, "${provider.label} · ${provider.model}")
            }
            // Screenshots go back as a user turn: function results can't carry images on every provider.
            images.forEach { history += ChatMsg("user", "صورة الشاشة الحالية:", image = it) }
        }
        return AssistantAnswer("تم ✅", cards, turn.opened, "${provider.label} · ${provider.model}")
    }

    private fun systemPrompt(screen: ScreenInfo?, voice: Boolean): String {
        val now = ZonedDateTime.now(ZoneId.of("Asia/Riyadh"))
        val m = DashboardClock.at(System.currentTimeMillis())
        val day = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy، HH:mm", Locale("ar")).format(now)
        return """
# من أنت
أنت «رفيق»: المساعد الشخصي لشاشة التلفزيون في بيت المستخدم بمدينة الخبر في السعودية. شخصيتك صديق قريب من أهل البيت: دافئ، خفيف دم، ذكي، يفهم من نص كلمة، ويختصر. تتكلم باللهجة السعودية البيضاء، وإذا كلّمك المستخدم بلغة ثانية ترد بلغته. إيموجي واحد على الأكثر في الرد، وفقط إذا ناسب.

# كيف ترد
- ردك يظهر مكتوباً على التلفزيون ويُقرأ بصوت عالٍ: جمل قصيرة طبيعية سهلة النطق. بدون Markdown ولا جداول ولا روابط ولا رموز تقنية.
- بعد أي أمر نفّذته: جملة قصيرة جداً تؤكد ما حصل (مثل «أبشر، شغّلته لك»). لا تشرح خطواتك.
- إذا سأل أكثر من سؤال في رسالة واحدة أجب عنها كلها.
${if (voice) "- هذا طلب صوتي: لا تتجاوز ٢٥ كلمة. نفّذ مباشرة، ولا تسأل إلا سؤال توضيح واحد قصير عند الحاجة الفعلية (انظر «الاقتراحات»)." else "- هذا طلب مكتوب: من جملة إلى ثلاث جمل غالباً."}

# ممنوعات قطعية
- لا تذكر أبداً الأدوات أو أسماءها أو أي كلمة إنجليزية تقنية، ولا تقل «استدعيت» أو «نفّذت أداة» أو «حسب النظام»، ولا تكتب استدعاء أداة كنص. الأدوات تُستدعى فعلاً فقط، والمستخدم لا يرى إلا كلامك العادي.
- لا تقل «شغّلت» أو «فتحت» أو «عرضت» إلا بعد أن تستدعي الأداة في هذا الطلب وتنجح. كل طلب جديد يحتاج استدعاءً جديداً حتى لو تكرر.
- لا تخترع أسماء أعمال أو نتائج أو معلومات. الأفلام والمسلسلات تقترحها من المكتبة فقط (find_titles) وليس من ذاكرتك، لأن المكتبة فيها فلتر المحتوى.
- البيت عائلي: لا تقترح ولا تشغّل محتوى جنسي أو فاضح أو إباحي بأي شكل، وارفض بلطف واقترح بديلاً. وارفض ما هو محرّم أو ضار.
- إذا فشل شيء قل ذلك بلطف في جملة، واقترح بديلاً واحداً.

# قدراتك (استخدمها مباشرة بدون استئذان)
عندك وصول كامل للتلفزيون: التطبيقات، يوتيوب، مكتبة ضخمة للأفلام والمسلسلات والأنمي، ستريميو، كاسبر فليكس للمباريات، القرآن، الطقس، أوقات الصلاة، المباريات، الصوت والتشغيل، رؤية الشاشة، والبحث في الإنترنت.

## الاقتراحات والبحث في المكتبة
- أي طلب اقتراح أو «وش أشوف» أو بحث بمواصفات (تصنيف، بلد، جديد/قديم، عائلي، شي يشبه عمل معيّن) ← find_titles، والنتائج تنعرض بوسترات على الشاشة.
- إذا الطلب ناقص جداً (مثل «اقترح لي شي» بدون أي وصف) اسأل سؤالاً واحداً قصيراً فيه خيارات، مثل: «تبي فيلم ولا مسلسل ولا أنمي؟ وأي جو: أكشن، كوميدي، رعب، دراما؟ جديد ولا قديم؟». إذا فيه وصف كافٍ (مثل «فيلم عائلي» أو «أنمي أكشن جديد») نفّذ مباشرة وكمّل الباقي بافتراضات معقولة (الأشهر، وجديد نسبياً). سؤال واحد فقط لكل طلب، وإذا قال «أي شي» أو «انت اختار» اختر أنت.
- التحويل: كوري ← origin=korean، ياباني ← japanese، أنمي ← origin=anime (kind=tv للمسلسلات أو movie لأفلام الأنمي)، صيني ← chinese، آسيوي ← asian، تركي ← turkish، هندي ← indian، عربي ← arabic، مصري ← egyptian، خليجي ← gulf، أمريكي ← american. جديد ← era=new، قديم/كلاسيكي ← classic، «التسعينات» ← 90s. «الأعلى تقييماً/أفضل» ← sort=top_rated، «أحدث شي نزل» ← sort=newest.
- الفلتر: «عائلي/للأطفال/نشوفه مع الأهل» ← family=true. «ما فيه لقطات» ← clean=true. في كل الاقتراحات الأخرى خلّ clean=true افتراضياً، إلا إذا طلب المستخدم صراحة نوعاً لا يناسبه ذلك (مثل رعب أو أكشن للكبار)، والفلتر الأساسي للمحتوى الفاضح يبقى دائماً.
- بعد العرض: اذكر اسمين أو ثلاثة من الأبرز باختصار، وقل له يختار من الشاشة أو يطلب منك. «افتح الثاني/افتح فلان» ← open_title بـ tmdb_id وkind من آخر نتائج. «شغّله» ← stremio_play.
- اسم عمل محدد يبي صفحته أو معلومات عنه ← open_title.

## التشغيل في ستريميو (أي عمل، وليس عمل بعينه)
- أي فيلم أو مسلسل أو أنمي يطلب مشاهدته أو تشغيله ← stremio_play بالعنوان الأصلي بالإنجليزية (مثل: ون بيس ← One Piece، هجوم العمالقة ← Attack on Titan، لعبة الحبار ← Squid Game). kind=series للمسلسلات والأنمي، وmovie للأفلام، وanime=true للأنمي الياباني.
- «آخر حلقة» أو «آخر حلقة من آخر موسم» ← بدون season ولا episode. «الموسم ٢ الحلقة ٥» ← season=2 وepisode=5. «آخر حلقة من الموسم الثالث» ← season=3 وepisode=last. «الحلقة ١٠٠٠» بدون موسم ← episode=1000 فقط.

## يوتيوب
- «آخر مقطع/آخر فيديو لفلان» ← youtube_channel_latest(channel).
- «آخر حلقة من برنامج X لفلان» ← youtube_channel_latest(channel=فلان, show=X). مثال: «افتح آخر حلقة أنا أقطع لمحمد عدنان» ← channel=محمد عدنان، show=أنا أقطع. المقصود أحدث حلقة نزلت من البرنامج، وليس مقطعاً عنوانه «آخر حلقة».
- «الجزيرة مباشر» وأي قناة يطلبها مباشر ← youtube_channel_live.
- مقطع أو أغنية أو موضوع ← youtube_search (play=true إذا طلب التشغيل).

## باقي القدرات
- فتح تطبيق ← open_app. مباراة فريق ← watch_match. جدول المباريات والنتائج ← get_matches (وإن ما كانت البطولة متابعة ← web_search).
- القرآن أو سورة ← play_quran. الطقس ← get_weather. أوقات الصلاة ← get_prayer_times.
- الصوت ← set_volume. إيقاف/تشغيل/التالي ← media_control. الرئيسية/رجوع/الإعدادات/شاشة التوقف ← tv_navigation.
- أي سؤال عن اللي على الشاشة («وش هذا؟»، «مين هذا الممثل؟»، «وش اسم هالفيلم؟»، «اقرأ لي المكتوب») ← look_at_screen أولاً ثم أجب مما تراه.
- الأخبار والأسعار وأي معلومة تتغير ← web_search. الحساب والمعلومات العامة الثابتة أجب مباشرة بدقة.

# السياق الآن
$day (توقيت السعودية). الهجري: ${m.hijri}. الصلاة القادمة: ${m.next.arabic} الساعة ${ArabicText.time(m.nextAt)}.
${screen?.let { "التطبيق الظاهر على الشاشة: ${it.appLabel ?: it.packageName ?: "غير معروف"}." } ?: ""}
""".trim()
    }

    /**
     * A model that writes a tool call as text ("[استدعيت الأداة find_titles {…}]") instead of making it:
     * run it as the call it meant, so the request still happens.
     */
    private fun salvage(r: AiReply): AiReply {
        if (r.toolCalls.isNotEmpty()) return r
        val m = TEXT_CALL.find(r.text) ?: return r
        val name = m.groupValues[1]
        if (tools.specs.none { it.name == name }) return r
        val args = runCatching { org.json.JSONObject(m.groupValues[2]) }.getOrNull() ?: return r
        android.util.Log.i("Assistant", "${r.provider} wrote a tool call as text; running $name")
        return r.copy(text = r.text.replace(m.value, "").trim(), toolCalls = listOf(ToolCall("text-${System.nanoTime()}", name, args)), raw = null)
    }

    private companion object {
        val TEXT_CALL = Regex("""\[?\s*(?:استدعيت الأداة|calling tool|tool_call|call)\s*:?\s*([a-z_]+)\s*(\{.*\})\s*]?""", RegexOption.DOT_MATCHES_ALL)
        /** The TV shows plain text: drop Markdown the models add anyway. */
        fun plain(s: String) = s.replace(Regex("\\*\\*|__|`+"), "").replace(Regex("(?m)^\\s*#+\\s*"), "").replace(Regex("(?m)^\\s*[-*]\\s+"), "• ").trim()
        const val MAX_STEPS = 6
        const val COOLDOWN_MS = 60_000L
        val CLAIMS_ACTION = Regex("شغّ?لت|فتحت|رفعت الصوت|خفضت الصوت|وقّ?فت|بدأ التشغيل|يشتغل الحين|يشتغل الآن")
    }
}
