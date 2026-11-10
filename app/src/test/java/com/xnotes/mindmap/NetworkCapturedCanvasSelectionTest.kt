package com.xnotes.mindmap

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.enableReferenceAnchors
import com.xnotes.core.model.fixedWriteSnapshot
import com.xnotes.core.pdf.PdfPageGeometry
import com.xnotes.format.BundleHeader
import com.xnotes.format.DocumentCodec
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidRasterSurface
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.storage.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** Uses the displayed production selection and a real provider which stalls only the new target's load. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class NetworkCapturedCanvasSelectionTest {
    private class Engine:OriginalPdfEngine {
        override val pageCount=2
        override fun pageInfo(pdfKey:Int)=OriginalPdfPageInfo(Pt(600.0,800.0),PdfPageGeometry.flipped(800.0))
        override fun renderRegion(pdfKey:Int,pxPerPt:Double,leftPx:Int,topPx:Int,width:Int,height:Int):AndroidRasterSurface?=
            error("Card metadata must not render the PDF")
        override fun closeAndAwait() {}
    }
    private class SlowTarget(val f:MapBacklinkLibraryTest.Fixture) {
        val base=MindMapDocument(nodes=listOf(MapNode(id="body",parent=null,text="Keep target body")))
        val file=File(f.root,"slow-existing.xmap").apply {writeBytes(MapCodec.encode(base))}
        val authority="network-card-slow-${UUID.randomUUID()}"
        val uri=DocumentsContract.buildDocumentUri(authority,"target")
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val once=AtomicBoolean(false);val writes=AtomicInteger()
        val provider=object:ContentProvider() {
            override fun onCreate()=true
            override fun getType(uri:Uri)="application/json"
            override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,sort:String?):Cursor {
                val columns=projection ?: arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)
                return MatrixCursor(columns).apply {addRow(columns.map {when(it) {
                    OpenableColumns.DISPLAY_NAME->file.name;OpenableColumns.SIZE->file.length()
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID->"target"
                    DocumentsContract.Document.COLUMN_MIME_TYPE->"application/json"
                    DocumentsContract.Document.COLUMN_FLAGS->DocumentsContract.Document.FLAG_SUPPORTS_WRITE
                    else->null
                }})}
            }
            override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
                val write='w' in mode
                if(write) writes.incrementAndGet()
                else if(once.compareAndSet(false,true)) {entered.countDown();check(release.await(10,TimeUnit.SECONDS))}
                return ParcelFileDescriptor.open(file,if(write) ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE else ParcelFileDescriptor.MODE_READ_ONLY)
            }
            override fun insert(uri:Uri,values:ContentValues?):Uri?=null
            override fun update(uri:Uri,values:ContentValues?,selection:String?,args:Array<out String>?)=0
            override fun delete(uri:Uri,selection:String?,args:Array<out String>?)=0
        }
        init {
            provider.attachInfo(f.context,ProviderInfo().apply {authority=this@SlowTarget.authority;exported=true;grantUriPermissions=true})
            ShadowContentResolver.registerProviderInternal(authority,provider)
        }
    }
    private suspend fun await(check:()->Boolean)=withTimeout(15000) {while(!withContext(Dispatchers.Main) {check()}) delay(1)}
    private fun scenario(deleteBeforeAppend:Boolean)=MapBacklinkLibraryTest().scenario {f->
        val catalog=OriginalPdfCatalog(f.storage.registry)
        val access=OriginalPdfAccess(f.context,engineFactory={Engine()},storageOverride=f.storage)
        val original=withContext(Dispatchers.IO) {
            val pdf=File(f.root,"original.pdf").apply {writeText("immutable original bytes")}
            val candidate=access.candidate(pdf,"Original")
            try {(catalog.retain(candidate) as OriginalPdfCatalog.Retention.Retained).observation} finally {candidate.closeAndAwait()}
        }
        val native=Document(mutableListOf(Page(500.0,500.0))).apply {enableReferenceAnchors()}
        withContext(Dispatchers.IO) {
            val file=File(f.notes,"actual-native.xnote")
            file.outputStream().use {DocumentCodec(AndroidImageCodec(),AndroidTextMeasurer()).write(native.fixedWriteSnapshot(),it)}
            f.storage.registry.registerObserved(BundleHeader.read(file),"xnote",file.name,f.uri(file).toString())
        }
        val record=original.record
        val source=MapSource(documentId=record.id,fileName=record.title,scope=SourceScope.PDF_ORIGINAL,pageId=record.pages[1],
            originalPdf=PdfOriginalProjection(record.sha256,1),status=SourceStatus.CURRENT)
        val graph=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,sources=listOf(source,
            MapSource(documentId=native.identity.documentId,fileName="Actual native")))))
        val evidence=f.graph("selection-evidence.xmap",graph)
        withContext(Dispatchers.IO) {f.storage.registry.registerMapObserved(graph.id,evidence.name,f.uri(evidence).toString())}
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val workspace=FileNetworkWorkspace(f.context,scope);val binding=Any();val target=SlowTarget(f)
        val originalBytes=target.file.readBytes()
        lateinit var entry:FileNetworkWorkspace.Entry
        var flow:NetworkToCanvasController?=null
        try {
            withContext(Dispatchers.Main) {workspace.bindHost(binding);entry=workspace.admit(binding,NetworkQuery.library("Library",listOf(f.tree.toString())))!!}
            await {!entry.controller.loading && !entry.controller.filtering && entry.controller.fileChoices.any {it.documentId==record.id}}
            withContext(Dispatchers.Main) {assertTrue(entry.controller.showCollection(setOf(record.id,native.identity.documentId)))}
            await {!entry.controller.loading && !entry.controller.filtering && entry.controller.browseMode==NetworkBrowseMode.FILES}
            withContext(Dispatchers.Main) {assertTrue(entry.controller.applyFilter(NetworkFilter(kinds=setOf(NetworkFileKind.PDF_ORIGINAL))))}
            await {!entry.controller.loading && !entry.controller.filtering && entry.controller.report?.matchedFiles?.contains(record.id)==true}
            lateinit var selection:NetworkCanvasSelection
            lateinit var oldNavigationGuard:()->Boolean
            var generation=0L
            withContext(Dispatchers.Main) {
                val scene=entry.builder.build(entry.controller.report!!,LocalNetworkLabels("File","Concept","Ink","Reference","References %d","Belongs"))
                selection=requireNotNull(NetworkCanvasSelection.capture(entry,scene))
                oldNavigationGuard=requireNotNull(entry.controller.captureMetadataGuard());generation=entry.controller.filterRevision
                assertTrue(selection.current());assertTrue(oldNavigationGuard())
                flow=NetworkToCanvasController(f.context,selection,scope)
                assertTrue(flow!!.chooseExisting(target.uri));assertTrue(flow!!.confirm())
            }
            assertTrue(withContext(Dispatchers.IO) {target.entered.await(5,TimeUnit.SECONDS)})
            // Acquiring the target has published a new map actor and a fresh network report while
            // its body read is still blocked. This is not a user scope or catalog identity change.
            await {!entry.controller.loading && !entry.controller.filtering && !oldNavigationGuard()}
            withContext(Dispatchers.Main) {assertEquals(generation,entry.controller.filterRevision)}
            if(deleteBeforeAppend) {
                withContext(Dispatchers.IO) {catalog.delete(original)}
                withContext(Dispatchers.Main) {assertFalse(selection.current());assertFalse(flow!!.current())}
                target.release.countDown();await {flow?.busy==false}
                assertArrayEquals(originalBytes,target.file.readBytes());assertEquals(0,target.writes.get())
                withContext(Dispatchers.Main) {assertTrue(flow!!.nodeIds.isEmpty());assertFalse(flow!!.canView)}
            } else {
                withContext(Dispatchers.Main) {
                    assertTrue("The fixed file metadata survives the target's own actor acquisition",selection.current())
                    assertTrue(flow!!.current());assertEquals(NetworkCanvasStep.WORKING,flow!!.step)
                }
                target.release.countDown();await {flow?.step==NetworkCanvasStep.ADDED && flow?.busy==false}
                val saved=MapCodec.decode(target.file.readBytes())
                assertEquals(2,saved.nodes.count {it.fileCardId!=null});assertEquals(target.base.node("body"),saved.node("body"))
                assertEquals(selection.seeds.map {it.target}.toSet(),saved.nodes.filter {it.fileCardId!=null}.map {it.sources.single().target}.toSet())
                assertEquals(1,target.writes.get());withContext(Dispatchers.Main) {assertTrue(flow!!.canView)}
            }
        } finally {
            target.release.countDown();withContext(Dispatchers.Main) {flow?.close()}?.join()
            withContext(Dispatchers.Main) {workspace.dispose()};scope.cancel()
        }
    }
    @Test fun capturedFileSelectionSurvivesItsOwnSlowExistingTargetAcquisitionAndAppendsExactlyOnce()=scenario(false)
    @Test fun capturedFileSelectionStillRejectsCatalogDeletionBeforeTheSlowTargetCanAppend()=scenario(true)
}
