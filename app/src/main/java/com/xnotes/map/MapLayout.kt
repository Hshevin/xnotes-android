package com.xnotes.map

import com.xnotes.core.geometry.Rect

/** Gives a node's box size. Production uses [NodeMetrics]; tests pass fixed sizes. */
fun interface NodeMeasure {
    fun size(node: MapNode, isRoot: Boolean): Size
}

/** The result of laying out every tree in a [MapState]. */
data class MapLayoutResult(
    /** Every visible node's placement. Nodes hidden by a collapsed ancestor are absent. */
    val placements: Map<NodeId, NodePlacement>,
    /** Every visible tree line (both ends visible), in tree order: roots in [MapState.roots] order, depth-first, siblings in order. */
    val edges: List<TreeEdge>,
    /** Nodes hidden because an ancestor is collapsed. A collapsed node itself stays visible. */
    val hidden: Set<NodeId>,
    /** For each collapsed visible node, how many descendants it hides (shown as the badge count). */
    val hiddenCount: Map<NodeId, Int>,
) {
    fun rectOf(id: NodeId): Rect? = placements[id]?.rect
}

/**
 * Automatic layout. A pure function of its inputs; never moves a root (roots sit at
 * [MapState.rootOrigin]).
 *
 * Rules, per tree, using `s(n)` = [NodeMeasure.size] and the constants below:
 *
 * **Subtree extent** (used to pack siblings) is computed from automatic positions only: offsets
 * never push siblings apart. Hidden descendants contribute nothing. A collapsed node's extent is its
 * own box.
 *
 * **RIGHT**: a child's left edge is `parent.right + [H_GAP]`. The children of a node are stacked top
 * to bottom with [V_GAP] between consecutive subtree extents; the stack (total height of the
 * children's subtree extents plus gaps) is centred vertically on the parent's centre Y. Each child
 * box is centred vertically within its own subtree extent. Edge: from (parent.right, parent.centerY)
 * to (child.left, child.centerY), side RIGHT.
 *
 * **BALANCED**: the root's children are split by order: the first `ceil(n / 2)` go right, the rest go
 * left. The right group is laid exactly like RIGHT. The left group mirrors it: a child's right edge is
 * `parent.left − [H_GAP]`, stacked top to bottom in order, centred on the parent's centre Y; edges
 * from (parent.left, parent.centerY) to (child.right, child.centerY), side LEFT. Deeper levels keep
 * growing on their branch's side.
 *
 * **ORG**: a child's top edge is `parent.bottom + [ORG_V_GAP]`. The children are placed left to right
 * with [ORG_H_GAP] between consecutive subtree extents (widths); the row is centred horizontally on
 * the parent's centre X; each child box is centred horizontally within its own subtree extent. Edge:
 * from (parent.centerX, parent.bottom) to (child.centerX, child.top), side DOWN.
 *
 * **INDENT**: a child's left edge is `parent.left + [INDENT]`. The first child's top is
 * `parent.bottom + [INDENT_V_GAP]`; each next sibling's top is the previous sibling's subtree extent
 * bottom + [INDENT_V_GAP] (a subtree extent here is the node box plus everything below it that
 * belongs to it). Edge: from (parent.left + [INDENT] / 2, parent.bottom) to (child.left,
 * child.centerY), side INDENT.
 *
 * **Offsets**: after a child's automatic box is computed relative to its parent's *final* box, its
 * offset from [MapState.offsets] is added. Its descendants are then laid relative to that moved box,
 * so the whole subtree follows the nudge. Siblings do not move.
 *
 * [NodePlacement.side] is the side the node's own children grow to: RIGHT/LEFT for its branch in
 * RIGHT and BALANCED (a BALANCED root reports RIGHT), DOWN for ORG, INDENT for INDENT.
 */
object MapLayout {
    const val H_GAP = 48.0
    const val V_GAP = 16.0
    const val ORG_H_GAP = 24.0
    const val ORG_V_GAP = 40.0
    const val INDENT = 32.0
    const val INDENT_V_GAP = 12.0

    fun compute(state: MapState, collapsed: Set<NodeId>, measure: NodeMeasure): MapLayoutResult =
        TODO("phase 1: gpt-6.2-astra")
}
