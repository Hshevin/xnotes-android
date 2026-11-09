package com.xnotes.pen

import android.view.InputDevice
import android.view.MotionEvent
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.AddCanvasItem
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.BundleHeader
import com.xnotes.format.BundleMigration
import com.xnotes.format.CanvasCodec
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.storage.NativeReferenceIssue
import com.xnotes.storage.NativeReferenceTarget
import com.xnotes.ui.Editor
import com.xnotes.ui.NativeReferencePreparation
import com.xnotes.ui.NativeReferenceRequest
import com.xnotes.ui.NativeReferenceResult
import com.xnotes.ui.ReferenceRegionReveal
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual Canvas codec, immutable source snapshot and Editor owner adoption; no replacement engine. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class NativeCanvasReferenceHostTest {
    private fun canvasCodec()=CanvasCodec(AndroidImageCodec(),AndroidTextMeasurer())
    private fun seedCanvas(file:File,doc:InfiniteDocument=InfiniteDocument()):InfiniteDocument {
        file.outputStream().use {canvasCodec().write(doc,it)}
        return doc
    }
    private fun ready(value:NativeReferencePreparation):NativeReferenceRequest {
        assertTrue("Expected the actual frozen source request: $value",value is NativeReferencePreparation.Ready)
        return (value as NativeReferencePreparation.Ready).request
    }
    private fun opened(value:NativeReferenceResult):NativeReferenceResult.Opened {
        assertTrue("Expected the actual source handoff: $value",value is NativeReferenceResult.Opened)
        return value as NativeReferenceResult.Opened
    }
    private fun add(editor:Editor,x:Double=120.0):Stroke {
        val canvas=editor.infinite;val doc=canvas.document
        val stroke=Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),listOf(Sample(x,110.0,.5),Sample(x+30,150.0,.8)))
        doc.add(stroke);canvas.history.push(AddCanvasItem(doc,stroke));doc.dirty=true
        return stroke
    }
    private var eventTime=1L
    private fun pen(editor:Editor,action:Int,point:Pt) {
        val at=editor.infinite.viewport.contentToViewport(point)
        val props=MotionEvent.PointerProperties().apply {id=7;toolType=MotionEvent.TOOL_TYPE_STYLUS}
        val coords=MotionEvent.PointerCoords().apply {x=at.x.toFloat();y=at.y.toFloat();pressure=.65f}
        val event=MotionEvent.obtain(1L,eventTime++,action,1,arrayOf(props),arrayOf(coords),0,0,1f,1f,0,0,InputDevice.SOURCE_STYLUS,0)
        try {assertTrue(editor.infinite.view.onTouchEvent(event))} finally {event.recycle()}
    }
    @Test fun unknownSelectedLocationUsesActualCanvasKindDespiteItsNoteFileNameAndNeverWritesTheSource()=runParkingScenario {f->
        val source=withContext(Dispatchers.IO) {f.seed();seedCanvas(f.target)}
        val before=f.target.readBytes();val target=NativeReferenceTarget(source.identity.documentId)
        withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri,"reader.xnote")
            assertNull(f.storage.registry.kindForId(target.documentId))
            val request=ready(f.editor.prepareReference(target,selectedUri=f.targetUri))
            assertEquals(DocumentKind.CANVAS,request.kind);assertNull(request.keptOwner)
            assertNull(request.model);assertNull(request.page);assertNull(request.canvasModel)
            assertNull("Kind probing itself never registers a source",f.storage.registry.kindForId(target.documentId))
            var committed=0
            val result=opened(f.editor.openReference(request,onOpened={actual->
                committed++;assertTrue(actual.pane.canvasOpen);assertEquals(target.documentId,actual.pane.visibleDocumentId)
                assertFalse(actual.pane.inSplit)
            }))
            assertEquals(1,committed);assertSame(f.editor,result.pane);assertEquals(ReferenceRegionReveal.NONE,result.region)
            assertTrue(f.editor.canvasOpen);assertFalse(f.editor.inSplit)
            assertEquals(target.documentId,f.editor.infinite.document.identity.documentId)
            assertEquals("target.xnote",f.editor.infinite.document.displayName)
            assertEquals(DocumentKind.CANVAS,f.editor.lastReadSourceVersion()!!.kind)
            assertEquals("xcanvas",f.storage.registry.kindForId(target.documentId))
        }
        assertArrayEquals(before,f.target.readBytes());assertEquals(0,f.writes.get());assertEquals(0,f.writeOpens.get());assertEquals(0,f.deletes.get())
    }
    @Test fun liveCanvasKeepsItsExactOwnerModelHistoryViewportDirtyAndEditSourceWithoutSaveOrReopen()=runParkingScenario {f->
        withContext(Dispatchers.IO) {seedCanvas(f.target)}
        val bytes=f.target.readBytes()
        withContext(Dispatchers.Main) {
            assertTrue(f.editor.openCanvasAsync(f.targetUri,"Actual canvas"))
            val canvas=f.editor.infinite;val model=canvas.document;val history=canvas.history;val viewport=canvas.viewport
            add(f.editor);viewport.scrollX=-345.0;viewport.scrollY=678.0;viewport.zoom=1.25
            val revision=model.bodyRevision
            val editField=Editor::class.java.getDeclaredField("canvasEditSource").apply {isAccessible=true}
            val edit=editField.get(f.editor)
            val request=ready(f.editor.prepareReference(NativeReferenceTarget(model.identity.documentId)))
            assertEquals(DocumentKind.CANVAS,request.kind);assertSame(f.editor,request.keptOwner)
            assertSame(model,request.canvasModel);assertNull(request.model);assertNull(request.page);assertTrue(request.guardOwners.isEmpty())
            val result=opened(f.editor.openReference(request))
            assertSame(f.editor,result.pane);assertSame(canvas,f.editor.infinite);assertSame(model,canvas.document)
            assertSame(history,canvas.history);assertSame(viewport,canvas.viewport);assertSame(edit,editField.get(f.editor))
            assertEquals(-345.0,viewport.scrollX,0.0);assertEquals(678.0,viewport.scrollY,0.0);assertEquals(1.25,viewport.zoom,0.0)
            assertEquals(revision,model.bodyRevision);assertTrue(model.dirty);assertTrue(history.canUndo)
            assertEquals(1,model.items.size);assertFalse(f.editor.inSplit)
            history.undo();assertTrue(model.items.isEmpty());history.redo();assertEquals(1,model.items.size)
        }
        assertArrayEquals(bytes,f.target.readBytes());assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }
    @Test fun canvasPageOrRegionIsStrictlyRejectedAndTheLivePenIsNeverCancelledOrSaved()=runParkingScenario {f->
        withContext(Dispatchers.IO) {seedCanvas(f.target)}
        val bytes=f.target.readBytes()
        withContext(Dispatchers.Main) {
            assertTrue(f.editor.openCanvasAsync(f.targetUri,"Actual canvas"));val model=f.editor.infinite.document
            val page=f.editor.prepareReference(NativeReferenceTarget(model.identity.documentId,UUID.randomUUID().toString()))
            assertTrue(page is NativeReferencePreparation.Failed);assertEquals(NativeReferenceIssue.UNSUPPORTED,(page as NativeReferencePreparation.Failed).issue)
            assertTrue(f.editor.prepareReference(NativeReferenceTarget(model.identity.documentId),Rect(0.0,0.0,30.0,40.0)) is NativeReferencePreparation.Failed)
            val request=ready(f.editor.prepareReference(NativeReferenceTarget(model.identity.documentId)))
            f.editor.infinite.view.layout(0,0,1000,1000);f.editor.selectTool(Tool.PEN)
            pen(f.editor,MotionEvent.ACTION_DOWN,Pt(100.0,100.0));pen(f.editor,MotionEvent.ACTION_MOVE,Pt(150.0,120.0))
            val result=f.editor.openReference(request)
            assertTrue(result is NativeReferenceResult.Failed);assertTrue(f.editor.stylusInContact)
            pen(f.editor,MotionEvent.ACTION_UP,Pt(170.0,150.0))
            assertSame(model,f.editor.infinite.document);assertEquals(1,model.items.filterIsInstance<Stroke>().size)
            assertTrue(model.dirty);assertTrue(f.editor.infinite.history.canUndo)
        }
        assertArrayEquals(bytes,f.target.readBytes());assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }
    @Test fun liveDuplicateIdentityIsCheckedAcrossNoteAndCanvasAndKnownLocationsRemainUnique() {
        for(otherKind in listOf(DocumentKind.NOTE,DocumentKind.CANVAS)) runParkingScenario {f->
            val original=withContext(Dispatchers.IO) {f.seed();seedCanvas(f.target)}
            val bytes=f.target.readBytes()
            withContext(Dispatchers.Main) {
                assertTrue(f.editor.openCanvasAsync(f.targetUri,"Canvas"));val model=f.editor.infinite.document;add(f.editor)
                val other=f.editor.secondaryPane()
                if(otherKind==DocumentKind.NOTE) {other.openAsync(f.sourceUri,"Other note");other.state.document.identity=model.identity}
                else {
                    // A legacy/external live arrangement must be checked even though the product never creates Canvas splits.
                    seedCanvas(f.source)
                    assertTrue(other.openCanvasAsync(f.sourceUri,"Other canvas"))
                    other.infinite.document.identity=original.identity
                }
                val prepared=f.editor.prepareReference(NativeReferenceTarget(model.identity.documentId))
                assertTrue(prepared is NativeReferencePreparation.Failed)
                assertEquals(NativeReferenceIssue.DUPLICATE,(prepared as NativeReferencePreparation.Failed).issue)
                assertSame(model,f.editor.infinite.document);assertTrue(model.dirty);assertTrue(f.editor.infinite.history.canUndo)
            }
            assertArrayEquals(bytes,f.target.readBytes());assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
        }
    }
    @Test fun lateColdIdentityKindOrCancellationNeverReplacesTheExistingNativeWorkspace() {
        for(mode in listOf("identity","kind","cancel")) runParkingScenario {f->
            val canvas=withContext(Dispatchers.IO) {f.seed();seedCanvas(f.target)}
            val target=NativeReferenceTarget(canvas.identity.documentId)
            val old=withContext(Dispatchers.Main) {f.editor.openAsync(f.sourceUri,"Current note");f.editor.state.document}
            val request=withContext(Dispatchers.Main) {ready(f.editor.prepareReference(target,selectedUri=f.targetUri))}
            val entered=CompletableDeferred<Unit>();val reads=AtomicInteger()
            f.beforeTargetRead={if(reads.incrementAndGet()==2) {entered.complete(Unit);check(f.release.await(10,TimeUnit.SECONDS))}}
            coroutineScope {
                var commits=0
                val opening=async(Dispatchers.Main) {f.editor.openReference(request,onOpened={commits++})}
                entered.await()
                when(mode) {
                    "identity"->withContext(Dispatchers.IO) {seedCanvas(f.target)}
                    "kind"->withContext(Dispatchers.IO) {
                        val note=Document(mutableListOf(Page(500.0,500.0))).apply {identity=canvas.identity}
                        f.target.outputStream().use {f.codec.write(note,it)}
                    }
                    else->withContext(Dispatchers.Main) {request.cancel()}
                }
                f.release.countDown()
                val result=opening.await()
                withContext(Dispatchers.Main) {
                    assertTrue("$mode: $result",result is NativeReferenceResult.Failed);assertEquals(0,commits)
                    assertSame(old,f.editor.state.document);assertTrue(f.editor.noteOpen);assertFalse(f.editor.canvasOpen)
                }
            }
            assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
        }
    }
    @Test fun sourceChangedWhileTheOutgoingOwnerSavesIsActuallyRereadBeforeCanvasAdoption()=runParkingScenario {f->
        val source=withContext(Dispatchers.IO) {f.seed(f.target);seedCanvas(f.source)}
        val changed=InfiniteDocument().apply {identity=source.identity;add(Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),
            listOf(Sample(700.0,110.0,.5),Sample(750.0,150.0,.8))))}
        val target=NativeReferenceTarget(source.identity.documentId)
        val request=withContext(Dispatchers.Main) {
            f.editor.openAsync(f.targetUri,"Outgoing note");f.addStroke(110.0,recordHistory=true);f.stopDebounce()
            ready(f.editor.prepareReference(target,selectedUri=f.sourceUri))
        }
        f.beforeTargetWrite={seedCanvas(f.source,changed)}
        withContext(Dispatchers.Main) {
            val result=opened(f.editor.openReference(request))
            assertSame(f.editor,result.pane);assertTrue(f.editor.canvasOpen)
            assertEquals(target.documentId,f.editor.infinite.document.identity.documentId)
            assertEquals(1,f.editor.infinite.document.items.size)
            assertEquals(700.0,(f.editor.infinite.document.items.single() as Stroke).samples[0].x,0.0)
            assertEquals(BundleMigration.hash(f.source),f.editor.lastReadSourceVersion()!!.sha256)
            assertFalse(f.editor.infinite.document.dirty);assertFalse(f.editor.infinite.history.canUndo)
        }
        assertEquals(1,f.writes.get());assertEquals(0,f.deletes.get())
    }
    @Test fun rawUnknownItemsOrMissingImagesCannotAdoptAPartiallyDecodedCanvasOrGrantANewEditSource() {
        for(mode in listOf("unknown-kind","missing-image")) runParkingScenario {f->
            val source=withContext(Dispatchers.IO) {
                f.seed()
                val canvas=seedCanvas(f.target)
                val entries=java.util.zip.ZipFile(f.target).use {zip->zip.entries().asSequence().map {entry->
                    entry.name to zip.getInputStream(entry).use {it.readBytes()}
                }.toList()}
                val item=if(mode=="unknown-kind") org.json.JSONObject().put("kind","future-shape")
                    else org.json.JSONObject().put("kind","image").put("asset","assets/image-missing.png")
                        .put("rect",org.json.JSONArray(listOf(0,0,20,20))).put("src_w",20).put("src_h",20)
                val replacement=File(f.root,"raw-canvas-replacement.zip")
                java.util.zip.ZipOutputStream(replacement.outputStream()).use {zip->
                    for((name,bytes) in entries) {
                        zip.putNextEntry(java.util.zip.ZipEntry(name))
                        val actual=if(name=="manifest.json") org.json.JSONObject(bytes.toString(Charsets.UTF_8))
                            .put("items",org.json.JSONArray().put(item)).toString().toByteArray(Charsets.UTF_8) else bytes
                        zip.write(actual);zip.closeEntry()
                    }
                }
                replacement.copyTo(f.target,overwrite=true);replacement.delete()
                canvas
            }
            val rawBytes=f.target.readBytes();val outgoingBytes=f.source.readBytes()
            withContext(Dispatchers.Main) {
                f.editor.openAsync(f.sourceUri,"Outgoing note")
                f.addStroke(120.0,recordHistory=true);f.stopDebounce()
                val model=f.editor.state.document;val history=f.editor.history;val revision=model.bodyRevision
                val noteSource=Editor::class.java.getDeclaredField("noteEditSource").apply {isAccessible=true}
                val canvasSource=Editor::class.java.getDeclaredField("canvasEditSource").apply {isAccessible=true}
                val previousNote=noteSource.get(f.editor);val previousCanvas=canvasSource.get(f.editor)
                val request=ready(f.editor.prepareReference(NativeReferenceTarget(source.identity.documentId),selectedUri=f.targetUri))
                assertEquals(DocumentKind.CANVAS,request.kind)
                var committed=0
                val result=f.editor.openReference(request,onOpened={committed++})
                assertTrue("$mode must reject partial Canvas decoding: $result",result is NativeReferenceResult.Failed)
                assertEquals(0,committed);assertSame(model,f.editor.state.document);assertSame(history,f.editor.history)
                assertEquals(revision,model.bodyRevision);assertTrue(model.dirty);assertTrue(history.canUndo)
                assertEquals(1,model.pages.first().items.filterIsInstance<Stroke>().size)
                assertTrue(f.editor.noteOpen);assertFalse(f.editor.canvasOpen);assertFalse(f.editor.inSplit)
                assertSame(previousNote,noteSource.get(f.editor));assertSame(previousCanvas,canvasSource.get(f.editor))
            }
            assertArrayEquals(rawBytes,f.target.readBytes());assertArrayEquals(outgoingBytes,f.source.readBytes())
            assertEquals(0,f.writes.get());assertEquals(0,f.writeOpens.get());assertEquals(0,f.deletes.get())
        }
    }
    @Test fun finalRereadRejectsSameIdentityCanvasThatBecameIncompleteDuringTheOutgoingSave() {
        for(mode in listOf("unknown-kind","missing-image")) runParkingScenario {f->
            val source=withContext(Dispatchers.IO) {f.seed(f.target);seedCanvas(f.source)}
            lateinit var model:Document
            lateinit var history:com.xnotes.core.history.History
            val request=withContext(Dispatchers.Main) {
                f.editor.openAsync(f.targetUri,"Outgoing note")
                f.addStroke(120.0,recordHistory=true);f.stopDebounce()
                model=f.editor.state.document;history=f.editor.history
                ready(f.editor.prepareReference(NativeReferenceTarget(source.identity.documentId),selectedUri=f.sourceUri))
            }
            var lateBytes:ByteArray?=null
            val changed=AtomicInteger()
            // First Canvas decoding has finished before settling the actual outgoing note starts this write.
            f.beforeTargetWrite={
                changed.incrementAndGet()
                val entries=java.util.zip.ZipFile(f.source).use {zip->zip.entries().asSequence().map {entry->
                    entry.name to zip.getInputStream(entry).use {it.readBytes()}
                }.toList()}
                val item=if(mode=="unknown-kind") org.json.JSONObject().put("kind","future-shape")
                    else org.json.JSONObject().put("kind","image").put("asset","assets/image-missing.png")
                        .put("rect",org.json.JSONArray(listOf(0,0,20,20))).put("src_w",20).put("src_h",20)
                val replacement=File(f.root,"late-raw-canvas.zip")
                java.util.zip.ZipOutputStream(replacement.outputStream()).use {zip->
                    for((name,bytes) in entries) {
                        zip.putNextEntry(java.util.zip.ZipEntry(name))
                        val actual=if(name=="manifest.json") org.json.JSONObject(bytes.toString(Charsets.UTF_8))
                            .put("items",org.json.JSONArray().put(item)).toString().toByteArray(Charsets.UTF_8) else bytes
                        zip.write(actual);zip.closeEntry()
                    }
                }
                replacement.copyTo(f.source,overwrite=true);replacement.delete()
                lateBytes=f.source.readBytes()
            }
            withContext(Dispatchers.Main) {
                val canvasSource=Editor::class.java.getDeclaredField("canvasEditSource").apply {isAccessible=true}
                val previousCanvas=canvasSource.get(f.editor)
                var committed=0
                val result=f.editor.openReference(request,onOpened={committed++})
                assertTrue("Final re-read must reject incomplete $mode B: $result",result is NativeReferenceResult.Failed)
                assertEquals(0,committed);assertSame(model,f.editor.state.document);assertSame(history,f.editor.history)
                assertTrue(f.editor.noteOpen);assertFalse(f.editor.canvasOpen);assertFalse(f.editor.inSplit)
                assertSame(previousCanvas,canvasSource.get(f.editor));assertTrue(history.canUndo)
                assertEquals(1,model.pages.first().items.filterIsInstance<Stroke>().size)
                assertFalse("The accepted outgoing save finishes even when source B is rejected",model.dirty)
            }
            assertEquals(1,changed.get());assertNotNull(lateBytes)
            assertArrayEquals(lateBytes!!,f.source.readBytes())
            assertEquals(source.identity.documentId,BundleHeader.read(f.source).identity!!.documentId)
            assertEquals(1,f.writes.get());assertEquals(0,f.deletes.get())
        }
    }
    @Test fun aLiveCanvasRequestRejectsLateModelReplacementAndDuplicateLocationWithoutTouchingDirtyHistory() {
        for(duplicate in listOf(false,true)) runParkingScenario {f->
            withContext(Dispatchers.IO) {seedCanvas(f.target)}
            withContext(Dispatchers.Main) {
                assertTrue(f.editor.openCanvasAsync(f.targetUri,"Canvas"));val model=f.editor.infinite.document;add(f.editor)
                val request=ready(f.editor.prepareReference(NativeReferenceTarget(model.identity.documentId)))
                if(duplicate) withContext(Dispatchers.IO) {
                    f.target.copyTo(f.source,overwrite=true)
                    f.storage.registry.registerObserved(BundleHeader.read(f.source),"xcanvas","Duplicate",f.sourceUri)
                } else {
                    // The real InfiniteEditor replacement retires the request even if UUID is reused.
                    f.editor.infinite.replaceDocument(InfiniteDocument().apply {identity=model.identity})
                }
                var commits=0
                val result=f.editor.openReference(request,onOpened={commits++})
                assertTrue(result is NativeReferenceResult.Failed);assertEquals(0,commits)
                if(duplicate) {
                    assertSame(model,f.editor.infinite.document);assertTrue(model.dirty);assertTrue(f.editor.infinite.history.canUndo)
                    assertEquals(NativeReferenceIssue.DUPLICATE,(result as NativeReferenceResult.Failed).issue)
                } else assertNotSame(model,f.editor.infinite.document)
            }
            assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
        }
    }
}
