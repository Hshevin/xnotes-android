package com.xnotes.map.ui

import com.xnotes.core.geometry.Pt
import com.xnotes.map.NodeId
import com.xnotes.map.SourceKind
import com.xnotes.map.SourceRef
import com.xnotes.core.testkit.assertEquals
import com.xnotes.core.testkit.assertFalse
import com.xnotes.core.testkit.assertNull
import com.xnotes.core.testkit.assertTrue
import kotlin.test.Test

/** Source chips on the canvas and the node's source list, as the map's UI drives them. */
class MapSourceUiTest {

    private val x = ControllerFixture()
    private val c = x.c
    private val e = x.editor

    private val doc = "0b9f3c2e-58a4-4a7e-9d43-0c1e2f3a4b5c"
    private fun ref(id: String, label: String = "Math · 1") =
        SourceRef(id, SourceKind.NOTE_PAGE, doc, "7d1e2a3b-4c5d-4e6f-8a9b-0c1d2e3f4${id.padStart(3, '0').takeLast(3)}", label)

    private fun root(vararg refs: SourceRef): NodeId {
        val r = x.f.root("topic")
        if (refs.isNotEmpty()) e.addSources(r, refs.toList())
        return r
    }

    /** The row the node's chips sit in, as laid out now. */
    private fun chipRow(id: NodeId) =
        x.f.metrics.frame(x.state.nodes.getValue(id), x.state.parent[id] == null, x.rect(id).topLeft).sources!!

    @Test fun tappingAChipSelectsTheNodeAndAsksToOpenThatSource() {
        val r = root(ref("1"), ref("2"))
        val row = chipRow(r)
        x.tap(Pt(row.left + 3.0, row.centerY))
        assertEquals(listOf(r to "1"), x.host.sourceTaps)
        assertEquals(r, c.selected)
        assertFalse("a chip tap does not start typing", c.isEditingTitle)
    }

    @Test fun aChipTapOnTheSelectedNodeStillOpensTheSourceNotTheTitle() {
        val r = root(ref("1"))
        c.select(r)
        val row = chipRow(r)
        x.tap(Pt(row.left + 3.0, row.centerY))
        assertEquals(listOf(r to "1"), x.host.sourceTaps)
        assertFalse(c.isEditingTitle)
    }

    @Test fun theOverflowChipAsksForTheWholeList() {
        val r = root(*(1..12).map { ref("$it", "a rather long source label $it") }.toTypedArray())
        val row = chipRow(r)
        val hit = generateSequence(row.right - 1.0) { it - 2.0 }.takeWhile { it > row.left }
            .map { Pt(it, row.centerY) }.firstOrNull { x.projection.chipAt(it)?.second == null && x.projection.chipAt(it) != null }
        requireNotNull(hit) { "no +N chip drawn inside the row" }
        x.tap(hit)
        assertEquals(listOf(r to null), x.host.sourceTaps)
    }

    @Test fun chipsStayInsideTheirRowHoweverLongTheFirstLabel() {
        val r = root(ref("1", "x".repeat(300)), ref("2"), ref("3"))
        val row = chipRow(r)
        var inside = 0
        var px = row.left - 20.0
        while (px < row.right + 60.0) {
            val hit = x.projection.chipAt(Pt(px, row.centerY))
            if (hit != null) { assertTrue("chip drawn past the row at $px", px <= row.right); inside++ }
            px += 1.0
        }
        assertTrue(inside > 0)
        assertEquals(r to null, x.projection.chipAt(Pt(row.right - 2.0, row.centerY)))
    }

    @Test fun tappingTheNodeAwayFromItsChipsIsAPlainTap() {
        val r = root(ref("1"))
        x.tap(Pt(x.rect(r).left + 4.0, x.rect(r).top + 4.0))
        assertTrue(x.host.sourceTaps.isEmpty())
        assertEquals(r, c.selected)
    }

    @Test fun addingSourcesIsOneStepAndSkipsOnesAlreadyThere() {
        val r = root(ref("1"))
        c.select(r)
        val before = x.state
        assertTrue(c.addSources(listOf(ref("1").copy(id = "dup"), ref("2"), ref("3"))))
        assertEquals(listOf("1", "2", "3"), c.selectedSources.map { it.id })
        assertFalse("all already there", c.addSources(listOf(ref("2").copy(id = "again"))))
        e.history.undo()
        assertEquals(before, x.state)
    }

    @Test fun removingASourceIsOneStep() {
        val r = root(ref("1"), ref("2"))
        c.select(r)
        assertTrue(c.removeSource("1"))
        assertEquals(listOf("2"), c.selectedSources.map { it.id })
        assertFalse(c.removeSource("nope"))
        e.history.undo()
        assertEquals(listOf("1", "2"), c.selectedSources.map { it.id })
    }

    @Test fun nothingSelectedOrALockedLayerChangesNothing() {
        val r = root()
        assertFalse(c.addSources(listOf(ref("1"))))
        c.select(r)
        x.host.editable = { false }
        assertFalse(c.addSources(listOf(ref("1"))))
        assertFalse(c.removeSource("1"))
        assertTrue(x.state.nodes.getValue(r).sources.isEmpty())
        assertTrue(c.selectedSources.isEmpty())
    }

    @Test fun chipsOfACollapsedAwayNodeCannotBeHit() {
        val r = root()
        val a = x.f.child(r, "a")
        e.addSources(a, listOf(ref("1")))
        val row = chipRow(a)
        val at = Pt(row.left + 3.0, row.centerY)
        assertEquals(a to "1", x.projection.chipAt(at))
        x.f.model.setCollapsed(r, true)
        assertNull(x.projection.chipAt(at))
    }
}
