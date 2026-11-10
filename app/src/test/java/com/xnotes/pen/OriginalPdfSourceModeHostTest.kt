package com.xnotes.pen

import com.xnotes.canvas.PageSourceRegion
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Room
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.PdfKey
import com.xnotes.core.pdf.PdfPageGeometry
import com.xnotes.mindmap.SourceScope
import com.xnotes.storage.OriginalPdfAccess
import com.xnotes.storage.OriginalPdfCatalog
import com.xnotes.storage.OriginalPdfEngine
import com.xnotes.storage.OriginalPdfPageInfo
import com.xnotes.ui.SourceCreationIssue
import com.xnotes.ui.PageReferencePreparation
import com.xnotes.ui.SourceCreationMode
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Mode choice signs a fixed request before the shared selection/screenshot menu is dismissed. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class OriginalPdfSourceModeHostTest {
    private fun fakeAccess(f:EditorPageCopyFixture,onOpen:()->Unit={}):OriginalPdfAccess=OriginalPdfAccess(f.context,{onOpen();object:OriginalPdfEngine {
        override val pageCount=1
        override fun pageInfo(pdfKey:Int):OriginalPdfPageInfo {
            val turn=PdfKey.turn(pdfKey);val across=turn%2!=0
            return OriginalPdfPageInfo(Pt(if(across) 792.0 else 612.0,if(across) 612.0 else 792.0),
                PdfPageGeometry.of(10.0,20.0,622.0,812.0,turn*90))
        }
        override fun renderRegion(pdfKey:Int,pxPerPt:Double,leftPx:Int,topPx:Int,width:Int,height:Int):com.xnotes.platform.AndroidRasterSurface?=
            error("Creation must not render the annotation note")
        override fun closeAndAwait() {}
    }},f.storage)
    @Test fun originalWholePageKeepsActualOriginalIdentityWithoutSavingDirtyNoteOrEnablingItsPageIds()=runParkingScenario {f->
        withContext(Dispatchers.Main) {
            f.editor.newNote();val doc=f.editor.state.document;val page=doc.pages.first()
            page.pdfPage=0;page.pdfTurn=1;page.rooms=listOf(Room(20.0,150.0))
            val pdf=File(f.root,"actual-original.pdf").apply {writeText("controlled engine original bytes")}
            val bytes=pdf.readBytes();doc.pdfFile=pdf;f.editor.originalPdfAccess=fakeAccess(f)
            f.addStroke(140.0,recordHistory=true);f.stopDebounce()
            val identity=doc.identity;val revision=doc.bodyRevision;val history=f.editor.history;val next=history.nextUndo
            assertTrue(f.editor.requestPageReference(page));val request=f.editor.pageReferenceRequest!!
            assertTrue(request.prepare() is PageReferencePreparation.ModeRequired)
            val result=request.selectMode(SourceCreationMode.PDF_ORIGINAL)
            assertTrue("Original creation result: $result",result is PageReferencePreparation.Ready)
            val source=(result as PageReferencePreparation.Ready).source
            assertEquals(SourceScope.PDF_ORIGINAL,source.scope);assertNull(source.region);assertNotNull(source.pageId)
            assertNotEquals(identity.documentId,source.documentId);assertEquals(1,source.originalPdf!!.viewTurns)
            assertNull(source.originalPdf!!.userRegion);assertNull(page.referenceIdentity.id);assertFalse(doc.hasReferenceAnchors)
            assertSame(identity,doc.identity);assertEquals(revision,doc.bodyRevision);assertTrue(doc.dirty)
            assertSame(history,f.editor.history);assertSame(next,history.nextUndo)
            request.clear()
            val catalog=OriginalPdfCatalog.get(f.context);val kept=catalog.observe(source.documentId)!!
            assertFalse(kept.record.deleted);assertEquals(source.pageId,kept.record.pages.single())
            assertArrayEquals(bytes,pdf.readBytes())
            // Dismissing the source flow leaves its independently retained original available.
            assertTrue(f.editor.requestPageReference(page))
            val repeated=f.editor.pageReferenceRequest!!.selectMode(SourceCreationMode.PDF_ORIGINAL) as PageReferencePreparation.Ready
            assertEquals(source.target,repeated.source.target);f.editor.pageReferenceRequest!!.clear()
        }
        assertEquals(0,f.writes.get());assertEquals(0,f.writeOpens.get());assertEquals(0,f.deletes.get())
    }
    @Test fun anUnrepresentableInsertedSpaceRegionDoesNotRetainAnythingAndStillAllowsTheNoteBranch()=runParkingScenario {f->
        withContext(Dispatchers.Main) {
            f.editor.newNote();val doc=f.editor.state.document;val page=doc.pages.first()
            page.pdfPage=0;page.rooms=listOf(Room(100.0,30.0));doc.pdfFile=File(f.root,"raw.pdf").apply {writeText("raw")}
            var opened=0;f.editor.originalPdfAccess=fakeAccess(f) {opened++}
            assertTrue(f.editor.requestPageRegionReference(PageSourceRegion(doc,page,Rect(80.0,80.0,90.0,90.0))))
            val request=f.editor.pageReferenceRequest!!
            val result=request.selectMode(SourceCreationMode.PDF_ORIGINAL) as PageReferencePreparation.ModeRequired
            assertEquals(SourceCreationIssue.ORIGINAL_REGION,result.issue);assertEquals(0,opened)
            assertTrue(OriginalPdfCatalog.get(f.context).list().records.isEmpty())
            assertEquals(PageReferencePreparation.SaveRequired,request.selectMode(SourceCreationMode.NOTE_PAGE));request.clear()
        }
        assertEquals(0,f.writes.get())
    }
    @Test fun aDeletedOriginalRequiresTheExplicitRestoreActionAndKeepsItsExistingPermanentIds()=runParkingScenario {f->
        withContext(Dispatchers.Main) {
            f.editor.newNote();val doc=f.editor.state.document;val page=doc.pages.first()
            page.pdfPage=0;doc.pdfFile=File(f.root,"original.pdf").apply {writeText("original bytes")}
            f.editor.originalPdfAccess=fakeAccess(f)
            assertTrue(f.editor.requestPageReference(page));val first=f.editor.pageReferenceRequest!!
            val source=(first.selectMode(SourceCreationMode.PDF_ORIGINAL) as PageReferencePreparation.Ready).source
            first.clear();val catalog=OriginalPdfCatalog.get(f.context)
            withContext(Dispatchers.IO) {catalog.delete(catalog.observe(source.documentId)!!)}
            assertTrue(f.editor.requestPageReference(page));val request=f.editor.pageReferenceRequest!!
            val required=request.selectMode(SourceCreationMode.PDF_ORIGINAL) as PageReferencePreparation.RestoreRequired
            assertTrue(catalog.observe(source.documentId)!!.record.deleted)
            val restored=request.restoreOriginal(required.offer) as PageReferencePreparation.Ready
            assertEquals(source.target,restored.source.target);assertFalse(catalog.observe(source.documentId)!!.record.deleted)
            assertNull(page.referenceIdentity.id);assertFalse(doc.hasReferenceAnchors);request.clear()
        }
        assertEquals(0,f.writes.get());assertEquals(0,f.writeOpens.get())
    }

    @Test fun wholePdfPageAndRegionUseTheSameChooserWithoutEnablingOrSavingTheNote()=runParkingScenario {f->
        withContext(Dispatchers.Main) {
            f.editor.newNote();val doc=f.editor.state.document;val page=doc.pages.first()
            page.pdfPage=0;page.rooms=listOf(Room(60.0,40.0));doc.pdfFile=File(f.root,"raw.pdf").apply {writeText("raw")}
            f.addStroke(140.0,recordHistory=true);f.stopDebounce()
            val identity=doc.identity;val revision=doc.bodyRevision;val nextUndo=f.editor.history.nextUndo
            assertFalse(doc.hasReferenceAnchors)
            assertTrue(f.editor.requestPageReference(page))
            val whole=f.editor.pageReferenceRequest!!
            assertTrue(whole.prepare() is PageReferencePreparation.ModeRequired)
            assertEquals(PageReferencePreparation.SaveRequired,whole.selectMode(SourceCreationMode.NOTE_PAGE))
            whole.clear()
            assertTrue(f.editor.requestPageRegionReference(PageSourceRegion(doc,page,Rect(20.0,30.0,40.0,20.0))))
            val region=f.editor.pageReferenceRequest!!
            f.editor.controller.clearSelection()
            assertTrue(region.current());assertTrue(region.prepare() is PageReferencePreparation.ModeRequired)
            assertEquals(Rect(20.0,30.0,40.0,20.0),region.region)
            assertSame(identity,doc.identity);assertEquals(revision,doc.bodyRevision);assertSame(nextUndo,f.editor.history.nextUndo)
            assertFalse(doc.hasReferenceAnchors);assertTrue(doc.dirty);region.clear()
        }
        assertEquals(0,f.writes.get());assertEquals(0,f.writeOpens.get())
    }
    @Test fun blankInsertedPageKeepsTheExistingNoteFlowAndCannotInventAnOriginalPdfPage()=runParkingScenario {f->
        withContext(Dispatchers.Main) {
            f.editor.newNote();val doc=f.editor.state.document;val page=doc.pages.first()
            doc.pdfFile=File(f.root,"raw.pdf").apply {writeText("raw")}
            assertNull(page.pdfPage);assertTrue(f.editor.requestPageReference(page))
            val request=f.editor.pageReferenceRequest!!
            assertEquals(PageReferencePreparation.SaveRequired,request.prepare())
            assertEquals(PageReferencePreparation.Failed,request.selectMode(SourceCreationMode.PDF_ORIGINAL))
            request.clear()
        }
    }
    @Test fun deletingAndUndoingTheSelectedActualPageDoesNotReviveTheOldModeChooser()=runParkingScenario {f->
        withContext(Dispatchers.Main) {
            f.editor.newNote();val doc=f.editor.state.document;val page=doc.pages.first()
            page.pdfPage=0;doc.pdfFile=File(f.root,"raw.pdf").apply {writeText("raw")}
            f.editor.addPageAtEnd()
            assertTrue(f.editor.requestPageReference(page));val request=f.editor.pageReferenceRequest!!
            assertTrue(request.prepare() is PageReferencePreparation.ModeRequired)
            f.editor.deletePages(listOf(0));f.editor.undo()
            assertTrue(doc.pages.any {it===page});assertFalse(request.current())
            assertEquals(PageReferencePreparation.Changed,request.selectMode(SourceCreationMode.PDF_ORIGINAL))
        }
    }
}
