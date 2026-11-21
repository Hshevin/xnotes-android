package com.xnotes.map.ui

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.InkPass
import com.xnotes.core.infinite.ItemMesher
import com.xnotes.core.infinite.MeshPart
import com.xnotes.core.infinite.ShapeTessellator
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.ConnectorEnd
import com.xnotes.core.model.ConnectorItem
import com.xnotes.core.model.FixedGeometry
import com.xnotes.core.model.GeometrySnapshot
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Routing
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.ShapeLabel
import com.xnotes.core.model.TextStyle
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pal.TextMeasurer
import com.xnotes.core.vector.VectorContour
import com.xnotes.core.shapes.Axis
import com.xnotes.core.shapes.Elbow
import com.xnotes.core.shapes.Head
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.map.GrowSide
import com.xnotes.map.MapLayoutResult
import com.xnotes.map.MapNode
import com.xnotes.map.MapState
import com.xnotes.map.NodeFrame
import com.xnotes.map.NodeId
import com.xnotes.map.NodeMetrics
import com.xnotes.map.NodeShape
import com.xnotes.map.Relation
import com.xnotes.map.RelationArrow
import com.xnotes.map.RelationId
import com.xnotes.map.TreeEdge
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A part of a mind map as the canvas renderer sees it. These stand in for the map in the GL scene
 * and its z order only: they are never added to the [com.xnotes.core.infinite.InfiniteDocument], so
 * saving, the band, the lasso, the eraser and the clipboard never meet them. Their geometry is the
 * map's to decide, so every transform is refused.
 */
sealed class MapPart : CanvasItem {
    override var layerId: String? = null
    override val resizable: Boolean get() = false
    override var locked: Boolean
        get() = true
        set(_) {}
    internal var box: Rect = Rect(0.0, 0.0, 0.0, 0.0)

    /** What the part is drawn from, kept so the CPU renderer (thumbnails, previews) draws what the scene meshes. */
    internal var pieces: List<MapPiece> = emptyList()

    override fun paint(r: Renderer) { for (p in pieces) p.paint(r) }
    override fun bounds(): Rect = box
    override fun translate(dx: Double, dy: Double) {}
    override fun contains(p: Pt): Boolean = box.contains(p)
    override fun centroid(): Pt = box.center
    override fun intersectsCircle(cx: Double, cy: Double, radius: Double): Boolean = false
    override fun snapshotGeometry(): GeometrySnapshot = FixedGeometry
    override fun restoreGeometry(snap: GeometrySnapshot) {}
    override fun applyTransform(t: Affine) {}
}

class MapNodePart(val id: NodeId) : MapPart() { override val kind: String get() = "map_node" }
class MapEdgePart(val child: NodeId) : MapPart() { override val kind: String get() = "map_edge" }
class MapRelationPart(val id: RelationId) : MapPart() { override val kind: String get() = "map_relation" }

/**
 * One drawable of a map part, which the GL scene meshes and the CPU renderer paints, so both
 * draw the same thing.
 */
sealed interface MapPiece {
    fun mesh(): List<MeshPart>
    fun paint(r: Renderer)

    /** A canvas item that already knows how to draw itself: a box, a line, a stroke. */
    class Item(val item: CanvasItem) : MapPiece {
        override fun mesh(): List<MeshPart> = ItemMesher.mesh(item)?.parts.orEmpty()
        override fun paint(r: Renderer) = item.paint(r)
    }

    /** Glyph outlines in their own frame, placed at ([dx], [dy]). */
    class Glyphs(val contours: List<VectorContour>, val color: Rgba, val dx: Double, val dy: Double) : MapPiece {
        override fun mesh(): List<MeshPart> =
            listOfNotNull(ShapeTessellator.textPart(contours, color) { Pt(dx + it.x, dy + it.y) })
        override fun paint(r: Renderer) {
            if (contours.isEmpty()) return
            r.save(); r.translate(dx, dy); r.fillPath(contours, color); r.restore()
        }
    }

    /** A plain filled rectangle. */
    class Fill(val rect: Rect, val color: Rgba) : MapPiece {
        override fun mesh(): List<MeshPart> {
            val b = com.xnotes.core.infinite.MeshBuilder(4, 6)
            b.rect(rect.left, rect.top, rect.w, rect.h)
            return listOf(MeshPart(b.build(), color, if (color.a == 255) InkPass.OPAQUE else InkPass.TRANSLUCENT))
        }
        override fun paint(r: Renderer) = r.fillRect(rect, color)
    }
}

/** Where the projection's meshes go: the GL scene, or a test's list. */
interface MapSceneSink {
    /** False for a caller that only paints the parts, so nodes are not meshed for nothing. */
    val wantsMeshes: Boolean get() = true
    fun upsert(item: CanvasItem, parts: List<MeshPart>, bounds: Rect)
    fun remove(item: CanvasItem)
}

/**
 * Turns the visible map into meshes, one record per node, tree edge and relation line, keyed by
 * id so a record survives every edit that keeps its id. Only what changed since the last
 * [update] is re-meshed: a node whose content, box and badge are as they were keeps its triangles.
 *
 * Drawing order, which [items] publishes: per layer, tree edges, then relation lines, then nodes,
 * so lines run under the boxes they join.
 */
class MapProjection(private val titles: MapTitles, private val labels: TextMeasurer, private val metrics: NodeMetrics) {

    /** Everything a node's look depends on: equal looks keep their mesh. */
    private data class NodeLook(
        val node: MapNode, val rect: Rect, val root: Boolean, val hidden: Int, val side: GrowSide,
        val titleShown: Boolean, val night: Boolean,
    )

    private data class EdgeLook(val edge: TreeEdge, val color: Rgba, val width: Double, val kind: Routing)
    private data class RelationLook(val relation: Relation, val from: Rect, val to: Rect)

    private val nodes = LinkedHashMap<NodeId, Pair<MapNodePart, NodeLook>>()
    private val edges = LinkedHashMap<NodeId, Pair<MapEdgePart, EdgeLook>>()
    private val relations = LinkedHashMap<RelationId, Pair<MapRelationPart, RelationLook>>()

    /** Each shown node's source chips as drawn: the chip and its source id, null for the "+N" chip. */
    private val chips = HashMap<NodeId, List<Pair<Rect, String?>>>()

    /** The parts in drawing order, grouped by nothing: the caller stacks them by [CanvasItem.layerId]. */
    var items: List<MapPart> = emptyList()
        private set

    fun node(id: NodeId): MapNodePart? = nodes[id]?.first

    /**
     * Bring the scene in line with [state] laid out as [layout]. [untitled] is the node whose title
     * is being typed in a field over it, drawn without its words. Returns whether anything changed.
     */
    fun update(
        state: MapState,
        layout: MapLayoutResult,
        sink: MapSceneSink,
        untitled: NodeId? = null,
        night: Boolean = false,
        layerFor: (String?) -> String? = { it },
    ): Boolean {
        var changed = false
        val layerOf = HashMap<NodeId, String?>()
        fun layer(id: NodeId): String? = layerOf.getOrPut(id) { layerFor(state.layers[state.rootOf(id)]) }

        // Nodes.
        val seenNodes = HashSet<NodeId>()
        for ((id, placement) in layout.placements) {
            val node = state.nodes[id] ?: continue
            seenNodes += id
            val look = NodeLook(
                node, placement.rect, id !in state.parent, layout.hiddenCount[id] ?: 0, placement.side,
                id != untitled, night,
            )
            val old = nodes[id]
            val part = old?.first ?: MapNodePart(id)
            part.layerId = layer(id)
            if (old?.second == look) continue
            val (pieces, bounds) = nodePieces(look)
            part.box = bounds
            part.pieces = pieces
            nodes[id] = part to look
            sink.upsert(part, if (sink.wantsMeshes) pieces.flatMap { it.mesh() } else emptyList(), bounds)
            changed = true
        }
        for (id in nodes.keys.filter { it !in seenNodes }) {
            chips.remove(id)
            sink.remove(nodes.remove(id)!!.first)
            changed = true
        }

        // Tree edges, keyed by the child.
        val seenEdges = HashSet<NodeId>()
        for (edge in layout.edges) {
            val child = state.nodes[edge.child] ?: continue
            seenEdges += edge.child
            val routing = if (edge.side == GrowSide.RIGHT || edge.side == GrowSide.LEFT) Routing.CURVED else Routing.ROUNDED
            val look = EdgeLook(edge, child.style.border, EDGE_WIDTH, routing)
            val old = edges[edge.child]
            val part = old?.first ?: MapEdgePart(edge.child)
            part.layerId = layer(edge.child)
            if (old?.second == look) continue
            val line = edgeItem(look)
            val meshed = ItemMesher.mesh(line)
            part.box = meshed?.bounds ?: Rect(edge.from.x, edge.from.y, 0.0, 0.0)
            part.pieces = listOf(MapPiece.Item(line))
            edges[edge.child] = part to look
            if (meshed == null) sink.remove(part) else sink.upsert(part, meshed.parts, meshed.bounds)
            changed = true
        }
        for (id in edges.keys.filter { it !in seenEdges }) {
            sink.remove(edges.remove(id)!!.first)
            changed = true
        }

        // Relation lines, shown only while both ends are.
        val seenRelations = HashSet<RelationId>()
        for (relation in state.relations.values) {
            val from = layout.rectOf(relation.from) ?: continue
            val to = layout.rectOf(relation.to) ?: continue
            seenRelations += relation.id
            val look = RelationLook(relation, from, to)
            val old = relations[relation.id]
            val part = old?.first ?: MapRelationPart(relation.id)
            part.layerId = layer(relation.from)
            if (old?.second == look) continue
            val line = relationItem(look)
            val meshed = line?.let(ItemMesher::mesh)
            part.box = meshed?.bounds ?: from.union(to)
            part.pieces = listOfNotNull(line?.let { MapPiece.Item(it) })
            relations[relation.id] = part to look
            if (meshed == null) sink.remove(part) else sink.upsert(part, meshed.parts, meshed.bounds)
            changed = true
        }
        for (id in relations.keys.filter { it !in seenRelations }) {
            sink.remove(relations.remove(id)!!.first)
            changed = true
        }

        if (changed) items = edges.values.map { it.first } + relations.values.map { it.first } + nodes.values.map { it.first }
        return changed
    }

    /** Forget every record without telling the scene, after the scene itself was reset. */
    fun forget() {
        nodes.clear(); edges.clear(); relations.clear(); chips.clear()
        items = emptyList()
    }

    /** Drop every record, as when the canvas's document is replaced. */
    fun clear(sink: MapSceneSink) {
        for ((part, _) in nodes.values) sink.remove(part)
        for ((part, _) in edges.values) sink.remove(part)
        for ((part, _) in relations.values) sink.remove(part)
        nodes.clear(); edges.clear(); relations.clear(); chips.clear()
        items = emptyList()
    }

    /**
     * The source chip under [p]: its node and source id, with a null id for the "+N" chip that
     * stands for the sources that did not fit. Null when [p] is on no chip.
     */
    fun chipAt(p: Pt): Pair<NodeId, String?>? {
        for ((id, row) in chips) for ((r, source) in row) if (r.contains(p)) return id to source
        return null
    }

    /** The relation line whose stroke passes within [reach] of [p], topmost first. */
    fun relationAt(p: Pt, reach: Double): RelationId? {
        for ((id, entry) in relations.entries.reversed()) {
            val (a, b) = relationEnds(entry.second.from, entry.second.to) ?: continue
            if (distanceToSegment(p, a, b) <= reach) return id
        }
        return null
    }

    // ---- meshing ----

    private fun nodePieces(look: NodeLook): Pair<List<MapPiece>, Rect> {
        val node = look.node
        val style = node.style
        val frame = metrics.frame(node, look.root, look.rect.topLeft)
        val r = look.rect
        val parts = ArrayList<MapPiece>()
        var bounds = r.outset(style.borderWidth)
        val fill = style.fill?.let { if (look.night && it.r == 255 && it.g == 255 && it.b == 255) NIGHT_PAPER.withAlpha(it.a) else it }
        val box = when (style.shape) {
            NodeShape.ROUNDED -> ShapeItem(ShapeKind.ROUNDED_RECT, r.topLeft, Pt(r.right, r.bottom), style.border, style.borderWidth, fill).also {
                it.adj = doubleArrayOf((CORNER / max(1.0, min(r.w, r.h))).coerceIn(0.0, 0.5))
            }
            NodeShape.ELLIPSE -> {
                val e = Rect(r.left - r.w * ELLIPSE_GROW, r.top - r.h * ELLIPSE_GROW, r.w * (1 + 2 * ELLIPSE_GROW), r.h * (1 + 2 * ELLIPSE_GROW))
                bounds = bounds.union(e.outset(style.borderWidth))
                ShapeItem(ShapeKind.ELLIPSE, e.topLeft, Pt(e.right, e.bottom), style.border, style.borderWidth, fill)
            }
            NodeShape.UNDERLINE -> null
        }
        if (box != null) {
            parts += MapPiece.Item(box)
        } else {
            // No box: the paper behind the content keeps lines under it from showing through, then the rule.
            fill?.let { parts += MapPiece.Fill(r, it) }
            parts += MapPiece.Item(ShapeItem(ShapeKind.LINE, Pt(r.left, r.bottom), Pt(r.right, r.bottom), style.border, style.borderWidth))
        }
        if (look.titleShown) frame.title?.let { t -> parts += titlePiece(node, t) }
        frame.ink?.let { area ->
            for (stroke in node.ink.strokes) {
                val placed = stroke.copyForWrite().also { it.translate(area.left, area.top) }
                parts += MapPiece.Item(placed)
                bounds = bounds.union(placed.paintBounds())
            }
        }
        chips.remove(node.id)
        frame.sources?.let { row -> parts += chipParts(node, row) }
        if (look.hidden > 0) {
            val badge = badgeRect(r, look.side)
            parts += badgeParts(badge, look.hidden.toString(), style.border, fill ?: if (look.night) NIGHT_PAPER else Rgba(255, 255, 255))
            bounds = bounds.union(badge.outset(EDGE_WIDTH))
        }
        return parts to bounds
    }

    private fun titlePiece(node: MapNode, title: Rect): MapPiece =
        MapPiece.Glyphs(titles.outline(node.title, node.style.textSize, node.style.bold, NodeMetrics.MAX_TITLE_W), node.style.text, title.left, title.top)

    /** Source chips across [row], as many as fit, then "+N" for the rest. */
    private fun chipParts(node: MapNode, row: Rect): List<MapPiece> {
        val out = ArrayList<MapPiece>()
        val placed = ArrayList<Pair<Rect, String?>>()
        var x = row.left
        val sources = node.sources
        for ((i, source) in sources.withIndex()) {
            val rest = sources.size - i - 1
            val label = source.label.ifBlank { "?" }
            var w = min(titles.width(label, CHIP_TEXT) + 2 * CHIP_PAD, CHIP_MAX_W)
            val overflowW = if (rest > 0) titles.width("+$rest", CHIP_TEXT) + 2 * CHIP_PAD + CHIP_GAP else 0.0
            // The first chip always shows, cut (its label ellipsized) to leave room for the "+N" after it.
            if (i == 0) w = max(CHIP_MIN_W, min(w, row.right - x - overflowW))
            if (x + w + overflowW > row.right && i > 0) {
                val more = Rect(x, row.top, titles.width("+${sources.size - i}", CHIP_TEXT) + 2 * CHIP_PAD, row.h)
                out += chip(more, "+${sources.size - i}")
                placed += more to null
                break
            }
            val r = Rect(x, row.top, w, row.h)
            out += chip(r, label)
            placed += r to source.id
            x += w + CHIP_GAP
        }
        chips[node.id] = placed
        return out
    }

    private fun chip(r: Rect, text: String): List<MapPiece> {
        val pill = ShapeItem(ShapeKind.ROUNDED_RECT, r.topLeft, Pt(r.right, r.bottom), CHIP_BORDER, 1.0, CHIP_FILL).also {
            it.adj = doubleArrayOf(0.5)
        }
        val h = titles.lineHeight(CHIP_TEXT)
        val glyphs = titles.outlineLine(titles.fit(text, CHIP_TEXT, r.w - 2 * CHIP_PAD), CHIP_TEXT)
        return listOf(MapPiece.Item(pill), MapPiece.Glyphs(glyphs, CHIP_INK, r.left + CHIP_PAD, r.top + (r.h - h) / 2))
    }

    private fun badgeRect(r: Rect, side: GrowSide): Rect {
        val d = BADGE
        val c = when (side) {
            GrowSide.RIGHT -> Pt(r.right + d / 2 + 2, r.centerY)
            GrowSide.LEFT -> Pt(r.left - d / 2 - 2, r.centerY)
            GrowSide.DOWN -> Pt(r.centerX, r.bottom + d / 2 + 2)
            GrowSide.INDENT -> Pt(r.left + NodeMetrics.PAD_X, r.bottom + d / 2 + 2)
        }
        return Rect(c.x - d / 2, c.y - d / 2, d, d)
    }

    private fun badgeParts(r: Rect, text: String, ink: Rgba, paper: Rgba): List<MapPiece> {
        val w = max(r.w, titles.width(text, BADGE_TEXT) + 8)
        val pill = Rect(r.centerX - w / 2, r.top, w, r.h)
        val shape = ShapeItem(ShapeKind.ROUNDED_RECT, pill.topLeft, Pt(pill.right, pill.bottom), ink, EDGE_WIDTH, paper).also {
            it.adj = doubleArrayOf(0.5)
        }
        val tw = titles.width(text, BADGE_TEXT)
        val th = titles.lineHeight(BADGE_TEXT)
        return listOf(MapPiece.Item(shape), MapPiece.Glyphs(titles.outlineLine(text, BADGE_TEXT), ink, pill.centerX - tw / 2, pill.centerY - th / 2))
    }

    private fun edgeItem(look: EdgeLook): ConnectorItem {
        val e = look.edge
        val elbow = when (e.side) {
            GrowSide.RIGHT, GrowSide.LEFT -> Elbow(Axis.H, doubleArrayOf((e.from.x + e.to.x) / 2))
            GrowSide.DOWN -> Elbow(Axis.V, doubleArrayOf((e.from.y + e.to.y) / 2))
            GrowSide.INDENT -> Elbow(Axis.V, doubleArrayOf())
        }
        val c = ConnectorItem(look.kind, ConnectorEnd(e.from), ConnectorEnd(e.to), look.color, look.width)
        c.elbow = elbow
        return c
    }

    private fun relationItem(look: RelationLook): ConnectorItem? {
        val (a, b) = relationEnds(look.from, look.to) ?: return null
        val r = look.relation
        val c = ConnectorItem(
            Routing.STRAIGHT, ConnectorEnd(a), ConnectorEnd(b), r.color, RELATION_WIDTH,
            headStart = if (r.arrow == RelationArrow.BACKWARD || r.arrow == RelationArrow.BOTH) Head.FILLED else Head.NONE,
            headEnd = if (r.arrow == RelationArrow.FORWARD || r.arrow == RelationArrow.BOTH) Head.FILLED else Head.NONE,
            dashed = true, dashLength = RELATION_DASH, dashGap = RELATION_GAP,
        )
        if (r.label.isNotBlank()) {
            c.label = ShapeLabel(r.label, TextStyle(r.color, RELATION_LABEL_PT, MapTitles.FACE), labels)
        }
        return c
    }

    companion object {
        const val EDGE_WIDTH = 2.0
        const val RELATION_WIDTH = 2.0
        const val RELATION_DASH = 10.0
        const val RELATION_GAP = 7.0
        const val RELATION_LABEL_PT = 9.0
        const val CORNER = 10.0
        const val ELLIPSE_GROW = 0.12
        const val BADGE = 24.0
        const val BADGE_TEXT = 15.0
        const val CHIP_TEXT = 15.0
        const val CHIP_PAD = 8.0
        const val CHIP_GAP = 6.0
        const val CHIP_MAX_W = 200.0
        const val CHIP_MIN_W = 2 * CHIP_PAD + 12.0
        val CHIP_FILL = Rgba(238, 238, 238)
        val CHIP_BORDER = Rgba(170, 170, 170)
        val CHIP_INK = Rgba(60, 60, 60)

        /** Night paper as content draws it: one step off exact black, which night ink would turn white. */
        val NIGHT_PAPER = Rgba(1, 1, 1)

        /**
         * Where a relation line meets its two boxes: on the line between their centres, cut at each
         * box's edge. Null when the boxes overlap so much there is no line to draw between them.
         */
        fun relationEnds(from: Rect, to: Rect): Pair<Pt, Pt>? {
            val a = from.center
            val b = to.center
            val pa = exit(from, a, b)
            val pb = exit(to, b, a)
            if (pa.distanceTo(pb) < 1.0 || to.contains(pa) || from.contains(pb)) return null
            return pa to pb
        }

        /** Where the ray from [inside] toward [toward] leaves [r]. */
        private fun exit(r: Rect, inside: Pt, toward: Pt): Pt {
            val dx = toward.x - inside.x
            val dy = toward.y - inside.y
            if (abs(dx) < 1e-9 && abs(dy) < 1e-9) return inside
            val tx = if (abs(dx) < 1e-9) Double.MAX_VALUE else (if (dx > 0) r.right - inside.x else r.left - inside.x) / dx
            val ty = if (abs(dy) < 1e-9) Double.MAX_VALUE else (if (dy > 0) r.bottom - inside.y else r.top - inside.y) / dy
            val t = min(tx, ty).coerceIn(0.0, 1.0)
            return Pt(inside.x + dx * t, inside.y + dy * t)
        }

        fun distanceToSegment(p: Pt, a: Pt, b: Pt): Double {
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len2 = dx * dx + dy * dy
            if (len2 < 1e-12) return p.distanceTo(a)
            val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
            return p.distanceTo(Pt(a.x + dx * t, a.y + dy * t))
        }
    }
}
