package com.xnotes.pen

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import com.xnotes.ui.NativeDestinationPicker
import com.xnotes.core.infinite.AddCanvasItem
import android.provider.DocumentsContract
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.InfiniteDocument
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
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver

/** Actual Canvas selection → shared target flow → provider → cold map → Canvas source → return. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CanvasRegionToMapFlowHostTest {
    private class Maps(val root:File):ContentProvider() {
        var reject=false
        fun file(uri:Uri)=File(root,"${uri.lastPathSegment}.xmap")
        override fun onCreate()=true
        override fun getType(uri:Uri)="application/json"
        override fun query(uri:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,order:String?):Cursor=
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)).apply {addRow(arrayOf(file(uri).name,file(uri).length()))}
        override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
            if('w' in mode && reject) throw java.io.IOException("Map write rejected")
            return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.parseMode(mode))
        }
        override fun insert(uri:Uri,v:ContentValues?):Uri?=null
        override fun update(uri:Uri,v:ContentValues?,s:String?,a:Array<out String>?)=0
        override fun delete(uri:Uri,s:String?,a:Array<out String>?):Int=error("A reference flow cannot delete its target")
    }
    private class CopyRegistry(private val context:Context):ActivityResultRegistry() {
        val launches=mutableListOf<Int>()
        override fun <I,O> onLaunch(code:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?) {
            val intent=contract.createIntent(context,input)
            assertEquals(Intent.ACTION_CREATE_DOCUMENT,intent.action);assertEquals("application/octet-stream",intent.type)
            assertTrue(intent.getStringExtra(Intent.EXTRA_TITLE)!!.endsWith(".xcanvas"));launches+=code
        }
        fun result(uri:Uri?) {assertTrue(dispatchResult(launches.last(),if(uri==null) Activity.RESULT_CANCELED else Activity.RESULT_OK,uri?.let {Intent().setData(it)}))}
    }
    private class Fixture(val native:EditorPageCopyFixture) {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val provider=Maps(native.root)
        val authority="canvas-region-map-${UUID.randomUUID()}"
        val target=Uri.parse("content://$authority/document/existing")
        val fresh=Uri.parse("content://$authority/document/fresh")
        val flows=mutableListOf<PageToMapController>()
        val workspace=MapWorkspace(native.context,scope)
        val binding=Any()
        lateinit var navigator:MapSourceNavigator
        var fixed:Rect?=null
        suspend fun start() {
            provider.attachInfo(native.context,ProviderInfo().apply {authority=this@Fixture.authority})
            ShadowContentResolver.registerProviderInternal(authority,provider)
            provider.file(target).writeBytes(MapCodec.encode(MindMapDocument(nodes=listOf(MapNode(id="root",parent=null),
                MapNode(id="concept",parent="root",text="Keep this body")))))
            provider.file(fresh).writeBytes(byteArrayOf())
            val canvas=InfiniteDocument()
            canvas.add(Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),listOf(Sample(-250.0,-180.0,.5),Sample(-150.0,-80.0,.8))))
            canvas.add(Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),listOf(Sample(1000.0,900.0,.5),Sample(1100.0,1000.0,.8))))
            val codec=CanvasCodec(AndroidImageCodec(),AndroidTextMeasurer())
            withContext(Dispatchers.IO) {
                native.target.outputStream().use {codec.write(canvas,it)}
                native.targetDisplayName="Region source.xcanvas"
                native.storage.registry.registerObserved(BundleHeader.read(native.target),"xcanvas",native.targetDisplayName,native.targetUri)
            }
            withContext(Dispatchers.Main) {
                assertTrue(native.editor.openCanvasAsync(native.targetUri,native.targetDisplayName))
                val infinite=native.editor.infinite
                infinite.view.layout(0,0,1000,800);infinite.viewport.widthPx=1000;infinite.viewport.heightPx=800
                infinite.selection.select(listOf(infinite.document.items.first()))
                fixed=requireNotNull(infinite.sourceSelectionRegion())
                workspace.bindHost(binding)
                navigator=MapSourceNavigator(native.editor,workspace,binding,scope,guard={_,_->MapSourceGuardResult.Ready(emptySet())})
            }
        }
        suspend fun flow():PageToMapController=withContext(Dispatchers.Main) {
            assertTrue(native.editor.requestCanvasRegionReference(requireNotNull(fixed)))
            PageToMapController(native.context,requireNotNull(native.editor.canvasRegionReferenceRequest),scope).also {flows+=it;it.start()}
        }
        suspend fun await(check:()->Boolean)=withTimeout(10000L) {while(!withContext(Dispatchers.Main) {check()}) delay(1)}
        suspend fun close()=withContext(NonCancellable) {
            withContext(Dispatchers.Main) {
                provider.reject=false
                flows.forEach {it.close()?.join()}
                navigator.dispose();workspace.dispose()
            }.join();scope.cancel()
        }
    }
    private fun scenario(block:suspend(Fixture)->Unit)=runParkingScenario {native->
        val f=Fixture(native);try {f.start();block(f)} finally {f.close()}
    }
    @Test fun anActualSelectionAppendsToTheOriginalConceptReopensWithClippedPreviewAndReturnsFromItsExactRegion()=scenario {f->
        val original=f.native.target.readBytes();val model=f.native.editor.infinite.document
        val flow=f.flow();f.await {flow.step==PageToMapStep.DESTINATION}
        withContext(Dispatchers.Main) {assertTrue(flow.selectMap(f.target))}
        f.await {flow.step==PageToMapStep.TARGET}
        withContext(Dispatchers.Main) {
            assertTrue(flow.chooseExistingNode("concept"));assertTrue(flow.confirm())
        }
        f.await {flow.step==PageToMapStep.ADDED}
        val previewSession=withContext(Dispatchers.Main) {
            val session=flow.targetSession!!;val node=session.document!!.node("concept")
            assertEquals("Keep this body",node.text);assertEquals(1,node.sources.size)
            assertEquals(SourceScope.CANVAS_REGION,node.sources.single().scope);assertEquals(f.fixed,node.sources.single().region)
            session.undo();assertTrue(session.document!!.node("concept").sources.isEmpty())
            session.redo();assertEquals(1,session.document!!.node("concept").sources.size)
            val previewOwner=Any()
            val previewTicket=requireNotNull(session.captureSourcePreview("concept",session.document!!.node("concept").sources.single().id))
            assertTrue(session.sourcePreviews.request(previewOwner,listOf(previewTicket),MapSourcePreviewMode.REFRESH) {true})
            Triple(session,previewOwner,previewTicket)
        }
        // The fixture pumps immediate Main messages; its polling delay belongs on the worker scope.
        f.await {previewSession.first.sourcePreviews.busy(previewSession.second).isEmpty()}
        val saved=withContext(Dispatchers.Main) {
            val session=previewSession.first
            val preview=requireNotNull(session.document!!.node("concept").sources.single().lastPreview)
            assertEquals(previewSession.third.target,preview.target);assertTrue(session.document!!.previewAssets.containsKey(preview.assetKey))
            session.cancelPendingAutosave();assertTrue(session.saveAttempt().await() is MapSaveOutcome.Written)
            session.document!!.also {flow.close()?.join()}
        }
        f.await {MapDocuments.observations(f.native.context).isEmpty()}
        assertEquals(saved,MapCodec.decode(f.provider.file(f.target).readBytes()))
        val source=saved.node("concept").sources.single()
        val preview=withContext(Dispatchers.IO) {MapSourcePreviewBackend.load(f.native.context,listOf(source.target),setOf(source.target)).single()}
        assertEquals(SourceStatus.CURRENT,preview.status);assertNotNull(preview.asset)
        withContext(Dispatchers.Main) {assertTrue(f.workspace.open(f.target,"Region graph"))}
        f.await {f.workspace.visible && !f.workspace.opening}
        val entry=withContext(Dispatchers.Main) {
            f.workspace.current!!.also {e->
                e.session.selected="concept";e.presentation.synchronize(e.session)
                val view=MindMapView(f.native.context).apply {document=e.session.document!!;documentEpoch=e.session.interactionEpoch;layout(0,0,1000,700)}
                view.restoreViewport(MindMapView.Viewport(.8f,73f,-21f),1000,700,view.resources.displayMetrics.density);assertTrue(e.presentation.capture(e.session,view))
                val ticket=e.session.captureSourceNavigation("concept",source.id)!!
                val bookmark=e.session.captureSourceReturn(ticket)!!
                assertTrue(f.navigator.open(e,ticket,bookmark))
            }
        }
        f.await {!f.navigator.busy && !f.workspace.visible}
        withContext(Dispatchers.Main) {
            assertSame(model,f.native.editor.infinite.document)
            assertTrue(f.navigator.returnToMap())
        }
        f.await {!f.navigator.busy && f.workspace.visible}
        withContext(Dispatchers.Main) {
            persistNativeForMapPresentation(f.native.editor,entry)
            assertSame(entry,f.workspace.current);assertEquals("concept",entry.session.selected)
            assertEquals(source.id,entry.presentation.sourceFocus!!.sourceId)
            assertEquals(source.target,entry.session.document!!.node("concept").sources.single().target)
        }
        assertArrayEquals(original,f.native.target.readBytes());assertEquals(0,f.native.writes.get())
    }
    @Test fun explicitSourceSaveKeepsTheFixedRectangleAndANewMapsSaveRetryNeverDuplicatesIt()=scenario {f->
        val editor=f.native.editor;val model=editor.infinite.document;val identity=model.identity;val fixed=f.fixed
        val flow=f.flow();f.await {flow.step==PageToMapStep.DESTINATION}
        withContext(Dispatchers.Main) {
            model.add(Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),listOf(Sample(70.0,60.0,.5),Sample(80.0,90.0,.8))))
            model.dirty=true;editor.infinite.selection.select(listOf(model.items.last()))
            assertTrue(flow.start())
        }
        f.await {flow.step==PageToMapStep.SAVE_REQUIRED}
        withContext(Dispatchers.Main) {
            assertEquals(0,f.native.writes.get());assertTrue(flow.canSaveSourceInPlace)
            assertTrue(flow.saveSource {error("An actual known Canvas must not be redirected to Save As")})
        }
        f.await {flow.step==PageToMapStep.DESTINATION}
        withContext(Dispatchers.Main) {
            assertSame(model,editor.infinite.document);assertEquals(identity,model.identity)
            assertEquals(f.native.targetUri,model.path);assertFalse(model.dirty);assertEquals(1,f.native.writes.get())
            assertEquals(fixed,flow.source!!.region);assertTrue(flow.createMap(f.fresh))
        }
        f.await {flow.step==PageToMapStep.TARGET}
        withContext(Dispatchers.Main) {
            assertTrue(flow.chooseNewReference(false,"Fixed area"));f.provider.reject=true;assertTrue(flow.confirm())
        }
        f.await {flow.step==PageToMapStep.SAVE_FAILED}
        val added=withContext(Dispatchers.Main) {
            flow.targetSession!!.document!!.nodes.single {it.sources.isNotEmpty()}.also {
                assertEquals(fixed,it.sources.single().region);assertEquals(1,it.sources.size)
                f.provider.reject=false;assertTrue(flow.retrySave())
            }
        }
        f.await {flow.step==PageToMapStep.ADDED}
        withContext(Dispatchers.Main) {
            val doc=flow.targetSession!!.document!!
            assertEquals(1,doc.nodes.count {it.sources.isNotEmpty()})
            assertEquals(added,doc.node(added.id));assertEquals(fixed,doc.node(added.id).sources.single().region)
        }
        assertEquals(SourceScope.CANVAS_REGION,MapCodec.decode(f.provider.file(f.fresh).readBytes()).node(added.id).sources.single().scope)
        assertEquals(1,f.native.writes.get());assertEquals(0,f.native.deletes.get())
    }
    @Test fun anUnsavedCanvasUsesTheActualCopyPickerAndItsAdoptedRegionCanOpenAndReturn()=scenario {f->
        val original=f.native.target.readBytes();val editor=f.native.editor
        withContext(Dispatchers.Main) {editor.newCanvas()}
        f.await {editor.canvasOpen && editor.infinite.document.path==null}
        val model=withContext(Dispatchers.Main) {
            editor.infinite.document.also {doc->
                val stroke=Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),listOf(Sample(-100.0,-80.0,.5),Sample(-20.0,-10.0,.8)))
                doc.add(stroke);editor.infinite.history.push(AddCanvasItem(doc,stroke));doc.dirty=true
                editor.infinite.selection.select(listOf(stroke));f.fixed=editor.infinite.sourceSelectionRegion()
            }
        }
        val originalId=model.identity;val history=editor.infinite.history
        val flow=f.flow();f.await {flow.step==PageToMapStep.SAVE_REQUIRED}
        val old=flow.request
        val copy=DocumentsContract.buildDocumentUri(f.native.authority,"retry")
        f.native.retryDisplayName="Copy.xcanvas"
        val registry=CopyRegistry(f.native.context);val picker=NativeDestinationPicker(registry)
        try {
            withContext(Dispatchers.Main) {
                assertFalse(flow.canSaveSourceInPlace);assertTrue(flow.canSaveSourceCopy)
                assertFalse(flow.saveSource {error("Canvas copy must not reach the NOTE-only Main Save As")})
                assertTrue(launchSourceCopy(flow,picker,"Copy.xcanvas"));assertTrue(picker.busy)
                registry.result(copy)
            }
            f.await {editor.canvasRegionReferenceRequest?.id?.let {it!=old.id}==true}
            val continued=withContext(Dispatchers.Main) {
                assertSame(model,editor.infinite.document);assertSame(history,editor.infinite.history);assertTrue(history.canUndo)
                assertEquals(copy.toString(),model.path);assertNotEquals(originalId,model.identity);assertFalse(model.dirty)
                assertEquals(f.fixed,editor.canvasRegionReferenceRequest!!.region);assertFalse(old.current())
                val newRequest=editor.canvasRegionReferenceRequest!!
                flow.close()?.join() // Disposal of the old UI must not clear the newly issued request.
                assertSame(newRequest,editor.canvasRegionReferenceRequest);assertTrue(newRequest.current())
                PageToMapController(f.native.context,newRequest,f.scope).also {f.flows+=it;it.start()}
            }
            f.await {continued.step==PageToMapStep.DESTINATION}
            withContext(Dispatchers.Main) {assertTrue(continued.selectMap(f.target))}
            f.await {continued.step==PageToMapStep.TARGET}
            withContext(Dispatchers.Main) {assertTrue(continued.chooseExistingNode("concept"));assertTrue(continued.confirm())}
            f.await {continued.step==PageToMapStep.ADDED}
            withContext(Dispatchers.Main) {assertTrue(f.workspace.open(f.target,"Copy graph"))}
            f.await {f.workspace.visible && !f.workspace.opening}
            val entry=withContext(Dispatchers.Main) {
                f.workspace.current!!.also {e->
                    val source=e.session.document!!.node("concept").sources.single()
                    assertEquals(model.identity.documentId,source.documentId);assertEquals(f.fixed,source.region)
                    val ticket=e.session.captureSourceNavigation("concept",source.id)!!
                    assertTrue(f.navigator.open(e,ticket,e.session.captureSourceReturn(ticket)!!))
                }
            }
            f.await {!f.navigator.busy && !f.workspace.visible}
            withContext(Dispatchers.Main) {assertSame(model,editor.infinite.document);assertTrue(f.navigator.returnToMap())}
            f.await {!f.navigator.busy && f.workspace.visible}
            withContext(Dispatchers.Main) {assertSame(entry,f.workspace.current);assertSame(history,editor.infinite.history)}
            val copyModel=f.native.retry.inputStream().use {CanvasCodec(AndroidImageCodec(),AndroidTextMeasurer()).read(it)}
            assertEquals(model.identity,copyModel.identity);assertEquals(1,copyModel.items.size)
            assertArrayEquals(original,f.native.target.readBytes());assertEquals(1,f.native.writes.get());assertEquals(0,f.native.deletes.get())
        } finally {withContext(Dispatchers.Main) {picker.dispose()}}
    }
    @Test fun anOldCopyPickerCannotWriteForANewerFixedRegionRequest()=scenario {f->
        val editor=f.native.editor
        withContext(Dispatchers.Main) {editor.newCanvas()}
        f.await {editor.canvasOpen && editor.infinite.document.path==null}
        f.fixed=Rect(-100.0,-80.0,200.0,160.0)
        val flow=f.flow();f.await {flow.step==PageToMapStep.SAVE_REQUIRED}
        val registry=CopyRegistry(f.native.context);val picker=NativeDestinationPicker(registry)
        try {
            withContext(Dispatchers.Main) {
                assertTrue(launchSourceCopy(flow,picker,"Cancelled.xcanvas"));registry.result(null)
                assertEquals(PageToMapStep.SAVE_REQUIRED,flow.step);assertTrue(flow.request.current())
                assertTrue(launchSourceCopy(flow,picker,"Old.xcanvas"))
                flow.close()
                assertTrue(editor.requestCanvasRegionReference(Rect(900.0,800.0,120.0,90.0)))
                val latest=editor.canvasRegionReferenceRequest!!
                registry.result(DocumentsContract.buildDocumentUri(f.native.authority,"retry"))
                assertSame(latest,editor.canvasRegionReferenceRequest);assertTrue(latest.current());assertNull(editor.infinite.document.path)
            }
            assertEquals(0L,f.native.retry.length());assertEquals(0,f.native.writes.get());assertEquals(0,f.native.deletes.get())
        } finally {withContext(Dispatchers.Main) {picker.dispose()}}
    }
}
