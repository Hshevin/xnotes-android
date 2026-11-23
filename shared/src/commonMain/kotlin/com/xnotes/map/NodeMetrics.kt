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
 *    empty line, so a new node is not zero-height). Measured with [TitleMeasure] at [MAX_TITLE_W].
 * 2. Ink area, when [MapNode.ink] is not [NodeInk.NONE]: exactly `ink.width × ink.height`, preceded
 *    by [GAP] when a title line is shown.
 * 3. Source chip row, when there are sources: [CHIP_H] tall, preceded by [GAP] when anything is above.
 *
 * Then [PAD_Y]. Parts are left-aligned at `left + PAD_X`; the title box is exactly the measured
 * size. Width = max(min width, widest of title / ink + 2·[PAD_X]); min width is [ROOT_MIN_W] for roots,
 * [MIN_W] otherwise. The chip row does not widen the node: its box spans the node's inner width
 * (`width − 2·PAD_X`), and chips that do not fit collapse into a "+N" chip (a UI concern).
 */
class NodeMetrics(private val titles: TitleMeasure) : NodeMeasure {

    override fun size(node: MapNode, isRoot: Boolean): Size {
        val r = frame(node, isRoot, Pt(0.0, 0.0)).rect
        return Size(r.w, r.h)
    }

    /** The node's parts with its top-left corner at [at]. */
    fun frame(node: MapNode, isRoot: Boolean, at: Pt): NodeFrame {
        val x = at.x + PAD_X
        var y = PAD_Y
        val title = if (node.title.isNotEmpty() || node.ink.isEmpty) {
            val size = titles.measure(node.title, node.style.textSize, node.style.bold, MAX_TITLE_W)
            Rect(x, at.y + y, size.w, size.h).also { y += size.h }
        } else null
        val ink = if (!node.ink.isEmpty) {
            if (title != null) y += GAP
            Rect(x, at.y + y, node.ink.width, node.ink.height).also { y += it.h }
        } else null
        val minWidth = if (isRoot) ROOT_MIN_W else MIN_W
        val width = maxOf(minWidth, maxOf(title?.w ?: 0.0, node.ink.width) + 2 * PAD_X)
        val sources = if (node.sources.isNotEmpty()) {
            y += GAP
            Rect(x, at.y + y, width - 2 * PAD_X, CHIP_H).also { y += CHIP_H }
        } else null
        return NodeFrame(Rect(at.x, at.y, width, y + PAD_Y), title, ink, sources)
    }

    companion object {
        const val PAD_X = 16.0
        const val PAD_Y = 10.0
        const val GAP = 8.0
        const val CHIP_H = 22.0
        const val MIN_W = 80.0
        const val ROOT_MIN_W = 120.0
        const val MAX_TITLE_W = 320.0
    }
}
