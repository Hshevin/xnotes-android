package com.xnotes.mindmap

import android.os.Looper
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Actual provider and broker actors verify the center and co-source as separate source lifetimes. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class MapNetworkAccessTest {
    private fun id()=UUID.randomUUID().toString()
    private fun scenario(block:suspend (MapBacklinkLibraryTest.Fixture)->Unit)=MapBacklinkLibraryTest().scenario(block=block)
    private data class Sample(val anchor:MapSource,val target:MapSource,val doc:MindMapDocument)
    private fun sample():Sample {
        val s=MapSource(documentId=id(),fileName="Center S.xnote")
        val t=MapSource(documentId=id(),scope=SourceScope.PAGE,pageId=id(),fileName="Co-source T.xnote",lastKnownPage=4)
        return Sample(s,t,MindMapDocument(nodes=listOf(MapNode(id="root",parent=null,text="Owner"),
            MapNode(id="n",parent="root",text="Exact concept",sources=listOf(s,t),primarySource=t.id))))
    }
    private suspend fun await(condition:()->Boolean)=withContext(Dispatchers.Default) {
        withTimeout(15000) {while(!withContext(Dispatchers.Main) {condition()}) delay(1)}
    }
    private suspend fun controller(f:MapBacklinkLibraryTest.Fixture,s:Sample,block:suspend (LocalFileNetworkController)->Unit)=withContext(Dispatchers.Main) {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val c=LocalFileNetworkController(f.context,scope,NetworkQuery(s.anchor.documentId,"S",listOf(f.tree.toString())))
        try {assertTrue(c.refresh());await {!c.loading && c.report!=null};block(c)} finally {c.close();scope.cancel()}
    }
    private fun request(c:LocalFileNetworkController,s:Sample):MapNetworkOpenRequest {
        val members=c.report!!.concepts.single().members
        return c.captureFocus(members.single {it.row.sourceId==s.anchor.id},members.single {it.row.sourceId==s.target.id})!!
    }
    private suspend fun access(f:MapBacklinkLibraryTest.Fixture,r:MapNetworkOpenRequest):MapNetworkAccessResult.Ready {
        val result=acquireNetwork(f.context,r,{r.current()})
        assertTrue("Actual Access must load and validate both members: $result",result is MapNetworkAccessResult.Ready)
        return (result as MapNetworkAccessResult.Ready).also {f.leases+=it.lease}
    }
    @Test fun storedObservationCanOnlySignBothMembersFromThisAttemptsOwnNewActorAndFocusesTheExactCoSource()=scenario {f->
        val s=sample();val file=f.graph("stored.xmap",s.doc);val bytes=file.readBytes();val journals=f.journalSnapshot()
        controller(f,s) {c->
            val r=request(c,s);assertNull(r.anchor.membership);assertNull(r.target.membership)
            assertTrue(MapDocuments.observations(f.context).isEmpty())
            val ready=access(f,r);val session=ready.lease.session
            assertEquals(1,MapDocuments.observations(f.context).size);assertEquals(s.doc,session.document)
            assertSame(session,ready.anchorMembership.issuer);assertSame(session,ready.targetMembership.issuer)
            val before=session.document
            val ticket=session.captureNetworkFocus(s.doc.id,"n",s.target.id,s.target.target,ready.anchorMembership,ready.targetMembership)!!
            assertEquals(s.target.id,ticket.sourceId);assertEquals(s.target.target,ticket.target)
            assertTrue(session.backlinkFocusCurrent(ticket));assertTrue(session.applyBacklinkFocus(ticket));assertEquals("n",session.selected)
            assertSame(before,session.document);assertFalse(session.dirty);assertFalse(session.canUndo);assertFalse(session.canRedo)
            val late=acquireNetwork(f.context,r,{r.current()})
            assertTrue(late is MapNetworkAccessResult.Failed);assertEquals(BacklinkProblem.CHANGED,(late as MapNetworkAccessResult.Failed).problem)
            assertSame("A stored row cannot reopen or borrow a replacement actor",session,MapDocuments.observations(f.context).single().session)
            assertArrayEquals(bytes,file.readBytes());assertEquals(journals,f.journalSnapshot())
            assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
        }
    }
    @Test fun liveAccessKeepsOriginalTwoProofsAndFinalFocusChecksBothThroughMetadataAndSourceAba()=scenario {f->
        val s=sample();val file=f.graph("live.xmap",s.doc);val bytes=file.readBytes();val original=f.acquire(f.uri(file))
        controller(f,s) {c->
            val r=request(c,s);val ready=access(f,r);val session=original.session
            assertSame(session,ready.lease.session);assertSame(r.anchor.membership,ready.anchorMembership);assertSame(r.target.membership,ready.targetMembership)
            val ticket=session.captureNetworkFocus(s.doc.id,"n",s.target.id,s.target.target,ready.anchorMembership,ready.targetMembership)!!
            val panel=SourceListEditor(session,session.sourceEditSnapshot("n")!!)
            try {assertTrue(panel.note(s.target.id,"Metadata C1"));assertTrue(panel.move(s.target.id,-1))} finally {panel.close()}
            assertTrue(r.current());assertTrue(session.backlinkFocusCurrent(ticket));assertTrue(session.applyBacklinkFocus(ticket))
            assertTrue(session.operate {it.update("n") {node->node.copy(sources=node.sources.filterNot {it.id==s.anchor.id})}})
            assertFalse(session.backlinkFocusCurrent(ticket));assertFalse(r.current())
            session.undo();await {!c.loading && c.report!!.concepts.singleOrNull()?.members?.singleOrNull {it.row.sourceId==s.anchor.id}?.row?.membership?.let {
                it.current(session,s.doc.id,"n",s.anchor.id,s.anchor.target)
            }==true}
            assertFalse(session.backlinkFocusCurrent(ticket));assertNull(session.captureNetworkFocus(s.doc.id,"n",s.target.id,s.target.target,
                ready.anchorMembership,ready.targetMembership))
            assertTrue(acquireNetwork(f.context,r,{true}) is MapNetworkAccessResult.Failed)
            val second=request(c,s);val fresh=access(f,second)
            val freshTicket=session.captureNetworkFocus(s.doc.id,"n",s.target.id,s.target.target,fresh.anchorMembership,fresh.targetMembership)!!
            assertTrue(session.operate {it.update("n") {node->node.copy(sources=node.sources.map {if(it.id==s.target.id) it.copy(pageId=id()) else it})}})
            session.undo()
            assertFalse(session.backlinkFocusCurrent(freshTicket));assertFalse(second.current())
            assertNull(session.captureNetworkFocus(s.doc.id,"n",s.target.id,s.target.target,fresh.anchorMembership,fresh.targetMembership))
            assertTrue(session.dirty);assertArrayEquals(bytes,file.readBytes());assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
        }
    }
    @Test fun anActionScopeVetoIsStickyWhenAnotherNormalLeaseIsTheLastOneToClose()=scenario {f->
        val s=sample();val file=f.graph("scope.xmap",s.doc);val uri=f.uri(file);val user=f.acquire(uri)
        controller(f,s) {c->
            val session=user.session
            assertTrue(session.operate {it.update("n") {it.copy(text="Unwritten C1 must survive")}})
            session.cancelPendingAutosave()
            val r=request(c,s);val original=file.readBytes();val journals=f.journalSnapshot()
            val moved=File(f.notes,".xnote/trash/network-scope/scope.xmap").apply {parentFile!!.mkdirs()};val movedUri=f.uri(moved)
            val checks=AtomicInteger()
            // Move the actual file between the two fresh action-time scope checks.
            f.beforeOpenRead={if(checks.incrementAndGet()==2) {
                assertTrue(file.renameTo(moved));f.storage.registry.moved(uri.toString(),movedUri.toString())
            }}
            val denied=try {acquireNetwork(f.context,r,{r.current()})} finally {f.beforeOpenRead=null}
            assertTrue(denied is MapNetworkAccessResult.Failed)
            assertEquals(BacklinkProblem.UNAVAILABLE,(denied as MapNetworkAccessResult.Failed).problem)
            assertTrue(user.alive);user.close().join()
            withContext(Dispatchers.Default) {delay(30)}
            assertTrue(session.dirty);assertFalse(session.saving);assertEquals("Unwritten C1 must survive",session.document!!.node("n").text)
            assertSame(session,MapDocuments.observations(f.context).single().session)
            assertArrayEquals(original,moved.readBytes());assertEquals(journals,f.journalSnapshot())
            assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
            // End the intentionally retained actor only via an explicit ordinary save after restoring the fixture.
            assertTrue(moved.renameTo(file));withContext(Dispatchers.IO) {f.storage.registry.moved(movedUri.toString(),uri.toString())}
            val cleanup=MapDocuments.acquire(f.context,uri).also(f.leases::add);assertSame(session,cleanup.session)
            assertTrue(session.saveAttempt().await() is MapSaveOutcome.Written)
        }
    }
    @Test fun theFirstScopeVetoPreservesTheExistingDirtyActorBeforeEitherAccessPathCanAcquire() {
        for(network in listOf(true,false)) scenario {f->
            val s=sample();val file=f.graph("first-scope.xmap",s.doc);val uri=f.uri(file);val user=f.acquire(uri)
            controller(f,s) {c->
                val session=user.session
                assertTrue(session.operate {it.update("n") {it.copy(text="C1 survives the first scope rejection")}})
                val r=request(c,s);val original=file.readBytes();val journals=f.journalSnapshot()
                val moved=File(f.notes,".xnote/trash/first-network-scope/first-scope.xmap").apply {parentFile!!.mkdirs()}
                val movedUri=f.uri(moved)
                if(network) {
                    assertTrue(file.renameTo(moved))
                    withContext(Dispatchers.IO) {f.storage.registry.moved(uri.toString(),movedUri.toString())}
                } else f.beforeOpenRead={throw SecurityException("The provider revoked read access")}
                val problem=if(network) {
                    val denied=acquireNetwork(f.context,r,{r.current()})
                    assertTrue(denied is MapNetworkAccessResult.Failed)
                    (denied as MapNetworkAccessResult.Failed).problem
                } else {
                    val denied=acquireBacklink(f.context,r.anchor,{r.current()},r.query.roots)
                    assertTrue(denied is BacklinkAccessResult.Failed)
                    (denied as BacklinkAccessResult.Failed).problem
                }
                assertEquals(if(network) BacklinkProblem.TRASHED else BacklinkProblem.UNAVAILABLE,problem)
                assertTrue("The first veto never acquired or closed the ordinary user's lease",user.alive)
                // This case intentionally advances the old debounce: a veto must cancel it as well as retain the actor.
                shadowOf(Looper.getMainLooper()).idleFor(400,TimeUnit.MILLISECONDS)
                user.close().join()
                withContext(Dispatchers.Default) {delay(30)}
                assertEquals("First-scope rejection must not start a graph write",0,f.writes.get())
                assertEquals("First-scope rejection must not checkpoint an unsolicited pending save",journals,f.journalSnapshot())
                assertEquals(0,f.deletes.get());assertArrayEquals(original,if(network) moved.readBytes() else file.readBytes())
                if(network) assertFalse("A close cannot recreate the old active location",file.exists())
                assertTrue(session.dirty);assertFalse(session.saving);assertTrue(session.canUndo)
                assertEquals("C1 survives the first scope rejection",session.document!!.node("n").text)
                assertSame(session,MapDocuments.observations(f.context).single().session)
                f.beforeOpenRead=null
                if(network) {
                    assertTrue(moved.renameTo(file))
                    withContext(Dispatchers.IO) {f.storage.registry.moved(movedUri.toString(),uri.toString())}
                }
                val cleanup=MapDocuments.acquire(f.context,uri).also(f.leases::add);assertSame(session,cleanup.session)
                assertTrue(session.saveAttempt().await() is MapSaveOutcome.Written)
            }
        }
    }
}
