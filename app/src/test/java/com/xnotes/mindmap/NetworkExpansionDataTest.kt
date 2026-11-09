package com.xnotes.mindmap

import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Expansion uses the same actual provider observation and shared actors as the direct network. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class NetworkExpansionDataTest {
    private fun id()=UUID.randomUUID().toString()
    private fun source(uuid:String)=MapSource(documentId=uuid,fileName="$uuid.xnote")
    private fun graph(vararg nodes:MapNode)=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null))+nodes)
    private fun concept(name:String,vararg sources:MapSource)=MapNode(id=name,parent="r",text=name,sources=sources.toList())
    private suspend fun await(check:()->Boolean)=withContext(Dispatchers.Default) {
        withTimeout(15000) {while(!withContext(Dispatchers.Main) {check()}) delay(1)}
    }
    private suspend fun controller(f:MapBacklinkLibraryTest.Fixture,focus:String,block:suspend(LocalFileNetworkController)->Unit)=withContext(Dispatchers.Main) {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val c=LocalFileNetworkController(f.context,scope,NetworkQuery(focus,"Center",listOf(f.tree.toString())))
        try {assertTrue(c.refresh());await {!c.loading && !c.filtering && c.report!=null};block(c)}
        finally {c.close();scope.cancel()}
    }
    @Test fun aSingleActualReadProjectsDirectDeepAndOwnedConceptsWithoutFollowingOrdinaryEdges()=MapBacklinkLibraryTest().scenario {f->
        val s=source(id());val t=source(id());val u=source(id());val unrelated=source(id())
        val doc=graph(concept("direct",s,t),concept("deep",t.copy(id=id()),u),concept("ordinary",unrelated))
            .copy(relations=listOf(MapRelation(from="direct",to="ordinary",kind=RelationKind.DEPENDS_ON)))
        val file=f.graph("one-read.xmap",doc);val bytes=file.readBytes();val journals=f.journalSnapshot()
        val first=MapBacklinkLibrary(f.context,listOf(f.tree.toString())).scanExpandedNetwork(s.documentId,setOf(t.documentId))
        assertEquals(setOf("direct","deep"),first.graphs.single().concepts.map {it.key.nodeId}.toSet())
        assertEquals(1,f.reads.get())
        val owned=MapBacklinkLibrary(f.context,listOf(f.tree.toString())).scanExpandedNetwork(s.documentId,setOf(doc.id))
        assertEquals(setOf("direct","deep","ordinary"),owned.graphs.single().concepts.map {it.key.nodeId}.toSet())
        assertEquals(2,f.reads.get());assertArrayEquals(bytes,file.readBytes());assertEquals(journals,f.journalSnapshot())
        assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get());assertTrue(f.storage.registry.knownMapLocations().isEmpty())
    }
    @Test fun deepBudgetCannotStopALaterGraphFromContributingTheOriginalFocus()=MapBacklinkLibraryTest().scenario {f->
        val s=source(id());val t=source(id());val u=source(id())
        val deep=f.graph("a-deep.xmap",graph(*Array(4) {concept("deep$it",t.copy(id=id()),u.copy(id=id()))}))
        val direct=f.graph("z-direct.xmap",graph(concept("direct",s,t)))
        val a=deep.readBytes();val b=direct.readBytes()
        val scan=MapBacklinkLibrary(f.context,listOf(f.tree.toString())).scanExpandedNetwork(s.documentId,setOf(t.documentId),
            networkBudget=LocalNetworkBudget(maxEvidence=4))
        val concepts=scan.graphs.flatMap {it.concepts}
        assertEquals("Deep and direct lightweight projections have separate quotas",3,concepts.size)
        assertEquals(4,concepts.filter {c->c.members.none {it.target.documentId==s.documentId}}.sumOf {it.members.size})
        assertEquals(2,concepts.single {it.key.nodeId=="direct"}.members.size)
        assertTrue(scan.limited);assertTrue(scan.issues.any {it.problem==BacklinkProblem.LIMIT});assertEquals(2,f.reads.get())
        assertArrayEquals(a,deep.readBytes());assertArrayEquals(b,direct.readBytes());assertEquals(0,f.writes.get())
    }
    @Test fun realControlsExpandCollapseAndFilterWholePathsWithoutWritingOrReusingOldActions()=MapBacklinkLibraryTest().scenario {f->
        val s=source(id());val t=source(id());val u=source(id());val v=source(id())
        val a=f.graph("a.xmap",graph(concept("st",s,t)))
        val b=f.graph("b.xmap",graph(concept("tu",t,u)))
        val d=f.graph("c.xmap",graph(concept("uv",u,v)))
        val bytes=listOf(a,b,d).map {it.readBytes()};val journals=f.journalSnapshot()
        controller(f,s.documentId) {c->
            assertEquals(setOf("st"),c.report!!.concepts.map {it.key.nodeId}.toSet())
            assertFalse(c.setExpanded(v.documentId,true));assertFalse(c.setExpanded(s.documentId,false))
            assertTrue(c.setExpanded(t.documentId,true));await {!c.loading && !c.filtering && c.report!!.concepts.size==2}
            assertTrue(c.setExpanded(u.documentId,true));await {!c.loading && !c.filtering && c.report!!.concepts.size==3}
            val tu=c.report!!.concepts.single {it.key.nodeId=="tu"}
            val request=c.captureFocus(tu.members.single {it.fileId==t.documentId},tu.members.single {it.fileId==u.documentId})!!
            assertEquals(t.documentId,request.anchor.target.documentId);assertTrue(request.current())
            val oldRevision=c.filterRevision
            assertTrue(c.applyFilter(NetworkFilter(fileIds=setOf(v.documentId))))
            assertEquals(setOf("st","tu","uv"),c.report!!.concepts.map {it.key.nodeId}.toSet())
            assertEquals(setOf("st","tu"),c.report!!.contextConcepts.map {it.nodeId}.toSet())
            assertEquals(setOf(v.documentId),c.report!!.matchedFiles)
            assertEquals(setOf(s.documentId,t.documentId,u.documentId,v.documentId),c.catalog.map {it.documentId}.toSet())
            assertFalse(request.current());assertFalse(c.collapseAll(oldRevision))
            assertTrue(c.applyFilter(NetworkFilter()));assertTrue(c.setExpanded(t.documentId,false))
            await {!c.loading && !c.filtering && c.report!!.concepts.size==1}
            assertTrue(c.expandedFiles.contains(u.documentId));assertFalse(request.current())
            assertTrue(c.setExpanded(t.documentId,true));await {!c.loading && !c.filtering && c.report!!.concepts.size==3}
            assertFalse(request.current());assertTrue(c.collapseAll());await {!c.loading && !c.filtering && c.report!!.concepts.size==1}
            assertTrue(c.expandedFiles.isEmpty())
        }
        listOf(a,b,d).forEachIndexed {i,file->assertArrayEquals(bytes[i],file.readBytes())}
        assertEquals(journals,f.journalSnapshot());assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }
    @Test fun anExpandedOwnerUsesTheActualTargetAsItsLocalAnchorWithoutInventingASource()=MapBacklinkLibraryTest().scenario {f->
        val s=source(id());val t=source(id());val u=source(id())
        val doc=graph(concept("direct",s,t),concept("owned-only",u));val file=f.graph("owner.xmap",doc)
        val bytes=file.readBytes()
        controller(f,s.documentId) {c->
            assertTrue(c.setExpanded(doc.id,true));await {!c.loading && !c.filtering && c.report!!.concepts.size==2}
            val direct=c.report!!.concepts.single {it.key.nodeId=="direct"}
            val owned=c.report!!.concepts.single {it.key.nodeId=="owned-only"}
            assertEquals(NetworkOriginKind.OWNER,owned.origin!!.kind);assertEquals(doc.id,owned.origin!!.fileId)
            assertEquals(listOf(u.id),owned.members.map {it.row.sourceId})
            val request=c.captureFocus(direct.members.first(),owned.members.single())!!
            assertSame(request.target,request.anchor);assertEquals(u.target,request.target.target);assertTrue(request.current())
            assertEquals(s.documentId,request.query.focusId)
        }
        assertArrayEquals(bytes,file.readBytes());assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }
    @Test fun livePrefixMembershipAbaRetiresADeepRequestWhileMetadataC1NeedsNoNewLibraryRead()=MapBacklinkLibraryTest().scenario {f->
        val s=source(id());val t=source(id());val u=source(id())
        val a=f.graph("prefix.xmap",graph(concept("st",s,t)));val b=f.graph("next.xmap",graph(concept("tu",t,u)))
        val bytes=a.readBytes();val next=b.readBytes()
        val lease=withContext(Dispatchers.Main) {MapDocuments.acquire(f.context,f.uri(a)).also(f.leases::add)}
        await {!lease.session.loading}
        controller(f,s.documentId) {c->
            assertTrue(c.setExpanded(t.documentId,true));await {!c.loading && !c.filtering && c.report!!.concepts.size==2}
            val deep=c.report!!.concepts.single {it.key.nodeId=="tu"}
            val request=c.captureFocus(deep.members.single {it.fileId==t.documentId},deep.members.single {it.fileId==u.documentId})!!
            val reads=f.reads.get();val trees=f.treeQueries.get()
            assertTrue(lease.session.operate {it.update("st") {node->node.copy(text="Metadata C1")}})
            lease.session.cancelPendingAutosave()
            await {c.report!!.concepts.any {it.title=="Metadata C1"}}
            assertTrue(request.current());assertEquals(reads,f.reads.get());assertEquals(trees,f.treeQueries.get())
            assertTrue(lease.session.operate {it.update("st") {node->node.copy(sources=listOf(s))}})
            lease.session.cancelPendingAutosave();await {c.report!!.concepts.size==1};assertFalse(request.current())
            lease.session.undo();lease.session.cancelPendingAutosave();await {c.report!!.concepts.size==2}
            assertFalse("A restored prefix cannot revive a request's old path membership",request.current())
            assertEquals(reads,f.reads.get());assertEquals(trees,f.treeQueries.get())
        }
        assertArrayEquals(bytes,a.readBytes());assertArrayEquals(next,b.readBytes());assertEquals(0,f.writes.get())
    }
}
