package com.xnotes.iosapp

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.history.Command
import com.xnotes.core.history.CompositeCommand
import com.xnotes.core.history.TransformItems
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.infinite.OnCanvas
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.GlueFollow
import com.xnotes.edit.SelectionEdit

internal fun List<Command>.oneEdit(): Command? = when (size) {
    0 -> null
    1 -> first()
    else -> CompositeCommand(this)
}

internal class SelectionTransform(
    members: List<CanvasItem>, editable: List<CanvasItem>, present: List<CanvasItem>, private val origin: Pt, private val canvas: InfiniteDocument?,
) {
    private val edit = SelectionEdit(members)
    private val glue = GlueFollow.of(edit.items, editable, present.toHashSet()::contains)
    private val affected = (edit.items + glue.items).distinct()
    private var changed = false

    fun preview(transform: Affine) {
        val local = transform.translatedFrame(origin)
        changed = !(local.isAxisAligned && local.a == 1.0 && local.d == 1.0 && local.e == 0.0 && local.f == 0.0)
        edit.preview(local)
        if (changed) glue.follow(local) else glue.restore()
        canvas?.itemsChanged(affected)
    }

    fun cancel() {
        edit.cancel(); glue.restore(); canvas?.itemsChanged(affected)
    }

    fun commit(): Command? {
        if (changed) glue.settle()
        val moved = edit.commit()
        val after = glue.items.map(CanvasItem::snapshotGeometry)
        val joined = if (after == glue.startSnapshots) null else TransformItems(glue.items, glue.startSnapshots, after)
        val command = listOfNotNull(moved, joined).oneEdit() ?: return null
        return if (canvas == null) command else OnCanvas(canvas, command, affected)
    }
}
