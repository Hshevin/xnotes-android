package com.xnotes.map

import com.xnotes.core.geometry.Pt

/** What dropping a dragged node does. Computed by [MapHit.drop], applied by [MapEditor.applyDrop]. */
sealed interface DropAction {
    /**
     * Make the dragged node a child of [parent] at [index], where [index] counts [parent]'s children
     * *after* the dragged node has been taken out of its current place.
     */
    data class Reparent(val parent: NodeId, val index: Int) : DropAction

    /** Keep the hierarchy; move the node by [delta]: a child's offset grows by it, a root's origin moves by it. */
    data class Nudge(val delta: Vec) : DropAction

    /** Nothing changes (dropped back where it was). */
    data object None : DropAction
}

/**
 * Hit testing and drop resolution over a [MapLayoutResult]. Pure functions.
 *
 * Draw order is [MapState.roots] order, each tree depth-first with siblings in order; a node drawn
 * later is on top.
 */
object MapHit {

    /** Fraction of a target's extent along the stacking axis that means "insert before/after" rather than "become a child". */
    const val EDGE_BAND = 0.25

    /** The topmost visible node whose box contains [p], or null. */
    fun nodeAt(state: MapState, layout: MapLayoutResult, p: Pt): NodeId? = TODO("phase 1: gpt-6.2-astra")

    /**
     * What dropping [dragged] does when the finger is at [pointer] and the node has been dragged by
     * [delta] since pick-up.
     *
     * 1. The target is the topmost visible node under [pointer], ignoring [dragged] and all its
     *    descendants.
     * 2. With a target that is a child, the stacking axis is that of the target's parent: vertical when
     *    the parent's [NodePlacement.side] is RIGHT, LEFT or INDENT, horizontal when DOWN. If [pointer]
     *    lies in the first [EDGE_BAND] of the target's box along that axis (top / left), the result is
     *    a [DropAction.Reparent] under the target's parent just before the target; in the last
     *    [EDGE_BAND] (bottom / right), just after it. Otherwise (and always when the target is a
     *    root) the result is a [DropAction.Reparent] as the target's last child.
     * 3. A [DropAction.Reparent] that would leave [dragged] with the same parent at the same index is
     *    [DropAction.None].
     * 4. Without a target: [DropAction.Nudge] by [delta], or [DropAction.None] when [delta] is zero.
     */
    fun drop(state: MapState, layout: MapLayoutResult, dragged: NodeId, pointer: Pt, delta: Vec): DropAction =
        TODO("phase 1: gpt-6.2-astra")
}
