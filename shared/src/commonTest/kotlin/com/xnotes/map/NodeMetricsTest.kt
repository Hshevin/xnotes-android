package com.xnotes.map

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.testkit.assertEquals
import com.xnotes.core.testkit.assertNull
import kotlin.test.Test

/** [FakeTitles]: a title is 10 per character wide (capped at the wrap width) and 24 tall. */
class NodeMetricsTest {

    private val titles = FakeTitles()
    private val metrics = NodeMetrics(titles)
    private val at = Pt(1000.0, 500.0)

    private fun node(title: String = "", ink: NodeInk = NodeInk.NONE, sources: Int = 0) = MapNode(
        NodeId("n"), title, NodeStyle.TOPIC, ink,
        List(sources) { SourceRef("s$it", SourceKind.NOTE_PAGE, "doc", "p$it", "Doc · $it") },
    )

    @Test fun emptyNodeIsOneEmptyLineAtMinimumWidth() {
        val f = metrics.frame(node(), isRoot = false, at = at)
        assertRect(Rect(1000.0, 500.0, 80.0, 44.0), f.rect)
        assertRect(Rect(1016.0, 510.0, 0.0, 24.0), f.title)
        assertNull(f.ink)
        assertNull(f.sources)
    }

    @Test fun rootsHaveALargerMinimumWidth() {
        assertEquals(Size(120.0, 44.0), metrics.size(node(), isRoot = true))
    }

    @Test fun titleWidensTheNodePastTheMinimum() {
        assertEquals(Size(82.0, 44.0), metrics.size(node("Hello"), isRoot = false))
    }

    @Test fun titlesWrapAtTheMaximumWidth() {
        val f = metrics.frame(node("x".repeat(100)), isRoot = false, at = at)
        assertEquals(320.0 + 32.0, f.rect.w, 1e-9)
        assertEquals(NodeMetrics.MAX_TITLE_W, titles.calls.last().third, 1e-9)
    }

    @Test fun titleIsMeasuredAtTheNodesTextSize() {
        metrics.frame(node("abc").copy(style = NodeStyle.TOPIC.copy(textSize = 30.0)), isRoot = false, at = at)
        assertEquals(30.0, titles.calls.last().second, 1e-9)
    }

    @Test fun inkAreaSitsBelowTheTitle() {
        val f = metrics.frame(node("Hello", NodeInk(200.0, 100.0, emptyList())), isRoot = false, at = at)
        assertRect(Rect(1000.0, 500.0, 232.0, 10.0 + 24.0 + 8.0 + 100.0 + 10.0), f.rect)
        assertRect(Rect(1016.0, 542.0, 200.0, 100.0), f.ink)
    }

    @Test fun blankTitleWithInkShowsNoTitleLine() {
        val f = metrics.frame(node("", NodeInk(200.0, 100.0, emptyList())), isRoot = false, at = at)
        assertNull(f.title)
        assertRect(Rect(1016.0, 510.0, 200.0, 100.0), f.ink)
        assertEquals(120.0, f.rect.h, 1e-9)
    }

    @Test fun sourceRowGoesLastAndDoesNotWiden() {
        val f = metrics.frame(node("Hi", sources = 5), isRoot = false, at = at)
        assertRect(Rect(1000.0, 500.0, 80.0, 10.0 + 24.0 + 8.0 + 22.0 + 10.0), f.rect)
        assertRect(Rect(1016.0, 542.0, 48.0, 22.0), f.sources)
    }

    @Test fun allThreePartsStack() {
        val f = metrics.frame(node("Hi", NodeInk(160.0, 80.0, emptyList()), sources = 1), isRoot = false, at = Pt(0.0, 0.0))
        assertRect(Rect(16.0, 10.0, 20.0, 24.0), f.title)
        assertRect(Rect(16.0, 42.0, 160.0, 80.0), f.ink)
        assertRect(Rect(16.0, 130.0, 160.0, 22.0), f.sources)
        assertRect(Rect(0.0, 0.0, 192.0, 162.0), f.rect)
    }

    @Test fun sizeMatchesFrame() {
        val n = node("Hello", NodeInk(200.0, 100.0, emptyList()), 2)
        val f = metrics.frame(n, isRoot = false, at = at)
        assertEquals(Size(f.rect.w, f.rect.h), metrics.size(n, isRoot = false))
    }
}
