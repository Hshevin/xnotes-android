package com.xnotes.mindmap

/** Two-sided, variable-size tidy tree. Child list order is the document's stable sibling order. */
object MapLayout {
    private const val H_GAP = 72.0
    private const val V_GAP = 28.0
    fun arrange(doc: MindMapDocument): MindMapDocument {
        doc.validate()
        if (doc.direction == MapDirection.DOWN) {
            val swapped=doc.copy(direction=MapDirection.RIGHT,nodes=doc.nodes.map { it.copy(x=it.y,y=it.x,width=it.height,height=it.width) })
            val laid=arrange(swapped)
            return doc.copy(nodes=laid.nodes.map { it.copy(x=it.y,y=it.x,width=it.height,height=it.width) })
        }
        val children = doc.nodes.groupBy { it.parent }
        val heights = hashMapOf<String, Double>()
        fun height(n: MapNode): Double = heights.getOrPut(n.id) {
            val kids = if (n.collapsed) emptyList() else children[n.id].orEmpty()
            maxOf(n.height, kids.sumOf { height(it) } + V_GAP * (kids.size - 1).coerceAtLeast(0))
        }
        val root = doc.root
        val placed = hashMapOf(root.id to root.copy(x = 0.0, y = -root.height / 2))
        fun place(n: MapNode, x: Double, centerY: Double, right: Boolean) {
            val p = n.copy(x = x, y = centerY - n.height / 2)
            placed[n.id] = p
            if (n.collapsed) return
            val kids = children[n.id].orEmpty()
            var top = centerY - (kids.sumOf { height(it) } + V_GAP * (kids.size - 1).coerceAtLeast(0)) / 2
            for (c in kids) {
                val h = height(c)
                place(c, if (right) x + n.width + H_GAP else x - H_GAP - c.width, top + h / 2, right)
                top += h + V_GAP
            }
        }
        if (!root.collapsed) for (right in listOf(true, false)) {
            val kids = children[root.id].orEmpty().filterIndexed { i, _ -> if(doc.direction == MapDirection.RIGHT) right else (i % 2 == 0) == right }
            var top = -(kids.sumOf { height(it) } + V_GAP * (kids.size - 1).coerceAtLeast(0)) / 2
            for (c in kids) {
                val h = height(c)
                place(c, if (right) root.width + H_GAP else -H_GAP - c.width, top + h / 2, right)
                top += h + V_GAP
            }
        }
        // A free topic stays where the user put it; its branch is laid out beside it like the root's,
        // otherwise children added under it would keep the default origin and land on the root.
        for (f in doc.nodes) if (f.parent == null && f.free && !f.collapsed) {
            val kids = children[f.id].orEmpty()
            var top = f.y + f.height / 2 - (kids.sumOf { height(it) } + V_GAP * (kids.size - 1).coerceAtLeast(0)) / 2
            for (c in kids) {
                val h = height(c)
                place(c, f.x + f.width + H_GAP, top + h / 2, true)
                top += h + V_GAP
            }
        }
        return doc.copy(nodes = doc.nodes.map { placed[it.id] ?: it })
    }
}
