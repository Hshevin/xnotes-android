package com.xnotes.pen

import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.AddCanvasItem
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.format.CanvasCodec
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.ui.Editor
import com.xnotes.ui.PageReferencePreparation
import com.xnotes.ui.SourceCopyResult
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real empty-target publication, same-owner adoption and the new source request. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class CanvasRegionSourceCopyHostTest {
    private val area=Rect(-160.0,80.0,400.0,280.0)
    private fun codec()=CanvasCodec(AndroidImageCodec(),AndroidTextMeasurer())
    private fun seed(file:File) {file.outputStream().use {codec().write(InfiniteDocument(),it)}}
    private fun add(editor:Editor,x:Double) {
        val doc=editor.infinite.document
        val stroke=Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),listOf(Sample(x,110.0,.5),Sample(x+30,150.0,.8)))
        doc.add(stroke);editor.infinite.history.push(AddCanvasItem(doc,stroke));doc.dirty=true
    }
    private suspend fun newCanvas(f:EditorPageCopyFixture)=withContext(Dispatchers.Main) {
        f.editor.newCanvas()
        withTimeout(5000) {while(!f.editor.canvasOpen) delay(1)}
        add(f.editor,120.0)
        assertTrue(f.editor.requestCanvasRegionReference(area))
        f.editor.canvasRegionReferenceRequest!!
    }
    @Test fun aNewUnsavedCanvasPublishesACopyAdoptsTheSameModelAndStartsANewFixedRegionRequest()=runParkingScenario {f->
        val request=newCanvas(f)
        withContext(Dispatchers.Main) {
            val model=f.editor.infinite.document;val originalId=model.identity.documentId;val history=f.editor.infinite.history
            val viewport=f.editor.infinite.viewport
            assertNull(model.path);assertEquals(PageReferencePreparation.SaveRequired,request.prepare())
            assertFalse(request.canSaveInPlace);assertTrue(request.canSaveCopy)
            assertEquals(SourceCopyResult.Adopted,request.saveCopyTo(f.targetUri,"independent.xcanvas") {true})
            assertSame(model,f.editor.infinite.document);assertSame(history,f.editor.infinite.history);assertSame(viewport,f.editor.infinite.viewport)
            assertEquals(f.targetUri,model.path);assertNotEquals(originalId,model.identity.documentId)
            assertFalse(model.dirty);assertTrue(history.canUndo);assertEquals(1,model.items.size)
            assertFalse(request.current())
            val next=f.editor.canvasRegionReferenceRequest!!
            assertNotSame(request,next);assertNotEquals(request.id,next.id);assertEquals(area,next.region)
            val prepared=next.prepare();assertTrue(prepared is PageReferencePreparation.Ready)
            assertEquals(model.identity.documentId,(prepared as PageReferencePreparation.Ready).source.documentId)
            assertEquals(area,prepared.source.region);assertTrue(next.canSaveInPlace)
        }
        val copy=f.target.inputStream().use {codec().read(it)}
        assertEquals(1,copy.items.size);assertEquals(f.editor.infinite.document.identity,copy.identity)
        assertEquals(1,f.writes.get());assertEquals(0,f.deletes.get())
    }
    @Test fun anUnknownLegacyBaseCanCopyWithoutTouchingItsOriginalBytesOrClearingHistory()=runParkingScenario {f->
        withContext(Dispatchers.IO) {seed(f.source)}
        val bytes=f.source.readBytes()
        withContext(Dispatchers.Main) {
            assertTrue(f.editor.openCanvasAsync(f.sourceUri,"Original canvas"));add(f.editor,120.0)
            // The historical unclaimed working model has no native source binding, as on legacy restoration.
            Editor::class.java.getDeclaredMethod("retireCanvasEditSource").apply {isAccessible=true}.invoke(f.editor)
            assertTrue(f.editor.requestCanvasRegionReference(area));val request=f.editor.canvasRegionReferenceRequest!!
            assertFalse(request.canSaveInPlace);assertTrue(request.canSaveCopy)
            assertEquals(PageReferencePreparation.SaveRequired,request.prepare())
            val model=f.editor.infinite.document;val history=f.editor.infinite.history;val oldId=model.identity.documentId
            assertEquals(SourceCopyResult.Adopted,request.saveCopyTo(f.targetUri,"Independent canvas") {true})
            assertSame(model,f.editor.infinite.document);assertSame(history,f.editor.infinite.history);assertTrue(history.canUndo)
            assertNotEquals(oldId,model.identity.documentId);assertEquals(f.targetUri,model.path)
            assertTrue(f.editor.canvasRegionReferenceRequest!!.prepare() is PageReferencePreparation.Ready)
        }
        assertArrayEquals(bytes,f.source.readBytes());assertEquals(1,f.writes.get());assertEquals(0,f.deletes.get())
    }
    @Test fun lateC1SurvivesSameModelAdoptionAndOnlyAnExplicitNextSavePublishesIt()=runParkingScenario {f->
        val request=newCanvas(f);val entered=CompletableDeferred<Unit>()
        f.beforeTargetWrite={entered.complete(Unit);check(f.release.await(10,TimeUnit.SECONDS))}
        coroutineScope {
            val saving=async(Dispatchers.Main) {request.saveCopyTo(f.targetUri,"Copy") {true}}
            entered.await();withContext(Dispatchers.Main) {add(f.editor,500.0)};f.release.countDown()
            assertEquals(SourceCopyResult.Adopted,saving.await())
        }
        f.beforeTargetWrite=null
        assertEquals(1,f.target.inputStream().use {codec().read(it)}.items.size)
        withContext(Dispatchers.Main) {
            val next=f.editor.canvasRegionReferenceRequest!!
            assertTrue(f.editor.infinite.document.dirty);assertEquals(2,f.editor.infinite.document.items.size)
            assertTrue(f.editor.infinite.history.canUndo);assertTrue(next.canSaveInPlace)
            assertEquals(PageReferencePreparation.SaveRequired,next.prepare())
            assertTrue(next.saveInPlace() is PageReferencePreparation.Ready)
            assertFalse(f.editor.infinite.document.dirty)
        }
        assertEquals(2,f.target.inputStream().use {codec().read(it)}.items.size)
        assertEquals(2,f.writes.get());assertEquals(0,f.deletes.get())
    }
    @Test fun cancelledUiOrAChangedOriginKeepsThePublishedIndependentCopyWithoutAdoptingIt() {
        for(mode in listOf("cancel","path","identity","model")) runParkingScenario {f->
            val request=newCanvas(f);val model=f.editor.infinite.document;val originalId=model.identity.documentId
            val entered=CompletableDeferred<Unit>();f.beforeTargetWrite={entered.complete(Unit);check(f.release.await(10,TimeUnit.SECONDS))}
            coroutineScope {
                val saving=async(Dispatchers.Main) {request.saveCopyTo(f.targetUri,"Copy") {request.current()}}
                entered.await()
                withContext(Dispatchers.Main) {
                    when(mode) {
                        "cancel"->request.clear()
                        "path"->model.path=f.sourceUri
                        "identity"->model.identity=model.identity.copy(documentId=java.util.UUID.randomUUID().toString())
                        else->f.editor.infinite.replaceDocument(InfiniteDocument())
                    }
                }
                f.release.countDown()
                val result=saving.await();assertTrue("$mode: $result",result is SourceCopyResult.WrittenNotAdopted)
                val copy=f.target.inputStream().use {codec().read(it)}
                assertEquals(1,copy.items.size);assertNotEquals(originalId,copy.identity.documentId)
                withContext(Dispatchers.Main) {
                    assertNotEquals(copy.identity.documentId,f.editor.infinite.document.identity.documentId)
                    assertNotEquals(f.targetUri,f.editor.infinite.document.path)
                    if(mode=="cancel") {assertSame(model,f.editor.infinite.document);assertTrue(model.dirty);assertTrue(f.editor.infinite.history.canUndo)}
                }
            }
            assertEquals(1,f.writes.get());assertEquals(0,f.deletes.get())
        }
    }
    @Test fun aNonemptyTargetIsNeverOverwrittenAndAnActualRetainedCreateContinuesTheSameCandidate() {
        for(retain in listOf(false,true)) runParkingScenario {f->
            val request=newCanvas(f)
            val original=if(!retain) withContext(Dispatchers.IO) {seed(f.target);f.target.readBytes()} else null
            if(retain) f.beforeTargetWrite={throw java.io.IOException("Before target write")}
            withContext(Dispatchers.Main) {
                val model=f.editor.infinite.document;val history=f.editor.infinite.history
                val result=request.saveCopyTo(f.targetUri,"Copy") {true}
                assertEquals(if(retain) SourceCopyResult.Retained else SourceCopyResult.Failed,result)
                assertSame(model,f.editor.infinite.document);assertSame(history,f.editor.infinite.history)
                assertNull(model.path);assertTrue(model.dirty);assertTrue(history.canUndo)
                if(retain) {
                    val pending=request.pendingSaveCopy!!;assertFalse(request.canSaveCopy)
                    f.beforeTargetWrite=null
                    assertEquals(SourceCopyResult.Adopted,request.continueSaveCopy {true})
                    assertEquals(pending.documentId,model.identity.documentId)
                    assertTrue(f.editor.canvasRegionReferenceRequest!!.prepare() is PageReferencePreparation.Ready)
                } else {
                    assertNull(request.pendingSaveCopy);assertTrue(request.canSaveCopy)
                    assertArrayEquals(original!!,f.target.readBytes())
                }
            }
            assertEquals(if(retain) 1 else 0,f.writes.get());assertEquals(0,f.deletes.get())
        }
    }
}
