package com.xnotes.map

import com.xnotes.core.geometry.Pt
import com.xnotes.core.testkit.assertTrue
import kotlin.test.Test

class MapInvariantsTest {

    private val b = StateBuilder()
    private val r = b.root("r")
    private val a = b.child(r, "a")
    private val a1 = b.child(a, "a1")
    private val good = b.build()

    private fun broken(s: MapState) = assertTrue("expected a violation in $s", MapInvariants.check(s).isNotEmpty())

    @Test fun aWellFormedStatePasses() {
        assertValid(good)
        assertValid(MapState.EMPTY)
        assertValid(good.copy(children = good.children + (a1 to emptyList())))
    }

    @Test fun danglingReferencesFail() {
        broken(good.copy(parent = good.parent + (id("x") to r)))
        broken(good.copy(roots = good.roots + id("ghost")))
        broken(good.copy(relations = mapOf(RelationId("q") to Relation(RelationId("q"), a, id("ghost")))))
        broken(good.copy(offsets = mapOf(id("ghost") to Vec(1.0, 1.0))))
    }

    @Test fun everyNodeIsExactlyRootOrChild() {
        broken(good.copy(roots = good.roots + a, rootOrigin = good.rootOrigin + (a to Pt(0.0, 0.0)), layout = good.layout + (a to LayoutKind.RIGHT)))
        broken(good.copy(nodes = good.nodes + (id("lost") to MapNode(id("lost")))))
    }

    @Test fun childrenListsMatchParents() {
        broken(good.copy(children = good.children + (r to listOf(a, a))))
        broken(good.copy(children = good.children - a))
    }

    @Test fun cyclesFail() {
        val p = good.parent + (a to a1)
        broken(good.copy(parent = p, children = mapOf(r to emptyList(), a1 to listOf(a), a to listOf(a1))))
    }

    @Test fun rootOnlyEntries() {
        broken(good.copy(rootOrigin = good.rootOrigin - r))
        broken(good.copy(layout = good.layout - r))
        broken(good.copy(rootOrigin = good.rootOrigin + (a to Pt(1.0, 1.0))))
        broken(good.copy(offsets = mapOf(r to Vec(1.0, 1.0))))
        broken(good.copy(layers = mapOf(a to "L")))
    }

    @Test fun zeroOffsetsFail() {
        broken(good.copy(offsets = mapOf(a to Vec.ZERO)))
    }

    @Test fun relationsMustJoinTwoDifferentNodesOnce() {
        broken(good.copy(relations = mapOf(RelationId("q") to Relation(RelationId("q"), a, a))))
        broken(good.copy(relations = mapOf(
            RelationId("q") to Relation(RelationId("q"), a, a1),
            RelationId("w") to Relation(RelationId("w"), a1, a),
        )))
        broken(good.copy(relations = mapOf(RelationId("q") to Relation(RelationId("w"), a, a1))))
    }
}
