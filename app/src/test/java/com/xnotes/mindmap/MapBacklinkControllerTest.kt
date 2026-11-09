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
import com.xnotes.storage.NotebookStorage
import com.xnotes.ui.BacklinkPageIdentity
import com.xnotes.ui.NativeBacklinkContext
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** Real tree enumeration, actors, source-list history, recovery writes and visible preview reads. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class MapBacklinkControllerTest {
    private class Provider(val root:File):ContentProvider() {
        var treeQueries=0;var reads=0;var writeAttempts=0;var writes=0;var deletes=0;var failWrites=false
        var beforeWrite:(()->Unit)?=null
        val redirects=mutableMapOf<String,File>()
        fun file(uri:Uri):File {
            val id=DocumentsContract.getDocumentId(uri)
            redirects[id]?.let {return it}
            return if(id=="root") root else File(root,id.removePrefix("root/"))
        }
        override fun onCreate()=true
        override fun getType(uri:Uri)="application/octet-stream"
        override fun query(uri:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,sort:String?):Cursor {
            val columns=p ?: arrayOf(OpenableColumns.DISPLAY_NAME)
            return MatrixCursor(columns).apply {
                fun add(file:File) {addRow(columns.map {column->when(column) {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID->if(file==root) "root" else "root/${file.relativeTo(root).invariantSeparatorsPath}"
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME->if(file==root) "Library" else file.name
                    DocumentsContract.Document.COLUMN_MIME_TYPE->if(file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
                    DocumentsContract.Document.COLUMN_SIZE->file.length()
                    else->null
                }}.toTypedArray())}
                if(uri.lastPathSegment=="children") {treeQueries++;file(uri).listFiles().orEmpty().sortedBy {it.name}.forEach(::add)} else add(file(uri))
            }
        }
        override fun insert(uri:Uri,v:ContentValues?):Uri?=null
        override fun update(uri:Uri,v:ContentValues?,s:String?,a:Array<out String>?)=0
        override fun delete(uri:Uri,s:String?,a:Array<out String>?):Int {deletes++;error("A backlink operation cannot delete provider files")}
        override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
            if('w' in mode) {
                writeAttempts++;if(failWrites) throw IOException("Provider rejected output")
                val fd=ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE)
                try {beforeWrite?.invoke();writes++;return fd} catch(error:Throwable) {fd.close();throw error}
            }
            reads++;return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }
    private class Fixture(base:Context) {
        val root=File(base.cacheDir,"backlink-controller-${UUID.randomUUID()}").apply {mkdirs()}
        val context=object:ContextWrapper(base) {
            override fun getApplicationContext():Context=this
            override fun getFilesDir()=File(root,"files").apply {mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
        }
        val storage=NotebookStorage(context)
        val authority="backlink-controller-${UUID.randomUUID()}"
        val provider=Provider(File(root,"library").apply {mkdirs()})
        val tree=DocumentsContract.buildTreeDocumentUri(authority,"root")
        val uri=DocumentsContract.buildDocumentUriUsingTree(tree,"root/graph.xmap")
        val documentId=UUID.randomUUID().toString()
        val one=MapSource(documentId=documentId,fileName="Source.xnote",scope=SourceScope.PAGE,pageId=UUID.randomUUID().toString(),lastKnownPage=1)
        val two=one.copy(id=UUID.randomUUID().toString(),pageId=UUID.randomUUID().toString(),lastKnownPage=2)
        var sourceCurrent=true
        val source=NativeBacklinkContext.observe(documentId,one.pageId,1,BacklinkPageIdentity.AVAILABLE,listOf(tree.toString())) {sourceCurrent}
        val original=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,text="Root"),
            MapNode(id="n",parent="r",text="Independent concept",sources=listOf(one,two),primarySource=two.id)))
        val uiScope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val controller=MapBacklinkController(context,uiScope,source)
        val leases=mutableListOf<MapDocumentLease>()
        val gates=mutableListOf<CountDownLatch>()
        init {
            provider.attachInfo(context,ProviderInfo().apply {this.authority=this@Fixture.authority})
            ShadowContentResolver.registerProviderInternal(authority,provider)
            provider.file(uri).writeBytes(MapCodec.encode(original))
        }
        suspend fun start() {assertTrue(controller.refresh());await {!controller.loading && controller.report!=null}}
        suspend fun acquire():MapDocumentLease {
            val lease=MapDocuments.acquire(context,uri).also {leases+=it}
            await {!lease.session.loading};assertFalse(lease.session.loadFailed);return lease
        }
        suspend fun await(condition:()->Boolean)=withContext(Dispatchers.Default) {
            withTimeout(15000) {while(!withContext(Dispatchers.Main) {condition()}) delay(1)}
        }
        suspend fun close() {
            gates.forEach {it.countDown()};controller.close()
            leases.filter {it.alive}.forEach {it.close().join()}
            await {MapDocuments.observations(context).isEmpty()}
            uiScope.cancel()
        }
    }
    private fun scenario(block:suspend (Fixture)->Unit) {
        val f=Fixture(RuntimeEnvironment.getApplication());var failure:Throwable?=null
        val singleton=NotebookStorage::class.java.getDeclaredField("instance").apply {isAccessible=true};val prior=singleton.get(null)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val job=scope.launch {
            try {withContext(Dispatchers.Main) {singleton.set(null,f.storage);f.start();withTimeout(30000) {block(f)}}}
            catch(error:Throwable) {failure=error}
            finally {withContext(NonCancellable+Dispatchers.Main) {f.close();singleton.set(null,prior)}}
        }
        try {
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45)
            while(!job.isCompleted && System.nanoTime()<deadline) {
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications();shadowOf(Looper.getMainLooper()).idle();Thread.sleep(1)
            }
            assertTrue("Actual backlink controller did not settle",job.isCompleted);failure?.let {throw it}
        } finally {scope.cancel();if(job.isCompleted) {f.storage.registry.close();f.root.deleteRecursively()}}
    }
    @Test fun liveC1OverlaysWithoutRescanningAndOldStoredOrDeleteUndoRowsCannotAcquireNewProofs()=scenario {f->
        val c=f.controller;val stored=c.report!!.rows.single {it.sourceId==f.two.id}
        assertEquals(BacklinkOrigin.STORED,stored.origin)
        val request=c.captureOpen(stored)!!
        val lease=f.acquire();val s=lease.session
        f.await {!c.loading && c.report!!.rows.all {it.origin==BacklinkOrigin.LIVE}}
        assertTrue("An accepted request is not invalidated by its own possible live overlay",request.current())
        assertNull(c.captureOpen(stored))
        val queries=f.provider.treeQueries;val reads=f.provider.reads
        val live=c.report!!.rows.single {it.sourceId==f.two.id}
        val panel=SourceListEditor(s,s.sourceEditSnapshot("n")!!)
        try {assertTrue(panel.note(f.two.id,"Latest note"));assertTrue(panel.move(f.two.id,-1))} finally {panel.close()}
        f.await {c.report!!.rows.first().sourceId==f.two.id && c.report!!.rows.first().sourceNote=="Latest note"}
        assertEquals(queries,f.provider.treeQueries);assertEquals(reads,f.provider.reads)
        assertNotNull(c.captureOpen(live))
        assertTrue(s.operate {it.update("n") {node->node.copy(sources=node.sources.filterNot {it.id==f.two.id},primarySource=null)}})
        f.await {c.report!!.rows.size==1};s.undo();f.await {c.report!!.rows.size==2}
        assertNull(c.captureOpen(live));assertEquals(0,f.provider.writes);assertEquals(0,f.provider.deletes)
    }
    @Test fun failedRemovalRetriesOnlyTheSaveAndKeepsOtherSourcesBodyAndOneUndo()=scenario {f->
        val c=f.controller;val row=c.report!!.rows.single {it.sourceId==f.two.id}
        c.prepareRemove(row);f.await {c.removal is BacklinkRemovalState.Confirm}
        val token=c.removal!!.token;f.provider.failWrites=true;c.confirmRemove(token)
        f.await {c.removal is BacklinkRemovalState.Failed}
        val failed=c.removal as BacklinkRemovalState.Failed
        assertTrue(failed.canRetrySave);assertTrue(failed.canReview)
        val session=MapDocuments.observations(f.context).single().session
        assertEquals(listOf(f.one),session.document!!.node("n").sources);assertNull(session.document!!.node("n").primarySource)
        assertEquals("Independent concept",session.document!!.node("n").text);assertTrue(session.canUndo)
        assertNotNull(c.captureRemovedOpen(token))
        f.provider.failWrites=false;c.retryRemoveSave(token)
        f.await {c.removal is BacklinkRemovalState.Removed}
        val written=MapCodec.decode(f.provider.file(f.uri).readBytes())
        assertEquals(listOf(f.one),written.node("n").sources);assertEquals("Independent concept",written.node("n").text)
        assertEquals(1,f.provider.writes);assertEquals(2,f.provider.writeAttempts);assertEquals(0,f.provider.deletes)
        session.undo();assertEquals(listOf(f.one,f.two),session.document!!.node("n").sources);assertFalse(session.canUndo)
        assertNull(c.captureRemovedOpen(token))
    }
    @Test fun closingTheSourceTabDoesNotCancelAnAlreadyAcceptedMapSave()=scenario {f->
        val c=f.controller;c.prepareRemove(c.report!!.rows.single {it.sourceId==f.two.id})
        f.await {c.removal is BacklinkRemovalState.Confirm}
        val entered=CountDownLatch(1);val release=CountDownLatch(1);f.gates+=release
        f.provider.beforeWrite={entered.countDown();check(release.await(15,TimeUnit.SECONDS))}
        c.confirmRemove(c.removal!!.token)
        withContext(Dispatchers.Default) {assertTrue(entered.await(10,TimeUnit.SECONDS))}
        assertTrue(c.removal is BacklinkRemovalState.Saving);c.close();release.countDown()
        f.await {MapDocuments.observations(f.context).isEmpty()}
        assertEquals(listOf(f.one),MapCodec.decode(f.provider.file(f.uri).readBytes()).node("n").sources)
        assertEquals(1,f.provider.writes);assertEquals(0,f.provider.deletes)
    }
    @Test fun retiringTheLastLiveActorReloadsActualDiskInsteadOfResurrectingItsOldStoredRows()=scenario {f->
        val c=f.controller;assertEquals(2,c.report!!.rows.size)
        val lease=f.acquire();val session=lease.session
        f.await {!c.loading && c.report!!.rows.all {it.origin==BacklinkOrigin.LIVE}}
        assertTrue(session.operate {it.update("n") {node->node.copy(sources=emptyList(),primarySource=null)}})
        f.await {c.report!!.rows.isEmpty()}
        assertTrue(session.saveAttempt().await() is MapSaveOutcome.Written)
        val queries=f.provider.treeQueries
        lease.close().join()
        f.await {f.provider.treeQueries>queries && !c.loading && MapDocuments.observations(f.context).isEmpty() && c.report!!.rows.isEmpty()}
        assertTrue(c.report!!.complete);assertTrue(MapCodec.decode(f.provider.file(f.uri).readBytes()).node("n").sources.isEmpty())
    }
    @Test fun aLocationChangeRejectedAtConfirmationRetainsTheDirtyActorWithoutWritingWhenTheDialogAndTabClose()=scenario {f->
        val c=f.controller;c.prepareRemove(c.report!!.rows.single {it.sourceId==f.two.id})
        f.await {c.removal is BacklinkRemovalState.Confirm}
        val token=c.removal!!.token;val s=MapDocuments.observations(f.context).single().session
        assertTrue(s.operate {it.update("n") {node->node.copy(text="Unsaved C1 must remain in its actor")}})
        val original=f.provider.file(f.uri);val bytes=original.readBytes()
        val moved=File(f.provider.root,".xnote/trash/item/graph.xmap").apply {parentFile!!.mkdirs()}
        assertTrue(original.renameTo(moved));f.provider.redirects["root/graph.xmap"]=moved
        val movedUri=DocumentsContract.buildDocumentUriUsingTree(f.tree,"root/.xnote/trash/item/graph.xmap")
        withContext(Dispatchers.IO) {f.storage.registry.moved(f.uri.toString(),movedUri.toString())}
        c.confirmRemove(token);f.await {c.removal is BacklinkRemovalState.Failed}
        assertEquals(BacklinkProblem.CHANGED,(c.removal as BacklinkRemovalState.Failed).problem)
        c.dismissRemove(token);c.close()
        withContext(Dispatchers.Default) {delay(30)}
        assertFalse(s.saving);assertTrue(s.dirty)
        assertSame(s,MapDocuments.observations(f.context).single().session)
        assertEquals("Unsaved C1 must remain in its actor",s.document!!.node("n").text)
        assertEquals(listOf(f.one,f.two),s.document!!.node("n").sources)
        assertEquals(0,f.provider.writeAttempts);assertEquals(0,f.provider.deletes);assertArrayEquals(bytes,moved.readBytes())
        // Restore the fixture's actual location, then explicitly finish it through the normal actor path.
        f.provider.redirects.clear();assertTrue(moved.renameTo(original))
        withContext(Dispatchers.IO) {f.storage.registry.moved(movedUri.toString(),f.uri.toString())}
        val cleanup=f.acquire();assertSame(s,cleanup.session)
        assertTrue(s.saveAttempt().await() is MapSaveOutcome.Written)
    }
    private fun png():ByteArray {
        val data=ByteArrayOutputStream();val out=DataOutputStream(data)
        out.write(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
        fun chunk(name:String,body:ByteArray) {val type=name.toByteArray();val crc=CRC32().apply {update(type);update(body)}
            out.writeInt(body.size);out.write(type);out.write(body);out.writeInt(crc.value.toInt())}
        val header=ByteArrayOutputStream();DataOutputStream(header).use {it.writeInt(2);it.writeInt(2);it.write(byteArrayOf(8,6,0,0,0))};chunk("IHDR",header.toByteArray())
        val pixels=ByteArrayOutputStream();DeflaterOutputStream(pixels).use {z->repeat(2) {z.write(0);z.write(ByteArray(8) {-1})}}
        chunk("IDAT",pixels.toByteArray());chunk("IEND",byteArrayOf());return data.toByteArray()
    }
    @Test fun visibleSourcesInOneClosedGraphShareOnePreviewReadAndNeverOpenTheirNativeSource()=scenario {f->
        val asset=MapPreviewAsset.fromPng(png())
        val updated=f.original.copy(nodes=f.original.nodes.map {node->node.copy(sources=node.sources.map {s->s.copy(previewKey=asset.key,
            lastPreview=MapLastPreview(asset.key,s.target,"native-sha256:${"a".repeat(64)}",1L))})},previewAssets=mapOf(asset.key to asset))
        f.provider.file(f.uri).writeBytes(MapCodec.encode(updated));f.controller.refresh();f.await {!f.controller.loading}
        val rows=f.controller.report!!.rows;assertEquals(2,rows.size)
        val reads=f.provider.reads;rows.forEach {f.controller.showPreview(it,true)}
        f.await {f.controller.previewAssets.size==2 && f.controller.previewBusy.isEmpty()}
        assertEquals("The batch must open/decode this xmap only once",reads+1,f.provider.reads)
        rows.forEach {assertArrayEquals(asset.pngBytes(),f.controller.previewAssets.getValue(it.key).pngBytes())}
        rows.forEach {f.controller.showPreview(it,false)};assertTrue(f.controller.previewAssets.isEmpty())
        assertEquals(0,f.provider.writes);assertEquals(0,f.provider.deletes)
    }
    @Test fun retryAfterUndoSavesTheLatestGraphWithoutClaimingThatTheSourceWasRemoved()=scenario {f->
        val originalBytes=f.provider.file(f.uri).readBytes()
        val c=f.controller;c.prepareRemove(c.report!!.rows.single {it.sourceId==f.two.id})
        f.await {c.removal is BacklinkRemovalState.Confirm}
        val token=c.removal!!.token;f.provider.failWrites=true;c.confirmRemove(token)
        f.await {c.removal is BacklinkRemovalState.Failed}
        val s=MapDocuments.observations(f.context).single().session
        s.undo();assertEquals(listOf(f.one,f.two),s.document!!.node("n").sources)
        assertFalse(s.canUndo);assertTrue(s.canRedo)
        assertTrue((c.removal as BacklinkRemovalState.Failed).canRetrySave)
        assertFalse(c.removal!!.canReview)
        f.provider.failWrites=false;c.retryRemoveSave(token)
        f.await {!s.saving && c.removal !is BacklinkRemovalState.Preparing && c.removal !is BacklinkRemovalState.Saving}
        assertTrue(c.removal is BacklinkRemovalState.SavedChanged)
        assertFalse(c.removal is BacklinkRemovalState.Removed);assertFalse(c.removal!!.canReview)
        assertEquals(listOf(f.one,f.two),MapCodec.decode(f.provider.file(f.uri).readBytes()).node("n").sources)
        assertEquals("Independent concept",s.document!!.node("n").text)
        assertFalse(s.canUndo);assertTrue(s.canRedo)
        assertArrayEquals(originalBytes,f.provider.file(f.uri).readBytes())
        assertEquals("The original file already contains the undone document; retry only verifies it",0,f.provider.writes)
        assertEquals(1,f.provider.writeAttempts);assertEquals(0,f.provider.deletes)
    }

    @Test fun aScopeVetoRemainsEffectiveWhenTheOtherTemporaryLeaseIsTheLastOneToCancel()=scenario {f->
        val c=f.controller;c.prepareRemove(c.report!!.rows.single {it.sourceId==f.two.id})
        f.await {c.removal is BacklinkRemovalState.Confirm}
        val token=c.removal!!.token;val second=f.acquire();val s=second.session
        assertTrue(s.operate {it.update("n") {node->node.copy(text="C1 survives both temporary leases")}})
        val original=f.provider.file(f.uri);val bytes=original.readBytes()
        val moved=File(f.provider.root,".xnote/trash/item/graph.xmap").apply {parentFile!!.mkdirs()}
        assertTrue(original.renameTo(moved));f.provider.redirects["root/graph.xmap"]=moved
        val movedUri=DocumentsContract.buildDocumentUriUsingTree(f.tree,"root/.xnote/trash/item/graph.xmap")
        withContext(Dispatchers.IO) {f.storage.registry.moved(f.uri.toString(),movedUri.toString())}
        c.confirmRemove(token);f.await {c.removal is BacklinkRemovalState.Failed}
        assertEquals(BacklinkProblem.CHANGED,(c.removal as BacklinkRemovalState.Failed).problem)
        c.dismissRemove(token);c.close() // A's scope veto occurs while B still owns the actor.
        assertTrue(second.alive);second.close().join() // B's ordinary cancel must not start a save.
        withContext(Dispatchers.Default) {delay(30)}
        assertFalse(s.saving);assertTrue(s.dirty);assertSame(s,MapDocuments.observations(f.context).single().session)
        assertEquals(0,f.provider.writeAttempts);assertEquals(0,f.provider.deletes);assertArrayEquals(bytes,moved.readBytes())
        assertEquals("C1 survives both temporary leases",s.document!!.node("n").text)
        f.provider.redirects.clear();assertTrue(moved.renameTo(original))
        withContext(Dispatchers.IO) {f.storage.registry.moved(movedUri.toString(),f.uri.toString())}
        val cleanup=f.acquire();assertSame(s,cleanup.session)
        assertTrue(s.saveAttempt().await() is MapSaveOutcome.Written)
    }

    @Test fun savingARemovalDuringATextDraftReportsTheRemovalWithoutMintingABusyFocusTicket()=scenario {f->
        val c=f.controller;c.prepareRemove(c.report!!.rows.single {it.sourceId==f.two.id})
        f.await {c.removal is BacklinkRemovalState.Confirm}
        val token=c.removal!!.token;val s=MapDocuments.observations(f.context).single().session
        assertTrue(s.beginText("n",MindMapView.Viewport(1f,0f,0f),1000,700))
        val draft=s.textDraft!!.token
        assertTrue(s.updateTextDraft(draft,androidx.compose.ui.text.input.TextFieldValue("Unconfirmed body C1")))
        c.confirmRemove(token);f.await {c.removal is BacklinkRemovalState.Removed}
        assertFalse(c.removal!!.canReview);assertNull(c.captureRemovedOpen(token))
        assertEquals(draft,s.textDraft!!.token);assertEquals("Unconfirmed body C1",s.textDraft!!.value.text)
        assertEquals("Independent concept",s.document!!.node("n").text)
        assertEquals(listOf(f.one),MapCodec.decode(f.provider.file(f.uri).readBytes()).node("n").sources)
        assertEquals(1,f.provider.writes);assertEquals(0,f.provider.deletes)
        s.cancelTextDraft(draft);s.completeTextRestore(s.textRestore!!.token)
        assertNotNull(c.captureRemovedOpen(token))
    }

}
