package com.xnotes.map

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect

/** Measures a title: its wrapped size at [maxWidth]. An empty title measures as one empty line. */
fun interface TitleMeasure {
    fun measure(text: String, textSize: Double, bold: Boolean, maxWidth: Double): Size
}

/** Where a node's parts sit, all on the canvas. */
data class NodeFrame(
    val rect: Rect,
    /** The title's box; null when the node shows no title line (empty title with ink). */
    val title: Rect?,
    /** The ink area; null when the node has no ink area. */
    val ink: Rect?,
    /** The row of source chips; null when the node has no sources. */
    val sources: Rect?,
)

/**
 * A node's size and inner layout. Top to bottom inside the box, after [PAD_Y]:
 *
 * 1. Title line(s), shown when the title is non-empty, or when both title and ink are empty (an
 *    empty line, so a new node is not zero-height). Measured with [TitleMeasure] at the wrap width:
 *    [MAX_TITLE_W] for a node sized to its content, the inner width (`width − 2·PAD_X`) for a
 *    resized one ([MapNode.width] set).
 * 2. Ink area, when [MapNode.ink] is not [NodeInk.NONE]: `inner width × ink.height`, preceded by
 *    [GAP] when a title line is shown. It spans the node's whole inner width, so strokes are cut at
 *    the box's visible inner edges, not at a narrower stored width.
 * 3. Source chip row, when there are sources: [CHIP_H] tall, preceded by [GAP] when anything is above.
 *
 * Then [PAD_Y]. Parts are left-aligned at `left + PAD_X`; the title box is exactly the measured
 * size. The min width is [ROOT_MIN_W] for roots, [MIN_W] otherwise.
 * - Sized to content ([MapNode.width] null): width = max(min width, widest of title / ink.width + 2·[PAD_X]).
 * - Resized: width = max(min width, [MapNode.width], title + 2·[PAD_X]); the stored ink.width does
 *   not widen it.
 *
 * Ink may be written anywhere in the box, over the title and padding too, so the box always holds
 * all of it: with r the union of the strokes' (cut) bounds in area-local coordinates, the width is
 * at least `PAD_X + r.right`, the area starts no higher than `−r.top` below the box top (a title
 * that later wraps to fewer lines leaves a gap rather than ink above the box), and the box reaches
 * at least `r.bottom` below the area top.
 *
 * The chip row does not widen the node: its box spans the node's inner width, and chips that do
 * not fit collapse into a "+N" chip (a UI concern).
 */
class NodeMetrics(private val titles: TitleMeasure) : NodeMeasure {

    override fun size(node: MapNode, isRoot: Boolean): Size {
        val r = frame(node, isRoot, Pt(0.0, 0.0)).rect
        return Size(r.w, r.h)
    }

    /** The node's parts with its top-left corner at [at]. */
    fun frame(node: MapNode, isRoot: Boolean, at: Pt): NodeFrame {
        val minWidth = if (isRoot) ROOT_MIN_W else MIN_W
        val wrapWidth = titleWrap(node, isRoot)
        val reach = inkReach(node)
        val x = at.x + PAD_X
        var y = PAD_Y
        val title = if (node.title.isNotEmpty() || node.ink.isEmpty) {
            val size = titles.measure(node.title, node.style.textSize, node.style.bold, wrapWidth)
            Rect(x, at.y + y, size.w, size.h).also { y += size.h }
        } else null
        val width = maxOf(
            minWidth, node.width ?: (node.ink.width + 2 * PAD_X), (title?.w ?: 0.0) + 2 * PAD_X,
            (reach?.right ?: 0.0) + PAD_X,
        )
        var inkBottom = 0.0
        val ink = if (!node.ink.isEmpty) {
            if (title != null) y += GAP
            if (reach != null) y = maxOf(y, -reach.top)
            inkBottom = y + (reach?.bottom ?: 0.0)
            Rect(x, at.y + y, width - 2 * PAD_X, node.ink.height).also { y += it.h }
        } else null
        val sources = if (node.sources.isNotEmpty()) {
            y += GAP
            Rect(x, at.y + y, width - 2 * PAD_X, CHIP_H).also { y += CHIP_H }
        } else null
        return NodeFrame(Rect(at.x, at.y, width, maxOf(y + PAD_Y, inkBottom)), title, ink, sources)
    }

    /** The union of [node]'s strokes' bounds, area-local; null with none. */
    private fun inkReach(node: MapNode): Rect? {
        var out: Rect? = null
        for (stroke in node.ink.strokes) stroke.bounds().let { b -> out = out?.union(b) ?: b }
        return out
    }

    /** The width [node]'s title wraps at: its inner width once resized, [MAX_TITLE_W] otherwise. */
    fun titleWrap(node: MapNode, isRoot: Boolean): Double =
        node.width?.let { maxOf(it, if (isRoot) ROOT_MIN_W else MIN_W) - 2 * PAD_X } ?: MAX_TITLE_W

    /**
     * [node] resized so its box comes as close to [target] as its content allows. Pure; strokes are
     * kept as the very same instances and never moved or re-encoded.
     *
     * - Width: [MapNode.width] = max(target.w, min width, r.right + [PAD_X]) where r is the union of
     *   the strokes' (cut) bounds (right 0 with none), so drawn ink is never cut by shrinking. The
     *   title then wraps at the new inner width.
     * - Height: the ink area's height is whatever makes the box [target].h tall at that width, but
     *   at least r.bottom less the space the box keeps below the area (0 with none). The box is
     *   never shorter than the title and source row need.
     * - A node without strokes whose ink area would come out lower than [RESIZE_MIN_INK_H] gets no
     *   ink area ([NodeInk.NONE]); otherwise the area is `NodeInk(inner width, height, strokes)`.
     */
    fun resized(node: MapNode, isRoot: Boolean, target: Size): MapNode {
        if (!target.w.isFinite() || !target.h.isFinite()) return node
        val strokes = node.ink.strokes
        val reach = inkReach(node)
        val width = maxOf(target.w, if (isRoot) ROOT_MIN_W else MIN_W, (reach?.right ?: 0.0) + PAD_X)
        // Measured with an empty area, so an empty title consumes no line or gap.
        val bare = frame(node.copy(width = width, ink = NodeInk(width - 2 * PAD_X, 0.0, emptyList())), isRoot, Pt(0.0, 0.0))
        val area = checkNotNull(bare.ink)
        val below = bare.rect.bottom - area.bottom
        val height = maxOf(target.h - bare.rect.h, (reach?.bottom ?: 0.0) - below, 0.0)
        val ink = if (strokes.isEmpty() && height < RESIZE_MIN_INK_H) NodeInk.NONE
            else NodeInk(area.w, height, strokes)
        return node.copy(width = width, ink = ink)
    }

    companion object {
        /** The lowest ink area a resize opens; a shorter one on a node with no strokes is no area at all. */
        const val RESIZE_MIN_INK_H = 24.0
        const val PAD_X = 16.0
        const val PAD_Y = 10.0
        const val GAP = 8.0
        const val CHIP_H = 22.0
        const val MIN_W = 80.0
        const val ROOT_MIN_W = 120.0
        const val MAX_TITLE_W = 320.0
    }
}
