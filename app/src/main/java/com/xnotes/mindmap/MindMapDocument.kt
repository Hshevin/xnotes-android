package com.xnotes.mindmap

import com.xnotes.core.geometry.Rect
import java.util.UUID
import kotlin.math.abs

enum class NodeKind { TEXT, INK, REFERENCE }
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
    val free: Boolean = false,
    val sources: List<MapSource> = emptyList(),
    val primarySource: String? = null,
) {
    val bounds get() = Rect(x, y, width, height)
}

data class MindMapDocument(
    val id: String = UUID.randomUUID().toString(),
    val nodes: List<MapNode> = listOf(MapNode(parent = null)),
    val relations: List<MapRelation> = emptyList(),
    val direction: MapDirection = MapDirection.BOTH,
    val previewAssets: Map<String, MapPreviewAsset> = emptyMap(),
) {
    val root get() = nodes.single { it.parent == null && !it.free }
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
    fun visible(expanded:Set<String> = emptySet()): List<MapNode> {
        val children = nodes.groupBy { it.parent }
        val visible = hashSetOf<String>()
        val todo = ArrayDeque<MapNode>().apply { nodes.filter { it.parent == null }.forEach { add(it) } }
        while (todo.isNotEmpty()) {
            val at = todo.removeFirst()
            if (visible.add(at.id) && (!at.collapsed || at.id in expanded)) children[at.id].orEmpty().forEach { todo.add(it) }
        }
        return nodes.filter { it.id in visible }
    }
    /** Derive subtree sizes once in linear time, including nested collapsed descendants. */
    fun hiddenNodeCounts(expanded:Set<String> = emptySet()):Map<String,Int> {
        val remaining=nodes.groupingBy {it.parent}.eachCount().toMutableMap()
        val sizes=nodes.associate {it.id to 1}.toMutableMap()
        val byId=nodes.associateBy {it.id}
        val leaves=ArrayDeque(nodes.filter {remaining[it.id]==null})
        while(leaves.isNotEmpty()) {
            val node=leaves.removeFirst();val parent=byId[node.parent] ?: continue
            sizes[parent.id]=sizes.getValue(parent.id)+sizes.getValue(node.id)
            val left=remaining.getValue(parent.id)-1;remaining[parent.id]=left
            if(left==0) leaves.add(parent)
        }
        return visible(expanded).mapNotNull {node->
            (sizes.getValue(node.id)-1).takeIf {node.collapsed && node.id !in expanded && it>0}?.let {node.id to it}
        }.toMap()
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
        return copy(nodes = nodes.filter { it.id !in removed }, relations = relations.filter { it.from !in removed && it.to !in removed })
    }
    fun reparent(id: String, parent: String): MindMapDocument {
        if (id == root.id || parent in descendants(id) || nodes.none { it.id == parent }) return this
        val doc = update(id) { it.copy(parent = parent, free = false) }.update(parent) { it.copy(collapsed = false) }
        // Moving a deep subtree must also respect the reader's depth limit.
        return runCatching { doc.validate(); MapLayout.arrange(doc) }.getOrDefault(this)
    }
    fun translate(id: String, dx: Double, dy: Double): MindMapDocument {
        val ids = descendants(id)
        return copy(nodes = nodes.map { if (it.id in ids) it.copy(x = it.x + dx, y = it.y + dy) else it })
    }

    fun validate() {
        require(id.isNotBlank() && id.length <= 128)
        require(nodes.size in 1..MAX_NODES && nodes.count { it.parent == null && !it.free } == 1)
        val byId = nodes.associateBy { it.id }
        require(byId.size == nodes.size)
        require(relations.size <= 4000 && relations.map { it.id }.distinct().size == relations.size)
        require(relations.map { it.duplicateKey() }.distinct().size == relations.size)
        relations.forEach { require(it.id.isNotBlank() && it.id.length <= 128 && it.from in byId && it.to in byId && it.from != it.to && it.label.length <= 20_000) }
        val sources = nodes.flatMap { it.sources }
        require(sources.size <= 5000 && sources.map { it.id }.distinct().size == sources.size)
        require(previewAssets.size <= MapPreviewAsset.MAX_ASSETS)
        require(previewAssets.all { (key, asset) -> key == asset.key })
        require(previewAssets.values.sumOf { it.byteSize.toLong() } <= MapPreviewAsset.MAX_TOTAL_BYTES)
        require(previewAssets.values.sumOf { it.width.toLong() * it.height } <= MapPreviewAsset.MAX_TOTAL_PIXELS)
        sources.forEach { source -> source.lastPreview?.let { require(it.assetKey in previewAssets) } }
        var pointCount = 0
        for (n in nodes) {
            require(!n.free || n.parent == null)
            require(n.sources.size <= 128 && (n.primarySource == null || n.sources.any { it.id == n.primarySource }))
            n.sources.forEach { it.validate() }
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
                    require(p.x.isFinite() && abs(p.x) <= MAX_COORD && p.y.isFinite() && abs(p.y) <= MAX_COORD)
                    require(p.pressure.isFinite() && p.pressure in 0.0..2.0 && p.time.isFinite() && p.time >= 0.0)
                }
            }
        }
    }
    /** History frames own their asset maps; pruning a new frame never alters an older frame. */
    fun prunePreviewAssets(): MindMapDocument {
        val used = nodes.flatMap { it.sources }.mapNotNull { it.lastPreview?.assetKey }.toSet()
        return copy(previewAssets = previewAssets.filterKeys { it in used })
    }
    companion object {
        const val MAX_NODES = 1000
        const val MAX_DEPTH = 128
        const val MAX_POINTS = 250_000
        const val MAX_COORD = 1_000_000.0
    }
}
