package com.moath.tvhome.settings

import android.content.Intent
import com.moath.tvhome.prefs.LauncherSettings
import com.moath.tvhome.screensaver.Screensaver

// ── Screen saver ────────────────────────────────────────────────────────────────────────────

internal fun saverPage(c: SettingsController, p: LauncherSettings): PageSpec {
    val photos = p.screensaverMode == LauncherSettings.SAVER_PHOTOS
    val custom = Screensaver.customFile(c.context)
    return PageSpec(
        "شاشة التوقف",
        Art.Landscape,
        buildList {
            add(Item("mode", "الوضع", if (photos) "صور جوجل" else "مواقيت الصلاة", chevron = true, onClick = { c.push(Dest.SaverMode) }))
            if (photos) {
                add(Header("h.photos", "صور جوجل"))
                add(
                    Item(
                        "album", "الألبوم ومدة تبديل الصور", chevron = true,
                        footer = "يفتح إعدادات صور جوجل في التلفاز: اختر الألبوم من حسابك المربوط وسرعة تبديل الصور.",
                        onClick = { c.open(Intent(Screensaver.AMBIENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                    ),
                )
                add(Header("h.widgets", "الأدوات فوق الصور"))
                listOf("clock" to "الساعة والتاريخ", "prayer" to "الصلاة القادمة", "weather" to "الطقس", "matches" to "مباريات اليوم").forEach { (k, label) ->
                    add(
                        Item("w:$k", label, checked = k in p.photoWidgets, onClick = {
                            c.prefs.update { s -> s.copy(photoWidgets = if (k in s.photoWidgets) s.photoWidgets - k else s.photoWidgets + k) }
                        }),
                    )
                }
            } else {
                add(Header("h.prayer", "مواقيت الصلاة"))
                add(
                    Item(
                        "bg", "الخلفية",
                        if (p.prayerBackground == "custom" && custom != null) "صورتي / الفيديو الخاص" else "فيديو Aerial",
                        chevron = true, onClick = { c.push(Dest.SaverBackground) },
                    ),
                )
                add(
                    Item(
                        "rotating", "البطاقة الزجاجية المتبدلة", c.onOff(p.rotatingWidget),
                        footer = "تتبدل كل ٣٠ ثانية بين الطقس ومباريات اليوم وأحدث مقطع يوتيوب.",
                        onClick = { c.prefs.update { it.copy(rotatingWidget = !it.rotatingWidget) } },
                    ),
                )
            }
            add(Header("h.now", " "))
            add(
                Item("start", "تشغيل الآن", onClick = {
                    Screensaver.apply(c.context)
                    c.open(Screensaver.startIntent())
                }),
            )
        },
    )
}

internal fun saverModePage(c: SettingsController, p: LauncherSettings): PageSpec = PageSpec(
    "وضع شاشة التوقف",
    Art.Landscape,
    buildList {
        add(LauncherSettings.SAVER_PRAYER to "مواقيت الصلاة (فيديو أو صورة + الطقس + بطاقة متبدلة)")
        if (Screensaver.photosAvailable(c.context)) add(LauncherSettings.SAVER_PHOTOS to "صور جوجل مع الأدوات")
    }.map { (mode, label) ->
        Item("m:$mode", label, checked = p.screensaverMode == mode, onClick = {
            c.prefs.update { it.copy(screensaverMode = mode) }
            Screensaver.apply(c.context, mode)
            c.pop()
        })
    },
)

internal fun saverBackgroundPage(c: SettingsController, p: LauncherSettings): PageSpec {
    val custom = Screensaver.customFile(c.context)
    return PageSpec(
        "الخلفية",
        Art.Landscape,
        listOf(
            Item("aerial", "فيديو Aerial من Apple", checked = p.prayerBackground != "custom" || custom == null, onClick = {
                c.prefs.update { it.copy(prayerBackground = "aerial") }
                c.pop()
            }),
            Item(
                "custom", "صورتي أو الفيديو الخاص",
                value = custom?.name ?: "لا يوجد ملف",
                checked = p.prayerBackground == "custom" && custom != null,
                footer = "ضع ملفاً باسم screensaver.jpg أو screensaver.png أو screensaver.mp4 في مجلد التطبيق على التلفاز (Android/data/com.moath.tvhome/files).",
                onClick = if (custom == null) null else {
                    {
                        c.prefs.update { it.copy(prayerBackground = "custom") }
                        c.pop()
                    }
                },
            ),
        ),
    )
}

// ── Prayer & notifications ──────────────────────────────────────────────────────────────────

internal fun prayerPage(c: SettingsController, p: LauncherSettings): PageSpec = PageSpec(
    "الأذان وقفل الصلاة",
    Art.Landscape,
    listOf(
        Note("n", "الظهر والعصر والمغرب والعشاء فقط؛ لا تنبيه للفجر."),
        Item("sound", "صوت الأذان", c.onOff(p.adhanSound), footer = "بصوت المؤذن محمد قصاص من المسجد النبوي.",
            onClick = { c.prefs.update { it.copy(adhanSound = !it.adhanSound) } }),
        Item("lock", "قفل الشاشة وقت الصلاة", c.onOff(p.prayerLock),
            footer = "من الأذان " + (if (p.lockAfterIqama == 0) "حتى انتهاء عدّاد الإقامة" else "حتى ${p.lockAfterIqama} دقائق بعد الإقامة") + ": تتوقف الوسائط وتظهر شاشة الصلاة وتعود الشاشة تلقائياً.",
            onClick = { c.prefs.update { it.copy(prayerLock = !it.prayerLock) } }),
        Item("wake", "تشغيل الشاشة وقت الأذان", c.onOff(p.wakeForAdhan),
            onClick = { c.prefs.update { it.copy(wakeForAdhan = !it.wakeForAdhan) } }),
        Item("iqama", "وقت الإقامة بعد الأذان", chevron = true,
            footer = "لا يوجد مصدر رسمي مفتوح لأوقات الإقامة لكل مدينة؛ القيم الافتراضية هي المعتادة في مساجد المملكة، وعدّلها حسب مسجدك.",
            onClick = { c.push(Dest.Iqama) }),
        Item("after", "مدة القفل بعد الإقامة", if (p.lockAfterIqama == 0) "عند الإقامة" else "${p.lockAfterIqama} دقائق", onClick = {
            val options = listOf(0, 5, 10, 15)
            val next = options[(options.indexOf(p.lockAfterIqama) + 1).mod(options.size)]
            c.prefs.update { it.copy(lockAfterIqama = next) }
        }),
    ),
)

internal fun iqamaPage(c: SettingsController, p: LauncherSettings): PageSpec = PageSpec(
    "وقت الإقامة",
    Art.Landscape,
    listOf("dhuhr" to "الظهر", "asr" to "العصر", "maghrib" to "المغرب", "isha" to "العشاء").map { (key, label) ->
        Item("iq:$key", label, "${p.iqamaFor(key)} دقيقة", footer = "اضغط لتغيير المدة (٥ إلى ٣٠ دقيقة).", onClick = {
            val options = listOf(5, 10, 15, 20, 25, 30)
            val next = options[(options.indexOf(p.iqamaFor(key)) + 1).mod(options.size)]
            val map = listOf("dhuhr", "asr", "maghrib", "isha").associateWith { k -> if (k == key) next else p.iqamaFor(k) }
            c.prefs.update { it.copy(iqamaMinutes = map.entries.joinToString(",") { e -> "${e.key}=${e.value}" }) }
        })
    },
)

internal fun noticesPage(c: SettingsController, p: LauncherSettings): PageSpec = PageSpec(
    "الإشعارات",
    Art.Landscape,
    listOf(
        Item("all", "الإشعارات المنبثقة", c.onOff(p.notifications), footer = "تظهر من أسفل الشاشة فوق أي تطبيق ثم تختفي.",
            onClick = { c.prefs.update { it.copy(notifications = !it.notifications) } }),
        Item("adhan", "إشعار الأذان", c.onOff(p.notifyAdhan), footer = "يظهر عندما يكون قفل الصلاة متوقفاً.",
            onClick = { c.prefs.update { it.copy(notifyAdhan = !it.notifyAdhan) } }),
        Item("matches", "نتائج المباريات", c.onOff(p.notifyMatches), footer = "الأهداف وبداية ونهاية مباريات الأندية المتابعة.",
            onClick = { c.prefs.update { it.copy(notifyMatches = !it.notifyMatches) } }),
        Item(
            "soon", "تذكير قبل المباراة", if (p.matchReminderMin > 0) "قبل ${p.matchReminderMin} دقيقة" else c.onOff(false),
            footer = "لكل مباراة لفريق أضفته (وريال مدريد وبرشلونة دائماً). اضغط للتغيير.",
            onClick = {
                val options = listOf(15, 30, 60, 0)
                val next = options[(options.indexOf(p.matchReminderMin) + 1).mod(options.size)]
                c.prefs.update { it.copy(matchReminderMin = next) }
            },
        ),
    ),
)

// ── Friday ──────────────────────────────────────────────────────────────────────────────────

internal fun jumuahPage(c: SettingsController, p: LauncherSettings): PageSpec = PageSpec(
    "صلاة الجمعة وسورة الكهف",
    Art.Landscape,
    listOf(
        Item(
            "lock", "قفل الشاشة وقت الجمعة", c.onOff(p.jumuahLock),
            footer = "يبدأ قبل أذان الظهر يوم الجمعة ويستمر بعده، بدون صوت أذان. للخروج الطارئ اضغط مطولاً على رجوع ٥ ثوانٍ.",
            onClick = { c.prefs.update { it.copy(jumuahLock = !it.jumuahLock) } },
        ),
        Item("before", "يبدأ القفل قبل الأذان", "${p.jumuahBefore} دقيقة", onClick = {
            val o = listOf(15, 20, 30, 45, 60)
            c.prefs.update { it.copy(jumuahBefore = o[(o.indexOf(p.jumuahBefore) + 1).mod(o.size)]) }
        }),
        Item("after", "ينتهي القفل بعد الأذان", "${p.jumuahAfter} دقيقة", footer = "وقت الخطبة والصلاة؛ اضغط للتغيير.", onClick = {
            val o = listOf(30, 40, 50, 60, 75)
            c.prefs.update { it.copy(jumuahAfter = o[(o.indexOf(p.jumuahAfter) + 1).mod(o.size)]) }
        }),
        Item(
            "standby", "وضع الاستعداد أثناء القفل", c.onOff(p.jumuahStandby),
            footer = "بعد دقيقة من بدء القفل تنطفئ الشاشة. لو شغّلها أحد خلال الوقت تظهر شاشة الجمعة ثم تنطفئ مرة أخرى.",
            onClick = { c.prefs.update { it.copy(jumuahStandby = !it.jumuahStandby) } },
        ),
        Header("h.kahf", "سورة الكهف"),
        Item("listen", "استمع الآن", onClick = { c.open(com.moath.tvhome.quran.QuranPlayerActivity.intent(c.context)) }),
        Item(
            "reciter", "القارئ", com.moath.tvhome.quran.QuranAudio.reciter(p.kahfReciter).name,
            footer = "مشاري العفاسي · ماهر المعيقلي · عبدالرحمن السديس. اضغط للتبديل.",
            onClick = {
                val r = com.moath.tvhome.quran.QuranAudio.reciters
                val i = r.indexOfFirst { it.id == p.kahfReciter }
                c.prefs.update { it.copy(kahfReciter = r[(i + 1).mod(r.size)].id) }
            },
        ),
        Item(
            "hero", "سورة الكهف في الشاشة الرئيسية", c.onOff(p.kahfHero),
            footer = "تظهر أول القسم الكبير من مغرب الخميس إلى مغرب الجمعة، مع تذكير لطيف صباح الجمعة.",
            onClick = { c.prefs.update { it.copy(kahfHero = !it.kahfHero) } },
        ),
    ),
)

// ── Feature status ──────────────────────────────────────────────────────────────────────────

internal fun healthRow(c: SettingsController): Item {
    val items = runCatching { com.moath.tvhome.health.Health.check(c.context) }.getOrDefault(emptyList())
    val broken = items.count { it.level == com.moath.tvhome.health.Level.Broken }
    val warn = items.count { it.level == com.moath.tvhome.health.Level.Warn }
    val value = when {
        broken > 0 -> "$broken متوقفة"
        warn > 0 -> "⚠️ $warn تنبيه"
        else -> "✅ كل شيء يعمل"
    }
    return Item(
        "health", "فحص الميزات", value, chevron = true,
        footer = items.filter { it.level != com.moath.tvhome.health.Level.Ok }.joinToString("\n") { "• ${it.title}: ${it.detail}" }.ifEmpty { null },
        onClick = { c.open(com.moath.tvhome.health.HealthActivity.intent(c.context, fromHome = false)) },
    )
}
