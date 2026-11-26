package com.xnotes.iosapp

import com.xnotes.core.util.DocumentKind
import com.xnotes.explorer.EntryKind
import com.xnotes.explorer.ExplorerLayout
import com.xnotes.explorer.ExplorerSortKey
import com.xnotes.explorer.GroupBy
import com.xnotes.iosapp.home.DocPreview
import com.xnotes.iosapp.home.HomeState
import com.xnotes.iosapp.home.Arranged
import com.xnotes.iosapp.home.arrange
import com.xnotes.iosapp.home.entryKey
import com.xnotes.iosapp.home.fmt
import com.xnotes.iosapp.home.kindOf
import com.xnotes.storage.ios.IosDocumentEntry
import okio.Path
import platform.Foundation.NSUserDefaults
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeStateTest {
    private val suite = "home-state-test"
    private val defaults = NSUserDefaults(suiteName = suite)

    @AfterTest fun clear() { defaults.removePersistentDomainForName(suite) }

    @Test fun viewOptionsAndRailSurviveARelaunch() {
        val first = HomeState(defaults)
        assertEquals(ExplorerLayout.GRID, first.view.layout)
        assertTrue(first.view.descending)
        first.setView(first.view.copy(layout = ExplorerLayout.LIST, sortKey = ExplorerSortKey.NAME, descending = false, groupBy = GroupBy.KIND))
        first.toggleRail()
        val again = HomeState(defaults)
        assertEquals(ExplorerLayout.LIST, again.view.layout)
        assertEquals(ExplorerSortKey.NAME, again.view.sortKey)
        assertFalse(again.view.descending)
        assertEquals(GroupBy.KIND, again.view.groupBy)
        assertTrue(again.rail)
    }

    @Test fun aLayoutThisIPadLacksFallsBackToTheGrid() {
        defaults.setObject("timeline", "home.layout")
        assertEquals(ExplorerLayout.GRID, HomeState(defaults).view.layout)
    }

    @Test fun goingToAFolderOpensEveryFolderOnTheWay() {
        val state = HomeState(defaults)
        val deep = state.root / "A" / "B" / "C"
        state.kindFilter = EntryKind.NOTE
        state.go(deep)
        assertEquals(listOf(state.root / "A", state.root / "A" / "B", deep), state.folders)
        assertEquals(deep, state.current)
        assertNull(state.kindFilter)
        state.go(state.root)
        assertEquals(emptyList<Path>(), state.folders)
    }

    @Test fun aNoteMarkingUpAPdfFiltersAsAPdf() {
        val note = entry("Lecture.xnote", DocumentKind.NOTE, 1)
        assertEquals(EntryKind.NOTE, kindOf(note, null))
        assertEquals(EntryKind.PDF, kindOf(note, DocPreview(null, 3, true)))
        assertEquals(EntryKind.FOLDER, kindOf(entry("Folder", null, 1), null))
    }

    @Test fun arrangingKeepsFoldersApartAndFiltersByKind() {
        val state = HomeState(defaults)
        val entries = listOf(entry("Folder", null, 3), entry("Old.xnote", DocumentKind.NOTE, 1), entry("New.xcanvas", DocumentKind.CANVAS, 2))
        val all = arrange(entries, state.view.copy(groupBy = GroupBy.NONE), null, { kindOf(it, null) }, 10)
        assertEquals(listOf("Folder"), all.folders.map { it.name })
        assertEquals(listOf("New.xcanvas", "Old.xnote"), all.groups.single().items.map { it.name })
        val canvases = arrange(entries, state.view, EntryKind.CANVAS, { kindOf(it, null) }, 10)
        assertTrue(canvases.folders.isEmpty())
        assertEquals(1, canvases.files)
    }

    @Test fun aPinnedNoteIsOnTheShelfAndInItsGroupWithoutClashingOrCountingTwice() {
        val state = HomeState(defaults)
        val entries = listOf(entry("Pinned.xnote", DocumentKind.NOTE, 1), entry("Other.xcanvas", DocumentKind.CANVAS, 2))
        val plain = arrange(entries, state.view, null, { kindOf(it, null) }, 10)
        val shelved = Arranged(plain.folders, plain.groups, entries.take(1))
        val keys = shelved.shown("Pinned").flatMap { g -> g.items.map { entryKey(g, it) } }
        assertEquals(3, keys.size)
        assertEquals(keys.size, keys.toSet().size, "every drawn item needs its own key: $keys")
        assertEquals(plain.files, shelved.files)
        assertEquals(plain.all, shelved.all)
    }

    @Test fun androidPlaceholdersAreFilledInOrder() {
        // Keys missing from the test bundle come back as themselves, so the key is the pattern.
        assertEquals("a-05", fmt("%1\$s-%2\$02d", "a", 5))
        assertEquals("3 of 7", fmt("%d of %d", 3, 7))
    }

    @Test fun recentKeepsTheNewestFirstOnceEachAndFollowsMoves() {
        val state = HomeState(defaults)
        val a = state.root / "A.xnote"
        val b = state.root / "Folder" / "B.xnote"
        state.opened(a, 1); state.opened(b, 2); state.opened(a, 3)
        assertEquals(listOf(a to 3L, b to 2L), state.recents.map { it.path to it.opened })
        state.moved(state.root / "Folder", state.root / "Moved")
        assertEquals(state.root / "Moved" / "B.xnote", state.recents[1].path)
        state.moved(a, null)
        val again = HomeState(defaults)
        assertEquals(listOf(state.root / "Moved" / "B.xnote"), again.recents.map { it.path })
        repeat(45) { state.opened(state.root / "N$it.xnote", 10L + it) }
        assertEquals(40, state.recents.size)
    }

    private fun entry(name: String, kind: DocumentKind?, modified: Long) =
        IosDocumentEntry(HomeState(defaults).root / name, kind, modified)
}
