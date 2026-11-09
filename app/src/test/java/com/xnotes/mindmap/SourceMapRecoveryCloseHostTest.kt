package com.xnotes.mindmap

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual failed publication, durable recovery, route lease release and another owner's draft. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class SourceMapRecoveryCloseHostTest {
    private class Fixture(val base:MapBacklinkLibraryTest.Fixture) {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val workspace=MapWorkspace(base.context,scope)
        val document=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,text="A")))
        val file=File(base.root,"recovery-close.xmap").apply {writeBytes(MapCodec.encode(document))}
        val original=file.readBytes()
        val authority="map-recovery-close-${UUID.randomUUID()}"
        val uri=Uri.parse("content://$authority/document/graph")
        var reject=true;var reads=0;var writes=0
        val provider=object:ContentProvider() {
            override fun onCreate()=true
            override fun getType(uri:Uri)="application/json"
            override fun query(uri:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,order:String?):Cursor=
                MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply {addRow(arrayOf(file.name))}
            override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
                if('w' in mode) {writes++;if(reject) throw IOException("Rejected actual map publication")}
                else reads++
                return ParcelFileDescriptor.open(file,ParcelFileDescriptor.parseMode(mode))
            }
            override fun insert(uri:Uri,v:ContentValues?):Uri?=null
            override fun update(uri:Uri,v:ContentValues?,s:String?,a:Array<out String>?)=0
            override fun delete(uri:Uri,s:String?,a:Array<out String>?):Int=error("Recovery close cannot delete")
        }
        lateinit var entry:MapWorkspace.Entry
        val session get()=entry.session
        val extra=mutableListOf<MapDocumentLease>()
        init {
            provider.attachInfo(base.context,ProviderInfo().apply {authority=this@Fixture.authority})
            org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(authority,provider)
        }
        suspend fun start() {
            withContext(Dispatchers.Main) {assertTrue(workspace.open(uri,"Recovery close"))}
            withTimeout(10000) {while(withContext(Dispatchers.Main) {workspace.opening}) delay(1)}
            withContext(Dispatchers.Main) {entry=workspace.current!!}
        }
        suspend fun retainB()=withContext(Dispatchers.Main) {
            assertTrue(session.operate {it.update("r") {node->node.copy(text="Recoverable B")}})
            session.cancelPendingAutosave()
            assertTrue(session.saveAttempt().await() is MapSaveOutcome.Retained)
            assertTrue(session.recoverySafe);assertTrue(session.dirty);assertTrue(session.canUndo);assertEquals(1,writes)
        }
        fun pending()=MapSession::class.java.getDeclaredField("debounce").apply {isAccessible=true}.get(session) as? Job
        suspend fun acquire()=withContext(Dispatchers.Main) {MapDocuments.acquire(base.context,uri).also(extra::add)}
        suspend fun finish()=withContext(NonCancellable) {
            if(this@Fixture::entry.isInitialized) {
                val cleanup=acquire();val managed=cleanup.session
                withContext(Dispatchers.Main) {
                    managed.textDraft?.let {managed.cancelTextDraft(it.token)}
                    managed.textRestore?.let {managed.completeTextRestore(it.token)}
                    managed.cancelPendingAutosave();reject=false
                    // Explicit test cleanup publication, after all no-extra-write assertions.
                    if(managed.dirty) assertTrue(managed.saveAttempt().await() is MapSaveOutcome.Written)
                }
                withContext(Dispatchers.Main) {workspace.dispose()}.join()
                val jobs=withContext(Dispatchers.Main) {(extra+cleanup).distinct().map {it.close()}}
                jobs.forEach {it.join()}
            } else withContext(Dispatchers.Main) {workspace.dispose()}.join()
            scope.cancel()
        }
    }
    private fun scenario(block:suspend(Fixture)->Unit)=MapBacklinkLibraryTest().scenario {base->
        val f=Fixture(base);try {f.start();block(f)} finally {f.finish()}
    }
    @Test fun retainingTheLatestRecoveryOnTheLastRouteLeaseClosesWithoutAnotherSaveAndKeepsTheManagedActor()=scenario {f->
        f.retainB()
        val body=withContext(Dispatchers.Main) {f.session.document}
        val journal=f.base.journalSnapshot();val reads=f.reads;val writes=f.writes
        val closed=withContext(Dispatchers.Main) {assertFalse(f.entry.lease.hasOtherOwners);f.workspace.close(f.entry,preserveRecovery=true)!!}
        closed.join()
        withContext(Dispatchers.Main) {
            assertNull(f.workspace.current);assertFalse(f.workspace.visible);assertFalse(f.entry.lease.alive)
            val retained=MapDocuments.observations(f.base.context).single {it.session===f.session}
            assertSame(body,retained.document);assertTrue(f.session.dirty);assertTrue(f.session.recoverySafe);assertTrue(f.session.canUndo)
        }
        assertEquals(reads,f.reads);assertEquals(writes,f.writes);assertArrayEquals(f.original,f.file.readBytes())
        assertEquals(journal,f.base.journalSnapshot())
    }
    @Test fun aSharedRecoveryCloseDoesNotCancelAnotherOwnersAutosaveDraftHistoryOrEpoch()=scenario {f->
        f.retainB();val external=f.acquire()
        val expected=withContext(Dispatchers.Main) {
            assertTrue(f.session.operate {it.update("r") {node->node.copy(text="Later C owned elsewhere")}})
            assertTrue(f.session.beginText("r",MindMapView.Viewport(1f,0f,0f),1000,700))
            Triple(f.session.textDraft!!,f.session.interactionEpoch,f.pending()!!)
        }
        val body=f.session.document;val reads=f.reads;val writes=f.writes
        val closed=withContext(Dispatchers.Main) {assertTrue(f.entry.lease.hasOtherOwners);f.workspace.close(f.entry,preserveRecovery=true)!!}
        closed.join()
        withContext(Dispatchers.Main) {
            assertTrue(external.alive);assertSame(body,f.session.document);assertTrue(f.session.canUndo)
            assertSame(expected.first,f.session.textDraft);assertEquals(expected.second,f.session.interactionEpoch)
            assertSame(expected.third,f.pending());assertFalse(expected.third.isCancelled)
        }
        assertEquals(reads,f.reads);assertEquals(writes,f.writes);assertArrayEquals(f.original,f.file.readBytes())
    }
    @Test fun anOlderRecoveryDoesNotAllowTheLastRouteLeaseToCloseNewerUnpublishedContent()=scenario {f->
        f.retainB()
        withContext(Dispatchers.Main) {
            assertTrue(f.session.operate {it.update("r") {node->node.copy(text="Unprotected C")}})
            val pending=f.pending()!!;assertFalse(f.session.recoverySafe)
            assertNull(f.workspace.close(f.entry,preserveRecovery=true))
            assertSame(f.entry,f.workspace.current);assertTrue(f.entry.lease.alive);assertTrue(f.workspace.visible)
            assertSame(pending,f.pending());assertFalse(pending.isCancelled)
            assertEquals("Unprotected C",f.session.document!!.root.text)
        }
        assertEquals(1,f.writes);assertArrayEquals(f.original,f.file.readBytes())
    }
}
