package com.xnotes.map

import com.xnotes.core.geometry.Pt
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

    fun compute(state: MapState, collapsed: Set<NodeId>, measure: NodeMeasure): MapLayoutResult {
        val placements = LinkedHashMap<NodeId, NodePlacement>()
        val edges = ArrayList<TreeEdge>()
        val hidden = HashSet<NodeId>()
        val hiddenCount = HashMap<NodeId, Int>()

        for (root in state.roots) {
            val kind = state.layout.getValue(root)
            val order = ArrayList<NodeId>()
            state.walkTrees(listOf(root)) { id ->
                order.add(id)
                if (id in collapsed && state.childrenOf(id).isNotEmpty()) {
                    val before = hidden.size
                    state.walkTrees(state.childrenOf(id)) { hidden.add(it) }
                    hiddenCount[id] = hidden.size - before
                }
                id !in collapsed
            }

            // Pack from the leaves inward, without offsets. Each visible box is measured once.
            val branches = HashMap<NodeId, Branch>()
            for (id in order.asReversed()) {
                val size = measure.size(state.nodes.getValue(id), id == root)
                val ownExtent = if (kind == LayoutKind.ORG) size.w else size.h
                val children = if (id in collapsed) emptyList() else state.childrenOf(id)
                val extent = if (id == root && kind == LayoutKind.BALANCED) {
                    val split = (children.size + 1) / 2
                    val right = pack(children.subList(0, split), branches, kind, ownExtent)
                    val left = pack(children.subList(split, children.size), branches, kind, ownExtent, left = true)
                    maxOf(left, right)
                } else pack(children, branches, kind, ownExtent)
                branches[id] = Branch(size, extent)
            }

            // Resolve each child's slot against its parent's final box, including all ancestor nudges.
            for (id in order) {
                val branch = branches.getValue(id)
                val parent = state.parent[id]?.let(placements::getValue)
                val side = when (kind) {
                    LayoutKind.RIGHT -> GrowSide.RIGHT
                    LayoutKind.BALANCED -> if (branch.left || parent?.side == GrowSide.LEFT) GrowSide.LEFT else GrowSide.RIGHT
                    LayoutKind.ORG -> GrowSide.DOWN
                    LayoutKind.INDENT -> GrowSide.INDENT
                }
                val at = if (parent == null) state.rootOrigin.getValue(id) else {
                    val box = parent.rect
                    val auto = when (side) {
                        GrowSide.RIGHT -> Pt(box.right + H_GAP, box.top + branch.start)
                        GrowSide.LEFT -> Pt(box.left - H_GAP - branch.size.w, box.top + branch.start)
                        GrowSide.DOWN -> Pt(box.left + branch.start, box.bottom + ORG_V_GAP)
                        GrowSide.INDENT -> Pt(box.left + INDENT, box.top + branch.start)
                    }
                    val offset = state.offsets[id] ?: Vec.ZERO
                    Pt(auto.x + offset.dx, auto.y + offset.dy)
                }
                val rect = Rect(at.x, at.y, branch.size.w, branch.size.h)
                val placement = NodePlacement(id, rect, side, if (parent == null) 0 else parent.depth + 1)
                placements[id] = placement
                if (parent != null) edges.add(edge(parent, placement))
            }
        }
        return MapLayoutResult(placements, edges, hidden, hiddenCount)
    }

    private class Branch(val size: Size, val extent: Double, var start: Double = 0.0, var left: Boolean = false)

    /** Sets child box starts along the stacking axis, relative to the parent, and returns the subtree extent. */
    private fun pack(
        children: List<NodeId>, branches: Map<NodeId, Branch>, kind: LayoutKind, ownExtent: Double,
        left: Boolean = false,
    ): Double {
        if (children.isEmpty()) return ownExtent
        val gap = when (kind) {
            LayoutKind.ORG -> ORG_H_GAP
            LayoutKind.INDENT -> INDENT_V_GAP
            else -> V_GAP
        }
        val span = children.sumOf { branches.getValue(it).extent } + (children.size - 1) * gap
        var cursor = if (kind == LayoutKind.INDENT) ownExtent + gap else (ownExtent - span) / 2
        for (id in children) {
            val child = branches.getValue(id)
            val boxExtent = if (kind == LayoutKind.ORG) child.size.w else child.size.h
            child.start = if (kind == LayoutKind.INDENT) cursor else cursor + (child.extent - boxExtent) / 2
            child.left = left
            cursor += child.extent + gap
        }
        return if (kind == LayoutKind.INDENT) ownExtent + gap + span else maxOf(ownExtent, span)
    }

    private fun edge(parent: NodePlacement, child: NodePlacement): TreeEdge {
        val p = parent.rect
        val c = child.rect
        val (from, to) = when (child.side) {
            GrowSide.RIGHT -> Pt(p.right, p.centerY) to Pt(c.left, c.centerY)
            GrowSide.LEFT -> Pt(p.left, p.centerY) to Pt(c.right, c.centerY)
            GrowSide.DOWN -> Pt(p.centerX, p.bottom) to Pt(c.centerX, c.top)
            GrowSide.INDENT -> Pt(p.left + INDENT / 2, p.bottom) to Pt(c.left, c.centerY)
        }
        return TreeEdge(parent.id, child.id, from, to, child.side)
    }
}
