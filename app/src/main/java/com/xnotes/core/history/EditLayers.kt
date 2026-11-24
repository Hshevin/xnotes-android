package com.xnotes.core.history

import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.DrawingLayers
import com.xnotes.core.model.Page

/**
 * Complete structural snapshots: deletion and cross-layer moves restore membership and z order.
 * [page] is the page whose layers changed, so a paged view rebuilds just that page; null on the infinite canvas.
 */
class EditLayers private constructor(
    val page: Page?,
    /** Only the layer list changed (visibility, lock, name, order, current layer, a new empty layer): no object moved or went. */
    val stackOnly: Boolean,
    private val before: () -> Unit,
    private val after: () -> Unit,
) : Command {
    override fun undo() = before()
    override fun redo() = after()

    companion object {
        /** A [change] to the layer list alone: undo restores the list and touches no object. */
        fun captureStack(layers: DrawingLayers, page: Page?, change: () -> Unit): EditLayers {
            fun snapshot(): () -> Unit {
                val entries = layers.entries
                val active = layers.activeId
                return { layers.restore(entries, active) }
            }
            val before = snapshot()
            val ids = layers.entries.map { it.id }.toSet()
            try {
                change()
                // Deleting a layer leaves its objects pointing at nothing: that is a content edit.
                require(ids.all { id -> layers.entries.any { it.id == id } }) { "a layer stack edit may not delete a layer" }
            } catch (e: Exception) { before(); throw e }
            return EditLayers(page, true, before, snapshot())
        }

        fun capture(page: Page, change: () -> Unit): EditLayers =
            capture(page, page.layers, { page.items.toList() }, { next -> page.items.clear(); page.items.addAll(next) }, change)

        fun capture(layers: DrawingLayers, items: () -> List<CanvasItem>, replace: (List<CanvasItem>) -> Unit, change: () -> Unit): EditLayers =
            capture(null, layers, items, replace, change)

        private fun capture(page: Page?, layers: DrawingLayers, items: () -> List<CanvasItem>, replace: (List<CanvasItem>) -> Unit, change: () -> Unit): EditLayers {
            fun snapshot(): () -> Unit {
                val entries = layers.entries.toList()
                val active = layers.activeId
                val content = items().toList()
                val ids = content.map { it.layerId }
                return {
                    layers.restore(entries, active)
                    content.forEachIndexed { i, item -> item.layerId = ids[i] }
                    replace(content)
                }
            }
            val before = snapshot()
            try { change() } catch (e: Exception) { before(); throw e }
            return EditLayers(page, false, before, snapshot())
        }
    }
}
