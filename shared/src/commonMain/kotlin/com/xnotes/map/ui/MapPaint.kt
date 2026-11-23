package com.xnotes.map.ui

import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.infinite.MeshPart
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.DrawingLayers
import com.xnotes.core.pal.TextMeasurer
import com.xnotes.map.MapLayout
import com.xnotes.map.NodeMetrics

/**
 * A canvas's map for drawing away from its editor (file tiles, page thumbnails, previews): the
 * same parts the editor's scene shows, painted by the CPU renderer with the document's items.
 */
object MapPaint {

    private object PaintOnly : MapSceneSink {
        override val wantsMeshes: Boolean get() = false
        override fun upsert(item: CanvasItem, parts: List<MeshPart>, bounds: Rect) {}
        override fun remove(item: CanvasItem) {}
    }

    /** The map's visible parts, each on the drawing layer the editor would put it on. */
    fun parts(doc: InfiniteDocument, measurer: TextMeasurer): List<MapPart> {
        val state = doc.map.state
        if (state.nodes.isEmpty()) return emptyList()
        val titles = MapTitles(measurer)
        val metrics = NodeMetrics(titles)
        val projection = MapProjection(titles, measurer, metrics)
        val ids = doc.layers.entries.mapTo(HashSet()) { it.id }
        val fallback = doc.layers.entries.firstOrNull()?.id
        val layerFor = { layer: String? -> (layer ?: DrawingLayers.DEFAULT).let { if (it in ids) it else fallback } }
        projection.update(state, MapLayout.compute(state, doc.map.collapsed, metrics), PaintOnly, layerFor = layerFor)
        return projection.items
    }

    /** The document's items and its map, visible layers only, back to front. */
    fun ordered(doc: InfiniteDocument, measurer: TextMeasurer): List<CanvasItem> =
        doc.layers.ordered(doc.items.toList() + parts(doc, measurer))
}
