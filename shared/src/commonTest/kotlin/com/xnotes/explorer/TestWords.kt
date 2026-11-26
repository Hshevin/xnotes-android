package com.xnotes.explorer

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Month

internal object TestWords : ExplorerWords {
    override fun month(m: Month) = m.name.lowercase().replaceFirstChar { it.uppercase() }
    override fun shortMonth(m: Month) = month(m).take(3)
    override fun shortWeekday(d: DayOfWeek) = d.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
    override val today = "Today"
    override val yesterday = "Yesterday"
    override val earlierThisWeek = "Earlier this week"
    override val lastWeek = "Last week"
    override fun earlierIn(m: Month) = "Earlier in ${month(m)}"
    override fun monthYear(m: Month, year: Int) = "${month(m)} $year"
    override val justNow = "Just now"
    override fun minutesAgo(n: Long) = "$n minutes ago"
    override fun hoursAgo(n: Long) = "$n hours ago"
    override fun daysAgo(n: Long) = "$n days ago"
    override fun dayMonth(day: Int, m: Month) = "$day ${shortMonth(m)}"
    override fun dayMonthYear(day: Int, m: Month, year: Int) = "${dayMonth(day, m)} $year"
    override fun withTime(date: String, clock: String) = "$date, $clock"
    override fun clock12(hour: Int, minute: Int, pm: Boolean) =
        "$hour:${minute.toString().padStart(2, '0')} ${if (pm) "PM" else "AM"}"
    override fun opened(ago: String) = "Opened $ago"
    override fun folders(n: Int) = "$n folders"
    override fun files(n: Int) = "$n files"
    override fun items(n: Int) = "$n items"
    override fun kinds(k: EntryKind) = k.name
    override fun hue(h: Hue) = h.name
    override val noColour = "No colour"
}
