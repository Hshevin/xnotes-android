package com.xnotes.mindmap

import com.xnotes.core.geometry.Pt
import com.xnotes.core.pdf.PdfPageGeometry
import com.xnotes.platform.AndroidRasterSurface
import com.xnotes.storage.*
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real catalog epochs, provider publication, map actors and receipts; the PDF engine only supplies metadata. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class NetworkOriginalCanvasContinuationTest {
    private class Engine:OriginalPdfEngine {
        override val pageCount=2
        override fun pageInfo(pdfKey:Int)=OriginalPdfPageInfo(Pt(600.0,800.0),PdfPageGeometry.flipped(800.0))
        override fun renderRegion(pdfKey:Int,pxPerPt:Double,leftPx:Int,topPx:Int,width:Int,height:Int):AndroidRasterSurface?=error("No pixels are needed to add file cards")
        override fun closeAndAwait() {}
    }
    private class Original(val f:MapBacklinkLibraryTest.Fixture) {
        val access=OriginalPdfAccess(f.context,engineFactory={Engine()},storageOverride=f.storage)
        val catalog=OriginalPdfCatalog(f.storage.registry)
        val observation:OriginalPdfCatalog.Observation
        val selection:NetworkCanvasSelection
        init {
            val input=File(f.root,"immutable-${UUID.randomUUID()}.pdf").apply {writeText("original source bytes")}
            val candidate=access.candidate(input,"Original")
            observation=try {(catalog.retain(candidate) as OriginalPdfCatalog.Retention.Retained).observation} finally {candidate.closeAndAwait()}
            val record=observation.record;val guard=access.identityDomains(setOf(record.id))
            val target=MapSourceTarget(record.id,SourceScope.PDF_ORIGINAL,record.pages.first(),originalPdf=PdfOriginalProjection(record.sha256,0))
            selection=NetworkCanvasSelection(listOf(MapFileCardSeed(record.id,"Original",30.0,40.0,target),
                MapFileCardSeed(UUID.randomUUID().toString(),"Note",400.0,40.0))) {guard.current()}
        }
        fun retireSelection() {catalog.delete(observation);assertFalse(selection.current())}
    }
    private suspend fun await(check:()->Boolean)=withTimeout(15000) {while(!withContext(Dispatchers.Main) {check()}) delay(1)}
    @Test fun aCreationAcceptedBeforePublicationFinishesKeepsItsPayloadWhenTheCatalogChanges()=MapBacklinkLibraryTest().scenario {f->
        val original=withContext(Dispatchers.IO) {Original(f)}
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val target=File(f.notes,"accepted-original.xmap").apply {writeBytes(byteArrayOf())}
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val blocked=AtomicBoolean(false)
        val controller=withContext(Dispatchers.Main) {NetworkToCanvasController(f.context,original.selection,scope)}
        try {
            f.beforeOpenRead={if(f.writes.get()>0 && blocked.compareAndSet(false,true)) {entered.countDown();check(release.await(5,TimeUnit.SECONDS))}}
            withContext(Dispatchers.Main) {assertTrue(controller.chooseNew(f.uri(target)));assertTrue(controller.confirm())}
            assertTrue(withContext(Dispatchers.IO) {entered.await(5,TimeUnit.SECONDS)})
            withContext(Dispatchers.IO) {original.retireSelection()};release.countDown()
            await {controller.step==NetworkCanvasStep.ADDED && !controller.busy}
            withContext(Dispatchers.Main) {assertTrue(controller.current());assertTrue(controller.canView);assertNotNull(controller.captureOpen())}
            val stored=MapCodec.decode(target.readBytes())
            assertEquals(original.selection.seeds.map {it.target}.toSet(),stored.nodes.map {it.sources.single().target}.toSet())
            assertEquals(1,f.writes.get())
        } finally {release.countDown();f.beforeOpenRead=null;withContext(Dispatchers.Main) {controller.close()}?.join();scope.cancel()}
    }
    @Test fun retainedWrittenCreationContinuesWithItsSameSourceIdsAfterTheSelectionEpochRetires()=MapBacklinkLibraryTest().scenario {f->
        val original=withContext(Dispatchers.IO) {Original(f)};val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val target=File(f.notes,"retained-original.xmap").apply {writeBytes(byteArrayOf())}
        val controller=withContext(Dispatchers.Main) {NetworkToCanvasController(f.context,original.selection,scope)}
        try {
            f.beforeOpenRead={if(f.writes.get()>0) throw IOException("Readback failed after actual publication")}
            withContext(Dispatchers.Main) {assertTrue(controller.chooseNew(f.uri(target)));assertTrue(controller.confirm())}
            await {controller.step==NetworkCanvasStep.CREATE_RETAINED}
            val written=target.readBytes();val fixed=MapCodec.decode(written)
            withContext(Dispatchers.IO) {original.retireSelection()};f.beforeOpenRead=null
            withContext(Dispatchers.Main) {assertTrue(controller.current());assertTrue(controller.openRetained())}
            await {controller.step==NetworkCanvasStep.ADDED && !controller.busy}
            withContext(Dispatchers.Main) {assertEquals(fixed,controller.targetSession!!.document);assertTrue(controller.canView)}
            assertArrayEquals(written,target.readBytes());assertEquals(1,f.writes.get())
        } finally {f.beforeOpenRead=null;withContext(Dispatchers.Main) {controller.close()}?.join();scope.cancel()}
    }
    @Test fun anAppliedSaveFailureRetriesOnlyTheSameGraphAfterCatalogDeletionAndNeverAppendsAgain()=MapBacklinkLibraryTest().scenario {f->
        val original=withContext(Dispatchers.IO) {Original(f)};val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val base=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,text="Body")))
        val target=f.graph("failed-original-save.xmap",base);val before=target.readBytes();val lease=f.acquire(f.uri(target))
        target.writeBytes(MapCodec.encode(base.update("r") {it.copy(text="External C")}))
        val controller=withContext(Dispatchers.Main) {NetworkToCanvasController(f.context,original.selection,scope)}
        try {
            withContext(Dispatchers.Main) {assertTrue(controller.chooseExisting(f.uri(target)));assertTrue(controller.confirm())}
            await {controller.step==NetworkCanvasStep.SAVE_FAILED && !controller.busy}
            val fixed=withContext(Dispatchers.Main) {controller.targetSession!!.document!!}
            withContext(Dispatchers.IO) {original.retireSelection();target.writeBytes(before)}
            withContext(Dispatchers.Main) {assertSame(lease.session,controller.targetSession);assertTrue(controller.current());assertTrue(controller.canRetrySave);assertTrue(controller.retrySave())}
            await {controller.step==NetworkCanvasStep.ADDED && !controller.busy}
            assertEquals(fixed,MapCodec.decode(target.readBytes()));assertEquals(2,fixed.nodes.count {it.fileCardId!=null});assertEquals(1,f.writes.get())
        } finally {withContext(Dispatchers.Main) {controller.close()}?.join();scope.cancel()}
    }
    @Test fun anUnacceptedPickerCandidateStillExpiresBeforeAnyPublication()=MapBacklinkLibraryTest().scenario {f->
        val original=withContext(Dispatchers.IO) {Original(f)};val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val target=File(f.notes,"expired-original.xmap").apply {writeBytes(byteArrayOf())}
        val controller=withContext(Dispatchers.Main) {NetworkToCanvasController(f.context,original.selection,scope)}
        try {
            val candidate=withContext(Dispatchers.Main) {controller.prepareNew()!!}
            withContext(Dispatchers.IO) {original.retireSelection()}
            withContext(Dispatchers.Main) {assertFalse(controller.current());assertFalse(controller.completeNew(candidate,f.uri(target)));assertFalse(controller.chooseExisting(f.uri(target)))}
            assertEquals(0,target.length());assertEquals(0,f.writes.get())
        } finally {withContext(Dispatchers.Main) {controller.close()}?.join();scope.cancel()}
    }
}
