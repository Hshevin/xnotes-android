package com.xnotes.mindmap

import android.net.Uri
import com.xnotes.core.geometry.Rect
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Independent domain oracles: real IDs and member ranges, never inferred file-to-file edges. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class LocalNetworkDomainTest {
    private val s=UUID.randomUUID().toString()
    private val t=UUID.randomUUID().toString()
    private val g=UUID.randomUUID().toString()
    private val uri=Uri.parse("content://network-domain/document/root/graph.xmap")
    private val location=BacklinkLocation(uri,setOf(uri.toString()),MapSaves.key(uri),g,"Graph.xmap")
    private val query=NetworkQuery(s,"Current note",emptyList())
    private fun member(node:String,id:String,file:String,page:String?=null)=MapBacklinkRow(
        location,node,id,MapSourceTarget(file,if(page==null) SourceScope.FILE else SourceScope.PAGE,page,
            if(page==null) null else Rect(10.0,20.0,40.0,60.0)),"Concept",NodeKind.TEXT,"Recorded name.xnote","Remark",
        if(page==null) null else 2,SourceStatus.STALE,null,BacklinkOrigin.STORED)
    private fun concept(node:String,rows:List<MapBacklinkRow>)=NetworkConceptProjection(
        NetworkConceptKey(location.key,g,node),location,"Concept $node",NodeKind.TEXT,rows)
    private fun assemble(projections:List<NetworkConceptProjection>,budget:LocalNetworkBudget=LocalNetworkBudget())=
        LocalNetworkAssembler.assemble(query,projections,budget=budget,issuer=Any(),generation=1)
    private val labels=LocalNetworkLabels("File","Concept","Hand drawing","Reference","References: %1\$d","Belongs to")

    @Test fun fileIdentityAggregatesPositionsButEveryMemberAndItsConceptRemainExplainable() {
        val rows=listOf(member("c","anchor",s),member("c","page-one",t,UUID.randomUUID().toString()),
            member("c","page-two",t,UUID.randomUUID().toString()),member("c","map-source",g))
        val report=assemble(listOf(concept("c",rows)))
        assertTrue(report.complete);assertEquals(setOf(s,t,g),report.files.map {it.documentId}.toSet())
        assertEquals(rows,report.concepts.single().members.map {it.row})
        val scene=LocalNetworkSceneBuilder().build(report,labels)
        scene.document.validate()
        assertEquals(4,scene.document.nodes.size) // S, T, map G, and the concept.
        assertEquals(4,scene.document.relations.size) // Three referenced files plus distinct ownership.
        val conceptRender=scene.nodes.entries.single {it.value is NetworkSceneNodeKey.Concept}.key
        assertTrue(scene.document.relations.all {it.from==conceptRender})
        assertEquals(2,scene.document.relations.count {edge->scene.nodes[edge.to]==NetworkSceneNodeKey.File(g)})
        assertEquals(3,scene.edges.values.count {it is NetworkSceneEdgeKey.References})
        assertEquals(1,scene.edges.values.count {it is NetworkSceneEdgeKey.Belongs})
        assertEquals(2,report.concepts.single().members.count {it.row.target.documentId==t})
    }

    @Test fun ownershipEdgesAlsoCountAndTheLimitRetainsWholeConceptsWithoutDanglingFiles() {
        val others=List(6) {UUID.randomUUID().toString()}
        val concepts=List(100) {i->val node="c$i"
            concept(node,(listOf(s)+others).mapIndexed {at,file->member(node,"$node-$at",file)})}
        val report=assemble(concepts)
        assertFalse(report.complete);assertTrue(report.limited)
        assertEquals(93,report.concepts.size);assertEquals(651,report.concepts.sumOf {it.members.size})
        val scene=LocalNetworkSceneBuilder().build(report,labels)
        assertEquals(101,scene.document.nodes.size);assertEquals(744,scene.document.relations.size)
        assertTrue(report.concepts.all {it.members.size==7 && it.members.any {e->e.row.target.documentId==s}})
        val ids=scene.document.nodes.mapTo(hashSetOf()) {it.id}
        assertTrue(scene.document.relations.all {it.from in ids && it.to in ids})
        assertTrue(report.issues.any {it.problem==BacklinkProblem.LIMIT})
    }

    @Test fun displayChangesKeepStableRuntimeIdsAndPositionsAndNeverTurnFilesIntoParents() {
        val rows=listOf(member("c","anchor",s),member("c","other",t))
        val initial=assemble(listOf(concept("c",rows)))
        val builder=LocalNetworkSceneBuilder();val before=builder.build(initial,labels)
        val renamed=assemble(listOf(concept("c",rows.reversed().map {it.copy(nodeTitle="Renamed",sourceNote="New remark",sourceFileName="Moved name")} )
            .copy(title="Renamed concept")))
        val after=builder.build(renamed,labels)
        assertEquals(before.nodes,after.nodes);assertEquals(before.edges,after.edges)
        before.document.nodes.forEach {old->assertEquals(old.bounds,after.document.node(old.id).bounds)}
        assertEquals(1,after.document.nodes.count {it.parent==null && !it.free})
        assertTrue(after.document.nodes.all {it.parent==null})
        assertEquals(rows,initial.concepts.single().members.map {it.row})
    }

    @Test fun aNetworkFocusNamesBothTheAnchorAndTheExactCosourceWithoutInventingANativeContext() {
        val anchor=member("c","anchor",s);val target=member("c","other",t)
        var alive=true
        val request=MapNetworkOpenRequest.capture(query,anchor,target,Any()) {alive}
        assertNotNull(request);assertTrue(request!!.current())
        assertEquals(t,request.target.target.documentId);assertEquals(s,request.anchor.target.documentId)
        assertNull(MapNetworkOpenRequest.capture(query,anchor,target.copy(nodeId="other-concept"),Any()) {true})
        assertNull(MapNetworkOpenRequest.capture(query,target,anchor,Any()) {true})
        assertNull(request.withPresentationGuard {false})
        val narrowed=request.withPresentationGuard {true}!!
        alive=false;assertFalse(request.current());assertFalse(narrowed.current())
    }
}
