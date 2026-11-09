package com.xnotes.mindmap

import android.net.Uri
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class NetworkExpansionTest {
    private val s=id();private val t=id();private val u=id();private val v=id()
    private fun id()=UUID.randomUUID().toString()
    private fun concept(node:String,files:List<String>,owner:String=id()):NetworkConceptProjection {
        val uri=Uri.parse("content://expansion/document/$owner")
        val location=BacklinkLocation(uri,setOf(uri.toString()),MapSaves.key(uri),owner,"Graph $node")
        return NetworkConceptProjection(NetworkConceptKey(location.key,owner,node),location,node,NodeKind.TEXT,
            files.mapIndexed {at,file->MapBacklinkRow(location,node,"$node-$at",MapSourceTarget(file,SourceScope.FILE),
                node,NodeKind.TEXT,"File $at","Original source remark",null,SourceStatus.UNVERIFIED,null,BacklinkOrigin.STORED)})
    }
    @Test fun oneExplicitFileExpansionAddsOnlyItsNextLayerWithEveryOriginalMember() {
        val a=concept("a",listOf(s,t,t));val b=concept("b",listOf(t,u));val c=concept("c",listOf(u,v))
        val all=listOf(c,b,a)
        assertEquals(setOf(a.key),NetworkExpansion.select(s,emptySet(),all).concepts.map {it.key}.toSet())
        val first=NetworkExpansion.select(s,setOf(t),all)
        assertEquals(setOf(a.key,b.key),first.concepts.map {it.key}.toSet())
        assertEquals(NetworkOriginKind.SOURCE,first.origins.getValue(b.key).kind)
        assertEquals(t,first.origins.getValue(b.key).fileId);assertEquals(2,first.origins.getValue(b.key).depth)
        assertEquals(setOf(s,t),first.origins.getValue(b.key).path.map {it.target.documentId}.toSet())
        assertEquals(a.members,first.concepts.single {it.key==a.key}.members)
        assertEquals(setOf(a.key,b.key,c.key),NetworkExpansion.select(s,setOf(t,u),all).concepts.map {it.key}.toSet())
    }
    @Test fun collapsingOneRoutePreservesSharedConceptsReachableThroughAnotherAndCyclesTerminate() {
        val a=concept("a",listOf(s,t,v));val b=concept("b",listOf(t,u));val c=concept("c",listOf(v,u));val cycle=concept("cycle",listOf(u,t))
        val all=listOf(a,b,c,cycle)
        val expanded=NetworkExpansion.select(s,setOf(t,v,u),all)
        assertEquals(4,expanded.concepts.size)
        val folded=NetworkExpansion.select(s,setOf(v,u),all)
        assertEquals(expanded.concepts.map {it.key}.toSet(),folded.concepts.map {it.key}.toSet())
        assertEquals(u,folded.origins.getValue(b.key).fileId)
        assertEquals(setOf(a.key),NetworkExpansion.select(s,emptySet(),all).concepts.map {it.key}.toSet())
        assertTrue(folded.origins.values.all {it.depth<=4})
    }
    @Test fun anExpandedOwningMapUsesOwnershipWithoutInventingAContentReference() {
        val owner=id();val a=concept("a",listOf(s,t),owner);val b=concept("b",listOf(u,v),owner)
        val query=NetworkQuery(s,"S",emptyList());val found=NetworkExpansion.select(s,setOf(owner),listOf(a,b))
        val origin=found.origins.getValue(b.key)
        assertEquals(NetworkOriginKind.OWNER,origin.kind);assertEquals(owner,origin.fileId)
        assertFalse(b.members.any {it.target.documentId==owner})
        val target=b.members.last()
        assertNull(MapNetworkOpenRequest.capture(query,target,target,Any()) {true})
        val request=MapNetworkOpenRequest.captureExpanded(query,origin,target,target,Any()) {true}
        assertNotNull(request);assertTrue(request!!.current());assertEquals(s,request.query.focusId)
        assertNull(MapNetworkOpenRequest.captureExpanded(query,NetworkConceptOrigin(id(),NetworkOriginKind.OWNER,2),target,target,Any()) {true})
    }
    @Test fun aDeeperSourceOpenKeepsItsRealLocalAnchorAndTheLegacyRootRuleStaysStrict() {
        val a=concept("a",listOf(s,t));val b=concept("b",listOf(t,u));val found=NetworkExpansion.select(s,setOf(t),listOf(a,b))
        val query=NetworkQuery(s,"S",emptyList());val anchor=b.members.first();val target=b.members.last()
        assertNull(MapNetworkOpenRequest.capture(query,anchor,target,Any()) {true})
        var current=true
        val request=MapNetworkOpenRequest.captureExpanded(query,found.origins.getValue(b.key),anchor,target,Any()) {current}!!
        assertEquals(t,request.anchor.target.documentId);assertEquals(u,request.target.target.documentId)
        assertTrue(request.current());assertNull(request.withPresentationGuard {false})
        val narrowed=request.withPresentationGuard {true}!!;current=false
        assertFalse(request.current());assertFalse(narrowed.current())
    }
    @Test fun onlyTheExplicitExpandedAssemblerAcceptsAValidDeeperOriginAndKeepsWholeConceptBudgets() {
        val a=concept("a",listOf(s,t));val b=concept("b",listOf(t,u));val query=NetworkQuery(s,"S",emptyList())
        val old=LocalNetworkAssembler.assemble(query,listOf(b),issuer=Any(),generation=0)
        assertTrue(old.concepts.isEmpty());assertFalse(old.complete)
        val found=NetworkExpansion.select(s,setOf(t),listOf(a,b))
        val report=LocalNetworkAssembler.assemble(query,found.concepts,issuer=Any(),generation=1,origins=found.origins)
        assertEquals(2,report.concepts.size);assertTrue(report.complete)
        assertEquals(listOf(t,u),report.concepts.single {it.key==b.key}.members.map {it.fileId})
        val wrong=LocalNetworkAssembler.assemble(query,listOf(b),issuer=Any(),generation=0,
            origins=mapOf(b.key to NetworkConceptOrigin(v,NetworkOriginKind.SOURCE,2)))
        assertTrue(wrong.concepts.isEmpty());assertFalse(wrong.complete)
    }
    @Test fun aBlockedEarlierRouteCannotAuthorizeAHealthyNextLayerOrBecomeAnExpansionBridge() {
        val a=concept("a",listOf(s,t));val b=concept("b",listOf(t,u));val query=NetworkQuery(s,"S",emptyList())
        for(problem in listOf(BacklinkProblem.DUPLICATE,BacklinkProblem.UNKNOWN_SCOPE,BacklinkProblem.RECOVERY_CONFLICT)) {
            val blocked=a.copy(members=a.members.map {it.copy(blocked=problem)})
            val result=NetworkExpansion.select(s,setOf(t),listOf(blocked,b))
            assertEquals(listOf(blocked.key),result.concepts.map {it.key})
            val route=NetworkConceptOrigin(t,NetworkOriginKind.SOURCE,2,blocked.members)
            assertNull(MapNetworkOpenRequest.captureExpanded(query,route,b.members.first(),b.members.last(),Any()) {true})
        }
    }
}
