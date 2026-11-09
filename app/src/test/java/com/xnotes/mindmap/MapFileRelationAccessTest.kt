package com.xnotes.mindmap

import android.os.Looper
import java.io.File
import java.io.IOException
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

/** Real DocumentsProvider, journals and broker actors; file edges never acquire a source ticket. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class MapFileRelationAccessTest {
    private data class Sample(val fromId:String,val toId:String,val doc:MindMapDocument)
    private fun sample():Sample {
        val from=UUID.randomUUID().toString();val to=UUID.randomUUID().toString()
        val a=MapNode(id="a",parent=null,free=true,kind=NodeKind.REFERENCE,text="A",fileCardId=from,
            sources=listOf(MapSource(documentId=from,fileName="A.xnote")))
        val b=MapNode(id="b",parent=null,free=true,kind=NodeKind.REFERENCE,text="B",fileCardId=to,
            sources=listOf(MapSource(documentId=to,fileName="B.xnote")))
        return Sample(from,to,MindMapDocument(organization=MapOrganization.RELATION_CANVAS,nodes=listOf(a,b),
            relations=listOf(MapRelation(id="edge",from="a",to="b",kind=RelationKind.BELONGS_TO,label="Exact meaning"))))
    }
    private fun scenario(block:suspend (MapBacklinkLibraryTest.Fixture)->Unit)=MapBacklinkLibraryTest().scenario(block=block)
    private suspend fun stored(f:MapBacklinkLibraryTest.Fixture,s:Sample)=
        MapBacklinkLibrary(f.context,listOf(f.tree.toString())).scan(s.fromId).graphs.single().fileRelations.single()
    private fun live(f:MapBacklinkLibraryTest.Fixture,session:MapSession,aliases:Set<String>?=null):MapFileRelationRow {
        val observation=MapDocuments.observations(f.context).single {it.session===session}
        val location=BacklinkLocation(observation.uri,aliases ?: observation.knownAliases,observation.key,
            requireNotNull(observation.document).id,"actual.xmap")
        return MapBacklinkLibrary(f.context,listOf(f.tree.toString())).projectFileRelations(requireNotNull(observation.document),
            location,BacklinkOrigin.LIVE,false,membership=observation::captureFileRelation).rows.single()
    }
    private fun request(f:MapBacklinkLibraryTest.Fixture,row:MapFileRelationRow,valid:()->Boolean={true})=
        requireNotNull(FileRelationOpenRequest.capture(row,listOf(f.tree.toString()),Any(),valid=valid))
    private suspend fun access(f:MapBacklinkLibraryTest.Fixture,r:FileRelationOpenRequest):FileRelationAccessResult.Ready {
        val result=acquireFileRelation(f.context,r,{true})
        assertTrue("Actual relation access must load the exact explicit edge: $result",result is FileRelationAccessResult.Ready)
        return (result as FileRelationAccessResult.Ready).also {f.leases+=it.lease}
    }
    private fun problem(result:FileRelationAccessResult):BacklinkProblem {
        assertTrue(result is FileRelationAccessResult.Failed)
        return (result as FileRelationAccessResult.Failed).problem
    }
    @Test fun storedEdgeCanOnlyFirstSignThisActionsNewActorAndNeverBorrowAnAlreadyLiveOne()=scenario {f->
        val s=sample();val file=f.graph("stored-relation.xmap",s.doc);val bytes=file.readBytes();val journals=f.journalSnapshot()
        val row=stored(f,s)
        withContext(Dispatchers.Main) {
            assertNull(row.membership);assertTrue(MapDocuments.observations(f.context).isEmpty())
            val r=request(f,row);val ready=access(f,r);val session=ready.lease.session
            assertEquals(s.doc,session.document);assertTrue(ready.membership.current())
            val focus=session.captureFileRelationFocus(row.location.graphId,row.relation,row.fromFileId,row.toFileId,ready.membership)!!
            assertTrue(session.applyFileRelationFocus(focus));assertEquals("edge",session.selectedRelation)
            assertTrue(session.selectedNodes.isEmpty());assertFalse(session.canUndo);assertFalse(session.dirty)
            assertEquals(BacklinkProblem.CHANGED,problem(acquireFileRelation(f.context,r,{true})))
            assertSame(session,MapDocuments.observations(f.context).single().session)
        }
        assertArrayEquals(bytes,file.readBytes());assertEquals(journals,f.journalSnapshot())
        assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }
    @Test fun liveActorAndMeaningStayBoundAcrossMovementButNeverRetargetOrReopen()=scenario {f->
        val s=sample();val file=f.graph("live-relation.xmap",s.doc);val original=f.acquire(f.uri(file));val bytes=file.readBytes()
        withContext(Dispatchers.Main) {
            val row=live(f,original.session);val r=request(f,row);val ready=access(f,r)
            assertSame(original.session,ready.lease.session);assertSame(row.membership,ready.membership)
            assertTrue(original.session.operate {it.update("a") {n->n.copy(x=480.0,text="New independent title")}})
            original.session.cancelPendingAutosave()
            assertTrue(r.current())
            val moved=access(f,r);assertSame(ready.membership,moved.membership)
            assertTrue(original.session.operate {it.copy(relations=it.relations.map {edge->edge.copy(from="b",to="a")})})
            original.session.cancelPendingAutosave();assertFalse(r.current())
            assertEquals(BacklinkProblem.CHANGED,problem(acquireFileRelation(f.context,r,{true})))
            original.session.undo();original.session.cancelPendingAutosave();assertFalse(r.current())
            assertFalse(ready.membership.current())
        }
        assertArrayEquals(bytes,file.readBytes());assertEquals(0,f.writes.get())
        // Retire the real old actor, then opening identical stored bytes cannot revive its live proof.
        val old=withContext(Dispatchers.Main) {request(f,live(f,original.session))}
        withContext(Dispatchers.Main) {original.session.saveAttempt().await();f.leases.map {it.close()}}.forEach {it.join()}
        withContext(Dispatchers.Main) {
            assertFalse(old.current());assertTrue(MapDocuments.observations(f.context).isEmpty())
            assertEquals(BacklinkProblem.CHANGED,problem(acquireFileRelation(f.context,old,{true})))
            assertTrue(MapDocuments.observations(f.context).isEmpty())
        }
    }
    @Test fun completeStoredMeaningAndBothFileIdentitiesAreCheckedAgainstTheActuallyReadDocument()=scenario {f->
        val s=sample();f.graph("semantic-relation.xmap",s.doc);val row=stored(f,s)
        for(changed in listOf(row.copy(relation=row.relation.copy(label="Different label")),
            row.copy(relation=row.relation.copy(kind=RelationKind.DEPENDS_ON)),
            row.copy(relation=row.relation.copy(from="b",to="a")),
            row.copy(fromFileId=UUID.randomUUID().toString()))) {
            withContext(Dispatchers.Main) {
                assertEquals(BacklinkProblem.CHANGED,problem(acquireFileRelation(f.context,request(f,changed),{true})))
            }
            withTimeout(10000) {while(withContext(Dispatchers.Main) {MapDocuments.observations(f.context).isNotEmpty()}) delay(1)}
        }
        assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }
    @Test fun firstAndFinalScopeChecksPreserveDirtyActorAndNeverRecreateItsTrashedLocation() {
        for(first in listOf(true,false)) scenario {f->
            val s=sample();val file=f.graph("scope-relation.xmap",s.doc);val uri=f.uri(file);val user=f.acquire(uri)
            val moved=File(f.notes,".xnote/trash/relation/scope-relation.xmap").apply {parentFile!!.mkdirs()}
            val movedUri=f.uri(moved)
            withContext(Dispatchers.Main) {
                assertTrue(user.session.operate {it.update("a") {n->n.copy(text="Unwritten title survives")}})
                val r=request(f,live(f,user.session));val bytes=file.readBytes();val journals=f.journalSnapshot()
                val reads=AtomicInteger()
                if(first) {
                    assertTrue(file.renameTo(moved));withContext(Dispatchers.IO) {f.storage.registry.moved(uri.toString(),movedUri.toString())}
                } else f.beforeOpenRead={if(reads.incrementAndGet()==2) {
                    assertTrue(file.renameTo(moved));f.storage.registry.moved(uri.toString(),movedUri.toString())
                }}
                val denied=try {acquireFileRelation(f.context,r,{true})} finally {f.beforeOpenRead=null}
                assertEquals(if(first) BacklinkProblem.TRASHED else BacklinkProblem.UNAVAILABLE,problem(denied))
                shadowOf(Looper.getMainLooper()).idleFor(400,TimeUnit.MILLISECONDS)
                user.close().join()
                assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get());assertEquals(journals,f.journalSnapshot())
                assertArrayEquals(bytes,moved.readBytes());assertFalse(file.exists())
                assertTrue(user.session.dirty);assertFalse(user.session.saving)
                assertEquals("Unwritten title survives",user.session.document!!.node("a").text)
                assertSame(user.session,MapDocuments.observations(f.context).single().session)
                assertTrue(moved.renameTo(file));withContext(Dispatchers.IO) {f.storage.registry.moved(movedUri.toString(),uri.toString())}
                val cleanup=MapDocuments.acquire(f.context,uri).also(f.leases::add)
                assertTrue(cleanup.session.saveAttempt().await() is MapSaveOutcome.Written)
            }
        }
    }
    @Test fun replacedGraphIdentityAndDuplicateRegisteredLocationsFailBeforeAnyRelationPresentation() {
        for(duplicate in listOf(false,true)) scenario {f->
            val s=sample();val file=f.graph("identity-relation.xmap",s.doc);val row=stored(f,s)
            if(duplicate) {
                val copy=f.graph("duplicate-relation.xmap",s.doc)
                withContext(Dispatchers.IO) {
                    f.storage.registry.registerMapObserved(s.doc.id,file.name,f.uri(file).toString())
                    f.storage.registry.registerMapObserved(s.doc.id,copy.name,f.uri(copy).toString())
                }
            } else file.writeBytes(MapCodec.encode(s.doc.copy(id=UUID.randomUUID().toString())))
            val bytes=file.readBytes()
            withContext(Dispatchers.Main) {
                val result=acquireFileRelation(f.context,request(f,row),{true})
                assertEquals(if(duplicate) BacklinkProblem.DUPLICATE else BacklinkProblem.IDENTITY,problem(result))
            }
            assertArrayEquals(bytes,file.readBytes());assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
        }
    }
    @Test fun newlyFoundAliasRecoveryCannotBeSignedByAnAlreadyOpenRelationActor()=scenario {f->
        val s=sample();val file=f.graph("alias-relation.xmap",s.doc);val bare=f.uri(file,false);val tree=f.uri(file,true)
        val lease=f.acquire(bare);val actual=MapFiles.target(f.context,bare)
        val failing=object:MapTarget {override fun read()=actual.read();override fun write(bytes:ByteArray):Unit=throw IOException("before write")}
        val old=MapStore(File(f.context.noBackupFilesDir,"mind-map-recovery"),tree.toString(),failing)
        old.open();assertThrows(IOException::class.java) {old.save(s.doc.copy(relations=s.doc.relations.map {it.copy(label="Retained newer edge")}))}
        val bytes=file.readBytes();val journals=f.journalSnapshot()
        withContext(Dispatchers.Main) {
            val row=live(f,lease.session,setOf(bare.toString(),tree.toString()))
            assertEquals(BacklinkProblem.RECOVERY_CONFLICT,problem(acquireFileRelation(f.context,request(f,row),{true})))
            assertEquals(s.doc,lease.session.document)
        }
        assertArrayEquals(bytes,file.readBytes());assertEquals(journals,f.journalSnapshot());assertEquals(0,f.writes.get())
    }
    @Test fun narrowedPresentationAndOriginNeverReissueMissingOrInvalidLiveMembership()=scenario {f->
        val s=sample();val file=f.graph("guard-relation.xmap",s.doc);val lease=f.acquire(f.uri(file))
        withContext(Dispatchers.Main) {
            val row=live(f,lease.session);var panel=true;var host=true
            val r=request(f,row) {panel};val narrowed=r.withPresentationGuard {host}!!
            assertTrue(narrowed.current());host=false;assertFalse(narrowed.current());assertTrue(r.current())
            host=true;panel=false;assertFalse(narrowed.current());assertFalse(r.current())
            assertNull(FileRelationOpenRequest.capture(row.copy(membership=null),emptyList(),Any()) {true})
            assertNull(FileRelationOpenRequest.capture(row.copy(origin=BacklinkOrigin.STORED),emptyList(),Any()) {true})
        }
    }
}
