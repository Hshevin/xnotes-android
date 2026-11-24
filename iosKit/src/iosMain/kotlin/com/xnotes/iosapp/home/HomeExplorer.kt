package com.xnotes.iosapp.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.focus.focusRequester
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
/**
 * A folder's listing as drawn: its folders, its files in groups, and the top folder's [pinned] notes on a shelf of
 * their own above them. A pinned note is listed in its group as well, as on Android, so counts leave the shelf out.
 */
internal class Arranged(val folders: List<IosDocumentEntry>, val groups: List<EntryGroup<IosDocumentEntry>>,
                        val pinned: List<IosDocumentEntry> = emptyList()) {
    val all: List<IosDocumentEntry> get() = folders + groups.flatMap { it.items }
    val files: Int get() = groups.sumOf { it.items.size }
    val isEmpty: Boolean get() = folders.isEmpty() && files == 0
    val bytes: Long get() = groups.sumOf { g -> g.items.sumOf { it.size } }

    /** The groups drawn, the pinned shelf first; [EntryGroup.key] keeps a note on the shelf apart from itself in its group. */
    fun shown(pinnedLabel: String): List<EntryGroup<IosDocumentEntry>> =
        if (pinned.isEmpty()) groups else listOf(EntryGroup(PINNED, pinnedLabel, pinned)) + groups

    companion object { const val PINNED = "pinned" }
}

/** A file's key in the lazy lists: the same note can be both on the pinned shelf and in its own group. */
internal fun entryKey(group: EntryGroup<IosDocumentEntry>, e: IosDocumentEntry): String = "${group.key}/${e.path}"

internal fun arrange(entries: List<IosDocumentEntry>, view: ExplorerView, filter: EntryKind?, kind: (IosDocumentEntry) -> EntryKind, now: Long,
                     colorName: (com.xnotes.core.model.Rgba) -> String? = { null }): Arranged {
    val shown = entries.sortedWith(explorerComparator(view.sortKey, view.descending)).filter { filter == null || kind(it) == filter }
    val files = shown.filterNot { it.isFolder }
    return Arranged(shown.filter { it.isFolder },
        groupEntries(HomeWords, files, view.groupBy, view.sortKey, view.descending, kind, colorName, now, TimeZone.currentSystemDefault()))
}

/** What the explorer needs to draw one entry. */
internal class EntryLook(val view: ExplorerView, val now: Long, val dark: Boolean, private val clock24: Boolean,
                         private val opened: Map<okio.Path, Long>? = null, private val root: okio.Path? = null, private val flat: Boolean = false) {
    /** Recent shows when each was opened instead of its date. */
    fun whenText(e: IosDocumentEntry): String = opened?.get(e.path)?.let { openedLabel(HomeWords, it, now) } ?: formatWhen(HomeWords, if (view.sortKey == ExplorerSortKey.CREATED) e.created else e.modified,
        now, "day", view.showTime, clock24, TimeZone.currentSystemDefault())

    /** The folder an item is in, where items come from many folders (Recent, search, a colour); null elsewhere. */
    fun where(e: IosDocumentEntry): String? = if (!flat) null else e.path.parent?.let { if (it == root) text("app_name") else it.name }

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
    GroupBy.COLOUR -> "grouped_by_colour"
})

private fun groupLabel(g: GroupBy): String = text(when (g) {
    GroupBy.NONE -> "none"
    GroupBy.DATE -> "group_date"
    GroupBy.KIND -> "group_kind"
    GroupBy.COLOUR -> "colour"
})

/** Grid columns for a screen [width] wide, as Android picks them for medium tiles. */
internal fun gridColumns(width: Dp): Int = (width.value / HomeSize.TILE_WIDTH).roundToInt().coerceIn(1, 10)

/** The chips over the files: sort (in the grid), grouping and the kind filter, and on the right what the folder holds. */
@Composable
internal fun ChipRow(state: HomeState, arranged: Arranged?) {
    val p = LocalHome.current
    val view = state.view
    if (state.selecting) { SelectionBar(arranged?.all.orEmpty().map { it.path }); return }
    Row(Modifier.fillMaxWidth().height(HomeSize.chipRow).padding(top = Space.s8), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Space.s12), verticalAlignment = Alignment.CenterVertically) {
            val flat = state.pane == HomePane.RECENT
            if (view.layout == ExplorerLayout.GRID && !flat) {
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
            if (!flat) Box {
                HomeChip(groupChip(view.groupBy), view.groupBy != GroupBy.NONE, XnotesIcons.layers, XnotesIcons.chevronDown) { groupOpen = true }
                if (groupOpen) HomeMenu({ groupOpen = false }) {
                    GroupBy.entries.forEach { g -> MenuRow(groupLabel(g), chosen = g == view.groupBy) { groupOpen = false; state.setView(view.copy(groupBy = g)) } }
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
            val clip = state.clip
            if (clip != null && state.pane == HomePane.FILES && state.query == null && state.colorFilter == null) {
                val actions = LocalActions.current
                HomeChip(plural("paste_items", clip.items.size), true, XnotesIcons.paste) { actions.paste(state.current) }
                HomeIconButton(XnotesIcons.close, text("clear_clipboard"), p.textDim) { state.clip = null }
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

/** Tap opens (or, in select mode, picks); a long press starts select mode with this item; cut items fade. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.pick(e: IosDocumentEntry, open: () -> Unit): Modifier {
    val state = LocalHomeState.current
    val toggle = { state.selected = if (e.path in state.selected) state.selected - e.path else state.selected + e.path }
    return this.alpha(if (state.clip?.let { it.cut && e.path in it.items } == true) 0.4f else 1f)
        .combinedClickable(role = Role.Button, onClick = { if (state.selecting) toggle() else open() }, onLongClick = toggle)
}

@Composable
private fun FileTile(e: IosDocumentEntry, look: EntryLook, open: () -> Unit) {
    val p = LocalHome.current
    val state = LocalHomeState.current
    val preview = rememberPreview(e, look.dark)
    val kind = kindOf(e, preview)
    val picked = e.path in state.selected
    val code = e.color?.let { codeTint(it, look.dark) }
    val shape = RoundedCornerShape(HomeSize.tileCorner)
    Column(Modifier.pick(e, open).semantics(mergeDescendants = true) { selected = picked }) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(shape).border(if (picked) 2.dp else HomeSize.hairline, if (picked) p.text else code ?: p.border, shape)) {
            Thumb(preview, Modifier.fillMaxSize())
            if (picked) Box(Modifier.fillMaxSize().background(p.selectionBackground.copy(alpha = 0.3f)))
            Box(Modifier.fillMaxSize().padding(Space.s8)) {
                if (kind == EntryKind.PDF || kind == EntryKind.CANVAS)
                    TileBadge(kindIcon(kind), if (kind == EntryKind.PDF) "PDF" else text("kind_canvas"), Modifier.align(Alignment.BottomStart))
                val pages = preview?.pages ?: 0
                if (pages > 1) TileBadge(XnotesIcons.pages, "$pages", Modifier.align(Alignment.BottomEnd))
                if (state.selecting) CheckRing(picked, Modifier.align(Alignment.TopStart))
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(start = Space.s12, end = Space.s2, top = Space.s6, bottom = Space.s8),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                Label(shownName(e), p.text, TextSize.bodyLarge)
                val meta = listOfNotNull(look.whenText(e).ifEmpty { null }, formatSize(e.size).takeIf { look.view.showSize }).joinToString(" · ")
                if (meta.isNotEmpty()) Label(meta, p.textDim, TextSize.label)
            }
            if (!state.selecting) EntryMenuButton(e, p.textDim)
        }
    }
}

@Composable
private fun FolderChip(e: IosDocumentEntry, look: EntryLook, open: () -> Unit) {
    val p = LocalHome.current
    val state = LocalHomeState.current
    val active = e.path in state.selected
    val code = e.color?.let { codeTint(it, look.dark) }
    val fg = if (active) p.selectionForeground else p.text
    val shape = RoundedCornerShape(HomeSize.chipCorner)
    Row(Modifier.fillMaxWidth().height(HomeSize.folderChip).clip(shape).background(if (active) p.selectionBackground else Color.Transparent)
        .border(HomeSize.hairline, if (active) p.text else code ?: p.border, shape)
        .pick(e, open).semantics(mergeDescendants = true) { selected = active }.padding(start = Space.s12, end = Space.s2),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(XnotesIcons.folder, if (active) fg else code ?: p.textDim, 22.dp)
        Spacer(Modifier.width(Space.s12))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            Label(e.name, fg, TextSize.bodyLarge)
            Label(look.folderMeta(e), if (active) fg else p.textDim, TextSize.label)
        }
        if (e.path in state.pinnedFolders) Icon(XnotesIcons.pin, if (active) fg else p.textDim, 14.dp)
        if (!state.selecting) EntryMenuButton(e, p.textDim)
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
        arranged.shown(text("pinned")).forEach { g ->
            if (g.label.isNotEmpty()) item(key = "group:${g.key}", span = { GridItemSpan(maxLineSpan) }) {
                GroupHeader(g.label, g.items.size, Modifier.padding(top = Space.s2))
            }
            items(g.items, key = { entryKey(g, it) }) { FileTile(it, look) { open(it) } }
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
    val state = LocalHomeState.current
    val preview = if (e.isFolder) null else rememberPreview(e, look.dark)
    val kind = kindOf(e, preview)
    val picked = e.path in state.selected
    val code = e.color?.let { codeTint(it, look.dark) }
    val fg = if (picked) p.selectionForeground else p.text
    val dim = if (picked) fg.copy(alpha = 0.72f) else p.textDim
    val shape = RoundedCornerShape(HomeSize.chipCorner)
    Row(Modifier.fillMaxWidth().height(HomeSize.listRow).background(if (picked) p.selectionBackground else Color.Transparent)
        .pick(e, open).semantics(mergeDescendants = true) { selected = picked }
        .padding(start = Space.s8, end = Space.s4), verticalAlignment = Alignment.CenterVertically) {
        if (state.selecting) { CheckRing(picked); Spacer(Modifier.width(Space.s16)) }
        if (e.isFolder) Box(Modifier.size(HomeSize.listThumb).clip(shape).background(p.surface), contentAlignment = Alignment.Center) {
            Icon(XnotesIcons.folder, code ?: p.textDim, 22.dp)
        } else Thumb(preview, Modifier.size(HomeSize.listThumb).clip(shape).border(HomeSize.hairline, p.border, shape))
        Row(Modifier.weight(1f).padding(start = Space.s16, end = Space.s8), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
            Column(Modifier.weight(1f, fill = false)) {
                Label(shownName(e), fg, TextSize.bodyLarge)
                val where = look.where(e)
                if (!wide) Label(listOfNotNull(where, if (e.isFolder) look.folderMeta(e) else look.whenText(e)).joinToString(" · "), dim, TextSize.label)
                else if (where != null) Label(where, dim, TextSize.label)
            }
            if (code != null && !e.isFolder) Box(Modifier.size(8.dp).clip(CircleShape).background(code))
            if (e.path in state.pinnedFolders) Icon(XnotesIcons.pin, dim, 14.dp)
        }
        if (wide) {
            Row(Modifier.width(HomeSize.listKind), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
                Icon(kindIcon(kind), dim, 16.dp)
                Label(kindLabel(kind), dim, TextSize.body)
            }
            val pages = if (e.isFolder || kind == EntryKind.CANVAS) "–" else preview?.pages?.takeIf { it > 0 }?.toString() ?: ""
            Label(pages, fg, TextSize.body, Modifier.width(HomeSize.listPages), align = TextAlign.End)
            Label(if (e.isFolder) e.childCount?.let { itemsLabel(HomeWords, it) } ?: "" else formatSize(e.size),
                if (e.isFolder) dim else fg, TextSize.body, Modifier.width(HomeSize.listSize), align = TextAlign.End)
            Label(look.whenText(e), dim, TextSize.body, Modifier.width(HomeSize.listWhen).padding(start = Space.s32))
        }
        if (!state.selecting) EntryMenuButton(e, dim, 40.dp) else Spacer(Modifier.width(Space.s40))
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
        arranged.shown(text("pinned")).forEach { g ->
            if (g.label.isNotEmpty()) item(key = "group:${g.key}") { GroupHeader(g.label, g.items.size, Modifier.padding(start = Space.s8, top = Space.s12)) }
            items(g.items, key = { entryKey(g, it) }) { ListRow(it, look, wide) { open(it) }; Hairline() }
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
            val title = when {
                state.pane == HomePane.RECENT -> text("recent")
                state.pane == HomePane.RECOVERY -> text("recovery_materials")
                state.pane == HomePane.TRASH -> text("trash")
                else -> null
            }
            val color = state.colorFilter
            when {
                title != null -> Label(title, p.text, TextSize.title, Modifier.padding(horizontal = Space.s8).semantics { heading() }, FontWeight.Medium)
                state.query != null -> SearchField(state)
                color != null -> {
                    Box(Modifier.padding(start = Space.s8).size(12.dp).clip(CircleShape).background(codeTint(color, p.sheet == Color.Black)))
                    Label(LocalActions.current.colorNames[color] ?: hueName(HomeWords, color), p.text, TextSize.title,
                        Modifier.padding(horizontal = Space.s8).semantics { heading() }, FontWeight.Medium)
                }
                else -> Crumbs(state)
            }
        }
        if (state.pane == HomePane.FILES && state.query == null) {
            HomeIconButton(XnotesIcons.search, text("search_notes")) { state.query = ""; state.selected = emptySet() }
            Spacer(Modifier.width(Space.s8))
        }
        if (state.pane != HomePane.RECOVERY && state.pane != HomePane.TRASH) LayoutSwitcher(state)
    }
}

@Composable
private fun RowScope.Crumbs(state: HomeState) {
    val p = LocalHome.current
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

/** The search box over the crumbs: it searches the folder shown and every folder in it, by name. */
@Composable
private fun RowScope.SearchField(state: HomeState) {
    val p = LocalHome.current
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val shape = RoundedCornerShape(HomeSize.chipCorner)
    Row(Modifier.weight(1f).widthIn(max = 480.dp).height(HomeSize.headerButton).clip(shape).background(p.surface).border(HomeSize.hairline, p.border, shape)
        .padding(start = Space.s12), verticalAlignment = Alignment.CenterVertically) {
        Icon(XnotesIcons.search, p.textDim, 18.dp)
        Spacer(Modifier.width(Space.s8))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            val q = state.query.orEmpty()
            if (q.isEmpty()) Label(text("search_notes"), p.textDim, TextSize.bodyLarge)
            androidx.compose.foundation.text.BasicTextField(q, { state.query = it }, Modifier.fillMaxWidth().focusRequester(focus)
                .semantics { contentDescription = text("search_notes") }, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = p.text, fontSize = TextSize.bodyLarge),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(p.text))
        }
        HomeIconButton(XnotesIcons.close, text("clear_search"), p.textDim) { state.query = null }
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
