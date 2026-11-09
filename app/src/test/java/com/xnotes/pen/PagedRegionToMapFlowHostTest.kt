package com.xnotes.pen

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.xnotes.canvas.PageSourceRegion
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Document
import com.xnotes.core.model.fixedWriteSnapshot
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.mindmap.*
import com.xnotes.requestPagedRegionReference
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

/** Actual paged menu consumer → saved page identity/range → one map actor → preview → source/return. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PagedRegionToMapFlowHostTest {
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
        override fun delete(uri:Uri,s:String?,a:Array<out String>?):Int=error("References cannot delete their map")
    }
    private class Fixture(val native:EditorPageCopyFixture) {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val provider=Maps(native.root)
        val authority="paged-region-map-${UUID.randomUUID()}"
        val target=Uri.parse("content://$authority/document/existing")
        val fresh=Uri.parse("content://$authority/document/fresh")
        val flows=mutableListOf<PageToMapController>()
        val workspace=MapWorkspace(native.context,scope)
        val binding=Any()
        lateinit var navigator:MapSourceNavigator
        lateinit var capture:PageSourceRegion
        val originalOther=MapSource(documentId=UUID.randomUUID().toString(),fileName="Other note")
        suspend fun start() {
            provider.attachInfo(native.context,ProviderInfo().apply {authority=this@Fixture.authority})
            ShadowContentResolver.registerProviderInternal(authority,provider)
            provider.file(target).writeBytes(MapCodec.encode(MindMapDocument(nodes=listOf(MapNode(id="root",parent=null),
                MapNode(id="concept",parent="root",text="Keep the body",sources=listOf(originalOther),primarySource=originalOther.id)))))
            provider.file(fresh).writeBytes(byteArrayOf())
            withContext(Dispatchers.IO) {
                native.seed(native.target)
                val doc=native.target.inputStream().use {native.codec.read(it)}
                doc.pages[1].items+=Stroke(Tool.SPEED,ToolDefaults.configFor(Tool.SPEED),listOf(Sample(40.0,70.0,.5,10.0),Sample(130.0,130.0,.8,20.0)))
                native.target.outputStream().use {native.codec.write(doc.fixedWriteSnapshot(),it)}
            }
            withContext(Dispatchers.Main) {
                native.editor.openAsync(native.targetUri,"Region.xnote");assertTrue(native.editor.noteOpen);assertFalse(native.editor.canvasOpen)
                native.editor.view.layout(0,0,1000,1000)
                native.editor.controller.selectAll()
                capture=requireNotNull(native.editor.sourceSelectionRegion())
                assertSame(native.editor.state.document.pages[1],capture.page)
                workspace.bindHost(binding)
                navigator=MapSourceNavigator(native.editor,workspace,binding,scope,guard={_,_->MapSourceGuardResult.Ready(emptySet())})
            }
        }
        suspend fun flow():PageToMapController=withContext(Dispatchers.Main) {
            assertTrue(requestPagedRegionReference(native.editor,capture.document,capture))
            PageToMapController(native.context,requireNotNull(native.editor.pageReferenceRequest),scope).also {flows+=it;it.start()}
        }
        suspend fun await(check:()->Boolean)=withTimeout(10000L) {while(!withContext(Dispatchers.Main) {check()}) delay(1)}
        suspend fun close()=withContext(NonCancellable) {
            withContext(Dispatchers.Main) {
                provider.reject=false;flows.forEach {it.close()?.join()}
                if(::navigator.isInitialized) navigator.dispose()
                workspace.dispose()
            }.join();scope.cancel()
        }
    }
    private fun scenario(block:suspend(Fixture)->Unit)=runParkingScenario {native->
        val f=Fixture(native);try {f.start();block(f)} finally {f.close()}
    }
    @Test fun theActualSelectedPageSurvivesReorderingAndReopensItsRangePreviewAndSourceReturn()=scenario {f->
        val model=f.capture.document;val page=f.capture.page;val region=f.capture.region
        val flow=f.flow();f.await {flow.step==PageToMapStep.DESTINATION}
        withContext(Dispatchers.Main) {
            assertTrue(f.native.editor.movePages(listOf(page),0))
            f.native.editor.controller.clearSelection()
            assertTrue(flow.selectMap(f.target))
        }
        f.await {flow.step==PageToMapStep.TARGET}
        withContext(Dispatchers.Main) {assertTrue(flow.chooseExistingNode("concept"));assertTrue(flow.confirm())}
        f.await {flow.step==PageToMapStep.ADDED}
        val previewWork=withContext(Dispatchers.Main) {
            val session=flow.targetSession!!;val node=session.document!!.node("concept")
            assertEquals("Keep the body",node.text);assertEquals(f.originalOther,node.sources.first());assertEquals(2,node.sources.size)
            assertEquals(f.originalOther.id,node.primarySource)
            val source=node.sources.last();assertEquals(SourceScope.PAGE,source.scope)
            assertEquals(page.referenceIdentity.id,source.pageId);assertEquals(region,source.region);assertEquals(1,source.lastKnownPage)
            session.undo();assertEquals(listOf(f.originalOther),session.document!!.node("concept").sources)
            session.redo();assertEquals(2,session.document!!.node("concept").sources.size)
            val ticket=session.captureSourcePreview("concept",source.id)!!;val owner=Any()
            assertTrue(session.sourcePreviews.request(owner,listOf(ticket),MapSourcePreviewMode.REFRESH) {true})
            Triple(session,owner,source.id)
        }
        f.await {previewWork.first.sourcePreviews.busy(previewWork.second).isEmpty()}
        val saved=withContext(Dispatchers.Main) {
            val session=previewWork.first;val source=session.document!!.node("concept").sources.last()
            assertNotNull(source.lastPreview);assertTrue(session.document!!.previewAssets.containsKey(source.lastPreview!!.assetKey))
            session.cancelPendingAutosave();assertTrue(session.saveAttempt().await() is MapSaveOutcome.Written)
            session.document!!.also {flow.close()?.join()}
        }
        f.await {MapDocuments.observations(f.native.context).isEmpty()}
        assertEquals(saved,MapCodec.decode(f.provider.file(f.target).readBytes()))
        withContext(Dispatchers.Main) {assertTrue(f.workspace.open(f.target,"Region graph"))}
        f.await {f.workspace.visible && !f.workspace.opening}
        val entry=withContext(Dispatchers.Main) {
            f.workspace.current!!.also {e->
                val source=e.session.document!!.node("concept").sources.last()
                val view=MindMapView(f.native.context).apply {document=e.session.document!!;documentEpoch=e.session.interactionEpoch;layout(0,0,1000,700)}
                e.session.selected="concept";e.presentation.synchronize(e.session)
                view.restoreViewport(MindMapView.Viewport(.7f,51f,-33f),1000,700,view.resources.displayMetrics.density)
                assertTrue(e.presentation.capture(e.session,view))
                val ticket=e.session.captureSourceNavigation("concept",source.id)!!
                assertTrue(f.navigator.open(e,ticket,e.session.captureSourceReturn(ticket)!!))
            }
        }
        f.await {!f.navigator.busy && !f.workspace.visible}
        withContext(Dispatchers.Main) {
            assertSame(model,f.native.editor.state.document);assertSame(page,f.native.editor.state.document.pages.first())
            assertTrue(f.navigator.returnToMap())
        }
        f.await {!f.navigator.busy && f.workspace.visible}
        withContext(Dispatchers.Main) {
            assertSame(entry,f.workspace.current);assertEquals("concept",entry.session.selected)
            assertEquals(previewWork.third,entry.presentation.sourceFocus!!.sourceId)
            assertEquals(region,entry.session.document!!.node("concept").sources.last().region)
        }
        val stored=f.native.target.inputStream().use {f.native.codec.read(it)}
        assertEquals(page.referenceIdentity.id,stored.pages.first().referenceIdentity.id)
        val stroke=stored.pages.first().items.single() as Stroke
        assertEquals(.5,stroke.pAt(0),.0001);assertEquals(10.0,stroke.tAt(0),.0001)
        assertEquals(1,f.native.writes.get());assertEquals(0,f.native.deletes.get())
    }
    @Test fun aNewMapsSaveFailureRetriesOnlyItsOneFixedRegionAndAnOldMenuCannotBindANewModel()=scenario {f->
        val original=f.native.target.readBytes();val flow=f.flow();f.await {flow.step==PageToMapStep.DESTINATION}
        withContext(Dispatchers.Main) {assertTrue(flow.createMap(f.fresh))}
        f.await {flow.step==PageToMapStep.TARGET}
        withContext(Dispatchers.Main) {assertTrue(flow.chooseNewReference(false,"Fixed page area"));f.provider.reject=true;assertTrue(flow.confirm())}
        f.await {flow.step==PageToMapStep.SAVE_FAILED}
        val added=withContext(Dispatchers.Main) {
            flow.targetSession!!.document!!.nodes.single {it.sources.isNotEmpty()}.also {
                assertEquals(f.capture.region,it.sources.single().region);f.provider.reject=false;assertTrue(flow.retrySave())
            }
        }
        f.await {flow.step==PageToMapStep.ADDED}
        withContext(Dispatchers.Main) {
            assertEquals(added,flow.targetSession!!.document!!.node(added.id))
            assertEquals(1,flow.targetSession!!.document!!.nodes.count {it.sources.isNotEmpty()})
            flow.close()?.join()
            f.native.editor.openAsync(f.native.targetUri,"Same file reopened");assertTrue(f.native.editor.noteOpen);assertFalse(f.native.editor.canvasOpen)
            val newModel=f.native.editor.state.document
            assertNotSame(f.capture.document,newModel)
            assertFalse(requestPagedRegionReference(f.native.editor,f.capture.document,f.capture))
            assertSame(newModel,f.native.editor.state.document);assertNull(f.native.editor.pageReferenceRequest)
        }
        assertArrayEquals(original,f.native.target.readBytes());assertEquals(0,f.native.writes.get());assertEquals(0,f.native.deletes.get())
        val stored=MapCodec.decode(f.provider.file(f.fresh).readBytes()).node(added.id).sources.single()
        assertEquals(f.capture.region,stored.region);assertEquals(f.capture.page.referenceIdentity.id,stored.pageId)
    }
    @Test fun aCancelledMenusOldRangeCannotSignANewRequestButDismissingAnAcceptedMenuKeepsItsFixedRequest()=scenario {f->
        withContext(Dispatchers.Main) {
            val editor=f.native.editor;val model=f.capture.document;val old=f.capture
            editor.controller.clearSelection();editor.controller.selectAll()
            assertFalse(old.current())
            assertFalse(requestPagedRegionReference(editor,model,old));assertNull(editor.pageReferenceRequest)
            val latest=requireNotNull(editor.sourceSelectionRegion())
            assertTrue(requestPagedRegionReference(editor,model,latest))
            val request=editor.pageReferenceRequest!!
            editor.controller.clearSelection()
            assertFalse(latest.current());assertTrue(request.current());assertEquals(latest.region,request.region)
            request.clear();assertNull(editor.pageReferenceRequest)
        }
        assertEquals(0,f.native.writes.get());assertEquals(0,f.native.deletes.get())
    }

}
