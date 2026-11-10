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
class NetworkFileRelationDomainTest {
    private fun id()=UUID.randomUUID().toString()
    private fun relation(a:String,b:String,kind:RelationKind=RelationKind.RELATED,owner:String=id(),label:String="Remark"):MapFileRelationRow {
        val uri=Uri.parse("content://file-relations/document/$owner")
        val at=BacklinkLocation(uri,setOf(uri.toString()),MapSaves.key(uri),owner,"Owner")
        return MapFileRelationRow(at,MapRelation(from="card-a",to="card-b",kind=kind,label=label),a,b,"A","B",BacklinkOrigin.STORED)
    }
    private fun concept(a:String,b:String):NetworkConceptProjection {
        val g=id();val uri=Uri.parse("content://file-relations/document/$g")
        val at=BacklinkLocation(uri,setOf(uri.toString()),MapSaves.key(uri),g,"Graph")
        return NetworkConceptProjection(NetworkConceptKey(at.key,g,"concept"),at,"Concept",NodeKind.TEXT,
            listOf(a,b).mapIndexed {i,f->MapBacklinkRow(at,"concept","source$i",MapSourceTarget(f,SourceScope.FILE),
                "Concept",NodeKind.TEXT,"Source","",null,SourceStatus.UNVERIFIED,null,BacklinkOrigin.STORED)})
    }
    @Test fun manualOnlyNetworkHasRealEndpointsAndAggregatesCrossGraphEvidenceWithoutOwnership() {
        val a=id();val b=id();val first=relation(a,b);val reverse=relation(b,a)
        val report=LocalNetworkAssembler.assemble(NetworkQuery(a,"A",emptyList()),emptyList(),issuer=Any(),generation=0,
            fileRelations=listOf(first,reverse))
        assertTrue(report.complete);assertEquals(setOf(a,b),report.files.map {it.documentId}.toSet())
        assertTrue(report.concepts.isEmpty());assertEquals(2,report.fileRelations.size)
        assertEquals(1,report.aggregatedFileRelations.size)
        val scene=LocalNetworkSceneBuilder().build(report,LocalNetworkLabels("File","Concept","Ink","Reference","References %d","Belongs",userRelation="User relation"))
        scene.document.validate();assertEquals(2,scene.document.nodes.size);assertEquals(1,scene.document.relations.size)
        assertEquals(RelationKind.RELATED,scene.document.relations.single().kind)
        assertTrue(scene.document.relations.single().label.contains("User relation"))
        assertTrue(scene.edges.values.single() is NetworkSceneEdgeKey.UserRelation)
        val directed=relation(a,b,RelationKind.DEPENDS_ON);val back=relation(b,a,RelationKind.DEPENDS_ON)
        assertNotEquals(NetworkFileRelationKey.of(directed),NetworkFileRelationKey.of(back))
    }
    @Test fun relationAndConceptPathsAlternateOnlyThroughExplicitExpandedEndpoints() {
        val a=id();val b=id();val c=id();val d=id();val r=relation(a,b);val p=concept(b,c);val tail=relation(c,d)
        val first=NetworkExpansion.select(a,emptySet(),listOf(p),listOf(r,tail))
        assertEquals(listOf(r),first.fileRelations);assertTrue(first.concepts.isEmpty())
        val all=NetworkExpansion.select(a,setOf(b,c),listOf(p),listOf(r,tail))
        assertEquals(setOf(r.key,tail.key),all.fileRelations.map {it.key}.toSet());assertEquals(listOf(p),all.concepts)
        assertEquals(listOf(r),all.origins.getValue(p.key).relationPath)
        assertEquals(listOf(r),all.relationOrigins.getValue(tail.key).relationPath)
        assertTrue(all.relationOrigins.getValue(tail.key).path.isNotEmpty())
        val blocked=r.copy(blocked=BacklinkProblem.DUPLICATE)
        val stopped=NetworkExpansion.select(a,setOf(b,c),listOf(p),listOf(blocked,tail))
        assertEquals(listOf(blocked),stopped.fileRelations);assertTrue(stopped.concepts.isEmpty())
        assertTrue(NetworkExpansion.select(r.location.graphId,emptySet(),emptyList(),listOf(r)).fileRelations.isEmpty())
    }
    @Test fun filterMatchesOneActualEndpointAndRetainsTheWholeRelationAndContext() {
        val a=id();val b=id();val r=relation(a,b)
        val filter=NetworkFilter(kinds=setOf(NetworkFileKind.NOTE))
        val facts=NetworkFileFacts(mapOf(a to NetworkFileFact(NetworkFileKind.NOTE),b to NetworkFileFact(NetworkFileKind.MAP),
            r.location.graphId to NetworkFileFact(NetworkFileKind.MAP)))
        val result=NetworkFilterSelector.selectRelations(listOf(r),filter,facts)
        assertEquals(listOf(r),result.rows);assertEquals(setOf(a),result.matched)
        assertTrue(NetworkFilterSelector.selectRelations(listOf(r),NetworkFilter(fileIds=setOf(r.location.graphId)),facts).rows.isEmpty())
    }
    @Test fun relationRowsShareAllBudgetsAndDirectEvidenceSurvivesAnEarlierDeepLayer() {
        val a=id();val b=id();val c=id();val direct=relation(a,b);val deeper=relation(b,c)
        val query=NetworkQuery(a,"A",emptyList())
        val expanded=NetworkExpansion.select(a,setOf(b),emptyList(),listOf(deeper,direct))
        val report=LocalNetworkAssembler.assemble(query,emptyList(),budget=LocalNetworkBudget(maxEvidence=1),issuer=Any(),generation=0,
            fileRelations=expanded.fileRelations,relationOrigins=expanded.relationOrigins)
        assertEquals(listOf(direct),report.fileRelations);assertTrue(report.limited)
        assertEquals(setOf(a,b),report.files.map {it.documentId}.toSet())
        assertTrue(LocalNetworkAssembler.assemble(query,emptyList(),budget=LocalNetworkBudget(maxSceneNodes=1),issuer=Any(),generation=0,
            fileRelations=listOf(direct)).fileRelations.isEmpty())
    }
    @Test fun aBudgetRejectedPrefixCannotLeaveADisconnectedDeeperRelation() {
        val a=id();val b=id();val c=id();val prefix=concept(a,b);val tail=relation(b,c)
        val expanded=NetworkExpansion.select(a,setOf(b),listOf(prefix),listOf(tail))
        val report=LocalNetworkAssembler.assemble(NetworkQuery(a,"A",emptyList()),expanded.concepts,
            budget=LocalNetworkBudget(maxSceneNodes=3),issuer=Any(),generation=0,origins=expanded.origins,
            fileRelations=expanded.fileRelations,relationOrigins=expanded.relationOrigins)
        assertTrue(report.concepts.isEmpty());assertTrue(report.fileRelations.isEmpty());assertTrue(report.limited)
        assertEquals(listOf(a),report.files.map {it.documentId})
    }

}
