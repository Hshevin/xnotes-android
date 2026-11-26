package com.xnotes.map

/** Checks the invariants documented on [MapState]. */
object MapInvariants {
    /** Human-readable descriptions of every violated invariant; empty when the state is valid. */
    fun check(state: MapState): List<String> = buildList {
        val roots = state.roots.toSet()
        if (roots.size != state.roots.size) add("Duplicate entries in roots")
        for ((id, node) in state.nodes) {
            if (node.width != null && (!node.width.isFinite() || node.width <= 0.0)) add("Invalid width on node $id")
            if (id != node.id) add("Node ${node.id} is stored under $id")
            if (id in roots && id in state.parent) add("Root $id also has a parent")
            if (id !in roots && id !in state.parent) add("Node $id has no parent and is not a root")
        }
        for (root in roots) {
            if (root !in state.nodes) add("Root $root does not exist")
            if (root !in state.rootOrigin) add("Root $root has no origin")
            if (root !in state.layout) add("Root $root has no layout")
        }
        for (id in state.rootOrigin.keys) if (id !in roots) add("Non-root $id has an origin")
        for (id in state.layout.keys) if (id !in roots) add("Non-root $id has a layout")
        for (id in state.layers.keys) if (id !in roots) add("Non-root $id has a layer")
        for ((id, offset) in state.offsets) {
            if (id !in state.nodes || id !in state.parent || id in roots) add("Offset on non-child $id")
            if (offset.isZero) add("Zero offset on $id")
        }
        val listed = HashSet<NodeId>()
        for ((p, children) in state.children) {
            if (p !in state.nodes) add("Unknown node $p has a children list")
            val siblings = HashSet<NodeId>()
            for (child in children) {
                if (!siblings.add(child)) add("$p lists $child more than once")
                if (child !in state.nodes) add("Unknown child $child of $p")
                if (state.parent[child] != p) add("$p lists $child with a different or missing parent")
                else listed.add(child)
            }
        }
        for ((child, p) in state.parent) {
            if (child !in state.nodes || p !in state.nodes) add("Unknown node in parent link $child -> $p")
            if (child !in listed) add("Child $child missing from $p's children")
        }

        // Check parent chains independently of the (possibly inconsistent) children lists.
        val checked = HashSet<NodeId>()
        for (id in state.nodes.keys) {
            if (id in checked) continue
            val path = LinkedHashSet<NodeId>()
            var current: NodeId? = id
            while (current != null && current !in checked) {
                if (!path.add(current)) {
                    add("Parent cycle at $current")
                    break
                }
                if (current !in state.parent && current !in roots) {
                    add("Parent chain from $id ends at $current without reaching a root")
                    break
                }
                current = state.parent[current]
            }
            checked.addAll(path)
        }
        val pairs = HashSet<Pair<NodeId, NodeId>>()
        for ((id, relation) in state.relations) {
            val from = relation.from
            val to = relation.to
            if (id != relation.id) add("Relation ${relation.id} is stored under $id")
            if (from !in state.nodes || to !in state.nodes) add("Unknown endpoint in relation $id ($from, $to)")
            if (from == to) add("Self relation $id on $from")
            val pair = if (from.value <= to.value) Pair(from, to) else Pair(to, from)
            if (!pairs.add(pair)) add("Duplicate relation between $from and $to")
        }
    }
}
