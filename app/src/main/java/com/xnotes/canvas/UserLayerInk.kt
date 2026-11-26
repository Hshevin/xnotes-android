package com.xnotes.canvas

import com.xnotes.core.audio.InkFade
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.DrawingLayers
import com.xnotes.core.model.Page
import com.xnotes.core.model.PageLayers
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.TapeItem
import com.xnotes.core.pal.Renderer

/** Leading fills can cross the flow text when a lower layer is hidden or reordered.
 * Keep their bands separate so the remaining ink always has the same pixels. */
internal data class InkPart(val layerId: String, val leadingFills: Boolean = false)

/** UI-thread snapshot of visible order. Item geometry and lifted state are read at paint/build time. */
internal class UserLayerInk(page: Page) {
    val visible: List<CanvasItem> = page.visibleItems
    val under: Int = PageLayers.underText(visible)
    val layers = LinkedHashMap<String, Layer>()
    val underText: List<CanvasItem> = visible.subList(0, under)

    class Layer(val id: String, val start: Int, val items: List<CanvasItem>, under: Int) {
        val prefix = PageLayers.underText(items)
        val fills = InkPart(id, leadingFills = true)
        val body = InkPart(id)
        val cacheFills = prefix > 0 && start >= under
        val parts = if (cacheFills) listOf(fills, body) else listOf(body)
        val highlighters = items.indices.filter { items[it].isHighlighterInk() }

        fun itemsFor(part: InkPart): List<CanvasItem> =
            if (part.leadingFills) items.subList(0, prefix) else items.subList(prefix, items.size)
    }

    init {
        var start = 0
        for (entry in page.layers.entries) {
            if (!entry.visible) continue
            var end = start
            while (end < visible.size && (visible[end].layerId ?: DrawingLayers.DEFAULT) == entry.id) end++
            layers[entry.id] = Layer(entry.id, start, visible.subList(start, end), under)
            start = end
        }
    }
}

/** Paint in the model's coordinates, including the two translated slices of a live insert-space drag. */
internal fun drawThroughRoom(r: Renderer, room: RoomView?, seen: Rect, draw: (Rect) -> Unit) {
    if (room?.live != true) return draw(seen)
    room.drawCut(r, seen, draw)
}

internal fun RoomView.drawCut(r: Renderer, seen: Rect, draw: (Rect) -> Unit) {
    val far = 1e9
    r.withSave {
        r.clipRect(if (sideways) Rect.ltrb(-far, -far, split, far) else Rect.ltrb(-far, -far, far, split))
        draw(seen)
    }
    r.withSave {
        val dx = if (sideways) shift else 0.0
        val dy = if (sideways) 0.0 else shift
        r.translate(dx, dy)
        r.clipRect(if (sideways) Rect.ltrb(resume, -far, far, far) else Rect.ltrb(-far, resume, far, far))
        draw(seen.translate(-dx, -dy))
    }
}

/** Blit an opaque ribbon against the composed page, cutting out any covering tape later in visible order. */
internal fun CanvasState.drawHighlighter(
    r: Renderer, page: Page, items: List<CanvasItem>, index: Int, seen: Rect,
    clipOut: (List<List<Pt>>) -> Unit,
) {
    val stroke = items[index] as? Stroke ?: return
    if (!stroke.isHighlighterInk() || isLiftedItem(stroke)) return
    val bounds = stroke.bounds()
    if (!bounds.intersects(seen)) return
    val ribbon = highlighterCacheFor(stroke, page)
    val faint = if (fade?.fades(stroke) == true) InkFade.ALPHA else 1.0
    r.withSave {
        for (k in index + 1 until items.size) {
            val tape = items[k] as? TapeItem ?: continue
            if (!tape.peeled && !isLiftedItem(tape) && tape.bounds().intersects(bounds)) clipOut(tape.shape.body)
        }
        r.drawRasterBlended(ribbon.surface, ribbon.cover, stroke.renderColor.a / 255.0 * faint, stroke.blendMode)
    }
}
