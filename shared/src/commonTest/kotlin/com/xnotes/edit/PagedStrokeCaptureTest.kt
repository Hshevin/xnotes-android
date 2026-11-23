package com.xnotes.edit

import com.xnotes.core.geometry.Pt
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * W3-B contract (docs/movinkpad/process/ipados-wave3-contracts-2026-11-24.md, "W3-B"): the pen-to-stroke
 * capture that Android's InteractionController does today, lifted into shared code so the iPad and Android
 * build strokes the same way. Behaviour must match the Android code at commit 73f664f0 exactly.
 */
class PagedStrokeCaptureTest {

    /** Pages 100 × 100 stacked vertically from y = 0 with a 20 px gap: page n starts at y = 120 n. */
    private class Stack(override var zoom: Double = 1.0, val pages: Int = 3, val readOnly: Set<Int> = emptySet()) : PageSpace {
        override fun pageAt(content: Pt): Int? {
            if (content.x !in 0.0..100.0) return null
            val n = (content.y / 120.0).toInt()
            val inPage = content.y - n * 120.0
            return if (n in 0 until pages && content.y >= 0.0 && inPage <= 100.0) n else null
        }
        override fun toPage(page: Int, content: Pt) = Pt(content.x, content.y - page * 120.0)
        override fun fromPage(page: Int, local: Pt) = Pt(local.x, local.y + page * 120.0)
        override fun hasPage(page: Int) = page in 0 until pages
        override fun writable(page: Int) = page !in readOnly
    }

    private val pen = ToolDefaults.configFor(Tool.PEN)

    private fun capture(space: Stack = Stack()) = PagedStrokeCapture(space)

    @Test
    fun aPressOffThePaperOrOnALockedPageStartsNothing() {
        val c = capture(Stack(readOnly = setOf(1)))
        assertFalse(c.begin(Pt(50.0, 110.0), 0.5, Tool.PEN, pen, timeMs = 0, speedScale = 1.0)) // in the gap
        assertFalse(c.begin(Pt(50.0, 150.0), 0.5, Tool.PEN, pen, timeMs = 0, speedScale = 1.0)) // locked page 1
        assertFalse(c.active)
        assertNull(c.live)
    }

    @Test
    fun theFirstSampleIsPageLocalAtTimeZero() {
        val c = capture()
        assertTrue(c.begin(Pt(10.0, 130.0), 0.4, Tool.PEN, pen, timeMs = 5_000, speedScale = 1.0))
        assertEquals(1, c.page)
        val s = assertNotNull(c.live).samples.single()
        assertEquals(10.0, s.x)
        assertEquals(10.0, s.y)
        assertEquals(0.4, s.pressure)
        assertEquals(0.0, s.t)
        assertFalse(c.live!!.finished, "a live stroke is unfinished until pen-up")
    }

    @Test
    fun samplesAreDecimatedByOnScreenSpacing() {
        // At zoom 1 the gate is 1 content px (Manhattan); at zoom 2 it is 0.5.
        val c1 = capture(Stack(zoom = 1.0))
        c1.begin(Pt(10.0, 10.0), 0.5, Tool.PEN, pen, 0, 1.0)
        c1.move(Pt(10.3, 10.3), 0.5, 10)
        assertEquals(1, c1.live!!.samples.size)
        c1.move(Pt(10.6, 10.6), 0.5, 20)
        assertEquals(2, c1.live!!.samples.size)

        val c2 = capture(Stack(zoom = 2.0))
        c2.begin(Pt(10.0, 10.0), 0.5, Tool.PEN, pen, 0, 1.0)
        c2.move(Pt(10.3, 10.3), 0.5, 10)
        assertEquals(2, c2.live!!.samples.size)

        // Zoomed out the gate never coarsens past 1 content px.
        val c3 = capture(Stack(zoom = 0.25))
        c3.begin(Pt(10.0, 10.0), 0.5, Tool.PEN, pen, 0, 1.0)
        c3.move(Pt(11.0, 10.0), 0.5, 10)
        assertEquals(2, c3.live!!.samples.size)
    }

    @Test
    fun forcedSamplesAlwaysLand() {
        val c = capture()
        c.begin(Pt(10.0, 10.0), 0.5, Tool.PEN, pen, 0, 1.0)
        c.move(Pt(10.1, 10.0), 0.5, 10, force = true)
        assertEquals(2, c.live!!.samples.size)
    }

    @Test
    fun timeAndPressureAreRelativeAndClamped() {
        val c = capture()
        c.begin(Pt(10.0, 10.0), 0.5, Tool.PEN, pen, 1_000, 1.0)
        c.move(Pt(20.0, 10.0), 1.7, 1_016)
        c.move(Pt(30.0, 10.0), -0.2, 1_032)
        val s = c.live!!.samples
        assertEquals(listOf(0.0, 16.0, 32.0), s.map { it.t })
        assertEquals(listOf(0.5, 1.0, 0.0), s.map { it.pressure })
    }

    @Test
    fun unscaledPensAreNormalisedToTheDrawTimeZoom() {
        val c = capture(Stack(zoom = 2.0))
        val fixed = pen.copy(scale = false, baseWidth = 4.0, dashLength = 10.0, dashGap = 8.0)
        c.begin(Pt(10.0, 10.0), 0.5, Tool.PEN, fixed, 0, 1.0)
        val cfg = c.live!!.config
        assertEquals(2.0, cfg.baseWidth)
        assertEquals(5.0, cfg.dashLength)
        assertEquals(4.0, cfg.dashGap)
        assertTrue(cfg.scale, "baked in, so it is ordinary ink afterwards")

        val scaled = capture(Stack(zoom = 2.0))
        scaled.begin(Pt(10.0, 10.0), 0.5, Tool.PEN, pen.copy(baseWidth = 4.0), 0, 1.0)
        assertEquals(4.0, scaled.live!!.config.baseWidth)
    }

    @Test
    fun smoothScaleFollowsZoomInAndIsCapped() {
        assertEquals(0.5, PagedStrokeCapture.smoothScaleFor(2.0))
        assertEquals(1.0, PagedStrokeCapture.smoothScaleFor(0.5))
        assertEquals(1.0, PagedStrokeCapture.smoothScaleFor(0.0))
        assertEquals(1.0, PagedStrokeCapture.smoothScaleFor(Double.NaN))
    }

    @Test
    fun aStraightHighlighterTracksPenDownToNibAndSnapsFlat() {
        val hl = ToolDefaults.configFor(Tool.HIGHLIGHTER).copy(straightLine = true)
        val c = capture()
        c.begin(Pt(10.0, 50.0), 0.5, Tool.HIGHLIGHTER, hl, 0, 1.0)
        assertTrue(c.live!!.straight)
        c.move(Pt(40.0, 52.0), 0.5, 10)
        c.move(Pt(80.0, 51.0), 0.5, 20)
        val s = c.live!!.samples
        assertEquals(2, s.size)
        assertEquals(80.0, s[1].x, 1e-9)
        assertEquals(50.0, s[1].y, 1e-9) // snapped to the axis

        val ruled = capture()
        ruled.begin(Pt(10.0, 50.0), 0.5, Tool.HIGHLIGHTER, hl, 0, 1.0)
        ruled.move(Pt(80.0, 51.0), 0.5, 10, rulerVisible = true)
        assertEquals(51.0, ruled.live!!.samples[1].y, 1e-9) // the ruler steers instead
    }

    @Test
    fun aStraightLineNeverHandsOverToTheNextPage() {
        val hl = ToolDefaults.configFor(Tool.HIGHLIGHTER).copy(straightLine = true)
        val c = capture()
        c.begin(Pt(10.0, 90.0), 0.5, Tool.HIGHLIGHTER, hl, 0, 1.0)
        assertNull(c.move(Pt(10.0, 130.0), 0.5, 10))
        assertEquals(0, c.page)
        assertEquals(1, c.finish().size)
    }

    @Test
    fun crossingOntoAnotherPageHandsTheStrokeOverWithABridge() {
        val c = capture()
        c.begin(Pt(50.0, 80.0), 0.5, Tool.PEN, pen, 0, 1.0)
        c.move(Pt(50.0, 95.0), 0.6, 10)
        val first = c.live
        val handover = assertNotNull(c.move(Pt(50.0, 125.0), 0.7, 20))
        assertSame(first, handover.retired)
        assertEquals(0, handover.fromPage)
        assertEquals(1, handover.toPage)
        assertTrue(handover.retired.finished)
        assertEquals(1, c.page)

        val next = c.live!!
        assertNotEquals(first, next)
        val (bridge, landed) = next.samples
        assertEquals(50.0, bridge.x, 1e-9)
        assertEquals(95.0 - 120.0, bridge.y, 1e-9) // the last point, in page 1's space
        assertEquals(0.6, bridge.pressure)
        assertEquals(10.0, bridge.t)
        assertEquals(5.0, landed.y, 1e-9)
        assertEquals(0.7, landed.pressure)

        val segments = c.finish()
        assertEquals(listOf(0, 1), segments.map { it.page })
        assertSame(first, segments[0].stroke)
        assertSame(next, segments[1].stroke)
        assertTrue(segments.all { it.stroke.finished })
        assertFalse(c.active)
    }

    @Test
    fun movingIntoTheGapKeepsDrawingOnTheCurrentPage() {
        val c = capture()
        c.begin(Pt(50.0, 90.0), 0.5, Tool.PEN, pen, 0, 1.0)
        assertNull(c.move(Pt(50.0, 110.0), 0.5, 10))
        assertEquals(0, c.page)
        assertEquals(110.0, c.live!!.samples.last().y, 1e-9)
    }

    @Test
    fun aStabilisedStrokeLagsTheNibAndShowsItAsATip() {
        val steady = pen.copy(stabilize = true)
        val c = capture()
        c.begin(Pt(10.0, 10.0), 0.5, Tool.PEN, steady, 0, 1.0)
        c.move(Pt(60.0, 10.0), 0.5, 8)
        val stroke = c.live!!
        assertTrue(stroke.samples.last().x < 60.0, "the filter has not reached the nib yet")
        assertEquals(60.0, assertNotNull(stroke.tip).x, 1e-9)
    }

    @Test
    fun cancelHandsBackEverySegmentAndResets() {
        val c = capture()
        c.begin(Pt(50.0, 80.0), 0.5, Tool.PEN, pen, 0, 1.0)
        c.move(Pt(50.0, 125.0), 0.5, 10)
        val segments = c.cancel()
        assertEquals(listOf(0, 1), segments.map { it.page })
        assertFalse(c.active)
        assertNull(c.live)
        assertNull(c.page)
    }

    @Test
    fun movesWithoutABeginAreIgnored() {
        val c = capture()
        assertNull(c.move(Pt(10.0, 10.0), 0.5, 0))
        assertTrue(c.finish().isEmpty())
    }

    @Test
    fun theSampleGateConstantMatchesAndroid() {
        assertEquals(1.0, PagedStrokeCapture.MIN_SAMPLE_DIST)
    }
}
