package com.xnotes.core.model

import com.xnotes.core.geometry.Rect

enum class LayerPreviewMode { NONE, CONTENT, PAGE }

/** Hidden layers keep their preview. Display mode never changes document contents or history. */
class LayerPreview(items: List<CanvasItem>, private val pageFrame: Rect? = null) {
    private val byLayer = items.groupBy { it.layerId ?: DrawingLayers.DEFAULT }
    val modes = if (pageFrame != null) LayerPreviewMode.entries.toList()
        else listOf(LayerPreviewMode.NONE, LayerPreviewMode.CONTENT)
    // Lazy so disabling thumbnails performs no bounds work or rasterization.
    val frame: Rect by lazy { normalized(pageFrame ?: bounds(byLayer.values.flatten())) }

    fun itemsFor(id: String): List<CanvasItem> = byLayer[id].orEmpty()

    fun frameFor(id: String, mode: LayerPreviewMode): Rect? = when (mode) {
        LayerPreviewMode.NONE -> null
        LayerPreviewMode.CONTENT -> bounds(itemsFor(id))
        LayerPreviewMode.PAGE -> if (pageFrame != null) frame else null
    }

    private fun bounds(items: List<CanvasItem>): Rect = normalized(items.map { it.paintBounds() }
        .filter { it.valid() }.reduceOrNull(Rect::union))

    private fun normalized(rect: Rect?): Rect = rect?.takeIf { it.valid() }
        ?.let { it.copy(w = it.w.coerceAtLeast(1.0), h = it.h.coerceAtLeast(1.0)) }
        ?: Rect(0.0, 0.0, 1.0, 1.0)

    private fun Rect.valid() = x.isFinite() && y.isFinite() && w.isFinite() && h.isFinite() &&
        right.isFinite() && bottom.isFinite() && w >= 0 && h >= 0
}
