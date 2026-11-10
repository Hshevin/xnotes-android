package com.xnotes.pen

import com.xnotes.core.geometry.Pt
import com.xnotes.core.pdf.PdfPageGeometry
import com.xnotes.format.BundleMigration
import com.xnotes.platform.AndroidRasterSurface
import com.xnotes.storage.OriginalPdfAccess
import com.xnotes.storage.OriginalPdfCatalog
import com.xnotes.storage.OriginalPdfEngine
import com.xnotes.storage.OriginalPdfPageInfo
import com.xnotes.ui.PageReferencePreparation
import com.xnotes.ui.PageReferenceRequest
import com.xnotes.ui.SourceCreationMode
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Prompt cancellation after successful IO, before its caller receives ownership. Actual SQLite
 * leases and Editor requests are used; the controlled PDF engine does not assert PDFium pixels. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class OriginalPdfCreationCancellationTest {
    private class QueueDispatcher:CoroutineDispatcher() {
        private val queue=ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context:CoroutineContext,block:Runnable) {queue.add(block)}
        fun pending()=queue.isNotEmpty()
        fun runNext():Boolean=queue.poll()?.let {it.run();true} ?: false
    }
    private class Engines {
        val opened=AtomicInteger()
        val closed=AtomicInteger()
        val blockingClose=AtomicInteger(-1)
        val closeEntered=CountDownLatch(1)
        val allowClose=CountDownLatch(1)
        fun open():OriginalPdfEngine {
            val index=opened.incrementAndGet()
            return object:OriginalPdfEngine {
                override val pageCount=1
                override fun pageInfo(pdfKey:Int)=OriginalPdfPageInfo(Pt(612.0,792.0),PdfPageGeometry.flipped(792.0))
                override fun renderRegion(pdfKey:Int,pxPerPt:Double,leftPx:Int,topPx:Int,width:Int,height:Int):AndroidRasterSurface?=
                    error("Creation cancellation does not render a preview")
                override fun closeAndAwait() {
                    if(index==blockingClose.get()) {
                        closeEntered.countDown()
                        check(allowClose.await(5,TimeUnit.SECONDS)) {"The test did not finish the controlled native close"}
                    }
                    closed.incrementAndGet()
                }
            }
        }
    }
    private data class Source(val request:PageReferenceRequest,val sha:String,val engines:Engines)
    private suspend fun source(f:EditorPageCopyFixture):Source=withContext(Dispatchers.Main) {
        f.editor.newNote()
        val document=f.editor.state.document;val page=document.pages.first();page.pdfPage=0
        val pdf=File(f.root,"original-for-cancellation.pdf").apply {writeText("immutable controlled original PDF bytes")}
        document.pdfFile=pdf
        val engines=Engines()
        f.editor.originalPdfAccess=OriginalPdfAccess(f.context,{engines.open()},f.storage)
        f.addStroke(120.0,recordHistory=true);f.stopDebounce()
        assertTrue(f.editor.requestPageReference(page))
        Source(requireNotNull(f.editor.pageReferenceRequest),BundleMigration.hash(pdf),engines)
    }
    private fun leases(f:EditorPageCopyFixture,sha:String):Int=
        f.storage.registry.readableDatabase.rawQuery("SELECT COUNT(*) FROM leases WHERE resource_id=?",arrayOf(sha)).use {
            assertTrue(it.moveToFirst());it.getInt(0)
        }
    private suspend fun await(check:()->Boolean)=withTimeout(10000L) {while(!check()) delay(1)}
    private suspend fun drainUntilDone(queue:QueueDispatcher,task:Deferred<*>) {
        withTimeout(10000L) {
            while(!task.isCompleted) {
                withContext(Dispatchers.Main) {while(queue.runNext()) {}}
                delay(1)
            }
        }
        task.join()
    }
    private suspend fun cancelAtOwnershipReturn(f:EditorPageCopyFixture,source:Source,prepareRetained:Boolean) {
        val queue=QueueDispatcher();val scope=CoroutineScope(SupervisorJob()+queue)
        val priorCloses=source.engines.closed.get()
        source.engines.blockingClose.set(priorCloses+1)
        val task=scope.async {
            if(prepareRetained) source.request.prepare() else source.request.selectMode(SourceCreationMode.PDF_ORIGINAL)
        }
        try {
            withContext(Dispatchers.Main) {assertTrue(queue.runNext())}
            // This queued continuation is the successful IO return. Do not run it before cancel:
            // cancelling inside candidate/open would only exercise their checkActive cleanup.
            await {queue.pending()}
            assertFalse(task.isCompleted)
            assertEquals(priorCloses+1,source.engines.opened.get())
            assertEquals(priorCloses,source.engines.closed.get())
            assertEquals(1,leases(f,source.sha))
            task.cancel()
            withContext(Dispatchers.Main) {assertTrue(queue.runNext())}
            await {source.engines.closeEntered.count==0L}
            assertEquals("The actual native close has not completed",priorCloses,source.engines.closed.get())
            assertEquals("The PDF lease must outlive its actual native close",1,leases(f,source.sha))
            source.engines.allowClose.countDown()
            drainUntilDone(queue,task)
            assertTrue(task.isCancelled)
            assertEquals(priorCloses+1,source.engines.closed.get())
            assertEquals(0,leases(f,source.sha))
        } finally {
            source.engines.allowClose.countDown();task.cancel()
            drainUntilDone(queue,task);scope.cancel()
            withContext(Dispatchers.Main) {source.request.clear()}
        }
    }
    @Test fun aCandidateWhoseIoSucceededBeforePromptCancellationIsClosedAndNeverPublished()=runParkingScenario {f->
        val source=source(f)
        val model=f.editor.state.document;val history=f.editor.history;val undo=history.nextUndo
        val revision=model.bodyRevision;val identity=model.identity
        cancelAtOwnershipReturn(f,source,prepareRetained=false)
        assertTrue(OriginalPdfCatalog.get(f.context).list().records.isEmpty())
        withContext(Dispatchers.Main) {
            assertSame(model,f.editor.state.document);assertSame(history,f.editor.history);assertSame(undo,history.nextUndo)
            assertSame(identity,model.identity);assertEquals(revision,model.bodyRevision)
            assertTrue(model.dirty);assertFalse(model.hasReferenceAnchors);assertNull(model.pages.first().referenceIdentity.id)
        }
        assertEquals(0,f.writes.get());assertEquals(0,f.writeOpens.get());assertEquals(0,f.deletes.get())
    }
    @Test fun aRetainedReadWhoseIoSucceededBeforePromptCancellationIsClosedWithoutDeletingItsOriginal()=runParkingScenario {f->
        val source=source(f)
        val retained=withContext(Dispatchers.Main) {
            val prepared=source.request.selectMode(SourceCreationMode.PDF_ORIGINAL)
            assertTrue("Initial original retention: $prepared",prepared is PageReferencePreparation.Ready)
            (prepared as PageReferencePreparation.Ready).source
        }
        val before=OriginalPdfCatalog.get(f.context).observe(retained.documentId)!!.record
        assertEquals(1,source.engines.closed.get());assertEquals(0,leases(f,source.sha))
        val model=f.editor.state.document;val history=f.editor.history;val undo=history.nextUndo
        val revision=model.bodyRevision;val identity=model.identity
        cancelAtOwnershipReturn(f,source,prepareRetained=true)
        val after=OriginalPdfCatalog.get(f.context).observe(retained.documentId)!!.record
        assertEquals(before,after);assertFalse(after.deleted);assertEquals(retained.pageId,after.pages.single())
        withContext(Dispatchers.Main) {
            assertSame(model,f.editor.state.document);assertSame(history,f.editor.history);assertSame(undo,history.nextUndo)
            assertSame(identity,model.identity);assertEquals(revision,model.bodyRevision)
            assertTrue(model.dirty);assertFalse(model.hasReferenceAnchors);assertNull(model.pages.first().referenceIdentity.id)
        }
        assertEquals(0,f.writes.get());assertEquals(0,f.writeOpens.get());assertEquals(0,f.deletes.get())
    }
}
