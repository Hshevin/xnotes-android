package com.xnotes.map

import com.xnotes.core.geometry.Pt
import com.xnotes.core.testkit.assertEquals
import com.xnotes.core.testkit.assertFalse
import com.xnotes.core.testkit.assertNull
import com.xnotes.core.testkit.assertTrue
import kotlin.test.Test

/** Every box is 100 × 40 unless stated; gaps come from [MapLayout]'s constants. */
class MapLayoutTest {

    private val m = FixedMeasure()

    private fun lay(s: MapState, collapsed: Set<NodeId> = emptySet(), measure: NodeMeasure = m) =
        MapLayout.compute(s, collapsed, measure)

    // ---- RIGHT ----

    @Test fun rootStaysAtItsOrigin() {
        val b = StateBuilder()
        val r = b.root("r", Pt(300.0, -50.0))
        val l = lay(b.build())
        assertRect(rect(300.0, -50.0), l.rectOf(r))
        assertEquals(0, l.placements.getValue(r).depth)
        assertTrue(l.edges.isEmpty())
    }

    @Test fun rightSingleChildSitsOneGapRightAndCentred() {
        val b = StateBuilder()
        val r = b.root("r")
        val a = b.child(r, "a")
        val l = lay(b.build())
        assertRect(rect(148.0, 0.0), l.rectOf(a))
        assertEquals(1, l.placements.getValue(a).depth)
        assertEquals(GrowSide.RIGHT, l.placements.getValue(a).side)
        val e = l.edges.single()
        assertEquals(TreeEdge(r, a, Pt(100.0, 20.0), Pt(148.0, 20.0), GrowSide.RIGHT), e)
    }

    @Test fun rightSiblingsStackCentredOnParent() {
        val b = StateBuilder()
        val r = b.root("r")
        val a = b.child(r, "a")
        val c = b.child(r, "c")
        val l = lay(b.build())
        // stack = 40 + 16 + 40 = 96, centred on y = 20 → starts at -28
        assertRect(rect(148.0, -28.0), l.rectOf(a))
        assertRect(rect(148.0, 28.0), l.rectOf(c))
    }

    @Test fun rightSubtreeExtentsPackSiblings() {
        val b = StateBuilder()
        val r = b.root("r")
        val a = b.child(r, "a")
        val a1 = b.child(a, "a1")
        val a2 = b.child(a, "a2")
        val c = b.child(r, "c")
        val l = lay(b.build())
        // a's extent 96, c's 40: stack 152 centred on 20 → top -56
        assertRect(rect(148.0, -28.0), l.rectOf(a), "a centred in [-56, 40]")
        assertRect(rect(148.0, 56.0), l.rectOf(c))
        assertRect(rect(296.0, -56.0), l.rectOf(a1))
        assertRect(rect(296.0, 0.0), l.rectOf(a2))
        assertEquals(2, l.placements.getValue(a1).depth)
    }

    @Test fun rightChildIsCentredInsideItsTallerExtent() {
        val b = StateBuilder()
        val r = b.root("r")
        val a = b.child(r, "a")
        val tall = b.child(a, "tall")
        val measure = FixedMeasure(byTitle = mapOf("tall" to Size(100.0, 200.0)))
        val l = lay(b.build(), measure = measure)
        // a's extent is 200 tall, centred on root centre 20 → [-80, 120]; a centred inside → y = 0
        assertRect(rect(148.0, 0.0), l.rectOf(a))
        assertRect(rect(296.0, -80.0, 100.0, 200.0), l.rectOf(tall))
    }

    @Test fun differentSizedNodesUseTheirOwnBoxes() {
        val b = StateBuilder()
        val r = b.root("r", title = "big")
        val a = b.child(r, "a")
        val measure = FixedMeasure(byTitle = mapOf("big" to Size(200.0, 80.0)))
        val l = lay(b.build(), measure = measure)
        assertRect(rect(0.0, 0.0, 200.0, 80.0), l.rectOf(r))
        assertRect(rect(248.0, 20.0), l.rectOf(a))
    }

    // ---- BALANCED ----

    @Test fun balancedPutsFirstHalfRightRestLeft() {
        val b = StateBuilder()
        val r = b.root("r", kind = LayoutKind.BALANCED)
        val a = b.child(r, "a")
        val c = b.child(r, "c")
        val d = b.child(r, "d")
        val l = lay(b.build())
        assertRect(rect(148.0, -28.0), l.rectOf(a))
        assertRect(rect(148.0, 28.0), l.rectOf(c))
        assertRect(rect(-148.0, 0.0), l.rectOf(d))
        assertEquals(GrowSide.LEFT, l.placements.getValue(d).side)
        assertEquals(GrowSide.RIGHT, l.placements.getValue(r).side)
        assertTrue(TreeEdge(r, d, Pt(0.0, 20.0), Pt(-48.0, 20.0), GrowSide.LEFT) in l.edges)
    }

    @Test fun balancedDeeperLevelsKeepGrowingOnTheirSide() {
        val b = StateBuilder()
        val r = b.root("r", kind = LayoutKind.BALANCED)
        b.child(r, "a")
        val d = b.child(r, "d")
        val d1 = b.child(d, "d1")
        val l = lay(b.build())
        assertRect(rect(-296.0, 0.0), l.rectOf(d1))
        assertEquals(GrowSide.LEFT, l.placements.getValue(d1).side)
        assertTrue(TreeEdge(d, d1, Pt(-148.0, 20.0), Pt(-196.0, 20.0), GrowSide.LEFT) in l.edges)
    }

    @Test fun balancedWithOneChildGoesRight() {
        val b = StateBuilder()
        val r = b.root("r", kind = LayoutKind.BALANCED)
        val a = b.child(r, "a")
        assertRect(rect(148.0, 0.0), lay(b.build()).rectOf(a))
    }

    // ---- ORG ----

    @Test fun orgPlacesChildrenBelowSideBySideCentred() {
        val b = StateBuilder()
        val r = b.root("r", kind = LayoutKind.ORG)
        val a = b.child(r, "a")
        val c = b.child(r, "c")
        val l = lay(b.build())
        // row = 100 + 24 + 100 = 224 centred on x = 50 → starts at -62
        assertRect(rect(-62.0, 80.0), l.rectOf(a))
        assertRect(rect(62.0, 80.0), l.rectOf(c))
        assertEquals(GrowSide.DOWN, l.placements.getValue(a).side)
        assertTrue(TreeEdge(r, a, Pt(50.0, 40.0), Pt(-12.0, 80.0), GrowSide.DOWN) in l.edges)
    }

    @Test fun orgSubtreeWidthsPackSiblings() {
        val b = StateBuilder()
        val r = b.root("r", kind = LayoutKind.ORG)
        val a = b.child(r, "a")
        val a1 = b.child(a, "a1")
        val a2 = b.child(a, "a2")
        val c = b.child(r, "c")
        val l = lay(b.build())
        // a's extent 224, c's 100: row 348 centred on 50 → [-124, 224]; a centred in [-124, 100] → x = -62
        assertRect(rect(-62.0, 80.0), l.rectOf(a))
        assertRect(rect(124.0, 80.0), l.rectOf(c))
        assertRect(rect(-124.0, 160.0), l.rectOf(a1))
        assertRect(rect(0.0, 160.0), l.rectOf(a2))
    }

    // ---- INDENT ----

    @Test fun indentStacksChildrenBelowIndented() {
        val b = StateBuilder()
        val r = b.root("r", kind = LayoutKind.INDENT)
        val a = b.child(r, "a")
        val a1 = b.child(a, "a1")
        val c = b.child(r, "c")
        val l = lay(b.build())
        assertRect(rect(32.0, 52.0), l.rectOf(a))
        assertRect(rect(64.0, 104.0), l.rectOf(a1))
        // a's subtree ends at 144 → c at 156
        assertRect(rect(32.0, 156.0), l.rectOf(c))
        assertEquals(GrowSide.INDENT, l.placements.getValue(a).side)
        assertTrue(TreeEdge(r, a, Pt(16.0, 40.0), Pt(32.0, 72.0), GrowSide.INDENT) in l.edges)
        assertTrue(TreeEdge(a, a1, Pt(48.0, 92.0), Pt(64.0, 124.0), GrowSide.INDENT) in l.edges)
    }

    // ---- offsets ----

    @Test fun offsetMovesTheWholeSubtreeButNotSiblings() {
        val b = StateBuilder()
        val r = b.root("r")
        val a = b.child(r, "a")
        val a1 = b.child(a, "a1")
        val c = b.child(r, "c")
        b.offset(a, 10.0, -5.0)
        val plain = StateBuilder().also { p ->
            val pr = p.root("r"); val pa = p.child(pr, "a"); p.child(pa, "a1"); p.child(pr, "c")
        }.build()
        val base = lay(plain)
        val l = lay(b.build())
        assertRect(base.rectOf(a)!!.translate(10.0, -5.0), l.rectOf(a))
        assertRect(base.rectOf(a1)!!.translate(10.0, -5.0), l.rectOf(a1))
        assertRect(base.rectOf(c)!!, l.rectOf(c), "sibling untouched")
        val e = l.edges.first { it.child == a }
        assertPt(Pt(100.0, 20.0), e.from)
        assertPt(Pt(l.rectOf(a)!!.left, l.rectOf(a)!!.centerY), e.to)
    }

    @Test fun nestedOffsetsAccumulate() {
        val b = StateBuilder()
        val r = b.root("r")
        val a = b.child(r, "a")
        val a1 = b.child(a, "a1")
        b.offset(a, 0.0, 30.0)
        b.offset(a1, 5.0, 0.0)
        val l = lay(b.build())
        assertRect(rect(148.0, 30.0), l.rectOf(a))
        assertRect(rect(296.0 + 5.0, 30.0), l.rectOf(a1))
    }

    // ---- collapse ----

    @Test fun collapsedNodeHidesDescendantsAndShrinksItsExtent() {
        val b = StateBuilder()
        val r = b.root("r")
        val a = b.child(r, "a")
        val a1 = b.child(a, "a1")
        val a11 = b.child(a1, "a11")
        val a2 = b.child(a, "a2")
        val c = b.child(r, "c")
        val l = lay(b.build(), collapsed = setOf(a))
        assertEquals(setOf(a1, a11, a2), l.hidden)
        assertEquals(mapOf(a to 3), l.hiddenCount)
        assertNull(l.rectOf(a1))
        assertFalse(l.edges.any { it.child in l.hidden || it.parent in l.hidden })
        // a now counts as 40 tall: same as two plain siblings
        assertRect(rect(148.0, -28.0), l.rectOf(a))
        assertRect(rect(148.0, 28.0), l.rectOf(c))
    }

    @Test fun collapsedRootHidesItsWholeTree() {
        val b = StateBuilder()
        val r = b.root("r")
        val a = b.child(r, "a")
        val l = lay(b.build(), collapsed = setOf(r))
        assertEquals(setOf(a), l.hidden)
        assertEquals(setOf(r), l.placements.keys)
    }

    @Test fun collapsedLeafOrUnknownIdIsIgnored() {
        val b = StateBuilder()
        val r = b.root("r")
        val a = b.child(r, "a")
        val l = lay(b.build(), collapsed = setOf(a, id("ghost")))
        assertTrue(l.hidden.isEmpty())
        assertTrue(l.hiddenCount.isEmpty())
    }

    // ---- several trees ----

    @Test fun treesAreIndependentAndEachUsesItsOwnKind() {
        val b = StateBuilder()
        val r1 = b.root("r1", Pt(0.0, 0.0), LayoutKind.RIGHT)
        val a = b.child(r1, "a")
        val r2 = b.root("r2", Pt(1000.0, 1000.0), LayoutKind.ORG)
        val x = b.child(r2, "x")
        val l = lay(b.build())
        assertRect(rect(148.0, 0.0), l.rectOf(a))
        assertRect(rect(1000.0, 1080.0), l.rectOf(x))
        assertEquals(listOf(a, x), l.edges.map { it.child })
    }

    @Test fun edgesFollowTreeOrder() {
        val b = StateBuilder()
        val r = b.root("r")
        val a = b.child(r, "a")
        val a1 = b.child(a, "a1")
        val c = b.child(r, "c")
        assertEquals(listOf(a, a1, c), lay(b.build()).edges.map { it.child })
    }

    @Test fun layoutIsPureAndRepeatable() {
        val b = StateBuilder()
        val r = b.root("r", kind = LayoutKind.BALANCED)
        repeat(5) { b.child(r, "c$it") }
        val s = b.build()
        assertEquals(lay(s), lay(s))
    }
}
