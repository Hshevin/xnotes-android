package com.xnotes.pen

import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.AddCanvasItem
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.format.CanvasCodec
import com.xnotes.mindmap.SourceScope
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.ui.Editor
import com.xnotes.ui.PageReferencePreparation
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real fixed source geometry and typed Canvas saves on the existing native owner. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class CanvasRegionReferenceSourceHostTest {
    private fun codec()=CanvasCodec(AndroidImageCodec(),AndroidTextMeasurer())
    private fun seed(file:File,document:InfiniteDocument=InfiniteDocument()):InfiniteDocument {
        file.outputStream().use {codec().write(document,it)};return document
    }
    private fun add(editor:Editor,x:Double):Stroke {
        val doc=editor.infinite.document
        val stroke=Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),listOf(Sample(x,100.0,.5),Sample(x+20,140.0,.8)))
        doc.add(stroke);editor.infinite.history.push(AddCanvasItem(doc,stroke));doc.dirty=true
        return stroke
    }
    private val area=Rect(-140.0,55.0,380.0,240.0)
    @Test fun aCleanActualCanvasProducesOnlyItsFixedRegionWithoutSavingOrFollowingLaterSelection()=runParkingScenario {f->
        withContext(Dispatchers.IO) {seed(f.target)}
        val bytes=f.target.readBytes()
        withContext(Dispatchers.Main) {
            assertTrue(f.editor.openCanvasAsync(f.targetUri,"Misleading filename.xnote"))
            val model=f.editor.infinite.document;val history=f.editor.infinite.history
            assertTrue(f.editor.requestCanvasRegionReference(area))
            val request=requireNotNull(f.editor.canvasRegionReferenceRequest)
            assertTrue(request.current());assertTrue(request.canSaveInPlace)
            val result=request.prepare()
            assertTrue(result is PageReferencePreparation.Ready)
            val source=(result as PageReferencePreparation.Ready).source
            assertEquals(SourceScope.CANVAS_REGION,source.scope);assertNull(source.pageId);assertEquals(area,source.region)
            assertEquals(model.identity.documentId,source.documentId)
            assertSame(model,f.editor.infinite.document);assertSame(history,f.editor.infinite.history)
            assertFalse(model.dirty);assertFalse(history.canUndo)
            val stroke=add(f.editor,500.0)
            f.editor.infinite.selection.select(listOf(stroke));f.editor.infinite.selection.clear()
            assertTrue(request.current());assertEquals(area,request.region)
            assertEquals(PageReferencePreparation.SaveRequired,request.prepare())
            request.clear();assertFalse(request.current());assertNull(f.editor.canvasRegionReferenceRequest)
        }
        assertArrayEquals(bytes,f.target.readBytes());assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }
    @Test fun dirtyPreparationWaitsForExplicitStrictSaveAndKeepsTheActualHistoryAndModel()=runParkingScenario {f->
        withContext(Dispatchers.IO) {seed(f.target)}
        val bytes=f.target.readBytes()
        withContext(Dispatchers.Main) {
            assertTrue(f.editor.openCanvasAsync(f.targetUri,"Canvas"));val model=f.editor.infinite.document
            val history=f.editor.infinite.history;add(f.editor,120.0)
            assertTrue(f.editor.requestCanvasRegionReference(area));val request=f.editor.canvasRegionReferenceRequest!!
            assertEquals(PageReferencePreparation.SaveRequired,request.prepare());assertArrayEquals(bytes,f.target.readBytes())
            assertEquals(0,f.writes.get());assertTrue(model.dirty);assertTrue(history.canUndo)
            assertTrue(request.saveInPlace() is PageReferencePreparation.Ready)
            assertSame(model,f.editor.infinite.document);assertSame(history,f.editor.infinite.history)
            assertFalse(model.dirty);assertTrue(history.canUndo);assertEquals(1,model.items.size)
            assertEquals(area,(request.prepare() as PageReferencePreparation.Ready).source.region)
        }
        assertEquals(1,f.writes.get());assertEquals(0,f.creates.get());assertEquals(0,f.deletes.get())
        assertEquals(1,f.target.inputStream().use {codec().read(it)}.items.size)
    }
    @Test fun successfulBPublicationKeepsLateC1DirtyAndTheNextExplicitSaveUsesBAsItsStrictBase()=runParkingScenario {f->
        withContext(Dispatchers.IO) {seed(f.target)}
        val request=withContext(Dispatchers.Main) {
            assertTrue(f.editor.openCanvasAsync(f.targetUri,"Canvas"));add(f.editor,120.0)
            assertTrue(f.editor.requestCanvasRegionReference(area));f.editor.canvasRegionReferenceRequest!!
        }
        val entered=CompletableDeferred<Unit>()
        f.beforeTargetWrite={entered.complete(Unit);check(f.release.await(10,java.util.concurrent.TimeUnit.SECONDS))}
        coroutineScope {
            val saving=async(Dispatchers.Main) {request.saveInPlace()}
            entered.await()
            withContext(Dispatchers.Main) {add(f.editor,400.0)}
            f.release.countDown()
            assertEquals(PageReferencePreparation.SaveRequired,saving.await())
        }
        f.beforeTargetWrite=null
        assertEquals(1,f.target.inputStream().use {codec().read(it)}.items.size)
        withContext(Dispatchers.Main) {
            assertTrue(f.editor.infinite.document.dirty);assertEquals(2,f.editor.infinite.document.items.size)
            assertTrue(request.current());assertTrue(request.canSaveInPlace)
            assertTrue(request.saveInPlace() is PageReferencePreparation.Ready)
            assertFalse(f.editor.infinite.document.dirty);assertTrue(f.editor.infinite.history.canUndo)
        }
        assertEquals(2,f.target.inputStream().use {codec().read(it)}.items.size)
        assertEquals(2,f.writes.get());assertEquals(0,f.creates.get());assertEquals(0,f.deletes.get())
    }
    @Test fun externalSameIdentityChangesCannotBeOverwrittenOrForkedByExplicitOrLaterOrdinarySaves() {
        for(explicit in listOf(true,false)) runParkingScenario {f->
            val source=withContext(Dispatchers.IO) {seed(f.target)}
            val request=withContext(Dispatchers.Main) {
                assertTrue(f.editor.openCanvasAsync(f.targetUri,"Canvas"));add(f.editor,120.0)
                assertTrue(f.editor.requestCanvasRegionReference(area));f.editor.canvasRegionReferenceRequest!!
            }
            val changed=InfiniteDocument().apply {identity=source.identity;dpi=200}
            withContext(Dispatchers.IO) {seed(f.target,changed)}
            val external=f.target.readBytes()
            withContext(Dispatchers.Main) {
                val model=f.editor.infinite.document;val history=f.editor.infinite.history
                // Even fresh weak size/mtime metadata cannot authorize overwriting a foreign body.
                Editor::class.java.getDeclaredMethod("rememberStamp",String::class.java).apply {isAccessible=true}.invoke(f.editor,f.targetUri)
                if(explicit) assertFalse(request.saveInPlace() is PageReferencePreparation.Ready)
                else assertFalse(f.editor.saveCanvasBeforeReplacement())
                assertSame(model,f.editor.infinite.document);assertSame(history,f.editor.infinite.history)
                assertTrue(model.dirty);assertTrue(history.canUndo);assertEquals(1,model.items.size)
            }
            assertArrayEquals(external,f.target.readBytes());assertEquals(0,f.writes.get());assertEquals(0,f.creates.get());assertEquals(0,f.deletes.get())
        }
    }
    @Test fun anOrdinaryFixedCanvasSaveContinuesItsProvenCompleteVersionForLaterRegionCreation()=runParkingScenario {f->
        withContext(Dispatchers.IO) {seed(f.target)}
        withContext(Dispatchers.Main) {
            assertTrue(f.editor.openCanvasAsync(f.targetUri,"Canvas"));add(f.editor,120.0)
            val model=f.editor.infinite.document;val history=f.editor.infinite.history
            assertTrue(f.editor.saveCanvasBeforeReplacement());assertFalse(model.dirty)
            assertTrue(f.editor.requestCanvasRegionReference(area));val request=f.editor.canvasRegionReferenceRequest!!
            assertTrue(request.canSaveInPlace)
            assertTrue(request.prepare() is PageReferencePreparation.Ready)
            assertSame(model,f.editor.infinite.document);assertSame(history,f.editor.infinite.history);assertTrue(history.canUndo)
        }
        assertEquals(1,f.writes.get());assertEquals(0,f.creates.get());assertEquals(0,f.deletes.get())
    }
    @Test fun anIncompleteTolerantOpenCannotCreateARegionOrWashItsLostContentThroughASave()=runParkingScenario {f->
        withContext(Dispatchers.IO) {
            seed(f.target)
            val entries=java.util.zip.ZipFile(f.target).use {zip->zip.entries().asSequence().map {entry->
                entry.name to zip.getInputStream(entry).use {it.readBytes()}
            }.toList()}
            val replacement=File(f.root,"partial-canvas.zip")
            java.util.zip.ZipOutputStream(replacement.outputStream()).use {zip->
                for((name,bytes) in entries) {
                    zip.putNextEntry(java.util.zip.ZipEntry(name))
                    zip.write(if(name=="manifest.json") org.json.JSONObject(bytes.toString(Charsets.UTF_8))
                        .put("items",org.json.JSONArray().put(org.json.JSONObject().put("kind","future-shape")))
                        .toString().toByteArray(Charsets.UTF_8) else bytes)
                    zip.closeEntry()
                }
            }
            replacement.copyTo(f.target,overwrite=true);replacement.delete()
        }
        val bytes=f.target.readBytes()
        withContext(Dispatchers.Main) {
            assertTrue("Ordinary legacy open remains tolerant",f.editor.openCanvasAsync(f.targetUri,"Canvas"))
            assertTrue(f.editor.requestCanvasRegionReference(area));val request=f.editor.canvasRegionReferenceRequest!!
            assertFalse(request.canSaveInPlace);assertEquals(PageReferencePreparation.Failed,request.prepare())
            add(f.editor,120.0)
            assertEquals(PageReferencePreparation.Failed,request.prepare())
            assertEquals(PageReferencePreparation.Failed,request.saveInPlace())
            assertFalse(f.editor.saveCanvasBeforeReplacement());assertTrue(f.editor.infinite.document.dirty)
        }
        assertArrayEquals(bytes,f.target.readBytes());assertEquals(0,f.writes.get());assertEquals(0,f.creates.get());assertEquals(0,f.deletes.get())
    }
    @Test fun onlyAnExplicitCanvasRuntimeTargetCanOpenARegionAndItNeverAcceptsANote()=runParkingScenario {f->
        val canvas=withContext(Dispatchers.IO) {f.seed();seed(f.target)}
        val bytes=f.target.readBytes()
        withContext(Dispatchers.Main) {
            assertTrue(f.editor.openCanvasAsync(f.targetUri,"Canvas"))
            val model=f.editor.infinite.document;val history=f.editor.infinite.history
            val legacy=f.editor.prepareReference(com.xnotes.storage.NativeReferenceTarget(canvas.identity.documentId),area)
            assertTrue(legacy is com.xnotes.ui.NativeReferencePreparation.Failed)
            val typed=f.editor.prepareReference(com.xnotes.storage.NativeReferenceTarget(canvas.identity.documentId,
                requiredKind=com.xnotes.core.util.DocumentKind.CANVAS),area)
            assertTrue(typed is com.xnotes.ui.NativeReferencePreparation.Ready)
            val result=f.editor.openReference((typed as com.xnotes.ui.NativeReferencePreparation.Ready).request)
            assertTrue(result is com.xnotes.ui.NativeReferenceResult.Opened)
            assertEquals(com.xnotes.ui.ReferenceRegionReveal.SHOWN,(result as com.xnotes.ui.NativeReferenceResult.Opened).region)
            assertSame(model,f.editor.infinite.document);assertSame(history,f.editor.infinite.history);assertFalse(model.dirty)
            f.editor.openAsync(f.sourceUri,"Note")
            val note=f.editor.state.document
            val wrong=f.editor.prepareReference(com.xnotes.storage.NativeReferenceTarget(note.identity.documentId,
                requiredKind=com.xnotes.core.util.DocumentKind.CANVAS),area)
            assertTrue(wrong is com.xnotes.ui.NativeReferencePreparation.Failed)
            assertEquals(com.xnotes.storage.NativeReferenceIssue.UNSUPPORTED,(wrong as com.xnotes.ui.NativeReferencePreparation.Failed).issue)
            assertSame(note,f.editor.state.document);assertFalse(f.editor.canvasOpen)
        }
        assertArrayEquals(bytes,f.target.readBytes());assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }
    @Test fun modelPathOrIdentityReplacementRetiresTheOriginalRequestInsteadOfRebindingIt() {
        for(which in 0..2) runParkingScenario {f->
            withContext(Dispatchers.IO) {seed(f.target)}
            withContext(Dispatchers.Main) {
                assertTrue(f.editor.openCanvasAsync(f.targetUri,"Canvas"));val doc=f.editor.infinite.document
                assertTrue(f.editor.requestCanvasRegionReference(area));val request=f.editor.canvasRegionReferenceRequest!!
                when(which) {
                    0->doc.path=f.sourceUri
                    1->doc.identity=doc.identity.copy(documentId=java.util.UUID.randomUUID().toString())
                    else->f.editor.infinite.replaceDocument(InfiniteDocument().apply {identity=doc.identity;path=doc.path})
                }
                assertFalse(request.current());assertEquals(PageReferencePreparation.Changed,request.prepare())
                assertEquals(PageReferencePreparation.Changed,request.saveInPlace())
            }
            assertEquals(0,f.writes.get());assertEquals(0,f.creates.get());assertEquals(0,f.deletes.get())
        }
    }
}
