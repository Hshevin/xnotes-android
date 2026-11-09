package com.xnotes.pen

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.compose.ui.text.input.TextFieldValue
import com.xnotes.mindmap.*
import com.xnotes.platform.AppStorageDocumentsProvider
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real broker leases, provider writes and recovery files. Closing a presentation owns no other
 * lease's input, history, picker or autosave. No Compose/device execution is claimed here. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class MapSourceCloseHostTest {
    private class Fixture(val native:EditorPageCopyFixture) {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val maps=MapWorkspace(native.context,scope);val binding=Any()
        val originalProvider=AppStorageDocumentsProvider()
        val notes=AppStorageDocumentsProvider.rootDir(native.context)
        val folder=File(notes,"map-close-${UUID.randomUUID()}").apply {mkdirs()}
        val tree=AppStorageDocumentsProvider.treeUri(native.context)
        val bDoc=MindMapDocument(nodes=listOf(MapNode(id="br",parent=null),MapNode(id="bn",parent="br",text="B saved")))
        val source=MapSource(documentId=bDoc.id,fileName="B.xmap")
        val aDoc=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null),MapNode(id="n",parent="r",text="A saved",sources=listOf(source))))
        val cDoc=MindMapDocument(nodes=listOf(MapNode(id="cr",parent=null),MapNode(id="cn",parent="cr",text="C saved")))
        val a=File(folder,"A.xmap");val b=File(folder,"B.xmap");val c=File(folder,"C.xmap")
        val attempts=AtomicInteger();val successfulWrites=AtomicInteger()
        @Volatile var rejectWrites=false
        @Volatile var beforeWrite:((Uri)->Unit)?=null
        val release=CountDownLatch(1)
        val held=mutableListOf<MapDocumentLease>();val releases=mutableListOf<Job>()
        val requests=mutableListOf<Pair<MapSession,String>>()
        val entries=mutableListOf<MapWorkspace.Entry>()
        var callbacks=0
        lateinit var navigator:MapSourceNavigator
        lateinit var rootEntry:MapWorkspace.Entry
        fun uri(file:File)=DocumentsContract.buildDocumentUriUsingTree(tree,"root/"+file.relativeTo(notes).invariantSeparatorsPath)
        private fun file(uri:Uri)=File(notes,DocumentsContract.getDocumentId(uri).removePrefix("root/"))
        private val provider=object:ContentProvider() {
            override fun onCreate()=true
            override fun getType(uri:Uri)="application/octet-stream"
            override fun query(uri:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,sort:String?):Cursor {
                @Suppress("UNCHECKED_CAST") val columns=p as? Array<String>
                return originalProvider.queryDocument(DocumentsContract.getDocumentId(uri),columns)
            }
            override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
                if('w' in mode) {
                    attempts.incrementAndGet();beforeWrite?.invoke(uri)
                    if(rejectWrites) throw IOException("Controlled provider write failure")
                    successfulWrites.incrementAndGet()
                }
                return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.parseMode(mode))
            }
            override fun insert(uri:Uri,v:ContentValues?):Uri?=null
            override fun update(uri:Uri,v:ContentValues?,s:String?,a:Array<out String>?)=0
            override fun delete(uri:Uri,s:String?,a:Array<out String>?):Int=error("Closing must not delete a source")
        }
        suspend fun start() {
            originalProvider.attachInfo(native.context,ProviderInfo().apply {
                authority=tree.authority;exported=true;grantUriPermissions=true
                readPermission="android.permission.MANAGE_DOCUMENTS";writePermission=readPermission
            })
            provider.attachInfo(native.context,ProviderInfo().apply {authority=tree.authority})
            org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(requireNotNull(tree.authority),provider)
            withContext(Dispatchers.IO) {
                for((file,doc) in listOf(a to aDoc,b to bDoc,c to cDoc)) {
                    file.writeBytes(MapCodec.encode(doc))
                    native.storage.registry.registerMapObserved(doc.id,file.name,uri(file).toString())
                }
            }
            withContext(Dispatchers.Main) {
                maps.bindHost(binding);assertTrue(maps.open(uri(a),"A"))
                navigator=MapSourceNavigator(native.editor,maps,binding,scope,guard={_,_->MapSourceGuardResult.Ready(emptySet())})
            }
            await {!maps.opening && maps.visible}
            withContext(Dispatchers.Main) {rootEntry=maps.current!!;entries+=rootEntry}
        }
        suspend fun await(check:()->Boolean)=withTimeout(10000) {while(!withContext(Dispatchers.Main) {check()}) delay(1)}
        suspend fun acquire(file:File=a):MapDocumentLease=withContext(Dispatchers.Main) {
            MapDocuments.acquire(native.context,uri(file)).also(held::add)
        }.also {lease->await {!lease.session.loading}}
        fun edit(session:MapSession=rootEntry.session,text:String) {
            val id=if(session.document!!.id==bDoc.id) "bn" else "n"
            assertTrue(session.operate {it.update(id) {node->node.copy(text=text)}})
        }
        fun askClose(entry:MapWorkspace.Entry=maps.current!!,recovery:Boolean=false):Boolean=
            navigator.closeEntry(entry,preserveRecovery=recovery) {
                if(maps.current!==entry) false else {
                    callbacks++;navigator.retire()
                    maps.close(entry,preserveRecovery=recovery)?.also(releases::add)!=null
                }
            }
        fun openB():Boolean {
            val ticket=rootEntry.session.captureSourceNavigation("n",source.id)!!
            return navigator.open(rootEntry,ticket,rootEntry.session.captureSourceReturn(ticket)!!)
        }
        fun picker(session:MapSession=rootEntry.session)=session.beginExistingSource("n")!!.also {requests+=session to it}
        fun debounce(session:MapSession)=MapSession::class.java.getDeclaredField("debounce").apply {isAccessible=true}.get(session) as? Job
        fun history(session:MapSession)=MapSession::class.java.getDeclaredField("history").apply {isAccessible=true}.get(session)
        fun recoveryFiles()=File(native.context.noBackupFilesDir,"mind-map-recovery").let {dir->
            if(!dir.exists()) emptyMap() else dir.walkTopDown().filter {it.isFile}.associate {it.relativeTo(dir).path to it.readBytes().toList()}
        }
        suspend fun finish()=withContext(NonCancellable) {
            release.countDown();beforeWrite=null;rejectWrites=false
            val cleanups=withContext(Dispatchers.Main) {
                for((session,id) in requests) session.receiveExistingSource(id,null)
                for(session in (entries.map {it.session}+held.map {it.session}).distinct()) {
                    session.inkContact=false
                    session.textDraft?.let {session.cancelTextDraft(it.token)}
                    session.textRestore?.let {session.completeTextRestore(it.token)}
                    session.cancelPendingAutosave()
                }
                if(this@Fixture::navigator.isInitialized) navigator.dispose()
                (entries.map {it.session}+held.map {it.session}).distinct().filter {it.document!=null && it.dirty}
                    .map {it.saveAttempt()}
            }
            cleanups.forEach {it.await()}
            withContext(Dispatchers.Main) {maps.dispose()}.join()
            releases.forEach {it.join()}
            val jobs=withContext(Dispatchers.Main) {held.map {it.close()}}
            jobs.forEach {it.join()};scope.cancel();withContext(Dispatchers.IO) {folder.deleteRecursively()}
        }
    }
    private fun scenario(block:suspend(Fixture)->Unit)=runParkingScenario {native->
        val f=Fixture(native);try {f.start();block(f)} finally {f.finish()}
    }
    private enum class Shared {DIRTY,TEXT,INK,PICKER}
    private fun shared(mode:Shared)=scenario {f->
        val lease=f.acquire();val session=lease.session
        val picker=withContext(Dispatchers.Main) {
            assertSame(f.rootEntry.session,session);f.edit(text="Another owner's committed body")
            when(mode) {
                Shared.TEXT->{assertTrue(session.beginText("n",MindMapView.Viewport(1f,0f,0f),1000,700,1f));assertTrue(session.updateTextDraft(session.textDraft!!.token,TextFieldValue("Unconfirmed other-owner text")));null}
                Shared.INK->{session.inkContact=true;null}
                Shared.PICKER->f.picker(session)
                Shared.DIRTY->null
            }
        }
        val model=session.document;val epoch=session.interactionEpoch;val revision=session.contentRevision
        val history=f.history(session);val draft=session.textDraft;val debounce=f.debounce(session)!!
        withContext(Dispatchers.Main) {assertTrue(f.askClose())}
        f.await {!f.navigator.busy}
        withContext(Dispatchers.Main) {
            assertNull(f.maps.current);assertFalse(f.maps.visible);assertFalse(f.rootEntry.lease.alive);assertTrue(lease.alive)
            assertEquals(1,f.callbacks);assertSame(model,session.document);assertSame(history,f.history(session))
            assertEquals(epoch,session.interactionEpoch);assertEquals(revision,session.contentRevision)
            assertSame(draft,session.textDraft);assertFalse(session.closing);assertFalse(debounce.isCancelled)
            assertTrue(debounce.isActive)
            if(mode==Shared.INK) assertTrue(session.inkContact)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(400,TimeUnit.MILLISECONDS)
            if(mode==Shared.INK) {assertTrue(session.inkContact);assertEquals(0,f.successfulWrites.get());session.inkContact=false}
        }
        f.await {!session.saving && f.successfulWrites.get()>0}
        withContext(Dispatchers.Main) {
            assertEquals(epoch,session.interactionEpoch);assertSame(history,f.history(session));assertSame(draft,session.textDraft)
            if(picker!=null) assertTrue(session.receiveExistingSource(picker,null))
            assertFalse(session.closing)
        }
        assertEquals("Another owner's committed body",MapCodec.decode(f.a.readBytes()).node("n").text)
    }
    @Test fun rootCloseReleasesOnlyItsLeaseAndKeepsAnotherOwnersDirtyBodyAndAutosave()=shared(Shared.DIRTY)
    @Test fun rootCloseDoesNotRetireAnotherOwnersActualTextDraftOrHistoryEpoch()=shared(Shared.TEXT)
    @Test fun rootCloseDoesNotEndAnotherOwnersInkContactOrCancelItsPendingAutosave()=shared(Shared.INK)
    @Test fun rootCloseLeavesAnotherOwnersActualPickerRequestCurrent()=shared(Shared.PICKER)
    @Test fun aLastLeasePickerBlocksCloseWithoutHangingAndCanBeCancelledBeforeRetry()=scenario {f->
        val picker=withContext(Dispatchers.Main) {f.edit(text="Keep before picker");f.rootEntry.session.cancelPendingAutosave();f.picker()}
        withContext(Dispatchers.Main) {assertTrue(f.askClose())};f.await {!f.navigator.busy}
        withContext(Dispatchers.Main) {
            assertSame(f.rootEntry,f.maps.current);assertTrue(f.rootEntry.lease.alive);assertEquals(0,f.callbacks)
            assertTrue(f.rootEntry.session.receiveExistingSource(picker,null));assertTrue(f.askClose())
        }
        f.await {f.maps.current==null && !f.navigator.busy}
        assertEquals(1,f.callbacks);assertEquals("Keep before picker",MapCodec.decode(f.a.readBytes()).node("n").text)
    }
    @Test fun aLastLeaseSaveFailureKeepsItsCurrentEditableEntryAndRetriesTheLatestBody()=scenario {f->
        f.rejectWrites=true
        withContext(Dispatchers.Main) {f.edit(text="First failed close");f.rootEntry.session.cancelPendingAutosave();assertTrue(f.askClose())}
        f.await {!f.navigator.busy}
        withContext(Dispatchers.Main) {
            assertSame(f.rootEntry,f.maps.current);assertTrue(f.rootEntry.lease.alive);assertEquals(0,f.callbacks)
            assertTrue(f.rootEntry.session.dirty);assertFalse(f.rootEntry.session.closing)
            f.edit(text="Latest edit after the failed close");f.rootEntry.session.cancelPendingAutosave()
            f.rejectWrites=false;assertTrue(f.askClose())
        }
        f.await {f.maps.current==null && !f.navigator.busy}
        assertEquals(1,f.callbacks);assertEquals("Latest edit after the failed close",MapCodec.decode(f.a.readBytes()).node("n").text)
    }
    @Test fun rootRecoveryClosePerformsNoAdditionalWriteAndKeepsTheActualRecoveryBody()=scenario {f->
        f.rejectWrites=true
        withContext(Dispatchers.Main) {f.edit(text="Keep the recovery body");f.rootEntry.session.cancelPendingAutosave()}
        val saved=withContext(Dispatchers.Main) {f.rootEntry.session.saveAttempt()}.await()
        assertTrue(saved is MapSaveOutcome.Retained);assertTrue(f.rootEntry.session.recoverySafe)
        val attempts=f.attempts.get();val recovery=f.recoveryFiles();assertTrue(recovery.isNotEmpty())
        withContext(Dispatchers.Main) {assertTrue(f.askClose(recovery=true))}
        f.await {f.maps.current==null && !f.navigator.busy};f.releases.forEach {it.join()}
        assertEquals(attempts,f.attempts.get());assertEquals(recovery,f.recoveryFiles());assertEquals(1,f.callbacks)
        val reopened=f.acquire()
        withContext(Dispatchers.Main) {
            assertEquals("Keep the recovery body",reopened.session.document!!.node("n").text)
            assertTrue(reopened.session.dirty);assertTrue(reopened.session.recoverySafe)
        }
    }
    @Test fun childRecoveryCloseReturnsToTheParentAndKeepsBothLeasesAndTheFailedChildHistory()=scenario {f->
        withContext(Dispatchers.Main) {assertTrue(f.openB())};f.await {!f.navigator.busy}
        val child=withContext(Dispatchers.Main) {f.maps.current!!.also {assertEquals(f.bDoc.id,it.session.document!!.id);f.entries+=it}}
        f.rejectWrites=true
        withContext(Dispatchers.Main) {f.edit(child.session,"Unsaved child recovery");child.session.cancelPendingAutosave()}
        val saved=withContext(Dispatchers.Main) {child.session.saveAttempt()}.await()
        assertTrue(saved is MapSaveOutcome.Retained)
        val history=f.history(child.session);val revision=child.session.contentRevision;val attempts=f.attempts.get()
        withContext(Dispatchers.Main) {assertTrue(f.askClose(child,recovery=true))};f.await {!f.navigator.busy}
        withContext(Dispatchers.Main) {
            assertSame(f.rootEntry,f.maps.current);assertTrue(f.maps.visible);assertEquals(0,f.callbacks)
            assertTrue(f.rootEntry.lease.alive);assertTrue(child.lease.alive);assertSame(history,f.history(child.session))
            assertEquals(revision,child.session.contentRevision);assertTrue(child.session.dirty);assertTrue(child.session.canUndo)
            assertTrue(child.session.recoverySafe);assertFalse(child.session.closing);assertTrue(f.openB())
        }
        f.await {!f.navigator.busy}
        withContext(Dispatchers.Main) {assertSame(child,f.maps.current);assertEquals("Unsaved child recovery",child.session.document!!.node("bn").text)}
        assertEquals(attempts,f.attempts.get())
    }
    @Test fun aCloseWhoseSaveFinishesAfterAHostChangeAndNormalOpenCannotCloseTheNewEntry()=scenario {f->
        val entered=CompletableDeferred<Unit>()
        f.beforeWrite={uri->if(MapSaves.key(uri)==MapSaves.key(f.uri(f.a))) {entered.complete(Unit);check(f.release.await(5,TimeUnit.SECONDS))}}
        withContext(Dispatchers.Main) {f.edit(text="Accepted before close");f.rootEntry.session.cancelPendingAutosave();assertTrue(f.askClose())}
        entered.await()
        withContext(Dispatchers.Main) {f.maps.bindHost(Any());assertTrue(f.maps.open(f.uri(f.c),"C"))}
        f.await {!f.maps.opening && f.maps.current?.session?.document?.id==f.cDoc.id}
        val next=withContext(Dispatchers.Main) {f.maps.current!!.also(f.entries::add)}
        f.beforeWrite=null;f.release.countDown();f.await {!f.navigator.busy}
        withContext(Dispatchers.Main) {assertSame(next,f.maps.current);assertTrue(f.maps.visible);assertTrue(next.lease.alive);assertFalse(next.session.closing)}
        assertEquals(0,f.callbacks);assertEquals("C saved",MapCodec.decode(f.c.readBytes()).node("cn").text)
    }
}
