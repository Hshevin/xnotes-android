package com.xnotes.pen

import android.provider.DocumentsContract
import android.view.MotionEvent
import com.xnotes.canvas.InitialView
import com.xnotes.core.geometry.Pt
import com.xnotes.core.text.FlowPos
import com.xnotes.core.text.FlowRange
import com.xnotes.core.text.Paragraph
import com.xnotes.core.tools.Tool
import com.xnotes.format.BundleReferenceInfo
import com.xnotes.storage.NativeReferenceTarget
import com.xnotes.ui.Pane
import com.xnotes.ui.NativeReferencePreparation
import com.xnotes.ui.NativeReferenceResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual provider reads/adoption: a requested native slot must not collapse the workspace. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class NativeReferenceSplitSlotHostTest {
    @Test fun retainedSourceInTheRequestedSecondaryDoesNotCloseTheOtherNativeSlot()=runParkingScenario {f->
        val destination=openParkingPanes(f)
        withContext(Dispatchers.Main) {
            val primary=f.editor.state.document
            val history=f.editor.history
            f.addStroke(110.0,recordHistory=true)
            val model=destination.state.document
            val target=NativeReferenceTarget(model.identity.documentId,model.pages[1].referenceIdentity.id!!)
            val prepared=f.editor.prepareReference(target,pagedDestination=destination)
            assertTrue(prepared is NativeReferencePreparation.Ready)
            val request=(prepared as NativeReferencePreparation.Ready).request
            assertSame(destination,request.pagedDestination)
            val opened=f.editor.openReference(request)
            assertTrue(opened is NativeReferenceResult.Opened)
            assertTrue("Locating the requested source slot must preserve the unrelated primary slot",f.editor.noteOpen)
            assertSame(destination,(opened as NativeReferenceResult.Opened).pane)
            assertSame(primary,f.editor.state.document);assertSame(history,f.editor.history)
            assertTrue(primary.dirty);assertTrue(history.canUndo)
            assertSame(model,destination.state.document)
            assertEquals(0,f.writes.get())
        }
    }

    @Test fun unopenedSourceAdoptsIntoTheRequestedSecondaryWithoutReplacingThePrimary()=runParkingScenario {f->
        val destination=openParkingPanes(f)
        val retryUri=DocumentsContract.buildDocumentUri(f.authority,"retry").toString()
        val target=withContext(Dispatchers.IO) {
            f.seed(f.retry)
            val info=BundleReferenceInfo.inspectStrict(f.retry)
            NativeReferenceTarget(info.header.identity!!.documentId,info.orderedPageIds[1])
        }
        withContext(Dispatchers.Main) {
            val primary=f.editor.state.document;val history=f.editor.history
            f.addStroke(140.0,recordHistory=true)
            val prepared=f.editor.prepareReference(target,selectedUri=retryUri,pagedDestination=destination)
            assertTrue(prepared is NativeReferencePreparation.Ready)
            val request=(prepared as NativeReferencePreparation.Ready).request
            assertSame(destination,request.pagedDestination)
            val opened=f.editor.openReference(request)
            assertTrue(opened is NativeReferenceResult.Opened)
            assertSame("The actual adopted source must be the designated secondary slot",destination,(opened as NativeReferenceResult.Opened).pane)
            assertTrue(f.editor.noteOpen);assertSame(primary,f.editor.state.document)
            assertSame(history,f.editor.history);assertTrue(history.canUndo);assertTrue(primary.dirty)
            assertEquals(target.documentId,destination.state.document.identity.documentId)
            assertEquals(0,f.writes.get())
        }
    }

    @Test fun anActualSourceInAnotherSlotIsRefusedInsteadOfMovingItsOwner()=runParkingScenario {f->
        val destination=openParkingPanes(f)
        withContext(Dispatchers.Main) {
            val source=f.editor.state.document
            val other=destination.state.document
            val target=NativeReferenceTarget(source.identity.documentId,source.pages[1].referenceIdentity.id!!)
            assertTrue(f.editor.prepareReference(target,pagedDestination=destination) is NativeReferencePreparation.Failed)
            assertSame(source,f.editor.state.document);assertSame(other,destination.state.document)
            assertTrue(f.editor.noteOpen && destination.noteOpen);assertEquals(0,f.writes.get())
        }
    }

    @Test fun aChangedActualRootSlotDuringTheLastReadCannotAdoptThePreparedSecondary()=runParkingScenario {f->
        withContext(Dispatchers.IO) {f.seed();f.seed(f.target);f.seed(f.retry)}
        val info=BundleReferenceInfo.inspectStrict(f.target)
        val target=NativeReferenceTarget(info.header.identity!!.documentId,info.orderedPageIds[1])
        val destination=withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri,"source.xnote");f.editor.secondaryPane()
        }
        val old=destination.state.document
        val request=withContext(Dispatchers.Main) {
            (f.editor.prepareReference(target,selectedUri=f.targetUri,pagedDestination=destination) as NativeReferencePreparation.Ready).request
        }
        val entered=CompletableDeferred<Unit>();val reads=AtomicInteger()
        f.beforeTargetRead={if(reads.incrementAndGet()==2) {entered.complete(Unit);check(f.release.await(10,TimeUnit.SECONDS))}}
        coroutineScope {
            val opening=async(Dispatchers.Main) {f.editor.openReference(request)}
            entered.await()
            val replacement=withContext(Dispatchers.Main) {
                f.editor.openAsync(DocumentsContract.buildDocumentUri(f.authority,"retry").toString(),"retry.xnote")
                f.editor.state.document
            }
            f.release.countDown()
            assertTrue(opening.await() is NativeReferenceResult.Failed)
            withContext(Dispatchers.Main) {
                assertFalse(request.current());assertSame(replacement,f.editor.state.document)
                assertSame(old,destination.state.document);assertFalse(destination.noteOpen)
            }
        }
        assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }

    @Test fun preparedMixedDetachPreservesSharedOwnerAndPeerReadingPositionUntilSynchronousCommit()=runParkingScenario {f->
        withContext(Dispatchers.IO) {f.seed()}
        withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri,"source.xnote")
            val peer=f.editor.secondaryPane();assertTrue(peer.openSharedView(f.editor))
            f.editor.view.layout(0,0,1000,1000);peer.view.layout(0,0,1000,1000)
            peer.state.zoom=1.7;peer.state.scrollX=17.0;peer.state.scrollY=93.0
            val model=f.editor.state.document;val history=f.editor.history
            f.addStroke(120.0,recordHistory=true)
            val prepared=requireNotNull(f.editor.prepareDetachMixedPane(f.editor))
            assertTrue(prepared.current());assertTrue(f.editor.noteOpen && peer.noteOpen)
            assertSame(f.editor,peer.documentOwner);assertTrue(model.dirty)
            assertEquals(Pane.PRIMARY,prepared.result.remainingPane);assertEquals(Pane.SECONDARY,prepared.result.retiredPane)
            assertEquals(prepared.result,prepared.commit());assertFalse(prepared.current());assertNull(prepared.commit())
            assertTrue(f.editor.noteOpen);assertFalse(peer.noteOpen);assertSame(model,f.editor.state.document)
            assertSame(history,f.editor.history);assertTrue(history.canUndo);assertTrue(model.dirty)
            val position=f.editor.state.pendingInitialView as InitialView.Restore
            assertEquals(1.7,position.zoom,0.0);assertEquals(17.0,position.scrollX,0.0);assertEquals(93.0,position.scrollY,0.0)
            assertEquals(0,f.writes.get())
        }
    }

    @Test fun aLateContentChangeInvalidatesAPreparedDetachWithoutClosingEitherSlot()=runParkingScenario {f->
        val destination=openParkingPanes(f)
        withContext(Dispatchers.Main) {
            val original=f.editor.state.document;val other=destination.state.document
            val prepared=requireNotNull(f.editor.prepareDetachMixedPane(destination))
            f.addStroke(150.0,recordHistory=true)
            assertFalse(prepared.current());assertNull(prepared.commit())
            assertTrue(f.editor.noteOpen && destination.noteOpen)
            assertSame(original,f.editor.state.document);assertSame(other,destination.state.document)
            assertEquals(0,f.writes.get())
        }
    }

    @Test fun mixedFocusDoesNotCommitAnUnconfirmedTextDraftOrCopyTools()=runParkingScenario {f->
        val other=openParkingPanes(f)
        withContext(Dispatchers.Main) {
            f.editor.focusPane(Pane.PRIMARY);f.editor.selectTool(Tool.TEXT_BOX);other.selectTool(Tool.ERASER)
            parkingPen(f.editor,MotionEvent.ACTION_DOWN,Pt(220.0,220.0))
            parkingPen(f.editor,MotionEvent.ACTION_UP,Pt(220.0,220.0))
            f.editor.updateEditingText("Keep my unfinished native text")
            val draft=f.editor.controller.activeTextItem();assertNotNull(draft)
            val revision=f.editor.state.document.bodyRevision;val undo=f.editor.history.nextUndo
            assertTrue(f.editor.focusMixedNativePane(Pane.SECONDARY))
            assertSame(draft,f.editor.controller.activeTextItem());assertNotNull(f.editor.editingField)
            assertEquals(revision,f.editor.state.document.bodyRevision);assertSame(undo,f.editor.history.nextUndo)
            assertEquals(Tool.TEXT_BOX,f.editor.tool);assertEquals(Tool.ERASER,other.tool)
            f.editor.commitText()
        }
    }

    @Test fun retainedSlotLocationWaitsForActualTextAndCancellingDoesNotConfirmOrCloseIt()=runParkingScenario {f->
        val destination=openParkingPanes(f)
        val request=withContext(Dispatchers.Main) {
            f.editor.focusPane(Pane.SECONDARY);destination.selectTool(Tool.TEXT_BOX)
            parkingPen(destination,MotionEvent.ACTION_DOWN,Pt(220.0,220.0))
            parkingPen(destination,MotionEvent.ACTION_UP,Pt(220.0,220.0))
            destination.updateEditingText("Keep this source-side text unconfirmed")
            val model=destination.state.document
            val target=NativeReferenceTarget(model.identity.documentId,model.pages[1].referenceIdentity.id!!)
            (f.editor.prepareReference(target,pagedDestination=destination) as NativeReferencePreparation.Ready).request
        }
        coroutineScope {
            val model=destination.state.document;val revision=model.bodyRevision
            val undo=destination.history.nextUndo;val draft=destination.controller.activeTextItem()
            val locating=async(Dispatchers.Main) {f.editor.openReference(request)}
            repeat(3) {parkingDispatch()}
            withContext(Dispatchers.Main) {
                assertFalse("A live text draft must finish naturally before source location",locating.isCompleted)
                assertSame(draft,destination.controller.activeTextItem());assertNotNull(destination.editingField)
                assertEquals(revision,model.bodyRevision);assertSame(undo,destination.history.nextUndo)
            }
            locating.cancelAndJoin()
            withContext(Dispatchers.Main) {
                assertSame(draft,destination.controller.activeTextItem());assertNotNull(destination.editingField)
                assertTrue(f.editor.noteOpen && destination.noteOpen);assertTrue(request.current())
                assertEquals(revision,model.bodyRevision);assertSame(undo,destination.history.nextUndo)
                destination.commitText()
            }
        }
        assertEquals(0,f.writes.get())
    }

    @Test fun retainedSlotLocationWaitsForAnUnchangedLiveFlowCaretAndCancellationLeavesItActive()=runParkingScenario {f->
        val destination=openParkingPanes(f)
        val request=withContext(Dispatchers.Main) {
            val model=destination.state.document
            model.flow.paragraphs+=Paragraph();model.flow.touch()
            destination.flowText.startSession(FlowRange.caret(FlowPos.START))
            assertTrue(destination.flowEditingActive);assertFalse(destination.flowText.hasPendingBurst)
            val target=NativeReferenceTarget(model.identity.documentId,model.pages[1].referenceIdentity.id!!)
            (f.editor.prepareReference(target,pagedDestination=destination) as NativeReferencePreparation.Ready).request
        }
        coroutineScope {
            val model=destination.state.document;val revision=model.bodyRevision;val undo=destination.history.nextUndo
            val locating=async(Dispatchers.Main) {f.editor.openReference(request)}
            repeat(3) {parkingDispatch()}
            withContext(Dispatchers.Main) {
                assertFalse(locating.isCompleted);assertTrue(destination.flowText.active);assertTrue(destination.flowEditingActive)
                assertEquals(revision,model.bodyRevision);assertSame(undo,destination.history.nextUndo)
            }
            locating.cancelAndJoin()
            withContext(Dispatchers.Main) {
                assertTrue(destination.flowText.active);assertTrue(destination.flowEditingActive)
                assertTrue(f.editor.noteOpen && destination.noteOpen);assertEquals(revision,model.bodyRevision)
                assertSame(undo,destination.history.nextUndo);destination.flowText.endSession()
            }
        }
        assertEquals(0,f.writes.get())
    }

    @Test fun anExpiredExternalProofDuringIoKeepsTheOldWorkspaceAndNeverCallsAdopted()=runParkingScenario {f->
        withContext(Dispatchers.IO) {f.seed();f.seed(f.target)}
        val old=withContext(Dispatchers.Main) {f.editor.openAsync(f.sourceUri,"source.xnote");f.editor.state.document}
        var valid=true;var adopted=0
        val entered=CompletableDeferred<Unit>()
        f.beforeTargetRead={entered.complete(Unit);check(f.release.await(10,TimeUnit.SECONDS))}
        coroutineScope {
            val opening=async(Dispatchers.Main) {
                f.editor.openSingleFromHome(f.targetUri,"target.xnote",stillCurrent={valid},onAdopted={adopted++})
            }
            entered.await();withContext(Dispatchers.Main) {valid=false};f.release.countDown()
            assertFalse(opening.await())
            withContext(Dispatchers.Main) {assertSame(old,f.editor.state.document);assertTrue(f.editor.noteOpen);assertEquals(0,adopted)}
        }
    }

    @Test fun aSuccessfulSingleAdoptionCallsItsMainHandoffBeforeReturningFromCleanup()=runParkingScenario {f->
        withContext(Dispatchers.IO) {f.seed();f.seed(f.target)}
        withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri,"source.xnote")
            var adopted=0;var returned=false
            assertTrue(f.editor.openSingleFromHome(f.targetUri,"target.xnote",onAdopted={
                assertFalse(returned);assertEquals(f.targetUri,f.editor.visibleDocumentUri);adopted++
            }))
            returned=true;assertEquals(1,adopted)
        }
    }
}
