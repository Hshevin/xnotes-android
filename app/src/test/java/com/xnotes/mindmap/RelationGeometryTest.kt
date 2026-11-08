package com.xnotes.mindmap

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class RelationGeometryTest {
    @Test fun wideHitUsesTheFiniteSegmentRatherThanItsInfiniteExtension() {
        val line = MapRelationGeometry.Line("e", Pt(0.0, 0.0), Pt(100.0, 0.0))
        assertEquals(listOf("e"), MapRelationGeometry.hits(listOf(line), Pt(50.0, 11.0), 12.0))
        assertTrue(MapRelationGeometry.hits(listOf(line), Pt(50.0, 13.0), 12.0).isEmpty())
        assertTrue(MapRelationGeometry.hits(listOf(line), Pt(150.0, 0.0), 12.0).isEmpty())
        assertEquals(listOf("e"), MapRelationGeometry.hits(listOf(line), Pt(-5.0, 0.0), 12.0))
    }

    @Test fun verticalDiagonalAndZeroLengthSegmentsHaveFiniteDistances() {
        val vertical = MapRelationGeometry.Line("v", Pt(10.0, 0.0), Pt(10.0, 100.0))
        val diagonal = MapRelationGeometry.Line("d", Pt(0.0, 0.0), Pt(100.0, 100.0))
        val point = MapRelationGeometry.Line("p", Pt(40.0, 40.0), Pt(40.0, 40.0))
        assertEquals(listOf("v"), MapRelationGeometry.hits(listOf(vertical), Pt(18.0, 50.0), 10.0))
        assertEquals(listOf("d"), MapRelationGeometry.hits(listOf(diagonal), Pt(50.0, 55.0), 4.0))
        assertEquals(listOf("p"), MapRelationGeometry.hits(listOf(point), Pt(43.0, 44.0), 5.0))
        assertTrue(MapRelationGeometry.hits(listOf(point), Pt(46.0, 46.0), 5.0).isEmpty())
    }

    @Test fun overlapsSortNearestFirstAndTiesInReversePainterOrder() {
        val first = MapRelationGeometry.Line("first", Pt(0.0, 0.0), Pt(100.0, 0.0))
        val second = first.copy(id = "second")
        val nearest = first.copy(id = "near", start = Pt(0.0, 4.0), end = Pt(100.0, 4.0))
        assertEquals(listOf("near", "second", "first"), MapRelationGeometry.hits(listOf(first, second, nearest), Pt(50.0, 4.0), 24.0))
    }

    @Test fun screenTargetStays48DpAtAllZoomsAndDensities() {
        for (density in listOf(1.0, 2.5)) for (zoom in listOf(.05, 1.0, 4.0)) {
            val scale = density * zoom
            val radius = MapRelationGeometry.hitRadius(density, scale)
            assertEquals(24.0 * density, radius * scale, 1e-8)
        }
    }

    @Test fun hiddenEndpointsAreNotPaintedOrHitAndVisibleGeometryTracksCurrentNodes() {
        val doc = MindMapDocument(nodes = listOf(MapNode(id = "a", parent = null, collapsed = true),
            MapNode(id = "b", parent = "a", x = 500.0)), relations = listOf(MapRelation(id = "e", from = "a", to = "b")))
        assertTrue(MapRelationGeometry.visible(doc).isEmpty())
        val shown = doc.update("a") { it.copy(collapsed = false) }.update("b") { it.copy(x = 700.0) }
        val line = MapRelationGeometry.visible(shown).single()
        assertEquals(shown.node("a").bounds.center, line.start)
        assertEquals(shown.node("b").bounds.center, line.end)
    }

    @Test fun labelAndArrowWingsAreHitWhereTheyAreActuallyDrawn() {
        val wing = MapRelationGeometry.Segment(Pt(100.0, 0.0), Pt(88.0, 7.0))
        val line = MapRelationGeometry.Line("e", Pt(0.0, 0.0), Pt(100.0, 0.0),
            wings = listOf(wing), labelBounds = Rect(50.0, -30.0, 150.0, 20.0))
        assertEquals(listOf("e"), MapRelationGeometry.hits(listOf(line), Pt(150.0, -20.0), 1.0))
        assertEquals(listOf("e"), MapRelationGeometry.hits(listOf(line.copy(labelBounds = null)), Pt(88.0, 7.0), .1))
        assertTrue(MapRelationGeometry.hits(listOf(line), Pt(210.0, -20.0), 1.0).isEmpty())
    }

    @Test fun invalidPointerOrToleranceNeverSelectsAnything() {
        val line = MapRelationGeometry.Line("e", Pt(0.0, 0.0), Pt(100.0, 0.0))
        assertTrue(MapRelationGeometry.hits(listOf(line), Pt(Double.NaN, 0.0), 24.0).isEmpty())
        assertTrue(MapRelationGeometry.hits(listOf(line), Pt(50.0, 0.0), -1.0).isEmpty())
        assertTrue(MapRelationGeometry.hits(listOf(line), Pt(50.0, 0.0), Double.POSITIVE_INFINITY).isEmpty())
    }
}
