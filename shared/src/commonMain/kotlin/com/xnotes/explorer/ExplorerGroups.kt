package com.xnotes.explorer

import com.xnotes.core.model.Rgba
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** What an explorer entry is, for badges, the kind filter and grouping by kind. */
enum class EntryKind { FOLDER, NOTE, PDF, CANVAS, MIND_MAP, SPLIT }

/** The plain name of a colour nobody has named, from its hue. */
enum class Hue { BLACK, WHITE, GREY, RED, ORANGE, YELLOW, GREEN, TEAL, BLUE, PURPLE, PINK }

/** The explorer's wording for dates, counts, kinds and hues; string resources supply it on device. */
interface ExplorerWords {
    fun month(m: Month): String
    fun shortMonth(m: Month): String
    fun shortWeekday(d: DayOfWeek): String
    val today: String
    val yesterday: String
    val earlierThisWeek: String
    val lastWeek: String
    fun earlierIn(m: Month): String
    fun monthYear(m: Month, year: Int): String
    val justNow: String
    fun minutesAgo(n: Long): String
    fun hoursAgo(n: Long): String
    fun daysAgo(n: Long): String
    fun dayMonth(day: Int, m: Month): String
    fun dayMonthYear(day: Int, m: Month, year: Int): String
    fun withTime(date: String, clock: String): String
    fun clock12(hour: Int, minute: Int, pm: Boolean): String
    fun opened(ago: String): String
    fun folders(n: Int): String
    fun files(n: Int): String
    fun items(n: Int): String
    fun kinds(k: EntryKind): String
    fun hue(h: Hue): String
    val noColour: String
}

/** One heading's worth of entries; [color] is set when grouped by colour. An empty [label] means no heading. */
class EntryGroup<E : ExplorerEntry>(val key: String, val label: String, val items: List<E>, val color: Rgba? = null)

/** A date heading and the first day it covers, which orders the headings. */
class DateBucket(val label: String, val start: LocalDate)

private fun dayOf(time: Long, zone: TimeZone): LocalDate = Instant.fromEpochMilliseconds(time).toLocalDateTime(zone).date

/** The heading [time] files under, seen from [now]: Today, Yesterday, this week, last week, earlier this month, then by month. */
fun dateBucket(w: ExplorerWords, time: Long, now: Long, zone: TimeZone, weekStart: DayOfWeek = DayOfWeek.MONDAY): DateBucket {
    val today = dayOf(now, zone)
    val day = minOf(dayOf(time, zone), today)
    val thisWeek = today.minus((today.dayOfWeek.isoDayNumber - weekStart.isoDayNumber + 7) % 7, DateTimeUnit.DAY)
    val lastWeek = thisWeek.minus(1, DateTimeUnit.WEEK)
    return when {
        day == today -> DateBucket(w.today, today)
        day == today.minus(1, DateTimeUnit.DAY) -> DateBucket(w.yesterday, day)
        day >= thisWeek -> DateBucket(w.earlierThisWeek, thisWeek)
        day >= lastWeek -> DateBucket(w.lastWeek, lastWeek)
        day.year == today.year && day.month == today.month -> DateBucket(w.earlierIn(today.month), LocalDate(day.year, day.month, 1))
        day.year == today.year -> DateBucket(w.month(day.month), LocalDate(day.year, day.month, 1))
        else -> DateBucket(w.monthYear(day.month, day.year), LocalDate(day.year, day.month, 1))
    }
}

/**
 * Splits [items] (already sorted) under headings. Items keep their order within a heading. Date headings run
 * newest first unless the list is sorted by that date oldest first; they follow the created date when sorting
 * by it and the modified date otherwise.
 */
fun <E : ExplorerEntry> groupEntries(
    w: ExplorerWords,
    items: List<E>,
    by: GroupBy,
    sortKey: ExplorerSortKey,
    descending: Boolean,
    kindOf: (E) -> EntryKind,
    nameOf: (Rgba) -> String?,
    now: Long,
    zone: TimeZone,
    weekStart: DayOfWeek = DayOfWeek.MONDAY,
): List<EntryGroup<E>> {
    if (items.isEmpty()) return emptyList()
    return when (by) {
        GroupBy.NONE -> listOf(EntryGroup("all", "", items))
        GroupBy.DATE -> {
            val byCreated = sortKey == ExplorerSortKey.CREATED
            val buckets = LinkedHashMap<String, Pair<DateBucket, MutableList<E>>>()
            for (e in items) {
                val b = dateBucket(w, if (byCreated) e.created else e.modified, now, zone, weekStart)
                buckets.getOrPut(b.label) { b to ArrayList() }.second.add(e)
            }
            val oldestFirst = !descending && (sortKey == ExplorerSortKey.MODIFIED || byCreated)
            val ordered = buckets.values.sortedBy { it.first.start }
            (if (oldestFirst) ordered else ordered.reversed()).map { (b, list) -> EntryGroup(b.label, b.label, list) }
        }
        GroupBy.KIND -> {
            val byKind = items.groupBy(kindOf)
            EntryKind.entries.mapNotNull { k -> byKind[k]?.let { EntryGroup(k.name, w.kinds(k), it) } }
        }
        GroupBy.COLOUR -> {
            val byColor = items.groupBy { it.color }
            val named = byColor.keys.filterNotNull().mapNotNull { c -> nameOf(c)?.let { c to it } }.sortedBy { it.second.lowercase() }
            val unnamed = byColor.keys.filterNotNull().filter { nameOf(it) == null }
                .map { it to hueName(w, it) }.sortedWith(compareBy({ it.second }, { Rgba.toHex(it.first) }))
            (named + unnamed).map { (c, label) -> EntryGroup(Rgba.toHex(c), label, byColor.getValue(c), c) } +
                listOfNotNull(byColor[null]?.let { EntryGroup("none", w.noColour, it) })
        }
    }
}

/** A plain name for a colour nobody has named, from its hue. */
fun hueName(w: ExplorerWords, c: Rgba): String = w.hue(hueOf(c))

fun hueOf(c: Rgba): Hue {
    val r = c.r / 255.0
    val g = c.g / 255.0
    val b = c.b / 255.0
    val max = maxOf(r, g, b)
    val d = max - minOf(r, g, b)
    if (max < 0.15) return Hue.BLACK
    if (d / max < 0.15) return if (max > 0.85) Hue.WHITE else Hue.GREY
    val h = when (max) {
        r -> 60 * ((g - b) / d)
        g -> 60 * ((b - r) / d + 2)
        else -> 60 * ((r - g) / d + 4)
    }.let { if (it < 0) it + 360 else it }
    return when {
        h < 15 || h >= 345 -> Hue.RED
        h < 40 -> Hue.ORANGE
        h < 65 -> Hue.YELLOW
        h < 160 -> Hue.GREEN
        h < 195 -> Hue.TEAL
        h < 255 -> Hue.BLUE
        h < 290 -> Hue.PURPLE
        else -> Hue.PINK
    }
}

fun clockText(w: ExplorerWords, t: LocalTime, clock24: Boolean): String =
    if (clock24) "${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
    else w.clock12((t.hour + 11) % 12 + 1, t.minute, t.hour >= 12)

/**
 * [time] as the explorer writes it under a tile. [style] is "relative" (2 days ago), "day" (Wed 17:30, the
 * default) or "date" (16 Sep 2026); [withTime] adds the time of day where the style has room for it.
 */
fun formatWhen(w: ExplorerWords, time: Long, now: Long, style: String, withTime: Boolean, clock24: Boolean, zone: TimeZone): String {
    if (time <= 0) return ""
    val at = Instant.fromEpochMilliseconds(time).toLocalDateTime(zone)
    val today = dayOf(now, zone)
    val day = at.date
    val clock = clockText(w, at.time, clock24)
    val dayMonth = if (day.year != today.year) w.dayMonthYear(day.day, day.month, day.year) else w.dayMonth(day.day, day.month)
    return when (style) {
        "relative" -> {
            val mins = (now - time) / 60_000
            when {
                mins < 1 -> w.justNow
                day == today && mins < 60 -> w.minutesAgo(mins)
                day == today -> w.hoursAgo(mins / 60)
                day == today.minus(1, DateTimeUnit.DAY) -> w.yesterday
                day > today.minus(7, DateTimeUnit.DAY) -> w.daysAgo(day.daysUntil(today).toLong())
                else -> dayMonth
            }
        }
        "date" -> w.dayMonthYear(day.day, day.month, day.year).let { if (withTime) w.withTime(it, clock) else it }
        else -> {
            val d = when {
                day >= today -> w.today
                day == today.minus(1, DateTimeUnit.DAY) -> w.yesterday
                day > today.minus(7, DateTimeUnit.DAY) -> w.shortWeekday(day.dayOfWeek)
                else -> return dayMonth
            }
            if (withTime) "$d $clock" else d
        }
    }
}

/** [time] in full, for details panes: 16 Sep 2026, 17:30. */
fun formatFull(w: ExplorerWords, time: Long, clock24: Boolean, zone: TimeZone): String {
    if (time <= 0) return ""
    val at = Instant.fromEpochMilliseconds(time).toLocalDateTime(zone)
    return w.withTime(w.dayMonthYear(at.day, at.month, at.year), clockText(w, at.time, clock24))
}

/** When a document was opened, for Recent: "Opened 25 min ago", "Opened yesterday", "Opened 16 Aug". */
fun openedLabel(w: ExplorerWords, time: Long, now: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val rel = formatWhen(w, time, now, "relative", false, true, zone)
    return w.opened(if (rel.firstOrNull()?.isLetter() == true) rel.replaceFirstChar { it.lowercase() } else rel)
}

/** A file size the way the explorer shows it: 940 KB, 3.4 MB. */
fun formatSize(bytes: Long): String = when {
    bytes < 1000 -> "$bytes B"
    bytes < 1000L * 1024 -> "${(bytes + 1023) / 1024} KB"
    bytes < 1000L * 1024 * 1024 -> "${oneDecimal(bytes / 1048576.0)} MB"
    else -> "${oneDecimal(bytes / 1073741824.0)} GB"
}

private fun oneDecimal(value: Double): String {
    val lower = (value * 10).toLong()
    // Compare in the original unit: scaling a large value can round it onto the halfway mark.
    val tenths = if (value >= (lower + 0.5) / 10) lower + 1 else lower
    return "${tenths / 10}.${tenths % 10}"
}

/** "1 folder · 18 files", leaving out a count that is zero. */
fun countsLabel(w: ExplorerWords, folders: Int, files: Int): String = listOfNotNull(
    folders.takeIf { it > 0 }?.let { w.folders(it) },
    files.takeIf { it > 0 }?.let { w.files(it) },
).joinToString(" · ")

/** "1 item" or "24 items". */
fun itemsLabel(w: ExplorerWords, n: Int): String = w.items(n)
