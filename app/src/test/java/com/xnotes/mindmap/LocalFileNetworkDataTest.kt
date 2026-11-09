package com.xnotes.mindmap

import com.xnotes.core.geometry.Rect
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual provider bytes, recovery winners and shared actors; the network never opens native sources. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class LocalFileNetworkDataTest {
    private fun scenario(block:suspend (MapBacklinkLibraryTest.Fixture)->Unit)=MapBacklinkLibraryTest().scenario(block=block)
    private fun id()=UUID.randomUUID().toString()
    private data class Sources(val center:MapSource,val first:MapSource,val second:MapSource,val unrelated:MapSource)
    private fun sources():Sources {
        val center=MapSource(documentId=id(),fileName="Current source.xnote",note="The real center anchor")
        val first=MapSource(documentId=id(),fileName="Another source.xnote",scope=SourceScope.PAGE,pageId=id(),
            region=Rect(-24.0,40.0,120.0,88.0),lastKnownPage=2,note="First region")
        return Sources(center,first,first.copy(id=id(),pageId=id(),region=null,lastKnownPage=7,note="Second permanent page"),
            MapSource(documentId=id(),fileName="Unrelated.xnote"))
    }
    private fun graph(s:Sources)=MindMapDocument(nodes=listOf(MapNode(id="root",parent=null,text="The map"),
        MapNode(id="concept",parent="root",text="One concept has three real memberships",sources=listOf(s.center,s.first,s.second),primarySource=s.second.id),
        MapNode(id="unrelated",parent="root",text="An ordinary relation cannot turn this into a file reference",sources=listOf(s.unrelated))),
        relations=listOf(MapRelation(from="concept",to="unrelated",kind=RelationKind.DEPENDS_ON)))
    private suspend fun await(condition:()->Boolean)=withContext(Dispatchers.Default) {
        withTimeout(15000) {while(!withContext(Dispatchers.Main) {condition()}) delay(1)}
    }
    private suspend fun controller(f:MapBacklinkLibraryTest.Fixture,focus:String,budget:LocalNetworkBudget=LocalNetworkBudget(),
        block:suspend (LocalFileNetworkController)->Unit)=withContext(Dispatchers.Main) {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val c=LocalFileNetworkController(f.context,scope,NetworkQuery(focus,"Current source",listOf(f.tree.toString())),budget)
        try {
            assertTrue(c.refresh());await {!c.loading && !c.filtering && c.report!=null}
            block(c)
        } finally {c.close();scope.cancel()}
    }
    private fun members(report:LocalFileNetworkReport)=report.concepts.flatMap {it.members}
    private fun sceneEdges(report:LocalFileNetworkReport)=report.concepts.sumOf {c->c.members.map {it.row.target.documentId}.distinct().size+1}

    @Test fun oneActualObservationProjectsEverySourceOfTheMatchingConceptWithoutFollowingOrdinaryRelations()=scenario {f->
        val s=sources();val doc=graph(s);val file=f.graph("never-opened.xmap",doc);val before=file.readBytes()
        val journals=f.journalSnapshot();val reader=MapBacklinkLibrary(f.context,listOf(f.tree.toString()))
        val scan=reader.scanNetwork(s.center.documentId,emptyList())
        assertFalse(scan.limited);assertTrue(scan.issues.isEmpty());assertEquals(1,scan.graphs.size)
        val concept=scan.graphs.single().concepts.single()
        assertEquals(NetworkConceptKey(MapSaves.key(f.uri(file)),doc.id,"concept"),concept.key)
        assertEquals(listOf(s.center.id,s.first.id,s.second.id),concept.members.map {it.sourceId})
        assertEquals(listOf(s.center.target,s.first.target,s.second.target),concept.members.map {it.target})
        assertEquals(listOf(null,2,7),concept.members.map {it.lastKnownPage})
        assertTrue(concept.members.all {it.origin==BacklinkOrigin.STORED && it.membership==null && it.blocked==null})
        assertFalse(concept.members.any {it.target.documentId==s.unrelated.documentId})
        assertEquals("One canonical graph is opened once, not once per co-source edge",1,f.reads.get())
        assertArrayEquals(before,file.readBytes());assertEquals(journals,f.journalSnapshot())
        assertTrue(f.storage.registry.knownMapLocations().isEmpty());assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }

    @Test fun aRawRetainedAliasBProjectsItsCoSourcesWithoutClearingRecoveryOrClaimingTheLiveAActor()=scenario {f->
        val s=sources();val a=graph(s);val added=MapSource(documentId=id(),fileName="Only retained B contains this.xnote")
        val b=a.update("concept") {it.copy(sources=it.sources+added)}
        val file=f.graph("alias.xmap",a);val bare=f.uri(file,false);val tree=f.uri(file,true)
        val lease=f.acquire(bare);assertFalse(withContext(Dispatchers.Main) {lease.session.loadFailed})
        val actual=MapFiles.target(f.context,bare)
        val reject=object:MapTarget {override fun read()=actual.read();override fun write(bytes:ByteArray):Unit=throw IOException("Before external write")}
        val old=MapStore(File(f.context.noBackupFilesDir,"mind-map-recovery"),tree.toString(),reject)
        old.open();assertThrows(IOException::class.java) {old.save(b)}
        val bytes=file.readBytes();val journals=f.journalSnapshot()
        val cold=MapBacklinkLibrary(f.context,listOf(f.tree.toString())).scanNetwork(s.center.documentId,emptyList())
        val projected=cold.graphs.single().concepts.single().members
        assertEquals(listOf(s.center.id,s.first.id,s.second.id,added.id),projected.map {it.sourceId})
        assertTrue(projected.all {it.origin==BacklinkOrigin.RECOVERY})
        val live=withContext(Dispatchers.Main) {MapDocuments.observations(f.context)};val reads=f.reads.get()
        val scan=MapBacklinkLibrary(f.context,listOf(f.tree.toString())).scanNetwork(s.center.documentId,live)
        assertTrue(scan.graphs.isEmpty());assertEquals(BacklinkProblem.RECOVERY_CONFLICT,scan.liveBlocked[MapSaves.key(bare)])
        assertEquals("A live canonical actor suppresses disk reads even when an alias has retained B",reads,f.reads.get())
        assertEquals(a,withContext(Dispatchers.Main) {lease.session.document})
        assertEquals(journals,f.journalSnapshot());assertArrayEquals(bytes,file.readBytes())
        assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }

    @Test fun liveC1OnlyOverlaysAndBothOriginalMembershipLifetimesMustSurviveForACoSourceFocus()=scenario {f->
        val s=sources();val doc=graph(s);val file=f.graph("live.xmap",doc);val before=file.readBytes()
        controller(f,s.center.documentId) {c->
            val stored=members(c.report!!);val storedAnchor=stored.single {it.row.sourceId==s.center.id}
            val storedTarget=stored.single {it.row.sourceId==s.second.id}
            assertNotNull(c.captureFocus(storedAnchor,storedTarget))
            val lease=MapDocuments.acquire(f.context,f.uri(file)).also(f.leases::add)
            await {!lease.session.loading && !c.loading && !c.filtering && members(c.report!!).all {it.row.origin==BacklinkOrigin.LIVE}}
            val session=lease.session;assertFalse(session.loadFailed)
            assertNull("A stored display row cannot re-sign against an actor opened by somebody else",c.captureFocus(storedAnchor,storedTarget))
            val live=members(c.report!!);val anchor=live.single {it.row.sourceId==s.center.id};val target=live.single {it.row.sourceId==s.second.id}
            val original=c.captureFocus(anchor,target)!!;assertTrue(original.current())
            assertEquals(s.center.documentId,original.query.focusId);assertEquals(s.second.target,original.target.target)
            val trees=f.treeQueries.get();val reads=f.reads.get()
            val panel=SourceListEditor(session,session.sourceEditSnapshot("concept")!!)
            try {assertTrue(panel.note(s.second.id,"Metadata C1 keeps the same member"));assertTrue(panel.move(s.second.id,-1))} finally {panel.close()}
            await {members(c.report!!).any {it.row.sourceId==s.second.id && it.row.sourceNote=="Metadata C1 keeps the same member"}}
            assertEquals(trees,f.treeQueries.get());assertEquals(reads,f.reads.get())
            assertTrue(original.current());assertNotNull(c.captureFocus(anchor,target))
            assertTrue(session.operate {it.update("concept") {node->node.copy(sources=node.sources.filterNot {it.id==s.center.id})}})
            await {c.report!!.concepts.isEmpty()};assertFalse(original.current())
            session.undo();await {c.report!!.concepts.size==1}
            assertNull("Delete/undo must not resurrect the original anchor proof",c.captureFocus(anchor,target))
            val afterUndo=members(c.report!!);val newAnchor=afterUndo.single {it.row.sourceId==s.center.id};val newTarget=afterUndo.single {it.row.sourceId==s.second.id}
            val targetAttempt=c.captureFocus(newAnchor,newTarget)!!
            val otherPage=id()
            assertTrue(session.operate {it.update("concept") {node->node.copy(sources=node.sources.map {src->if(src.id==s.second.id) src.copy(pageId=otherPage) else src})}})
            await {members(c.report!!).single {it.row.sourceId==s.second.id}.row.target.pageId==otherPage}
            session.undo();await {members(c.report!!).single {it.row.sourceId==s.second.id}.row.target==s.second.target}
            assertFalse("A target A→B→A change must retire the old target half",targetAttempt.current())
            assertNull(c.captureFocus(newAnchor,newTarget))
            val latest=members(c.report!!)
            assertNotNull(c.captureFocus(latest.single {it.row.sourceId==s.center.id},latest.single {it.row.sourceId==s.second.id}))
            assertEquals(trees,f.treeQueries.get());assertEquals(reads,f.reads.get());assertTrue(session.dirty)
            assertArrayEquals(before,file.readBytes());assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
        }
    }

    @Test fun finalSceneEdgesNodesAndEvidenceHaveIndependentGlobalBudgetsAndKeepWholeConcepts()=scenario {f->
        val files=List(7) {id()};val root=MapNode(id="root",parent=null,text="Owner graph")
        val concepts=List(100) {n->MapNode(id="c$n",parent="root",text="Concept $n",
            sources=files.mapIndexed {i,file->MapSource(documentId=file,fileName="File $i.xnote")})}
        val doc=MindMapDocument(nodes=listOf(root)+concepts);val file=f.graph("edge-budget.xmap",doc);val original=file.readBytes()
        controller(f,files.first()) {c->
            val report=c.report!!
            assertEquals("700 members fit, but 100×(7 content + 1 owner) edges do not",93,report.concepts.size)
            assertEquals(744,sceneEdges(report));assertEquals(651,members(report).size)
            assertEquals((files+doc.id).toSet(),report.files.map {it.documentId}.toSet())
            assertEquals(101,report.files.size+report.concepts.size)
            assertTrue(report.concepts.all {it.members.size==7 && it.members.any {m->m.row.target.documentId==files.first()}})
            assertFalse(report.complete);assertTrue(report.issues.any {it.problem==BacklinkProblem.LIMIT})
        }
        controller(f,files.first(),LocalNetworkBudget(maxSceneNodes=108,maxSceneEdges=800,maxEvidence=700)) {c->
            assertEquals(100,c.report!!.concepts.size);assertEquals(108,c.report!!.files.size+c.report!!.concepts.size)
            assertEquals(800,sceneEdges(c.report!!));assertEquals(700,members(c.report!!).size);assertTrue(c.report!!.complete)
        }
        controller(f,files.first(),LocalNetworkBudget(maxSceneNodes=250,maxSceneEdges=800,maxEvidence=699)) {c->
            assertEquals(99,c.report!!.concepts.size);assertEquals(693,members(c.report!!).size)
            assertFalse(c.report!!.complete);assertTrue(c.report!!.issues.any {it.problem==BacklinkProblem.LIMIT})
        }
        controller(f,files.first(),LocalNetworkBudget(maxSceneNodes=20,maxSceneEdges=800,maxEvidence=5000)) {c->
            assertEquals(12,c.report!!.concepts.size);assertEquals(20,c.report!!.files.size+c.report!!.concepts.size)
            assertEquals(96,sceneEdges(c.report!!));assertTrue(c.report!!.issues.any {it.problem==BacklinkProblem.LIMIT})
        }
        assertArrayEquals(original,file.readBytes());assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }

    @Test fun duplicateGraphUuidsKeepCanonicalConceptsDistinctAndStayBlockedWhenOneCopyIsLive()=scenario {f->
        val s=sources();val doc=graph(s);val a=f.graph("a.xmap",doc);val b=f.graph("b.xmap",doc)
        val beforeA=a.readBytes();val beforeB=b.readBytes()
        controller(f,s.center.documentId) {c->
            fun checkDuplicate() {
                val report=c.report!!
                assertEquals(2,report.concepts.size);assertEquals(2,report.concepts.map {it.key.locationKey}.toSet().size)
                assertEquals(setOf(doc.id),report.concepts.map {it.key.graphId}.toSet())
                assertEquals(1,report.files.count {it.documentId==doc.id})
                assertTrue(members(report).all {it.row.blocked==BacklinkProblem.DUPLICATE})
                assertTrue(report.issues.any {it.problem==BacklinkProblem.DUPLICATE});assertFalse(report.complete)
                for(concept in report.concepts) assertNull(c.captureFocus(concept.members.single {it.row.sourceId==s.center.id},
                    concept.members.single {it.row.sourceId==s.second.id}))
            }
            checkDuplicate()
            val lease=MapDocuments.acquire(f.context,f.uri(a)).also(f.leases::add)
            await {!lease.session.loading && !c.loading && !c.filtering && c.report!!.concepts.any {it.members.all {m->m.row.origin==BacklinkOrigin.LIVE}}}
            checkDuplicate();assertEquals(doc,lease.session.document)
            assertArrayEquals(beforeA,a.readBytes());assertArrayEquals(beforeB,b.readBytes())
            assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
        }
    }
}
