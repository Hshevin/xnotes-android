package com.xnotes.iosapp

import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.MeshPart
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.DrawingLayers
import com.xnotes.map.*
import com.xnotes.map.ui.*
import com.xnotes.platform.ios.CgTextMeasurer

internal class PanelMapScene(override val wantsMeshes: Boolean, private val invalidate: (CanvasItem) -> Unit = {}) : MapSceneSink {
    private val titles = MapTitles(CgTextMeasurer)
    private val metrics = NodeMetrics(titles)
    private val map = MapProjection(titles, CgTextMeasurer, metrics)
    private var previous: MapState? = null
    private var folds = emptySet<NodeId>()
    private var ids = emptyList<String>()
    private var dark = false
    val meshes = mutableMapOf<CanvasItem, List<MeshPart>>()
    override fun upsert(item: CanvasItem, parts: List<MeshPart>, bounds: Rect) { if (wantsMeshes) meshes[item] = parts; invalidate(item) }
    override fun remove(item: CanvasItem) { meshes.remove(item); invalidate(item) }
    fun update(state: MapState, collapsed: Set<NodeId>, layers: List<String>, night: Boolean): List<MapPart> {
        if (previous !== state || folds != collapsed || ids != layers || dark != night) {
            map.update(state, MapLayout.compute(state, collapsed, metrics), this, night = night,
                layerFor = { (it ?: DrawingLayers.DEFAULT).takeIf(layers::contains) ?: layers.first() })
            previous = state; folds = collapsed.toSet(); ids = layers; dark = night
        }
        return map.items
    }
}
