package com.moath.tvhome.settings

import com.moath.tvhome.prefs.LauncherSettings
import com.moath.tvhome.sports.Clubs
import com.moath.tvhome.sports.Leagues
import com.moath.tvhome.youtube.YoutubeRepository

private fun heroLabel(source: String) =
    if (source == LauncherSettings.HERO_YOUTUBE) "جديد يوتيوب" else "الأكثر رواجاً"

/** Opened from the profile picture on Home. */
internal fun customizePage(c: SettingsController, p: LauncherSettings): PageSpec {
    val youtube = YoutubeRepository.get(c.context)
    val channels = youtube.channelCount()
    return PageSpec(
        "تخصيص",
        Art.HomeGrid,
        listOf(
            Header("h.hero", "الشاشة الرئيسية"),
            Item(
                "hero",
                "محتوى القسم الكبير",
                heroLabel(p.heroSource),
                chevron = true,
                footer = "اختر ما يظهر في القسم الكبير أعلى الشاشة الرئيسية: أحدث مقاطع قنواتك على يوتيوب، أو الأفلام والمسلسلات الأكثر رواجاً.",
                onClick = { c.push(Dest.HeroSource) },
            ),
            Header("h.health", "حالة الميزات"),
            healthRow(c),
            Header("h.more", "الشاشة والإشعارات"),
            Item("saver", "شاشة التوقف", if (p.screensaverMode == LauncherSettings.SAVER_PHOTOS) "صور جوجل" else "مواقيت الصلاة", chevron = true,
                footer = "اضغط Home أو رجوع وأنت أعلى الشاشة الرئيسية لتشغيلها.", onClick = { c.push(Dest.Saver) }),
            Item("prayer", "الأذان وقفل الصلاة", if (p.prayerLock) "القفل مفعّل" else "بدون قفل", chevron = true, onClick = { c.push(Dest.Prayer) }),
            Item("jumuah", "صلاة الجمعة وسورة الكهف", if (p.jumuahLock) "القفل مفعّل" else "بدون قفل", chevron = true, onClick = { c.push(Dest.Jumuah) }),
            Item("notices", "الإشعارات", c.onOff(p.notifications), chevron = true, onClick = { c.push(Dest.Notices) }),
            Item("ai", "المساعد الذكي", aiProviderName(p.aiPrimary), chevron = true, onClick = { c.push(Dest.Assistant) }),
            Header("h.sports", "المباريات"),
            Item(
                "sports.row",
                "صف المباريات",
                c.onOff(p.sportsRow),
                footer = "صف «المباريات» في الشاشة الرئيسية بعد جديد يوتيوب.",
                onClick = { c.prefs.update { it.copy(sportsRow = !it.sportsRow) } },
            ),
            Item(
                "sports.widget",
                "ويدجت الساعة",
                c.onOff(p.sportsWidget),
                footer = "حتى ٣ مباريات بجانب الساعة: دوري الأبطال المهم وريال مدريد وبرشلونة في أي بطولة. الأولوية لهما دائماً.",
                onClick = { c.prefs.update { it.copy(sportsWidget = !it.sportsWidget) } },
            ),
            Item(
                "sports.leagues",
                "البطولات",
                "${p.sportsLeagues.size} بطولة",
                chevron = true,
                footer = "اختر البطولات التي تظهر مبارياتها.",
                onClick = { c.push(Dest.SportsLeagues) },
            ),
            Item(
                "sports.clubs",
                "الأندية",
                "${p.sportsClubs.size} نادي",
                chevron = true,
                footer = "الأندية التي تظهر مبارياتها في البطولات التي تعرض الأندية الكبيرة فقط.",
                onClick = { c.push(Dest.SportsClubs) },
            ),
            Item(
                "sports.channels",
                "قناة البث",
                "تلقائي",
                footer = "تُقرأ قناة البث (مثل beIN SPORTS 2 HD) تلقائياً لكل مباراة عندما تُعلن، ولا تحتاج إعداداً.",
            ),
            Item(
                "sports.refresh",
                "تحديث المباريات الآن",
                footer = "تتحدث النتائج الحية كل ٤٥ ثانية أثناء المباراة، والجدول كل نصف ساعة.",
                onClick = { c.prefs.update { it.copy(sportsRefresh = it.sportsRefresh + 1) } },
            ),
            Header("h.youtube", "جديد يوتيوب"),
            Item(
                "yt.channels",
                "القنوات",
                if (channels > 0) "$channels قناة" else "لم تُضف قنوات",
                footer = "تُقرأ القنوات من الملف youtube_channels.txt في مجلد التطبيق. أضف معرّفات القنوات أو @المعرّفات أو ملف subscriptions.csv من Google Takeout.",
            ),
            Item(
                "yt.refresh",
                "تحديث الآن",
                footer = "يُحدَّث تلقائياً كل ١٢ دقيقة، ويختفي المقطع بعد فتحه أو إخفائه.",
                onClick = { c.prefs.update { it.copy(youtubeRefresh = it.youtubeRefresh + 1) } },
            ),
            Item(
                "yt.reset",
                "إعادة عرض المقاطع المشاهدة",
                footer = "يمسح قائمة المقاطع التي فتحتها أو أخفيتها لتظهر من جديد.",
                onClick = {
                    youtube.resetSeen()
                    c.prefs.update { it.copy(youtubeRefresh = it.youtubeRefresh + 1) }
                },
            ),
        ),
    )
}

internal fun heroSourcePage(c: SettingsController, p: LauncherSettings): PageSpec =
    PageSpec(
        "محتوى القسم الكبير",
        Art.HomeGrid,
        listOf(
            LauncherSettings.HERO_YOUTUBE to "جديد يوتيوب (حتى ١٠ مقاطع)",
            LauncherSettings.HERO_TRENDING to "الأفلام والمسلسلات الأكثر رواجاً",
        ).map { (value, label) ->
            Item(
                "src:$value",
                label,
                checked = p.heroSource == value,
                onClick = {
                    c.prefs.update { it.copy(heroSource = value) }
                    c.pop()
                },
            )
        },
    )

internal fun sportsLeaguesPage(c: SettingsController, p: LauncherSettings): PageSpec =
    PageSpec(
        "البطولات",
        Art.HomeGrid,
        Leagues.all.map { l ->
            Item(
                "lg:${l.slug}",
                l.nameAr,
                checked = l.slug in p.sportsLeagues,
                footer = if (l.allMatches) "تظهر كل المباريات." else "تظهر مباريات الأندية المتابَعة فقط.",
                onClick = { c.prefs.update { it.copy(sportsLeagues = if (l.slug in it.sportsLeagues) it.sportsLeagues - l.slug else it.sportsLeagues + l.slug) } },
            )
        },
    )

internal fun sportsClubsPage(c: SettingsController, p: LauncherSettings): PageSpec =
    PageSpec(
        "الأندية",
        Art.HomeGrid,
        Clubs.catalog.map { club ->
            val fixed = club.id in Clubs.priority
            Item(
                "club:${club.id}",
                club.nameAr,
                value = if (fixed) "أولوية دائمة" else null,
                checked = fixed || club.id in p.sportsClubs,
                onClick = if (fixed) null else {
                    { c.prefs.update { it.copy(sportsClubs = if (club.id in it.sportsClubs) it.sportsClubs - club.id else it.sportsClubs + club.id) } }
                },
            )
        },
    )
