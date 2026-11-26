package com.xnotes.core

import com.xnotes.core.tools.*
import org.junit.Assert.*
import org.junit.Test

/** The canvas's mind map tool: a canvas-only edit tool. */
class MapToolbarTest {
    private fun items(layout: ToolbarLayout) = layout.visibleSections.flatMap { it.visibleEntries }.map { it.item }

    @Test fun theCanvasBarHasTheMapToolAndThePagedBarNever() {
        assertTrue(ToolbarItem.MAP in items(ToolbarLayout.CANVAS_DEFAULT))
        assertTrue(ToolbarItem.MAP in ToolbarLayout.CANVAS_ITEMS)
        assertFalse(ToolbarItem.MAP in items(ToolbarLayout.DEFAULT))
        assertFalse(ToolbarItem.MAP in ToolbarLayout.NOTE_ITEMS)
        assertEquals(Tool.MAP, Tool.fromId(ToolbarItem.MAP.id))
    }

    @Test fun aStoredCanvasBarGainsTheMapToolBesideShapes() {
        val stored = listOf(listOf("pen" to true, "shape" to true, "text" to true))
        val layout = ToolbarLayout.fromRaw(stored, ToolbarLayout.CANVAS_ITEMS, ToolbarLayout.CANVAS_DEFAULT)
        val order = items(layout)
        assertEquals(order.indexOf(ToolbarItem.SHAPE) + 1, order.indexOf(ToolbarItem.MAP))
    }

    @Test fun aStoredPagedBarDropsTheMapTool() {
        val stored = listOf(listOf("pen" to true, "map" to true))
        assertFalse(ToolbarItem.MAP in items(ToolbarLayout.fromRaw(stored)))
    }

    @Test fun theMapToolSitsWithTheCanvasTools() {
        val layout = workspaceToolbarLayout(ToolbarLayout.DEFAULT, ToolbarLayout.CANVAS_DEFAULT)
        val canvasOnly = setOf(ToolbarDocument.CANVAS)
        assertTrue("a lone canvas keeps it with its other tools", ToolbarItem.MAP in items(layout.forDocumentControls(false, canvasOnly)))
        for (other in listOf(ToolbarDocument.NOTE, ToolbarDocument.PDF, ToolbarDocument.MAP)) {
            val documents = setOf(ToolbarDocument.CANVAS, other)
            assertFalse(ToolbarItem.MAP in items(layout.forDocumentControls(false, documents)))
            assertTrue(ToolbarItem.MAP in items(documentToolbarLayout(layout, ToolbarDocument.CANVAS, documents)))
            assertFalse(ToolbarItem.MAP in items(documentToolbarLayout(layout, other, documents)))
        }
    }
}
