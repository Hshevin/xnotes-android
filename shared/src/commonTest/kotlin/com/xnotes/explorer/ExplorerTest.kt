package com.xnotes.explorer

import com.xnotes.core.model.Rgba
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

private data class Entry(
    override val name: String,
    override val size: Long = 0,
    override val modified: Long = 0,
    override val created: Long = modified,
    val kind: EntryKind = EntryKind.NOTE,
    override val color: Rgba? = null,
) : ExplorerEntry {
    override val isDir: Boolean get() = kind == EntryKind.FOLDER
}

class ExplorerTest {
    private val zone = TimeZone.UTC
    private fun at(date: String, clock: String = "12:00") = Instant.parse("${date}T${clock}:00Z").toEpochMilliseconds()
    private val now = at("2026-09-18")

    @Test
    fun sortsAllKeysInBothDirectionsAndKeepsFoldersFirst() {
        val a = Entry("Apple", size = 20, modified = 1, created = 3)
        val b = Entry("banana", size = 10, modified = 3, created = 2)
        val c = Entry("Cherry", size = 30, modified = 2, created = 1)
        val folder = Entry("z", kind = EntryKind.FOLDER)
        val entries = listOf(c, folder, a, b)
        val orders = mapOf(
            ExplorerSortKey.NAME to listOf(a, b, c),
            ExplorerSortKey.MODIFIED to listOf(a, c, b),
            ExplorerSortKey.CREATED to listOf(c, b, a),
            ExplorerSortKey.SIZE to listOf(b, a, c),
        )
        for ((key, ascending) in orders) {
            assertEquals(listOf(folder) + ascending, entries.sortedWith(explorerComparator(key, descending = false)))
            assertEquals(listOf(folder) + ascending.reversed(), entries.sortedWith(explorerComparator(key, descending = true)))
        }
        assertEquals(listOf(a, b, c, folder), entries.sortedWith(explorerComparator(ExplorerSortKey.NAME, false, foldersFirst = false)))
    }

    @Test
    fun secondaryDatesFollowTheDirectionAndFinalNamesStayAscending() {
        val a = Entry("a", modified = 7, created = 5)
        val b = a.copy(name = "B")
        for (key in listOf(ExplorerSortKey.SIZE, ExplorerSortKey.MODIFIED, ExplorerSortKey.CREATED)) {
            val c = if (key == ExplorerSortKey.CREATED) a.copy(name = "c", modified = 8) else a.copy(name = "c", created = 6)
            val entries = listOf(b, c, a)
            assertEquals(listOf(c, a, b), entries.sortedWith(explorerComparator(key, true)))
            assertEquals(listOf(a, b, c), entries.sortedWith(explorerComparator(key, false)))
        }
    }

    @Test
    fun bucketsSupplyTheLabelAndFirstDayIncludingFutureDates() {
        fun check(date: String, label: String, start: String, weekStart: DayOfWeek = DayOfWeek.MONDAY) {
            val bucket = dateBucket(TestWords, at(date), now, zone, weekStart)
            assertEquals(label, bucket.label, date)
            assertEquals(LocalDate.parse(start), bucket.start, date)
        }
        check("2026-09-18", "Today", "2026-09-18")
        check("2027-01-01", "Today", "2026-09-18")
        check("2026-09-17", "Yesterday", "2026-09-17")
        check("2026-09-15", "Earlier this week", "2026-09-14")
        check("2026-09-13", "Last week", "2026-09-07")
        check("2026-09-13", "Earlier this week", "2026-09-13", DayOfWeek.SUNDAY)
        check("2026-09-06", "Earlier in September", "2026-09-01")
        check("2026-08-18", "August", "2026-08-01")
        check("2025-12-30", "December 2025", "2025-12-01")
    }

    @Test
    fun datesUseLocalMidnightAcrossADaylightSavingChange() {
        val ny = TimeZone.of("America/New_York")
        val beforeMidnight = at("2026-03-08", "04:30") // 23:30 on the 7th
        val afterMidnight = at("2026-03-09", "04:10") // 00:10 on the 9th
        assertEquals("2 days ago", formatWhen(TestWords, beforeMidnight, afterMidnight, "relative", false, true, ny))
        assertEquals("Yesterday", dateBucket(TestWords, at("2026-03-08", "05:30"), afterMidnight, ny).label)
    }

    private fun groups(entries: List<Entry>, by: GroupBy, key: ExplorerSortKey = ExplorerSortKey.MODIFIED,
                       descending: Boolean = true, names: Map<Rgba, String> = emptyMap()) =
        groupEntries(TestWords, entries, by, key, descending, Entry::kind, names::get, now, zone)

    @Test
    fun dateGroupsUseTheSelectedDateAndPreserveInputOrderInsideEachGroup() {
        val today = Entry("b", modified = now, created = at("2025-12-03"))
        val yesterday = Entry("c", modified = at("2026-09-17"))
        val entries = listOf(today, yesterday, today.copy(name = "a"))
        val recentFirst = listOf("Today", "Yesterday")
        assertEquals(recentFirst, groups(entries, GroupBy.DATE).map { it.label })
        assertEquals(listOf("b", "a"), groups(entries, GroupBy.DATE).first().items.map { it.name })
        assertEquals(recentFirst.reversed(), groups(entries, GroupBy.DATE, descending = false).map { it.label })
        assertEquals(recentFirst, groups(entries, GroupBy.DATE, ExplorerSortKey.NAME, false).map { it.label })
        assertEquals(listOf("December 2025", "Yesterday"), groups(entries, GroupBy.DATE, ExplorerSortKey.CREATED, false).map { it.label })
        assertEquals(listOf("Yesterday", "December 2025"), groups(entries, GroupBy.DATE, ExplorerSortKey.CREATED).map { it.label })
    }

    @Test
    fun kindColourAndHeadlessGroupsKeepTheirEntries() {
        val red = Rgba(255, 0, 0)
        val blue = Rgba(0, 0, 255)
        val green = Rgba(0, 255, 0)
        val entries = listOf(Entry("c", kind = EntryKind.CANVAS, color = red), Entry("d", kind = EntryKind.FOLDER),
            Entry("n", color = blue), Entry("g", color = green))
        val kinds = groups(entries, GroupBy.KIND)
        assertEquals(listOf("FOLDER", "NOTE", "CANVAS"), kinds.map { it.key })
        assertEquals(listOf(entries[2], entries[3]), kinds[1].items)
        val colours = groups(entries, GroupBy.COLOUR, names = mapOf(red to "Work", blue to "art"))
        assertEquals(listOf("art", "Work", "GREEN", "No colour"), colours.map { it.label })
        assertEquals(listOf(blue, red, green, null), colours.map { it.color })
        val headless = groups(entries, GroupBy.NONE).single()
        assertEquals("", headless.label)
        assertEquals(entries, headless.items)
        assertEquals(emptyList(), groups(emptyList(), GroupBy.NONE))
    }

    @Test
    fun datesShowRelativeTimeWeekdaysOrFullDatesWithTheChosenClock() {
        fun label(date: String, clock: String, style: String, withTime: Boolean = true, clock24: Boolean = true) =
            formatWhen(TestWords, at(date, clock), now, style, withTime, clock24, zone)
        assertEquals("Just now", label("2026-09-18", "12:10", "relative"))
        assertEquals("15 minutes ago", label("2026-09-18", "11:45", "relative"))
        assertEquals("2 hours ago", label("2026-09-18", "10:00", "relative"))
        assertEquals("Yesterday", label("2026-09-17", "12:00", "relative"))
        assertEquals("3 days ago", label("2026-09-15", "12:00", "relative"))
        assertEquals("11 Sep", label("2026-09-11", "23:00", "relative"))
        assertEquals("Today 00:05", label("2026-09-18", "00:05", "day"))
        assertEquals("Today 12:05 AM", label("2026-09-18", "00:05", "day", clock24 = false))
        assertEquals("Yesterday 12:05 PM", label("2026-09-17", "12:05", "day", clock24 = false))
        assertEquals("Tue", label("2026-09-15", "15:05", "day", withTime = false))
        assertEquals("15 Sep 2026, 3:05 PM", label("2026-09-15", "15:05", "date", clock24 = false))
        assertEquals("15 Sep 2026", label("2026-09-15", "15:05", "date", withTime = false))
        assertEquals("15 Sep 2025", label("2025-09-15", "09:45", "day"))
        assertEquals("", formatFull(TestWords, 0, true, zone))
        assertEquals("18 Sep 2026, 12:00", formatFull(TestWords, now, true, zone))
        assertEquals("Opened yesterday", openedLabel(TestWords, at("2026-09-17"), now, zone))
    }

    @Test
    fun sizesUseExplorerThresholdsAndRoundHalfUpToOneDecimal() {
        val mb = 1024L * 1024
        val gb = 1024 * mb
        assertEquals("999 B", formatSize(999))
        assertEquals("1 KB", formatSize(1000))
        assertEquals("2 KB", formatSize(1025))
        assertEquals("1000 KB", formatSize(1000 * 1024 - 1))
        assertEquals("1.0 MB", formatSize(1000 * 1024))
        assertEquals("1.5 MB", formatSize(mb * 3 / 2))
        assertEquals("1.3 MB", formatSize(mb * 5 / 4))
        assertEquals("1.0 GB", formatSize(1000 * mb))
        assertEquals("2.5 GB", formatSize(5 * gb / 2))
        assertEquals("2097151.0 GB", formatSize(2_251_798_793_630_515))
        assertEquals("2097151.1 GB", formatSize(2_251_798_793_630_516))
    }

    @Test
    fun countsOmitZeroFoldersAndFiles() {
        assertEquals("2 folders · 3 files", countsLabel(TestWords, 2, 3))
        assertEquals("2 folders", countsLabel(TestWords, 2, 0))
        assertEquals("3 files", countsLabel(TestWords, 0, 3))
        assertEquals("", countsLabel(TestWords, 0, 0))
        assertEquals("0 items", itemsLabel(TestWords, 0))
    }

    @Test
    fun neutralAndHueBoundaries() {
        val boundaries = listOf(
            Rgba(38, 38, 38) to Hue.BLACK, Rgba(39, 39, 39) to Hue.GREY,
            Rgba(216, 216, 216) to Hue.GREY, Rgba(217, 217, 217) to Hue.WHITE,
            Rgba(240, 59, 0) to Hue.RED, Rgba(240, 60, 0) to Hue.ORANGE,
            Rgba(240, 159, 0) to Hue.ORANGE, Rgba(240, 160, 0) to Hue.YELLOW,
            Rgba(221, 240, 0) to Hue.YELLOW, Rgba(220, 240, 0) to Hue.GREEN,
            Rgba(0, 240, 159) to Hue.GREEN, Rgba(0, 240, 160) to Hue.TEAL,
            Rgba(0, 181, 240) to Hue.TEAL, Rgba(0, 180, 240) to Hue.BLUE,
            Rgba(59, 0, 240) to Hue.BLUE, Rgba(60, 0, 240) to Hue.PURPLE,
            Rgba(199, 0, 240) to Hue.PURPLE, Rgba(200, 0, 240) to Hue.PINK,
            Rgba(240, 0, 61) to Hue.PINK, Rgba(240, 0, 60) to Hue.RED,
        )
        for ((rgb, expected) in boundaries) assertEquals(expected, hueOf(rgb), Rgba.toHex(rgb))
    }
}
