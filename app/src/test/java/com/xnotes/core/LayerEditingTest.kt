package com.xnotes.core

import com.xnotes.canvas.SelectionMath
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.EraseSession
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Page
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.*
import org.junit.Test

class LayerEditingTest {
    private fun stroke() = Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN),
        listOf(Sample(10.0, 10.0, 1.0), Sample(50.0, 10.0, 1.0)))

    @Test fun lassoCannotSelectHiddenLockedOrOtherLayers() {
        val page = Page(100.0, 100.0)
        page.items.add(stroke())
        page.layers.create("Active")
        val active = stroke().also { page.items.add(it) }
        val ring = listOf(Pt(0.0, 0.0), Pt(90.0, 0.0), Pt(90.0, 90.0), Pt(0.0, 90.0))
        fun selected() = SelectionMath.lassoMembers(listOf(page), listOf(Rect(0.0, 0.0, 100.0, 100.0)), ring)
        assertEquals(listOf(active), selected().map { it.item })
        page.layers.update(page.layers.activeId) { it.copy(locked = true) }
        assertTrue(selected().isEmpty())
        page.layers.update(page.layers.activeId) { it.copy(locked = false, visible = false) }
        assertTrue(selected().isEmpty())
    }

    @Test fun canvasEraserPreservesOtherLayerAndUndoRestoresActiveStroke() {
        val doc = InfiniteDocument()
        val base = stroke().also(doc::add)
        doc.layers.create("Active")
        val active = stroke().also(doc::add)
        val erase = EraseSession(doc)
        erase.erase(10.0, 10.0, 5.0, area = false)
        assertEquals(listOf(base), doc.items)
        erase.buildCommand()!!.undo()
        assertEquals(listOf(base, active), doc.items)
        assertEquals(doc.layers.activeId, active.layerId)
    }
}
