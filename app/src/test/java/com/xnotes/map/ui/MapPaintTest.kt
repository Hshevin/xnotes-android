package com.xnotes.map.ui

import com.xnotes.core.FakeRenderer
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.map.EditorFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The map drawn away from its editor: file tiles, page thumbnails and previews. */
class MapPaintTest {

    private val measurer = FakeTextMeasurer()
    private val f = EditorFixture(MapTitles(measurer))

    private fun doc() = InfiniteDocument().also { it.map = f.model }

    @Test fun anEmptyMapAddsNothing() {
        assertTrue(MapPaint.parts(doc(), measurer).isEmpty())
    }

    @Test fun everyShownPartIsPaintedByTheCpuRenderer() {
        val r = f.root("r")
        f.child(r, "a")
        val parts = MapPaint.parts(doc(), measurer)
        assertEquals(listOf("map_edge", "map_node", "map_node"), parts.map { it.kind })
        for (part in parts) {
            val out = FakeRenderer()
            part.paint(out)
            assertTrue("${part.kind} draws something", out.ops.isNotEmpty())
            assertTrue(part.paintBounds().w > 0.0)
        }
    }

    @Test fun collapsedBranchesStayHidden() {
        val r = f.root("r")
        f.child(r, "a")
        f.model.setCollapsed(r, true)
        assertEquals(listOf("map_node"), MapPaint.parts(doc(), measurer).map { it.kind })
    }

    @Test fun theMapDrawsOverTheItemsOfItsLayer() {
        f.root("r")
        val d = doc()
        val shape = ShapeItem(ShapeKind.ROUNDED_RECT, Pt(0.0, 0.0), Pt(10.0, 10.0), com.xnotes.core.model.Rgba(0, 0, 0))
        d.add(shape)
        val ordered = MapPaint.ordered(d, measurer)
        assertEquals(shape, ordered.first())
        assertEquals("map_node", ordered.last().kind)
    }
}
