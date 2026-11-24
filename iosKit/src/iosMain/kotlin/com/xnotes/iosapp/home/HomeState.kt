package com.xnotes.iosapp.home

import androidx.compose.runtime.*
import com.xnotes.core.model.Rgba
import com.xnotes.core.util.DocumentKind
import com.xnotes.explorer.EntryKind
import com.xnotes.explorer.ExplorerLayout
import com.xnotes.explorer.ExplorerSortKey
import com.xnotes.explorer.ExplorerView
import com.xnotes.explorer.GroupBy
import com.xnotes.storage.ios.IosDocumentEntry
import com.xnotes.storage.ios.IosStorageLocations
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSUserDefaults

/** Where the home screen is: a folder's files, or the recovery list. */
internal enum class HomePane { FILES, RECENT, RECOVERY, TRASH }

/** Items copied or cut, waiting to be pasted. */
internal class Clip(val items: List<Path>, val cut: Boolean)

/** A short message at the bottom of the screen, with one action (Undo) when it has one. */
internal class Notice(val text: String, val action: String? = null, val run: (() -> Unit)? = null)

/** A document opened on this iPad, and when. */
internal class RecentDoc(val path: Path, val opened: Long)

/** The layouts this iPad offers so far, in the switcher's order. */
internal val HomeLayouts = listOf(ExplorerLayout.GRID, ExplorerLayout.LIST)

/**
 * The home screen's state that outlives opening a document: the folder shown, the pane, the sidebar's width,
 * and the view options, which are kept between launches.
 */
internal class HomeState(private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults) {
    val root: Path = IosStorageLocations.documents
    /** The folders opened under [root], outermost first. */
    var folders by mutableStateOf(emptyList<Path>())
    var pane by mutableStateOf(HomePane.FILES)
    var rail by mutableStateOf(defaults.boolForKey(RAIL))
        private set
    var view by mutableStateOf(loadView())
        private set
    /** Folders open in the sidebar's tree. */
    var expanded by mutableStateOf(emptySet<Path>())
    /** Only this kind of file shows, or everything. Not kept, as on Android. */
    var kindFilter by mutableStateOf<EntryKind?>(null)
    /** Bumped to read the folders again. */
    var revision by mutableIntStateOf(0)
    var notice by mutableStateOf<Notice?>(null)
    /** Items picked in select mode; selecting while any are. */
    var selected by mutableStateOf(emptySet<Path>())
    val selecting: Boolean get() = selected.isNotEmpty()
    var clip by mutableStateOf<Clip?>(null)
    /** The search box's text while it is open, or null. */
    var query by mutableStateOf<String?>(null)
    /** Everything in this colour, across the top folder, in place of a folder. */
    var colorFilter by mutableStateOf<Rgba?>(null)
    /** Folders pinned to the sidebar on this iPad. */
    var pinnedFolders by mutableStateOf(loadPinned())
        private set

    fun togglePinned(folder: Path) {
        pinnedFolders = if (folder in pinnedFolders) pinnedFolders - folder else pinnedFolders + folder
        defaults.setObject(pinnedFolders.map { it.relativeTo(root).toString() }, PINNED)
    }

    private fun loadPinned(): List<Path> = (defaults.stringArrayForKey(PINNED) ?: emptyList<Any?>()).mapNotNull { (it as? String)?.let { rel -> root / rel } }

    val current: Path get() = folders.lastOrNull() ?: root

    /** Documents opened on this iPad, newest first, as Android keeps them. */
    var recents by mutableStateOf(loadRecents())
        private set

    fun opened(path: Path, now: Long) = setRecents((listOf(RecentDoc(path, now)) + recents.filter { it.path != path }).take(RECENT_MAX))

    /** Pins and Recent follow what moved from [from] (a document, or a folder and all in it) to [to], or forget it when [to] is null. */
    fun followMove(from: Path, to: Path?) {
        moved(from, to)
        val prefix = "$from/"
        val next = pinnedFolders.mapNotNull { f ->
            val p = f.toString()
            when { f == from -> to; p.startsWith(prefix) -> to?.let { "$it/${p.removePrefix(prefix)}".toPath() }; else -> f }
        }
        if (next != pinnedFolders) { pinnedFolders = next; defaults.setObject(next.map { it.relativeTo(root).toString() }, PINNED) }
    }

    /** Recent follows what moved from [from] (a document, or a folder and all in it) to [to], or forgets it when [to] is null. */
    fun moved(from: Path, to: Path?) {
        val prefix = "$from/"
        setRecents(recents.mapNotNull { r ->
            val p = r.path.toString()
            when {
                r.path == from -> to?.let { RecentDoc(it, r.opened) }
                p.startsWith(prefix) -> to?.let { RecentDoc("$to/${p.removePrefix(prefix)}".toPath(), r.opened) }
                else -> r
            }
        })
    }

    private fun setRecents(list: List<RecentDoc>) {
        recents = list
        defaults.setObject(list.map { "${it.opened}\t${it.path.relativeTo(root)}" }, RECENTS)
    }

    private fun loadRecents(): List<RecentDoc> = (defaults.stringArrayForKey(RECENTS) ?: emptyList<Any?>()).mapNotNull { line ->
        val (opened, rel) = (line as? String)?.split('\t', limit = 2)?.takeIf { it.size == 2 } ?: return@mapNotNull null
        RecentDoc(root / rel, opened.toLongOrNull() ?: return@mapNotNull null)
    }

    fun go(path: Path) {
        pane = HomePane.FILES
        query = null
        colorFilter = null
        selected = emptySet()
        folders = generateSequence(path) { it.parent }.takeWhile { it != root && it.isWithin(root) }.toList().asReversed()
        kindFilter = null
    }

    fun toggleRail() { rail = !rail; defaults.setBool(rail, RAIL) }

    fun setView(v: ExplorerView) {
        view = v
        defaults.setObject(v.layout.id, LAYOUT)
        defaults.setObject(v.sortKey.id, SORT)
        defaults.setBool(v.descending, DESCENDING)
        defaults.setObject(v.groupBy.id, GROUP)
    }

    fun say(message: String, action: String? = null, run: (() -> Unit)? = null) { notice = Notice(message, action, run) }

    /** Shows another place: leaves select mode, the search and the colour view. */
    fun show(next: HomePane) { pane = next; query = null; colorFilter = null; selected = emptySet(); kindFilter = null }

    private fun loadView(): ExplorerView {
        val layout = ExplorerLayout.fromId(defaults.stringForKey(LAYOUT))?.takeIf { it in HomeLayouts } ?: ExplorerLayout.GRID
        val descending = if (defaults.objectForKey(DESCENDING) == null) true else defaults.boolForKey(DESCENDING)
        return ExplorerView(layout = layout, sortKey = ExplorerSortKey.fromId(defaults.stringForKey(SORT) ?: "modified"),
            descending = descending, groupBy = GroupBy.fromId(defaults.stringForKey(GROUP)))
    }

    private companion object {
        const val RAIL = "home.rail"
        const val LAYOUT = "home.layout"
        const val SORT = "home.sortKey"
        const val DESCENDING = "home.descending"
        const val GROUP = "home.groupBy"
        const val RECENTS = "home.recents"
        const val PINNED = "home.pinnedFolders"
        const val RECENT_MAX = 40
    }
}

private fun Path.isWithin(root: Path) = toString().startsWith(root.toString() + "/")

/** What an entry shows as; a note reads as a PDF note once its preview shows it marks up a PDF. */
internal fun kindOf(e: IosDocumentEntry, preview: DocPreview?): EntryKind = when (e.kind) {
    null -> EntryKind.FOLDER
    DocumentKind.CANVAS -> EntryKind.CANVAS
    DocumentKind.MIND_MAP -> EntryKind.MIND_MAP
    DocumentKind.SPLIT -> EntryKind.SPLIT
    DocumentKind.PDF -> EntryKind.PDF
    DocumentKind.NOTE -> if (preview?.pdf == true) EntryKind.PDF else EntryKind.NOTE
}

/** A document's name without its extension, as Android shows it. */
internal fun shownName(e: IosDocumentEntry): String = if (e.isFolder) e.name else DocumentKind.stripSuffix(e.name)

/** The name of what is at [path], without a document's extension. */
internal fun shownName(path: Path): String = if (DocumentKind.ofName(path.name) == null) path.name else DocumentKind.stripSuffix(path.name)
