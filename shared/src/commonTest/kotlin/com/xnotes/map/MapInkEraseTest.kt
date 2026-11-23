package com.xnotes.map

import com.xnotes.core.geometry.Pt
import com.xnotes.core.testkit.assertEquals
import com.xnotes.core.testkit.assertFalse
import com.xnotes.core.testkit.assertNotNull
import com.xnotes.core.testkit.assertNull
import com.xnotes.core.testkit.assertSame
import com.xnotes.core.testkit.assertTrue
import kotlin.test.Test

/**
 * The eraser over node ink (contract in [MapInkErase]).
 *
 * Geometry: root "root" at the origin measures its title 40 × 24 at (16, 10), so its ink area
 * starts at canvas (16, 42); a stroke written at canvas (x, y) is stored at (x − 16, y − 42).
 * [com.xnotes.core.model.Stroke.intersectsCircle] tests samples, so every hit below sits on one.
 */
class MapInkEraseTest {

    private val f = EditorFixture()
    private val e = f.editor

    private fun eraser(editable: (NodeId) -> Boolean = { true }) = MapInkErase(f.model, f.metrics, { e.layout() }, editable)

    private fun inkOf(id: NodeId) = f.state.nodes.getValue(id).ink

    /** A root with two strokes: a short one at canvas y = 60 and a long horizontal one at y = 100. */
    private fun twoStrokes(): NodeId {
        val r = f.root("root")
        e.addInk(r, pen(30.0 to 60.0, 50.0 to 60.0))
        e.addInk(r, pen(30.0 to 100.0, 90.0 to 100.0, 150.0 to 100.0))
        return r
    }

    @Test fun wholeModeTakesOnlyTouchedStrokes() {
        val r = twoStrokes()
        val before = inkOf(r)
        val box = e.layout().rectOf(r)!!
        val dirty = eraser().erase(30.0, 60.0, 4.0, area = false)
        assertEquals(box, dirty)
        val after = inkOf(r)
        assertEquals(1, after.strokes.size)
        assertSame("untouched strokes are kept as they are", before.strokes[1], after.strokes[0])
        assertEquals(before.width, after.width, 1e-9)
        assertEquals(before.height, after.height, 1e-9)
    }

    @Test fun areaModeReplacesAStrokeWithItsPiecesInPlace() {
        val r = twoStrokes()
        val long = inkOf(r).strokes[1]
        val expected = long.erasedBy(90.0 - 16.0, 100.0 - 42.0, 6.0)
        assertNotNull(expected)
        val untouched = inkOf(r).strokes[0]
        eraser().erase(90.0, 100.0, 6.0, area = true)
        val after = inkOf(r).strokes
        assertSame(untouched, after[0])
        assertEquals(1 + expected!!.size, after.size)
        for ((i, piece) in expected.withIndex()) {
            assertEquals(piece.samples.map { it.x }, after[1 + i].samples.map { it.x })
            assertEquals(piece.samples.map { it.y }, after[1 + i].samples.map { it.y })
        }
    }

    @Test fun missingEverythingChangesNothing() {
        val r = twoStrokes()
        val state = f.state
        val er = eraser()
        assertNull(er.erase(1000.0, 1000.0, 10.0, area = false))
        assertNull(er.erase(40.0, 75.0, 2.0, area = true)) // inside the node, between the strokes
        assertSame(state, f.state)
        assertNull(er.command())
        assertEquals(2, inkOf(r).strokes.size)
    }

    @Test fun nodesThatMayNotBeEditedAreLeftAlone() {
        val r = twoStrokes()
        assertNull(eraser { false }.erase(30.0, 60.0, 4.0, area = false))
        assertEquals(2, inkOf(r).strokes.size)
    }

    @Test fun hiddenNodesAreNotErased() {
        val r = f.root("root")
        val c = f.child(r, "c")
        val box = e.layout().rectOf(c)!!
        val frame = f.metrics.frame(f.state.nodes.getValue(c), false, box.topLeft)
        val at = Pt(frame.rect.left + 30.0, frame.rect.bottom + 20.0)
        e.addInk(c, pen(at.x to at.y, at.x + 20 to at.y))
        f.model.setCollapsed(r, true)
        assertNull(eraser().erase(at.x, at.y, 5.0, area = false))
        assertEquals(1, inkOf(c).strokes.size)
    }

    @Test fun theLastStrokeGoingLeavesAnEmptyAreaOfTheSameSize() {
        val r = f.root("root")
        e.addInk(r, pen(30.0 to 60.0, 50.0 to 60.0))
        val before = inkOf(r)
        eraser().erase(30.0, 60.0, 4.0, area = false)
        val after = inkOf(r)
        assertTrue(after.strokes.isEmpty())
        assertFalse(after.isEmpty)
        assertEquals(before.width, after.width, 1e-9)
        assertEquals(before.height, after.height, 1e-9)
    }

    @Test fun oneStateChangePerStepWhateverItTouches() {
        val a = f.root("root")
        val b = f.root("root", at = Pt(0.0, 300.0))
        e.addInk(a, pen(30.0 to 60.0, 50.0 to 60.0))
        e.addInk(b, pen(30.0 to 360.0, 50.0 to 360.0))
        var changes = 0
        f.model.addListener { changes++ }
        val dirty = eraser().erase(40.0, 210.0, 160.0, area = false)
        assertEquals(1, changes)
        assertEquals(e.layout().rectOf(a)!!.union(e.layout().rectOf(b)!!), dirty)
        assertTrue(inkOf(a).strokes.isEmpty())
        assertTrue(inkOf(b).strokes.isEmpty())
    }

    @Test fun theDragIsOneCommandTheCallerPushes() {
        val r = twoStrokes()
        val before = f.state
        var pushed = 0
        f.history.onPush = { pushed++ }
        val er = eraser()
        er.erase(30.0, 60.0, 4.0, area = false)
        er.erase(90.0, 100.0, 4.0, area = true)
        assertEquals("erasing pushes nothing itself", 0, pushed)
        val erased = f.state
        val command = er.command()
        assertTrue(command is MapStateCommand)
        f.history.push(command!!)
        f.history.undo()
        assertEquals(before, f.state)
        assertEquals(2, inkOf(r).strokes.size)
        f.history.redo()
        assertEquals(erased, f.state)
    }
}
