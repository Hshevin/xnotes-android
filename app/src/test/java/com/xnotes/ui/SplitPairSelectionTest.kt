package com.xnotes.ui

import org.junit.Assert.*
import org.junit.Test

class SplitPairSelectionTest {
    private val map=BrowseEntry("graph.xmap","content://library/graph",false)
    private val note=BrowseEntry("note.xnote","content://library/note",false)

    @Test fun oneMapAndOneNoteAreSelectableInBothOrders() {
        assertEquals(listOf(map.documentUri,note.documentUri),splitPairSelection(listOf(map,note)))
        assertEquals(listOf(note.documentUri,map.documentUri),splitPairSelection(listOf(note,map)))
    }

    @Test fun twoNotesKeepTheirExistingSelectionOrder() {
        val other=note.copy(name="second.xnote",documentUri="content://library/second")
        assertEquals(listOf(other.documentUri,note.documentUri),splitPairSelection(listOf(other,note)))
    }

    @Test fun directoriesDuplicateUrisAndUnrecognisedFilesHaveNoTwoFileAction() {
        assertNull(splitPairSelection(listOf(note,map.copy(isDir=true))))
        assertNull(splitPairSelection(listOf(note,note.copy(name="alias.xnote"))))
        assertNull(splitPairSelection(listOf(note,map.copy(name="raw.json"))))
    }

    @Test fun twoMapsCanvasAndSavedSplitAreNotSupportedPairs() {
        assertNull(splitPairSelection(listOf(map,map.copy(documentUri="content://library/other"))))
        assertNull(splitPairSelection(listOf(note,map.copy(name="sketch.xcanvas"))))
        assertNull(splitPairSelection(listOf(map,note.copy(name="sketch.xcanvas"))))
        assertNull(splitPairSelection(listOf(note,map.copy(name="saved.xsplit"))))
    }

    @Test fun aPaneHomeCanAllowWholeWorkspaceNativePairsWithoutExposingMapPairs() {
        val other=note.copy(name="second.xnote",documentUri="content://library/second")
        assertEquals(listOf(note.documentUri,other.documentUri),
            splitPairSelection(listOf(note,other),allowNativePairs=true,allowMapPairs=false))
        assertNull(splitPairSelection(listOf(note,map),allowNativePairs=true,allowMapPairs=false))
        assertNull(splitPairSelection(listOf(note,other),allowNativePairs=false,allowMapPairs=true))
        assertEquals(listOf(map.documentUri,note.documentUri),
            splitPairSelection(listOf(map,note),allowNativePairs=false,allowMapPairs=true))
    }
}
