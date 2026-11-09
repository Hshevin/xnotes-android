package com.xnotes.pen

import android.view.MotionEvent
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.TextItem
import com.xnotes.core.text.FlowPos
import com.xnotes.core.text.FlowRange
import com.xnotes.core.text.FlowTable
import com.xnotes.core.text.Paragraph
import com.xnotes.core.tools.Tool
import com.xnotes.format.CanvasCodec
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.ui.Editor
import com.xnotes.ui.Pane
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual native owners and input/edit controllers. Pure map navigation never ends their edits. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class MapSourcePureParkingHostTest {
    private fun stopDebounce(editor:Editor) {
        for(name in listOf("noteDebounceJob","canvasDebounceJob"))
            (Editor::class.java.getDeclaredField(name).apply {isAccessible=true}.get(editor) as? Job)?.cancel()
    }
    private suspend fun dirtyPanes(f:EditorPageCopyFixture):List<Editor> {
        val other=openParkingPanes(f)
        return withContext(Dispatchers.Main) {
            listOf(f.editor,other).also {panes->panes.forEach {pane->
                parkingPen(pane,MotionEvent.ACTION_DOWN,Pt(100.0,100.0))
                parkingPen(pane,MotionEvent.ACTION_MOVE,Pt(140.0,130.0))
                parkingPen(pane,MotionEvent.ACTION_UP,Pt(180.0,160.0))
                stopDebounce(pane);assertTrue(pane.state.document.dirty);assertTrue(pane.history.canUndo)
            }}
        }
    }
    @Test fun bothDirtyNativeOwnersCanParkWithoutChangingTheirModelsHistoriesOrSourceBytes()=runParkingScenario {f->
        val panes=dirtyPanes(f);val original=f.source.readBytes();val second=f.target.readBytes()
        withContext(Dispatchers.Main) {
            val models=panes.map {it.state.document};val histories=panes.map {it.history}
            val undos=histories.map {it.nextUndo};val revisions=models.map {it.bodyRevision}
            val owners=panes.map {it.documentOwner}
            val proof=requireNotNull(f.editor.awaitMapSourceParking());assertTrue(proof.current())
            for(i in panes.indices) {
                assertSame(models[i],panes[i].state.document);assertSame(histories[i],panes[i].history)
                assertSame(undos[i],panes[i].history.nextUndo);assertSame(owners[i],panes[i].documentOwner)
                assertEquals(revisions[i],models[i].bodyRevision);assertTrue(models[i].dirty)
            }
            assertNotSame(owners[0],owners[1])
        }
        assertArrayEquals(original,f.source.readBytes());assertArrayEquals(second,f.target.readBytes())
        assertEquals(0,f.writes.get());assertEquals(0,f.writeOpens.get())
    }
    @Test fun emptyAndChangedTextDraftsBlockPureParkingAndCancellingOnlyRetiresThatWaiter()=runParkingScenario {f->
        dirtyPanes(f)
        withContext(Dispatchers.Main) {
            // The preceding real secondary pen stroke owns focus. Settle that intentional
            // handoff before arming Text, otherwise focusForPenInput copies its Pen tool back.
            f.editor.focusPane(Pane.PRIMARY)
            f.editor.selectTool(Tool.TEXT_BOX)
            parkingPen(f.editor,MotionEvent.ACTION_DOWN,Pt(220.0,220.0))
            parkingPen(f.editor,MotionEvent.ACTION_UP,Pt(220.0,220.0))
            assertNotNull(f.editor.controller.activeTextItem());assertNotNull(f.editor.editingField)
        }
        coroutineScope {
            val waiting=async(Dispatchers.Main) {f.editor.awaitMapSourceParking()}
            parkingDispatch();assertFalse(waiting.isCompleted)
            val draft=withContext(Dispatchers.Main) {
                f.editor.updateEditingText("keep the unconfirmed native draft")
                val model=f.editor.state.document
                Triple(model.bodyRevision,f.editor.history.nextUndo,f.editor.controller.activeTextItem())
            }
            parkingDispatch();assertFalse(waiting.isCompleted);waiting.cancelAndJoin()
            withContext(Dispatchers.Main) {
                assertSame(draft.third,f.editor.controller.activeTextItem());assertNotNull(f.editor.editingField)
                assertEquals(draft.first,f.editor.state.document.bodyRevision);assertSame(draft.second,f.editor.history.nextUndo)
                // The existing explicit/default parking contract still commits the user's text.
                assertNotNull(f.editor.awaitHostParking())
                assertNull(f.editor.controller.activeTextItem())
                assertEquals("keep the unconfirmed native draft",f.editor.state.document.pages.first().items.filterIsInstance<TextItem>().single().text)
                stopDebounce(f.editor)
            }
        }
        assertEquals(0,f.writes.get());assertEquals(0,f.writeOpens.get())
    }
    @Test fun aLiveFlowCaretWithoutAPendingBurstWaitsForTheUsersNaturalSessionEnd()=runParkingScenario {f->
        dirtyPanes(f)
        withContext(Dispatchers.Main) {
            f.editor.state.document.flow.paragraphs+=Paragraph()
            f.editor.state.document.flow.touch()
            f.editor.flowText.startSession(FlowRange.caret(FlowPos.START))
            assertTrue(f.editor.flowEditingActive);assertFalse(f.editor.flowText.hasPendingBurst)
        }
        coroutineScope {
            val model=f.editor.state.document;val undo=f.editor.history.nextUndo;val revision=model.bodyRevision
            val waiting=async(Dispatchers.Main) {f.editor.awaitMapSourceParking()}
            parkingDispatch();assertFalse(waiting.isCompleted)
            withContext(Dispatchers.Main) {
                assertTrue(f.editor.flowText.active);assertTrue(f.editor.flowEditingActive)
                assertSame(undo,f.editor.history.nextUndo);assertEquals(revision,model.bodyRevision)
                f.editor.flowText.endSession();stopDebounce(f.editor)
            }
            val proof=withTimeout(5000) {waiting.await()};assertNotNull(proof)
            withContext(Dispatchers.Main) {
                assertTrue(proof!!.current());assertFalse(f.editor.flowText.active)
                assertSame(undo,f.editor.history.nextUndo);assertEquals(revision,model.bodyRevision)
            }
        }
        assertEquals(0,f.writes.get());assertEquals(0,f.writeOpens.get())
    }
    @Test fun tableEditAndEvenAZeroChangePreviewWaitAndTheirNaturalEndWakesWithoutHistory()=runParkingScenario {f->
        dirtyPanes(f);val table=FlowTable(listOf(.5,.5),listOf(0.0))
        withContext(Dispatchers.Main) {
            val flow=f.editor.state.document.flow
            flow.paragraphs+=Paragraph(table=table,cellStart=true)
            flow.paragraphs+=Paragraph(table=table,cellStart=true)
            flow.touch()
        }
        for(editMode in listOf(true,false)) coroutineScope {
            val undo=f.editor.history.nextUndo;val revision=f.editor.state.document.bodyRevision;val geometry=table.snapshot()
            val preview=withContext(Dispatchers.Main) {
                if(editMode) {f.editor.startTableEdit(table);null} else requireNotNull(f.editor.beginTablePreview(table))
            }
            val waiting=async(Dispatchers.Main) {f.editor.awaitMapSourceParking()}
            parkingDispatch();assertFalse(waiting.isCompleted)
            withContext(Dispatchers.Main) {
                assertSame(undo,f.editor.history.nextUndo);assertEquals(revision,f.editor.state.document.bodyRevision)
                assertEquals(geometry,table.snapshot())
                if(editMode) {assertSame(table,f.editor.editingTable);f.editor.endTableEdit()}
                else f.editor.finishTablePreview(requireNotNull(preview),commit=false)
            }
            val proof=withTimeout(5000) {waiting.await()};assertNotNull(proof)
            withContext(Dispatchers.Main) {
                assertTrue(proof!!.current());assertEquals(geometry,table.snapshot())
                assertSame(undo,f.editor.history.nextUndo);assertEquals(revision,f.editor.state.document.bodyRevision)
            }
        }
        assertEquals(0,f.writes.get());assertEquals(0,f.writeOpens.get())
    }
    @Test fun stableSelectionIsPreservedButItsUnconfirmedStylePreviewIsNeverSettledByParking()=runParkingScenario {f->
        dirtyPanes(f)
        withContext(Dispatchers.Main) {f.editor.controller.selectAll()}
        val selected=withContext(Dispatchers.Main) {f.editor.controller.selectedItems()}
        val proof=withContext(Dispatchers.Main) {requireNotNull(f.editor.awaitMapSourceParking())}
        assertTrue(selected.isNotEmpty())
        coroutineScope {
            val before=withContext(Dispatchers.Main) {
                assertEquals(selected,f.editor.controller.selectedItems());assertTrue(proof.current())
                f.editor.controller.restyleSelection(null,10.0,preview=true)
                Triple(f.editor.state.document.bodyRevision,f.editor.history.nextUndo,selected.filterIsInstance<Stroke>().map {it.config})
            }
            val waiting=async(Dispatchers.Main) {f.editor.awaitMapSourceParking()}
            parkingDispatch();assertFalse(waiting.isCompleted)
            withContext(Dispatchers.Main) {
                assertFalse(proof.current());assertTrue(f.editor.controller.hasPendingContentTransaction)
                assertEquals(selected,f.editor.controller.selectedItems());assertEquals(before.first,f.editor.state.document.bodyRevision)
                assertSame(before.second,f.editor.history.nextUndo);assertEquals(before.third,selected.filterIsInstance<Stroke>().map {it.config})
                f.editor.controller.finishSelectionPreviews();stopDebounce(f.editor)
            }
            val next=withTimeout(5000) {waiting.await()};assertNotNull(next)
            withContext(Dispatchers.Main) {assertTrue(next!!.current());assertEquals(selected,f.editor.controller.selectedItems())}
        }
        assertEquals(0,f.writes.get());assertEquals(0,f.writeOpens.get())
    }
    @Test fun canvasTextRemainsAnUnconfirmedDraftUntilItsUserCommitsIt()=runParkingScenario {f->
        withContext(Dispatchers.IO) {f.target.outputStream().use {CanvasCodec(AndroidImageCodec(),AndroidTextMeasurer()).write(InfiniteDocument(),it)}}
        val original=f.target.readBytes()
        withContext(Dispatchers.Main) {
            assertTrue(f.editor.openCanvasAsync(f.targetUri,"canvas.xcanvas"))
            f.editor.infinite.view.layout(0,0,1000,800)
            f.editor.infinite.insertTextBox(Rect(100.0,100.0,220.0,100.0))
            assertNotNull(f.editor.infinite.editingField)
        }
        coroutineScope {
            val waiting=async(Dispatchers.Main) {f.editor.awaitMapSourceParking()}
            parkingDispatch();assertFalse(waiting.isCompleted)
            withContext(Dispatchers.Main) {f.editor.infinite.updateEditingText("unconfirmed Canvas text")}
            val revision=f.editor.infinite.document.bodyRevision;val undo=f.editor.infinite.history.nextUndo
            parkingDispatch();assertFalse(waiting.isCompleted);waiting.cancelAndJoin()
            withContext(Dispatchers.Main) {
                assertNotNull(f.editor.infinite.editingField);assertSame(undo,f.editor.infinite.history.nextUndo)
                assertEquals(revision,f.editor.infinite.document.bodyRevision)
                f.editor.infinite.commitText();stopDebounce(f.editor)
                assertNotNull(f.editor.awaitMapSourceParking());assertNull(f.editor.infinite.editingField)
                assertTrue(f.editor.infinite.history.canUndo);assertTrue(f.editor.infinite.document.dirty)
            }
        }
        assertArrayEquals(original,f.target.readBytes());assertEquals(0,f.writes.get());assertEquals(0,f.writeOpens.get())
    }
    @Test fun cancellingPureParkingNeverCancelsTheNativeWriteThatWasAlreadyAccepted()=runParkingScenario {f->
        withContext(Dispatchers.IO) {f.seed(f.target)}
        val entered=CompletableDeferred<Unit>();val done=CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            f.editor.openAsync(f.targetUri,"target.xnote")
            f.addStroke(120.0,recordHistory=true);stopDebounce(f.editor)
        }
        val model=f.editor.state.document;val history=f.editor.history;val undo=history.nextUndo
        f.beforeTargetWrite={entered.complete(Unit);check(f.release.await(10,java.util.concurrent.TimeUnit.SECONDS))}
        coroutineScope {
            withContext(Dispatchers.Main) {f.editor.saveToThen(f.targetUri) {done.complete(it)}}
            entered.await()
            val waiting=async(Dispatchers.Main) {f.editor.awaitMapSourceParking()}
            parkingDispatch();assertFalse(waiting.isCompleted);waiting.cancelAndJoin()
            assertFalse(done.isCompleted);f.release.countDown();assertTrue(done.await())
            withContext(Dispatchers.Main) {
                assertNotNull(f.editor.awaitMapSourceParking())
                assertSame(model,f.editor.state.document);assertSame(history,f.editor.history);assertSame(undo,history.nextUndo)
            }
        }
        assertEquals(1,f.writes.get())
        assertEquals(1,f.target.inputStream().use {f.codec.read(it)}.pages.first().items.filterIsInstance<Stroke>().size)
    }

    @Test fun aPendingFlowBurstIsNotFlushedOrAddedToHistoryByAPureParkingWait()=runParkingScenario {f->
        dirtyPanes(f)
        withContext(Dispatchers.Main) {
            f.editor.state.document.flow.paragraphs+=Paragraph()
            f.editor.state.document.flow.touch()
            f.editor.flowText.startSession(FlowRange.caret(FlowPos.START))
            f.editor.flowText.applyReplace(FlowRange.caret(FlowPos.START),"unfinished flow burst")
            assertTrue(f.editor.flowText.hasPendingBurst)
        }
        coroutineScope {
            val model=f.editor.state.document;val revision=model.bodyRevision;val undo=f.editor.history.nextUndo
            val waiting=async(Dispatchers.Main) {f.editor.awaitMapSourceParking()}
            parkingDispatch();assertFalse(waiting.isCompleted);waiting.cancelAndJoin()
            withContext(Dispatchers.Main) {
                assertTrue(f.editor.flowText.active);assertTrue(f.editor.flowText.hasPendingBurst)
                assertEquals("unfinished flow burst",model.flow.plainText())
                assertSame(undo,f.editor.history.nextUndo);assertEquals(revision,model.bodyRevision)
                f.editor.flowText.endSession();stopDebounce(f.editor)
                assertFalse(f.editor.flowText.hasPendingBurst);assertNotSame(undo,f.editor.history.nextUndo)
                assertNotNull(f.editor.awaitMapSourceParking())
                assertEquals("unfinished flow burst",model.flow.plainText())
            }
        }
        assertEquals(0,f.writes.get());assertEquals(0,f.writeOpens.get())
    }

}
