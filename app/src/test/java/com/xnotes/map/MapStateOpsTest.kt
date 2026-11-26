package com.xnotes.map

import com.xnotes.core.geometry.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Contract in MapStateOps.kt. */
class MapStateOpsTest {

    private val f = EditorFixture()
    private val e = f.editor

    @Test fun nudgedMatchesTheEditorForRootsAndChildren() {
        val r = f.root("r", at = Pt(10.0, 20.0))
        val a = f.child(r, "a")
        val start = f.state
        val moved = start.nudged(r, Vec(5.0, -3.0))
        assertEquals(Pt(15.0, 17.0), moved.rootOrigin.getValue(r))
        e.nudge(r, Vec(5.0, -3.0))
        assertEquals(f.state, moved)

        val s = f.state
        val child = s.nudged(a, Vec(4.0, 6.0)).nudged(a, Vec(1.0, 1.0))
        assertEquals(Vec(5.0, 7.0), child.offsets.getValue(a))
        e.nudge(a, Vec(4.0, 6.0)); e.nudge(a, Vec(1.0, 1.0))
        assertEquals(f.state, child)
        assertEquals("an offset back to zero is dropped", false, child.nudged(a, Vec(-5.0, -7.0)).offsets.containsKey(a))
        assertValid(child)
    }

    @Test fun nudgedIsTheSameStateWhenNothingMoves() {
        val r = f.root("r")
        val s = f.state
        assertSame(s, s.nudged(r, Vec.ZERO))
        assertSame(s, s.nudged(id("nope"), Vec(1.0, 1.0)))
    }

    @Test fun withoutSubtreesMatchesDelete() {
        val r = f.root("r")
        val a = f.child(r, "a")
        val b = f.child(r, "b")
        val a1 = f.child(a, "a1")
        val other = f.root("other", at = Pt(0.0, 500.0))
        e.addRelation(a1, other)
        e.addRelation(b, other)
        e.nudge(a1, Vec(3.0, 3.0))
        val s = f.state
        val pure = s.withoutSubtrees(listOf(a, other))
        e.delete(listOf(a, other))
        assertEquals(f.state, pure)
        assertEquals(listOf(b), pure.childrenOf(r))
        assertEquals(emptyMap<RelationId, Relation>(), pure.relations)
        assertValid(pure)
    }

    @Test fun withoutSubtreesOfNothingKnownIsTheSameState() {
        f.root("r")
        val s = f.state
        assertSame(s, s.withoutSubtrees(emptyList()))
        assertSame(s, s.withoutSubtrees(listOf(id("nope"))))
    }

    @Test fun rootsOnLayerTakeUnlayeredTreesForTheDefault() {
        e.beginRoot(Pt(0.0, 0.0)); val plain = e.commitDraft("plain")!!
        e.beginRoot(Pt(0.0, 100.0), "ink"); val inked = e.commitDraft("inked")!!
        e.beginRoot(Pt(0.0, 200.0)); val plain2 = e.commitDraft("plain2")!!
        val s = f.state
        assertEquals(listOf(plain, plain2), s.rootsOnLayer("default", isDefault = true))
        assertEquals(listOf(inked), s.rootsOnLayer("ink", isDefault = false))
        assertEquals(emptyList<NodeId>(), s.rootsOnLayer("other", isDefault = false))
    }
}
