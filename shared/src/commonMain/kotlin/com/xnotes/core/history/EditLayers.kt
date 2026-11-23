package com.xnotes.core.history

import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.DrawingLayers

/** Complete structural snapshots: deletion and cross-layer moves restore membership and z order. */
class EditLayers private constructor(private val before: () -> Unit, private val after: () -> Unit) : Command {
    override fun undo() = before()
    override fun redo() = after()

    companion object {
        fun capture(layers: DrawingLayers, items: MutableList<CanvasItem>, change: () -> Unit): EditLayers =
            capture(layers, { items.toList() }, { next -> items.clear(); items.addAll(next) }, change)

        fun capture(layers: DrawingLayers, items: () -> List<CanvasItem>, replace: (List<CanvasItem>) -> Unit, change: () -> Unit): EditLayers {
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
            return EditLayers(before, snapshot())
        }
    }
}
