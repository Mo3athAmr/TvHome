package com.moath.tvhome.prayer

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.icu.text.DateFormat as IcuDateFormat
import android.icu.util.IslamicCalendar
import android.icu.util.ULocale
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.moath.tvhome.R
import java.util.Date
import java.util.Locale

/**
 * Al Khobar prayer times for the idle screen, as a tvOS dark-glass card. Plain Views refreshed once a
 * minute by the caller — nothing animates per frame. Arabic labels, Western digits.
 */
class PrayerPanel(private val context: Context) {
    private val res = context.resources
    private val is24h = DateFormat.is24HourFormat(context)
    private val arabic: Typeface? = ResourcesCompat.getFont(context, R.font.plex_arabic_medium)
    private val arabicBold: Typeface? = ResourcesCompat.getFont(context, R.font.plex_arabic_semibold)
    private val digits: Typeface? = ResourcesCompat.getFont(context, R.font.plex_arabic_medium)
    private val digitsBold: Typeface? = ResourcesCompat.getFont(context, R.font.plex_arabic_semibold)

    private val eyebrow = label(arabic, 12f, SECONDARY)
    private val nextName = label(arabicBold, 24f, PRIMARY)
    private val nextTime = label(digitsBold, 24f, PRIMARY)
    private val countdown = label(arabic, 13f, SECONDARY)
    private val dateLine = label(arabic, 11.5f, TERTIARY)
    private val rows = Prayer.entries.associateWith { Row() }

    val view: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        background = GradientDrawable().apply {
            cornerRadius = dp(18f)
            setColor(GLASS)
            setStroke(dp(1f).toInt(), RIM)
        }
        val pad = dp(16f).toInt()
        setPadding(pad, dp(14f).toInt(), pad, dp(12f).toInt())

        addView(eyebrow)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(nextName, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(nextTime)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2f).toInt() })
        addView(countdown)
        addView(View(context).apply { setBackgroundColor(SEPARATOR) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1f).toInt()).apply {
            topMargin = dp(10f).toInt(); bottomMargin = dp(6f).toInt()
        })
        rows.values.forEach { addView(it.root, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ROW_DP).toInt())) }
        addView(dateLine, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f).toInt() })
    }

    fun layoutParams(rightMargin: Int, topMargin: Int) =
        FrameLayout.LayoutParams(dp(WIDTH_DP).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.RIGHT).apply {
            this.rightMargin = rightMargin
            this.topMargin = topMargin
        }

    fun update(nowMillis: Long = System.currentTimeMillis()) {
        // A box that booted without network can sit at its 2017 factory clock; wrong times are worse
        // than none, so the card stays hidden until the clock has been set.
        val clockSet = java.util.Calendar.getInstance().apply { timeInMillis = nowMillis }[java.util.Calendar.YEAR] >= MIN_VALID_YEAR
        view.visibility = if (clockSet) View.VISIBLE else View.GONE
        if (!clockSet) return
        val s = PrayerSchedule.at(nowMillis)
        val now = s.current
        if (now != null) {
            eyebrow.text = "حان الآن وقت صلاة"
            nextName.text = now.arabic
            nextTime.text = clock(s.times.getValue(now))
            countdown.text = "${s.next.arabic} بعد ${duration(s.minutesUntilNext)}"
        } else {
            eyebrow.text = if (s.next.isPrayer) "الصلاة القادمة · الخبر" else "الشروق · الخبر"
            nextName.text = s.next.arabic
            // After Isha the next row is tomorrow's Fajr; its clock time is now + countdown.
            nextTime.text = clock((minutesNow(nowMillis) + s.minutesUntilNext) % (24 * 60))
            countdown.text = "بعد ${duration(s.minutesUntilNext)}"
        }
        val highlight = now ?: s.next
        rows.forEach { (p, row) -> row.bind(p.arabic, clock(s.times.getValue(p)), p == highlight) }
        dateLine.text = hijri(nowMillis)
    }

    private fun minutesNow(millis: Long): Int {
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Riyadh")).apply { timeInMillis = millis }
        return c[java.util.Calendar.HOUR_OF_DAY] * 60 + c[java.util.Calendar.MINUTE]
    }

    private inner class Row {
        val name = label(arabic, 15f, PRIMARY)
        val time = label(digits, 15f, PRIMARY)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = dp(10f).toInt()
            setPadding(pad, 0, pad, 0)
            addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(time)
        }

        fun bind(label: String, at: String, active: Boolean) {
            name.text = label
            time.text = at
            val fg = if (active) ON_PILL else PRIMARY
            name.setTextColor(fg)
            time.setTextColor(fg)
            root.background = if (active) GradientDrawable().apply { cornerRadius = dp(9f); setColor(PILL) } else null
        }
    }

    private fun label(face: Typeface?, sp: Float, color: Int) = TextView(context).apply {
        typeface = face
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        includeFontPadding = false
        textDirection = View.TEXT_DIRECTION_ANY_RTL
        setPadding(0, dp(2f).toInt(), 0, dp(2f).toInt())
    }

    private fun clock(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        val hh = if (is24h) h else (h % 12).let { if (it == 0) 12 else it }
        return String.format(Locale.US, if (is24h) "%02d:%02d" else "%d:%02d", hh, m)
    }

    private fun duration(min: Int): String {
        val h = min / 60
        val m = min % 60
        return when {
            h == 0 -> "$m دقيقة"
            m == 0 -> "$h ساعة"
            else -> "$h س $m د"
        }
    }

    private fun hijri(millis: Long): String = runCatching {
        val locale = ULocale("ar@calendar=islamic-umalqura;numbers=latn")
        val cal = IslamicCalendar(locale).apply {
            calculationType = IslamicCalendar.CalculationType.ISLAMIC_UMALQURA
            timeInMillis = millis
        }
        val fmt = IcuDateFormat.getPatternInstance(cal, "d MMMM y", locale)
        fmt.format(Date(millis))
    }.getOrDefault("")

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, res.displayMetrics)

    private companion object {
        const val WIDTH_DP = 210f
        const val MIN_VALID_YEAR = 2025
        const val ROW_DP = 27f
        const val PRIMARY = 0xF2FFFFFF.toInt()
        const val SECONDARY = 0x99EBEBF5.toInt()
        const val TERTIARY = 0x66EBEBF5
        const val GLASS = 0xA61C1C1E.toInt()
        const val RIM = 0x33FFFFFF
        const val SEPARATOR = 0x26FFFFFF
        const val PILL = 0xF2FFFFFF.toInt()
        const val ON_PILL = 0xFF000000.toInt()
    }
}
