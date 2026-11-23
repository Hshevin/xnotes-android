package com.xnotes.map

import com.xnotes.core.geometry.Pt
import com.xnotes.core.testkit.assertEquals
import com.xnotes.core.testkit.assertNull
import kotlin.test.Test

/**
 * RIGHT tree with 100 × 40 boxes: r (0, 0); a (148, −28); c (148, 28); a1 (296, −28) under a.
 */
class MapHitTest {

    private val b = StateBuilder()
    private val r = b.root("r")
    private val a = b.child(r, "a")
    private val a1 = b.child(a, "a1")
    private val c = b.child(r, "c")
    private val s = b.build()
    private val l = MapLayout.compute(s, emptySet(), FixedMeasure())

    private fun drop(dragged: NodeId, p: Pt, delta: Vec = Vec(5.0, 5.0)) = MapHit.drop(s, l, dragged, p, delta)

    @Test fun pointsHitTheNodeUnderThem() {
        assertEquals(r, MapHit.nodeAt(s, l, Pt(50.0, 20.0)))
        assertEquals(c, MapHit.nodeAt(s, l, Pt(200.0, 50.0)))
        assertNull(MapHit.nodeAt(s, l, Pt(-500.0, -500.0)))
    }

    @Test fun laterTreesAreOnTop() {
        val b2 = StateBuilder()
        b2.root("first", Pt(0.0, 0.0))
        val second = b2.root("second", Pt(50.0, 0.0))
        val s2 = b2.build()
        val l2 = MapLayout.compute(s2, emptySet(), FixedMeasure())
        assertEquals(second, MapHit.nodeAt(s2, l2, Pt(75.0, 20.0)))
    }

    @Test fun hiddenNodesAreNotHit() {
        val lc = MapLayout.compute(s, setOf(a), FixedMeasure())
        assertNull(MapHit.nodeAt(s, lc, Pt(320.0, -10.0)))
    }

    @Test fun middleOfANodeMakesALastChild() {
        assertEquals(DropAction.Reparent(c, 0), drop(a, Pt(198.0, 48.0)))
    }

    @Test fun edgeBandsInsertBeforeOrAfterOnTheParentsAxis() {
        // c spans y 28..68: the first quarter is 28..38, the last 58..68
        assertEquals(DropAction.Reparent(r, 1), drop(a, Pt(198.0, 66.0)))
    }

    @Test fun droppingBackInPlaceIsNone() {
        // just before c is exactly where a already is
        assertEquals(DropAction.None, drop(a, Pt(198.0, 30.0)))
    }

    @Test fun aRootTargetAlwaysTakesALastChild() {
        assertEquals(DropAction.Reparent(r, 1), drop(a, Pt(50.0, 2.0)))
        assertEquals(DropAction.Reparent(r, 2), drop(a1, Pt(50.0, 20.0)))
    }

    @Test fun ownSubtreeIsIgnoredAsATarget() {
        assertEquals(DropAction.Nudge(Vec(5.0, 5.0)), drop(a, Pt(320.0, -10.0)))
        assertEquals(DropAction.Nudge(Vec(5.0, 5.0)), drop(a, Pt(190.0, -10.0)))
    }

    @Test fun emptySpaceNudgesOrDoesNothing() {
        assertEquals(DropAction.Nudge(Vec(3.0, -2.0)), drop(c, Pt(900.0, 900.0), Vec(3.0, -2.0)))
        assertEquals(DropAction.None, drop(c, Pt(900.0, 900.0), Vec.ZERO))
        assertEquals(DropAction.Nudge(Vec(1.0, 1.0)), drop(r, Pt(900.0, 900.0), Vec(1.0, 1.0)))
    }

    @Test fun orgSiblingsUseTheHorizontalAxis() {
        val bo = StateBuilder()
        val ro = bo.root("r", kind = LayoutKind.ORG)
        val x = bo.child(ro, "x")
        val y = bo.child(ro, "y")
        val z = bo.root("z", Pt(1000.0, 1000.0))
        val so = bo.build()
        val lo = MapLayout.compute(so, emptySet(), FixedMeasure())
        val yr = lo.rectOf(y)!!
        // y's left quarter → before y; its right quarter → after y; its middle → child of y
        assertEquals(DropAction.Reparent(ro, 1), MapHit.drop(so, lo, z, Pt(yr.left + 5.0, yr.centerY), Vec(1.0, 1.0)))
        assertEquals(DropAction.Reparent(ro, 2), MapHit.drop(so, lo, z, Pt(yr.right - 5.0, yr.centerY), Vec(1.0, 1.0)))
        assertEquals(DropAction.Reparent(y, 0), MapHit.drop(so, lo, z, Pt(yr.centerX, yr.top + 2.0), Vec(1.0, 1.0)))
        assertEquals(x, MapHit.nodeAt(so, lo, lo.rectOf(x)!!.center))
    }
}
