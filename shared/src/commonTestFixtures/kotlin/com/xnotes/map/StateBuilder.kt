package com.xnotes.map

import com.xnotes.core.geometry.Pt

/** Builds a [MapState] by hand for layout and hit tests. */
class StateBuilder {
    private val nodes = LinkedHashMap<NodeId, MapNode>()
    private val roots = ArrayList<NodeId>()
    private val parent = LinkedHashMap<NodeId, NodeId>()
    private val children = LinkedHashMap<NodeId, MutableList<NodeId>>()
    private val origin = LinkedHashMap<NodeId, Pt>()
    private val layout = LinkedHashMap<NodeId, LayoutKind>()
    private val offsets = LinkedHashMap<NodeId, Vec>()
    private val relations = LinkedHashMap<RelationId, Relation>()

    fun root(id: String, at: Pt = Pt(0.0, 0.0), kind: LayoutKind = LayoutKind.RIGHT, title: String = id): NodeId {
        val n = NodeId(id)
        nodes[n] = MapNode(n, title, NodeStyle.ROOT)
        roots += n
        origin[n] = at
        layout[n] = kind
        return n
    }

    fun child(of: NodeId, id: String, title: String = id): NodeId {
        val n = NodeId(id)
        nodes[n] = MapNode(n, title)
        parent[n] = of
        children.getOrPut(of) { ArrayList() } += n
        return n
    }

    fun offset(id: NodeId, dx: Double, dy: Double) { offsets[id] = Vec(dx, dy) }

    fun relation(id: String, a: NodeId, b: NodeId) {
        relations[RelationId(id)] = Relation(RelationId(id), a, b)
    }

    fun build() = MapState(
        nodes = nodes.toMap(),
        roots = roots.toList(),
        parent = parent.toMap(),
        children = children.mapValues { it.value.toList() },
        rootOrigin = origin.toMap(),
        layout = layout.toMap(),
        offsets = offsets.toMap(),
        relations = relations.toMap(),
    )
}
