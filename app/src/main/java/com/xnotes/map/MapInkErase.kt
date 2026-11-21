package com.xnotes.map

import com.xnotes.core.geometry.Rect
import com.xnotes.core.history.Command
import com.xnotes.core.infinite.EraseSession

/**
 * One eraser drag over the canvas's mind maps (phase 2.5 contract; implemented by the backend).
 * The canvas's [EraseSession] calls [erase] for every eraser step and [command] once at pen up, and
 * pushes that command together with its own as one undo step.
 *
 * **erase(cx, cy, radius, area)**, canvas coordinates:
 * - Considers every node placed in [layout] (hidden nodes are not) whose box intersects the circle's
 *   bounding square and for which [editable] is true. Other nodes are never changed.
 * - A node's ink area sits at `metrics.frame(node, isRoot, box.topLeft).ink`; its strokes are in
 *   area-local coordinates, so the circle is tested at (cx − ink.left, cy − ink.top).
 * - A stroke the circle touches ([com.xnotes.core.model.Stroke.intersectsCircle] in local
 *   coordinates) is removed when [area] is false. When [area] is true it is replaced in place (same
 *   position in the list) by [com.xnotes.core.model.Stroke.erasedBy]'s pieces; a null result keeps
 *   the stroke unchanged. Strokes the circle does not touch stay identical (same instances).
 * - Never removes a node and never changes the ink area's width or height, even when its last
 *   stroke goes (the area stays, empty).
 * - Applies the result to [model] at once, outside history, in a single state change per call (so
 *   listeners redraw), and returns the union of the changed nodes' boxes; null, and no state change
 *   at all, when nothing was erased.
 *
 * **command()**: null when the model's state is equal to what it was when this object was made;
 * otherwise a [MapStateCommand] from that state to the current one, *not* pushed onto any history.
 * Undoing it restores every erased stroke; redoing erases them again.
 *
 * The caller guarantees no node draft is open in the map's editor during the drag.
 */
class MapInkErase(
    private val model: MapModel,
    private val metrics: NodeMetrics,
    private val layout: () -> MapLayoutResult,
    private val editable: (NodeId) -> Boolean,
) : EraseSession.Also {

    override fun erase(cx: Double, cy: Double, radius: Double, area: Boolean): Rect? =
        TODO("mind map v2 phase 2.5: backend")

    override fun command(): Command? = TODO("mind map v2 phase 2.5: backend")
}
