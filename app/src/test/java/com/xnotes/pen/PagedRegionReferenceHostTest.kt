package com.xnotes.pen

import android.view.MotionEvent
import com.xnotes.canvas.PageSourceRegion
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.enableReferenceAnchors
import com.xnotes.core.model.fixedWriteSnapshot
import com.xnotes.mindmap.SourceScope
import com.xnotes.ui.PageReferencePreparation
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Fixed page-local geometry on real owner/model, independent of the current displayed page. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class PagedRegionReferenceHostTest {
    private val area=Rect(20.0,40.0,160.0,100.0)
    private suspend fun open(f:EditorPageCopyFixture) {
        withContext(Dispatchers.IO) {f.seed(f.target)}
        withContext(Dispatchers.Main) {f.editor.openAsync(f.targetUri,"Region note.xnote")}
    }
    @Test fun aFixedRegionKeepsItsActualPageThroughPageFlipReorderAndPageCopy()=runParkingScenario {f->
        open(f)
        withContext(Dispatchers.Main) {
            val doc=f.editor.state.document;val page=doc.pages[1];val id=page.referenceIdentity.id
            assertTrue(f.editor.requestPageRegionReference(PageSourceRegion(doc,page,area)))
            val request=f.editor.pageReferenceRequest!!
            assertEquals(area,request.region);assertEquals(com.xnotes.R.string.graph_page_region_context,request.contextNoticeRes)
            f.editor.goToPage(0);assertTrue(request.current())
            assertTrue(f.editor.movePages(listOf(page),0));assertTrue(request.current())
            f.editor.duplicatePages(listOf(0));assertTrue(request.current())
            assertSame(page,doc.pages.first());assertNotEquals(id,doc.pages[1].referenceIdentity.id)
            val result=request.prepare();assertTrue("Actual fixed page source: $result",result is PageReferencePreparation.Ready)
            val source=(result as PageReferencePreparation.Ready).source
            assertEquals(SourceScope.PAGE,source.scope);assertEquals(id,source.pageId);assertEquals(area,source.region)
            assertEquals(doc.identity.documentId,source.documentId);assertEquals(1,source.lastKnownPage)
            assertSame(page,request.page);assertTrue(f.editor.history.canUndo)
        }
        assertEquals(1,f.writes.get());assertEquals(0,f.deletes.get())
    }
    @Test fun deletingTheActualPageRetiresItsRequestAndUndoNeverReissuesTheOldTicket()=runParkingScenario {f->
        open(f);val bytes=f.target.readBytes()
        withContext(Dispatchers.Main) {
            val doc=f.editor.state.document;val page=doc.pages[1]
            assertTrue(f.editor.requestPageRegionReference(PageSourceRegion(doc,page,area)));val request=f.editor.pageReferenceRequest!!
            f.editor.deletePages(listOf(1));assertFalse(request.current());assertNull(f.editor.pageReferenceRequest)
            f.editor.undo();assertTrue(doc.pages.any {it===page})
            assertFalse(request.current());assertEquals(PageReferencePreparation.Changed,request.prepare())
        }
        assertArrayEquals(bytes,f.target.readBytes());assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }
    @Test fun anOldModelOrForeignPageCannotTurnItsRectangleIntoTheCurrentPagesRegion()=runParkingScenario {f->
        open(f)
        withContext(Dispatchers.Main) {
            val doc=f.editor.state.document;val page=doc.pages[1]
            assertFalse(f.editor.requestPageRegionReference(PageSourceRegion(Document(),page,area)))
            assertFalse(f.editor.requestPageRegionReference(PageSourceRegion(doc,Page(500.0,500.0),area)))
            assertNull(f.editor.pageReferenceRequest)
            assertEquals(f.context.getString(com.xnotes.R.string.graph_region_single_page_required),f.editor.message)
            assertTrue(f.editor.requestPageRegionReference(PageSourceRegion(doc,page,area)))
            val request=f.editor.pageReferenceRequest!!
            doc.path=f.sourceUri;assertFalse(request.current());assertEquals(PageReferencePreparation.Changed,request.prepare())
        }
        assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }
    @Test fun eitherPhysicalPanesActiveInputAndSourceStylePreviewRejectCaptureWithoutSettlingThem()=runParkingScenario {f->
        val other=openParkingPanes(f)
        withContext(Dispatchers.Main) {
            val doc=f.editor.state.document;val target=PageSourceRegion(doc,doc.pages[0],area)
            parkingPen(other,MotionEvent.ACTION_DOWN,Pt(100.0,100.0))
            assertFalse(f.editor.requestPageRegionReference(target));assertTrue(other.stylusInContact)
            parkingPen(other,MotionEvent.ACTION_UP,Pt(150.0,140.0))
            f.addStroke(120.0,recordHistory=true);f.editor.selectAll()
            f.editor.restyleSelection(Rgba(70,70,70),null,preview=true)
            assertTrue(f.editor.controller.hasPendingContentTransaction)
            val before=f.editor.history.nextUndo
            assertFalse(f.editor.requestPageRegionReference(target))
            assertTrue(f.editor.controller.hasPendingContentTransaction);assertSame(before,f.editor.history.nextUndo)
            f.editor.restyleSelection(Rgba(70,70,70),null,preview=false)
            assertTrue(f.editor.requestPageRegionReference(target))
        }
        assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }
    @Test fun aKnownIncompleteNoteIsRefusedBeforeRegionPreparationCanFlushItsDirtyBody()=runParkingScenario {f->
        withContext(Dispatchers.IO) {
            f.seed(f.target)
            val entries=java.util.zip.ZipFile(f.target).use {zip->zip.entries().asSequence().map {entry->
                entry.name to zip.getInputStream(entry).use {it.readBytes()}
            }.toList()}
            val tmp=File(f.root,"partial-note.zip")
            java.util.zip.ZipOutputStream(tmp.outputStream()).use {zip->
                for((name,bytes) in entries) {
                    zip.putNextEntry(java.util.zip.ZipEntry(name))
                    val actual=if(name=="manifest.json") org.json.JSONObject(bytes.toString(Charsets.UTF_8)).apply {
                        getJSONArray("pages").getJSONObject(1).getJSONArray("items").put(org.json.JSONObject().put("kind","future-shape"))
                    }.toString().toByteArray(Charsets.UTF_8) else bytes
                    zip.write(actual);zip.closeEntry()
                }
            }
            tmp.copyTo(f.target,overwrite=true);tmp.delete()
        }
        val bytes=f.target.readBytes()
        withContext(Dispatchers.Main) {
            f.editor.openAsync(f.targetUri,"Partial note.xnote");assertTrue(f.editor.noteOpen)
            val doc=f.editor.state.document;val page=doc.pages[1]
            f.addStroke(120.0,recordHistory=true);f.stopDebounce()
            assertTrue(f.editor.requestPageRegionReference(PageSourceRegion(doc,page,area)))
            val request=f.editor.pageReferenceRequest!!
            assertEquals(PageReferencePreparation.Failed,request.prepare())
            assertTrue(f.editor.prepareReference(com.xnotes.storage.NativeReferenceTarget(doc.identity.documentId,
                page.referenceIdentity.id),area) is com.xnotes.ui.NativeReferencePreparation.Failed)
            assertSame(doc,f.editor.state.document);assertTrue(doc.dirty);assertTrue(f.editor.history.canUndo)
        }
        assertArrayEquals(bytes,f.target.readBytes());assertEquals(0,f.writes.get());assertEquals(0,f.writeOpens.get());assertEquals(0,f.deletes.get())
    }
    @Test fun aPdfRegionNamesItsActualAnnotationNoteAndStrictSavingPreservesTheOriginalPdfBytes()=runParkingScenario {f->
        val pdf=File(f.root,"original.pdf").apply {writeBytes(minimalPdf())};val originalPdf=pdf.readBytes()
        withContext(Dispatchers.IO) {
            f.seed()
            val portable=Document(mutableListOf(Page(500.0,500.0,pdfPage=0)),pdfFile=pdf).apply {
                enableReferenceAnchors()
            }
            f.target.outputStream().use {f.codec.write(portable.fixedWriteSnapshot(),it,embedPdf=true)}
        }
        val before=com.xnotes.format.BundleReferenceInfo.inspectStrict(f.target)
        withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri,"Reader.xnote")
            val prepared=f.editor.prepareReference(com.xnotes.storage.NativeReferenceTarget(before.header.identity!!.documentId,
                before.orderedPageIds.first()),selectedUri=f.targetUri)
            assertTrue(prepared is com.xnotes.ui.NativeReferencePreparation.Ready)
            assertTrue(f.editor.openReference((prepared as com.xnotes.ui.NativeReferencePreparation.Ready).request) is com.xnotes.ui.NativeReferenceResult.Opened)
            val doc=f.editor.state.document;val page=doc.pages.first();val history=f.editor.history
            assertEquals(0,page.pdfPage)
            assertTrue(f.editor.requestPageRegionReference(PageSourceRegion(doc,page,area)))
            val request=f.editor.pageReferenceRequest!!
            assertTrue(request.prepare() is PageReferencePreparation.ModeRequired)
            val clean=request.selectMode(com.xnotes.ui.SourceCreationMode.NOTE_PAGE);assertTrue(clean is PageReferencePreparation.Ready)
            assertEquals(before.header.identity!!.documentId,(clean as PageReferencePreparation.Ready).source.documentId)
            assertEquals(page.referenceIdentity.id,clean.source.pageId);assertEquals(area,clean.source.region)
            assertEquals(0,f.writes.get())
            f.addStroke(120.0,recordHistory=true);f.stopDebounce()
            assertTrue(request.prepare() is PageReferencePreparation.Ready)
            assertSame(doc,f.editor.state.document);assertSame(history,f.editor.history);assertTrue(history.canUndo)
            assertEquals(before.header.identity,doc.identity)
        }
        val after=com.xnotes.format.BundleReferenceInfo.inspectStrict(f.target)
        assertEquals(before.header.identity,after.header.identity);assertEquals(before.orderedPageIds,after.orderedPageIds)
        assertEquals(before.embeddedPdfHash,after.embeddedPdfHash)
        java.util.zip.ZipFile(f.target).use {zip->assertArrayEquals(originalPdf,zip.getInputStream(zip.getEntry(com.xnotes.format.BundlePdf.ENTRY)).readBytes())}
        assertArrayEquals(originalPdf,pdf.readBytes());assertEquals(1,f.writes.get());assertEquals(0,f.deletes.get())
        // PDF byte/identity/save contracts only; this is not PDFium, Surface or target-GPU rendering validation.
    }
    @Test fun aFailedStrictRegionSaveKeepsTheOriginalRequestAreaDirtyBodyAndRetryTarget()=runParkingScenario {f->
        open(f);val bytes=f.target.readBytes()
        withContext(Dispatchers.Main) {
            val doc=f.editor.state.document;val page=doc.pages[1]
            f.addStroke(120.0,recordHistory=true);f.stopDebounce()
            assertTrue(f.editor.requestPageRegionReference(PageSourceRegion(doc,page,area)));val request=f.editor.pageReferenceRequest!!
            f.beforeTargetRead={throw java.io.IOException("Before native source verification")}
            assertFalse(request.prepare() is PageReferencePreparation.Ready)
            assertTrue(request.current());assertEquals(area,request.region);assertTrue(doc.dirty);assertTrue(f.editor.history.canUndo)
            assertArrayEquals(bytes,f.target.readBytes())
            f.beforeTargetRead=null
            val result=request.prepare();assertTrue("Same fixed region retries the native save: $result",result is PageReferencePreparation.Ready)
            assertEquals(area,(result as PageReferencePreparation.Ready).source.region)
            assertEquals(page.referenceIdentity.id,result.source.pageId);assertSame(doc,f.editor.state.document)
        }
        assertEquals(1,f.writes.get());assertEquals(0,f.deletes.get())
    }
    private fun minimalPdf():ByteArray {
        val out=java.io.ByteArrayOutputStream();fun ascii(value:String) {out.write(value.toByteArray(Charsets.US_ASCII))}
        ascii("%PDF-1.4\n");val objects=listOf("<< /Type /Catalog /Pages 2 0 R >>","<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Resources << >> /Contents 4 0 R >>","<< /Length 4 >>\nstream\nq\nQ\nendstream")
        val offsets=IntArray(objects.size+1)
        objects.forEachIndexed {at,body->offsets[at+1]=out.size();ascii("${at+1} 0 obj\n$body\nendobj\n")}
        val xref=out.size();ascii("xref\n0 ${offsets.size}\n0000000000 65535 f \n")
        for(at in 1 until offsets.size) ascii(String.format(java.util.Locale.ROOT,"%010d 00000 n \n",offsets[at]))
        ascii("trailer\n<< /Size ${offsets.size} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n");return out.toByteArray()
    }
}
