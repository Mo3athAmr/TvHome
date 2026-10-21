package com.moath.tvhome.quran

import java.util.Locale

data class Reciter(val id: String, val name: String)

/**
 * Recitations streamed from mp3quran.net's CDN (Hafs, murattal): no ads, no account, nothing stored
 * on the TV. A surah is one MP3, so playback is a plain stream.
 */
object QuranAudio {
    const val KAHF = 18

    val reciters = listOf(
        Reciter("mishary-alafasy", "مشاري العفاسي"),
        Reciter("maher-muaiqly", "ماهر المعيقلي"),
        Reciter("abdulrahman-sudais", "عبدالرحمن السديس"),
    )

    fun reciter(id: String?): Reciter = reciters.firstOrNull { it.id == id } ?: reciters.first()

    fun url(surah: Int, reciter: Reciter): String =
        "https://cdn.mp3quran.net/audio/${reciter.id}/r1/" + String.format(Locale.US, "%03d", surah.coerceIn(1, 114)) + ".mp3"

    val surahs = listOf(
        "الفاتحة", "البقرة", "آل عمران", "النساء", "المائدة", "الأنعام", "الأعراف", "الأنفال", "التوبة", "يونس",
        "هود", "يوسف", "الرعد", "إبراهيم", "الحجر", "النحل", "الإسراء", "الكهف", "مريم", "طه",
        "الأنبياء", "الحج", "المؤمنون", "النور", "الفرقان", "الشعراء", "النمل", "القصص", "العنكبوت", "الروم",
        "لقمان", "السجدة", "الأحزاب", "سبأ", "فاطر", "يس", "الصافات", "ص", "الزمر", "غافر",
        "فصلت", "الشورى", "الزخرف", "الدخان", "الجاثية", "الأحقاف", "محمد", "الفتح", "الحجرات", "ق",
        "الذاريات", "الطور", "النجم", "القمر", "الرحمن", "الواقعة", "الحديد", "المجادلة", "الحشر", "الممتحنة",
        "الصف", "الجمعة", "المنافقون", "التغابن", "الطلاق", "التحريم", "الملك", "القلم", "الحاقة", "المعارج",
        "نوح", "الجن", "المزمل", "المدثر", "القيامة", "الإنسان", "المرسلات", "النبأ", "النازعات", "عبس",
        "التكوير", "الانفطار", "المطففين", "الانشقاق", "البروج", "الطارق", "الأعلى", "الغاشية", "الفجر", "البلد",
        "الشمس", "الليل", "الضحى", "الشرح", "التين", "العلق", "القدر", "البينة", "الزلزلة", "العاديات",
        "القارعة", "التكاثر", "العصر", "الهمزة", "الفيل", "قريش", "الماعون", "الكوثر", "الكافرون", "النصر",
        "المسد", "الإخلاص", "الفلق", "الناس",
    )

    fun name(surah: Int): String = surahs.getOrElse(surah - 1) { "" }

    private fun norm(s: String) = s.replace(Regex("[\\u064B-\\u0652\\u0670]"), "").replace(Regex("[أإآ]"), "ا")
        .replace("سورة", "").replace("ال", "").replace(" ", "").trim()

    /** "الكهف", "سورة يس", "18" → surah number, or null. */
    fun find(query: String): Int? {
        query.trim().toIntOrNull()?.let { if (it in 1..114) return it }
        val q = norm(query)
        if (q.isEmpty()) return null
        surahs.indexOfFirst { norm(it) == q }.takeIf { it >= 0 }?.let { return it + 1 }
        return surahs.indexOfFirst { norm(it).contains(q) || q.contains(norm(it)) }.takeIf { it >= 0 }?.plus(1)
    }
}

/** Surah al-Kahf is read from Thursday's Maghrib to Friday's Maghrib (the Islamic Friday). */
object KahfTime {
    private val zone = java.util.TimeZone.getTimeZone("Asia/Riyadh")

    private fun maghribMs(day: java.util.Calendar): Long {
        val m = com.moath.tvhome.prayer.PrayerTimes.forDay(day[java.util.Calendar.YEAR], day[java.util.Calendar.MONTH] + 1, day[java.util.Calendar.DAY_OF_MONTH], com.moath.tvhome.prayer.PrayerConfig.AlKhobarUmmAlQura)
            .getValue(com.moath.tvhome.prayer.Prayer.Maghrib)
        return (day.clone() as java.util.Calendar).apply {
            set(java.util.Calendar.HOUR_OF_DAY, m / 60); set(java.util.Calendar.MINUTE, m % 60); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    /** The Friday this moment belongs to as "yyyy-mm-dd", or null outside Thursday-Maghrib…Friday-Maghrib. */
    fun fridayOf(nowMs: Long): String? {
        val c = java.util.Calendar.getInstance(zone).apply { timeInMillis = nowMs }
        val dow = c[java.util.Calendar.DAY_OF_WEEK]
        val friday = when {
            dow == java.util.Calendar.FRIDAY && nowMs < maghribMs(c) -> c
            dow == java.util.Calendar.THURSDAY && nowMs >= maghribMs(c) -> (c.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_MONTH, 1) }
            else -> return null
        }
        return String.format(Locale.US, "%04d-%02d-%02d", friday[java.util.Calendar.YEAR], friday[java.util.Calendar.MONTH] + 1, friday[java.util.Calendar.DAY_OF_MONTH])
    }
}
