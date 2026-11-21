package com.xnotes.map

/**
 * Holds the canvas's current [MapState] and its collapsed set.
 *
 * [state] changes only through [MapEditor] operations and their undo/redo (content history). The
 * collapsed set is view state that is saved with the file but never enters history: undoing an edit
 * does not re-expand or re-collapse anything. Ids in [collapsed] that no longer name a node with
 * children are ignored by layout and dropped by [prunedCollapsed].
 *
 * Listeners hear every change of either, after it happened, on the calling thread.
 */
class MapModel(initial: MapState = MapState.EMPTY, collapsed: Set<NodeId> = emptySet()) {

    fun interface Listener {
        fun onMapChanged(model: MapModel)
    }

    var state: MapState = initial
        private set

    var collapsed: Set<NodeId> = collapsed
        private set

    private val listeners = ArrayList<Listener>()

    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }

    /**
     * Collapse or expand [id]. Returns false (and changes nothing, notifies nobody) when [id] is not a
     * node, has no children, or is already in the requested state.
     */
    fun setCollapsed(id: NodeId, value: Boolean): Boolean {
        val node = state.nodes[id] ?: return false
        if (state.childrenOf(node.id).isEmpty()) return false
        if ((id in collapsed) == value) return false
        collapsed = if (value) collapsed + id else collapsed - id
        notifyChanged()
        return true
    }

    /** [collapsed] limited to nodes that exist and have children: what a save writes. */
    fun prunedCollapsed(): Set<NodeId> = collapsed.filterTo(HashSet()) { state.childrenOf(it).isNotEmpty() }

    /** Swap the whole state. Only [MapEditor] and its commands call this. */
    internal fun replace(next: MapState) {
        if (next == state) return
        state = next
        notifyChanged()
    }

    private fun notifyChanged() {
        for (l in listeners.toList()) l.onMapChanged(this)
    }
}
