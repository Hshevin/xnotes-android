package com.xnotes.pen

import android.provider.DocumentsContract
import android.view.MotionEvent
import android.view.KeyEvent
import com.xnotes.core.geometry.Pt
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.text.FlowPos
import com.xnotes.core.text.FlowRange
import com.xnotes.core.text.Paragraph
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.format.CanvasCodec
import com.xnotes.format.BundleReferenceInfo
import com.xnotes.core.model.fixedWriteSnapshot
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.ui.Editor
import com.xnotes.ui.Pane
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual provider bytes and native owners; preparation must not publish one side early. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class PreparedPagedPairOpenHostTest {
    @Test fun aFreshPairCandidateKeepsBothOldModelsUntilItsSynchronousCommit()=runParkingScenario {f->
        val secondary=openParkingPanes(f)
        val uri=DocumentsContract.buildDocumentUri(f.authority,"retry").toString()
        val target=withContext(Dispatchers.IO) {f.seed(f.retry);BundleReferenceInfo.inspectStrict(f.retry)}
        withContext(Dispatchers.Main) {
            val old=f.editor.state.document;val other=secondary.state.document
            var guarded:List<Editor>?=null
            val cap=f.editor.preparePagedPairOpen(uri,"retry.xnote",Pane.SECONDARY,guardOwners={owners,current->
                assertTrue(current());guarded=owners;emptySet()
            })
            assertNotNull("An actual NOTE must produce a staged pair candidate",cap)
            try {
                assertSame(secondary,cap!!.target);assertEquals(Pane.SECONDARY,cap.nativePane)
                assertEquals(setOf(f.editor,secondary),guarded!!.toSet())
                assertSame(old,f.editor.state.document);assertSame(other,secondary.state.document)
                assertTrue(f.editor.noteOpen && secondary.noteOpen);assertTrue(cap.current())
                assertTrue(cap.commit());assertFalse(cap.current());assertFalse(cap.commit())
                assertFalse(f.editor.noteOpen);assertTrue(secondary.noteOpen)
                assertEquals(target.header.identity!!.documentId,secondary.state.document.identity.documentId)
            } finally {cap?.close()}
        }
        assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }

    @Test fun aRetainedDirtyOwnerKeepsItsActualModelHistoryAndInputTokenAfterPairCommit()=runParkingScenario {f->
        val secondary=openParkingPanes(f)
        withContext(Dispatchers.Main) {
            f.addStroke(150.0,recordHistory=true)
            val model=f.editor.state.document;val history=f.editor.history;val undo=history.nextUndo
            val token=f.editor.penInputOwnerToken
            val cap=f.editor.preparePagedPairOpen(f.sourceUri,"source.xnote",Pane.SECONDARY,guardOwners={owners,current->
                assertTrue(current());assertEquals(listOf(secondary),owners);emptySet()
            })
            assertNotNull("The already-open actual owner must be reusable by a pair",cap)
            try {
                assertSame(f.editor,cap!!.target);assertTrue(cap.current());assertTrue(cap.commit())
                assertEquals(listOf(secondary to f.editor),cap.keyHandoffs)
                assertSame(model,f.editor.state.document);assertSame(history,f.editor.history);assertSame(undo,history.nextUndo)
                assertTrue(model.dirty);assertSame(token,f.editor.penInputOwnerToken)
                assertTrue(f.editor.noteOpen);assertFalse(secondary.noteOpen)
            } finally {cap?.close()}
        }
        assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }

    @Test fun aFailedNativeInitialReadDoesNotGuardOrConfirmAnOutgoingTextDraft()=runParkingScenario {f->
        openParkingPanes(f)
        val uri=DocumentsContract.buildDocumentUri(f.authority,"retry").toString()
        withContext(Dispatchers.IO) {f.retry.writeText("not a native NOTE")}
        withContext(Dispatchers.Main) {
            f.editor.focusPane(Pane.PRIMARY);f.editor.selectTool(Tool.TEXT_BOX)
            parkingPen(f.editor,MotionEvent.ACTION_DOWN,Pt(220.0,220.0))
            parkingPen(f.editor,MotionEvent.ACTION_UP,Pt(220.0,220.0))
            f.editor.updateEditingText("Keep this old draft")
            val model=f.editor.state.document;val revision=model.bodyRevision;val draft=f.editor.controller.activeTextItem()
            var guarded=0
            assertNull(f.editor.preparePagedPairOpen(uri,"retry.xnote",guardOwners={_,_->guarded++;emptySet()}))
            assertEquals(0,guarded);assertSame(model,f.editor.state.document);assertEquals(revision,model.bodyRevision)
            assertSame(draft,f.editor.controller.activeTextItem());assertNotNull(f.editor.editingField)
            f.editor.commitText()
        }
    }

    @Test fun retainedTextAndFlowWaitWithoutBlurAndCancellingKeepsTheActualDraft() {
        for(flow in listOf(false,true)) runParkingScenario {f->
            openParkingPanes(f)
            withContext(Dispatchers.Main) {
                if(flow) {
                    f.editor.state.document.flow.paragraphs+=Paragraph();f.editor.state.document.flow.touch()
                    f.editor.flowText.startSession(FlowRange.caret(FlowPos.START))
                } else {
                    f.editor.focusPane(Pane.PRIMARY);f.editor.selectTool(Tool.TEXT_BOX)
                    parkingPen(f.editor,MotionEvent.ACTION_DOWN,Pt(220.0,220.0))
                    parkingPen(f.editor,MotionEvent.ACTION_UP,Pt(220.0,220.0));f.editor.updateEditingText("Keep retained text")
                }
            }
            coroutineScope {
                val model=f.editor.state.document;val revision=model.bodyRevision;val undo=f.editor.history.nextUndo
                val draft=f.editor.controller.activeTextItem()
                val preparing=async(Dispatchers.Main) {f.editor.preparePagedPairOpen(f.sourceUri,"source.xnote")}
                repeat(3) {parkingDispatch()}
                withContext(Dispatchers.Main) {
                    assertFalse("Retained input must finish naturally",preparing.isCompleted)
                    if(flow) assertTrue(f.editor.flowText.active) else assertSame(draft,f.editor.controller.activeTextItem())
                    assertEquals(revision,model.bodyRevision);assertSame(undo,f.editor.history.nextUndo)
                }
                preparing.cancelAndJoin()
                withContext(Dispatchers.Main) {
                    assertSame(model,f.editor.state.document);assertEquals(revision,model.bodyRevision)
                    assertSame(undo,f.editor.history.nextUndo)
                    if(flow) {assertTrue(f.editor.flowText.active);f.editor.flowText.endSession()}
                    else {assertSame(draft,f.editor.controller.activeTextItem());f.editor.commitText()}
                }
            }
        }
    }

    @Test fun aSharedPeerHandsItsHeldKeyAndReadingPositionToTheActualOwnerWithoutReplacingFormalTool()=runParkingScenario {f->
        withContext(Dispatchers.IO) {f.seed()}
        withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri,"source.xnote")
            val peer=f.editor.secondaryPane();assertTrue(peer.openSharedView(f.editor))
            f.editor.view.layout(0,0,1000,1000);peer.view.layout(0,0,1000,1000)
            f.editor.selectTool(Tool.PEN);f.editor.focusPane(Pane.SECONDARY);peer.selectTool(Tool.HIGHLIGHTER)
            val down=KeyEvent(1L,2L,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_F1,0)
            val old=f.editor.preferences.penShortcuts
            try {
                assertTrue(f.editor.setPenShortcuts(listOf(PenShortcut("hold",listOf(requireNotNull(PenSignals.key(down))),PenBinding("eraser",PenBindingMode.HOLD)))))
                assertTrue(peer.onRecordedPenKey(down));assertEquals(Tool.ERASER,peer.tool)
                peer.state.zoom=1.7;peer.state.scrollX=19.0;peer.state.scrollY=83.0
                val model=f.editor.state.document;val history=f.editor.history;val token=f.editor.penInputOwnerToken
                val cap=requireNotNull(f.editor.preparePagedPairOpen(f.sourceUri,"source.xnote",Pane.SECONDARY))
                try {
                    assertEquals(listOf(peer to f.editor),cap.keyHandoffs);assertTrue(cap.commit())
                    assertSame(model,f.editor.state.document);assertSame(history,f.editor.history);assertSame(token,f.editor.penInputOwnerToken)
                    assertEquals(Tool.PEN,f.editor.toolbarTool);assertEquals(Tool.ERASER,f.editor.tool)
                    assertFalse(peer.noteOpen)
                    val position=f.editor.state.pendingInitialView as com.xnotes.canvas.InitialView.Restore
                    assertEquals(1.7,position.zoom,0.0);assertEquals(19.0,position.scrollX,0.0);assertEquals(83.0,position.scrollY,0.0)
                    assertTrue(f.editor.onRecordedPenKey(KeyEvent.changeAction(down,KeyEvent.ACTION_UP)))
                    assertEquals(Tool.PEN,f.editor.tool);assertEquals(Tool.PEN,f.editor.toolbarTool)
                } finally {cap.close()}
            } finally {f.editor.setPenShortcuts(old)}
        }
        assertEquals(0,f.writes.get())
    }

    @Test fun aSameUuidModelReplacementDuringTheLastReadRetiresTheCandidateWithoutTouchingTheReplacement()=runParkingScenario {f->
        withContext(Dispatchers.IO) {f.seed();f.seed(f.target)}
        val old=withContext(Dispatchers.Main) {f.editor.openAsync(f.sourceUri,"source.xnote");f.editor.state.document}
        val entered=CompletableDeferred<Unit>();val reads=AtomicInteger()
        f.beforeTargetRead={if(reads.incrementAndGet()==2) {entered.complete(Unit);check(f.release.await(10,TimeUnit.SECONDS))}}
        coroutineScope {
            val preparing=async(Dispatchers.Main) {f.editor.preparePagedPairOpen(f.targetUri,"target.xnote",Pane.SECONDARY)}
            entered.await()
            val replacement=withContext(Dispatchers.Main) {
                // The final source read owns the storage lock; adopt synchronously instead of
                // starting newNote's asynchronous save/settle flow behind that same lock.
                val next=Document(mutableListOf(Page(500.0,500.0))).apply {identity=old.identity}
                org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(f.editor,"replaceDocument",
                    org.robolectric.util.ReflectionHelpers.ClassParameter.from(Document::class.java,next))
                assertSame(next,f.editor.state.document);assertNotSame(old,next)
                assertEquals(old.identity.documentId,next.identity.documentId)
                next
            }
            f.release.countDown();assertNull(preparing.await())
            withContext(Dispatchers.Main) {assertSame(replacement,f.editor.state.document);assertNotSame(old,replacement);assertTrue(f.editor.noteOpen)}
        }
        assertEquals(0,f.writes.get())
    }

    @Test fun actualCanvasAndAnotherLiveLocationWithTheSameUuidAreRejectedBeforeOutgoingGuards() {
        for(canvas in listOf(false,true)) runParkingScenario {f->
            withContext(Dispatchers.IO) {f.seed();if(canvas) {
                f.target.outputStream().use {CanvasCodec(AndroidImageCodec(),AndroidTextMeasurer()).write(InfiniteDocument(),it)}
            } else f.source.copyTo(f.target,overwrite=true)}
            withContext(Dispatchers.Main) {
                f.editor.openAsync(f.sourceUri,"source.xnote");val model=f.editor.state.document
                var guarded=0
                assertNull(f.editor.preparePagedPairOpen(f.targetUri,"misleading.xnote",guardOwners={_,_->guarded++;emptySet()}))
                assertEquals(0,guarded);assertSame(model,f.editor.state.document);assertTrue(f.editor.noteOpen)
            }
            assertEquals(0,f.writes.get())
        }
    }

    @Test fun aSourceChangedDuringExitProtectionIsActuallyReDecodedBeforeItsSingleCommit()=runParkingScenario {f->
        withContext(Dispatchers.IO) {f.seed();f.seed(f.target)}
        withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri,"source.xnote")
            val cap=requireNotNull(f.editor.preparePagedPairOpen(f.targetUri,"target.xnote",guardOwners={_,_->
                withContext(Dispatchers.IO) {
                    val next=f.target.inputStream().use {f.codec.read(it)}
                    next.pages.first().items+=Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),listOf(Sample(71.0,110.0,.5),Sample(99.0,140.0,.8)))
                    f.target.outputStream().use {f.codec.write(next.fixedWriteSnapshot(),it)}
                }
                emptySet()
            }))
            try {
                assertTrue(cap.commit());assertEquals(1,cap.target.state.document.pages.first().items.filterIsInstance<Stroke>().size)
                assertEquals(71.0,cap.target.state.document.pages.first().items.filterIsInstance<Stroke>().single().samples[0].x,0.0)
            } finally {cap.close()}
        }
        assertEquals(0,f.writes.get())
    }
}
