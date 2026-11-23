package com.xnotes.edit

import com.xnotes.core.geometry.Affine
import com.xnotes.core.history.Command
import com.xnotes.core.history.TransformItems
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.sameSelectionGeometry

class SelectionEdit(items: List<CanvasItem>) {
    val items = items.filterNot { it.locked }.distinct()
    private val before = this.items.map(CanvasItem::snapshotGeometry)
    private var transformed = false
    var active = true
        private set

    fun preview(t: Affine) {
        if (!active) return
        if (transformed) restore()
        val translate = t.a == 1.0 && t.d == 1.0 && t.isAxisAligned
        transformed = !translate || t.e != 0.0 || t.f != 0.0
        if (transformed) for (item in items) {
            if (translate) item.translate(t.e, t.f) else item.applyTransform(t)
        }
    }

    fun cancel() {
        if (!active) return
        active = false
        if (transformed) restore()
    }

    fun commit(): Command? {
        if (!active) return null
        active = false
        if (!transformed) return null
        val after = items.map(CanvasItem::snapshotGeometry)
        return if (items.indices.all { before[it].sameSelectionGeometry(after[it]) }) null else TransformItems(items, before, after)
    }

    private fun restore() = items.forEachIndexed { index, item -> item.restoreGeometry(before[index]) }
}
