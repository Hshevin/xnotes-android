package com.xnotes.map

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke

/*
 * Mind map v2 data contract (docs/movinkpad/mind-map/mind-map-v2-design-2026-11-21.md).
 *
 * Everything in this file is immutable and compared by value, so a [MapState] can be kept whole as
 * an undo snapshot and two states can be compared in tests. The only exception is [Stroke], which is
 * a mutable canvas item: strokes stored in a [NodeInk] are owned by the state and must never be
 * mutated after they are stored (copy, never translate in place).
 */

/** A node's stable identity inside one canvas. Never reused, never derived from position or title. */
@kotlin.jvm.JvmInline
value class NodeId(val value: String) {
    override fun toString() = value
}

/** A relation line's stable identity inside one canvas. */
@kotlin.jvm.JvmInline
value class RelationId(val value: String) {
    override fun toString() = value
}

/** A free 2-D offset, in canvas units. */
data class Vec(val dx: Double, val dy: Double) {
    operator fun plus(o: Vec) = Vec(dx + o.dx, dy + o.dy)
    val isZero: Boolean get() = dx == 0.0 && dy == 0.0

    companion object {
        val ZERO = Vec(0.0, 0.0)
    }
}

/** A width and a height, in canvas units. */
data class Size(val w: Double, val h: Double) {
    companion object {
        val ZERO = Size(0.0, 0.0)
    }
}

/** How one tree arranges its descendants. Set per root; children inherit their root's kind. */
enum class LayoutKind(val id: String) {
    /** Logic chart: every child to the right of its parent, siblings stacked top to bottom. */
    RIGHT("right"),

    /** Classic mind map: the root's children split between right (first half) and left (rest); deeper levels continue outward on their side. */
    BALANCED("balanced"),

    /** Org chart: children below their parent, siblings side by side left to right. */
    ORG("org"),

    /** Indented tree: each child below its parent and indented to the right, siblings stacked top to bottom like an outline. */
    INDENT("indent");

    companion object {
        fun fromId(id: String?): LayoutKind? = entries.firstOrNull { it.id == id }
    }
}

/** The outline a node draws. */
enum class NodeShape(val id: String) {
    ROUNDED("rounded"),
    ELLIPSE("ellipse"),

    /** No box: only a line under the content. */
    UNDERLINE("underline");

    companion object {
        fun fromId(id: String?): NodeShape? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A node's look. Colours are content (like ink), not theme, so they are stored as chosen.
 * [fill] null draws no fill.
 */
data class NodeStyle(
    val shape: NodeShape = NodeShape.ROUNDED,
    val border: Rgba = Rgba(0, 0, 0),
    val fill: Rgba? = Rgba(255, 255, 255),
    val text: Rgba = Rgba(0, 0, 0),
    val borderWidth: Double = 2.0,
    /** Title text size in canvas units; the default matches a canvas text box's 13 pt. */
    val textSize: Double = 27.0,
    val bold: Boolean = false,
) {
    companion object {
        /** The style a new root gets. */
        val ROOT = NodeStyle(borderWidth = 3.0, textSize = 34.0, bold = true)

        /** The style a new child or sibling gets. */
        val TOPIC = NodeStyle()
    }
}

/**
 * The handwriting area under a node's title. [width] × [height] is the area's size; [strokes] are in
 * area-local coordinates (0,0 = the area's top-left); ink written over the title or in the node's
 * padding lies outside `[0, width] × [0, height]`, cut at the node's box. [NONE] means the node has no ink area.
 */
data class NodeInk(val width: Double, val height: Double, val strokes: List<Stroke>) {
    val isEmpty: Boolean get() = strokes.isEmpty() && width == 0.0 && height == 0.0

    override fun equals(other: Any?): Boolean =
        other is NodeInk && other.width == width && other.height == height &&
            other.strokes.size == strokes.size && other.strokes.indices.all { other.strokes[it] === strokes[it] }

    override fun hashCode(): Int = (width.hashCode() * 31 + height.hashCode()) * 31 + strokes.size

    companion object {
        val NONE = NodeInk(0.0, 0.0, emptyList())
    }
}

/** What a source points at. Resolution (phase 3) turns it into a target that can be opened. */
enum class SourceKind(val id: String) {
    /** A page of a paged note, by its permanent page id. */
    NOTE_PAGE("note_page"),

    /** A page of a PDF-backed note, by its permanent page id. */
    PDF_PAGE("pdf_page"),

    /** A whole canvas document. [SourceRef.pageId] is null. */
    CANVAS("canvas");

    companion object {
        fun fromId(id: String?): SourceKind? = entries.firstOrNull { it.id == id }
    }
}

/**
 * One link from a node to another document. [documentId] is the target's document UUID and
 * [pageId] its permanent page id (null for [SourceKind.CANVAS]). [label] is the display text cached
 * when the link was made (file name and page number); it is only a hint and never used to resolve.
 */
data class SourceRef(
    val id: String,
    val kind: SourceKind,
    val documentId: String,
    val pageId: String?,
    val label: String,
)

/**
 * One node's content. Its position lives in the tree ([MapState.rootOrigin], [MapState.offsets]), not here.
 *
 * [width] is the node box's width the person set by resizing it, in canvas units; null sizes the
 * box to its content (see [NodeMetrics]). Its height always follows the content, so a resized
 * node's height is carried by its ink area's height ([NodeMetrics.resized]).
 */
data class MapNode(
    val id: NodeId,
    val title: String = "",
    val style: NodeStyle = NodeStyle.TOPIC,
    val ink: NodeInk = NodeInk.NONE,
    val sources: List<SourceRef> = emptyList(),
    val width: Double? = null,
)

/** Arrowheads on a relation line, relative to its [Relation.from] → [Relation.to] direction. */
enum class RelationArrow(val id: String) {
    NONE("none"),
    FORWARD("forward"),
    BACKWARD("backward"),
    BOTH("both");

    companion object {
        fun fromId(id: String?): RelationArrow? = entries.firstOrNull { it.id == id }
    }
}

/** An ordinary line between two nodes. It never changes the hierarchy. */
data class Relation(
    val id: RelationId,
    val from: NodeId,
    val to: NodeId,
    val label: String = "",
    val arrow: RelationArrow = RelationArrow.NONE,
    val color: Rgba = Rgba(0, 0, 0),
)

/**
 * The whole content of all mind maps on one canvas, as one immutable value.
 *
 * Invariants ([MapInvariants.check] reports violations):
 * - every node is stored under its own id ([nodes] key == [MapNode.id]), and every relation under its
 *   own id;
 * - every id in [roots], [parent] keys/values, [children] keys/values, [rootOrigin], [layout],
 *   [offsets] and every relation end is a key of [nodes];
 * - every node is either a root (in [roots], absent from [parent]) or a child (key of [parent],
 *   absent from [roots]), never both and never neither;
 * - [children] lists exactly the nodes whose parent is the key, each once, in sibling order; a node
 *   without children may be absent from [children] or map to an empty list;
 * - following [parent] from any node reaches a root (no cycles);
 * - [rootOrigin] and [layout] have an entry for every root and for nothing else;
 * - [offsets] has entries only for children (never for roots) and never a zero offset;
 * - [layers] has entries only for roots;
 * - every node's [MapNode.width] is null or a finite number above zero;
 * - relations connect two different nodes, and no two relations share the same unordered pair.
 */
data class MapState(
    val nodes: Map<NodeId, MapNode> = emptyMap(),
    /** Roots in creation order. */
    val roots: List<NodeId> = emptyList(),
    /** child → parent. */
    val parent: Map<NodeId, NodeId> = emptyMap(),
    /** parent → children in sibling order. */
    val children: Map<NodeId, List<NodeId>> = emptyMap(),
    /** Each root's top-left corner on the canvas. */
    val rootOrigin: Map<NodeId, Pt> = emptyMap(),
    /** Each root's layout kind. */
    val layout: Map<NodeId, LayoutKind> = emptyMap(),
    /** A child's manual nudge away from its automatic place. */
    val offsets: Map<NodeId, Vec> = emptyMap(),
    /** Each root's canvas layer id; a root without an entry sits on the canvas's default layer. Its whole tree shares it. */
    val layers: Map<NodeId, String> = emptyMap(),
    val relations: Map<RelationId, Relation> = emptyMap(),
) {
    fun childrenOf(id: NodeId): List<NodeId> = children[id].orEmpty()

    fun rootOf(id: NodeId): NodeId {
        var cur = id
        while (true) cur = parent[cur] ?: return cur
    }

    val isEmpty: Boolean get() = nodes.isEmpty()

    companion object {
        val EMPTY = MapState()
    }
}

/** A node's laid-out box and where its incoming tree line attaches. */
data class NodePlacement(
    val id: NodeId,
    /** The node's box on the canvas. */
    val rect: Rect,
    /** Which way this node's subtree grows from it, for drawing tree lines and placing the + handles. */
    val side: GrowSide,
    /** Depth in its tree; the root is 0. */
    val depth: Int,
)

/** The direction a node's children are placed in relative to the node. */
enum class GrowSide { RIGHT, LEFT, DOWN, INDENT }

/** A tree line from a parent to one of its children. Points are on the canvas. */
data class TreeEdge(val parent: NodeId, val child: NodeId, val from: Pt, val to: Pt, val side: GrowSide)
