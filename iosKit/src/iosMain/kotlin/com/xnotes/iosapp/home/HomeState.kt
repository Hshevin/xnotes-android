package com.xnotes.iosapp.home

import androidx.compose.runtime.*
import com.xnotes.core.util.DocumentKind
import com.xnotes.explorer.EntryKind
import com.xnotes.explorer.ExplorerLayout
import com.xnotes.explorer.ExplorerSortKey
import com.xnotes.explorer.ExplorerView
import com.xnotes.explorer.GroupBy
import com.xnotes.storage.ios.IosDocumentEntry
import com.xnotes.storage.ios.IosStorageLocations
import okio.Path
import platform.Foundation.NSUserDefaults

/** Where the home screen is: a folder's files, or the recovery list. */
internal enum class HomePane { FILES, RECOVERY }

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
    /** A short message at the bottom of the screen. */
    var notice by mutableStateOf<String?>(null)

    val current: Path get() = folders.lastOrNull() ?: root

    fun go(path: Path) {
        pane = HomePane.FILES
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

    fun say(message: String) { notice = message }

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
