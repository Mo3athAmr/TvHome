package com.moath.tvhome.assistant

import com.moath.tvhome.dashboard.ArabicText
import com.moath.tvhome.dashboard.DashboardClock
import org.json.JSONObject
import java.text.Normalizer
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** Arabic text ready for matching: no diacritics, unified letters, Western digits, single spaces. */
internal fun normWords(s: String): String {
    val digits = buildString { s.forEach { c -> append(if (c in '٠'..'٩') ('0' + (c - '٠')) else c) } }
    return Normalizer.normalize(digits, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase(Locale.ROOT)
        .replace(Regex("[أإآ]"), "ا").replace('ة', 'ه').replace('ى', 'ي')
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()
}

/** The same without spaces, for comparing names. */
internal fun normAr(s: String): String = normWords(s).replace(" ", "")

sealed interface Fast {
    data class Tool(val name: String, val args: JSONObject) : Fast
    data class Reply(val text: String) : Fast
}

/**
 * The commands people say all day, answered without asking a model: it removes seconds of waiting and
 * can't get them wrong. Anything not matched exactly (or naming an app that isn't installed) returns null
 * and goes to the assistant.
 */
object FastIntents {
    private val filler = listOf("لو سمحت", "من فضلك", "يا رفيق", "رفيق", "الله يعافيك", "ممكن", "تكفي", "تكفا", "بسرعه", "الحين", "الان", "ابغي", "ابغا", "ابي", "اريد", "ودي")
    private val OPEN = Regex("^(?:افتح|شغل|روح|وديني|ودني|فتح|خذني) (?:الي )?(?:تطبيق )?(.+)$")
    private val LIVE = Regex("^(?:افتح|شغل|روح|وديني|ودني|فتح) (?:(?:ل|الي )?قناه )?(.+) مباشر$")
    private val QURAN = Regex("^(?:شغل|افتح|اسمع|سمعني|ابغي) (?:ال)?سوره (.+)$")
    private val LEVEL = Regex("^(?:(?:خل|خلي|ضبط) )?(?:ال)?صوت (?:(?:علي|الي) )?(\\d{1,3})$")

    suspend fun parse(raw: String, isApp: suspend (String) -> Boolean, now: ZonedDateTime = ZonedDateTime.now(ZoneId.of("Asia/Riyadh"))): Fast? {
        var t = " " + normWords(raw) + " "
        for (p in filler) t = t.replace(" $p ", " ")
        t = t.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return null

        fun tool(name: String, vararg kv: Pair<String, Any>) = Fast.Tool(name, JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } })

        // Volume
        if (Regex("^(?:ارفع|زود|زيد|علي|اعلي) (?:ال)?صوت(?: شويه)?$").matches(t)) return tool("set_volume", "change" to "up")
        if (Regex("^(?:خفض|اخفض|نزل|وطي|قلل) (?:ال)?صوت(?: شويه)?$").matches(t)) return tool("set_volume", "change" to "down")
        if (Regex("^(?:اكتم|كتم) (?:ال)?صوت$").matches(t)) return tool("set_volume", "change" to "mute")
        LEVEL.matchEntire(t)?.let { return tool("set_volume", "level" to it.groupValues[1].toInt().coerceIn(0, 100)) }

        // Playback
        if (Regex("^(?:وقف|اوقف|ايقاف|بوز|وقفه|وقف موقت|ايقاف موقت|وقف الفيديو|اوقف الفيديو)$").matches(t)) return tool("media_control", "action" to "pause")
        if (Regex("^(?:كمل|كمله|استمر|استانف|كمل الفيديو)$").matches(t)) return tool("media_control", "action" to "play")
        if (Regex("^(?:التالي|بعده|الحلقه التاليه|الي بعده)$").matches(t)) return tool("media_control", "action" to "next")
        if (Regex("^(?:السابق|قبله|الحلقه السابقه|الي قبله)$").matches(t)) return tool("media_control", "action" to "previous")

        // Navigation
        // "للرئيسية" is ل + الرئيسية fused, so it is listed as such.
        if (Regex("^(?:(?:روح|ارجع|خذني|وديني|ودني) (?:للرييسيه|الي (?:ال)?رييسيه|(?:ال)?رييسيه)|(?:ال)?رييسيه)$").matches(t)) return tool("tv_navigation", "action" to "home")
        if (Regex("^(?:ارجع|رجوع|للخلف|رجعني)$").matches(t)) return tool("tv_navigation", "action" to "back")

        // Time and prayer: local, instant
        if (Regex("^(?:كم )?(?:ال)?ساعه(?: كم)?(?: الحين)?$|^كم الوقت$").matches(t)) {
            return Fast.Reply("الساعة الحين ${ArabicText.time(now.hour * 60 + now.minute)} ⏰")
        }
        if (Regex("^(?:متي|كم باقي علي|وش|كم|باقي كم علي) .*(?:اذان|صلاه).*$").matches(t)) {
            val m = DashboardClock.at(now.toInstant().toEpochMilli())
            return Fast.Reply("الصلاة القادمة ${m.next.arabic} الساعة ${ArabicText.time(m.nextAt)}، بعد ${ArabicText.duration(m.minutesToNext)} 🕌")
        }

        // Quran, live channels, apps
        QURAN.matchEntire(t)?.let { return tool("play_quran", "surah" to it.groupValues[1]) }
        LIVE.matchEntire(t)?.let { return tool("youtube_channel_live", "channel" to it.groupValues[1] + " مباشر") }
        OPEN.matchEntire(t)?.let { m ->
            val rest = m.groupValues[1]
            // "ودني لستريميو": the ل belongs to the preposition — but "لينكس" starts with it. Try both.
            for (name in listOfNotNull(rest, rest.removePrefix("ل").takeIf { rest.startsWith("ل") && rest.length > 3 })) {
                if (isApp(name)) return tool("open_app", "name" to name)
            }
        }
        return null
    }
}
