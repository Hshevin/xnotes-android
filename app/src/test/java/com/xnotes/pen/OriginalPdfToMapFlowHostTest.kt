package com.xnotes.pen

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.xnotes.canvas.PageSourceRegion
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.fixedWriteSnapshot
import com.xnotes.core.pdf.PdfPageGeometry
import com.xnotes.mindmap.*
import com.xnotes.persistNativeForMapPresentation
import com.xnotes.platform.AndroidRasterSurface
import com.xnotes.requestPagedRegionReference
import com.xnotes.storage.*
import com.xnotes.ui.SourceCreationMode
import com.xnotes.ui.theme.Palette
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver

/** Real Editor/creation/SQLite/map actor/PNG/reader/return chain. The injected PDF engine supplies
 * controlled original pixels and geometry; this does not claim PDFium or physical GPU acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OriginalPdfToMapFlowHostTest {
    private class Maps(private val root:File):ContentProvider() {
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
        override fun delete(uri:Uri,s:String?,a:Array<out String>?):Int=error("Source creation must not delete its map")
    }
    private class Fixture(val native:EditorPageCopyFixture) {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val provider=Maps(native.root)
        val authority="original-pdf-flow-${UUID.randomUUID()}"
        val existing=Uri.parse("content://$authority/document/existing")
        val fresh=Uri.parse("content://$authority/document/fresh")
        val maps=MapWorkspace(native.context,scope)
        val networks=FileNetworkWorkspace(native.context,scope)
        val catalog=OriginalPdfCatalog(native.storage.registry)
        val rendered=AtomicInteger()
        val access=OriginalPdfAccess(native.context,engineFactory={object:OriginalPdfEngine {
            override val pageCount=1
            override fun pageInfo(pdfKey:Int)=OriginalPdfPageInfo(Pt(600.0,800.0),PdfPageGeometry.flipped(800.0))
            override fun renderRegion(pdfKey:Int,pxPerPt:Double,leftPx:Int,topPx:Int,width:Int,height:Int):AndroidRasterSurface {
                rendered.incrementAndGet()
                return AndroidRasterSurface.create(width,height).apply {bitmap.eraseColor(Color.WHITE)}
            }
            override fun closeAndAwait() {}
        }},storageOverride=native.storage)
        val originals=OriginalPdfWorkspace(native.context,scope,access)
        val binding=Any()
        val flows=mutableListOf<PageToMapController>()
        val readers=mutableListOf<OriginalPdfReader>()
        lateinit var originalNavigator:OriginalPdfNavigator
        lateinit var sources:MapSourceNavigator
        lateinit var capture:PageSourceRegion
        lateinit var originalBytes:ByteArray
        lateinit var noteBytes:ByteArray
        val pdf=File(native.root,"actual-original.pdf").apply {writeText("immutable controlled original bytes")}
        val other=MapSource(documentId=UUID.randomUUID().toString(),fileName="Existing other source")
        var nativeRevision=0L
        var nativeUndo:Any?=null
        var nativeIdentity:com.xnotes.format.BundleIdentity?=null
        var nativeHistory:Any?=null
        suspend fun start() {
            provider.attachInfo(native.context,ProviderInfo().apply {authority=this@Fixture.authority})
            ShadowContentResolver.registerProviderInternal(authority,provider)
            provider.file(existing).writeBytes(MapCodec.encode(MindMapDocument(nodes=listOf(MapNode(id="root",parent=null),
                MapNode(id="concept",parent="root",text="Keep this concept body",sources=listOf(other),primarySource=other.id)))))
            provider.file(fresh).writeBytes(byteArrayOf())
            withContext(Dispatchers.IO) {
                native.target.outputStream().use {native.codec.write(Document(mutableListOf(Page(600.0,800.0)),dpi=72).fixedWriteSnapshot(),it)}
                noteBytes=native.target.readBytes();originalBytes=pdf.readBytes()
            }
            withContext(Dispatchers.Main) {
                native.editor.openAsync(native.targetUri,"Annotated note.xnote")
                val doc=native.editor.state.document
                assertFalse(doc.hasReferenceAnchors);assertNull(doc.pages.first().referenceIdentity.id)
                // An ordinary NOTE request keeps its established presentation policy.
                assertTrue(native.editor.requestPageReference(doc.pages.first()))
                assertTrue(native.editor.pageReferenceRequest!!.flushNativeOnPresent)
                native.editor.pageReferenceRequest!!.clear()
                doc.pdfFile=pdf;doc.pages.first().pdfPage=0
                native.editor.originalPdfAccess=access
                native.addStroke(120.0,recordHistory=true);native.stopDebounce()
                native.editor.view.layout(0,0,1000,1000);native.editor.controller.selectAll()
                capture=requireNotNull(native.editor.sourceSelectionRegion())
                nativeRevision=doc.bodyRevision;nativeUndo=native.editor.history.nextUndo
                nativeIdentity=doc.identity;nativeHistory=native.editor.history
                maps.bindHost(binding);networks.bindHost(binding);originals.bindHost(binding)
                originalNavigator=OriginalPdfNavigator(native.editor,maps,networks,originals,binding,scope,captureHost={
                    val owner=native.editor.documentOwner;val model=native.editor.state.document
                    val current:()->Boolean={native.editor.documentOwner===owner && native.editor.state.document===model}
                    current
                })
                sources=MapSourceNavigator(native.editor,maps,binding,scope,
                    guard={_,_->error("Original PDF sources must bypass native document replacement")},originalNavigator=originalNavigator)
            }
        }
        suspend fun flow(region:Boolean):PageToMapController=withContext(Dispatchers.Main) {
            assertTrue(if(region) requestPagedRegionReference(native.editor,capture.document,capture)
                else native.editor.requestPageReference(capture.page))
            PageToMapController(native.context,native.editor.pageReferenceRequest!!,scope).also {flows+=it;assertTrue(it.start())}
        }
        suspend fun await(check:()->Boolean)=withTimeout(10000L) {while(!withContext(Dispatchers.Main) {check()}) delay(1)}
        suspend fun assertNativeUnwritten() {
            withContext(Dispatchers.Main) {
                val doc=native.editor.state.document
                assertSame(capture.document,doc);assertSame(nativeIdentity,doc.identity);assertEquals(nativeRevision,doc.bodyRevision)
                assertSame(nativeHistory,native.editor.history);assertSame(nativeUndo,native.editor.history.nextUndo)
                assertTrue(doc.dirty);assertFalse(doc.hasReferenceAnchors);assertNull(capture.page.referenceIdentity.id)
            }
            assertArrayEquals(noteBytes,native.target.readBytes());assertArrayEquals(originalBytes,pdf.readBytes())
            assertEquals(0,native.writes.get());assertEquals(0,native.writeOpens.get());assertEquals(0,native.deletes.get())
        }
        suspend fun finish()=withContext(NonCancellable) {
            provider.reject=false
            withContext(Dispatchers.Main) {readers.forEach {it.retire()}}
            withContext(Dispatchers.IO) {readers.forEach {it.closeAndAwait()}}
            withContext(Dispatchers.Main) {
                flows.forEach {it.close()?.join()}
                if(this@Fixture::sources.isInitialized) sources.dispose()
                if(this@Fixture::originalNavigator.isInitialized) originalNavigator.dispose()
                networks.dispose();maps.dispose()
            }.join()
            withContext(Dispatchers.Main) {originals.dispose()}.join();scope.cancel()
        }
    }
    private fun scenario(block:suspend(Fixture)->Unit)=runParkingScenario {native->
        val f=Fixture(native);try {f.start();block(f)} finally {f.finish()}
    }
    @Test fun aRegionCanJoinAnExistingConceptKeepItsOriginalPngColdReopenAndReturnFromItsReadOnlyOriginal()=scenario {f->
        val flow=f.flow(region=true);f.await {flow.step==PageToMapStep.SOURCE_MODE}
        withContext(Dispatchers.Main) {assertTrue(flow.selectSourceMode(SourceCreationMode.PDF_ORIGINAL))}
        f.await {flow.step==PageToMapStep.DESTINATION}
        withContext(Dispatchers.Main) {
            assertFalse(flow.request.flushNativeOnPresent)
            assertTrue(flow.selectMap(f.existing))
        }
        f.await {flow.step==PageToMapStep.TARGET}
        withContext(Dispatchers.Main) {assertTrue(flow.chooseExistingNode("concept"));assertTrue(flow.confirm())}
        f.await {flow.step==PageToMapStep.ADDED}
        val session=withContext(Dispatchers.Main) {flow.targetSession!!}
        val ticket=withContext(Dispatchers.Main) {
            val node=session.document!!.node("concept")
            assertEquals("Keep this concept body",node.text);assertEquals(f.other,node.sources.first());assertEquals(f.other.id,node.primarySource)
            assertEquals(2,node.sources.size)
            val source=node.sources.last();assertEquals(SourceScope.PDF_ORIGINAL,source.scope);assertNull(source.region)
            val r=f.capture.region
            val expected=com.xnotes.core.geometry.Rect(r.x,800.0-r.bottom,r.w,r.h)
            assertEquals(expected,source.originalPdf!!.userRegion)
            session.captureSourcePreview("concept",source.id)!!
        }
        val read=withContext(Dispatchers.IO) {
            OriginalPdfSourcePreviewBackend.load(f.native.context,listOf(ticket.target),setOf(ticket.target),access=f.access,catalog=f.catalog).single()
        }
        assertEquals(SourceStatus.CURRENT,read.status);assertFalse(read.failedPreview)
        val asset=requireNotNull(read.asset);val png=asset.pngBytes()
        val bitmap=requireNotNull(BitmapFactory.decodeByteArray(png,0,png.size))
        try {assertEquals(Color.WHITE,bitmap.getPixel(bitmap.width/2,bitmap.height/2))} finally {bitmap.recycle()}
        withContext(Dispatchers.Main) {
            val preview=MapLastPreview(asset.key,ticket.target,requireNotNull(read.renderedVersion),System.currentTimeMillis())
            val patch=MapSourceDerivedPatch(ticket.nodeId,ticket.sourceId,ticket.target,read.status,read.fileName,read.renderedVersion,
                read.lastKnownPage,preview,asset,true,read.rawVersion,read.renderedVersion)
            assertTrue(session.mergeSourceDerived(listOf(patch)) {session.currentSourcePreview(ticket)!=null})
            // Derived PNG changes do not add a content undo above the one source import.
            session.undo();assertEquals(listOf(f.other),session.document!!.node("concept").sources)
            session.redo();assertEquals(asset.key,session.document!!.node("concept").sources.last().lastPreview!!.assetKey)
            session.cancelPendingAutosave();assertTrue(session.saveAttempt().await() is MapSaveOutcome.Written)
            assertTrue(flow.openMap(flow.targetUri,flow.targetContext) {uri->
                assertTrue(f.maps.open(uri,"Original reference",flushNativeOnPresent=flow.request.flushNativeOnPresent))
            })
        }
        f.await {f.maps.visible && !f.maps.opening}
        withContext(Dispatchers.Main) {
            persistNativeForMapPresentation(f.native.editor,f.maps.current!!)
            f.maps.close(f.maps.current!!)?.join();flow.close()?.join()
        }
        f.await {MapDocuments.observations(f.native.context).isEmpty()}
        val cold=MapCodec.decode(f.provider.file(f.existing).readBytes())
        val stored=cold.node("concept").sources.last()
        assertEquals(ticket.target,stored.target);assertNotNull(stored.lastPreview)
        assertArrayEquals(png,cold.previewAssets.getValue(stored.lastPreview!!.assetKey).pngBytes())
        val policy=flow.request.flushNativeOnPresent
        withContext(Dispatchers.Main) {assertFalse(policy);assertTrue(f.maps.open(f.existing,"Cold original reference",flushNativeOnPresent=policy))}
        f.await {f.maps.visible && !f.maps.opening}
        val camera=MindMapView.Viewport(.75f,63f,-28f)
        val entry=withContext(Dispatchers.Main) {
            f.maps.current!!.also {entry->
                assertNotSame(session,entry.session);assertEquals(cold,entry.session.document)
                persistNativeForMapPresentation(f.native.editor,entry)
                entry.session.selected="concept";entry.presentation.synchronize(entry.session)
                val view=MindMapView(f.native.context).apply {document=entry.session.document!!;documentEpoch=entry.session.interactionEpoch;layout(0,0,1000,700)}
                view.restoreViewport(camera,1000,700,view.resources.displayMetrics.density)
                assertTrue(entry.presentation.showSources(entry.session,view,"concept"))
                val navigation=entry.session.captureSourceNavigation("concept",stored.id)!!
                assertTrue(f.sources.open(entry,navigation,entry.session.captureSourceReturn(navigation)!!))
            }
        }
        f.await {f.originals.visible && !f.sources.busy}
        val shown=withContext(Dispatchers.Main) {f.originals.current!!}
        val reader=withContext(Dispatchers.Main) {
            OriginalPdfReader(f.native.context,shown,shown.pageIndex,shown.read.engine.pageInfo(0),Palette.DEFAULT,shown.retainReader()!!).also {
                f.readers+=it;assertNull(it.document.path);assertNull(it.document.pdfFile)
                assertTrue(it.page.items.isEmpty());assertTrue(it.page.markups.isEmpty());assertTrue(it.page.rooms.isEmpty())
                assertFalse(it.document.dirty);assertEquals(ticket.target,shown.target)
                it.view.layout(0,0,600,800)
                val pixels=Bitmap.createBitmap(600,800,Bitmap.Config.ARGB_8888)
                try {it.view.draw(Canvas(pixels))} finally {pixels.recycle()}
            }
        }
        f.await {f.rendered.get()>=2}
        withContext(Dispatchers.Main) {assertTrue(f.sources.returnToMap())}
        f.await {f.maps.visible && !f.sources.busy}
        withContext(Dispatchers.Main) {
            assertSame(entry,f.maps.current);assertEquals("concept",entry.session.selected)
            assertEquals("concept",entry.presentation.sourcesFor);assertEquals(stored.id,entry.presentation.sourceFocus!!.sourceId)
            val restored=MindMapView(f.native.context).apply {document=entry.session.document!!;documentEpoch=entry.session.interactionEpoch;layout(0,0,1000,700)}
            assertTrue(entry.presentation.restore(entry.session,restored));assertEquals(camera,restored.viewport())
            persistNativeForMapPresentation(f.native.editor,entry)
            reader.retire()
        }
        withContext(Dispatchers.IO) {reader.closeAndAwait()}
        f.assertNativeUnwritten()
    }
    @Test fun aWholeOriginalInANewMapRetriesItsFailedSaveWithoutRepeatingTheNodeSourceOrRetention()=scenario {f->
        val flow=f.flow(region=false);f.await {flow.step==PageToMapStep.SOURCE_MODE}
        withContext(Dispatchers.Main) {assertTrue(flow.selectSourceMode(SourceCreationMode.PDF_ORIGINAL))}
        f.await {flow.step==PageToMapStep.DESTINATION}
        val original=withContext(Dispatchers.Main) {flow.source!!.also {assertNull(it.originalPdf!!.userRegion)}}
        val kept=f.catalog.observe(original.documentId)!!.record
        withContext(Dispatchers.Main) {assertTrue(flow.createMap(f.fresh))}
        f.await {flow.step==PageToMapStep.TARGET}
        withContext(Dispatchers.Main) {
            assertTrue(flow.chooseNewReference(false,"Original page"));f.provider.reject=true;assertTrue(flow.confirm())
        }
        f.await {flow.step==PageToMapStep.SAVE_FAILED}
        val added=withContext(Dispatchers.Main) {
            flow.targetSession!!.document!!.nodes.single {it.sources.isNotEmpty()}.also {
                assertEquals(original.target,it.sources.single().target);f.provider.reject=false;assertTrue(flow.retrySave())
            }
        }
        f.await {flow.step==PageToMapStep.ADDED}
        withContext(Dispatchers.Main) {
            val session=flow.targetSession!!
            assertEquals(added,session.document!!.node(added.id));assertEquals(1,session.document!!.nodes.count {it.sources.isNotEmpty()})
            assertEquals(1,session.document!!.nodes.flatMap {it.sources}.size)
            session.undo();assertTrue(session.document!!.nodes.none {it.id==added.id})
            session.redo();assertEquals(added,session.document!!.node(added.id))
            session.cancelPendingAutosave();assertTrue(session.saveAttempt().await() is MapSaveOutcome.Written)
            flow.close()?.join()
        }
        f.await {MapDocuments.observations(f.native.context).isEmpty()}
        val cold=MapCodec.decode(f.provider.file(f.fresh).readBytes())
        assertEquals(added,cold.node(added.id));assertEquals(1,cold.nodes.flatMap {it.sources}.size)
        assertEquals(kept,f.catalog.observe(original.documentId)!!.record);assertFalse(kept.deleted)
        f.assertNativeUnwritten()
    }
}
