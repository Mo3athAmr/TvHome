package com.moath.tvhome.dashboard

/** Arabic presentation rules for the dashboard: Arabic-Indic digits and correct number agreement. */
object ArabicText {
    private val weekdays = listOf("الأحد", "الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت")
    private val months = listOf(
        "يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو",
        "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر",
    )

    /** Western → Arabic-Indic digits; '.' and ',' become the Arabic decimal and thousands marks. */
    fun digits(s: String): String = buildString(s.length) {
        for (c in s) append(
            when (c) {
                in '0'..'9' -> '٠' + (c - '0')
                '.' -> '٫'
                ',' -> '٬'
                else -> c
            },
        )
    }

    fun digits(n: Int): String = digits(n.toString())

    /** Arabic count agreement: 1 and 2 use the noun alone / dual; 3–10 plural; 11+ singular accusative. */
    fun count(n: Int, one: String, two: String, few: String, many: String): String = when {
        n == 1 -> one
        n == 2 -> two
        n in 3..10 -> "${digits(n)} $few"
        else -> "${digits(n)} $many"
    }

    fun hours(n: Int) = count(n, "ساعة", "ساعتان", "ساعات", "ساعة")
    fun minutes(n: Int) = count(n, "دقيقة", "دقيقتان", "دقائق", "دقيقة")
    fun days(n: Int) = count(n, "يوم", "يومان", "أيام", "يومًا")

    /** "٨ ساعات و٤٤ دقيقة", "٤٤ دقيقة", "ساعتان". */
    fun duration(totalMinutes: Int): String {
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return when {
            h == 0 -> minutes(m)
            m == 0 -> hours(h)
            else -> "${hours(h)} و${minutes(m)}"
        }
    }

    /** 12-hour clock without the marker: 19:52 → "٧:٥٢". */
    fun clock(minuteOfDay: Int): String {
        val h = (minuteOfDay / 60) % 12
        val m = minuteOfDay % 60
        return digits("${if (h == 0) 12 else h}:${m.toString().padStart(2, '0')}")
    }

    /** ص before noon, م after. */
    fun meridiem(minuteOfDay: Int): String = if (minuteOfDay < 12 * 60) "ص" else "م"

    fun time(minuteOfDay: Int): String = "${clock(minuteOfDay)} ${meridiem(minuteOfDay)}"

    /** [weekday] 0 = Sunday. "الأحد، ١٨ أكتوبر". */
    fun gregorian(weekday: Int, day: Int, month: Int): String = "${weekdays[weekday]}، ${digits(day)} ${months[month]}"

    fun weekday(index: Int): String = weekdays[index]

    /** "٨١٬٠٠٢". */
    fun grouped(n: Long): String = digits(String.format(java.util.Locale.US, "%,d", n))

    /** "٠٫٣" — one decimal, no sign. */
    fun oneDecimal(x: Double): String = digits(String.format(java.util.Locale.US, "%.1f", kotlin.math.abs(x)))

    /** Instagram-style follower figure in Arabic: 14000 rounded → "١٤ ألف", 14100 → "١٤٫١ ألف", 1.2M → "١٫٢ مليون". */
    fun followers(n: Long, exact: Boolean): String = when {
        exact || n < 10_000 -> grouped(n)
        n < 1_000_000 -> "${trimmed(n / 1_000.0)} ألف"
        else -> "${trimmed(n / 1_000_000.0)} مليون"
    }

    private fun trimmed(x: Double): String {
        val s = String.format(java.util.Locale.US, "%.1f", x).removeSuffix(".0")
        return digits(s)
    }

    fun degrees(t: Int): String = "${digits(t)}°"
}
