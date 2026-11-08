package com.xnotes.mindmap

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.ui.theme.MinTouchTarget
import com.xnotes.ui.theme.Space
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** The geometry ordinary relations paint and hit. Hierarchy lines never enter this list. */
internal object MapRelationGeometry {
    data class Segment(val start: Pt, val end: Pt)
    data class Line(val id: String, val start: Pt, val end: Pt,
        val wings: List<Segment> = emptyList(), val labelBounds: Rect? = null) {
        val labelAnchor get() = Pt((start.x + end.x) / 2, (start.y + end.y) / 2 - Space.s8.value)
    }

    fun visible(doc: MindMapDocument, labelBounds: (MapRelation, Pt) -> Rect? = { _, _ -> null }): List<Line> {
        val nodes = doc.visible().associateBy { it.id }
        return doc.relations.mapNotNull { edge ->
            val from = nodes[edge.from] ?: return@mapNotNull null
            val to = nodes[edge.to] ?: return@mapNotNull null
            val start = from.bounds.center
            val end = to.bounds.center
            val dx = end.x - start.x; val dy = end.y - start.y
            val factor = minOf(if (dx == 0.0) Double.POSITIVE_INFINITY else to.width / 2 / abs(dx),
                if (dy == 0.0) Double.POSITIVE_INFINITY else to.height / 2 / abs(dy))
            val wings = if (edge.directed && factor.isFinite()) {
                val tip = Pt(end.x - dx * factor, end.y - dy * factor)
                val angle = atan2(dy, dx)
                listOf(-1, 1).map { sign -> Segment(tip, Pt(tip.x - 14 * cos(angle + sign * .45),
                    tip.y - 14 * sin(angle + sign * .45))) }
            } else emptyList()
            val line = Line(edge.id, start, end, wings)
            line.copy(labelBounds = labelBounds(edge, line.labelAnchor))
        }
    }

    /** A 48 dp screen target, independent of density and camera zoom. */
    fun hitRadius(density: Double, contentScale: Double): Double =
        MinTouchTarget.value * density / 2 / contentScale

    /** Nearest first, then last painted first. Other overlapping relations remain in the list. */
    fun hits(lines: List<Line>, point: Pt, radius: Double): List<String> {
        if (!point.x.isFinite() || !point.y.isFinite() || !radius.isFinite() || radius < 0) return emptyList()
        return lines.mapIndexedNotNull { index, line ->
            val stroke = distance(point, line.start, line.end)
            val arrow = line.wings.minOfOrNull { distance(point, it.start, it.end) } ?: Double.POSITIVE_INFINITY
            val label = line.labelBounds?.distanceTo(point) ?: Double.POSITIVE_INFINITY
            val near = minOf(stroke, arrow, label)
            if (near <= radius) Triple(line.id, near, index) else null
        }.sortedWith(compareBy<Triple<String, Double, Int>> { it.second }.thenByDescending { it.third }).map { it.first }
    }

    private fun distance(point: Pt, start: Pt, end: Pt): Double {
        val dx = end.x - start.x; val dy = end.y - start.y
        val length = dx * dx + dy * dy
        if (!length.isFinite()) return Double.POSITIVE_INFINITY
        if (length == 0.0) return hypot(point.x - start.x, point.y - start.y)
        val t = (((point.x - start.x) * dx + (point.y - start.y) * dy) / length).coerceIn(0.0, 1.0)
        return hypot(point.x - start.x - t * dx, point.y - start.y - t * dy)
    }
}
