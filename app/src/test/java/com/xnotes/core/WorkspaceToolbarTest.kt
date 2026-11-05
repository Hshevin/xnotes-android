package com.xnotes.core

import com.xnotes.core.tools.*
import org.junit.Assert.*
import org.junit.Test

class WorkspaceToolbarTest {
    private fun items(layout: ToolbarLayout) = layout.visibleSections.flatMap { it.visibleEntries }.map { it.item }

    @Test fun pdfMarkupMovesToCommonBarOnlyForAllPdfWorkspace() {
        val layout = workspaceToolbarLayout(ToolbarLayout.DEFAULT, ToolbarLayout.CANVAS_DEFAULT)
        val pdfs = setOf(ToolbarDocument.PDF)
        assertEquals(1, items(layout.forDocumentControls(false, pdfs)).count { it == ToolbarItem.MARKUP })
        assertFalse(ToolbarItem.MARKUP in items(documentToolbarLayout(layout, false, true, pdfs)))
        for (other in listOf(ToolbarDocument.NOTE, ToolbarDocument.CANVAS)) {
            val mixed = setOf(ToolbarDocument.PDF, other)
            assertFalse(ToolbarItem.MARKUP in items(layout.forDocumentControls(false, mixed)))
            assertTrue(ToolbarItem.MARKUP in items(documentToolbarLayout(layout, false, true, mixed)))
            assertFalse(ToolbarItem.MARKUP in items(documentToolbarLayout(layout, other == ToolbarDocument.CANVAS, false, mixed)))
        }
    }

    @Test fun eachCombinationKeepsPerDocumentSettingsLocalAndCommonToolsStable() {
        val layout = workspaceToolbarLayout(ToolbarLayout.DEFAULT, ToolbarLayout.CANVAS_DEFAULT)
        for (left in ToolbarDocument.entries) for (right in ToolbarDocument.entries) {
            val documents = setOf(left, right)
            val common = items(layout.forDocumentControls(false, documents))
            assertEquals(common, items(layout.forDocumentControls(false, setOf(right, left))))
            for (kind in documents) {
                val local = items(documentToolbarLayout(layout, kind == ToolbarDocument.CANVAS, kind == ToolbarDocument.PDF, documents))
                assertTrue(local.containsAll(listOf(ToolbarItem.ZOOM, ToolbarItem.FIT, ToolbarItem.ZOOM_LOCK, ToolbarItem.STYLES)))
                assertTrue(common.intersect(local.toSet()).isEmpty())
                assertEquals(local.size, local.distinct().size)
                assertEquals(kind != ToolbarDocument.CANVAS, ToolbarItem.PAGE_NAV in local)
                assertEquals(kind == ToolbarDocument.CANVAS, ToolbarItem.WAYPOINTS in local)
            }
            assertTrue(common.containsAll(listOf(ToolbarItem.TEXT_BOX, ToolbarItem.RULER, ToolbarItem.SCREENSHOT)))
        }
    }

    @Test fun workspaceKeepsOneOrderAndPartitionsDocumentControls() {
        val layout = workspaceToolbarLayout(ToolbarLayout.DEFAULT, ToolbarLayout.CANVAS_DEFAULT)
        val all = layout.visibleSections.flatMap { it.visibleEntries }.map { it.item }
        assertEquals(all.size, all.distinct().size)
        val common = layout.forDocumentControls(false).visibleSections.flatMap { it.visibleEntries }.map { it.item }
        val document = layout.forDocumentControls(true).visibleSections.flatMap { it.visibleEntries }.map { it.item }
        assertTrue(common.containsAll(listOf(ToolbarItem.TEXT_BOX, ToolbarItem.RULER, ToolbarItem.SCREENSHOT)))
        assertTrue(document.containsAll(listOf(ToolbarItem.ZOOM, ToolbarItem.FIT, ToolbarItem.ZOOM_LOCK)))
        assertTrue(ToolbarItem.PEN in common)
        assertTrue(ToolbarItem.ERASER in common)
        assertTrue(ToolbarItem.MARKUP in document)
        assertTrue(ToolbarItem.MARKUP !in common)
        assertTrue(common.intersect(document.toSet()).isEmpty())
        assertEquals(all.toSet(), (common + document).toSet())
    }
    @Test fun documentBarsOnlyContainApplicableControls() {
        val layout = workspaceToolbarLayout(ToolbarLayout.DEFAULT, ToolbarLayout.CANVAS_DEFAULT)
        fun items(canvas: Boolean, pdf: Boolean) = documentToolbarLayout(layout, canvas, pdf).visibleSections.flatMap { it.visibleEntries }.map { it.item }
        assertTrue(ToolbarItem.MARKUP in items(false, true))
        assertFalse(ToolbarItem.MARKUP in items(false, false))
        assertFalse(ToolbarItem.PAGE_NAV in items(true, false))
        assertTrue(ToolbarItem.MINIMAP in items(true, false))
        assertTrue(items(true, false).containsAll(listOf(ToolbarItem.ZOOM, ToolbarItem.FIT, ToolbarItem.ZOOM_LOCK)))
        assertFalse(ToolbarItem.MINIMAP in items(false, true))
    }

    @Test fun insertSpaceIsCommonForNotesAndPdfs() {
        val layout = workspaceToolbarLayout(ToolbarLayout.DEFAULT, ToolbarLayout.CANVAS_DEFAULT)
        fun items(layout: ToolbarLayout) = layout.visibleSections.flatMap { it.visibleEntries }.map { it.item }
        assertEquals(1, items(layout.forDocumentControls(false, setOf(ToolbarDocument.NOTE, ToolbarDocument.PDF))).count { it == ToolbarItem.INSERT_SPACE })
        for (pdf in listOf(false, true)) {
            assertFalse(ToolbarItem.INSERT_SPACE in items(documentToolbarLayout(layout, false, pdf, setOf(ToolbarDocument.NOTE, ToolbarDocument.PDF))))
        }
    }

    @Test fun insertSpaceMovesToPagedBarWhenCanvasIsOpen() {
        val layout = workspaceToolbarLayout(ToolbarLayout.DEFAULT, ToolbarLayout.CANVAS_DEFAULT)
        fun items(layout: ToolbarLayout) = layout.visibleSections.flatMap { it.visibleEntries }.map { it.item }
        assertFalse(ToolbarItem.INSERT_SPACE in items(layout.forDocumentControls(false, setOf(ToolbarDocument.NOTE, ToolbarDocument.CANVAS))))
        for (pdf in listOf(false, true)) {
            assertEquals(1, items(documentToolbarLayout(layout, false, pdf, setOf(ToolbarDocument.NOTE, ToolbarDocument.CANVAS))).count { it == ToolbarItem.INSERT_SPACE })
        }
        assertFalse(ToolbarItem.INSERT_SPACE in items(documentToolbarLayout(layout, true, false, setOf(ToolbarDocument.NOTE, ToolbarDocument.CANVAS))))
        assertTrue(ToolbarItem.INSERT_SPACE in items(layout.forDocumentControls(false, setOf(ToolbarDocument.NOTE, ToolbarDocument.PDF))))
    }


    @Test fun layersAppearOncePerDocumentInEveryWorkspaceCombination() {
        val layout = workspaceToolbarLayout(ToolbarLayout.DEFAULT, ToolbarLayout.CANVAS_DEFAULT)
        for (first in ToolbarDocument.entries) for (second in ToolbarDocument.entries) {
            val workspace = setOf(first, second)
            assertFalse(ToolbarItem.LAYERS in items(layout.forDocumentControls(false, workspace)))
            for (doc in workspace) {
                val bar = documentToolbarLayout(layout, doc == ToolbarDocument.CANVAS, doc == ToolbarDocument.PDF, workspace)
                assertEquals(1, items(bar).count { it == ToolbarItem.LAYERS })
            }
        }
    }
}
