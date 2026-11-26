package com.xnotes.iosapp.home

import com.xnotes.explorer.EntryKind
import com.xnotes.explorer.ExplorerWords
import com.xnotes.explorer.Hue
import com.xnotes.iosapp.text
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Month
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale

/**
 * [key]'s text with Android's placeholders filled in order: `%1$s`, `%2$d`, `%2$02d`, or a bare
 * `%d`/`%s` for the next argument. The strings come over from Android unchanged.
 */
internal fun fmt(key: String, vararg args: Any): String {
    var next = 0
    return Regex("""%(?:(\d+)\$)?(0?\d*)([sd])""").replace(text(key)) { m ->
        val index = m.groupValues[1].toIntOrNull()?.minus(1) ?: next++
        val value = args.getOrNull(index)?.toString() ?: return@replace m.value
        val width = m.groupValues[2]
        if (m.groupValues[3] == "d" && width.startsWith("0")) value.padStart(width.drop(1).toIntOrNull() ?: 0, '0') else value
    }
}

/** A count with Android's one/other forms. */
internal fun plural(key: String, n: Number): String = fmt(if (n.toLong() == 1L) "${key}_one" else "${key}_other", n)

/** Whether this iPad shows the time on a 24-hour clock. */
internal fun clock24(): Boolean = NSDateFormatter.dateFormatFromTemplate("j", 0u, NSLocale.currentLocale)?.contains('a') != true

/** [ExplorerWords] from the app's strings, as Android's `ResourceWords` reads them. */
internal object HomeWords : ExplorerWords {
    override fun month(m: Month) = text("months_${m.ordinal + 1}")
    override fun shortMonth(m: Month) = text("months_short_${m.ordinal + 1}")
    override fun shortWeekday(d: DayOfWeek) = text("weekdays_short_${d.ordinal + 1}")
    override val today get() = text("today")
    override val yesterday get() = text("yesterday")
    override val earlierThisWeek get() = text("earlier_this_week")
    override val lastWeek get() = text("last_week")
    override fun earlierIn(m: Month) = fmt("earlier_in", month(m))
    override fun monthYear(m: Month, year: Int) = fmt("month_year", month(m), year)
    override val justNow get() = text("just_now")
    override fun minutesAgo(n: Long) = fmt("minutes_ago", n)
    override fun hoursAgo(n: Long) = fmt("hours_ago", n)
    override fun daysAgo(n: Long) = plural("days_ago", n)
    override fun dayMonth(day: Int, m: Month) = fmt("day_month", day, shortMonth(m))
    override fun dayMonthYear(day: Int, m: Month, year: Int) = fmt("day_month_year", day, shortMonth(m), year)
    override fun withTime(date: String, clock: String) = fmt("date_with_time", date, clock)
    override fun clock12(hour: Int, minute: Int, pm: Boolean) = fmt("clock_12h", hour, minute, text(if (pm) "pm" else "am"))
    override fun opened(ago: String) = fmt("opened_ago", ago)
    override fun folders(n: Int) = plural("folders_count", n)
    override fun files(n: Int) = plural("files_count", n)
    override fun items(n: Int) = plural("items_count", n)
    override fun kinds(k: EntryKind) = text(when (k) {
        EntryKind.FOLDER -> "kind_folders"
        EntryKind.NOTE -> "kind_notes"
        EntryKind.PDF -> "kind_pdf_notes"
        EntryKind.CANVAS -> "kind_canvases"
        EntryKind.MIND_MAP -> "map_title"
        EntryKind.SPLIT -> "split_workspace"
    })
    override fun hue(h: Hue) = text("hue_" + h.name.lowercase())
    override val noColour get() = text("no_colour")
}
