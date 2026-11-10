package com.xnotes.mindmap

import android.net.Uri
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.PdfKey
import com.xnotes.core.model.enableReferenceAnchors
import com.xnotes.core.model.fixedWriteSnapshot
import com.xnotes.core.pdf.PdfPageGeometry
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.AnchorMetadata
import com.xnotes.format.BundleIdentity
import com.xnotes.format.BundleMigration
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidRasterSurface
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.storage.OriginalPdfAccess
import com.xnotes.storage.OriginalPdfCatalog
import com.xnotes.storage.OriginalPdfEngine
import com.xnotes.storage.OriginalPdfPageInfo
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual saved bundles, registry, raw bytes and final reads. Only the original PDF engine is
 * injected; its affine geometry is intentional test data, not a PDFium pixel assertion. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapSourceTargetAccessTest {
    private fun scenario(block:(MapBacklinkLibraryTest.Fixture)->Unit)=MapBacklinkLibraryTest().scenario {f->
        withContext(Dispatchers.IO) {block(f)}
    }
    private fun note(f:MapBacklinkLibraryTest.Fixture,name:String="actual-note.xcanvas"):Pair<File,Document> {
        val doc=Document(mutableListOf(Page(500.0,600.0),Page(700.0,800.0))).apply {enableReferenceAnchors()}
        val file=File(f.notes,name)
        file.outputStream().use {DocumentCodec(AndroidImageCodec(),AndroidTextMeasurer()).write(doc.fixedWriteSnapshot(),it)}
        return file to doc
    }
    private fun canvas(f:MapBacklinkLibraryTest.Fixture,name:String="actual-canvas.xnote"):Pair<File,InfiniteDocument> {
        val doc=InfiniteDocument();val file=File(f.notes,name)
        file.outputStream().use {CanvasCodec(AndroidImageCodec(),AndroidTextMeasurer()).write(doc,it)}
        return file to doc
    }
    @Test fun actualNativeKindsIgnoreDisplaySuffixAndFinalChoiceRereadsTheSourceSha()=scenario {f->
        val access=MapSourceTargetAccess(f.context,f.storage)
        val (file,doc)=note(f);val bytes=file.readBytes()
        val offer=access.inspectFile(f.uri(file)) as SourceTargetOffer.Native
        assertEquals(DocumentKind.NOTE,offer.offer.kind)
        val read=access.openOffer(offer)
        try {
            assertEquals(SourceRangeKind.NOTE,read.kind)
            val frame=read.frame(doc.pages[1].referenceIdentity.id)
            val region=Rect(-40.0,-30.0,120.0,180.0)
            val choice=read.prepareChoice(frame,SourceRangeScope.REGION,region)
            assertEquals(MapSourceTarget(doc.identity.documentId,SourceScope.PAGE,doc.pages[1].referenceIdentity.id,region),choice.target)
            assertEquals(2,choice.lastKnownPage);assertTrue(choice.current())
            assertArrayEquals(bytes,file.readBytes());assertEquals(0,f.writes.get())
            doc.pages.add(Page(900.0,1000.0));doc.enableReferenceAnchors()
            file.outputStream().use {DocumentCodec(AndroidImageCodec(),AndroidTextMeasurer()).write(doc.fixedWriteSnapshot(),it)}
            assertThrows(Exception::class.java) {read.prepareChoice(frame,SourceRangeScope.WHOLE_PAGE)}
        } finally {read.closeAndAwait()}
        assertFalse(read.current());assertFalse(offer.current());assertEquals(0,f.writes.get())
        val (other,_)=canvas(f);val otherOffer=access.inspectFile(f.uri(other)) as SourceTargetOffer.Native
        assertEquals(DocumentKind.CANVAS,otherOffer.offer.kind)
        access.openOffer(otherOffer).let {opened->try {assertEquals(SourceRangeKind.CANVAS,opened.kind)} finally {opened.closeAndAwait()}}
    }
    @Test fun aCanvasKeepsAnEmptyOldRangeAndRendersFiniteViewportsBeyondTargetCoordinateLimits()=scenario {f->
        val (file,doc)=canvas(f);val bytes=file.readBytes();val access=MapSourceTargetAccess(f.context,f.storage)
        access.inspectFile(f.uri(file)).closeAndAwait()
        val max=MindMapDocument.MAX_COORD;val region=Rect(max,max,max,max)
        val target=MapSourceTarget(doc.identity.documentId,SourceScope.CANVAS_REGION,region=region)
        val read=access.openCurrent(target)
        try {
            val frame=read.frame();assertEquals(region,frame.initialRegion);assertEquals(region,frame.bounds)
            val choice=read.prepareChoice(frame,SourceRangeScope.REGION,region)
            assertEquals(target,choice.target)
            val viewport=Rect(3*max,-4*max,2*max,3*max)
            val image=read.render(frame,viewport,64)
            assertEquals(viewport,image.frame);assertEquals(43,image.bitmap.width);assertEquals(64,image.bitmap.height)
            image.close();assertTrue(image.bitmap.isRecycled)
            assertThrows(IllegalArgumentException::class.java) {read.prepareChoice(frame,SourceRangeScope.REGION,region.copy(x=max+1))}
            assertThrows(IllegalArgumentException::class.java) {read.render(frame,Rect(Double.NaN,0.0,1.0,1.0),64)}
        } finally {read.closeAndAwait()}
        assertArrayEquals(bytes,file.readBytes());assertEquals(0,f.writes.get())
    }
    private fun raw(file:File,kind:DocumentKind,id:String=UUID.randomUUID().toString(),anchored:Boolean=true,items:String="[]") {
        ZipOutputStream(file.outputStream()).use {zip->
            BundleIdentity(id).write(zip)
            if(kind==DocumentKind.NOTE && anchored) AnchorMetadata(id).write(zip)
            val content=if(kind==DocumentKind.NOTE) {
                val page=if(anchored) "\"reference_id\":\"${UUID.randomUUID()}\"," else ""
                "\"pages\":[{$page\"items\":$items}]"
            } else "\"items\":$items"
            zip.putNextEntry(ZipEntry("manifest.json"));zip.write("""{"format":"${kind.extension}","version":1,"has_pdf":false,$content}""".toByteArray());zip.closeEntry()
        }
    }
    @Test fun strictNativeSelectionRejectsUnmaterializedObjectsAndCancellationWithoutChangingItsSource()=scenario {f->
        val access=MapSourceTargetAccess(f.context,f.storage)
        for(kind in listOf(DocumentKind.NOTE,DocumentKind.CANVAS)) {
            for(items in listOf("""[{"kind":"future-widget","rect":[0,0,10,10]}]""",
                """[{"kind":"image","asset":"assets/missing.png","rect":[0,0,10,10],"src_w":10,"src_h":10}]""")) {
                val file=File(f.notes,"bad-${UUID.randomUUID()}.${kind.extension}")
                raw(file,kind,items=items);val bytes=file.readBytes();val offer=access.inspectFile(f.uri(file))
                try {assertThrows(Exception::class.java) {access.openOffer(offer)}} finally {offer.closeAndAwait()}
                assertArrayEquals(bytes,file.readBytes())
            }
        }
        val (file,_)=note(f,"cancelled.xnote");val bytes=file.readBytes();val offer=access.inspectFile(f.uri(file))
        try {assertThrows(CancellationException::class.java) {access.openOffer(offer) {throw CancellationException()}}}
        finally {offer.closeAndAwait()}
        assertArrayEquals(bytes,file.readBytes());assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
        assertTrue(File(f.context.cacheDir,"source-target-assets").listFiles().orEmpty().isEmpty())
    }
    @Test fun aDeletedPermanentPageOnlyPreviewsAndNeverSelectsTheSameOrdinalImplicitly()=scenario {f->
        val (file,doc)=note(f);val access=MapSourceTargetAccess(f.context,f.storage)
        access.inspectFile(f.uri(file)).closeAndAwait()
        val missing=UUID.randomUUID().toString();val target=MapSourceTarget(doc.identity.documentId,SourceScope.PAGE,missing)
        val read=access.openCurrent(target)
        try {
            assertEquals(SourceRangeInitialIssue.PAGE_DELETED,read.initialIssue);assertNull(read.initialPageId)
            val firstPreview=read.frame();assertNull(firstPreview.pageId);assertFalse(firstPreview.regionAllowed)
            assertThrows(IllegalArgumentException::class.java) {read.prepareChoice(firstPreview,SourceRangeScope.WHOLE_PAGE)}
            val explicit=read.frame(doc.pages[1].referenceIdentity.id)
            assertEquals(doc.pages[1].referenceIdentity.id,read.prepareChoice(explicit,SourceRangeScope.WHOLE_PAGE).target.pageId)
        } finally {read.closeAndAwait()}
        assertEquals(0,f.writes.get())
    }
    @Test fun anUnactivatedNoteRequiresSeparateExplicitActivationAndClosingTheReadDoesNotRollbackIt()=scenario {f->
        val file=File(f.notes,"old.xnote");raw(file,DocumentKind.NOTE,anchored=false)
        val original=file.readBytes();val access=MapSourceTargetAccess(f.context,f.storage)
        val offer=access.inspectFile(f.uri(file)) as SourceTargetOffer.Native
        val read=access.openOffer(offer)
        assertTrue(read.needsPageActivation);assertTrue(read.pages.isEmpty());assertArrayEquals(original,file.readBytes())
        assertEquals(0,f.writes.get());read.closeAndAwait()
        // A fresh explicitly accepted offer is used; closed ordinary reads cannot grant this write.
        val explicit=access.inspectFile(f.uri(file)) as SourceTargetOffer.Native
        val activated=access.activatePages(explicit);explicit.closeAndAwait()
        val prepared=access.openOffer(activated)
        val page=try {
            assertFalse(prepared.needsPageActivation);assertEquals(1,prepared.pages.size)
            prepared.prepareChoice(prepared.frame(prepared.pages.single().id),SourceRangeScope.WHOLE_PAGE).target
        } finally {prepared.closeAndAwait()}
        // App-owned activation publishes by OwnedRecoveryTarget's verified atomic file move,
        // bypassing the provider bridge counter used for external SAF writes.
        val originalSha=java.security.MessageDigest.getInstance("SHA-256").digest(original).joinToString("") {"%02x".format(it)}
        assertNotEquals(originalSha,activated.offer.catalog!!.sha256)
        assertEquals(activated.offer.catalog!!.sha256,BundleMigration.hash(file))
        assertFalse(original.contentEquals(file.readBytes()))
        val reopened=access.openCurrent(page)
        try {assertEquals(page.pageId,reopened.pages.single().id);assertFalse(reopened.needsPageActivation)} finally {reopened.closeAndAwait()}
        assertEquals(0,f.deletes.get())
    }
    @Test fun actualMapsAllowOnlyWholeFilesAndConfirmRejectsAnExternalChangeWithoutWritingIt()=scenario {f->
        val graph=MindMapDocument();val file=f.graph("not-a-pdf.pdf",graph);val original=file.readBytes()
        val access=MapSourceTargetAccess(f.context,f.storage);val offer=access.inspectFile(f.uri(file))
        assertTrue(offer is SourceTargetOffer.Map);val read=access.openOffer(offer)
        try {
            val frame=read.frame();assertEquals(SourceRangeKind.MAP,read.kind);assertFalse(frame.regionAllowed)
            val choice=read.prepareChoice(frame,SourceRangeScope.WHOLE_FILE);assertEquals(MapSourceTarget(graph.id,SourceScope.FILE),choice.target)
            assertThrows(IllegalArgumentException::class.java) {read.prepareChoice(frame,SourceRangeScope.REGION,Rect(0.0,0.0,1.0,1.0))}
            assertArrayEquals(original,file.readBytes());assertEquals(0,f.writes.get())
            val external=MapCodec.encode(graph.update(graph.root.id) {it.copy(text="Externally changed")})
            file.writeBytes(external)
            assertThrows(IllegalArgumentException::class.java) {read.prepareChoice(frame,SourceRangeScope.WHOLE_FILE)}
            assertArrayEquals(external,file.readBytes())
        } finally {read.closeAndAwait()}
        assertEquals(0,f.writes.get())
    }
    @Test fun aPdfSignatureQuotedInsideActualMapJsonCannotReclassifyItAsAnOriginalPdf()=scenario {f->
        val empty=MindMapDocument()
        val graph=empty.update(empty.root.id) {it.copy(text="A quoted %PDF-1.7 signature remains ordinary concept text")}
        val file=f.graph("quoted-header.pdf",graph);val bytes=file.readBytes()
        assertTrue(bytes.take(1024).toByteArray().toString(Charsets.UTF_8).contains("%PDF-"))
        val access=MapSourceTargetAccess(f.context,f.storage);val offer=access.inspectFile(f.uri(file))
        assertTrue(offer is SourceTargetOffer.Map)
        val read=access.openOffer(offer)
        try {
            assertEquals(SourceRangeKind.MAP,read.kind);assertEquals(graph.id,read.documentId)
            assertEquals(MapSourceTarget(graph.id,SourceScope.FILE),
                read.prepareChoice(read.frame(),SourceRangeScope.WHOLE_FILE).target)
        } finally {read.closeAndAwait()}
        assertTrue(OriginalPdfCatalog(f.storage.registry).list().records.isEmpty())
        assertArrayEquals(bytes,file.readBytes());assertEquals(0,f.writes.get())
    }
    private class Engine(private val opened:AtomicInteger,private val closed:AtomicInteger):OriginalPdfEngine {
        init {opened.incrementAndGet()}
        override val pageCount=2
        override fun pageInfo(pdfKey:Int):OriginalPdfPageInfo {
            val turns=PdfKey.turn(pdfKey)
            val rotated=turns%2==1
            return OriginalPdfPageInfo(if(rotated) Pt(800.0,600.0) else Pt(600.0,800.0),
                PdfPageGeometry.of(40.0,50.0,640.0,850.0,turns*90))
        }
        override fun renderRegion(pdfKey:Int,pxPerPt:Double,leftPx:Int,topPx:Int,width:Int,height:Int)=AndroidRasterSurface.create(width,height)
        override fun closeAndAwait() {closed.incrementAndGet()}
    }
    @Test fun rawPdfRetentionAndTombstoneRestoreAreSeparateActionsAndPreservePermanentPages()=scenario {f->
        val opened=AtomicInteger();val closed=AtomicInteger()
        val originals=OriginalPdfAccess(f.context,{Engine(opened,closed)},f.storage)
        val access=MapSourceTargetAccess(f.context,f.storage,originals);val catalog=OriginalPdfCatalog(f.storage.registry)
        val file=File(f.notes,"actual-raw-pdf.xmap").apply {writeText("%PDF-1.7\nunchanged original bytes")}
        val bytes=file.readBytes();val offer=access.inspectFile(f.uri(file)) as SourceTargetOffer.RawPdf
        assertTrue(catalog.list().records.isEmpty());assertEquals(0,opened.get());assertEquals(0,f.writes.get())
        val retained=(access.retainPdf(offer) as SourcePdfRetention.Retained).read
        val id=retained.documentId;val pages=retained.pages.map {it.id}
        try {
            assertEquals(SourceRangeKind.PDF_ORIGINAL,retained.kind);assertFalse(offer.current())
            val frame=retained.frame(pages[1],1);val region=Rect(20.0,30.0,100.0,110.0)
            val choice=retained.prepareChoice(frame,SourceRangeScope.REGION,region)
            assertEquals(pages[1],choice.target.pageId);assertEquals(1,choice.target.originalPdf!!.pageIndex)
            assertEquals(1,choice.target.originalPdf!!.viewTurns)
            assertEquals(Rect(70.0,70.0,110.0,100.0),choice.target.originalPdf!!.userRegion)
            assertThrows(IllegalArgumentException::class.java) {retained.prepareChoice(frame,SourceRangeScope.WHOLE_FILE)}
            assertThrows(IllegalArgumentException::class.java) {retained.prepareChoice(frame,SourceRangeScope.REGION,Rect(-1.0,0.0,10.0,10.0))}
        } finally {retained.closeAndAwait()}
        assertFalse(catalog.observe(id)!!.record.deleted) // cancelling the graph target leaves accepted retention.
        catalog.delete(catalog.observe(id)!!)
        val second=access.inspectFile(f.uri(file)) as SourceTargetOffer.RawPdf
        val pending=(access.retainPdf(second) as SourcePdfRetention.RestoreRequired).offer
        assertTrue(catalog.observe(id)!!.record.deleted);assertTrue(pending.current());assertEquals(opened.get(),closed.get())
        val restored=access.restorePdf(pending)
        try {assertEquals(id,restored.documentId);assertEquals(pages,restored.pages.map {it.id})} finally {restored.closeAndAwait()}
        assertFalse(pending.current());assertFalse(catalog.observe(id)!!.record.deleted)
        assertEquals(opened.get(),closed.get());assertArrayEquals(bytes,file.readBytes());assertEquals(0,f.writes.get())
        assertTrue(File(f.context.cacheDir,"source-target-raw").listFiles().orEmpty().isEmpty())
        f.storage.registry.readableDatabase.rawQuery("SELECT COUNT(*) FROM leases",null).use {assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))}
    }
}
