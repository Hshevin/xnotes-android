package com.xnotes.pen

import android.net.Uri
import android.view.InputDevice
import android.view.MotionEvent
import com.xnotes.core.geometry.Pt
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.infinite.Waypoint
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.format.BundleHeader
import com.xnotes.format.CanvasCodec
import com.xnotes.mindmap.*
import com.xnotes.persistNativeForMapPresentation
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.platform.LegacySessionQueue
import com.xnotes.storage.NativeReferenceIssue
import com.xnotes.ui.Editor
import com.xnotes.ui.NativeReferenceResult
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual Canvas files, Editor actors, source tickets, parking and Main's presentation consumer. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class MapCanvasSourceNavigatorHostTest {
    private class Fixture(val native:EditorPageCopyFixture) {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val workspace=MapWorkspace(native.context,scope)
        val binding=Any()
        val file=File(native.root,"canvas-source-navigation.xmap")
        val codec=CanvasCodec(AndroidImageCodec(),AndroidTextMeasurer())
        lateinit var entry:MapWorkspace.Entry
        lateinit var navigator:MapSourceNavigator
        lateinit var source:MapSource
        var opened=0
        var retained=false
        var actual:NativeReferenceResult.Opened?=null
        suspend fun start(warm:Boolean,secondEntry:Boolean) {
            val canvas=InfiniteDocument(lastView=Waypoint("Stored view",240.0,-120.0,1.15))
            canvas.add(Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),listOf(Sample(120.0,160.0,.4),Sample(180.0,190.0,.8))))
            native.targetDisplayName="Actual drawing.xcanvas"
            withContext(Dispatchers.IO) {
                native.target.outputStream().use {codec.write(canvas,it)}
                native.storage.registry.registerObserved(BundleHeader.read(native.target),"xcanvas",native.targetDisplayName,native.targetUri)
            }
            if(warm) withContext(Dispatchers.Main) {
                assertTrue(native.editor.openCanvasAsync(native.targetUri,native.targetDisplayName))
                layoutCanvas()
            }
            source=MapSource(documentId=canvas.identity.documentId,fileName=native.targetDisplayName,scope=SourceScope.FILE,note="First Canvas remark")
            val sources=if(secondEntry) listOf(source,source.copy(id=UUID.randomUUID().toString(),note="Specific second Canvas remark")) else listOf(source)
            file.writeBytes(MapCodec.encode(MindMapDocument(nodes=listOf(MapNode(id="root",parent=null,text="Map"),
                MapNode(id="canvas",parent="root",kind=NodeKind.REFERENCE,text="Independent Canvas alias",sources=sources),
                MapNode(id="other",parent=null,free=true,text="Other",x=900.0)))))
            withContext(Dispatchers.Main) {
                workspace.bindHost(binding)
                assertTrue(workspace.open(Uri.fromFile(file),"Canvas source graph"))
                navigator=MapSourceNavigator(native.editor,workspace,binding,scope,guard={_,_->MapSourceGuardResult.Ready(emptySet())},
                    onOpened={result,kept->opened++;actual=result;retained=kept})
            }
            await {workspace.visible && !workspace.opening}
            withContext(Dispatchers.Main) {entry=workspace.current!!;entry.presentation.synchronize(entry.session);entry.session.selected="canvas"}
        }
        fun layoutCanvas() {
            val canvas=native.editor.infinite
            canvas.view.layout(0,0,1000,800);canvas.viewport.widthPx=1000;canvas.viewport.heightPx=800
            canvas.pad.frontBuffering=false;canvas.armTool(Tool.PEN)
        }
        fun source(index:Int=0)=entry.session.document!!.node("canvas").sources[index]
        fun open(index:Int=0):Boolean {
            val source=source(index)
            val ticket=requireNotNull(entry.session.captureSourceNavigation("canvas",source.id,source.target))
            assertEquals(SourceScope.FILE,ticket.target.scope);assertNull(ticket.target.pageId);assertNull(ticket.target.region)
            val bookmark=requireNotNull(entry.session.captureSourceReturn(ticket))
            entry.presentation.parkSourcePanel()
            return navigator.open(entry,ticket,bookmark)
        }
        fun mapView()=MindMapView(native.context).apply {
            document=entry.session.document!!;documentEpoch=entry.session.interactionEpoch;automaticInitialFit=false
            layout(0,0,1000,700)
        }
        suspend fun await(check:()->Boolean)=withTimeout(10000L) {while(!withContext(Dispatchers.Main) {check()}) delay(1)}
        suspend fun finish()=withContext(NonCancellable) {
            val closed=withContext(Dispatchers.Main) {if(this@Fixture::navigator.isInitialized) navigator.dispose();workspace.dispose()}
            closed.join();scope.cancel()
        }
    }
    private fun scenario(warm:Boolean=false,secondEntry:Boolean=false,block:suspend(Fixture)->Unit)=runParkingScenario {native->
        val f=Fixture(native);try {f.start(warm,secondEntry);block(f)} finally {f.finish()}
    }
    private fun stopDebounce(editor:Editor) {
        (Editor::class.java.getDeclaredField("canvasDebounceJob").apply {isAccessible=true}.get(editor) as? Job)?.cancel()
    }
    private suspend fun drain(editor:Editor) {
        repeat(3) {
            parkingDispatch()
            val jobs=withContext(Dispatchers.Main) {listOf("noteWriteJob","canvasWriteJob","sessionWriteJob").mapNotNull {
                Editor::class.java.getDeclaredField(it).apply {isAccessible=true}.get(editor) as? Job}}
            jobs.forEach {it.join()}
            (Editor::class.java.getDeclaredField("sessionOwner").apply {isAccessible=true}.get(editor) as LegacySessionQueue.Owner).awaitAccepted()
        }
    }
    private var time=100L
    private fun pen(editor:Editor,action:Int,point:Pt) {
        val at=editor.infinite.viewport.contentToViewport(point)
        val pointer=MotionEvent.PointerProperties().apply {id=7;toolType=MotionEvent.TOOL_TYPE_STYLUS}
        val coordinates=MotionEvent.PointerCoords().apply {x=at.x.toFloat();y=at.y.toFloat();pressure=.7f}
        val event=MotionEvent.obtain(100,time++,action,1,arrayOf(pointer),arrayOf(coordinates),0,0,1f,1f,0,0,InputDevice.SOURCE_STYLUS,0)
        try {assertTrue(editor.infinite.view.onTouchEvent(event))} finally {event.recycle()}
    }
    private fun draw(editor:Editor) {
        pen(editor,MotionEvent.ACTION_DOWN,Pt(300.0,210.0));pen(editor,MotionEvent.ACTION_MOVE,Pt(330.0,240.0))
        pen(editor,MotionEvent.ACTION_UP,Pt(370.0,260.0));stopDebounce(editor)
    }

    @Test fun aSingleReferenceTicketColdOpensTheActualWholeCanvasAndReturnsToTheSameMapCameraAndSource()=scenario {f->
        val editor=f.native.editor;val original=f.native.target.readBytes();val mapBytes=f.file.readBytes();val body=f.entry.session.document!!
        lateinit var camera:MindMapView.Viewport
        withContext(Dispatchers.Main) {
            assertFalse(editor.noteOpen)
            val view=f.mapView();view.zoomBy(1.4f);view.panText(83f,-59f);camera=view.viewport()
            assertTrue(f.entry.presentation.showSources(f.entry.session,view,"canvas"));assertTrue(f.open())
        }
        f.await {!f.navigator.busy && !f.workspace.visible}
        withContext(Dispatchers.Main) {
            assertEquals(1,f.opened);assertFalse(f.retained);assertTrue(editor.canvasOpen);assertFalse(editor.inSplit)
            assertSame(editor,f.actual!!.pane);assertEquals(f.source.documentId,editor.infinite.document.identity.documentId)
            assertEquals(f.native.targetUri,editor.infinite.document.path);assertEquals(1,editor.infinite.document.items.filterIsInstance<Stroke>().size)
            assertSame(f.entry,f.workspace.current);assertSame(body,f.entry.session.document)
            assertEquals(f.source.id,f.entry.sourceReturn!!.source.sourceId);assertTrue(f.navigator.returnToMap())
        }
        f.await {!f.navigator.busy && f.workspace.visible}
        withContext(Dispatchers.Main) {
            assertSame(f.entry,f.workspace.current);assertEquals("canvas",f.entry.session.selected)
            assertEquals("canvas",f.entry.presentation.sourcesFor);assertEquals(f.source.id,f.entry.presentation.sourceFocus!!.sourceId)
            val returned=f.mapView();assertTrue(f.entry.presentation.restore(f.entry.session,returned));assertEquals(camera,returned.viewport())
            persistNativeForMapPresentation(editor,f.entry)
        }
        drain(editor)
        assertArrayEquals(original,f.native.target.readBytes());assertArrayEquals(mapBytes,f.file.readBytes());assertEquals(0,f.native.writes.get());assertEquals(0,f.native.writeOpens.get())
    }

    @Test fun aDirtyLiveCanvasKeepsItsActualModelHistoryAndViewportAndTheReturnConsumerCannotPersistIt()=scenario(warm=true,secondEntry=true) {f->
        val editor=f.native.editor;val model=editor.infinite.document;val history=editor.infinite.history;val original=f.native.target.readBytes()
        lateinit var undo:com.xnotes.core.history.Command
        withContext(Dispatchers.Main) {
            draw(editor);undo=history.nextUndo!!
            // This fixture has no granted folder; enable the fixture's managed-file destination so
            // an accidental Main.persist() would actually write and clear this dirty model.
            Editor::class.java.getDeclaredField("canvasAutosaveUri").apply {isAccessible=true}.set(editor,f.native.targetUri)
            editor.infinite.viewport.zoom=1.7;editor.infinite.viewport.scrollX=-51.0;editor.infinite.viewport.scrollY=73.0
            assertTrue(f.open(1))
        }
        f.await {!f.navigator.busy && !f.workspace.visible}
        withContext(Dispatchers.Main) {
            assertTrue(f.retained);assertSame(editor,f.actual!!.pane);assertSame(model,editor.infinite.document);assertSame(history,editor.infinite.history)
            assertSame(undo,history.nextUndo);assertTrue(model.dirty);assertTrue(history.canUndo)
            assertEquals(Triple(1.7,-51.0,73.0),Triple(editor.infinite.viewport.zoom,editor.infinite.viewport.scrollX,editor.infinite.viewport.scrollY))
            assertEquals(f.source(1).id,f.entry.sourceReturn!!.source.sourceId);assertTrue(f.navigator.returnToMap())
        }
        f.await {!f.navigator.busy && f.workspace.visible}
        withContext(Dispatchers.Main) {
            assertTrue("Canvas returns retain the ordinary map's base flush policy",f.entry.flushNativeOnPresent)
            persistNativeForMapPresentation(editor,f.entry)
            assertEquals(f.source(1).id,f.entry.presentation.sourceFocus!!.sourceId)
        }
        drain(editor)
        withContext(Dispatchers.Main) {
            assertSame(model,editor.infinite.document);assertSame(history,editor.infinite.history);assertSame(undo,history.nextUndo)
            assertTrue(model.dirty);assertTrue(history.canUndo);assertEquals(2,model.items.filterIsInstance<Stroke>().size)
            assertEquals(Triple(1.7,-51.0,73.0),Triple(editor.infinite.viewport.zoom,editor.infinite.viewport.scrollX,editor.infinite.viewport.scrollY))
        }
        assertArrayEquals(original,f.native.target.readBytes());assertEquals(0,f.native.writes.get());assertEquals(0,f.native.writeOpens.get())
    }

    @Test fun returningWaitsForTheActualCanvasPenAndCancellingTheWaitPreservesItsStrokeAndBookmark()=scenario(warm=true) {f->
        val editor=f.native.editor;val model=editor.infinite.document;val original=f.native.target.readBytes()
        withContext(Dispatchers.Main) {assertTrue(f.open())};f.await {!f.navigator.busy && !f.workspace.visible}
        withContext(Dispatchers.Main) {
            pen(editor,MotionEvent.ACTION_DOWN,Pt(300.0,210.0));pen(editor,MotionEvent.ACTION_MOVE,Pt(340.0,230.0))
            assertTrue(editor.stylusInContact);assertTrue(editor.infinite.hasPendingContentTransaction);assertTrue(f.navigator.returnToMap())
        }
        parkingDispatch()
        withContext(Dispatchers.Main) {
            assertTrue(f.navigator.busy);assertFalse(f.workspace.visible);f.navigator.cancel()
            assertTrue(editor.stylusInContact);assertTrue(editor.infinite.hasPendingContentTransaction);assertNotNull(f.entry.sourceReturn)
            pen(editor,MotionEvent.ACTION_UP,Pt(380.0,260.0));stopDebounce(editor);Editor::class.java.getDeclaredField("canvasAutosaveUri").apply {isAccessible=true}.set(editor,f.native.targetUri)
            assertTrue(f.navigator.returnToMap())
        }
        f.await {!f.navigator.busy && f.workspace.visible}
        withContext(Dispatchers.Main) {persistNativeForMapPresentation(editor,f.entry)}
        drain(editor)
        withContext(Dispatchers.Main) {
            assertSame(model,editor.infinite.document);assertEquals(2,model.items.filterIsInstance<Stroke>().size)
            assertTrue(model.dirty);assertTrue(editor.infinite.history.canUndo)
            assertEquals(f.source.id,f.entry.presentation.sourceFocus!!.sourceId)
        }
        assertArrayEquals(original,f.native.target.readBytes());assertEquals(0,f.native.writes.get())
    }

    @Test fun theNextOrdinaryMapOpenRestoresItsNormalNativePersistencePolicy()=scenario(warm=true) {f->
        val editor=f.native.editor;val original=f.native.target.readBytes();val model=editor.infinite.document
        withContext(Dispatchers.Main) {draw(editor);Editor::class.java.getDeclaredField("canvasAutosaveUri").apply {isAccessible=true}.set(editor,f.native.targetUri);assertTrue(f.open())}
        f.await {!f.navigator.busy && !f.workspace.visible}
        withContext(Dispatchers.Main) {assertTrue(f.navigator.returnToMap())};f.await {!f.navigator.busy && f.workspace.visible}
        withContext(Dispatchers.Main) {persistNativeForMapPresentation(editor,f.entry)};drain(editor)
        assertArrayEquals(original,f.native.target.readBytes());assertEquals(0,f.native.writes.get())
        withContext(Dispatchers.Main) {
            assertTrue(f.workspace.hide(f.entry));f.navigator.retire()
            assertTrue(f.workspace.open(f.entry.uri,f.entry.title,preparePresentation={editor.awaitHostParking()?.let {proof->({proof.current()})}},
                onPresented={persistNativeForMapPresentation(editor,it)}))
        }
        f.await {f.workspace.visible && !f.workspace.opening};drain(editor)
        withContext(Dispatchers.Main) {assertSame(model,editor.infinite.document);assertFalse(model.dirty);assertTrue(editor.infinite.history.canUndo)}
        assertEquals(1,f.native.writes.get());assertFalse(original.contentEquals(f.native.target.readBytes()))
    }
    @Test fun aCanvasPageSourceIsRejectedWithoutDiscardingItsMapOrChangingItsOpenCanvas()=scenario(warm=true) {f->
        val editor=f.native.editor;val model=editor.infinite.document;val original=f.native.target.readBytes()
        withContext(Dispatchers.Main) {
            assertTrue(f.entry.session.operate {doc->doc.update("canvas") {node->node.copy(sources=node.sources.map {
                it.copy(scope=SourceScope.PAGE,pageId=UUID.randomUUID().toString())
            })}})
            f.entry.session.cancelPendingAutosave()
            val source=f.source();val ticket=f.entry.session.captureSourceNavigation("canvas",source.id)!!
            assertTrue(f.navigator.open(f.entry,ticket,f.entry.session.captureSourceReturn(ticket)!!))
        }
        f.await {!f.navigator.busy}
        withContext(Dispatchers.Main) {
            assertTrue(f.workspace.visible);assertSame(f.entry,f.workspace.current);assertSame(model,editor.infinite.document)
            assertTrue(editor.canvasOpen);assertEquals(0,f.opened);assertNull(f.entry.sourceReturn)
            assertEquals(NativeReferenceIssue.UNSUPPORTED,f.navigator.failure)
            assertEquals("canvas",f.entry.presentation.sourcesFor)
        }
        assertArrayEquals(original,f.native.target.readBytes());assertEquals(0,f.native.writes.get())
    }

}
