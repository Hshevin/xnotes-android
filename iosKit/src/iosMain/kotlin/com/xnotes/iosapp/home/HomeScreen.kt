@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.xnotes.iosapp.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.uikit.LocalUIViewController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xnotes.core.util.DocumentKind
import com.xnotes.explorer.EntryKind
import com.xnotes.explorer.EntryGroup
import com.xnotes.explorer.ExplorerLayout
import com.xnotes.iosapp.store
import com.xnotes.iosapp.text
import com.xnotes.iosapp.ui.icons.XnotesIcons
import com.xnotes.iosapp.ui.theme.HomePalette
import com.xnotes.iosapp.ui.theme.HomeSize
import com.xnotes.iosapp.ui.theme.MinTouchTarget
import com.xnotes.iosapp.ui.theme.Space
import com.xnotes.iosapp.ui.theme.TextSize
import com.xnotes.kio.KFiles
import com.xnotes.storage.ios.IosDocumentEntry
import com.xnotes.storage.ios.listIosDocuments
import com.xnotes.storage.lite.LiteKind
import com.xnotes.storage.lite.LiteOpen
import com.xnotes.storage.lite.LiteRecovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path
import kotlin.time.Clock

private sealed interface Listing {
    data object Loading : Listing
    data object Failed : Listing
    /**
     * [opened] is set for Recent: when each was opened, in the order to show them. [flat] lists come from many
     * folders (Recent, search, a colour) and show where each is; [pinned] are the top folder's pinned notes.
     */
    data class Ready(val entries: List<IosDocumentEntry>, val opened: Map<Path, Long>? = null, val flat: Boolean = false,
                     val pinned: List<IosDocumentEntry> = emptyList()) : Listing
}

/** What the new-item sheet is naming. */
internal enum class Naming(val title: String, val confirm: String, val placeholder: String) {
    NOTE("new_note", "create", "untitled"),
    CANVAS("new_canvas", "create", "untitled"),
    FOLDER("new_folder", "create", "folder_name"),
}

/**
 * The home screen after Android's: a floating sidebar (or its rail), and beside it the folder's files under a
 * header and chip row, with a round button for new things. [open] shows a document, [restore] its unsaved
 * changes, and [created] takes a document just made; it answers false when the screen has moved on.
 */
@Composable
internal fun HomeScreen(state: HomeState, dark: Boolean, open: (Path) -> Unit, restore: (LiteRecovery) -> Unit, created: (LiteOpen.Opened) -> Boolean) {
    val p = HomePalette.forTheme(dark)
    val current = state.current
    val pane = state.pane
    val recents = state.recents
    val query = state.query?.trim()?.takeIf { it.isNotEmpty() }
    val color = state.colorFilter
    val scope = rememberCoroutineScope()
    val actions = remember(state) { HomeActions(state, scope) }
    LaunchedEffect(state.revision) { actions.reload() }
    val pins = actions.notePins
    val listing by key(current, pane, query, color) {
        produceState<Listing>(Listing.Loading, state.revision, recents, pins) {
            if (query != null) delay(250)
            value = withContext(Dispatchers.Default) {
                runCatching {
                    when {
                        pane == HomePane.RECENT -> Listing.Ready(recentEntries(recents), recents.associate { it.path to it.opened }, flat = true)
                        query != null -> Listing.Ready(entriesAt(library.search(current, query)), flat = true)
                        color != null -> Listing.Ready(entriesAt(library.findByColor(color)), flat = true)
                        else -> Listing.Ready(listIosDocuments(current), pinned = if (current == state.root) entriesAt(pins) else emptyList())
                    }
                }.getOrElse { Listing.Failed }
            }
        }
    }
    val recoveries by produceState(emptyList<LiteRecovery>(), state.revision) {
        value = withContext(Dispatchers.Default) { runCatching { store.pendingRecoveries() }.getOrDefault(emptyList()) }
    }
    val creator = remember { Creator() }
    val sheet = remember { mutableStateOf<HomeSheet?>(null) }
    CompositionLocalProvider(LocalHome provides p, LocalCreator provides creator, LocalOnCreated provides created,
        LocalActions provides actions, LocalHomeState provides state, LocalSheet provides sheet) {
        BoxWithConstraints(Modifier.fillMaxSize().background(p.backdrop)) {
            val columns = gridColumns(maxWidth)
            Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                if (state.rail) HomeRail(state, recoveries.size) else HomeSidebar(state, recoveries.size)
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                    val wide = maxWidth >= 720.dp
                    Column(Modifier.fillMaxSize()) {
                        HomeHeader(state)
                        when (state.pane) {
                            HomePane.RECOVERY -> RecoveryPane(state, recoveries, restore)
                            HomePane.TRASH -> TrashPane(state, wide)
                            HomePane.FILES, HomePane.RECENT -> Files(state, listing, dark, columns, wide, open)
                        }
                    }
                }
            }
            if (state.pane == HomePane.FILES && state.query == null && state.colorFilter == null && !state.selecting) NewButton(state, Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.safeDrawing))
            Notice(state, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing))
            NameSheet(state, current)
            HomeSheets(state)
        }
    }
}

@Composable
private fun Files(state: HomeState, listing: Listing, dark: Boolean, columns: Int, wide: Boolean, open: (Path) -> Unit) {
    val ready = listing as? Listing.Ready
    val now = remember(listing) { Clock.System.now().toEpochMilliseconds() }
    val clock = remember { clock24() }
    val view = state.view
    val kind = { e: IosDocumentEntry -> kindOf(e, if (e.isFolder) null else HomeThumbs.cached(e, dark)) }
    val opened = ready?.opened
    val names = LocalActions.current.colorNames
    val arranged = ready?.let {
        if (opened == null) arrange(it.entries, view, state.kindFilter, kind, now) { c -> names[c] }
        else Arranged(emptyList(), listOf(EntryGroup("all", "", it.entries.filter { e -> state.kindFilter == null || kind(e) == state.kindFilter })))
    }?.let { a ->
        val pinned = ready.pinned.filter { e -> state.kindFilter == null || kind(e) == state.kindFilter }
        if (pinned.isEmpty() || state.selecting) a else Arranged(a.folders, a.groups, pinned)
    }
    val look = EntryLook(view, now, dark, clock, opened, state.root, ready?.flat == true)
    val chips: @Composable () -> Unit = { ChipRow(state, arranged) }
    val pick = { e: IosDocumentEntry -> if (e.isFolder) state.go(e.path) else open(e.path) }
    when {
        listing == Listing.Loading -> EmptyPane(text("loading"), null)
        listing == Listing.Failed -> EmptyPane(text("list_failed"), null)
        ready!!.entries.isEmpty() && opened != null -> Column(Modifier.padding(horizontal = HomeSize.gutter)) {
            chips()
            EmptyPane(text("recent_empty"), EmptyArt.RECENT)
        }
        ready.entries.isEmpty() && state.query != null -> Column(Modifier.padding(horizontal = HomeSize.gutter)) {
            chips()
            val q = state.query.orEmpty().trim()
            if (q.isEmpty()) EmptyPane(text("search_notes"), EmptyArt.SEARCH) else EmptyPane(fmt("no_notes_match", q), EmptyArt.SEARCH)
        }
        ready.entries.isEmpty() && state.colorFilter != null -> Column(Modifier.padding(horizontal = HomeSize.gutter)) {
            chips()
            EmptyPane(text("nothing_this_colour"), EmptyArt.SEARCH)
        }
        ready.entries.isEmpty() -> Column(Modifier.padding(horizontal = HomeSize.gutter)) {
            chips()
            EmptyPane(text("folder_empty"), EmptyArt.PAGES) { CreateActions(state) }
        }
        arranged!!.isEmpty -> Column(Modifier.padding(horizontal = HomeSize.gutter)) {
            chips()
            EmptyPane(text(when (state.kindFilter) {
                null, EntryKind.SPLIT -> "nothing_to_show"
                EntryKind.PDF -> "no_pdf_notes_here"
                EntryKind.FOLDER -> "kind_no_folders"
                EntryKind.NOTE -> "kind_no_notes"
                EntryKind.CANVAS -> "kind_no_canvases"
                EntryKind.MIND_MAP -> "map_no_documents"
            }), EmptyArt.SEARCH)
        }
        view.layout == ExplorerLayout.LIST -> ListBody(arranged, look, state, wide, chips, pick)
        else -> GridBody(arranged, look, columns, chips, pick)
    }
}

/** The items the new-item menu and an empty folder offer. */
private class Create(val icon: androidx.compose.ui.graphics.vector.ImageVector, val menu: String, val short: String, val naming: Naming?)

private val creates = listOf(
    Create(XnotesIcons.edit, "new_note_menu", "kind_note", Naming.NOTE),
    Create(XnotesIcons.canvas, "new_canvas_menu", "kind_canvas", Naming.CANVAS),
    Create(XnotesIcons.importDoc, "import_pdf", "import_title", null),
    Create(XnotesIcons.newFolder, "new_folder_menu", "folder", Naming.FOLDER),
)

/** Who is creating something, and the sheet naming it; shared by the round button and an empty folder's buttons. */
internal class Creator {
    var naming by mutableStateOf<Naming?>(null)
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
}

internal val LocalCreator = staticCompositionLocalOf { Creator() }

@Composable
private fun RowScope.CreateActions(state: HomeState) {
    val creator = LocalCreator.current
    val controller = LocalUIViewController.current
    val importer = rememberImporter(state)
    creates.filter { it.naming != Naming.FOLDER }.forEach { c ->
        PrimaryButton(c.icon, text(c.menu)) {
            if (c.naming == null) PdfPicker.pick(controller, importer) else { creator.error = null; creator.naming = c.naming }
        }
    }
}

/** Imports a picked PDF into the folder shown and opens it, through the screen's [Creator]. */
@Composable
private fun rememberImporter(state: HomeState): (Path?) -> Unit {
    val creator = LocalCreator.current
    val scope = rememberCoroutineScope()
    val onCreated = LocalOnCreated.current
    return { picked ->
        if (picked != null && !creator.busy) {
            val folder = state.current
            creator.busy = true
            scope.launch {
                var owned: LiteOpen.Opened? = null
                try {
                    val result = withContext(Dispatchers.Default) {
                        try { store.importPdf(folder, pdfStem(picked.name), picked) }
                        finally { runCatching { KFiles.system.delete(picked) } }
                    }
                    owned = result as? LiteOpen.Opened
                    if (owned == null) state.say(text("import_pdf_failed"))
                    else { state.revision++; if (onCreated(owned)) owned = null }
                } finally { owned?.session?.close(); creator.busy = false }
            }
        }
    }
}

private val LocalOnCreated = staticCompositionLocalOf<(LiteOpen.Opened) -> Boolean> { { false } }

/** A PDF's name without its extension, for the note made from it. */
/** What lies at [paths] now, for the lists that gather items from many folders. */
private fun entriesAt(paths: List<Path>): List<IosDocumentEntry> {
    val folders = HashMap<Path, Map<Path, IosDocumentEntry>>()
    return paths.mapNotNull { path ->
        val dir = path.parent ?: return@mapNotNull null
        folders.getOrPut(dir) { runCatching { listIosDocuments(dir) }.getOrDefault(emptyList()).associateBy { it.path } }[path]
    }
}

/** Recent's documents as they are now; ones that have gone are left out but kept in the list, as on Android. */
private fun recentEntries(recents: List<RecentDoc>): List<IosDocumentEntry> = recents.mapNotNull { r ->
    val info = KFiles.system.metadataOrNull(r.path)?.takeIf { it.isRegularFile && it.symlinkTarget == null } ?: return@mapNotNull null
    val kind = DocumentKind.ofName(r.path.name)?.takeIf { it != DocumentKind.MIND_MAP } ?: return@mapNotNull null
    IosDocumentEntry(r.path, kind, info.lastModifiedAtMillis, info.size ?: 0)
}

private fun pdfStem(name: String) = name.substringBeforeLast('.', name).ifBlank { text("untitled") }

@Composable
private fun NewButton(state: HomeState, modifier: Modifier) {
    val p = LocalHome.current
    val creator = LocalCreator.current
    var menu by remember { mutableStateOf(false) }
    val controller = LocalUIViewController.current
    val importer = rememberImporter(state)
    Box(modifier.padding(HomeSize.fabMargin)) {
        Box(Modifier.size(HomeSize.fab).shadow(6.dp, CircleShape).clip(CircleShape).background(p.selectionBackground)
            .clickable(enabled = !creator.busy, role = Role.Button) { menu = true }.semantics { contentDescription = text("create_new") },
            contentAlignment = Alignment.Center) { Icon(XnotesIcons.edit, p.selectionForeground, 24.dp) }
        if (menu) HomeMenu({ menu = false }, above = true) {
            creates.forEach { c ->
                MenuRow(text(c.menu), c.icon, height = HomeSize.menuRow) {
                    menu = false
                    if (c.naming == null) PdfPicker.pick(controller, importer) else { creator.error = null; creator.naming = c.naming }
                }
            }
        }
    }
}

/** The sheet naming a new note, canvas or folder in [folder], and making it. */
@Composable
private fun NameSheet(state: HomeState, folder: Path) {
    val creator = LocalCreator.current
    val created = LocalOnCreated.current
    val scope = rememberCoroutineScope()
    val naming = creator.naming ?: return
    NameDialog(text(naming.title), "", text(naming.confirm), text(naming.placeholder), allowEmpty = naming != Naming.FOLDER, creator.error,
        onConfirm = { name ->
            if (creator.busy) return@NameDialog
            if (naming == Naming.FOLDER) {
                val made = runCatching {
                    require(name.isNotEmpty() && '/' !in name && !name.startsWith('.'))
                    KFiles.system.createDirectory(folder / name, mustCreate = true)
                }.isSuccess
                if (made) { creator.naming = null; state.revision++ } else creator.error = text("err_create_folder")
                return@NameDialog
            }
            creator.busy = true
            scope.launch {
                var owned: LiteOpen.Opened? = null
                try {
                    val kind = if (naming == Naming.NOTE) LiteKind.NOTE else LiteKind.CANVAS
                    owned = withContext(Dispatchers.Default) { store.create(folder, name.ifBlank { text("untitled") }, kind) } as? LiteOpen.Opened
                    val made = owned
                    if (made == null) creator.error = text("create_failed")
                    else { creator.naming = null; state.revision++; if (created(made)) owned = null }
                } finally { owned?.session?.close(); creator.busy = false }
            }
        },
        onDismiss = { if (!creator.busy) creator.naming = null })
}

/** Unsaved changes kept from notes that closed unexpectedly, each to restore or let go. */
@Composable
private fun RecoveryPane(state: HomeState, recoveries: List<LiteRecovery>, restore: (LiteRecovery) -> Unit) {
    val p = LocalHome.current
    if (recoveries.isEmpty()) { EmptyPane(text("recovery_empty"), EmptyArt.RECENT); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = HomeSize.gutter, end = HomeSize.gutter, top = Space.s8, bottom = HomeSize.bottomClearance)) {
        items(recoveries, key = { it.record.toString() }) { r ->
            Row(Modifier.fillMaxWidth().heightIn(min = HomeSize.listRow).padding(horizontal = Space.s8), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
                Box(Modifier.size(HomeSize.listThumb).clip(RoundedCornerShape(HomeSize.chipCorner)).background(p.surface), contentAlignment = Alignment.Center) {
                    Icon(XnotesIcons.restore, p.textDim, 20.dp)
                }
                Column(Modifier.weight(1f).padding(start = Space.s8)) {
                    Label(DocumentKind.stripSuffix(r.original.name), p.text, TextSize.bodyLarge)
                    Label(text("recovery_unsaved"), p.textDim, TextSize.label)
                }
                HomeChip(text("discard"), false) {
                    if (runCatching { store.discard(r) }.isFailure) state.say(text("discard_failed"))
                    state.revision++
                }
                HomeChip(text("restore"), true) { restore(r) }
            }
            Box(Modifier.fillMaxWidth().height(HomeSize.hairline).background(p.border))
        }
    }
}

/** The screen's passing message, gone after a few seconds. */
@Composable
private fun Notice(state: HomeState, modifier: Modifier) {
    val p = LocalHome.current
    val notice = state.notice ?: return
    LaunchedEffect(notice) { delay(if (notice.action != null) 10_000 else 4_000); if (state.notice === notice) state.notice = null }
    val shape = RoundedCornerShape(HomeSize.tileCorner)
    Row(modifier.padding(bottom = HomeSize.fabMargin).widthIn(max = 560.dp).clip(shape).background(p.selectionBackground)
        .semantics { liveRegion = LiveRegionMode.Polite }.padding(start = Space.s20, end = if (notice.action != null) Space.s8 else Space.s20),
        verticalAlignment = Alignment.CenterVertically) {
        Label(notice.text, p.selectionForeground, TextSize.bodyLarge, Modifier.padding(vertical = Space.s12).weight(1f, fill = false), FontWeight.Medium, lines = 3)
        if (notice.action != null) {
            Spacer(Modifier.width(Space.s12))
            Box(Modifier.heightIn(min = MinTouchTarget).clip(RoundedCornerShape(HomeSize.rowCorner))
                .clickable(role = Role.Button) { state.notice = null; notice.run?.invoke() }.padding(horizontal = Space.s12),
                contentAlignment = Alignment.Center) { Label(notice.action, p.selectionForeground, TextSize.bodyLarge, weight = FontWeight.Bold) }
        }
    }
}
