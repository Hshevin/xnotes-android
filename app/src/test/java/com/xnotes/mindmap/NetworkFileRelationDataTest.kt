package com.xnotes.mindmap

import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual graph bytes and actor overlays, including paths alternating between both evidence types. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class NetworkFileRelationDataTest {
    private fun id()=UUID.randomUUID().toString()
    private fun cards(vararg ids:String):MindMapDocument {
        val empty=MindMapDocument(nodes=emptyList(),organization=MapOrganization.RELATION_CANVAS)
        val doc=empty.addFileCards(ids.mapIndexed {i,uuid->MapFileCardSeed(uuid,"File $i",i*400.0,0.0)}).document
        return doc.copy(relations=listOf(MapRelation(id="manual",from=doc.nodes[0].id,to=doc.nodes[1].id,label="Original remark")))
    }
    private fun concept(a:String,b:String)=MindMapDocument(nodes=listOf(MapNode(id="root",parent=null),
        MapNode(id="concept",parent="root",text="Real concept",sources=listOf(MapSource(documentId=a),MapSource(documentId=b)))))
    private suspend fun await(check:()->Boolean)=withContext(Dispatchers.Default) {
        withTimeout(15000) {while(!withContext(Dispatchers.Main) {check()}) delay(1)}
    }
    private suspend fun controller(f:MapBacklinkLibraryTest.Fixture,a:String,block:suspend(LocalFileNetworkController)->Unit)=withContext(Dispatchers.Main) {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val c=LocalFileNetworkController(f.context,scope,NetworkQuery(a,"A",listOf(f.tree.toString())))
        try {assertTrue(c.refresh());await {!c.loading && c.report!=null};block(c)} finally {c.close();scope.cancel()}
    }
    @Test fun manualOnlyGraphsWorkInLocalCollectionAndLibraryAndNeverInventOwnerEdges()=MapBacklinkLibraryTest().scenario {f->
        val a=id();val b=id();val doc=cards(a,b);val second=cards(b,a)
        val files=listOf(f.graph("one.xmap",doc),f.graph("two.xmap",second));val original=files.map {it.readBytes()}
        controller(f,a) {c->
            assertTrue(c.report!!.concepts.isEmpty());assertEquals(2,c.report!!.fileRelations.size)
            assertEquals(1,c.report!!.aggregatedFileRelations.size);assertEquals(setOf(a,b),c.report!!.files.map {it.documentId}.toSet())
            val row=c.report!!.fileRelations.first();val request=c.captureRelation(row)!!;assertTrue(request.current())
            assertTrue(c.applyFilter(NetworkFilter(fileIds=setOf(doc.id))));assertTrue(c.report!!.fileRelations.isEmpty());assertFalse(request.current())
            assertTrue(c.applyFilter(NetworkFilter()));assertTrue(c.showLibrary());await {!c.loading && c.report!!.browseMode==NetworkBrowseMode.LIBRARY}
            assertEquals(2,c.report!!.fileRelations.size);assertTrue(c.fileChoices.any {it.documentId==b})
            assertTrue(c.showCollection(setOf(b)));await {!c.loading && c.report!!.browseMode==NetworkBrowseMode.FILES}
            assertEquals(2,c.report!!.fileRelations.size);assertTrue(c.report!!.concepts.isEmpty())
            assertFalse(c.captureRelation(row)?.current()==true)
        }
        files.forEachIndexed {i,file->assertArrayEquals(original[i],file.readBytes())};assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }
    @Test fun alternatingPathsKeepFilterContextAndLiveRelationDeletionRetiresTheDeepConceptRequest()=MapBacklinkLibraryTest().scenario {f->
        val a=id();val b=id();val cId=id();val d=id();val first=cards(a,b)
        val file=f.graph("first.xmap",first);f.graph("concept.xmap",concept(b,cId));f.graph("tail.xmap",cards(cId,d))
        val original=file.readBytes()
        val lease=withContext(Dispatchers.Main) {MapDocuments.acquire(f.context,f.uri(file)).also(f.leases::add)}
        await {!lease.session.loading}
        controller(f,a) {c->
            assertEquals(1,c.report!!.fileRelations.size);assertTrue(c.report!!.concepts.isEmpty())
            assertTrue(c.setExpanded(b,true));await {!c.loading && c.report!!.concepts.size==1}
            val center=c.report!!.concepts.single();assertEquals(1,center.origin!!.relationPath.size)
            val open=c.captureFocus(center.members.first(),center.members.last())!!;assertTrue(open.current())
            assertTrue(c.setExpanded(cId,true));await {!c.loading && c.report!!.fileRelations.size==2}
            assertTrue(c.applyFilter(NetworkFilter(fileIds=setOf(d))))
            assertEquals(2,c.report!!.fileRelations.size);assertEquals(1,c.report!!.contextFileRelations.size)
            assertEquals(setOf("concept"),c.report!!.contextConcepts.map {it.nodeId}.toSet());assertEquals(setOf(d),c.report!!.matchedFiles)
            val tail=c.report!!.fileRelations.single {d in it.fileIds};val tailRequest=c.captureRelation(tail)!!
            val deep=c.report!!.concepts.single();val conceptRequest=c.captureFocus(deep.members.first(),deep.members.last())!!
            val reads=f.reads.get();val trees=f.treeQueries.get()
            assertTrue(lease.session.operate {it.copy(relations=emptyList())});lease.session.cancelPendingAutosave()
            await {c.report!!.fileRelations.isEmpty() && c.report!!.concepts.isEmpty()}
            assertFalse(tailRequest.current());assertFalse(conceptRequest.current())
            lease.session.undo();lease.session.cancelPendingAutosave();await {c.report!!.fileRelations.size==2}
            assertFalse(tailRequest.current());assertFalse(conceptRequest.current())
            assertEquals(reads,f.reads.get());assertEquals(trees,f.treeQueries.get())
        }
        assertArrayEquals(original,file.readBytes());assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }
    @Test fun liveRelationChangesReplaceStoredRowsAndCannotReviveAnOldActionAfterUndo()=MapBacklinkLibraryTest().scenario {f->
        val a=id();val b=id();val other=id();val doc=cards(a,b,other);val file=f.graph("live.xmap",doc);val original=file.readBytes()
        controller(f,a) {c->
            val stored=c.report!!.fileRelations.single();assertNotNull(c.captureRelation(stored))
            val lease=MapDocuments.acquire(f.context,f.uri(file)).also(f.leases::add)
            await {!lease.session.loading && !c.loading && c.report!!.fileRelations.singleOrNull()?.origin==BacklinkOrigin.LIVE}
            assertNull(c.captureRelation(stored));val live=c.report!!.fileRelations.single();val old=c.captureRelation(live)!!
            val reads=f.reads.get();val trees=f.treeQueries.get()
            assertTrue(lease.session.operate {it.copy(relations=it.relations.map {r->r.copy(label="Changed remark")})})
            lease.session.cancelPendingAutosave();await {c.report!!.fileRelations.single().relation.label=="Changed remark"}
            assertFalse(old.current());assertNull(c.captureRelation(live));assertNotNull(c.captureRelation(c.report!!.fileRelations.single()))
            lease.session.undo();lease.session.cancelPendingAutosave();await {c.report!!.fileRelations.single().relation.label=="Original remark"}
            assertFalse(old.current())
            assertTrue(lease.session.operate {it.copy(relations=it.relations.map {r->r.copy(to=doc.nodes[2].id)})})
            lease.session.cancelPendingAutosave();await {c.report!!.fileRelations.single().toFileId==other}
            assertEquals(setOf(a,other),c.report!!.files.map {it.documentId}.toSet())
            assertEquals(reads,f.reads.get());assertEquals(trees,f.treeQueries.get())
        }
        assertArrayEquals(original,file.readBytes());assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }
    @Test fun duplicateGraphEvidenceStaysVisibleButCannotBridgeToAnotherLayerEvenWhenOneCopyIsLive()=MapBacklinkLibraryTest().scenario {f->
        val a=id();val b=id();val cId=id();val doc=cards(a,b)
        val first=f.graph("copy-a.xmap",doc);f.graph("copy-b.xmap",doc);f.graph("next.xmap",cards(b,cId))
        controller(f,a) {c->
            assertEquals(2,c.report!!.fileRelations.size);assertTrue(c.report!!.fileRelations.all {it.blocked==BacklinkProblem.DUPLICATE})
            assertEquals(1,c.report!!.aggregatedFileRelations.size);assertNull(c.captureRelation(c.report!!.fileRelations.first()))
            assertTrue(c.setExpanded(b,true));await {!c.loading}
            assertEquals(2,c.report!!.fileRelations.size);assertFalse(c.report!!.files.any {it.documentId==cId})
            val lease=MapDocuments.acquire(f.context,f.uri(first)).also(f.leases::add)
            await {!lease.session.loading && !c.loading && c.report!!.fileRelations.any {it.origin==BacklinkOrigin.LIVE}}
            assertEquals(2,c.report!!.fileRelations.size);assertTrue(c.report!!.fileRelations.all {it.blocked==BacklinkProblem.DUPLICATE})
            assertFalse(c.report!!.files.any {it.documentId==cId})
        }
        assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }
    @Test fun aStoredMixedPrefixCannotSurviveAnIndependentlyOpenedActorOrReturnAfterItsRetirement()=MapBacklinkLibraryTest().scenario {f->
        val a=id();val b=id();val cId=id();val first=cards(a,b)
        val prefix=f.graph("prefix.xmap",first);f.graph("next.xmap",cards(b,cId));f.graph("concept.xmap",concept(b,cId))
        val original=prefix.readBytes()
        controller(f,a) {c->
            assertTrue(c.setExpanded(b,true));await {!c.loading && c.report!!.fileRelations.size==2 && c.report!!.concepts.size==1}
            val relation=c.report!!.fileRelations.single {cId in it.fileIds};val relationRequest=c.captureRelation(relation)!!
            val concept=c.report!!.concepts.single();val conceptRequest=c.captureFocus(concept.members.first(),concept.members.last())!!
            assertTrue(relationRequest.current());assertTrue(conceptRequest.current())
            val independent=MapDocuments.acquire(f.context,f.uri(prefix)).also(f.leases::add)
            // Guard the actual directory event before the observer has had a chance to publish its overlay.
            assertFalse(relationRequest.current());assertFalse(conceptRequest.current())
            await {!independent.session.loading}
            independent.close().join()
            await {!c.loading && c.report!!.fileRelations.all {it.origin!=BacklinkOrigin.LIVE}}
            assertFalse(relationRequest.current());assertFalse(conceptRequest.current())
            val editing=MapDocuments.acquire(f.context,f.uri(prefix)).also(f.leases::add)
            await {!editing.session.loading}
            assertTrue(editing.session.operate {it.copy(relations=emptyList())});editing.session.cancelPendingAutosave()
            await {!c.loading && c.report!!.concepts.isEmpty() && c.report!!.fileRelations.isEmpty()}
            editing.session.undo();editing.session.cancelPendingAutosave()
            await {!c.loading && c.report!!.fileRelations.size==2}
            assertFalse(relationRequest.current());assertFalse(conceptRequest.current())
        }
        assertArrayEquals(original,prefix.readBytes());assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }
    @Test fun anActionsOwnFirstActorCanProveItsStoredSameGraphPredecessors()=MapBacklinkLibraryTest().scenario {f->
        val a=id();val b=id();val cId=id();val doc=cards(a,b,cId)
        val tail=MapRelation(id="tail",from=doc.nodes[1].id,to=doc.nodes[2].id)
        val both=doc.copy(relations=doc.relations+tail)
        val file=f.graph("same-graph.xmap",both);val original=file.readBytes()
        controller(f,a) {c->
            assertTrue(c.setExpanded(b,true));await {!c.loading && c.report!!.fileRelations.size==2}
            val row=c.report!!.fileRelations.single {it.relation.id=="tail"};val request=c.captureRelation(row)!!
            val ready=acquireFileRelation(f.context,request,{true}) as FileRelationAccessResult.Ready
            f.leases+=ready.lease
            assertTrue(request.current());assertTrue(ready.membership.current())
            assertTrue(ready.lease.session.operate {it.copy(relations=it.relations.filterNot {r->r.id=="manual"})})
            ready.lease.session.cancelPendingAutosave();assertFalse(request.current())
            ready.lease.session.undo();ready.lease.session.cancelPendingAutosave();assertFalse(request.current())
        }
        assertArrayEquals(original,file.readBytes());assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }

    @Test fun aConceptActionsOwnFirstActorProvesTheSameGraphManualPredecessor()=MapBacklinkLibraryTest().scenario {f->
        val a=id();val b=id();val cId=id();val linked=cards(a,b)
        val concept=MapNode(id="concept",parent=null,free=true,text="Concept",sources=listOf(MapSource(documentId=b),MapSource(documentId=cId)))
        val doc=linked.copy(nodes=linked.nodes+concept);val file=f.graph("same-graph-concept.xmap",doc);val original=file.readBytes()
        controller(f,a) {c->
            assertTrue(c.setExpanded(b,true));await {!c.loading && c.report!!.concepts.size==1}
            val item=c.report!!.concepts.single();val request=c.captureFocus(item.members.first(),item.members.last())!!
            val result=acquireNetwork(f.context,request,{true}) as MapNetworkAccessResult.Ready
            f.leases+=result.lease;assertTrue(request.current())
            assertTrue(result.lease.session.operate {it.copy(relations=emptyList())});result.lease.session.cancelPendingAutosave()
            assertFalse(request.current())
            result.lease.session.undo();result.lease.session.cancelPendingAutosave();assertFalse(request.current())
        }
        assertArrayEquals(original,file.readBytes());assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }

}
