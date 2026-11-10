package com.xnotes.pen

import android.net.Uri
import android.view.MotionEvent
import androidx.compose.ui.text.input.TextFieldValue
import com.xnotes.core.geometry.Pt
import com.xnotes.core.tools.Tool
import com.xnotes.mindmap.*
import com.xnotes.ui.Editor
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The actual Main close route: source navigator -> network returnEntry -> coordinator -> workspace.
 * A close-only shared-owner allowance is rechecked after native parking, never reused for hide. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class MapSourceNetworkCloseHostTest {
    private class Fixture(val files:MapBacklinkLibraryTest.Fixture) {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val maps=MapWorkspace(files.context,scope);val networks=FileNetworkWorkspace(files.context,scope)
        val binding=Any()
        lateinit var editor:Editor
        lateinit var navigator:MapSourceNavigator
        lateinit var coordinator:LocalNetworkCoordinator
        lateinit var network:FileNetworkWorkspace.Entry
        lateinit var entry:MapWorkspace.Entry
        lateinit var other:MapDocumentLease
        val fixed=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null),MapNode(id="n",parent="r",text="Saved")))
        val file=File(files.notes,"network-close-${UUID.randomUUID()}.xmap")
        val uri get()=files.uri(file)
        var picker:String?=null
        suspend fun start() {
            withContext(Dispatchers.IO) {
                file.writeBytes(MapCodec.encode(fixed))
                files.storage.registry.registerMapObserved(fixed.id,file.name,uri.toString())
            }
            withContext(Dispatchers.Main) {
                editor=Editor(files.context).also(files.editors::add)
                maps.bindHost(binding);networks.bindHost(binding)
                network=networks.admit(binding,NetworkQuery.library("Network",listOf(files.tree.toString())))!!
                coordinator=LocalNetworkCoordinator(editor,maps,networks,binding,scope)
                navigator=MapSourceNavigator(editor,maps,binding,scope,guard={_,_->MapSourceGuardResult.Ready(emptySet())})
                assertTrue(maps.open(uri,"Map from network"))
            }
            await {!maps.opening && maps.visible}
            withContext(Dispatchers.Main) {
                entry=maps.current!!;assertTrue(networks.parkForMap(binding,network,entry))
                other=MapDocuments.acquire(files.context,uri);assertSame(entry.session,other.session)
            }
        }
        suspend fun await(check:()->Boolean)=withTimeout(10000) {while(!withContext(Dispatchers.Main) {check()}) delay(1)}
        fun history()=MapSession::class.java.getDeclaredField("history").apply {isAccessible=true}.get(entry.session)
        fun debounce()=MapSession::class.java.getDeclaredField("debounce").apply {isAccessible=true}.get(entry.session) as Job
        fun seed(text:Boolean) {
            val s=entry.session
            assertTrue(s.operate {it.update("n") {n->n.copy(text="Committed other-owner body")}})
            if(text) {
                assertTrue(s.beginText("n",MindMapView.Viewport(1f,0f,0f),1000,700,1f))
                assertTrue(s.updateTextDraft(s.textDraft!!.token,TextFieldValue("Still typing in another owner")))
            } else s.inkContact=true
        }
        fun close(recovery:Boolean):Boolean=navigator.closeEntry(entry,preserveRecovery=recovery) {
            val back=networks.returnEntry(entry)
            if(back!==network) false else {
                navigator.retire()
                coordinator.resume(back,entry,closeMap=true,preserveMapRecovery=recovery)
            }
        }
        fun nativeDraft() {
            editor.newNote();editor.view.layout(0,0,1000,1000);editor.selectTool(Tool.TEXT_BOX)
            parkingPen(editor,MotionEvent.ACTION_DOWN,Pt(100.0,100.0));parkingPen(editor,MotionEvent.ACTION_UP,Pt(100.0,100.0))
            editor.updateEditingText("Pending hidden native draft")
            assertNotNull(editor.controller.activeTextItem());assertNotNull(editor.editingField)
        }
        suspend fun finish()=withContext(NonCancellable) {
            withContext(Dispatchers.Main) {
                if(this@Fixture::navigator.isInitialized) navigator.dispose()
                if(this@Fixture::coordinator.isInitialized) coordinator.dispose()
                if(this@Fixture::entry.isInitialized) {
                    val s=entry.session;s.inkContact=false
                    s.textDraft?.let {s.cancelTextDraft(it.token)};s.textRestore?.let {s.completeTextRestore(it.token)}
                    picker?.let {s.receiveExistingSource(it,null)};s.cancelPendingAutosave()
                }
                if(this@Fixture::editor.isInitialized) {editor.commitText();editor.awaitHostParking()}
                networks.dispose()
            }
            withContext(Dispatchers.Main) {maps.dispose()}.join()
            if(this@Fixture::other.isInitialized) withContext(Dispatchers.Main) {other.close()}.join()
            scope.cancel()
        }
    }
    private fun scenario(test:suspend(Fixture)->Unit)=MapBacklinkLibraryTest().scenario {files->
        val f=Fixture(files);try {f.start();test(f)} finally {f.finish()}
    }
    private fun shared(text:Boolean,recovery:Boolean)=scenario {f->
        withContext(Dispatchers.Main) {f.seed(text)}
        val s=f.entry.session;val model=s.document;val epoch=s.interactionEpoch;val revision=s.contentRevision
        val draft=s.textDraft;val history=f.history();val auto=f.debounce()
        withContext(Dispatchers.Main) {
            assertSame(f.network,f.networks.returnEntry(f.entry));assertTrue(f.entry.lease.hasOtherOwners)
            assertFalse(f.maps.canHide(f.entry));assertTrue(f.close(recovery))
        }
        f.await {!f.navigator.busy && !f.coordinator.busy}
        withContext(Dispatchers.Main) {
            assertTrue(f.networks.visible);assertSame(f.network,f.networks.current)
            assertNull(f.maps.current);assertFalse(f.maps.visible);assertFalse(f.entry.lease.alive);assertTrue(f.other.alive)
            assertSame(model,s.document);assertSame(history,f.history());assertEquals(epoch,s.interactionEpoch);assertEquals(revision,s.contentRevision)
            assertSame(draft,s.textDraft);assertFalse(s.closing);assertFalse(auto.isCancelled);assertTrue(auto.isActive)
            if(!text) assertTrue(s.inkContact)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(400,TimeUnit.MILLISECONDS)
            if(!text) {assertTrue(s.inkContact);s.inkContact=false}
        }
        f.await {!s.saving && f.files.writes.get()>0}
        withContext(Dispatchers.Main) {assertEquals(epoch,s.interactionEpoch);assertSame(draft,s.textDraft);assertSame(history,f.history());assertFalse(s.closing)}
        assertEquals("Committed other-owner body",MapCodec.decode(f.file.readBytes()).node("n").text)
    }
    @Test fun ordinaryRootCloseReturnsToTheSameNetworkWithoutRetiringAnotherOwnersTextOrAutosave()=shared(text=true,recovery=false)
    @Test fun ordinaryRootCloseReturnsToTheSameNetworkWithoutEndingAnotherOwnersInk()=shared(text=false,recovery=false)
    @Test fun recoveryRootCloseReturnsToTheSameNetworkWithoutRetiringAnotherOwnersTextOrAutosave()=shared(text=true,recovery=true)
    @Test fun recoveryRootCloseReturnsToTheSameNetworkWithoutEndingAnotherOwnersInk()=shared(text=false,recovery=true)
    @Test fun networkCloseWaitsForTheHiddenNativeDraftWithoutCommittingIt()=scenario {f->
        withContext(Dispatchers.Main) {f.seed(text=true);f.nativeDraft()}
        val nativeModel=f.editor.state.document;val undo=f.editor.history.nextUndo;val revision=nativeModel.bodyRevision
        val draft=f.entry.session.textDraft;val epoch=f.entry.session.interactionEpoch
        withContext(Dispatchers.Main) {assertTrue(f.close(recovery=false))}
        parkingDispatch()
        withContext(Dispatchers.Main) {
            assertTrue(f.coordinator.busy);assertFalse(f.networks.visible);assertSame(f.entry,f.maps.current)
            assertNotNull(f.editor.controller.activeTextItem());assertNotNull(f.editor.editingField)
            assertEquals(revision,nativeModel.bodyRevision);assertSame(undo,f.editor.history.nextUndo)
            assertSame(draft,f.entry.session.textDraft);assertEquals(epoch,f.entry.session.interactionEpoch)
            f.editor.commitText()
        }
        f.await {!f.coordinator.busy && f.networks.visible}
        withContext(Dispatchers.Main) {assertSame(f.network,f.networks.current);assertTrue(f.other.alive);assertSame(draft,f.entry.session.textDraft)}
    }
    @Test fun aSharedCloseAllowanceExpiresIfThatOtherLeaseRetiresDuringNativeParking()=scenario {f->
        withContext(Dispatchers.Main) {f.seed(text=true);f.nativeDraft();assertTrue(f.close(recovery=true))}
        parkingDispatch()
        withContext(Dispatchers.Main) {assertTrue(f.coordinator.busy);assertTrue(f.entry.lease.hasOtherOwners)}
        withContext(Dispatchers.Main) {f.other.close()}.join()
        f.await {!f.coordinator.busy}
        withContext(Dispatchers.Main) {
            assertFalse(f.entry.lease.hasOtherOwners);assertTrue(f.entry.lease.alive)
            assertSame(f.entry,f.maps.current);assertTrue(f.maps.visible);assertFalse(f.networks.visible)
            assertNotNull(f.entry.session.textDraft);assertNotNull(f.editor.controller.activeTextItem())
            f.editor.commitText()
        }
        parkingDispatch()
        withContext(Dispatchers.Main) {assertSame(f.entry,f.maps.current);assertFalse(f.networks.visible)}
    }
}
