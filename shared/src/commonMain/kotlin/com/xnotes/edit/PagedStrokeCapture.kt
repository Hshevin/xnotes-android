package com.xnotes.edit

import com.xnotes.canvas.ResizeMath
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.stroke.Stabilizer
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig

interface PageSpace {
    val zoom: Double
    fun pageAt(content: Pt): Int?
    fun toPage(page: Int, content: Pt): Pt
    fun fromPage(page: Int, local: Pt): Pt
    fun hasPage(page: Int): Boolean
    fun writable(page: Int): Boolean
}

class PagedStrokeCapture(private val space: PageSpace) {
    data class Segment(val stroke: Stroke, val page: Int)
    data class Handover(val retired: Stroke, val fromPage: Int, val toPage: Int)

    private var segments: MutableList<Segment>? = null
    private var startTimeMs = 0L
    private var steadier: Stabilizer? = null

    val active: Boolean get() = segments != null
    val live: Stroke? get() = segments?.last()?.stroke
    val page: Int? get() = segments?.last()?.page

    fun writablePageAt(content: Pt): Int? = space.pageAt(content)?.takeIf(space::writable)

    fun begin(content: Pt, pressure: Double, tool: Tool, config: ToolConfig, timeMs: Long, speedScale: Double): Boolean {
        val downPage = writablePageAt(content) ?: return false
        beginOnPage(downPage, content, pressure, tool, config, timeMs, speedScale)
        return true
    }

    /** The host can hit-test with [writablePageAt] before a ruler moves the first sample off the paper. */
    fun beginOnPage(downPage: Int, content: Pt, pressure: Double, tool: Tool, config: ToolConfig, timeMs: Long, speedScale: Double): Stroke {
        val zoom = space.zoom
        val ink = if (config.scale) config else config.copy(
            baseWidth = config.baseWidth / zoom,
            dashLength = config.dashLength / zoom,
            dashGap = config.dashGap / zoom,
            scale = true,
        )
        val straight = tool == Tool.HIGHLIGHTER && ink.straightLine
        val stroke = Stroke(tool, ink, speedScale = speedScale, straight = straight, smoothScale = smoothScaleFor(zoom))
        stroke.finished = false
        val first = space.toPage(downPage, content)
        stroke.addSample(Sample(first.x, first.y, pressure))
        startTimeMs = timeMs
        steadier = Stabilizer.forStroke(ink.stabilize && !straight, ink.stabilizeTrail, ink.stabilizeCrossover, speedScale)
        steadier?.start(content.x, content.y, 0.0)
        segments = mutableListOf(Segment(stroke, downPage))
        return stroke
    }

    fun move(content: Pt, pressure: Double, timeMs: Long, force: Boolean = false, rulerVisible: Boolean = false): Handover? {
        val pieces = segments ?: return null
        val (stroke, currentPage) = pieces.last()
        if (!space.hasPage(currentPage)) return null
        val nib = content
        var time = (timeMs - startTimeMs).toDouble()
        val steady = steadier
        val point = when {
            steady == null -> nib
            force -> {
                if (steady.moved) time = steady.tipTime
                nib
            }
            else -> {
                steady.step(nib.x, nib.y, time)
                Pt(steady.x, steady.y)
            }
        }
        val clamped = pressure.coerceIn(0.0, 1.0)
        if (stroke.straight) {
            val local = space.toPage(currentPage, point)
            val start = stroke.samples.firstOrNull()
            val end = if (start != null && !rulerVisible) ResizeMath.snapAxis(start.pos, local) else local
            stroke.setStraightEnd(Sample(end.x, end.y, clamped, time))
            return null
        }

        val onPage = space.pageAt(point)
        val handover = if (onPage != null && onPage != currentPage) {
            val last = stroke.samples.lastOrNull()
            stroke.finished = true
            val next = Stroke(stroke.tool, stroke.config, speedScale = stroke.speedScale, smoothScale = stroke.smoothScale)
            next.finished = false
            if (last != null) {
                val bridge = space.toPage(onPage, space.fromPage(currentPage, last.pos))
                next.addSample(Sample(bridge.x, bridge.y, last.pressure, last.t))
            }
            val local = space.toPage(onPage, point)
            next.addSample(Sample(local.x, local.y, clamped, time))
            pieces.add(Segment(next, onPage))
            Handover(stroke, currentPage, onPage)
        } else {
            val local = space.toPage(currentPage, point)
            val last = stroke.samples.lastOrNull()
            val gate = (MIN_SAMPLE_DIST / space.zoom).coerceAtMost(MIN_SAMPLE_DIST)
            if (force || last == null || last.pos.manhattanTo(local) >= gate) {
                stroke.addSample(Sample(local.x, local.y, clamped, time))
            }
            null
        }
        if (steady != null && !force) {
            val current = pieces.last()
            val tip = space.toPage(current.page, nib)
            current.stroke.setTip(Sample(tip.x, tip.y, clamped, steady.tipTime))
        }
        return handover
    }

    fun finish(): List<Segment> {
        live?.finished = true
        return cancel()
    }

    fun cancel(): List<Segment> {
        val captured = segments ?: return emptyList()
        segments = null
        steadier = null
        return captured
    }

    companion object {
        const val MIN_SAMPLE_DIST = 1.0

        fun smoothScaleFor(zoom: Double): Double =
            if (zoom.isFinite() && zoom > 0.0) (1.0 / zoom).coerceAtMost(1.0) else 1.0
    }
}
