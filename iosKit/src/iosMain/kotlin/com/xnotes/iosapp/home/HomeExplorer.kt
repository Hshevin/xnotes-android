package com.xnotes.iosapp.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xnotes.explorer.*
import com.xnotes.iosapp.text
import com.xnotes.iosapp.ui.icons.XnotesIcons
import com.xnotes.iosapp.ui.theme.HomeSize
import com.xnotes.iosapp.ui.theme.Space
import com.xnotes.iosapp.ui.theme.TextSize
import com.xnotes.storage.ios.IosDocumentEntry
import kotlinx.datetime.TimeZone
import kotlin.math.roundToInt

/** A folder's contents as the explorer lays them out: folders on their own row, files under headings. */
internal class Arranged(val folders: List<IosDocumentEntry>, val groups: List<EntryGroup<IosDocumentEntry>>) {
    val files: Int get() = groups.sumOf { it.items.size }
    val isEmpty: Boolean get() = folders.isEmpty() && files == 0
    val bytes: Long get() = groups.sumOf { g -> g.items.sumOf { it.size } }
}

internal fun arrange(entries: List<IosDocumentEntry>, view: ExplorerView, filter: EntryKind?, kind: (IosDocumentEntry) -> EntryKind, now: Long): Arranged {
    val shown = entries.sortedWith(explorerComparator(view.sortKey, view.descending)).filter { filter == null || kind(it) == filter }
    val files = shown.filterNot { it.isFolder }
    return Arranged(shown.filter { it.isFolder },
        groupEntries(HomeWords, files, view.groupBy, view.sortKey, view.descending, kind, { null }, now, TimeZone.currentSystemDefault()))
}

/** What the explorer needs to draw one entry. */
internal class EntryLook(val view: ExplorerView, val now: Long, val dark: Boolean, private val clock24: Boolean) {
    fun whenText(e: IosDocumentEntry): String = formatWhen(HomeWords, if (view.sortKey == ExplorerSortKey.CREATED) e.created else e.modified,
        now, "day", view.showTime, clock24, TimeZone.currentSystemDefault())

    fun folderMeta(e: IosDocumentEntry): String = e.childCount?.let { itemsLabel(HomeWords, it) } ?: whenText(e)
}

internal fun kindIcon(k: EntryKind): ImageVector = when (k) {
    EntryKind.FOLDER -> XnotesIcons.folder
    EntryKind.NOTE, EntryKind.MIND_MAP -> XnotesIcons.file
    EntryKind.PDF -> XnotesIcons.pdf
    EntryKind.CANVAS -> XnotesIcons.canvas
    EntryKind.SPLIT -> XnotesIcons.split
}

internal fun kindLabel(k: EntryKind): String = text(when (k) {
    EntryKind.FOLDER -> "folder"
    EntryKind.NOTE -> "kind_note"
    EntryKind.PDF -> "kind_pdf_note"
    EntryKind.CANVAS -> "kind_canvas"
    EntryKind.MIND_MAP -> "map_title"
    EntryKind.SPLIT -> "split_workspace"
})

internal fun sortLabel(k: ExplorerSortKey): String = text(when (k) {
    ExplorerSortKey.NAME -> "sort_name"
    ExplorerSortKey.MODIFIED -> "sort_modified"
    ExplorerSortKey.CREATED -> "sort_created"
    ExplorerSortKey.SIZE -> "sort_size"
})

private fun groupChip(g: GroupBy): String = text(when (g) {
    GroupBy.NONE -> "no_grouping"
    GroupBy.DATE -> "grouped_by_date"
    GroupBy.KIND -> "grouped_by_kind"
    GroupBy.COLOUR -> "grouped_by_date"
})

private fun groupLabel(g: GroupBy): String = text(when (g) {
    GroupBy.NONE -> "none"
    GroupBy.DATE -> "group_date"
    GroupBy.KIND -> "group_kind"
    GroupBy.COLOUR -> "group_date"
})

/** Colour groups come with colour tags; until then the menu offers the rest. */
private val HomeGroups = listOf(GroupBy.NONE, GroupBy.DATE, GroupBy.KIND)

/** Grid columns for a screen [width] wide, as Android picks them for medium tiles. */
internal fun gridColumns(width: Dp): Int = (width.value / HomeSize.TILE_WIDTH).roundToInt().coerceIn(1, 10)

/** The chips over the files: sort (in the grid), grouping and the kind filter, and on the right what the folder holds. */
@Composable
internal fun ChipRow(state: HomeState, arranged: Arranged?) {
    val p = LocalHome.current
    val view = state.view
    Row(Modifier.fillMaxWidth().height(HomeSize.chipRow).padding(top = Space.s8), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Space.s12), verticalAlignment = Alignment.CenterVertically) {
            if (view.layout == ExplorerLayout.GRID) {
                var open by remember { mutableStateOf(false) }
                Box {
                    HomeChip(sortLabel(view.sortKey), true, XnotesIcons.sort, if (view.descending) XnotesIcons.arrowDown else XnotesIcons.arrowUp) { open = true }
                    if (open) HomeMenu({ open = false }) {
                        ExplorerSortKey.entries.forEach { k ->
                            val active = k == view.sortKey
                            MenuRow(sortLabel(k), chosen = active, trailing = if (!active) null else if (view.descending) XnotesIcons.arrowDown else XnotesIcons.arrowUp) {
                                open = false
                                state.setView(view.copy(sortKey = k, descending = if (active) !view.descending else k != ExplorerSortKey.NAME))
                            }
                        }
                    }
                }
            }
            var groupOpen by remember { mutableStateOf(false) }
            Box {
                HomeChip(groupChip(view.groupBy), view.groupBy != GroupBy.NONE, XnotesIcons.layers, XnotesIcons.chevronDown) { groupOpen = true }
                if (groupOpen) HomeMenu({ groupOpen = false }) {
                    HomeGroups.forEach { g -> MenuRow(groupLabel(g), chosen = g == view.groupBy) { groupOpen = false; state.setView(view.copy(groupBy = g)) } }
                }
            }
            var kindOpen by remember { mutableStateOf(false) }
            Box {
                val k = state.kindFilter
                HomeChip(k?.let(HomeWords::kinds) ?: text("all_kinds"), k != null, XnotesIcons.filter, XnotesIcons.chevronDown) { kindOpen = true }
                if (kindOpen) HomeMenu({ kindOpen = false }) {
                    listOf(null, EntryKind.NOTE, EntryKind.PDF, EntryKind.CANVAS, EntryKind.MIND_MAP).forEach { option ->
                        MenuRow(option?.let(HomeWords::kinds) ?: text("all_kinds"), chosen = option == k) { kindOpen = false; state.kindFilter = option }
                    }
                }
            }
        }
        if (arranged != null) {
            val size = if (view.layout == ExplorerLayout.LIST) arranged.bytes.takeIf { it > 0 }?.let(::formatSize) else null
            val label = listOfNotNull(countsLabel(HomeWords, arranged.folders.size, arranged.files).ifEmpty { null }, size).joinToString(" · ")
            Label(label, p.textDim, TextSize.body, Modifier.padding(start = Space.s8, end = Space.s4))
        }
    }
}

@Composable
private fun GroupHeader(label: String, count: Int, modifier: Modifier = Modifier) {
    val p = LocalHome.current
    Row(modifier.height(HomeSize.groupHeader).semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
        Label(label, p.text, TextSize.body, weight = FontWeight.Medium)
        Label("$count", p.textDim, TextSize.body)
    }
}

/** A document's preview, loading it the first time it shows. */
@Composable
private fun rememberPreview(e: IosDocumentEntry, dark: Boolean): DocPreview? {
    val cached = HomeThumbs.cached(e, dark)
    if (cached == null) LaunchedEffect(e.path, e.modified, dark) { HomeThumbs.load(e, dark) }
    return cached
}

/** A thumbnail on the page colour, cropped to the top of the first page; a file icon until it loads. */
@Composable
private fun Thumb(preview: DocPreview?, modifier: Modifier) {
    val p = LocalHome.current
    Box(modifier.background(p.paper), contentAlignment = Alignment.Center) {
        val image = preview?.image
        if (image != null) Image(image, null, Modifier.fillMaxSize(), alignment = Alignment.TopCenter, contentScale = ContentScale.Crop)
        else if (preview == null) Icon(XnotesIcons.file, p.textDim, 28.dp)
    }
}

@Composable
private fun TileBadge(icon: ImageVector, label: String, modifier: Modifier) {
    val p = LocalHome.current
    val shape = RoundedCornerShape(HomeSize.tileCorner)
    Row(modifier.height(22.dp).clip(shape).background(p.sheet.copy(alpha = if (p.sheet == Color.Black) 0.78f else 0.92f)).border(HomeSize.hairline, p.border, shape)
        .padding(start = Space.s6, end = Space.s8), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
        Icon(icon, p.textDim, 13.dp)
        Label(label, p.text, TextSize.caption, weight = FontWeight.Medium)
    }
}

@Composable
private fun FileTile(e: IosDocumentEntry, look: EntryLook, open: () -> Unit) {
    val p = LocalHome.current
    val preview = rememberPreview(e, look.dark)
    val kind = kindOf(e, preview)
    val shape = RoundedCornerShape(HomeSize.tileCorner)
    Column(Modifier.clickable(role = Role.Button, onClick = open).semantics(mergeDescendants = true) {}) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(shape).border(HomeSize.hairline, p.border, shape)) {
            Thumb(preview, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().padding(Space.s8)) {
                if (kind == EntryKind.PDF || kind == EntryKind.CANVAS)
                    TileBadge(kindIcon(kind), if (kind == EntryKind.PDF) "PDF" else text("kind_canvas"), Modifier.align(Alignment.BottomStart))
                val pages = preview?.pages ?: 0
                if (pages > 1) TileBadge(XnotesIcons.pages, "$pages", Modifier.align(Alignment.BottomEnd))
            }
        }
        Column(Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(start = Space.s12, end = Space.s12, top = Space.s6, bottom = Space.s8),
            verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            Label(shownName(e), p.text, TextSize.bodyLarge)
            val meta = look.whenText(e)
            if (meta.isNotEmpty()) Label(meta, p.textDim, TextSize.label)
        }
    }
}

@Composable
private fun FolderChip(e: IosDocumentEntry, look: EntryLook, open: () -> Unit) {
    val p = LocalHome.current
    val shape = RoundedCornerShape(HomeSize.chipCorner)
    Row(Modifier.fillMaxWidth().height(HomeSize.folderChip).clip(shape).border(HomeSize.hairline, p.border, shape)
        .clickable(role = Role.Button, onClick = open).semantics(mergeDescendants = true) {}.padding(horizontal = Space.s12),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(XnotesIcons.folder, p.textDim, 22.dp)
        Spacer(Modifier.width(Space.s12))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            Label(e.name, p.text, TextSize.bodyLarge)
            Label(look.folderMeta(e), p.textDim, TextSize.label)
        }
    }
}

@Composable
internal fun GridBody(arranged: Arranged, look: EntryLook, columns: Int, chips: @Composable () -> Unit, open: (IosDocumentEntry) -> Unit) {
    LazyVerticalGrid(
        GridCells.Fixed(columns), Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = HomeSize.gutter, end = HomeSize.gutter, bottom = HomeSize.bottomClearance),
        horizontalArrangement = Arrangement.spacedBy(Space.s12), verticalArrangement = Arrangement.spacedBy(Space.s12),
    ) {
        item(key = "chips", span = { GridItemSpan(maxLineSpan) }) { chips() }
        if (arranged.folders.isNotEmpty()) {
            items(arranged.folders, key = { it.path.toString() }) { FolderChip(it, look) { open(it) } }
            item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(Space.s4)) }
        }
        arranged.groups.forEach { g ->
            if (g.label.isNotEmpty()) item(key = "group:${g.key}", span = { GridItemSpan(maxLineSpan) }) {
                GroupHeader(g.label, g.items.size, Modifier.padding(top = Space.s2))
            }
            items(g.items, key = { it.path.toString() }) { FileTile(it, look) { open(it) } }
        }
    }
}

@Composable
private fun ColumnHead(label: String, key: ExplorerSortKey?, state: HomeState, modifier: Modifier, end: Boolean = false) {
    val p = LocalHome.current
    val view = state.view
    val active = key != null && view.sortKey == key
    val tint = if (active) p.text else p.textDim
    Row(modifier.fillMaxHeight().then(if (key == null) Modifier else Modifier.clickable(role = Role.Button) {
        state.setView(view.copy(sortKey = key, descending = if (active) !view.descending else key != ExplorerSortKey.NAME))
    }.semantics { if (active) selected = true }), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (end) Arrangement.End else Arrangement.Start) {
        Label(label, tint, TextSize.body, weight = FontWeight.Medium)
        if (active) { Spacer(Modifier.width(Space.s4)); Icon(if (view.descending) XnotesIcons.arrowDown else XnotesIcons.arrowUp, tint, 14.dp) }
    }
}

@Composable
private fun Hairline() = Box(Modifier.fillMaxWidth().height(HomeSize.hairline).background(LocalHome.current.border))

@Composable
private fun ListRow(e: IosDocumentEntry, look: EntryLook, wide: Boolean, open: () -> Unit) {
    val p = LocalHome.current
    val preview = if (e.isFolder) null else rememberPreview(e, look.dark)
    val kind = kindOf(e, preview)
    val shape = RoundedCornerShape(HomeSize.chipCorner)
    Row(Modifier.fillMaxWidth().height(HomeSize.listRow).clickable(role = Role.Button, onClick = open).semantics(mergeDescendants = true) {}
        .padding(start = Space.s8, end = Space.s4), verticalAlignment = Alignment.CenterVertically) {
        if (e.isFolder) Box(Modifier.size(HomeSize.listThumb).clip(shape).background(p.surface), contentAlignment = Alignment.Center) {
            Icon(XnotesIcons.folder, p.textDim, 22.dp)
        } else Thumb(preview, Modifier.size(HomeSize.listThumb).clip(shape).border(HomeSize.hairline, p.border, shape))
        Column(Modifier.weight(1f).padding(start = Space.s16, end = Space.s8)) {
            Label(shownName(e), p.text, TextSize.bodyLarge)
            if (!wide) Label(if (e.isFolder) look.folderMeta(e) else look.whenText(e), p.textDim, TextSize.label)
        }
        if (wide) {
            Row(Modifier.width(HomeSize.listKind), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
                Icon(kindIcon(kind), p.textDim, 16.dp)
                Label(kindLabel(kind), p.textDim, TextSize.body)
            }
            val pages = if (e.isFolder || kind == EntryKind.CANVAS) "–" else preview?.pages?.takeIf { it > 0 }?.toString() ?: ""
            Label(pages, p.text, TextSize.body, Modifier.width(HomeSize.listPages), align = TextAlign.End)
            Label(if (e.isFolder) e.childCount?.let { itemsLabel(HomeWords, it) } ?: "" else formatSize(e.size),
                if (e.isFolder) p.textDim else p.text, TextSize.body, Modifier.width(HomeSize.listSize), align = TextAlign.End)
            Label(look.whenText(e), p.textDim, TextSize.body, Modifier.width(HomeSize.listWhen).padding(start = Space.s32))
        }
        Spacer(Modifier.width(Space.s40))
    }
}

@Composable
internal fun ListBody(arranged: Arranged, look: EntryLook, state: HomeState, wide: Boolean, chips: @Composable () -> Unit, open: (IosDocumentEntry) -> Unit) {
    val byCreated = state.view.sortKey == ExplorerSortKey.CREATED
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = HomeSize.gutter, end = HomeSize.gutter, bottom = HomeSize.bottomClearance)) {
        item(key = "chips") { chips() }
        item(key = "heads") {
            Row(Modifier.fillMaxWidth().height(HomeSize.listHeader).padding(end = Space.s4), verticalAlignment = Alignment.CenterVertically) {
                ColumnHead(text("sort_name"), ExplorerSortKey.NAME, state, Modifier.weight(1f).padding(start = Space.s8 + HomeSize.listThumb + Space.s16))
                if (wide) {
                    ColumnHead(text("group_kind"), null, state, Modifier.width(HomeSize.listKind))
                    ColumnHead(text("pages"), null, state, Modifier.width(HomeSize.listPages), end = true)
                    ColumnHead(text("sort_size"), ExplorerSortKey.SIZE, state, Modifier.width(HomeSize.listSize), end = true)
                    ColumnHead(text(if (byCreated) "created" else "modified"), if (byCreated) ExplorerSortKey.CREATED else ExplorerSortKey.MODIFIED,
                        state, Modifier.width(HomeSize.listWhen).padding(start = Space.s32))
                }
                Spacer(Modifier.width(Space.s40))
            }
            Hairline()
        }
        items(arranged.folders, key = { it.path.toString() }) { ListRow(it, look, wide) { open(it) }; Hairline() }
        arranged.groups.forEach { g ->
            if (g.label.isNotEmpty()) item(key = "group:${g.key}") { GroupHeader(g.label, g.items.size, Modifier.padding(start = Space.s8, top = Space.s12)) }
            items(g.items, key = { it.path.toString() }) { ListRow(it, look, wide) { open(it) }; Hairline() }
        }
    }
}

/** The explorer's top bar: the way back to the top folder, the folders on the way here, and the layout switcher. */
@Composable
internal fun HomeHeader(state: HomeState) {
    val p = LocalHome.current
    Row(Modifier.fillMaxWidth().height(HomeSize.header).padding(start = Space.s8, end = HomeSize.gutter), verticalAlignment = Alignment.CenterVertically) {
        HomeIconButton(XnotesIcons.home, text("top_folder")) { state.go(state.root) }
        Spacer(Modifier.width(Space.s4))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (state.pane == HomePane.RECOVERY) {
                Label(text("recovery_materials"), p.text, TextSize.title, Modifier.padding(horizontal = Space.s8).semantics { heading() }, FontWeight.Medium)
                return@Row
            }
            val crumbs = listOf(state.root) + state.folders
            crumbs.forEachIndexed { i, path ->
                val last = i == crumbs.lastIndex
                if (i > 0) Icon(XnotesIcons.next, p.textDim, 14.dp, Modifier.padding(horizontal = Space.s2))
                Box(Modifier.heightIn(min = HomeSize.headerButton).clip(RoundedCornerShape(HomeSize.rowCorner))
                    .clickable(enabled = !last, role = Role.Button) { state.go(path) }.padding(horizontal = Space.s8),
                    contentAlignment = Alignment.Center) {
                    Label(if (i == 0) text("app_name") else path.name, if (last) p.text else p.textDim, TextSize.title,
                        Modifier.widthIn(max = 240.dp).then(if (last) Modifier.semantics { heading() } else Modifier), FontWeight.Medium)
                }
            }
        }
        if (state.pane == HomePane.FILES) LayoutSwitcher(state)
    }
}

@Composable
private fun LayoutSwitcher(state: HomeState) {
    val p = LocalHome.current
    val shape = RoundedCornerShape(HomeSize.tileCorner)
    Row(Modifier.clip(shape).border(HomeSize.hairline, p.border, shape).background(p.sheet)) {
        HomeLayouts.forEach { layout ->
            val on = state.view.layout == layout
            Box(Modifier.size(HomeSize.switcherCell + Space.s16, HomeSize.headerButton).background(if (on) p.selectionBackground else Color.Transparent)
                .clickable(role = Role.Button) { state.setView(state.view.copy(layout = layout)) }
                .semantics { contentDescription = text(if (layout == ExplorerLayout.GRID) "layout_grid" else "layout_list"); selected = on },
                contentAlignment = Alignment.Center) {
                Icon(if (layout == ExplorerLayout.GRID) XnotesIcons.layoutGrid else XnotesIcons.list, if (on) p.selectionForeground else p.text, HomeSize.navIcon)
            }
        }
    }
}
