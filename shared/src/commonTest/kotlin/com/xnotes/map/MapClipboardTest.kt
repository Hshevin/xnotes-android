package com.xnotes.map

import com.xnotes.core.geometry.Pt
import com.xnotes.core.testkit.assertEquals
import com.xnotes.core.testkit.assertFalse
import com.xnotes.core.testkit.assertNull
import com.xnotes.core.testkit.assertTrue
import kotlin.test.Test

class MapClipboardTest {

    private val b = StateBuilder()
    private val r = b.root("r", kind = LayoutKind.ORG)
    private val a = b.child(r, "a")
    private val a1 = b.child(a, "a1")
    private val a2 = b.child(a, "a2")
    private val c = b.child(r, "c")
    private val other = b.root("other", Pt(2000.0, 0.0))

    init {
        b.offset(a, 7.0, 7.0)
        b.offset(a1, 3.0, 0.0)
        b.relation("in", a1, a2)
        b.relation("out", a1, c)
        b.relation("far", a2, other)
    }

    private val s = b.build()
    private val l = MapLayout.compute(s, emptySet(), FixedMeasure())

    @Test fun copiesASubtreeAsAFragmentRoot() {
        val fr = MapClipboard.copy(s, l, listOf(a))
        val fs = fr.state
        assertEquals(listOf(a), fs.roots)
        assertEquals(setOf(a, a1, a2), fs.nodes.keys)
        assertEquals(listOf(a1, a2), fs.childrenOf(a))
        assertEquals(l.rectOf(a)!!.topLeft, fs.rootOrigin[a])
        assertEquals(LayoutKind.ORG, fs.layout[a])
        assertFalse("the top node loses its offset", a in fs.offsets)
        assertEquals(Vec(3.0, 0.0), fs.offsets[a1])
        assertEquals(setOf(RelationId("in")), fs.relations.keys)
        assertTrue(fs.layers.isEmpty())
        assertEquals(s.nodes.getValue(a).style, fs.nodes.getValue(a).style)
        assertValid(fs)
        val expected = listOf(a, a1, a2).map { l.rectOf(it)!! }.reduce { x, y -> x.union(y) }
        assertRect(expected, fr.bounds)
    }

    @Test fun selectedDescendantsAreCoveredByTheirAncestor() {
        val fr = MapClipboard.copy(s, l, listOf(a1, a))
        assertEquals(listOf(a), fr.state.roots)
    }

    @Test fun severalTopsComeInTreeOrder() {
        val fr = MapClipboard.copy(s, l, listOf(other, c, a1))
        assertEquals(listOf(a1, c, other), fr.state.roots)
        assertEquals(LayoutKind.RIGHT, fr.state.layout[other])
        assertValid(fr.state)
    }

    @Test fun relationsBetweenSeparateTopsAreKept() {
        val fr = MapClipboard.copy(s, l, listOf(a1, c))
        assertEquals(setOf(RelationId("out")), fr.state.relations.keys)
    }

    @Test fun collapsedPartsAreCopiedAndAHiddenTopUsesItsVisibleAncestor() {
        val lc = MapLayout.compute(s, setOf(a), FixedMeasure())
        val whole = MapClipboard.copy(s, lc, listOf(a))
        assertEquals(setOf(a, a1, a2), whole.state.nodes.keys)
        val hidden = MapClipboard.copy(s, lc, listOf(a2))
        assertEquals(lc.rectOf(a)!!.topLeft, hidden.state.rootOrigin[a2])
    }

    @Test fun nothingToCopy() {
        val fr = MapClipboard.copy(s, l, listOf(id("ghost")))
        assertTrue(fr.isEmpty)
        assertNull(fr.bounds)
    }
}
