package com.xnotes.mindmap

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.ui.text.input.TextFieldValue
import com.xnotes.storage.NotebookStorage
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** Actual destination actor/history/provider/journal consumers; no replacement save engine. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class MapDestinationConsumerTest {
    private class Provider(val root:File):ContentProvider() {
        var writeOpens=0;var deletes=0;var failReadbackAfterWrite=false;var denyRead=false;var beforeWrite:(()->Unit)?=null;private var readbackFailures=0
        fun file(uri:Uri)=File(root,DocumentsContract.getDocumentId(uri).substringAfterLast('/'))
        override fun onCreate()=true
        override fun getType(uri:Uri)="application/octet-stream"
        override fun query(uri:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,sort:String?):Cursor =
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)).apply {addRow(arrayOf(file(uri).name,file(uri).length()))}
        override fun insert(uri:Uri,values:ContentValues?):Uri?=null
        override fun update(uri:Uri,values:ContentValues?,s:String?,a:Array<out String>?)=0
        override fun delete(uri:Uri,s:String?,a:Array<out String>?):Int {deletes++;throw AssertionError("A source destination must not delete provider data")}
        override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
            val writing='w' in mode
            if(!writing && denyRead) throw SecurityException("Permission lost")
            if(writing) beforeWrite?.invoke()
            if(!writing && readbackFailures>0) {readbackFailures--;throw IOException("Provider failed after writing the full file")}
            if(writing) {writeOpens++;if(failReadbackAfterWrite) {readbackFailures=1;failReadbackAfterWrite=false}}
            return ParcelFileDescriptor.open(file(uri),if(writing) ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE or
                ParcelFileDescriptor.MODE_CREATE else ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }
    private class Fixture(base:Context) {
        val root=File(base.cacheDir,"map-destination-${UUID.randomUUID()}").apply {mkdirs()}
        val context=object:ContextWrapper(base) {
            override fun getApplicationContext():Context=this
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
            override fun getFilesDir()=File(root,"files").apply {mkdirs()}
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
        }
        val authority="map-destination-${UUID.randomUUID()}";val provider=Provider(root)
        val uri=DocumentsContract.buildDocumentUri(authority,"root/graph.xmap")
        val alias=DocumentsContract.buildDocumentUriUsingTree(DocumentsContract.buildTreeDocumentUri(authority,"root"),"root/graph.xmap")
        val empty=DocumentsContract.buildDocumentUri(authority,"root/new.xmap")
        val storage=NotebookStorage(context)
        val original=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,text="root"),MapNode(id="n",parent="r",text="saved body")))
        val source=MapSource(documentId=UUID.randomUUID().toString(),fileName="reading.xnote",scope=SourceScope.PAGE,
            pageId=UUID.randomUUID().toString(),lastKnownPage=2,sourceVersion="a".repeat(64))
        val leases=mutableListOf<MapDocumentLease>()
        val release=java.util.concurrent.CountDownLatch(1)
        init {
            provider.attachInfo(context,ProviderInfo().apply {this.authority=this@Fixture.authority})
            ShadowContentResolver.registerProviderInternal(authority,provider)
            provider.file(uri).writeBytes(MapCodec.encode(original));provider.file(empty).writeBytes(byteArrayOf())
        }
        suspend fun acquire(at:Uri=uri):MapDocumentLease {
            val lease=withContext(Dispatchers.Main) {MapDocuments.acquire(context,at).also {leases+=it}}
            await { !lease.session.loading };assertFalse(lease.session.loadFailed)
            return lease
        }
        suspend fun add(lease:MapDocumentLease,ticket:MapSourceImportTicket):MapSaveOutcome {
            assertTrue(lease.session.importSource(source,ticket) is MapSourceImportResult.Applied)
            return lease.session.saveAttempt().await()
        }
        suspend fun await(check:()->Boolean)=withTimeout(10000L) {while(!withContext(Dispatchers.Main) {check()}) delay(1)}
    }
    private fun scenario(block:suspend (Fixture)->Unit) {
        val f=Fixture(RuntimeEnvironment.getApplication())
        val field=NotebookStorage::class.java.getDeclaredField("instance").apply {isAccessible=true};val previous=field.get(null)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);var failure:Throwable?=null
        val job=scope.launch {
            try {
                withContext(Dispatchers.Main) {field.set(null,f.storage)}
                withTimeout(20000L) {block(f)}
            } catch(error:Throwable) {failure=error}
            finally {withContext(NonCancellable) {
                f.release.countDown();f.provider.beforeWrite=null;f.provider.denyRead=false
                val jobs=withContext(Dispatchers.Main) {
                    f.leases.filter {it.alive}.forEach {lease->lease.session.textDraft?.let {lease.session.cancelTextDraft(it.token)}
                        lease.session.textRestore?.let {lease.session.completeTextRestore(it.token)}}
                    f.leases.map {it.close()}
                }
                jobs.forEach {it.join()}
                withContext(Dispatchers.IO) {f.storage.registry.close()};field.set(null,previous)
            }}
        }
        try {
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
            while(!job.isCompleted && System.nanoTime()<deadline) {shadowOf(Looper.getMainLooper()).idle();Thread.sleep(1)}
            assertTrue("Real destination actor did not settle",job.isCompleted);failure?.let {throw it}
        } finally {scope.cancel();field.set(null,previous);if(job.isCompleted) f.root.deleteRecursively()}
    }
    @Test fun canonicalLeasesShareTheLiveDirtyActorAndAddingASourceKeepsItsUnconfirmedTextAndUndoHistory()=scenario {f->
        val activity=f.acquire();val reader=f.acquire(f.alias)
        withContext(Dispatchers.Main) {
            assertSame(activity.session,reader.session)
            assertTrue(activity.session.operate {it.update("n") {n->n.copy(text="live unsaved body")}})
            assertTrue(activity.session.beginText("n",MindMapView.Viewport(1f,0f,0f),1000,700))
            val draft=activity.session.textDraft!!
            assertTrue(activity.session.updateTextDraft(draft.token,TextFieldValue("unconfirmed typing")))
            val ticket=reader.session.captureSourceImport(MapReferenceDestination.Existing("n"))!!
            assertTrue(f.add(reader,ticket) is MapSaveOutcome.Written)
            assertEquals("live unsaved body",activity.session.document!!.node("n").text)
            assertEquals("unconfirmed typing",activity.session.textDraft!!.value.text)
            assertEquals(listOf(f.source),activity.session.document!!.node("n").sources)
        }
        withContext(Dispatchers.Main) {reader.close()}.join()
        withContext(Dispatchers.Main) {
            assertTrue(activity.alive);assertEquals("unconfirmed typing",activity.session.textDraft!!.value.text)
            val token=activity.session.textDraft!!.token;activity.session.cancelTextDraft(token);activity.session.completeTextRestore(token)
            activity.session.undo()
            assertEquals("live unsaved body",activity.session.document!!.node("n").text)
            assertTrue(activity.session.document!!.node("n").sources.isEmpty())
        }
        assertEquals(0,f.provider.deletes)
    }
    @Test fun aWrittenButUnverifiedAddCanRetryTheSameSourceWithoutAnotherWriteOrAnotherUndoStep()=scenario {f->
        val lease=f.acquire();val ticket=withContext(Dispatchers.Main) {lease.session.captureSourceImport(MapReferenceDestination.Existing("n"))!!}
        f.provider.failReadbackAfterWrite=true
        assertTrue(withContext(Dispatchers.Main) {f.add(lease,ticket)} is MapSaveOutcome.Retained)
        val written=f.provider.file(f.uri).readBytes();assertEquals(1,f.provider.writeOpens)
        assertTrue(withContext(Dispatchers.Main) {f.add(lease,ticket)} is MapSaveOutcome.Written)
        assertEquals(1,f.provider.writeOpens);assertArrayEquals(written,f.provider.file(f.uri).readBytes())
        withContext(Dispatchers.Main) {
            assertEquals(listOf(f.source),lease.session.document!!.node("n").sources)
            lease.session.undo();assertTrue(lease.session.document!!.node("n").sources.isEmpty());assertFalse(lease.session.canUndo)
        }
    }
    @Test fun newGraphCreationRefusesOccupiedTargetsAndTheFirstSourceIsOneRealUndoAfterActorHandoff()=scenario {f->
        val before=f.provider.file(f.uri).readBytes()
        val fixed=MindMapDocument(nodes=listOf(MapNode(id="new-root",parent=null,kind=NodeKind.REFERENCE)))
        val refused=withContext(Dispatchers.Main) {MapDocuments.create(f.context,f.uri,fixed)}
        assertTrue(refused is MapCreateOutcome.Failed);assertEquals(0,f.provider.writeOpens);assertEquals(0,f.provider.deletes);assertArrayEquals(before,f.provider.file(f.uri).readBytes())
        val reader=withContext(Dispatchers.Main) {(MapDocuments.create(f.context,f.empty,fixed) as MapCreateOutcome.Created).lease.also {f.leases+=it}}
        f.await {!reader.session.loading}
        withContext(Dispatchers.Main) {assertTrue(f.add(reader,reader.session.captureSourceImport(MapReferenceDestination.Existing("new-root"))!!) is MapSaveOutcome.Written)}
        val host=f.acquire(f.empty);assertSame(reader.session,host.session)
        withContext(Dispatchers.Main) {reader.close()}.join()
        withContext(Dispatchers.Main) {
            assertEquals(fixed.id,host.session.document!!.id);assertNotEquals(f.original.id,host.session.document!!.id)
            host.session.undo();assertTrue(host.session.document!!.root.sources.isEmpty());assertFalse(host.session.canUndo)
        }
    }
    @Test fun aNodeRemovedAndRestoredByUndoRejectsTheOldTicketAndANewReferenceNodeAddsInOneStep()=scenario {f->
        val lease=f.acquire()
        withContext(Dispatchers.Main) {
            val old=lease.session.captureSourceImport(MapReferenceDestination.Existing("n"))!!
            assertTrue(lease.session.operate {it.delete("n")});lease.session.undo()
            assertEquals(MapSourceImportResult.Changed,lease.session.importSource(f.source,old))
            assertTrue(lease.session.document!!.nodes.flatMap {it.sources}.isEmpty());assertEquals(0,f.provider.writeOpens)
            val node=MapNode(id="new-reference",parent=null,free=true,kind=NodeKind.REFERENCE,x=500.0)
            val ticket=lease.session.captureSourceImport(MapReferenceDestination.New(node))!!
            val result=lease.session.importSource(f.source,ticket)
            assertTrue(result is MapSourceImportResult.Applied)
            assertTrue(lease.session.saveAttempt().await() is MapSaveOutcome.Written)
            val added=lease.session.document!!.node((result as MapSourceImportResult.Applied).nodeId)
            assertEquals(NodeKind.REFERENCE,added.kind);assertTrue(added.free);assertEquals(listOf(f.source),added.sources)
            assertTrue(lease.session.sourceImportCurrent(ticket))
            lease.session.undo();assertTrue(lease.session.document!!.nodes.none {it.id==node.id})
            assertFalse(lease.session.sourceImportCurrent(ticket))
            lease.session.redo();assertFalse(lease.session.sourceImportCurrent(ticket))
            assertEquals(MapSourceImportResult.Changed,lease.session.importSource(f.source,ticket))
        }
    }
    @Test fun aFailedEmptyActorCanBeReacquiredAfterPermissionRestoration()=scenario {f->
        f.provider.denyRead=true
        val failed=withContext(Dispatchers.Main) {MapDocuments.acquire(f.context,f.uri).also {f.leases+=it}}
        f.await {!failed.session.loading};assertTrue(failed.session.loadFailed);assertNull(failed.session.document)
        f.provider.denyRead=false
        val current=f.acquire()
        assertNotSame("An empty failed opened actor must not permanently mask restored access",failed.session,current.session)
        assertEquals(f.original,current.session.document)
        withContext(Dispatchers.Main) {failed.close()}.join()
        withContext(Dispatchers.Main) {
            assertTrue(current.alive)
            assertNotNull(current.session.captureSourceImport(MapReferenceDestination.Existing("n")))
        }
    }
    @Test fun anExactWrittenAttemptDoesNotClearLaterCommittedBodyChanges()=scenario {f->coroutineScope {
        val lease=f.acquire();val entered=CompletableDeferred<Unit>()
        f.provider.beforeWrite={entered.complete(Unit);check(f.release.await(10,TimeUnit.SECONDS))}
        val attempt=withContext(Dispatchers.Main) {
            val ticket=lease.session.captureSourceImport(MapReferenceDestination.Existing("n"))!!
            assertTrue(lease.session.importSource(f.source,ticket) is MapSourceImportResult.Applied)
            lease.session.saveAttempt()
        }
        entered.await()
        withContext(Dispatchers.Main) {assertTrue(lease.session.operate {it.update("n") {n->n.copy(text="C1 during IO")}})}
        f.release.countDown();val outcome=attempt.await()
        assertTrue(outcome is MapSaveOutcome.Written);assertFalse((outcome as MapSaveOutcome.Written).latest)
        withContext(Dispatchers.Main) {assertTrue(lease.session.dirty);assertEquals("C1 during IO",lease.session.document!!.node("n").text)}
        assertEquals("saved body",MapCodec.decode(f.provider.file(f.uri).readBytes()).node("n").text)
        assertEquals(listOf(f.source),MapCodec.decode(f.provider.file(f.uri).readBytes()).node("n").sources)
    }}
    @Test fun reacquiringDuringLastReleaseKeepsTheSameActorAfterTheOldRetirementSaveCompletes()=scenario {f->coroutineScope {
        val first=f.acquire();val entered=CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) {assertTrue(first.session.operate {it.update("n") {n->n.copy(text="keep this draft")}})}
        f.provider.beforeWrite={entered.complete(Unit);check(f.release.await(10,TimeUnit.SECONDS))}
        val closing=withContext(Dispatchers.Main) {first.close()}
        entered.await();val next=f.acquire(f.alias)
        assertSame(first.session,next.session)
        f.release.countDown();closing.join()
        withContext(Dispatchers.Main) {
            assertTrue(next.alive);assertEquals("keep this draft",next.session.document!!.node("n").text)
            assertNotNull(next.session.captureSourceImport(MapReferenceDestination.Existing("n")))
        }
    }}
    @Test fun closingOneViewAfterSaveLeavesTheSharedActorAvailableForAnotherImport()=scenario {f->
        val activity=f.acquire();val reader=f.acquire(f.alias)
        var closed=false
        withContext(Dispatchers.Main) {
            assertTrue(activity.session.operate {it.update("n") {n->n.copy(text="saved before closing a view")}})
            activity.session.save(onClosed={activity.close();closed=true})
        }
        f.await {closed}
        withContext(Dispatchers.Main) {
            assertFalse(reader.session.closing)
            val ticket=reader.session.captureSourceImport(MapReferenceDestination.Existing("n"))!!
            assertTrue(f.add(reader,ticket) is MapSaveOutcome.Written)
            assertEquals(listOf(f.source),reader.session.document!!.node("n").sources)
        }
    }
    @Test fun aRegistryFailureBeforeTheCreationStoreExistsIsTypedAndReleasesItsReservation()=scenario {f->
        withContext(Dispatchers.IO) {f.storage.registry.writableDatabase.execSQL("DROP TABLE locations")}
        val fixed=MindMapDocument()
        val result=MapDocuments.create(f.context,f.empty,fixed)
        assertTrue(result is MapCreateOutcome.Failed)
        assertEquals(0,f.provider.writeOpens);assertEquals(0,f.provider.deletes)
        assertEquals(0L,f.provider.file(f.empty).length())
        withContext(Dispatchers.IO) {f.storage.registry.writableDatabase.execSQL(
            "CREATE TABLE locations(uri TEXT PRIMARY KEY,location_key TEXT NOT NULL UNIQUE,document_id TEXT NOT NULL REFERENCES documents(id),available INTEGER NOT NULL DEFAULT 1)")}
        val retried=MapDocuments.create(f.context,f.empty,fixed)
        assertTrue(retried is MapCreateOutcome.Created)
        val lease=(retried as MapCreateOutcome.Created).lease
        withContext(Dispatchers.Main) {f.leases+=lease}
        f.await {!lease.session.loading}
        assertEquals(fixed,lease.session.document)
        assertEquals(1,f.provider.writeOpens)
    }
    private class QueuedDispatcher:CoroutineDispatcher() {
        private val tasks=java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context:kotlin.coroutines.CoroutineContext,block:Runnable) {tasks.add(block)}
        val pending get()=tasks.isNotEmpty()
        fun runNext() {requireNotNull(tasks.poll()).run()}
        fun drain() {while(pending) runNext()}
    }
    @Test fun cancellationWhenCreatedLeaseReturnsFromMainReleasesTheUnreceivedLease()=scenario {f->
        val dispatcher=QueuedDispatcher()
        val task=CoroutineScope(SupervisorJob()+dispatcher).async {MapDocuments.create(f.context,f.empty,MindMapDocument())}
        dispatcher.runNext() // Begin; Main reserves the canonical destination.
        f.await {dispatcher.pending};dispatcher.runNext() // Begin the actual create IO.
        f.await {dispatcher.pending};dispatcher.runNext() // Publish the successful lease on Main.
        f.await {dispatcher.pending}
        val observed=f.acquire(f.empty)
        withContext(Dispatchers.Main) {observed.close()}.join()
        task.cancel()
        withTimeout(10000) {while(!task.isCompleted) {dispatcher.drain();delay(1)}}
        assertTrue(task.isCancelled)
        val fresh=f.acquire(f.empty)
        assertNotSame("A cancelled cross-dispatcher return must not leak the creator lease",observed.session,fresh.session)
        assertEquals(1,f.provider.writeOpens);assertEquals(0,f.provider.deletes)
    }
    @Test fun appendingToAnExistingNodePreservesItsUnspecifiedPrimaryPreview()=scenario {f->
        val lease=f.acquire()
        withContext(Dispatchers.Main) {
            val first=f.source.copy(id=UUID.randomUUID().toString(),note="First source")
            val second=f.source.copy(id=UUID.randomUUID().toString(),pageId=UUID.randomUUID().toString(),note="Second source")
            assertTrue(lease.session.operate {it.update("n") {node->node.copy(sources=listOf(first,second),primarySource=null)}})
            val before=lease.session.document!!
            val ticket=lease.session.captureSourceImport(MapReferenceDestination.Existing("n"))!!
            assertTrue(f.add(lease,ticket) is MapSaveOutcome.Written)
            val attached=lease.session.document!!.node("n")
            assertEquals(listOf(first,second,f.source),attached.sources)
            assertNull("Appending a source must not silently choose its preview",attached.primarySource)
            assertNull(MapCodec.decode(f.provider.file(f.uri).readBytes()).node("n").primarySource)
            lease.session.undo();assertEquals(before,lease.session.document)
        }
    }
    @Test fun aNewBranchWaitsForTextEditingAndViewportRestorationButExistingAndFreeReferencesStillMerge()=scenario {f->
        val lease=f.acquire()
        withContext(Dispatchers.Main) {
            assertTrue(lease.session.beginText("n",MindMapView.Viewport(1f,0f,0f),1000,700))
            val token=lease.session.textDraft!!.token
            assertTrue(lease.session.updateTextDraft(token,TextFieldValue("Finished typed body")))
            val branch=MapNode(id="branch",parent="n",kind=NodeKind.REFERENCE)
            val branchTicket=lease.session.captureSourceImport(MapReferenceDestination.New(branch))!!
            val branchSource=f.source.copy(id=UUID.randomUUID().toString())
            val before=lease.session.document!!;val revision=lease.session.contentRevision
            assertEquals(MapSourceImportResult.Busy,lease.session.importSource(branchSource,branchTicket))
            assertEquals(before,lease.session.document);assertEquals(revision,lease.session.contentRevision)
            assertFalse(lease.session.canUndo);assertEquals(0,f.provider.writeOpens)
            val existing=lease.session.captureSourceImport(MapReferenceDestination.Existing("n"))!!
            assertTrue(lease.session.importSource(f.source,existing) is MapSourceImportResult.Applied)
            val free=MapNode(id="free-reference",parent=null,free=true,kind=NodeKind.REFERENCE,x=1200.0)
            val freeTicket=lease.session.captureSourceImport(MapReferenceDestination.New(free))!!
            assertTrue(lease.session.importSource(f.source.copy(id=UUID.randomUUID().toString()),freeTicket) is MapSourceImportResult.Applied)
            assertEquals(before.nodes.map {it.bounds},lease.session.document!!.nodes.filter {it.id!="free-reference"}.map {it.bounds})
            assertEquals("Finished typed body",lease.session.textDraft!!.value.text)
            assertTrue(lease.session.finishTextDraft(token))
            val restoring=lease.session.document!!;val restoringRevision=lease.session.contentRevision
            assertNotNull(lease.session.textRestore)
            assertEquals(MapSourceImportResult.Busy,lease.session.importSource(branchSource,branchTicket))
            assertEquals(restoring,lease.session.document);assertEquals(restoringRevision,lease.session.contentRevision)
            lease.session.completeTextRestore(token)
            assertTrue(lease.session.importSource(branchSource,branchTicket) is MapSourceImportResult.Applied)
            assertEquals("Finished typed body",lease.session.document!!.node("n").text)
            assertEquals(listOf(f.source),lease.session.document!!.node("n").sources)
            assertEquals(branchSource.id,lease.session.document!!.node("branch").primarySource)
            assertTrue(lease.session.saveAttempt().await() is MapSaveOutcome.Written)
            lease.session.undo();assertEquals(restoring,lease.session.document)
        }
    }

}
