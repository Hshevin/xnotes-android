package com.xnotes.core.tools

/**
 * User-customisable toolbar layout: ordered [sections], each an ordered list of [ToolbarEntry].
 * Pure model (no Android, no JSON) so the add/remove/move/migration logic is unit-testable on
 * the JVM; the settings layer persists it and the UI renders from it.
 */

/** Every atomic, movable toolbar element. The three block ids each render a fixed multi-control
 *  cluster but move and hide as one unit. [id] is the persistence key (never rename without a
 *  migration); the tool ids match the matching [Tool.id]. */
enum class ToolbarItem(val id: String) {
    HOME("home"),
    TITLE("title"),
    SIDEBAR("sidebar"),
    LAYERS("layers"),
    PEN("pen"),
    DASHED("dashed"),
    CALLIGRAPHY("calligraphy"),
    SPEED("speed"),
    TAPER("taper"),
    HIGHLIGHTER("highlighter"),
    ERASER("eraser"),
    PAN("pan"),
    SELECT("select"),
    LASSO("lasso"),
    SCREENSHOT("screenshot"),
    WAND("wand"),
    TAPE("tape"),
    SHAPE("shape"),
    RULER("ruler"),
    TEXT("text"),
    TEXT_BOX("text_box"),

    /** The text markup tool; only notes with a PDF show it. */
    MARKUP("markup"),
    IMAGE("image"),
    UNDO("undo"),
    REDO("redo"),
    PAGE_NAV("page_nav"),
    STYLES("styles"),
    MARGINS("margins"),
    VIEW("view"),
    ZOOM("zoom"),
    FIT("fit"),
    ZOOM_LOCK("zoom_lock"),
    FULLSCREEN("fullscreen"),
    COLORS("colors"),

    /** Canvas only: saved views, which are what page numbers are on an unbounded surface. */
    WAYPOINTS("waypoints"),

    /** Canvas only: the overview map in the corner. */
    MINIMAP("minimap"),

    /** Notes only: opens and closes room on a page. Last, so a stored bar missing older items too still gets it right after the lasso. */
    INSERT_SPACE("insert_space");

    companion object {
        fun fromId(id: String?): ToolbarItem? = entries.firstOrNull { it.id == id }
    }
}

data class ToolbarEntry(val item: ToolbarItem, val visible: Boolean = true)

data class ToolbarSection(val entries: List<ToolbarEntry>) {
    val visibleEntries: List<ToolbarEntry> get() = entries.filter { it.visible }
    val hasVisible: Boolean get() = entries.any { it.visible }
}

data class ToolbarLayout(val sections: List<ToolbarSection>) {

    /** Sections that paint on the real bar; a separator goes between consecutive ones. */
    val visibleSections: List<ToolbarSection> get() = sections.filter { it.hasVisible }

    fun addSection(): ToolbarLayout = copy(sections = sections + ToolbarSection(emptyList()))

    /**
     * Remove section [i], merging its entries into the previous section, or the next one when [i]
     * is the first. Order and visibility are preserved. No-op when only one section remains.
     */
    fun removeSection(i: Int): ToolbarLayout {
        if (sections.size <= 1 || i !in sections.indices) return this
        val target = if (i == 0) i + 1 else i - 1
        val moved = sections[i].entries
        val rebuilt = sections.mapIndexedNotNull { idx, sec ->
            when (idx) {
                i -> null
                target -> sec.copy(entries = if (target < i) sec.entries + moved else moved + sec.entries)
                else -> sec
            }
        }
        return copy(sections = rebuilt)
    }

    fun moveSection(from: Int, to: Int): ToolbarLayout {
        if (from !in sections.indices || to !in sections.indices || from == to) return this
        val list = sections.toMutableList()
        list.add(to, list.removeAt(from))
        return copy(sections = list)
    }

    fun toggleVisible(sectionIndex: Int, entryIndex: Int): ToolbarLayout {
        val sec = sections.getOrNull(sectionIndex) ?: return this
        val e = sec.entries.getOrNull(entryIndex) ?: return this
        val entries = sec.entries.toMutableList().also { it[entryIndex] = e.copy(visible = !e.visible) }
        return copy(sections = sections.toMutableList().also { it[sectionIndex] = sec.copy(entries = entries) })
    }

    /**
     * Move the entry at ([fromSection], [fromIndex]) to ([toSection], [toIndex]), keeping its
     * visibility. The target index is interpreted against the layout after the source is removed.
     */
    fun moveItem(fromSection: Int, fromIndex: Int, toSection: Int, toIndex: Int): ToolbarLayout {
        val src = sections.getOrNull(fromSection) ?: return this
        val entry = src.entries.getOrNull(fromIndex) ?: return this
        if (toSection !in sections.indices) return this
        val mutable = sections.map { it.entries.toMutableList() }.toMutableList()
        mutable[fromSection].removeAt(fromIndex)
        val adjusted = if (toSection == fromSection && toIndex > fromIndex) toIndex - 1 else toIndex
        mutable[toSection].add(adjusted.coerceIn(0, mutable[toSection].size), entry)
        return copy(sections = mutable.mapIndexed { idx, e -> sections[idx].copy(entries = e) })
    }

    /** Add any of [among] missing from this layout, visible, so the bar never goes stale
     *  across versions: an item with a designated neighbour ([INSERT_AFTER]) slots in right
     *  after it; anything else is appended to the last section. */
    fun withMissingItemsAppended(among: Set<ToolbarItem> = NOTE_ITEMS): ToolbarLayout {
        var layout = this
        for (item in ToolbarItem.entries) {
            if (item !in among) continue
            if (layout.sections.any { s -> s.entries.any { it.item == item } }) continue
            layout = layout.insertMissing(item)
        }
        return layout
    }

    private fun insertMissing(item: ToolbarItem): ToolbarLayout {
        val anchor = INSERT_AFTER[item]
        if (anchor != null) {
            sections.forEachIndexed { si, sec ->
                val i = sec.entries.indexOfFirst { it.item == anchor }
                if (i >= 0) {
                    val entries = sec.entries.toMutableList().also { it.add(i + 1, ToolbarEntry(item)) }
                    return copy(sections = sections.toMutableList().also { it[si] = sec.copy(entries = entries) })
                }
            }
        }
        val last = sections.last()
        return copy(sections = sections.dropLast(1) + last.copy(entries = last.entries + ToolbarEntry(item)))
    }

    companion object {
        /** Later-added items that belong beside an existing one in stored layouts. */
        private val INSERT_AFTER = mapOf(
            ToolbarItem.TEXT_BOX to ToolbarItem.TEXT,
            ToolbarItem.VIEW to ToolbarItem.STYLES,
            ToolbarItem.LAYERS to ToolbarItem.STYLES,
            ToolbarItem.MARGINS to ToolbarItem.STYLES,
            ToolbarItem.WAND to ToolbarItem.LASSO,
            ToolbarItem.TAPE to ToolbarItem.WAND,
            ToolbarItem.INSERT_SPACE to ToolbarItem.LASSO,
            ToolbarItem.MARKUP to ToolbarItem.TEXT_BOX,
        )

        /**
         * The two bars hold different things, so each has its own set and neither can be handed the
         * other's. Page navigation means nothing on an unbounded canvas, and a waypoint means
         * nothing in a paged note; an item outside a layout's set is dropped on load rather than
         * drawn as a dead chip.
         */
        val CANVAS_ITEMS: Set<ToolbarItem> = setOf(
            ToolbarItem.HOME, ToolbarItem.TITLE,
            ToolbarItem.PEN, ToolbarItem.DASHED, ToolbarItem.CALLIGRAPHY, ToolbarItem.SPEED,
            ToolbarItem.TAPER, ToolbarItem.HIGHLIGHTER, ToolbarItem.ERASER,
            ToolbarItem.PAN, ToolbarItem.SELECT, ToolbarItem.LASSO, ToolbarItem.WAND,
            ToolbarItem.TAPE, ToolbarItem.SHAPE, ToolbarItem.TEXT, ToolbarItem.TEXT_BOX, ToolbarItem.RULER, ToolbarItem.SCREENSHOT,
            ToolbarItem.IMAGE, ToolbarItem.COLORS, ToolbarItem.UNDO, ToolbarItem.REDO,
            ToolbarItem.STYLES, ToolbarItem.LAYERS, ToolbarItem.WAYPOINTS, ToolbarItem.MINIMAP,
            ToolbarItem.ZOOM, ToolbarItem.FIT, ToolbarItem.ZOOM_LOCK,
        )

        /** Everything the paged bar can hold: the whole enum bar the two canvas-only additions. Insert space is the note bar's alone. */
        val NOTE_ITEMS: Set<ToolbarItem> =
            ToolbarItem.entries.toSet() - setOf(ToolbarItem.WAYPOINTS, ToolbarItem.MINIMAP)

        /** Mirrors the hardcoded bar exactly (see Toolbar.kt) so existing users see no change. */
        val DEFAULT: ToolbarLayout = of(
            listOf(ToolbarItem.HOME, ToolbarItem.TITLE),
            listOf(ToolbarItem.SIDEBAR),
            listOf(
                ToolbarItem.PEN, ToolbarItem.DASHED, ToolbarItem.CALLIGRAPHY, ToolbarItem.SPEED,
                ToolbarItem.TAPER, ToolbarItem.HIGHLIGHTER, ToolbarItem.ERASER,
            ),
            listOf(ToolbarItem.PAN, ToolbarItem.SELECT, ToolbarItem.LASSO, ToolbarItem.INSERT_SPACE, ToolbarItem.SCREENSHOT),
            listOf(ToolbarItem.WAND, ToolbarItem.TAPE, ToolbarItem.SHAPE, ToolbarItem.RULER, ToolbarItem.TEXT, ToolbarItem.TEXT_BOX, ToolbarItem.MARKUP),
            listOf(ToolbarItem.IMAGE),
            listOf(ToolbarItem.COLORS),
            listOf(ToolbarItem.UNDO, ToolbarItem.REDO),
            listOf(ToolbarItem.PAGE_NAV, ToolbarItem.LAYERS, ToolbarItem.STYLES, ToolbarItem.MARGINS, ToolbarItem.VIEW),
            listOf(ToolbarItem.ZOOM, ToolbarItem.FIT, ToolbarItem.ZOOM_LOCK),
            listOf(ToolbarItem.FULLSCREEN),
        )

        /** Mirrors the hardcoded canvas bar (see InfiniteToolbar.kt). */
        val CANVAS_DEFAULT: ToolbarLayout = of(
            listOf(ToolbarItem.HOME, ToolbarItem.TITLE),
            listOf(
                ToolbarItem.PEN, ToolbarItem.DASHED, ToolbarItem.CALLIGRAPHY, ToolbarItem.SPEED,
                ToolbarItem.TAPER, ToolbarItem.HIGHLIGHTER, ToolbarItem.ERASER,
            ),
            listOf(ToolbarItem.PAN, ToolbarItem.SELECT, ToolbarItem.LASSO),
            listOf(ToolbarItem.WAND, ToolbarItem.TAPE, ToolbarItem.SHAPE, ToolbarItem.TEXT, ToolbarItem.TEXT_BOX, ToolbarItem.RULER, ToolbarItem.SCREENSHOT),
            listOf(ToolbarItem.IMAGE),
            listOf(ToolbarItem.COLORS),
            listOf(ToolbarItem.UNDO, ToolbarItem.REDO),
            listOf(ToolbarItem.STYLES, ToolbarItem.LAYERS, ToolbarItem.WAYPOINTS, ToolbarItem.MINIMAP),
            listOf(ToolbarItem.ZOOM, ToolbarItem.FIT, ToolbarItem.ZOOM_LOCK),
        )

        private fun of(vararg groups: List<ToolbarItem>): ToolbarLayout =
            ToolbarLayout(groups.map { g -> ToolbarSection(g.map { ToolbarEntry(it) }) })

        /**
         * Build from raw (id, visible) pairs per section as read from storage: ids outside [among]
         * are dropped, duplicates keep their first occurrence, empty input falls back to [fallback],
         * and any item of [among] not present is appended so the bar never goes stale across
         * versions.
         */
        fun fromRaw(
            rawSections: List<List<Pair<String, Boolean>>>,
            among: Set<ToolbarItem> = NOTE_ITEMS,
            fallback: ToolbarLayout = DEFAULT,
        ): ToolbarLayout {
            if (rawSections.isEmpty()) return fallback
            val seen = LinkedHashSet<ToolbarItem>()
            val sections = rawSections.map { raw ->
                ToolbarSection(
                    raw.mapNotNull { (id, visible) ->
                        val item = ToolbarItem.fromId(id) ?: return@mapNotNull null
                        if (item !in among) return@mapNotNull null
                        if (!seen.add(item)) return@mapNotNull null
                        ToolbarEntry(item, visible)
                    },
                )
            }
            if (sections.all { it.entries.isEmpty() }) return fallback
            return ToolbarLayout(sections).withMissingItemsAppended(among)
        }
    }
}

/** One stable layout for a split; canvas-only extras follow the paged arrangement. */
fun workspaceToolbarLayout(paged: ToolbarLayout, canvas: ToolbarLayout): ToolbarLayout {
    val present = paged.visibleSections.flatMap { it.visibleEntries }.map { it.item }.toSet()
    val extra = canvas.visibleSections.flatMap { it.visibleEntries }.filter { it.item !in present }.distinctBy { it.item }
    return ToolbarLayout(paged.visibleSections + if (extra.isEmpty()) emptyList() else listOf(ToolbarSection(extra)))
}

/** Classify every new button here: the exhaustive when prevents silent placement defaults. */
enum class ToolbarDocument { NOTE, PDF, CANVAS }
private enum class ToolbarScope(val supported: Set<ToolbarDocument>, val perDocument: Boolean) {
    COMMON(ToolbarDocument.entries.toSet(), false),
    PAGED_TOOL(setOf(ToolbarDocument.NOTE, ToolbarDocument.PDF), false),
    PDF_TOOL(setOf(ToolbarDocument.PDF), false),
    ALL_VIEW(ToolbarDocument.entries.toSet(), true),
    PAGED_VIEW(setOf(ToolbarDocument.NOTE, ToolbarDocument.PDF), true),
    CANVAS_VIEW(setOf(ToolbarDocument.CANVAS), true),
}
private val ToolbarItem.scope: ToolbarScope get() = when (this) {
    ToolbarItem.HOME, ToolbarItem.TITLE, ToolbarItem.PEN, ToolbarItem.DASHED,
    ToolbarItem.CALLIGRAPHY, ToolbarItem.SPEED, ToolbarItem.TAPER, ToolbarItem.HIGHLIGHTER,
    ToolbarItem.ERASER, ToolbarItem.PAN, ToolbarItem.SELECT, ToolbarItem.LASSO,
    ToolbarItem.SCREENSHOT, ToolbarItem.WAND, ToolbarItem.TAPE, ToolbarItem.SHAPE,
    ToolbarItem.RULER, ToolbarItem.TEXT, ToolbarItem.TEXT_BOX, ToolbarItem.IMAGE,
    ToolbarItem.UNDO, ToolbarItem.REDO, ToolbarItem.FULLSCREEN, ToolbarItem.COLORS -> ToolbarScope.COMMON
    ToolbarItem.INSERT_SPACE -> ToolbarScope.PAGED_TOOL
    ToolbarItem.MARKUP -> ToolbarScope.PDF_TOOL
    ToolbarItem.ZOOM, ToolbarItem.FIT, ToolbarItem.ZOOM_LOCK, ToolbarItem.STYLES, ToolbarItem.LAYERS -> ToolbarScope.ALL_VIEW
    ToolbarItem.SIDEBAR, ToolbarItem.PAGE_NAV, ToolbarItem.MARGINS, ToolbarItem.VIEW -> ToolbarScope.PAGED_VIEW
    ToolbarItem.WAYPOINTS, ToolbarItem.MINIMAP -> ToolbarScope.CANVAS_VIEW
}

private val pagedDocuments = setOf(ToolbarDocument.NOTE, ToolbarDocument.PDF)

/** Tools move together based on the workspace, never the currently focused pane. */
fun ToolbarLayout.forDocumentControls(document: Boolean, documents: Set<ToolbarDocument> = pagedDocuments): ToolbarLayout =
    ToolbarLayout(sections.map { section ->
        ToolbarSection(section.visibleEntries.filter { entry ->
            val scope = entry.item.scope
            val local = scope.perDocument || documents.isEmpty() || !scope.supported.containsAll(documents)
            local == document
        })
    }.filter { it.hasVisible })

fun documentToolbarLayout(layout: ToolbarLayout, canvas: Boolean, pdf: Boolean,
    documents: Set<ToolbarDocument> = pagedDocuments): ToolbarLayout {
    val kind = if (canvas) ToolbarDocument.CANVAS else if (pdf) ToolbarDocument.PDF else ToolbarDocument.NOTE
    return ToolbarLayout(layout.forDocumentControls(true, documents).sections.map { section ->
        ToolbarSection(section.visibleEntries.filter { kind in it.item.scope.supported })
    }.filter { it.hasVisible })
}
