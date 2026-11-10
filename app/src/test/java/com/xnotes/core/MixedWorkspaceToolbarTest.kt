package com.xnotes.core

import com.xnotes.core.tools.*
import org.junit.Assert.*
import org.junit.Test

class MixedWorkspaceToolbarTest {
    private val layout = workspaceToolbarLayout(ToolbarLayout.DEFAULT, ToolbarLayout.CANVAS_DEFAULT)
    private fun items(layout: ToolbarLayout) = layout.visibleSections.flatMap { it.visibleEntries }.map { it.item }

    @Test fun mapKeepsEveryNativeEditingToolInItsOwnDocumentBar() {
        for (native in listOf(ToolbarDocument.NOTE, ToolbarDocument.PDF)) {
            val documents = setOf(native, ToolbarDocument.MAP)
            assertTrue(items(layout.forDocumentControls(false, documents)).isEmpty())
            val local = items(documentToolbarLayout(layout, false, native == ToolbarDocument.PDF, documents))
            assertTrue(local.containsAll(listOf(ToolbarItem.PEN, ToolbarItem.ERASER,
                ToolbarItem.UNDO, ToolbarItem.REDO, ToolbarItem.INSERT_SPACE)))
            assertEquals(native == ToolbarDocument.PDF, ToolbarItem.MARKUP in local)
            assertEquals(local.size, local.distinct().size)
        }
    }

    @Test fun addingMapDoesNotPromoteNativeViewSettingsToWorkspace() {
        val documents = setOf(ToolbarDocument.NOTE, ToolbarDocument.MAP)
        val local = items(documentToolbarLayout(layout, false, false, documents))
        assertTrue(local.containsAll(listOf(ToolbarItem.PAGE_NAV, ToolbarItem.ZOOM,
            ToolbarItem.ZOOM_LOCK, ToolbarItem.STYLES, ToolbarItem.VIEW)))
        assertTrue(items(layout.forDocumentControls(false, documents)).intersect(local.toSet()).isEmpty())
        assertTrue(items(documentToolbarLayout(layout, ToolbarDocument.MAP, documents)).isEmpty())
    }
}
