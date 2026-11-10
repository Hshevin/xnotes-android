package com.xnotes.pen

import com.xnotes.core.infinite.AddCanvasItem
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.BundleReferenceInfo
import com.xnotes.format.CanvasCodec
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.storage.NativeReferenceTarget
import com.xnotes.ui.Editor
import com.xnotes.ui.NativeReferencePreparation
import com.xnotes.ui.NativeReferenceRequest
import com.xnotes.ui.NativeReferenceResult
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Canvas parked behind a returned Home remains a real owner with guarded saves. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class CanvasHomeSplitSlotHostTest {
    private fun codec()=CanvasCodec(AndroidImageCodec(),AndroidTextMeasurer())
    private fun seed(file:File) {file.outputStream().use {codec().write(InfiniteDocument(),it)}}
    private fun add(editor:Editor,x:Double=120.0) {
        val doc=editor.infinite.document
        val stroke=Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),listOf(Sample(x,110.0,.5),Sample(x+30,150.0,.8)))
        doc.add(stroke);editor.infinite.history.push(AddCanvasItem(doc,stroke));doc.dirty=true
    }
    private suspend fun prepare(f:EditorPageCopyFixture):NativeReferenceRequest {
        val target=withContext(Dispatchers.IO) {
            val info=BundleReferenceInfo.inspectStrict(f.source)
            NativeReferenceTarget(info.header.identity!!.documentId,info.orderedPageIds[1],requiredKind=DocumentKind.NOTE)
        }
        return withContext(Dispatchers.Main) {
            val result=f.editor.prepareReference(target,selectedUri=f.sourceUri,pagedDestination=f.editor,allowCanvasHome=true)
            assertTrue(result is NativeReferencePreparation.Ready)
            (result as NativeReferencePreparation.Ready).request.also {
                assertSame(f.editor,it.pagedDestination);assertEquals(listOf(f.editor),it.guardOwners)
            }
        }
    }
    private suspend fun open(f:EditorPageCopyFixture) {
        withContext(Dispatchers.IO) {f.seed();seed(f.target)}
        withContext(Dispatchers.Main) {assertTrue(f.editor.openCanvasAsync(f.targetUri,"Canvas.xcanvas"));add(f.editor)}
    }

    @Test fun onlyTheExplicitHomePermitLetsANoteReplaceTheActualCanvasOwner()=runParkingScenario {f->
        open(f)
        val target=withContext(Dispatchers.IO) {
            val info=BundleReferenceInfo.inspectStrict(f.source)
            NativeReferenceTarget(info.header.identity!!.documentId,info.orderedPageIds[1])
        }
        withContext(Dispatchers.Main) {
            assertTrue(f.editor.prepareReference(target,selectedUri=f.sourceUri,pagedDestination=f.editor) is NativeReferencePreparation.Failed)
            assertNull(f.editor.prepareDetachMixedPane(f.editor));assertTrue(f.editor.canvasOpen)
        }
        val request=prepare(f)
        withContext(Dispatchers.Main) {
            val result=f.editor.openReference(request)
            assertTrue(result is NativeReferenceResult.Opened);assertSame(f.editor,(result as NativeReferenceResult.Opened).pane)
            assertTrue(f.editor.noteOpen);assertFalse(f.editor.canvasOpen)
            assertEquals(target.documentId,f.editor.state.document.identity.documentId)
        }
        assertEquals(1,f.target.inputStream().use {codec().read(it)}.items.size)
        assertEquals(1,f.writes.get());assertEquals(0,f.deletes.get())
    }

    @Test fun failedCanvasPublicationKeepsTheCanvasAndItsHistoryForBothNoteReplacementAndHomeDetach() {
        for(detach in listOf(false,true)) runParkingScenario {f->
            open(f);val bytes=f.target.readBytes()
            val request=if(detach) null else prepare(f)
            f.beforeTargetWrite={throw IOException("Canvas Home save failed")}
            withContext(Dispatchers.Main) {
                val model=f.editor.infinite.document;val history=f.editor.infinite.history;val undo=history.nextUndo
                if(detach) assertNull(f.editor.prepareDetachMixedPane(f.editor,allowCanvasHome=true))
                else assertTrue(f.editor.openReference(requireNotNull(request)) is NativeReferenceResult.Failed)
                assertTrue(f.editor.noteOpen && f.editor.canvasOpen);assertSame(model,f.editor.infinite.document)
                assertSame(history,f.editor.infinite.history);assertSame(undo,history.nextUndo);assertTrue(model.dirty)
            }
            f.beforeTargetWrite=null
            assertArrayEquals(bytes,f.target.readBytes());assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
        }
    }

    @Test fun contentArrivingDuringTheAcceptedCanvasSavePreventsNoteAdoptionAndKeepsWrittenB()=runParkingScenario {f->
        open(f);val request=prepare(f)
        val entered=CompletableDeferred<Unit>()
        f.beforeTargetWrite={entered.complete(Unit);check(f.release.await(10,TimeUnit.SECONDS))}
        coroutineScope {
            val opening=async(Dispatchers.Main) {f.editor.openReference(request)}
            entered.await()
            val model=withContext(Dispatchers.Main) {f.editor.infinite.document.also {add(f.editor,400.0)}}
            f.release.countDown();assertTrue(opening.await() is NativeReferenceResult.Failed)
            withContext(Dispatchers.Main) {
                assertTrue(f.editor.canvasOpen && f.editor.noteOpen);assertSame(model,f.editor.infinite.document)
                assertTrue(model.dirty);assertEquals(2,model.items.size);assertTrue(f.editor.infinite.history.canUndo)
            }
        }
        f.beforeTargetWrite=null
        assertEquals(1,f.target.inputStream().use {codec().read(it)}.items.size)
        assertEquals(1,f.writes.get());assertEquals(0,f.deletes.get())
    }

    @Test fun cancellingCanvasHomeReplacementOrDetachDoesNotCancelAcceptedSavingOrRetireItsOwner() {
        for(detach in listOf(false,true)) runParkingScenario {f->
            open(f);val request=if(detach) null else prepare(f)
            val entered=CompletableDeferred<Unit>()
            f.beforeTargetWrite={entered.complete(Unit);check(f.release.await(10,TimeUnit.SECONDS))}
            coroutineScope {
                val model=withContext(Dispatchers.Main) {f.editor.infinite.document}
                val operation=async(Dispatchers.Main) {
                    if(detach) f.editor.prepareDetachMixedPane(f.editor,allowCanvasHome=true)
                    else f.editor.openReference(requireNotNull(request))
                }
                entered.await();operation.cancel();f.release.countDown();operation.join()
                withContext(Dispatchers.Main) {
                    assertTrue(f.editor.saveCanvasBeforeReplacement())
                    assertTrue(f.editor.canvasOpen && f.editor.noteOpen);assertSame(model,f.editor.infinite.document)
                    assertFalse(model.dirty);assertTrue(f.editor.infinite.history.canUndo)
                }
            }
            f.beforeTargetWrite=null
            assertEquals(1,f.target.inputStream().use {codec().read(it)}.items.size)
            assertEquals(1,f.writes.get());assertEquals(0,f.deletes.get())
        }
    }

    @Test fun aCanvasHomeDetachCommitsOnlyItsActualSavedSlotAndRejectsLateContent()=runParkingScenario {f->
        open(f)
        withContext(Dispatchers.Main) {
            val model=f.editor.infinite.document;val history=f.editor.infinite.history
            val first=requireNotNull(f.editor.prepareDetachMixedPane(f.editor,allowCanvasHome=true))
            assertTrue(f.editor.canvasOpen && f.editor.noteOpen);assertTrue(first.current());assertFalse(model.dirty)
            add(f.editor,450.0);assertFalse(first.current());assertNull(first.commit())
            assertTrue(f.editor.canvasOpen);assertSame(model,f.editor.infinite.document);assertSame(history,f.editor.infinite.history)
            val next=requireNotNull(f.editor.prepareDetachMixedPane(f.editor,allowCanvasHome=true))
            assertNotNull(next.commit());assertFalse(f.editor.noteOpen);assertFalse(f.editor.canvasOpen)
            assertSame(model,f.editor.infinite.document);assertSame(history,f.editor.infinite.history);assertTrue(history.canUndo)
        }
        assertEquals(2,f.target.inputStream().use {codec().read(it)}.items.size)
        assertEquals(2,f.writes.get());assertEquals(0,f.deletes.get())
    }
}
