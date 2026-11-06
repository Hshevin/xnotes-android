package com.xnotes.mindmap

import com.xnotes.core.geometry.Rect
import java.util.UUID
import kotlin.math.abs

enum class NodeKind { TEXT, INK }
data class InkPoint(val x: Double, val y: Double, val pressure: Double = 1.0, val time: Double = 0.0)
data class MapInk(val points: List<InkPoint>, val width: Double = 3.0)

/** Immutable node-local ink makes drag, relayout and history independent of pen geometry. */
data class MapNode(
    val id: String = UUID.randomUUID().toString(),
    val parent: String?,
    val kind: NodeKind = NodeKind.TEXT,
    val text: String = "",
    val ink: List<MapInk> = emptyList(),
    val width: Double = if (kind == NodeKind.INK) 320.0 else 220.0,
    val height: Double = if (kind == NodeKind.INK) 200.0 else 72.0,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val collapsed: Boolean = false,
) {
    val bounds get() = Rect(x, y, width, height)
}

data class MindMapDocument(
    val id: String = UUID.randomUUID().toString(),
    val nodes: List<MapNode> = listOf(MapNode(parent = null)),
) {
    val root get() = nodes.single { it.parent == null }
    fun node(id: String) = nodes.first { it.id == id }
    fun children(id: String) = nodes.filter { it.parent == id }
    fun update(id: String, change: (MapNode) -> MapNode) = copy(nodes = nodes.map { if (it.id == id) change(it) else it })
    fun descendants(id: String): Set<String> {
        val children = nodes.groupBy { it.parent }
        val found = linkedSetOf<String>()
        val todo = ArrayDeque<String>().apply { add(id) }
        while (todo.isNotEmpty()) {
            val at = todo.removeFirst()
            if (found.add(at)) children[at].orEmpty().forEach { todo.add(it.id) }
        }
        return found
    }
    fun visible(): List<MapNode> {
        val children = nodes.groupBy { it.parent }
        val visible = hashSetOf<String>()
        val todo = ArrayDeque<MapNode>().apply { add(root) }
        while (todo.isNotEmpty()) {
            val at = todo.removeFirst()
            if (visible.add(at.id) && !at.collapsed) children[at.id].orEmpty().forEach { todo.add(it) }
        }
        return nodes.filter { it.id in visible }
    }
    fun add(parent: String, kind: NodeKind, text: String): Pair<MindMapDocument, String> {
        require(nodes.size < MAX_NODES)
        node(parent)
        val added = MapNode(parent = parent, kind = kind, text = text)
        val expanded = update(parent) { it.copy(collapsed = false) }
        val doc = expanded.copy(nodes = expanded.nodes + added)
        doc.validate()
        return MapLayout.arrange(doc) to added.id
    }
    fun delete(id: String): MindMapDocument {
        if (id == root.id) return this
        val removed = descendants(id)
        return copy(nodes = nodes.filter { it.id !in removed })
    }
    fun reparent(id: String, parent: String): MindMapDocument {
        if (id == root.id || parent in descendants(id) || nodes.none { it.id == parent }) return this
        val doc = update(id) { it.copy(parent = parent) }.update(parent) { it.copy(collapsed = false) }
        // Moving a deep subtree must also respect the reader's depth limit.
        return runCatching { doc.validate(); MapLayout.arrange(doc) }.getOrDefault(this)
    }
    fun translate(id: String, dx: Double, dy: Double): MindMapDocument {
        val ids = descendants(id)
        return copy(nodes = nodes.map { if (it.id in ids) it.copy(x = it.x + dx, y = it.y + dy) else it })
    }

    fun validate() {
        require(id.isNotBlank() && id.length <= 128)
        require(nodes.size in 1..MAX_NODES && nodes.count { it.parent == null } == 1)
        val byId = nodes.associateBy { it.id }
        require(byId.size == nodes.size)
        var pointCount = 0
        for (n in nodes) {
            require(n.id.isNotBlank() && n.id.length <= 128 && n.text.length <= 20_000)
            require(n.width.isFinite() && n.width in 48.0..4096.0 && n.height.isFinite() && n.height in 48.0..4096.0)
            require(n.x.isFinite() && abs(n.x) <= MAX_COORD && n.y.isFinite() && abs(n.y) <= MAX_COORD)
            require(n.kind == NodeKind.INK || n.ink.isEmpty())
            val seen = hashSetOf<String>()
            var at: MapNode? = n
            while (at != null) {
                require(seen.add(at.id) && seen.size <= MAX_DEPTH)
                at = at.parent?.let { requireNotNull(byId[it]) }
            }
            for (stroke in n.ink) {
                require(stroke.width.isFinite() && stroke.width in .1..32.0 && stroke.points.isNotEmpty())
                pointCount += stroke.points.size
                require(pointCount <= MAX_POINTS)
                for (p in stroke.points) {
                    require(p.x.isFinite() && p.x in 0.0..n.width && p.y.isFinite() && p.y in 0.0..n.height)
                    require(p.pressure.isFinite() && p.pressure in 0.0..2.0 && p.time.isFinite() && p.time >= 0.0)
                }
            }
        }
    }
    companion object {
        const val MAX_NODES = 1000
        const val MAX_DEPTH = 128
        const val MAX_POINTS = 250_000
        const val MAX_COORD = 1_000_000.0
    }
}

/** Selection and viewport never enter content history. Each accepted gesture is one snapshot. */
class MapHistory(initial: MindMapDocument) {
    var document = initial; private set
    private val past = ArrayDeque<MindMapDocument>()
    private val future = ArrayDeque<MindMapDocument>()
    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()
    fun commit(next: MindMapDocument) {
        if (next == document) return
        next.validate()
        past.addLast(document)
        if (past.size > 80) past.removeFirst()
        document = next
        future.clear()
    }
    fun undo() { if (canUndo) { future.addLast(document); document = past.removeLast() } }
    fun redo() { if (canRedo) { past.addLast(document); document = future.removeLast() } }
}
