package com.xnotes.iosapp

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.history.*
import com.xnotes.core.model.*
import com.xnotes.core.pdf.MarkupPainter
import com.xnotes.core.tools.EraseMode
import com.xnotes.core.tools.ToolConfig
import com.xnotes.edit.PageSpace
import com.xnotes.platform.ios.paperRect

internal class PageEraser(private val document: Document) {
    private val before = mutableMapOf<Page, List<CanvasItem>>()
    private val changes = mutableListOf<Command>()

    fun erase(content: Pt, space: PageSpace, radius: Double, config: ToolConfig): Boolean {
        var changed = false
        for ((index, page) in document.pages.withIndex()) {
            val (cx, cy) = space.toPage(index, content)
            if (!page.paperRect(document).intersects(Rect(cx - radius, cy - radius, radius * 2, radius * 2))) continue
            for (item in page.editableItems) {
                if (!EraseRule.erasable(item)) continue
                if (item is ShapeItem && EraseRule.takesFill(item, cx, cy, radius)) {
                    changes += RefillShapes(listOf(RefillShapes.Entry(item, item.fillRgba, null))).also { it.redo() }
                    changed = true; continue
                }
                val pieces: List<CanvasItem> = if (config.eraseMode == EraseMode.STROKE || EraseRule.whole(item)) {
                    if (!EraseRule.takes(item, cx, cy, radius)) continue
                    emptyList()
                } else when (item) {
                    is Stroke -> item.erasedBy(cx, cy, radius)
                    is ShapeItem -> item.erasedBy(cx, cy, radius)?.let { it + listOfNotNull(item.labelAsTextBox()) }
                    is ConnectorItem -> item.erasedBy(cx, cy, radius)?.let { it + listOfNotNull(item.labelAsTextBox()) }
                    else -> null
                } ?: continue
                before.getOrPut(page) { page.items.toList() }
                pieces.forEach { it.sync = item.sync; it.group = item.group; it.layerId = item.layerId }
                val at = page.items.indexOfFirst { it === item }
                page.items.removeAt(at); page.items.addAll(at, pieces); changed = true
            }
            if (!config.eraseMarkups) continue
            val source = page.sourcePoint(Pt(cx, cy)) ?: continue
            val pt = 72.0 / document.dpi
            page.markups.filter { MarkupPainter.touches(it, source.x * pt, source.y * pt, radius * pt) }.forEach {
                changes += RemoveMarkup(page, it).also { command -> command.redo() }; changed = true
            }
        }
        return changed
    }

    fun command(): Command? = (changes + before.map { (page, items) -> ReplacePageItems(page, items, page.items.toList()) })
        .takeIf { it.isNotEmpty() }?.let(::CompositeCommand)
}
