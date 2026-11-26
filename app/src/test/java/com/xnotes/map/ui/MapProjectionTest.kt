package com.xnotes.map.ui

import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba
import com.xnotes.map.EditorFixture
import com.xnotes.map.MapLayout
import com.xnotes.map.MapState
import com.xnotes.map.NodeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MapProjectionTest {

    private val measurer = FakeTextMeasurer()
    private val titles = MapTitles(measurer)
    private val f = EditorFixture(titles)
    private val e = f.editor
    private val sink = RecordingSink()
    private val projection = MapProjection(titles, measurer, f.metrics)

    private fun project(state: MapState = f.state, untitled: NodeId? = null, night: Boolean = false, layerFor: (String?) -> String? = { it }): Boolean =
        projection.update(state, MapLayout.compute(state, f.model.collapsed, f.metrics), sink, untitled, night, layerFor)

    /** Root "r" with children "a" and "b", and a relation from a to b. */
    private fun tree(): Triple<NodeId, NodeId, NodeId> {
        val r = f.root("r")
        val a = f.child(r, "a")
        val b = f.child(r, "b")
        e.addRelation(a, b)
        return Triple(r, a, b)
    }

    @Test fun everyNodeEdgeAndRelationGetsOneRecordInDrawingOrder() {
        val (r, a, b) = tree()
        assertTrue(project())
        val kinds = projection.items.map { it.kind }
        assertEquals(listOf("map_edge", "map_edge", "map_relation", "map_node", "map_node", "map_node"), kinds)
        assertEquals(setOf(r, a, b), projection.items.filterIsInstance<MapNodePart>().map { it.id }.toSet())
        assertEquals(setOf(a, b), projection.items.filterIsInstance<MapEdgePart>().map { it.child }.toSet())
        assertEquals(6, sink.live.size)
        assertTrue(sink.live.values.all { it.isNotEmpty() })
    }

    @Test fun anUnchangedMapRemeshesNothing() {
        tree()
        project()
        sink.clearLog()
        assertFalse(project())
        assertTrue(sink.upserts.isEmpty())
        assertTrue(sink.removes.isEmpty())
    }

    @Test fun anEditRemeshesOnlyWhatItChanged() {
        val (r, a, _) = tree()
        project()
        val nodeA = projection.node(a)
        sink.clearLog()
        e.setTitle(a, "a much longer title")
        project()
        assertSame("a node keeps its record across edits", nodeA, projection.node(a))
        val touched: Set<com.xnotes.core.model.CanvasItem> = sink.upserts.toSet()
        assertTrue(touched.contains(nodeA!!))
        assertFalse("the root did not change", touched.contains(projection.node(r)!!))
        assertTrue(touched.all { it is MapNodePart && it.id == a || it is MapEdgePart && it.child == a || it is MapRelationPart })
    }

    @Test fun deletedNodesTakeTheirEdgesAndRelationsWithThem() {
        val (_, a, _) = tree()
        project()
        val nodeA = projection.node(a)!!
        sink.clearLog()
        e.delete(listOf(a))
        project()
        assertTrue(nodeA in sink.removes)
        assertEquals(1, sink.removes.count { it is MapEdgePart })
        assertEquals(1, sink.removes.count { it is MapRelationPart })
        assertNull(projection.node(a))
        assertEquals(listOf("map_edge", "map_node", "map_node"), projection.items.map { it.kind })
    }

    @Test fun collapsingHidesChildrenAndBadgesTheParent() {
        val (r, a, b) = tree()
        project()
        val plain = sink.live.getValue(projection.node(r)!!).size
        f.model.setCollapsed(r, true)
        project()
        assertNull(projection.node(a))
        assertNull(projection.node(b))
        assertTrue("edges and the relation between hidden nodes go", projection.items.all { it is MapNodePart })
        assertTrue("the root now carries a count badge", sink.live.getValue(projection.node(r)!!).size > plain)
    }

    @Test fun aRelationNeedsBothEndsShown() {
        val r = f.root("r")
        val a = f.child(r, "a")
        val other = f.root("o", at = Pt(0.0, 400.0))
        e.addRelation(a, other)
        project()
        assertEquals(1, projection.items.count { it is MapRelationPart })
        f.model.setCollapsed(r, true)
        project()
        assertEquals(0, projection.items.count { it is MapRelationPart })
    }

    @Test fun theNodeBeingTypedIntoDrawsWithoutItsWords() {
        val r = f.root("title")
        project()
        val withWords = sink.live.getValue(projection.node(r)!!).size
        project(untitled = r)
        assertEquals(withWords - 1, sink.live.getValue(projection.node(r)!!).size)
    }

    @Test fun nightPaperReplacesAWhiteFillWithNearBlack() {
        val r = f.root("r")
        project(night = true)
        val colors = sink.live.getValue(projection.node(r)!!).map { it.color }
        assertTrue(colors.contains(MapProjection.NIGHT_PAPER))
        assertFalse(colors.contains(Rgba(255, 255, 255)))
        project(night = false)
        assertTrue(sink.live.getValue(projection.node(r)!!).map { it.color }.contains(Rgba(255, 255, 255)))
    }

    @Test fun partsTakeTheirTreesDrawingLayer() {
        e.beginRoot(Pt(0.0, 0.0), "ink"); val r = e.commitDraft("r")!!
        val a = f.child(r, "a")
        e.beginRoot(Pt(0.0, 300.0)); val plain = e.commitDraft("p")!!
        project(layerFor = { it ?: "base" })
        assertEquals("ink", projection.node(r)!!.layerId)
        assertEquals("ink", projection.node(a)!!.layerId)
        assertEquals("ink", projection.items.filterIsInstance<MapEdgePart>().single().layerId)
        assertEquals("base", projection.node(plain)!!.layerId)
    }

    @Test fun partsAreNeitherMovableNorErasable() {
        val r = f.root("r")
        project()
        val part = projection.node(r)!!
        val before = part.bounds()
        part.translate(50.0, 50.0)
        assertEquals(before, part.bounds())
        assertTrue(part.locked)
        assertFalse(part.intersectsCircle(before.centerX, before.centerY, 100.0))
    }

    @Test fun relationLinesRunBetweenBoxEdges() {
        val a = Rect(0.0, 0.0, 100.0, 40.0)
        val b = Rect(300.0, 0.0, 100.0, 40.0)
        val (p, q) = MapProjection.relationEnds(a, b)!!
        assertEquals(Pt(100.0, 20.0), p)
        assertEquals(Pt(300.0, 20.0), q)
        assertNull("overlapping boxes have no line between them", MapProjection.relationEnds(a, Rect(50.0, 10.0, 100.0, 40.0)))
    }

    @Test fun aRelationIsHitAlongItsLine() {
        val r = f.root("r")
        val other = f.root("o", at = Pt(600.0, 0.0))
        val rel = e.addRelation(r, other)
        project()
        val ra = MapLayout.compute(f.state, emptySet(), f.metrics)
        val (p, q) = MapProjection.relationEnds(ra.rectOf(r)!!, ra.rectOf(other)!!)!!
        val mid = Pt((p.x + q.x) / 2, (p.y + q.y) / 2)
        assertEquals(rel, projection.relationAt(Pt(mid.x, mid.y + 5.0), 8.0))
        assertNull(projection.relationAt(Pt(mid.x, mid.y + 30.0), 8.0))
    }

    @Test fun clearingTakesEverythingOffTheScene() {
        tree()
        project()
        projection.clear(sink)
        assertTrue(sink.live.isEmpty())
        assertTrue(projection.items.isEmpty())
        assertNotNull(f.state.nodes.values.firstOrNull())
    }
}
