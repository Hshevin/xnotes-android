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
class NetworkOverviewDomainTest {
    private fun id()=UUID.randomUUID().toString()
    private fun concept(owner:String,files:List<String>):NetworkConceptProjection {
        val uri=Uri.parse("content://overview-domain/document/$owner")
        val location=BacklinkLocation(uri,setOf(uri.toString()),MapSaves.key(uri),owner,"Graph.xmap")
        return NetworkConceptProjection(NetworkConceptKey(location.key,owner,"node"),location,"Concept",NodeKind.TEXT,
            files.mapIndexed {i,file->MapBacklinkRow(location,"node","source-$i",MapSourceTarget(file,SourceScope.FILE),
                "Concept",NodeKind.TEXT,"Source.xnote","Remark",null,SourceStatus.UNVERIFIED,null,BacklinkOrigin.STORED)})
    }

    @Test fun aLibraryQueryDoesNotInventAFileAndIsolatedRegisteredFilesUseOnlyRemainingBudget() {
        val owner=id();val source=id();val isolated=List(20) {LocalNetworkFile(id(),listOf("Isolated"))}
        val query=NetworkQuery.library("Library",emptyList());val c=concept(owner,listOf(source))
        assertFalse(query.hasLocalFocus)
        val result=LocalNetworkAssembler.assemble(query,listOf(c),budget=LocalNetworkBudget(maxSceneNodes=4),
            issuer=Any(),generation=0,origins=mapOf(c.key to NetworkConceptOrigin(owner,NetworkOriginKind.OWNER,1)),
            rootFiles=emptyList(),extraFiles=isolated)
        assertEquals(listOf(c.key),result.concepts.map {it.key})
        assertEquals(setOf(owner,source,isolated.first().documentId),result.files.map {it.documentId}.toSet())
        assertFalse(result.files.any {it.documentId==query.focusId})
        assertFalse(result.complete);assertTrue(result.limited)
        val empty=LocalNetworkAssembler.assemble(query,emptyList(),issuer=Any(),generation=0)
        assertTrue(empty.files.isEmpty());assertTrue(empty.complete)
        val fabricated=concept(owner,listOf(query.focusId)).members.single()
        assertNull(MapNetworkOpenRequest.capture(query,fabricated,fabricated,Any()) {true})
    }

    @Test fun multipleRealRootsIncludeTheirOwnedConceptsWithoutChangingTheOldLocalMeaning() {
        val a=id();val b=id();val x=id();val y=id()
        val source=concept(id(),listOf(a,x));val owned=concept(b,listOf(y));val unrelated=concept(id(),listOf(id()))
        val all=listOf(unrelated,owned,source)
        val found=NetworkExpansion.selectRoots(setOf(a,b),emptySet(),all)
        assertEquals(setOf(source.key,owned.key),found.concepts.map {it.key}.toSet())
        assertEquals(NetworkOriginKind.OWNER,found.origins.getValue(owned.key).kind)
        assertEquals(1,found.origins.getValue(owned.key).depth)
        assertTrue(found.origins.values.all {it.path.isEmpty()})
        assertTrue(NetworkExpansion.select(b,emptySet(),all).concepts.isEmpty())
        val query=NetworkQuery.library("Collection",emptyList())
        val report=LocalNetworkAssembler.assemble(query,found.concepts,issuer=Any(),generation=1,origins=found.origins,
            rootFiles=listOf(LocalNetworkFile(a,listOf("A")),LocalNetworkFile(b,listOf("B"))))
        assertEquals(setOf(a,b,x,y,source.key.graphId),report.files.map {it.documentId}.toSet())
        val target=owned.members.single()
        assertNotNull(MapNetworkOpenRequest.captureExpanded(query,found.origins.getValue(owned.key),target,target,Any()) {true})
    }

    @Test fun aSelectedIsolatedFileSurvivesAndItsFilterUsesItsOwnIdentity() {
        val a=id();val b=id()
        val report=LocalNetworkAssembler.assemble(NetworkQuery.library("Collection",emptyList()),emptyList(),issuer=Any(),generation=0,
            rootFiles=listOf(LocalNetworkFile(a,listOf("A")),LocalNetworkFile(b,listOf("B"))))
        assertTrue(report.complete);assertEquals(setOf(a,b),report.files.map {it.documentId}.toSet())
        val facts=NetworkFileFacts(mapOf(a to NetworkFileFact(NetworkFileKind.NOTE),b to NetworkFileFact(null)))
        val (matched,unknown)=NetworkFilterSelector.selectFiles(setOf(a,b),NetworkFilter(kinds=setOf(NetworkFileKind.NOTE)),facts)
        assertEquals(setOf(a),matched);assertEquals(setOf(b),unknown)
        val explicit=NetworkFilterSelector.selectFiles(setOf(a,b),NetworkFilter(fileIds=setOf(b)),facts)
        assertEquals(setOf(b),explicit.first);assertTrue(explicit.second.isEmpty())
    }
}
