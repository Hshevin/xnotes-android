package com.xnotes.map.ui

import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.MeshPart
import com.xnotes.core.model.CanvasItem
import com.xnotes.map.EditorFixture
import com.xnotes.map.NodeId

/** Records what the projection sends the scene. */
class RecordingSink : MapSceneSink {
    val live = LinkedHashMap<CanvasItem, List<MeshPart>>()
    val upserts = ArrayList<CanvasItem>()
    val removes = ArrayList<CanvasItem>()

    override fun upsert(item: CanvasItem, parts: List<MeshPart>, bounds: Rect) {
        upserts += item
        live[item] = parts
    }

    override fun remove(item: CanvasItem) {
        removes += item
        live.remove(item)
    }

    fun clearLog() { upserts.clear(); removes.clear() }
}

class FakeHost : MapController.Host {
    var editable: (String?) -> Boolean = { true }
    var active: String? = null
    var changes = 0
    val sourceTaps = ArrayList<Pair<NodeId, String?>>()
    override fun sourceTapped(node: NodeId, sourceId: String?) { sourceTaps += node to sourceId }
    override fun layerVisible(layer: String?) = true
    override fun layerEditable(layer: String?) = editable(layer)
    override fun activeLayer() = active
    override fun mapChanged() { changes++ }
}

/** A map editor, projection and controller over an empty canvas, at zoom 1 with 48 px touch targets. */
class ControllerFixture {
    val measurer = FakeTextMeasurer()
    val titles = MapTitles(measurer)
    val f = EditorFixture(titles)
    val sink = RecordingSink()
    val host = FakeHost()
    val projection = MapProjection(titles, measurer, f.metrics)
    val c = MapController(f.editor, projection, sink, host)
    val editor get() = f.editor
    val state get() = f.state

    fun rect(id: NodeId): Rect = checkNotNull(c.shown.rectOf(id)) { "$id not shown" }
    fun center(id: NodeId): Pt = rect(id).center

    /** A press, the moves and the lift, with a finger under the pan tool unless told otherwise. */
    fun gesture(
        vararg points: Pt,
        finger: Boolean = true,
        tool: com.xnotes.core.tools.Tool = com.xnotes.core.tools.Tool.PAN,
        connector: Boolean = false,
        fingerDraws: Boolean = false,
    ): Boolean {
        val taken = c.press(points.first(), ZOOM, TOUCH, finger, eraser = false, tool = tool, connector = connector, fingerDraws = fingerDraws)
        if (!taken) return false
        for (p in points.drop(1)) c.move(p, ZOOM)
        c.release(points.last())
        return true
    }

    fun tap(at: Pt, finger: Boolean = true, tool: com.xnotes.core.tools.Tool = com.xnotes.core.tools.Tool.PAN) = gesture(at, finger = finger, tool = tool)

    companion object {
        const val ZOOM = 1.0
        const val TOUCH = 48.0
    }
}
