package com.xnotes.map

import com.xnotes.core.geometry.Rect

/**
 * Copied trees, detached from any canvas. [state] is a valid [MapState] whose roots are the copied
 * subtrees' top nodes, with [MapState.rootOrigin] set to where each top node was laid out when copied
 * and [MapState.layout] to its tree's kind; it has no [MapState.layers] entries. [bounds] is the
 * union of every copied visible node's laid-out box at copy time.
 */
data class MapFragment(val state: MapState, val bounds: Rect?) {
    val isEmpty: Boolean get() = state.roots.isEmpty()
}

object MapClipboard {
    /**
     * Copy [ids] with their whole subtrees (collapsed parts included).
     *
     * - A selected node that is a descendant of another selected node is covered by that one.
     * - Top nodes keep their style; they lose their offset and become fragment roots in tree order
     *   ([MapState.roots] order, depth-first, siblings in order).
     * - Offsets inside the copied subtrees are kept.
     * - Relations are kept only when both ends are copied.
     * - Node ids are kept (paste renews them).
     *
     * A top node hidden by a collapsed ancestor has no laid-out box; its origin is then its nearest
     * visible ancestor's box top-left. Empty fragment when no id exists.
     */
    fun copy(state: MapState, layout: MapLayoutResult, ids: Collection<NodeId>): MapFragment =
        TODO("phase 1: gpt-6.2-astra")
}
