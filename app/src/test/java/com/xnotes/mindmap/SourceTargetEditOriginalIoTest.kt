package com.xnotes.mindmap

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pdf.PdfPageGeometry
import com.xnotes.platform.AndroidRasterSurface
import com.xnotes.storage.OriginalPdfAccess
import com.xnotes.storage.OriginalPdfCatalog
import com.xnotes.storage.OriginalPdfEngine
import com.xnotes.storage.OriginalPdfPageInfo
import java.io.File
import java.util.UUID
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

/** Real catalog, resource leases, source reads and map saves. Only PDFium pixels/native waits are
 * controlled; queued caller continuations expose cancellation after successful IO ownership. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class SourceTargetEditOriginalIoTest {
    private class QueueDispatcher:CoroutineDispatcher() {
        private val queue=ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context:CoroutineContext,block:Runnable) {queue.add(block)}
        fun pending()=queue.isNotEmpty()
        fun next()=queue.poll()?.let {it.run();true} ?: false
    }
    private class Engines {
        val opened=AtomicInteger();val closed=AtomicInteger();val renders=AtomicInteger()
        val blockingClose=AtomicInteger(-1)
        val closeEntered=CountDownLatch(1);val allowClose=CountDownLatch(1)
        fun open():OriginalPdfEngine {
            val index=opened.incrementAndGet()
            return object:OriginalPdfEngine {
                override val pageCount=1
                override fun pageInfo(pdfKey:Int)=OriginalPdfPageInfo(Pt(600.0,800.0),PdfPageGeometry.flipped(800.0))
                override fun renderRegion(pdfKey:Int,pxPerPt:Double,leftPx:Int,topPx:Int,width:Int,height:Int):AndroidRasterSurface {
                    renders.incrementAndGet()
                    return AndroidRasterSurface.create(width,height).apply {bitmap.eraseColor(Color.WHITE)}
                }
                override fun closeAndAwait() {
                    if(index==blockingClose.get()) {closeEntered.countDown();check(allowClose.await(5,TimeUnit.SECONDS))}
                    closed.incrementAndGet()
                }
            }
        }
    }
    private class Graphs(val file:File):ContentProvider() {
        var reject=true;var writes=0
        override fun onCreate()=true
        override fun getType(uri:Uri)="application/json"
        override fun query(uri:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,order:String?):Cursor=
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply {addRow(arrayOf("Original target.xmap"))}
        override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
            if('w' in mode) {writes++;if(reject) throw java.io.IOException("Controlled map save failure")}
            return ParcelFileDescriptor.open(file,ParcelFileDescriptor.parseMode(mode))
        }
        override fun insert(uri:Uri,v:ContentValues?):Uri?=null
        override fun update(uri:Uri,v:ContentValues?,s:String?,a:Array<out String>?)=0
        override fun delete(uri:Uri,s:String?,a:Array<out String>?):Int=error("Target editing must not delete files")
    }
    private class Fixture(val native:MapBacklinkLibraryTest.Fixture,val queue:QueueDispatcher?,val missingPage:Boolean) {
        val engines=Engines()
        val originals=OriginalPdfAccess(native.context,{engines.open()},native.storage)
        val catalog=OriginalPdfCatalog(native.storage.registry)
        val access=MapSourceTargetAccess(native.context,native.storage,originals)
        val scope=CoroutineScope(SupervisorJob()+(queue ?: Dispatchers.Main))
        val pdf=File(native.root,"immutable-original.pdf").apply {writeText("fixed original PDF bytes")}
        val originalBytes=pdf.readBytes()
        val graph=File(native.root,"original-target-edit.xmap")
        val provider=Graphs(graph)
        val authority="original-target-edit-${UUID.randomUUID()}"
        val uri=Uri.parse("content://$authority/document/graph")
        lateinit var record:OriginalPdfCatalog.Record
        lateinit var source:MapSource
        lateinit var session:MapSession
        lateinit var controller:SourceTargetEditController
        suspend fun start() {
            withContext(Dispatchers.IO) {
                val candidate=originals.candidate(pdf,"Original")
                try {record=(catalog.retain(candidate) as OriginalPdfCatalog.Retention.Retained).observation.record}
                finally {candidate.closeAndAwait()}
                source=MapSource(documentId=record.id,fileName=record.title,scope=SourceScope.PDF_ORIGINAL,
                    pageId=if(missingPage) UUID.randomUUID().toString() else record.pages.single(),originalPdf=PdfOriginalProjection(record.sha256,0),note="Keep this remark")
                graph.writeBytes(MapCodec.encode(MindMapDocument(nodes=listOf(MapNode(id="r",parent=null),
                    MapNode(id="c",parent="r",text="Keep body",sources=listOf(source),primarySource=source.id)))))
            }
            provider.attachInfo(native.context,ProviderInfo().apply {authority=this@Fixture.authority})
            org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(authority,provider)
            session=native.acquire(uri).session
            controller=SourceTargetEditController(native.context,session,scope,access)
        }
        suspend fun begin()=withContext(Dispatchers.Main) {
            val ticket=session.captureSourceTargetEdit("c",source.id,source.target,SourceTargetEditMode.RANGE)!!
            assertTrue(controller.start(Any(),ticket))
        }
        suspend fun await(check:()->Boolean)=withTimeout(10000) {while(!check()) delay(1)}
        suspend fun pumpUntil(check:()->Boolean) {
            withTimeout(10000) {
                while(true) {
                    val done=withContext(Dispatchers.Main) {while(queue?.next()==true) {};check()}
                    if(done) break
                    delay(1)
                }
            }
        }
        suspend fun visual()=pumpUntil {controller.state?.step==SourceTargetEditStep.VISUAL && idle()}
        fun idle()=scope.coroutineContext[Job]!!.children.none()
        fun leases():Int=native.storage.registry.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM leases WHERE resource_id=?",arrayOf(record.sha256)).use {assertTrue(it.moveToFirst());it.getInt(0)}
        fun actual()=session.document!!.node("c").sources.single()
        suspend fun deleteAndRestore()=withContext(Dispatchers.IO) {
            val deleted=catalog.delete(catalog.observe(record.id)!!)
            val candidate=originals.candidate(pdf,"Original")
            try {catalog.restore(deleted,candidate)} finally {candidate.closeAndAwait()}
        }
        val initialized get()=::controller.isInitialized
        suspend fun finish() {
            engines.allowClose.countDown()
            withContext(Dispatchers.Main) {controller.close();session.cancelPendingAutosave()}
            pumpUntil {idle() && leases()==0}
            scope.cancel()
        }
    }
    private fun scenario(queued:Boolean=false,missingPage:Boolean=false,block:suspend(Fixture)->Unit)=MapBacklinkLibraryTest().scenario {native->
        val f=Fixture(native,if(queued) QueueDispatcher() else null,missingPage)
        try {f.start();block(f)} finally {if(f.initialized) f.finish()}
    }
    @Test fun cancellingAfterSuccessfulReadIoWaitsForActualNativeCloseBeforeReleasingItsLease()=scenario(queued=true) {f->
        f.begin();val queue=f.queue!!;val prior=f.engines.closed.get()
        withContext(Dispatchers.Main) {assertTrue(queue.next())}
        f.await {queue.pending()}
        assertEquals(prior+1,f.engines.opened.get());assertEquals(prior,f.engines.closed.get());assertEquals(1,f.leases())
        f.engines.blockingClose.set(f.engines.opened.get())
        withContext(Dispatchers.Main) {assertTrue(f.controller.cancel());assertTrue(queue.next())}
        f.await {f.engines.closeEntered.count==0L}
        assertEquals(prior,f.engines.closed.get());assertEquals(1,f.leases())
        f.engines.allowClose.countDown();f.pumpUntil {f.idle() && f.leases()==0}
        assertEquals(prior+1,f.engines.closed.get());assertNull(f.controller.state)
        assertEquals(f.record,f.catalog.observe(f.record.id)!!.record)
        assertFalse(f.session.canUndo);assertEquals(0,f.provider.writes)
        assertArrayEquals(f.originalBytes,f.pdf.readBytes());assertEquals(0,f.native.writes.get())
    }
    @Test fun aRenderedImageReturningAfterOriginalDeleteRestoreCannotPublishOrConfirmButReloadCanAcquireANewObservation()=scenario(queued=true) {f->
        f.begin();f.visual();val queue=f.queue!!;val host=Any()
        val frame=withContext(Dispatchers.Main) {
            f.controller.state!!.frame!!.also {
                assertTrue(f.controller.attachVisualHost(host,it));assertTrue(f.controller.canConfirm())
                assertTrue(f.controller.render(it.bounds,host));assertTrue(queue.next())
            }
        }
        // First continuation is the render debounce; the next is the complete successful IO result.
        f.await {queue.pending()};withContext(Dispatchers.Main) {assertTrue(queue.next())}
        f.await {queue.pending() && f.engines.renders.get()==1}
        assertNull(f.controller.image)
        f.deleteAndRestore()
        withContext(Dispatchers.Main) {
            assertFalse(f.controller.visualCurrent(host,frame));assertFalse(f.controller.canConfirm())
            assertEquals(SourceTargetEditProblem.CHANGED,f.controller.displayedProblem())
            assertFalse(f.controller.confirm());assertFalse(f.controller.render(frame.bounds,host))
            assertEquals(SourceTargetEditStep.CHANGED,f.controller.state!!.step)
            assertEquals(SourceTargetEditProblem.CHANGED,f.controller.state!!.problem)
        }
        f.pumpUntil {f.idle()}
        withContext(Dispatchers.Main) {
            assertNull(f.controller.image);assertEquals(f.source.target,f.actual().target)
            assertTrue(f.controller.reload())
        }
        f.visual()
        withContext(Dispatchers.Main) {assertTrue(f.controller.canConfirm());assertNotSame(frame,f.controller.state!!.frame)}
        assertFalse(f.session.canUndo);assertEquals(0,f.provider.writes);assertEquals(0,f.native.writes.get())
    }
    @Test fun anInvalidatedReadStillLetsItsCurrentHostEndTheGestureAndReloadWithoutAcceptingAnOldHostsCallback()=scenario {f->
        f.begin();f.visual();val host=Any()
        val frame=withContext(Dispatchers.Main) {
            f.controller.state!!.frame!!.also {
                assertTrue(f.controller.attachVisualHost(host,it));f.controller.setVisualBusy(host,it,true)
                assertTrue(f.controller.visualBusy)
            }
        }
        f.deleteAndRestore()
        withContext(Dispatchers.Main) {
            assertFalse(f.controller.visualCurrent(host,frame));f.controller.setVisualBusy(host,frame,false)
            assertFalse(f.controller.visualBusy)
            assertEquals(SourceTargetEditProblem.CHANGED,f.controller.displayedProblem())
            assertTrue(f.controller.reload())
        }
        f.visual()
        withContext(Dispatchers.Main) {
            val nextHost=Any();val nextFrame=f.controller.state!!.frame!!
            assertNotSame(frame,nextFrame);assertTrue(f.controller.attachVisualHost(nextHost,nextFrame))
            f.controller.setVisualBusy(nextHost,nextFrame,true)
            f.controller.setVisualBusy(host,frame,false);assertTrue(f.controller.visualBusy)
            f.controller.setVisualBusy(nextHost,nextFrame,false);assertFalse(f.controller.visualBusy)
            assertTrue(f.controller.canConfirm())
        }
        assertEquals(f.source.target,f.actual().target);assertFalse(f.session.canUndo)
        assertEquals(0,f.provider.writes);assertEquals(0,f.native.writes.get());assertArrayEquals(f.originalBytes,f.pdf.readBytes())
    }
    @Test fun originalPdfRejectsOutsideRangesWithoutClampingAndAnAcceptedSaveRetryDoesNotNeedTheClosedReadOrLiveCatalog()=scenario {f->
        f.begin();f.visual()
        withContext(Dispatchers.Main) {assertTrue(f.controller.chooseScope(SourceRangeScope.REGION))}
        f.visual()
        withContext(Dispatchers.Main) {
            val frame=f.controller.state!!.frame!!
            assertFalse(f.controller.setRegion(frame,Rect(-1.0,10.0,40.0,50.0)))
            assertNull(f.controller.state!!.region);assertFalse(f.controller.canConfirm())
            assertTrue(f.controller.setRegion(frame,Rect(20.0,30.0,100.0,110.0)))
            assertTrue(f.controller.canConfirm());assertTrue(f.controller.confirm())
        }
        f.pumpUntil {f.controller.state?.step==SourceTargetEditStep.SAVE_FAILED && f.leases()==0}
        val accepted=withContext(Dispatchers.Main) {f.actual().target}
        assertEquals(Rect(20.0,660.0,100.0,110.0),accepted.originalPdf!!.userRegion)
        withContext(Dispatchers.IO) {f.catalog.delete(f.catalog.observe(f.record.id)!!)}
        val revision=withContext(Dispatchers.Main) {
            assertTrue(f.controller.state!!.ticket.accepted);assertFalse(f.controller.canConfirm())
            f.provider.reject=false
            f.session.contentRevision.also {assertTrue(f.controller.retrySave())}
        }
        f.pumpUntil {f.controller.state?.step==SourceTargetEditStep.SAVED}
        withContext(Dispatchers.Main) {
            assertEquals(revision,f.session.contentRevision);assertEquals(accepted,f.actual().target)
            assertEquals("Keep body",f.session.document!!.node("c").text)
            assertEquals("Keep this remark",f.actual().note)
        }
        assertEquals(accepted,MapCodec.decode(f.graph.readBytes()).node("c").sources.single().target)
        assertTrue(f.catalog.observe(f.record.id)!!.record.deleted)
        assertArrayEquals(f.originalBytes,f.pdf.readBytes());assertEquals(0,f.native.writes.get())
    }
    @Test fun anOriginalPageMissingFromTheStoredTargetOnlyPreviewsAndRequiresAnActualPageChoice()=scenario(missingPage=true) {f->
        f.begin();f.visual()
        withContext(Dispatchers.Main) {
            assertNull(f.controller.state!!.frame!!.pageId)
            assertEquals(SourceTargetEditProblem.PAGE_REQUIRED,f.controller.state!!.problem)
            assertFalse(f.controller.canConfirm());assertFalse(f.controller.confirm())
            assertTrue(f.controller.choosePage(f.record.pages.single()))
        }
        f.visual()
        withContext(Dispatchers.Main) {
            assertEquals(f.record.pages.single(),f.controller.state!!.frame!!.pageId)
            assertTrue(f.controller.canConfirm());assertEquals(f.source.target,f.actual().target)
        }
        assertFalse(f.session.canUndo);assertEquals(0,f.provider.writes);assertEquals(0,f.native.writes.get())
    }
    @Test fun anOriginalReadRetiredBetweenSuccessfulIoAndMainAdoptionEndsLoadingAndCanBeReloaded()=scenario(queued=true) {f->
        f.begin();val queue=f.queue!!
        withContext(Dispatchers.Main) {assertTrue(queue.next())}
        f.await {queue.pending()};assertEquals(1,f.leases())
        f.deleteAndRestore()
        f.pumpUntil {f.idle() && f.leases()==0}
        withContext(Dispatchers.Main) {
            assertEquals(SourceTargetEditStep.CHANGED,f.controller.state!!.step)
            assertFalse(f.controller.canConfirm());assertTrue(f.controller.reload())
        }
        f.visual()
        withContext(Dispatchers.Main) {assertTrue(f.controller.canConfirm());assertEquals(f.source.target,f.actual().target)}
        assertFalse(f.session.canUndo);assertEquals(0,f.provider.writes);assertEquals(0,f.native.writes.get())
    }

}
