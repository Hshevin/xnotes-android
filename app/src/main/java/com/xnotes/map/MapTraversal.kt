package com.xnotes.map

/** Visits parents before children in draw order. Returning false skips the current node's children. */
internal inline fun MapState.walkTrees(tops: Collection<NodeId>, visit: (NodeId) -> Boolean) {
    val pending = ArrayDeque<NodeId>()
    pending.addAll(tops.reversed())
    while (pending.isNotEmpty()) {
        val id = pending.removeLast()
        if (visit(id)) pending.addAll(childrenOf(id).asReversed())
    }
}

internal fun MapState.subtreeIds(tops: Collection<NodeId>): Set<NodeId> = buildSet {
    walkTrees(tops.filter { it in nodes }) { add(it) }
}
