package com.xnotes.mindmap

import android.net.Uri
import com.xnotes.core.geometry.Rect
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class NetworkFilterSelectorTest {
    private val s=UUID.randomUUID().toString()
    private val t=UUID.randomUUID().toString()
    private val g=UUID.randomUUID().toString()
    private val other=UUID.randomUUID().toString()
    private val uri=Uri.parse("content://network-filter/document/root/graph.xmap")
    private val location=BacklinkLocation(uri,setOf(uri.toString()),MapSaves.key(uri),g,"Actual graph.xmap")
    private val folder=NetworkFolder("content://network-filter/tree/root/document/root/folder","Chosen folder")
    private fun row(node:String,id:String,file:String)=MapBacklinkRow(location,node,id,
        MapSourceTarget(file,SourceScope.PAGE,UUID.randomUUID().toString(),Rect(-4.0,7.0,44.0,99.0)),
        "Concept body",NodeKind.INK,"Recorded title.xnote","Independent remark",2,SourceStatus.STALE,null,BacklinkOrigin.STORED)
    private fun concept(node:String="one",files:List<String> = listOf(s,t,t))=NetworkConceptProjection(
        NetworkConceptKey(location.key,g,node),location,"A concept",NodeKind.INK,
        files.mapIndexed {i,file->row(node,"$node-$i",file)})
    private fun facts(vararg pairs:Pair<String,NetworkFileFact>)=NetworkFileFacts(pairs.toMap())
    private fun select(p:List<NetworkConceptProjection>,f:NetworkFilter,values:NetworkFileFacts=NetworkFileFacts(emptyMap()))=
        NetworkFilterSelector.select(p,f,values)

    @Test fun unlimitedAndEmptyCollectionsHaveDifferentMeaningsAndNeverCreateFileDependencies() {
        val c=concept()
        val all=select(listOf(c),NetworkFilter())
        assertFalse(NetworkFilter().active);assertEquals(listOf(c),all.concepts)
        assertEquals(setOf(s,t),all.matched);assertTrue(all.unknown.isEmpty());assertEquals(0,all.filteredConcepts)
        val empty=select(listOf(c),NetworkFilter(fileIds=emptySet()))
        assertTrue(NetworkFilter(fileIds=emptySet()).active);assertTrue(empty.concepts.isEmpty())
        assertTrue(empty.unknown.isEmpty());assertEquals(1,empty.filteredConcepts)
    }
    @Test fun matchingOneOfSeveralPagesRetainsTheOriginalWholeConceptIncludingUnmatchedSources() {
        val c=concept();val before=c.members.toList()
        val result=select(listOf(c),NetworkFilter(fileIds=setOf(t)))
        assertEquals(listOf(c),result.concepts);assertSame(c,result.concepts.single())
        assertEquals(before,result.concepts.single().members);assertEquals(setOf(t),result.matched)
        assertEquals(2,result.concepts.single().members.count {it.target.documentId==t})
        val report=LocalNetworkAssembler.assemble(NetworkQuery(s,"S",emptyList()),result.concepts,issuer=Any(),generation=1)
        val scene=LocalNetworkSceneBuilder().build(report,LocalNetworkLabels("File","Concept","Ink","Ref","Refs %d","Belongs"))
        assertEquals(3,scene.document.relations.size) // Two real file groups plus ownership.
        assertEquals(1,scene.document.relations.map {it.from}.distinct().size)
        assertEquals(before,c.members)
    }
    @Test fun anOwningMapIsContextAndOnlyMatchesIfItIsAlsoATrueContentSource() {
        val c=concept()
        val result=select(listOf(c),NetworkFilter(fileIds=setOf(g)))
        assertTrue(result.concepts.isEmpty());assertTrue(result.matched.isEmpty())
        val self=concept("self",listOf(s,g))
        assertEquals(listOf(self),select(listOf(self),NetworkFilter(fileIds=setOf(g))).concepts)
        assertTrue(select(listOf(c),NetworkFilter(fileIds=setOf(other))).concepts.isEmpty())
    }
    @Test fun conditionsAreCombinedOnTheSameFileRatherThanAcrossDifferentCosources() {
        val c=concept()
        val values=facts(t to NetworkFileFact(NetworkFileKind.CANVAS,NetworkFolderMatch.OUTSIDE),
            s to NetworkFileFact(NetworkFileKind.NOTE,NetworkFolderMatch.INSIDE),g to NetworkFileFact(NetworkFileKind.MAP,NetworkFolderMatch.INSIDE))
        val mismatch=select(listOf(c),NetworkFilter(fileIds=setOf(t),kinds=setOf(NetworkFileKind.CANVAS),folder=folder),values)
        assertTrue(mismatch.concepts.isEmpty());assertTrue(mismatch.unknown.isEmpty())
        val match=select(listOf(c),NetworkFilter(fileIds=setOf(s),kinds=setOf(NetworkFileKind.NOTE),folder=folder),values)
        assertEquals(listOf(c),match.concepts);assertEquals(setOf(s),match.matched)
    }
    @Test fun anUnknownFormatOrFolderNeverBecomesAnInventedPositiveOrACompleteEmptyResult() {
        val c=concept()
        val noFormat=select(listOf(c),NetworkFilter(kinds=setOf(NetworkFileKind.NOTE)))
        assertTrue(noFormat.concepts.isEmpty());assertEquals(setOf(s,t),noFormat.unknown)
        val noFolder=select(listOf(c),NetworkFilter(fileIds=setOf(t),folder=folder),
            facts(t to NetworkFileFact(NetworkFileKind.NOTE)))
        assertTrue(noFolder.concepts.isEmpty());assertEquals(setOf(t),noFolder.unknown)
    }
    @Test fun aDefiniteFalseDominatesUnknownRegardlessOfConditionOrder() {
        val c=concept()
        val outside=select(listOf(c),NetworkFilter(fileIds=setOf(t),kinds=setOf(NetworkFileKind.NOTE),folder=folder),
            facts(t to NetworkFileFact(null,NetworkFolderMatch.OUTSIDE)))
        assertTrue(outside.concepts.isEmpty());assertTrue(outside.unknown.isEmpty())
        val wrongKind=select(listOf(c),NetworkFilter(fileIds=setOf(t),kinds=setOf(NetworkFileKind.NOTE),folder=folder),
            facts(t to NetworkFileFact(NetworkFileKind.CANVAS,NetworkFolderMatch.UNKNOWN)))
        assertTrue(wrongKind.concepts.isEmpty());assertTrue(wrongKind.unknown.isEmpty())
        val inaccessibleFolder=select(listOf(c),NetworkFilter(fileIds=setOf(t),kinds=setOf(NetworkFileKind.NOTE),folder=folder),
            facts(t to NetworkFileFact(NetworkFileKind.CANVAS,NetworkFolderMatch.UNKNOWN,BacklinkProblem.UNAVAILABLE)))
        assertTrue(inaccessibleFolder.concepts.isEmpty());assertTrue(inaccessibleFolder.unknown.isEmpty())
        assertTrue(select(listOf(c),NetworkFilter(kinds=emptySet())).unknown.isEmpty())
    }
    @Test fun duplicateAndUnavailableFactsCannotBeTrustedAsPositiveEvidence() {
        val c=concept()
        for(problem in listOf(BacklinkProblem.DUPLICATE,BacklinkProblem.IDENTITY,BacklinkProblem.UNAVAILABLE)) {
            val result=select(listOf(c),NetworkFilter(fileIds=setOf(t),kinds=setOf(NetworkFileKind.NOTE),folder=folder),
                facts(t to NetworkFileFact(NetworkFileKind.NOTE,NetworkFolderMatch.INSIDE,problem)))
            assertTrue(result.concepts.isEmpty());assertEquals(setOf(t),result.unknown)
        }
    }
    @Test fun aDefiniteMatchKeepsItsUnknownCosourcesAsContextWithoutDroppingTheirRanges() {
        val c=concept()
        val result=select(listOf(c),NetworkFilter(kinds=setOf(NetworkFileKind.NOTE)),facts(s to NetworkFileFact(NetworkFileKind.NOTE)))
        assertEquals(listOf(c),result.concepts);assertEquals(setOf(s),result.matched);assertEquals(setOf(t),result.unknown)
        assertEquals(c.members.map {it.target},result.concepts.single().members.map {it.target})
    }
    @Test fun filteringBeforeTheSceneBudgetLetsALateMatchingConceptRemainWhole() {
        val projections=(0 until 200).map {concept("c$it")}
        val unique=concept("zzz",listOf(s,other))
        val selected=select(projections+unique,NetworkFilter(fileIds=setOf(other)))
        val report=LocalNetworkAssembler.assemble(NetworkQuery(s,"S",emptyList()),selected.concepts,
            budget=LocalNetworkBudget(maxSceneNodes=4,maxSceneEdges=3,maxEvidence=2),issuer=Any(),generation=1)
        assertTrue(report.complete);assertEquals(listOf(unique.key),report.concepts.map {it.key})
        assertEquals(2,report.concepts.single().members.size);assertEquals(200,selected.filteredConcepts)
    }
    @Test fun filterCollectionsAreBoundedCanonicalSnapshotsRatherThanCallerMutableSets() {
        val selected=mutableSetOf(t);val kinds=mutableSetOf(NetworkFileKind.NOTE)
        val filter=NetworkFilter(selected,kinds,folder)
        selected.clear();kinds.clear()
        assertEquals(setOf(t),filter.fileIds);assertEquals(setOf(NetworkFileKind.NOTE),filter.kinds)
        assertEquals(filter,NetworkFilter(setOf(t),setOf(NetworkFileKind.NOTE),folder));assertEquals(filter.hashCode(),NetworkFilter(setOf(t),setOf(NetworkFileKind.NOTE),folder).hashCode())
        assertThrows(IllegalArgumentException::class.java) {NetworkFilter(fileIds=setOf("same name is not an identity"))}
        assertThrows(IllegalArgumentException::class.java) {NetworkFilter(fileIds=(0..10000).mapTo(hashSetOf()) {UUID(0,it.toLong()).toString()})}
    }
}
