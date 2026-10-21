package com.moath.tvhome.settings

import com.moath.tvhome.BuildConfig
import com.moath.tvhome.assistant.AssistantSession
import com.moath.tvhome.assistant.ModelCatalog
import com.moath.tvhome.prefs.LauncherSettings
import java.util.Locale

internal fun aiProviderName(id: String): String = when (id) {
    "xpl" -> "Experiential Labs"
    "anthropic" -> "Anthropic (Claude)"
    else -> "Gemini"
}

private fun keyState(key: String) = if (key.isNotBlank()) "✓ مضاف" else "✗ غير مضاف"

internal fun assistantPage(c: SettingsController, p: LauncherSettings): PageSpec = PageSpec(
    "المساعد الذكي",
    Art.Remote,
    listOf(
        Note("n", "اضغط زر المساعد في الريموت للكتابة، واضغط مطولاً للتكلم. إذا تعطّل المزوّد الأساسي ينتقل المساعد تلقائياً للمزوّد التالي."),
        Item("try", "جرّب المساعد الآن", onClick = { c.open(AssistantSession.intent(c.context, voice = false)) }),
        Item("primary", "المزوّد الأساسي", aiProviderName(p.aiPrimary), chevron = true, onClick = { c.push(Dest.AiPrimary) }),
        Item(
            "remote", "زر المساعد في الريموت", if (p.aiRemoteKey) "يفتح رفيق" else "مساعد جوجل",
            footer = "عند التفعيل لا يفتح زر الصوت بحث جوجل ونتائج يوتيوب، بل يفتح هذا المساعد.",
            onClick = { c.prefs.update { it.copy(aiRemoteKey = !it.aiRemoteKey) } },
        ),
        Item(
            "listen", "طريقة الاستماع", if (p.voiceMode == "google") "عبر مساعد جوجل (مايك الريموت)" else "مايك الشاشة",
            footer = "مايك الريموت لا يعمل إلا عبر مساعد جوجل: يسمعك جوجل ويكتب كلامك، ثم يُغلق ويجيبك رفيق. «مايك الشاشة» يسمعك من الشاشة نفسها ويحتاج أن تتكلم بقربها.",
            onClick = { c.prefs.update { it.copy(voiceMode = if (it.voiceMode == "google") "own" else "google") } },
        ),
        Header("h.voice", "الرد بالصوت"),
        Item(
            "tts.mode", "قراءة الردود بصوت", when (p.ttsMode) { "always" -> "دائماً"; "voice" -> "عند السؤال بالصوت"; else -> "متوقف" },
            footer = "اضغط للتغيير. الرجوع أثناء القراءة يوقف الصوت.",
            onClick = {
                val o = listOf("always", "voice", "off")
                c.prefs.update { it.copy(ttsMode = o[(o.indexOf(p.ttsMode) + 1).mod(o.size)]) }
            },
        ),
        Item(
            "tts.engine", "محرك الصوت", if (p.ttsEngine == "cloud") "سحابي (أوضح)" else "صوت التلفزيون (مجاني)",
            footer = "السحابي: أصوات OpenAI عبر Experiential، عربي وإنجليزي واضح، تقريباً ثلث سنت للرد ولا يستهلك توكن المحادثة. صوت التلفزيون مجاني ويعمل بدون إنترنت.",
            onClick = { c.prefs.update { it.copy(ttsEngine = if (it.ttsEngine == "cloud") "system" else "cloud") } },
        ),
        Item("tts.voice", "الصوت", if (p.ttsEngine == "cloud") com.moath.tvhome.assistant.Speaker.cloudVoices.firstOrNull { it.id == p.ttsCloudVoice }?.label ?: p.ttsCloudVoice else "اختر واستمع", chevron = true, onClick = { c.push(Dest.AiVoice) }),
        Header("h.models", "النماذج"),
        Item("m.gemini", "نموذج Gemini", p.geminiModel, chevron = true, onClick = { c.push(Dest.AiModel("gemini")) }),
        Item(
            "m.xpl", "نموذج Experiential", p.xplModel, chevron = true,
            footer = "تظهر فقط النماذج التي لا تتجاوز \$${ModelCatalog.MAX_USD} لكل مليون توكن في الإدخال والإخراج.",
            onClick = { c.push(Dest.AiModel("xpl")) },
        ),
        Item(
            "m.xpl.audio", "نموذج تحويل الصوت (Experiential)", p.xplAudioModel, chevron = true,
            footer = "يحوّل كلامك إلى نص إذا تعذّر Gemini. نماذج Gemini في Experiential لا تقبل الصوت، لذلك القائمة نماذج التفريغ الصوتي (تُحسب بالدقيقة).",
            onClick = { c.push(Dest.AiModel("xpl.audio")) },
        ),
        Item("m.anthropic", "نموذج Anthropic", p.anthropicModel, chevron = true, onClick = { c.push(Dest.AiModel("anthropic")) }),
        Header("h.keys", "المفاتيح"),
        Item("k.gemini", "Gemini", keyState(BuildConfig.GEMINI_API_KEY)),
        Item("k.xpl", "Experiential Labs", keyState(BuildConfig.EXPLABS_API_KEY)),
        Item(
            "k.anthropic", "Anthropic", keyState(BuildConfig.ANTHROPIC_API_KEY),
            footer = "المفاتيح تُحفظ داخل التطبيق وقت البناء من ملف local.properties على الكمبيوتر فقط.",
        ),
    ),
)

internal fun aiPrimaryPage(c: SettingsController, p: LauncherSettings): PageSpec = PageSpec(
    "المزوّد الأساسي",
    Art.Remote,
    listOf("anthropic" to BuildConfig.ANTHROPIC_API_KEY, "xpl" to BuildConfig.EXPLABS_API_KEY, "gemini" to BuildConfig.GEMINI_API_KEY).map { (id, key) ->
        Item(
            "p:$id", aiProviderName(id), if (key.isBlank()) "بدون مفتاح" else null, checked = p.aiPrimary == id,
            onClick = {
                c.prefs.update { it.copy(aiPrimary = id) }
                c.pop()
            },
        )
    },
)

internal fun aiModelPage(c: SettingsController, p: LauncherSettings, provider: String): PageSpec {
    c.loadModels(provider)
    val current = when (provider) {
        "gemini" -> p.geminiModel
        "xpl" -> p.xplModel
        "xpl.audio" -> p.xplAudioModel
        else -> p.anthropicModel
    }
    fun choose(id: String) {
        c.prefs.update {
            when (provider) {
                "gemini" -> it.copy(geminiModel = id)
                "xpl" -> it.copy(xplModel = id)
                "xpl.audio" -> it.copy(xplAudioModel = id)
                else -> it.copy(anthropicModel = id)
            }
        }
        c.pop()
    }
    val list = c.aiModels[provider]
    val error = c.aiModelErrors[provider]
    val title = when (provider) {
        "gemini" -> "نموذج Gemini"
        "xpl" -> "نموذج Experiential"
        "xpl.audio" -> "نموذج تحويل الصوت"
        else -> "نموذج Anthropic"
    }
    return PageSpec(
        title,
        Art.Remote,
        buildList {
            when {
                error != null -> add(Note("err", "تعذّر جلب القائمة: $error"))
                list == null -> add(Note("loading", "جارِ تحميل قائمة النماذج…"))
                list.isEmpty() -> add(Note("empty", "لا توجد نماذج متاحة (أضف المفتاح أولاً)."))
            }
            if (list?.none { it.id == current } != false) add(Item("cur:$current", current, "الحالي", checked = true))
            list?.forEach { m ->
                val price = when {
                    m.perMinuteUsd != null -> String.format(Locale.US, "\$%.4f / دقيقة", m.perMinuteUsd)
                    m.inUsd != null && m.outUsd != null -> String.format(Locale.US, "\$%.2f / \$%.2f", m.inUsd, m.outUsd)
                    else -> null
                }
                add(
                    Item(
                        "m:${m.id}", m.name, price, checked = m.id == current,
                        footer = listOfNotNull(m.id, price?.let { if (m.perMinuteUsd != null) "السعر: $it من الكلام" else "السعر لكل مليون توكن: إدخال / إخراج $it" }, if (m.audio) "يفهم الصوت 🎙️" else null).joinToString("\n"),
                        onClick = { choose(m.id) },
                    ),
                )
            }
        },
    )
}

internal fun aiVoicePage(c: SettingsController, p: LauncherSettings): PageSpec {
    val cloud = p.ttsEngine == "cloud"
    if (!cloud) c.loadSystemVoices()
    return PageSpec(
        "صوت المساعد",
        Art.Remote,
        buildList {
            add(Note("n", "اضغط على أي صوت لاختياره وسماع عيّنة بالعربي والإنجليزي."))
            if (cloud) {
                com.moath.tvhome.assistant.Speaker.cloudVoices.forEach { v ->
                    add(Item("c:${v.id}", v.label, checked = v.id == p.ttsCloudVoice, onClick = {
                        c.prefs.update { it.copy(ttsCloudVoice = v.id) }
                        c.previewVoice("cloud", v.id)
                    }))
                }
            } else {
                val voices = c.systemVoices
                if (voices.isNullOrEmpty()) add(Note("loading", if (voices == null) "جارِ تحميل أصوات التلفزيون…" else "لا توجد أصوات عربية/إنجليزية مثبتة على التلفزيون."))
                voices?.forEach { v ->
                    val arabic = v.label.startsWith("عربي")
                    val selected = v.id == (if (arabic) p.ttsSystemVoiceAr else p.ttsSystemVoiceEn)
                    add(Item("s:${v.id}", v.label, checked = selected, onClick = {
                        c.prefs.update { if (arabic) it.copy(ttsSystemVoiceAr = v.id) else it.copy(ttsSystemVoiceEn = v.id) }
                        c.previewVoice("system", v.id)
                    }))
                }
            }
        },
    )
}
