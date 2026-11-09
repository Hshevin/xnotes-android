package com.xnotes.mindmap

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.enableReferenceAnchors
import com.xnotes.core.model.fixedWriteSnapshot
import com.xnotes.format.DocumentCodec
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import java.io.File
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

/** Real source bytes and map actor. Slow reads and failed writes exercise the actual IO boundaries. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class SourceTargetEditControllerTest {
    private class Graphs(val file:File):ContentProvider() {
        var reject=true;var writes=0
        override fun onCreate()=true
        override fun getType(uri:Uri)="application/json"
        override fun query(uri:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,order:String?):Cursor=
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply {addRow(arrayOf("Graph.xmap"))}
        override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
            if('w' in mode) {writes++;if(reject) throw java.io.IOException("Before map publication")}
            return ParcelFileDescriptor.open(file,ParcelFileDescriptor.parseMode(mode))
        }
        override fun insert(uri:Uri,v:ContentValues?):Uri?=null
        override fun update(uri:Uri,v:ContentValues?,s:String?,a:Array<out String>?)=0
        override fun delete(uri:Uri,s:String?,a:Array<out String>?):Int=error("Target selection cannot delete")
    }
    private class Fixture(val native:MapBacklinkLibraryTest.Fixture) {
        val note=Document(mutableListOf(Page(500.0,400.0),Page(500.0,400.0)),dpi=72).apply {enableReferenceAnchors()}
        val codec=DocumentCodec(AndroidImageCodec(),AndroidTextMeasurer())
        val sourceFile=File(native.notes,"actual-source.xnote")
        val source=MapSource(documentId=note.identity.documentId,fileName="Source",scope=SourceScope.PAGE,
            pageId=note.pages.first().referenceIdentity.id,region=Rect(10.0,20.0,90.0,80.0),note="Keep latest remark")
        val graphFile=File(native.root,"target-edit.xmap")
        val provider=Graphs(graphFile)
        val authority="target-edit-${UUID.randomUUID()}"
        val uri=Uri.parse("content://$authority/document/graph")
        lateinit var session:MapSession
        val controller get()=session.sourceTargetEdits
        suspend fun start() {
            withContext(Dispatchers.IO) {
                sourceFile.outputStream().use {codec.write(note.fixedWriteSnapshot(),it)}
                native.storage.registry.register(note.identity.documentId,"xnote","Source",native.uri(sourceFile).toString(),true)
                graphFile.writeBytes(MapCodec.encode(MindMapDocument(nodes=listOf(MapNode(id="r",parent=null),
                    MapNode(id="c",parent="r",text="Body",sources=listOf(source),primarySource=source.id)))))
            }
            provider.attachInfo(native.context,ProviderInfo().apply {authority=this@Fixture.authority})
            org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(authority,provider)
            session=native.acquire(uri).session
        }
        suspend fun begin(owner:Any=Any(),mode:SourceTargetEditMode=SourceTargetEditMode.RANGE):Any {
            withContext(Dispatchers.Main) {
                val ticket=session.captureSourceTargetEdit("c",source.id,session.document!!.node("c").sources.single().target,mode)!!
                assertTrue(controller.start(owner,ticket))
            }
            if(mode==SourceTargetEditMode.RANGE) await {controller.state?.step==SourceTargetEditStep.VISUAL}
            return owner
        }
        fun actual()=session.document!!.node("c").sources.single()
        suspend fun await(check:()->Boolean)=withTimeout(10000) {while(!withContext(Dispatchers.Main) {check()}) delay(1)}
        suspend fun awaitImage()=withTimeout(10000) {
            while(!withContext(Dispatchers.Main) {
                // render's debounce uses Android Handler time, unlike this scenario's IO delays.
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(10,TimeUnit.MILLISECONDS)
                assertNotEquals("Actual source rendering failed",SourceTargetEditProblem.READ,controller.state?.problem)
                controller.image!=null
            }) delay(1)
        }
        suspend fun finish() {native.beforeOpenRead=null;withContext(Dispatchers.Main) {controller.close();session.cancelPendingAutosave()}}
    }
    // An external authority is intentional: final source snapshots must cross the provider's
    // beforeOpenRead barrier rather than AppStorage's direct OwnedRecoveryTarget file path.
    private fun scenario(block:suspend(Fixture)->Unit)=MapBacklinkLibraryTest().scenario(opaqueRoot=true) {native->
        val f=Fixture(native);try {f.start();block(f)} finally {f.finish()}
    }
    @Test fun aRetiredOwnersOsSlotStaysOccupiedUntilItsOwnResultAndCannotReplaceTheNextSource()=scenario {f->
        val owner=f.begin(mode=SourceTargetEditMode.REPLACE)
        withContext(Dispatchers.Main) {
            val slot=f.controller.capturePicker()!!
            f.controller.retireOwner(owner)
            assertNull(f.controller.state);assertTrue(f.controller.hasPending)
            assertNull(f.session.captureSourceTargetEdit("c",f.source.id,f.source.target,SourceTargetEditMode.REPLACE))
            assertTrue(f.controller.receivePicker(slot,f.native.uri(f.sourceFile)))
            assertFalse(SourceTargetEditController.deliverPicker(slot.id,f.native.uri(f.sourceFile)))
            assertFalse(f.controller.hasPending);assertEquals(f.source.target,f.actual().target)
            assertFalse(f.session.canUndo);assertEquals(0,f.provider.writes)
        }
    }
    @Test fun pageChangesRetireOldVisualCallbacksAndInvalidNewRangesNeverConfirmThePreviousRectangle()=scenario {f->
        f.begin()
        val oldFrame=withContext(Dispatchers.Main) {
            f.controller.state!!.frame!!.also {assertTrue(f.controller.attachVisualHost(Any(),it))}
        }
        withContext(Dispatchers.Main) {assertTrue(f.controller.choosePage(f.note.pages[1].referenceIdentity.id!!))}
        f.await {f.controller.state?.step==SourceTargetEditStep.VISUAL && f.controller.state?.frame!==oldFrame}
        withContext(Dispatchers.Main) {
            val current=f.controller.state!!;assertEquals(SourceRangeScope.WHOLE_PAGE,current.selectionScope);assertNull(current.region)
            assertFalse(f.controller.setRegion(oldFrame,Rect(2.0,3.0,20.0,30.0)))
            assertTrue(f.controller.chooseScope(SourceRangeScope.REGION))
        }
        f.await {f.controller.state?.step==SourceTargetEditStep.VISUAL && f.controller.state?.selectionScope==SourceRangeScope.REGION}
        withContext(Dispatchers.Main) {
            val frame=f.controller.state!!.frame!!
            assertTrue(f.controller.setRegion(frame,Rect(2.0,3.0,20.0,30.0)))
            assertFalse(f.controller.setRegion(frame,Rect(Double.NaN,0.0,20.0,30.0)))
            assertNull(f.controller.state!!.region);assertEquals(SourceTargetEditProblem.INVALID_RANGE,f.controller.state!!.problem)
            assertFalse(f.controller.confirm());assertEquals(f.source.target,f.actual().target);assertFalse(f.session.canUndo)
            f.controller.cancel()
        }
        assertEquals(0,f.provider.writes);assertEquals(0,f.native.writes.get())
    }
    @Test fun aPenBeginningDuringFinalIoWaitsThenRereadsWithoutForcingThePenOrApplyingTheOldChoice()=scenario {f->
        f.begin();val entered=CountDownLatch(1);val release=CountDownLatch(1);val reads=AtomicInteger()
        f.native.beforeOpenRead={if(reads.incrementAndGet()==1) {entered.countDown();check(release.await(5,TimeUnit.SECONDS))}}
        try {
            withContext(Dispatchers.Main) {
                assertTrue(f.controller.setRegion(f.controller.state!!.frame!!,Rect(40.0,50.0,100.0,110.0)))
                assertTrue(f.controller.confirm())
            }
            assertTrue(withContext(Dispatchers.IO) {entered.await(5,TimeUnit.SECONDS)})
            withContext(Dispatchers.Main) {f.session.inkContact=true};release.countDown()
            delay(20)
            withContext(Dispatchers.Main) {assertTrue(f.session.inkContact);assertEquals(f.source.target,f.actual().target);assertFalse(f.session.canUndo);f.session.inkContact=false}
            f.await {f.controller.state?.step==SourceTargetEditStep.SAVE_FAILED}
            assertTrue(reads.get()>=2)
            withContext(Dispatchers.Main) {assertEquals(Rect(40.0,50.0,100.0,110.0),f.actual().region)}
        } finally {release.countDown();f.native.beforeOpenRead=null;withContext(Dispatchers.Main) {f.session.inkContact=false}}
        assertEquals(0,f.native.writes.get())
    }
    @Test fun anAcceptedTargetSaveFailureRetriesOnlyTheCurrentActorAfterTheReadProofHasClosed()=scenario {f->
        f.begin();val next=Rect(40.0,50.0,100.0,110.0)
        withContext(Dispatchers.Main) {assertTrue(f.controller.setRegion(f.controller.state!!.frame!!,next));assertTrue(f.controller.confirm())}
        f.await {f.controller.state?.step==SourceTargetEditStep.SAVE_FAILED}
        val version=withContext(Dispatchers.Main) {
            assertEquals(next,f.actual().region);assertEquals(f.source.id,f.actual().id)
            assertTrue(f.controller.state!!.ticket.accepted);assertFalse(f.controller.confirm())
            f.provider.reject=false
            f.session.contentRevision.also {assertTrue(f.controller.retrySave())}
        }
        f.await {f.controller.state?.step==SourceTargetEditStep.SAVED}
        withContext(Dispatchers.Main) {
            assertEquals(version,f.session.contentRevision);assertEquals(next,f.actual().region)
            f.session.undo();assertEquals(f.source.target,f.actual().target)
            f.session.redo();assertEquals(next,f.actual().region)
            f.session.cancelPendingAutosave()
        }
        assertEquals(next,MapCodec.decode(f.graphFile.readBytes()).node("c").sources.single().region)
        assertEquals(0,f.native.writes.get())
    }
    @Test fun changingTheActualSourceDuringTheFinalReadCannotApplyARegionChosenOnItsOldBytes()=scenario {f->
        f.begin();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        f.native.beforeOpenRead={entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
        try {
            withContext(Dispatchers.Main) {assertTrue(f.controller.setRegion(f.controller.state!!.frame!!,Rect(40.0,50.0,100.0,110.0)));assertTrue(f.controller.confirm())}
            assertTrue(withContext(Dispatchers.IO) {entered.await(5,TimeUnit.SECONDS)})
            withContext(Dispatchers.IO) {f.note.pages.first().width=600.0;f.sourceFile.outputStream().use {f.codec.write(f.note.fixedWriteSnapshot(),it)}}
            f.native.beforeOpenRead=null;release.countDown()
            f.await {f.controller.state?.step==SourceTargetEditStep.CHANGED}
            withContext(Dispatchers.Main) {assertEquals(f.source.target,f.actual().target);assertFalse(f.session.canUndo)}
        } finally {release.countDown();f.native.beforeOpenRead=null}
        assertEquals(0,f.provider.writes);assertEquals(0,f.native.writes.get())
    }
    @Test fun aPendingOsPickerExcludesOtherPickerWorkUntilItsOriginalResultIsConsumed()=scenario {f->
        f.begin(mode=SourceTargetEditMode.REPLACE)
        withContext(Dispatchers.Main) {
            val slot=f.controller.capturePicker()!!
            assertFalse(f.controller.loadOriginals())
            assertFalse(f.controller.chooseAnotherSource())
            assertNull(f.controller.capturePicker())
            assertFalse(f.controller.canConfirm())
            assertTrue(f.controller.receivePicker(slot,null))
            assertNull(f.controller.pendingPicker)
            assertTrue(f.controller.loadOriginals())
        }
        f.await {f.controller.state?.step==SourceTargetEditStep.CHOOSE_SOURCE && f.controller.state?.originalPage!=null}
        assertEquals(0,f.provider.writes);assertEquals(0,f.native.writes.get())
    }
    @Test fun aLegalFixedRangeMayExtendBeyondMaxCoordinateAndItsWholeViewportStillRenders()=scenario {f->
        f.begin()
        val fixed=Rect(MindMapDocument.MAX_COORD,MindMapDocument.MAX_COORD,MindMapDocument.MAX_COORD,MindMapDocument.MAX_COORD)
        val host=Any()
        withContext(Dispatchers.Main) {
            val frame=f.controller.state!!.frame!!
            assertTrue(f.controller.setRegion(frame,fixed))
            assertTrue(f.controller.canConfirm())
            assertTrue(f.controller.attachVisualHost(host,frame))
            assertTrue(f.controller.render(fixed,host))
        }
        f.awaitImage()
        withContext(Dispatchers.Main) {
            assertEquals(fixed,f.controller.image!!.frame)
            assertEquals(fixed,f.controller.state!!.region)
            assertTrue(f.controller.canConfirm())
        }
        assertFalse(f.session.canUndo);assertEquals(0,f.native.writes.get())
    }
    @Test fun aReplacementVisualHostKeepsTheStableDraftAndCameraButRejectsTheOldHostsBusyAndImageCallbacks()=scenario {f->
        f.begin();val old=Any();val replacement=Any();val stable=SourceRegionCamera(250.0,200.0,0.75)
        val frame=withContext(Dispatchers.Main) {
            f.controller.state!!.frame!!.also {
                assertTrue(f.controller.attachVisualHost(old,it));assertTrue(f.controller.render(it.bounds,old,stable))
            }
        }
        f.awaitImage()
        withContext(Dispatchers.Main) {
            val image=f.controller.image!!
            assertTrue(f.controller.canConfirm())
            f.controller.setVisualBusy(old,frame,true);assertFalse(f.controller.canConfirm())
            f.controller.detachVisualHost(old)
            assertFalse(image.bitmap.isRecycled)
            assertNull(f.controller.image)
            assertEquals(stable,f.controller.state!!.camera)
            assertEquals(f.source.region,f.controller.state!!.region)
            assertTrue(f.controller.attachVisualHost(replacement,frame))
            f.controller.setVisualBusy(replacement,frame,true)
            f.controller.setVisualBusy(old,frame,false)
            assertTrue(f.controller.visualBusy)
            assertFalse(f.controller.visualCurrent(old,frame))
            assertFalse(f.controller.render(frame.bounds,old))
            f.controller.setVisualBusy(replacement,frame,false)
            assertTrue(f.controller.canConfirm())
            assertTrue(f.controller.render(frame.bounds,replacement))
        }
        f.awaitImage()
        assertFalse(f.session.canUndo);assertEquals(0,f.native.writes.get())
    }

    @Test fun aDelayedTargetEditClickCannotTakeOverAnAlreadyStartedAddSourcePicker()=scenario {f->
        withContext(Dispatchers.Main) {
            val ticket=f.session.captureSourceTargetEdit("c",f.source.id,f.source.target,SourceTargetEditMode.REPLACE)!!
            val existing=f.session.beginExistingSource("c")!!
            assertFalse(f.controller.start(Any(),ticket));assertNull(f.controller.state)
            assertTrue(f.session.receiveExistingSource(existing,null))
            assertTrue(f.controller.start(Any(),ticket))
            assertNull(f.session.beginExistingSource("c"))
            f.controller.cancel()
            assertFalse(f.session.canUndo)
        }
        assertEquals(0,f.provider.writes);assertEquals(0,f.native.writes.get())
    }

    @Test fun aLateOsResultAfterCancelOrCloseConsumesOnlyItsOriginalControllersSlotAcrossActualSessions()=scenario {f->
        for(closeOld in listOf(false,true)) {
            f.begin(mode=SourceTargetEditMode.REPLACE)
            val old=f.controller
            val oldSlot=withContext(Dispatchers.Main) {
                old.capturePicker()!!.also {if(closeOld) old.close() else assertTrue(old.cancel())}
            }
            val graph=withContext(Dispatchers.IO) {f.native.graph("next-session-${UUID.randomUUID()}.xmap",
                MindMapDocument(nodes=listOf(MapNode(id="r",parent=null),MapNode(id="c",parent="r",sources=listOf(f.source)))))}
            val next=f.native.acquire(f.native.uri(graph)).session
            val controller=withContext(Dispatchers.Main) {next.sourceTargetEdits}
            try {
                withContext(Dispatchers.Main) {
                    val ticket=next.captureSourceTargetEdit("c",f.source.id,f.source.target,SourceTargetEditMode.REPLACE)!!
                    assertTrue(controller.start(Any(),ticket))
                    val shown=controller.state!!;val slot=controller.capturePicker()!!
                    val reads=f.native.reads.get()
                    assertFalse(controller.receivePicker(oldSlot,f.native.uri(f.sourceFile)))
                    assertTrue(SourceTargetEditController.deliverPicker(oldSlot.id,f.native.uri(f.sourceFile)))
                    assertNull(old.pendingPicker);assertNull(old.state);assertFalse(old.hasPending)
                    assertSame(shown,controller.state);assertSame(slot,controller.pendingPicker)
                    assertEquals(reads,f.native.reads.get());assertEquals(f.source.target,next.document!!.node("c").sources.single().target)
                    assertFalse(next.canUndo);assertFalse(f.session.canUndo)
                    assertFalse(SourceTargetEditController.deliverPicker(oldSlot.id,f.native.uri(f.sourceFile)))
                    assertTrue(SourceTargetEditController.deliverPicker(slot.id,null))
                    assertSame(shown,controller.state);assertNull(controller.pendingPicker)
                }
            } finally {withContext(Dispatchers.Main) {controller.close();next.cancelPendingAutosave()}}
        }
        assertEquals(0,f.provider.writes);assertEquals(0,f.native.writes.get())
    }

}
