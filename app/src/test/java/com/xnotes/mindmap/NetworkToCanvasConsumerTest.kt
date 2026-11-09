package com.xnotes.mindmap

import android.app.Activity
import android.content.Intent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual map actors, provider IO, history and retained creation; source files are never read. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class NetworkToCanvasConsumerTest {
    private fun scenario(block:suspend(MapBacklinkLibraryTest.Fixture)->Unit)=MapBacklinkLibraryTest().scenario(block=block)
    private suspend fun <T> main(action:()->T):T=withContext(Dispatchers.Main) {action()}
    private suspend fun await(condition:()->Boolean) {withTimeout(15000) {while(!main(condition)) delay(1)}}
    private fun selection()=NetworkCanvasSelection(listOf(
        MapFileCardSeed(UUID.randomUUID().toString(),"First.xnote",140.0,30.0),
        MapFileCardSeed(UUID.randomUUID().toString(),"Second.xcanvas",420.0,160.0))) {true}
    private fun canvas()=MindMapDocument(nodes=emptyList(),organization=MapOrganization.RELATION_CANVAS)

    @Test fun choosingAndCancelingDoesNotAcquireOrAutosaveAZeroLeaseDirtyDestination()=scenario {f->
        val file=f.graph("cancel-target.xmap",canvas())
        val uri=f.uri(file);val held=f.acquire(uri);val session=held.session
        main {
            assertTrue(session.commit(session.document!!.copy(nodes=listOf(MapNode(parent=null,free=true,text="Unconfirmed destination draft")))))
            session.cancelPendingAutosave();session.inkContact=true
        }
        main {held.close()}.join()
        main {session.inkContact=false;assertTrue(session.dirty)}
        val before=file.readBytes();val reads=f.reads.get();val writes=f.writes.get()
        val history=main {session.document to (session.canUndo to session.canRedo)}
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val controller=main {NetworkToCanvasController(f.context,selection(),scope)}
        try {
            main {assertTrue(controller.chooseExisting(uri,"Cancel target"));assertNull(controller.targetSession)
                assertEquals(NetworkCanvasStep.CONFIRM,controller.step)}
            main {controller.close()}?.join()
            assertEquals(reads,f.reads.get());assertEquals(writes,f.writes.get());assertArrayEquals(before,file.readBytes())
            main {assertTrue(session.dirty);assertSame(history.first,session.document)
                assertEquals(history.second,session.canUndo to session.canRedo)
                assertEquals("Unconfirmed destination draft",session.document!!.nodes.single().text)}
        } finally {
            main {controller.close()}?.join();scope.cancel()
            val cleanup=f.acquire(uri)
            main {cleanup.session.useFileVersion()}
            await {!cleanup.session.replacingDocument && !cleanup.session.saving && !cleanup.session.dirty && cleanup.session.document?.nodes?.isEmpty()==true}
            assertArrayEquals(before,file.readBytes())
        }
    }

    @Test fun anExistingActorKeepsItsLateBodyAndAddsTheWholeBatchInOneUndoBeforeSaving()=scenario {f->
        val original=canvas().copy(nodes=listOf(MapNode(id="body",parent=null,free=true,text="Original body")))
        val file=f.graph("existing-target.xmap",original);val uri=f.uri(file);val held=f.acquire(uri)
        val selected=selection();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val controller=main {NetworkToCanvasController(f.context,selected,scope)}
        try {
            main {assertTrue(controller.chooseExisting(uri,"Existing target"));assertNull(controller.targetSession)
                assertTrue(held.session.commit(held.session.document!!.update("body") {it.copy(text="Later body")}
                    ));held.session.cancelPendingAutosave();assertTrue(controller.confirm())}
            await {controller.step==NetworkCanvasStep.ADDED}
            val ids=main {controller.nodeIds.toSet()}
            main {
                assertSame(held.session,controller.targetSession);assertEquals(2,ids.size)
                assertEquals("Later body",held.session.document!!.node("body").text)
                assertEquals(selected.seeds.map {it.documentId}.toSet(),held.session.document!!.nodes.mapNotNull {it.fileCardId}.toSet())
                held.session.undo();held.session.cancelPendingAutosave()
                assertEquals("Later body",held.session.document!!.node("body").text)
                assertTrue(held.session.document!!.nodes.none {it.fileCardId!=null})
                held.session.redo();held.session.cancelPendingAutosave()
                assertEquals(ids,held.session.document!!.nodes.filter {it.fileCardId!=null}.map {it.id}.toSet())
            }
            val stored=MapCodec.decode(file.readBytes())
            assertEquals("Later body",stored.node("body").text);assertEquals(ids,stored.nodes.filter {it.fileCardId!=null}.map {it.id}.toSet())
        } finally {main {controller.close()}?.join();scope.cancel()}
    }

    @Test fun newCreationWaitsForConfirmationAndARetainedWrittenCandidateContinuesWithoutAnotherWrite()=scenario {f->
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val selected=selection();val file=File(f.notes,"new-target.xmap").apply {writeBytes(byteArrayOf())};val uri=f.uri(file)
        val canceled=main {NetworkToCanvasController(f.context,selected,scope)}
        main {assertTrue(canceled.chooseNew(uri));assertNull(canceled.targetSession)}
        main {canceled.close()}?.join()
        assertEquals(0,f.writes.get());assertEquals(0,file.length())
        val controller=main {NetworkToCanvasController(f.context,selected,scope)}
        try {
            f.beforeOpenRead={if(f.writes.get()>0) throw IOException("Readback unavailable after the actual write")}
            main {assertTrue(controller.chooseNew(uri));assertTrue(controller.confirm())}
            await {controller.step==NetworkCanvasStep.CREATE_RETAINED}
            assertEquals(1,f.writes.get());val written=MapCodec.decode(file.readBytes())
            assertEquals(MapOrganization.RELATION_CANVAS,written.organization);assertNull(written.centralRoot)
            assertEquals(selected.seeds.map {it.documentId}.toSet(),written.nodes.mapNotNull {it.fileCardId}.toSet())
            f.beforeOpenRead=null
            main {assertTrue(controller.openRetained())}
            await {controller.step==NetworkCanvasStep.ADDED}
            main {assertEquals(written.id,controller.targetSession!!.document!!.id)
                assertFalse(controller.targetSession!!.canUndo);assertTrue(controller.canView)}
            assertEquals("Continuing a fully written candidate must only finish its checkpoint",1,f.writes.get())
        } finally {f.beforeOpenRead=null;main {controller.close()}?.join();scope.cancel()}
    }

    @Test fun theSystemCreateResultPublishesItsPreparedCandidateWithoutAnExtraConfirmationAndCancelOrOldNonceWritesNothing()=scenario {f->
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val selected=selection();val file=File(f.notes,"system-save.xmap").apply {writeBytes(byteArrayOf())};val uri=f.uri(file)
        val controller=main {NetworkToCanvasController(f.context,selected,scope)}
        val codes=mutableListOf<Int>()
        val registry=object:ActivityResultRegistry() {
            override fun <I,O> onLaunch(requestCode:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?) {
                codes+=requestCode
                assertEquals(Intent.ACTION_CREATE_DOCUMENT,contract.createIntent(f.context,input).action)
            }
        }
        val picker=main {NetworkCanvasPicker(registry)}
        try {
            val canceled=main {controller.prepareNew()!!}
            main {assertTrue(picker.launch(true,"Canvas.xmap") {result->
                if(result==null) controller.cancelNew(canceled) else controller.completeNew(canceled,result)
            });assertTrue(registry.dispatchResult(codes.last(),Activity.RESULT_CANCELED,null))}
            main {assertEquals(NetworkCanvasStep.DESTINATION,controller.step);assertNull(controller.targetUri);assertNull(controller.targetSession)}
            assertEquals(0,f.reads.get());assertEquals(0,f.writes.get());assertEquals(0L,file.length())
            val stale=main {controller.prepareNew()!!}
            main {assertTrue(picker.launch(true,"Old.xmap") {result->if(result!=null) assertFalse(controller.completeNew(stale,result))})}
            main {controller.cancelNew(stale)}
            val current=main {controller.prepareNew()!!}
            main {assertTrue(registry.dispatchResult(codes.last(),Activity.RESULT_OK,Intent().setData(uri)))}
            assertEquals(0,f.reads.get());assertEquals(0,f.writes.get());assertEquals(0L,file.length())
            main {assertTrue(picker.launch(true,"Canvas.xmap") {result->
                if(result!=null) assertTrue(controller.completeNew(current,result)) else controller.cancelNew(current)
            });assertTrue(registry.dispatchResult(codes.last(),Activity.RESULT_OK,Intent().setData(uri)))
                assertNotEquals("System Save is the final confirmation",NetworkCanvasStep.CONFIRM,controller.step)}
            await {controller.step==NetworkCanvasStep.ADDED}
            val stored=MapCodec.decode(file.readBytes())
            assertEquals(current.candidate.document.id,stored.id);assertNotEquals(stale.candidate.document.id,stored.id)
            assertEquals(current.candidate.nodeIds,stored.nodes.map {it.id}.toSet());assertEquals(1,f.writes.get())
        } finally {main {picker.dispose();controller.close()}?.join();scope.cancel()}
    }
}
