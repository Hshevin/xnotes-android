package com.xnotes.map

import com.xnotes.core.geometry.Pt

/*
 * Pure state transforms the canvas needs outside a single editor command (phase 2.5 contract;
 * implemented by the backend). [MapEditor] operations that do the same thing must produce exactly
 * the same states, so the editor can be written in terms of these.
 */

/**
 * This state with [id] moved by [delta] exactly as [MapEditor.nudge] moves it: a root's origin by
 * [delta]; a child's offset grows by it, and an offset that becomes zero is removed. Unchanged
 * (the same instance) for a zero delta or an unknown id. Used to preview a drag without history.
 */
fun MapState.nudged(id: NodeId, delta: Vec): MapState {
    if (delta.isZero || id !in nodes) return this
    val origin = rootOrigin[id]
    if (origin != null) return copy(rootOrigin = rootOrigin + (id to Pt(origin.x + delta.dx, origin.y + delta.dy)))
    val offset = (offsets[id] ?: Vec.ZERO) + delta
    return copy(offsets = if (offset.isZero) offsets - id else offsets + (id to offset))
}

/**
 * This state without the subtrees of [ids], exactly as [MapEditor.delete] leaves it: the nodes and
 * all their descendants go, with their parent/children links, root entries (origin, layout, layer),
 * offsets and every relation touching a removed node; surviving parents keep their other children
 * in order. Unknown ids are ignored; the same instance when nothing is removed. Used when a canvas
 * layer is deleted, so the trees on it go in the same undo step as the layer.
 */
fun MapState.withoutSubtrees(ids: Collection<NodeId>): MapState {
    val removed = subtreeIds(ids)
    if (removed.isEmpty()) return this
    val remainingChildren = children.toMutableMap()
    remainingChildren.keys.removeAll(removed)
    val survivingParents = removed.mapNotNullTo(HashSet()) { parent[it] } - removed
    for (id in survivingParents) remainingChildren[id] = childrenOf(id).filterNot { it in removed }
    return copy(
        nodes = nodes - removed,
        roots = roots.filterNot { it in removed },
        parent = parent - removed,
        children = remainingChildren,
        rootOrigin = rootOrigin - removed,
        layout = layout - removed,
        offsets = offsets - removed,
        layers = layers - removed,
        relations = relations.filterValues { it.from !in removed && it.to !in removed },
    )
}

/**
 * The roots of the trees drawn on canvas layer [layer]: those whose [MapState.layers] entry is
 * [layer], plus, when [isDefault], those with no entry. In [MapState.roots] order.
 */
fun MapState.rootsOnLayer(layer: String, isDefault: Boolean): List<NodeId> = roots.filter { id ->
    val assigned = layers[id]
    assigned == layer || (isDefault && assigned == null)
}
