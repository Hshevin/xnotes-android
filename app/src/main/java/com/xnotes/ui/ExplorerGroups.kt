package com.xnotes.ui

import com.xnotes.core.model.Rgba
import com.xnotes.explorer.ExplorerSortKey
import com.xnotes.explorer.GroupBy
import kotlinx.datetime.toJavaDayOfWeek
import kotlinx.datetime.toJavaMonth
import kotlinx.datetime.toKotlinDayOfWeek
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinTimeZone
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId

/** Bridges the Android resource/calendar API to the common datetime enums. */
internal interface ExplorerWords : com.xnotes.explorer.ExplorerWords {
    fun month(m: Month): String
    fun shortMonth(m: Month): String
    fun shortWeekday(d: DayOfWeek): String
    fun earlierIn(m: Month): String
    fun monthYear(m: Month, year: Int): String
    fun dayMonth(day: Int, m: Month): String
    fun dayMonthYear(day: Int, m: Month, year: Int): String

    override fun month(m: kotlinx.datetime.Month) = month(m.toJavaMonth())
    override fun shortMonth(m: kotlinx.datetime.Month) = shortMonth(m.toJavaMonth())
    override fun shortWeekday(d: kotlinx.datetime.DayOfWeek) = shortWeekday(d.toJavaDayOfWeek())
    override fun earlierIn(m: kotlinx.datetime.Month) = earlierIn(m.toJavaMonth())
    override fun monthYear(m: kotlinx.datetime.Month, year: Int) = monthYear(m.toJavaMonth(), year)
    override fun dayMonth(day: Int, m: kotlinx.datetime.Month) = dayMonth(day, m.toJavaMonth())
    override fun dayMonthYear(day: Int, m: kotlinx.datetime.Month, year: Int) = dayMonthYear(day, m.toJavaMonth(), year)
}

internal typealias EntryKind = com.xnotes.explorer.EntryKind
internal typealias Hue = com.xnotes.explorer.Hue
internal typealias EntryGroup = com.xnotes.explorer.EntryGroup<BrowseEntry>

internal class DateBucket(val label: String, val start: LocalDate)

internal fun dateBucket(w: ExplorerWords, time: Long, now: Long, zone: ZoneId, weekStart: DayOfWeek = DayOfWeek.MONDAY): DateBucket =
    com.xnotes.explorer.dateBucket(w, time, now, zone.toKotlinTimeZone(), weekStart.toKotlinDayOfWeek()).let { DateBucket(it.label, it.start.toJavaLocalDate()) }

internal fun groupEntries(
    w: ExplorerWords,
    items: List<BrowseEntry>,
    by: GroupBy,
    sortKey: ExplorerSortKey,
    descending: Boolean,
    kindOf: (BrowseEntry) -> EntryKind,
    nameOf: (Rgba) -> String?,
    now: Long,
    zone: ZoneId,
    weekStart: DayOfWeek = DayOfWeek.MONDAY,
): List<EntryGroup> = com.xnotes.explorer.groupEntries(w, items, by, sortKey, descending, kindOf, nameOf, now, zone.toKotlinTimeZone(), weekStart.toKotlinDayOfWeek())

internal fun formatWhen(w: ExplorerWords, time: Long, now: Long, style: String, withTime: Boolean, clock24: Boolean, zone: ZoneId): String =
    com.xnotes.explorer.formatWhen(w, time, now, style, withTime, clock24, zone.toKotlinTimeZone())

internal fun formatFull(w: ExplorerWords, time: Long, clock24: Boolean, zone: ZoneId): String =
    com.xnotes.explorer.formatFull(w, time, clock24, zone.toKotlinTimeZone())

internal fun openedLabel(w: ExplorerWords, time: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    com.xnotes.explorer.openedLabel(w, time, now, zone.toKotlinTimeZone())

internal fun formatSize(bytes: Long): String = com.xnotes.explorer.formatSize(bytes)
internal fun countsLabel(w: ExplorerWords, folders: Int, files: Int): String = com.xnotes.explorer.countsLabel(w, folders, files)
internal fun itemsLabel(w: ExplorerWords, n: Int): String = com.xnotes.explorer.itemsLabel(w, n)
internal fun hueName(w: ExplorerWords, c: Rgba): String = com.xnotes.explorer.hueName(w, c)
