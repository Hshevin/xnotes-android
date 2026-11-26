package com.xnotes.map

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [FakeTitles]: a title is 10 per character wide (capped at the wrap width) and 24 tall. */
class NodeMetricsTest {

    private val titles = FakeTitles()
    private val metrics = NodeMetrics(titles)
    private val at = Pt(1000.0, 500.0)

    private fun node(title: String = "", ink: NodeInk = NodeInk.NONE, sources: Int = 0, width: Double? = null) = MapNode(
        NodeId("n"), title, NodeStyle.TOPIC, ink,
        List(sources) { SourceRef("s$it", SourceKind.NOTE_PAGE, "doc", "p$it", "Doc · $it") },
        width,
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

    // ---- inner width and manual size ----

    @Test fun inkAreaSpansTheInnerWidthUnderAWiderTitle() {
        val f = metrics.frame(node("x".repeat(30), NodeInk(100.0, 50.0, emptyList())), isRoot = false, at = at)
        assertEquals(332.0, f.rect.w, 1e-9)
        assertRect(Rect(1016.0, 542.0, 300.0, 50.0), f.ink)
    }

    @Test fun aResizedNodeWrapsItsTitleAtItsInnerWidth() {
        val f = metrics.frame(node("x".repeat(30), width = 200.0), isRoot = false, at = at)
        assertEquals(168.0, titles.calls.last().third, 1e-9)
        assertEquals(200.0, f.rect.w, 1e-9)
        assertRect(Rect(1016.0, 510.0, 168.0, 24.0), f.title)
    }

    @Test fun aResizedNodeIsNotWidenedByItsStoredInkWidth() {
        val f = metrics.frame(node("Hi", NodeInk(500.0, 60.0, emptyList()), width = 240.0), isRoot = false, at = at)
        assertEquals(240.0, f.rect.w, 1e-9)
        assertRect(Rect(1016.0, 542.0, 208.0, 60.0), f.ink)
    }

    @Test fun aResizedNodeKeepsItsMinimumWidth() {
        assertEquals(80.0, metrics.frame(node("Hi", width = 10.0), isRoot = false, at = at).rect.w, 1e-9)
        assertEquals(120.0, metrics.frame(node("Hi", width = 10.0), isRoot = true, at = at).rect.w, 1e-9)
    }

    @Test fun resizingOpensAnInkAreaThatFillsTheBox() {
        val n = metrics.resized(node("Hi"), isRoot = false, target = Size(300.0, 200.0))
        assertEquals(300.0, n.width!!, 1e-9)
        val f = metrics.frame(n, isRoot = false, at = at)
        assertRect(Rect(1000.0, 500.0, 300.0, 200.0), f.rect)
        assertRect(Rect(1016.0, 542.0, 268.0, 148.0), f.ink)
        assertEquals(268.0, n.ink.width, 1e-9)
        assertEquals(0, n.ink.strokes.size)
    }

    @Test fun resizingTooShortForAnAreaOpensNone() {
        val n = metrics.resized(node("Hi"), isRoot = false, target = Size(300.0, 60.0))
        assertEquals(NodeInk.NONE, n.ink)
        assertEquals(Size(300.0, 44.0), metrics.size(n, isRoot = false))
    }

    @Test fun shrinkingAnEmptyAreaBelowTheMinimumRemovesIt() {
        val n = metrics.resized(node("Hi", NodeInk(268.0, 148.0, emptyList()), width = 300.0), isRoot = false, target = Size(300.0, 70.0))
        assertEquals(NodeInk.NONE, n.ink)
    }

    @Test fun resizingNeverCutsDrawnInk() {
        val stroke = pen(10.0 to 10.0, 150.0 to 100.0)
        val b = stroke.bounds()
        val n = metrics.resized(node("Hi", NodeInk(268.0, 148.0, listOf(stroke)), width = 300.0), isRoot = false, target = Size(10.0, 10.0))
        assertEquals("ink may reach into the right padding", b.right + 16.0, n.width!!, 1e-9)
        assertEquals("and into the bottom padding", b.bottom - NodeMetrics.PAD_Y, n.ink.height, 1e-9)
        assertInside(n, isRoot = false)
        org.junit.Assert.assertSame(stroke, n.ink.strokes.single())
        assertEquals(listOf(10.0, 150.0), stroke.samples.map { it.x })
    }

    @Test fun theBoxAlwaysHoldsInkWrittenOverTheTitle() {
        // Over the title line: PAD_Y + 24 + GAP = 42 above the area.
        val stroke = pen(10.0 to -36.0, 60.0 to -20.0)
        val n = node("Hi", NodeInk(168.0, 80.0, listOf(stroke)), width = 200.0)
        val titled = metrics.frame(n, isRoot = false, at = at)
        assertEquals(42.0, titled.ink!!.top - titled.rect.top, 1e-9)
        // The title is cleared, so no title line shows: the area stays low enough that the ink is still inside.
        val bare = n.copy(title = "")
        val f = metrics.frame(bare, isRoot = false, at = at)
        assertNull(f.title)
        assertEquals(-stroke.bounds().top, f.ink!!.top - f.rect.top, 1e-9)
        assertInside(bare, isRoot = false)
    }

    @Test fun theBoxHoldsInkWrittenOverTheSourceRowAfterTheSourcesGo() {
        val stroke = pen(10.0 to 70.0, 60.0 to 110.0)
        val n = node("Hi", NodeInk(168.0, 80.0, listOf(stroke)), sources = 1, width = 200.0)
        assertInside(n.copy(sources = emptyList()), isRoot = false)
    }

    private fun assertInside(n: MapNode, isRoot: Boolean) {
        val f = metrics.frame(n, isRoot, at)
        val area = f.ink!!
        for (s in n.ink.strokes) {
            val b = s.bounds().translate(area.left, area.top)
            val eps = 1e-6
            org.junit.Assert.assertTrue("$b inside ${f.rect}",
                b.left >= f.rect.left - eps && b.top >= f.rect.top - eps && b.right <= f.rect.right + eps && b.bottom <= f.rect.bottom + eps)
        }
    }

    @Test fun resizingKeepsTheSourceRowInsideTheBox() {
        val n = metrics.resized(node("Hi", sources = 2), isRoot = false, target = Size(260.0, 240.0))
        val f = metrics.frame(n, isRoot = false, at = at)
        assertRect(Rect(1000.0, 500.0, 260.0, 240.0), f.rect)
        assertEquals(f.rect.bottom - NodeMetrics.PAD_Y, f.sources!!.bottom, 1e-9)
    }

    @Test fun resizingARootKeepsTheRootMinimum() {
        assertEquals(120.0, metrics.resized(node("Hi"), isRoot = true, target = Size(10.0, 10.0)).width!!, 1e-9)
    }

    @Test fun resizingAnInkOnlyNodeFillsTheBoxWithInk() {
        val n = metrics.resized(node("", NodeInk(160.0, 80.0, emptyList())), isRoot = false, target = Size(300.0, 200.0))
        val f = metrics.frame(n, isRoot = false, at = at)
        assertNull(f.title)
        assertRect(Rect(1016.0, 510.0, 268.0, 180.0), f.ink)
    }
}
