package com.xnotes.core

import com.xnotes.core.model.*
import com.xnotes.core.history.History
import com.xnotes.core.history.EditLayers
import com.xnotes.core.geometry.Rect
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class DrawingLayersTest {
    private fun item() = ImageItem(ImageData(File("unused"), 10, 10), Rect(0.0, 0.0, 10.0, 10.0))

    @Test fun legacyItemsBelongToDefaultLayer() {
        val ink = item()
        val page = Page(100.0, 100.0, mutableListOf(ink))
        assertEquals(DrawingLayers.DEFAULT, ink.layerId)
        assertTrue(page.layers.editable(ink))
    }

    @Test fun hiddenAndLockedLayersAreNotEditable() {
        val page = Page(100.0, 100.0)
        val ink = item()
        page.items.add(ink)
        page.layers.update(DrawingLayers.DEFAULT) { it.copy(visible = false) }
        assertFalse(page.layers.visible(ink))
        assertFalse(page.layers.editable(ink))
        page.layers.update(DrawingLayers.DEFAULT) { it.copy(visible = true, locked = true) }
        assertFalse(page.layers.editable(ink))
    }

    @Test fun deleteUndoRestoresContentOrderAndActiveLayer() {
        val page = Page(100.0, 100.0)
        val first = item().also { page.items.add(it) }
        val layer = page.layers.create("Draft")
        val second = item().also { page.items.add(it) }
        val history = History()
        val command = EditLayers.capture(page.layers, page.items) {
            page.items.removeAll { it.layerId == layer.id }
            page.layers.delete(layer.id)
        }
        history.push(command)
        assertEquals(listOf(first), page.items)
        history.undo()
        assertEquals(listOf(first, second), page.items)
        assertEquals(layer.id, page.layers.activeId)
        history.redo()
        assertEquals(listOf(first), page.items)
    }

    @Test fun newItemsUseCurrentLayerAndExistingItemsKeepTheirLayer() {
        val page = Page(100.0, 100.0)
        val old = item().also { page.items.add(it) }
        val layer = page.layers.create("New")
        val fresh = item().also { page.items.add(it) }
        assertEquals(layer.id, fresh.layerId)
        assertEquals(DrawingLayers.DEFAULT, old.layerId)
        assertFalse(page.layers.editable(old))
        assertTrue(page.layers.editable(fresh))
    }

    @Test fun moveBetweenLayersUndoRestoresMembershipAndOrder() {
        val page = Page(100.0, 100.0)
        val first = item().also { page.items.add(it) }
        val second = item().also { page.items.add(it) }
        val target = page.layers.create("Target")
        val command = EditLayers.capture(page.layers, page.items) { first.layerId = target.id }
        assertEquals(listOf(second, first), page.visibleItems)
        command.undo()
        assertEquals(listOf(first, second), page.visibleItems)
        command.redo()
        assertEquals(listOf(second, first), page.visibleItems)
    }

    @Test fun cannotDeleteLastLayerOrRestoreDuplicateIds() {
        val layers = DrawingLayers()
        assertThrows(IllegalArgumentException::class.java) { layers.delete(DrawingLayers.DEFAULT) }
        assertThrows(IllegalArgumentException::class.java) { layers.restore(layers.entries + layers.entries, DrawingLayers.DEFAULT) }
        assertEquals(1, layers.entries.size)
    }

    @Test fun transferAcrossPagesRestoresSourceLayerOnUndo() {
        val from = Page(100.0, 100.0)
        val sourceLayer = from.layers.create("Source")
        val ink = item().also { from.items.add(it) }
        val to = Page(100.0, 100.0)
        val targetLayer = to.layers.create("Target")
        val command = com.xnotes.core.history.TransferItems(listOf(com.xnotes.core.history.TransferItems.Transfer(from, to, ink, 0.0, 0.0)))
        command.redo()
        assertEquals(targetLayer.id, ink.layerId)
        command.undo()
        assertEquals(sourceLayer.id, ink.layerId)
        assertSame(ink, from.items.single())
    }

    @Test fun canvasDeleteUndoRestoresSpatialIndex() {
        val doc = com.xnotes.core.infinite.InfiniteDocument()
        val layer = doc.layers.create("Draft")
        val ink = item().also(doc::add)
        val command = EditLayers.capture(doc.layers, { doc.items }, doc::replaceAll) {
            doc.remove(ink)
            doc.layers.delete(layer.id)
        }
        assertTrue(doc.itemsIn(Rect(0.0, 0.0, 20.0, 20.0)).isEmpty())
        command.undo()
        assertEquals(listOf(ink), doc.itemsIn(Rect(0.0, 0.0, 20.0, 20.0)))
        assertEquals(layer.id, doc.layers.activeId)
    }

    @Test fun sidebarDropUsesTopFirstOrderAndOneUndoStep() {
        val page = Page(100.0, 100.0)
        val middle = page.layers.create("Middle")
        val top = page.layers.create("Top")
        val original = page.layers.entries
        val history = History()
        history.push(EditLayers.capture(page.layers, page.items) {
            page.layers.moveBefore(DrawingLayers.DEFAULT, top.id)
        })
        assertEquals(listOf(DrawingLayers.DEFAULT, top.id, middle.id), page.layers.entries.asReversed().map { it.id })
        assertEquals(top.id, page.layers.activeId)
        history.undo()
        assertEquals(original, page.layers.entries)
        history.redo()
        assertEquals(DrawingLayers.DEFAULT, page.layers.entries.last().id)
    }

    @Test fun sidebarDropAtBottomAndStaleTargetAreSafe() {
        val layers = DrawingLayers()
        val top = layers.create("Top")
        assertTrue(layers.moveBefore(top.id, null))
        assertEquals(top.id, layers.entries.first().id)
        val before = layers.entries
        assertFalse(layers.moveBefore(top.id, "deleted"))
        assertFalse(layers.moveBefore(top.id, top.id))
        assertEquals(before, layers.entries)
    }
}
