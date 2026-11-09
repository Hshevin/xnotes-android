package com.xnotes.pen

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.MotionEvent
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Stroke
import com.xnotes.mindmap.*
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
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** Actual host/coordinator + canonical actors + provider bytes. No alternate Editor or map engine. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class MapWorkspaceHostTest {
    private class Fixture(val native:EditorPageCopyFixture) {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val workspace=MapWorkspace(native.context,scope)
        val authority="map-workspace-${UUID.randomUUID()}"
        val uri=DocumentsContract.buildDocumentUri(authority,"root/a.xmap")
        val alias=DocumentsContract.buildDocumentUriUsingTree(DocumentsContract.buildTreeDocumentUri(authority,"root"),"root/a.xmap")
        val other=DocumentsContract.buildDocumentUri(authority,"root/b.xmap")
        val readCount=AtomicInteger();val writes=AtomicInteger();val deletes=AtomicInteger()
        @Volatile var denyRead:Uri?=null
        @Volatile var denyWrite=false
        @Volatile var beforeRead:((Uri)->Unit)?=null
        val held=mutableListOf<MapDocumentLease>()
        val entries=mutableListOf<MapWorkspace.Entry>()
        fun file(uri:Uri)=File(native.root,DocumentsContract.getDocumentId(uri).substringAfterLast('/'))
        val document=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,text="root"),MapNode(id="n",parent="r",text="saved body")))
        private val provider=object:ContentProvider() {
            override fun onCreate()=true
            override fun getType(uri:Uri)="application/octet-stream"
            override fun query(uri:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,sort:String?):Cursor =
                MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)).apply {addRow(arrayOf(file(uri).name,file(uri).length()))}
            override fun insert(uri:Uri,values:ContentValues?):Uri?=null
            override fun update(uri:Uri,values:ContentValues?,s:String?,a:Array<out String>?)=0
            override fun delete(uri:Uri,s:String?,a:Array<out String>?):Int {deletes.incrementAndGet();error("Host must not delete map data")}
            override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
                val writing='w' in mode
                if(writing) {
                    if(denyWrite) throw IOException("Provider failed to write the map")
                    writes.incrementAndGet()
                } else {
                    readCount.incrementAndGet();beforeRead?.invoke(uri)
                    if(denyRead?.let {DocumentsContract.getDocumentId(it)==DocumentsContract.getDocumentId(uri)}==true) throw SecurityException("Permission lost")
                }
                return ParcelFileDescriptor.open(file(uri),if(writing) ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE or
                    ParcelFileDescriptor.MODE_CREATE else ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }
        init {
            provider.attachInfo(native.context,ProviderInfo().apply {this.authority=this@Fixture.authority})
            ShadowContentResolver.registerProviderInternal(authority,provider)
            file(uri).writeBytes(MapCodec.encode(document))
            file(other).writeBytes(MapCodec.encode(document.copy(id=UUID.randomUUID().toString())))
        }
        suspend fun await(check:()->Boolean)=withTimeout(10000L) {while(!withContext(Dispatchers.Main) {check()}) delay(1L)}
        suspend fun opened(at:Uri=uri):MapWorkspace.Entry {
            withContext(Dispatchers.Main) {assertTrue(workspace.open(at,file(at).name))}
            await {!workspace.opening && workspace.visible}
            return withContext(Dispatchers.Main) {requireNotNull(workspace.current).also {
                assertEquals(MapSaves.key(at),MapSaves.key(it.uri));entries+=it
            }}
        }
        suspend fun acquire(at:Uri=uri):MapDocumentLease=withContext(Dispatchers.Main) {
            MapDocuments.acquire(native.context,at).also {held+=it}
        }
        suspend fun finish()=withContext(NonCancellable) {
            native.release.countDown();beforeRead=null;denyRead=null
            val disposal=withContext(Dispatchers.Main) {
                workspace.current?.let {entries+=it}
                workspace.dispose()
            }
            disposal.join()
            val jobs=withContext(Dispatchers.Main) {(entries.map {it.lease}+held).distinct().map {it.close()}}
            jobs.forEach {it.join()};scope.cancel()
        }
    }
    private fun scenario(block:suspend (Fixture)->Unit)=runParkingScenario {native->
        val f=Fixture(native)
        try {block(f)} finally {f.finish()}
    }

    @Test fun theMapCannotShowUntilAllRealNativePenTransactionsEndAndCancellingDoesNotCancelThem()=scenario {f->
        val other=openParkingPanes(f.native)
        val original=f.native.source.readBytes();val second=f.native.target.readBytes()
        var prepared=CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) {
            parkingPen(f.native.editor,MotionEvent.ACTION_DOWN,Pt(100.0,100.0));parkingPen(f.native.editor,MotionEvent.ACTION_MOVE,Pt(140.0,120.0))
            assertTrue(f.workspace.open(f.uri,"a",preparePresentation={
                prepared.complete(Unit)
                val proof=f.native.editor.awaitHostParking()
                if(proof==null) null else ({proof.current()})
            }))
        }
        prepared.await();parkingDispatch()
        withContext(Dispatchers.Main) {
            assertTrue(f.workspace.opening);assertFalse(f.workspace.visible);assertNull(f.workspace.current)
            f.workspace.cancelOpen();assertTrue(f.native.editor.stylusInContact)
            parkingPen(f.native.editor,MotionEvent.ACTION_UP,Pt(180.0,140.0))
            parkingPen(other,MotionEvent.ACTION_DOWN,Pt(100.0,100.0));parkingPen(other,MotionEvent.ACTION_MOVE,Pt(140.0,120.0))
        }
        prepared=CompletableDeferred()
        withContext(Dispatchers.Main) {assertTrue(f.workspace.open(f.uri,"a",preparePresentation={
            prepared.complete(Unit);val proof=f.native.editor.awaitHostParking();if(proof==null) null else ({proof.current()})
        }))}
        prepared.await();parkingDispatch()
        withContext(Dispatchers.Main) {
            assertFalse(f.workspace.visible);assertNull(f.workspace.current)
            parkingPen(other,MotionEvent.ACTION_UP,Pt(180.0,140.0))
        }
        f.await {!f.workspace.opening && f.workspace.visible}
        withContext(Dispatchers.Main) {
            assertEquals(1,f.native.editor.state.document.pages.first().items.filterIsInstance<Stroke>().size)
            assertEquals(1,other.state.document.pages.first().items.filterIsInstance<Stroke>().size)
            assertTrue(f.native.editor.state.document.dirty);assertTrue(other.state.document.dirty)
            assertNotNull(f.workspace.current!!.session.document)
        }
        assertArrayEquals(original,f.native.source.readBytes());assertArrayEquals(second,f.native.target.readBytes());assertEquals(0,f.native.writes.get())
    }

    @Test fun cancelledOrLateIncomingReadsReleaseOnlyTheirLeaseAndKeepTheOriginalEntry()=scenario {f->
        val original=f.opened(f.other);val entered=CompletableDeferred<Unit>()
        f.beforeRead={uri->if(DocumentsContract.getDocumentId(uri)==DocumentsContract.getDocumentId(f.uri)) {
            entered.complete(Unit);check(f.native.release.await(10,TimeUnit.SECONDS))
        }}
        val external=f.acquire();entered.await();val pendingActor=external.session
        withContext(Dispatchers.Main) {assertTrue(f.workspace.open(f.uri,"late"))}
        parkingDispatch()
        withContext(Dispatchers.Main) {
            f.workspace.cancelOpen()
            assertSame(original,f.workspace.current);assertTrue(original.lease.alive);assertTrue(f.workspace.visible)
        }
        f.native.release.countDown();f.beforeRead=null;f.await {!pendingActor.loading}
        withContext(Dispatchers.Main) {external.close()}.join()
        val fresh=f.acquire();f.await {!fresh.session.loading}
        withContext(Dispatchers.Main) {
            assertNotSame("A cancelled incoming host lease must not leak the clean old actor",pendingActor,fresh.session)
            assertSame(original,f.workspace.current);assertTrue(original.lease.alive);assertNull(f.workspace.failure)
        }
    }

    @Test fun canonicalReopenHideShowAndStaleClosePreserveTheSamePresentationActorAndOtherReaders()=scenario {f->
        val first=f.opened();val reader=f.acquire(f.alias);f.await {!reader.session.loading}
        withContext(Dispatchers.Main) {
            assertSame(first.session,reader.session)
            assertTrue(first.session.operate {it.update("n") {n->n.copy(text="first edit")}})
            assertTrue(first.session.operate {it.update("n") {n->n.copy(text="second edit")}});first.session.undo();first.session.selected="n"
            assertTrue(first.session.dirty);assertTrue(first.session.canRedo)
        }
        val reads=f.readCount.get()
        withContext(Dispatchers.Main) {assertTrue(f.workspace.open(f.alias,"alias"))}
        f.await {!f.workspace.opening}
        withContext(Dispatchers.Main) {
            assertSame(first,f.workspace.current);assertTrue(f.workspace.hide(first));assertFalse(f.workspace.visible)
            assertTrue(f.workspace.show(first));assertTrue(f.workspace.visible);assertSame(first,f.workspace.current)
            assertSame(first.presentation,f.workspace.current!!.presentation);assertEquals("first edit",first.session.document!!.node("n").text)
            assertEquals("n",first.session.selected);assertTrue(first.session.canRedo);assertTrue(first.session.dirty)
        }
        assertEquals("Canonical reuse and hide/show must not reread source bytes",reads,f.readCount.get())
        val second=f.opened(f.other)
        withContext(Dispatchers.Main) {
            assertFalse(first.lease.alive);assertTrue(reader.alive);assertEquals("first edit",reader.session.document!!.node("n").text)
            assertNull(f.workspace.close(first));assertFalse(f.workspace.hide(first));assertSame(second,f.workspace.current)
        }
        withContext(Dispatchers.Main) {requireNotNull(f.workspace.close(second))}.join()
        withContext(Dispatchers.Main) {assertNull(f.workspace.current);assertFalse(f.workspace.visible);assertTrue(reader.alive)}
    }

    @Test fun failedReadsAndAnExpiredNativeProofDoNotReplaceTheOldMapOrNativeModel()=scenario {f->
        openParkingPanes(f.native);val original=f.opened();val nativeModel=f.native.editor.state.document
        f.denyRead=f.other
        withContext(Dispatchers.Main) {assertTrue(f.workspace.open(f.other,"unreadable"))}
        f.await {!f.workspace.opening}
        withContext(Dispatchers.Main) {assertSame(original,f.workspace.current);assertTrue(original.lease.alive);assertEquals(MapWorkspaceFailure.UNAVAILABLE,f.workspace.failure);f.workspace.clearFailure()}
        f.denyRead=null
        val prepared=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) {assertTrue(f.workspace.open(f.other,"changed",preparePresentation={
            val proof=f.native.editor.awaitHostParking();prepared.complete(Unit);release.await()
            if(proof==null) null else ({proof.current()})
        }))}
        prepared.await()
        withContext(Dispatchers.Main) {f.native.editor.view.layout(0,0,1200,1000);release.complete(Unit)}
        f.await {!f.workspace.opening}
        withContext(Dispatchers.Main) {
            assertSame(original,f.workspace.current);assertTrue(f.workspace.visible);assertTrue(original.lease.alive)
            assertEquals(MapWorkspaceFailure.CHANGED,f.workspace.failure);assertSame(nativeModel,f.native.editor.state.document)
        }
        assertEquals(0,f.native.writes.get());assertEquals(0,f.deletes.get())
    }

    @Test fun anOldHostUnbindCannotCancelTheNewHostsActualReadOrEraseItsParkingProof()=scenario {f->
        val oldHost=Any();val newHost=Any()
        withContext(Dispatchers.Main) {f.workspace.bindHost(oldHost);assertTrue(f.workspace.isHostCurrent(oldHost))}
        val original=f.opened()
        withContext(Dispatchers.Main) {
            f.workspace.bindHost(newHost)
            assertFalse(f.workspace.isHostCurrent(oldHost));assertTrue(f.workspace.isHostCurrent(newHost))
            assertTrue(original.lease.alive)
        }
        val entered=CompletableDeferred<Unit>()
        f.beforeRead={uri->if(DocumentsContract.getDocumentId(uri)==DocumentsContract.getDocumentId(f.other)) {
            entered.complete(Unit);check(f.native.release.await(10,TimeUnit.SECONDS))
        }}
        withContext(Dispatchers.Main) {assertTrue(f.workspace.open(f.other,"new host",preparePresentation={
            val proof=f.native.editor.awaitHostParking()
            if(proof==null) null else ({f.workspace.isHostCurrent(newHost) && proof.current()})
        },stillCurrent={f.workspace.isHostCurrent(newHost)}))}
        entered.await()
        withContext(Dispatchers.Main) {
            assertFalse("Disposal of the old composition must not cancel this actual incoming read",f.workspace.unbindHost(oldHost))
            assertTrue(f.workspace.opening);assertTrue(f.workspace.isHostCurrent(newHost));assertSame(original,f.workspace.current)
        }
        f.beforeRead=null;f.native.release.countDown()
        f.await {!f.workspace.opening && f.workspace.current?.uri==f.other}
        withContext(Dispatchers.Main) {
            val adopted=requireNotNull(f.workspace.current);f.entries+=adopted
            assertTrue(adopted.lease.alive);assertTrue(f.workspace.visible);assertTrue(f.workspace.hide(adopted))
            assertFalse(f.workspace.unbindHost(oldHost))
            assertTrue("Old host disposal cannot erase B's final native parking proof",f.workspace.show(adopted))
            assertSame(adopted,f.workspace.current);assertTrue(f.workspace.isHostCurrent(newHost))
        }
        assertEquals(0,f.deletes.get());assertEquals(0,f.native.writes.get())
    }

    @Test fun aFailedDirtyPersistAndZeroLeaseCloseKeepTheActualRecoveryBody()=scenario {f->
        val entry=f.opened();val before=f.file(f.uri).readBytes()
        withContext(Dispatchers.Main) {assertTrue(entry.session.operate {it.update("n") {n->n.copy(text="must survive failed provider write")}})}
        f.denyWrite=true
        withContext(Dispatchers.Main) {f.workspace.persist()}
        f.await {!entry.session.saving && entry.session.recoverySafe}
        withContext(Dispatchers.Main) {
            assertTrue(entry.session.dirty);assertEquals("must survive failed provider write",entry.session.document!!.node("n").text)
        }
        assertArrayEquals(before,f.file(f.uri).readBytes())
        withContext(Dispatchers.Main) {requireNotNull(f.workspace.close(entry))}.join()
        val recovered=f.acquire();f.await {!recovered.session.loading}
        withContext(Dispatchers.Main) {
            assertFalse(entry.lease.alive);assertNotSame(entry.session,recovered.session)
            assertEquals("must survive failed provider write",recovered.session.document!!.node("n").text)
        }
        assertArrayEquals(before,f.file(f.uri).readBytes());assertEquals(0,f.deletes.get())
    }
}
