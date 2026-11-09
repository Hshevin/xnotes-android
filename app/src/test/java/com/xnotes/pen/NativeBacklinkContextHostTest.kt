package com.xnotes.pen

import com.xnotes.core.history.AddItem
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.fixedWriteSnapshot
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.format.BundleReferenceInfo
import com.xnotes.ui.BacklinkPageIdentity
import com.xnotes.ui.Editor
import com.xnotes.ui.NativeBacklinkContext
import com.xnotes.ui.Pane
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual owner/page identities and source protection; no index/provider mock stands in for Editor. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class NativeBacklinkContextHostTest {
    private fun capture(editor:Editor):NativeBacklinkContext = requireNotNull(editor.captureBacklinkContext())
    private fun add(editor:Editor,x:Double) {
        val page=editor.state.document.pages.first()
        val stroke=Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),listOf(Sample(x,120.0,.5),Sample(x+20,150.0,.75)))
        page.items+=stroke;editor.history.push(AddItem(page,stroke));editor.state.document.dirty=true
    }

    @Test fun actualPageABAInvalidatesOldContextsWhileAnOrdinaryPenC1KeepsTheSameReadingEpoch()=runParkingScenario {f->
        withContext(Dispatchers.IO) {f.seed()}
        withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri,"source.xnote");f.editor.view.layout(0,0,1000,1000);f.editor.selectTool(Tool.PEN)
            f.editor.goToPage(0)
            val a=capture(f.editor);val first=f.editor.backlinkContextVersion
            assertEquals(f.editor.state.document.pages[0].referenceIdentity.id,a.pageId)
            assertEquals(BacklinkPageIdentity.AVAILABLE,a.pageIdentity);assertTrue(a.current())
            f.editor.goToPage(1)
            val b=capture(f.editor);val second=f.editor.backlinkContextVersion
            assertTrue(second>first);assertFalse(a.current());assertTrue(b.current())
            f.editor.goToPage(0)
            val returned=capture(f.editor);val third=f.editor.backlinkContextVersion
            assertTrue(third>second);assertEquals(a.documentId,returned.documentId);assertEquals(a.pageId,returned.pageId)
            assertFalse("Returning to the same literal PageID must not revive A's old async query",a.current())
            assertFalse(b.current());assertTrue(returned.current())
            parkingPen(f.editor,android.view.MotionEvent.ACTION_DOWN,com.xnotes.core.geometry.Pt(100.0,100.0))
            parkingPen(f.editor,android.view.MotionEvent.ACTION_MOVE,com.xnotes.core.geometry.Pt(140.0,120.0))
            parkingPen(f.editor,android.view.MotionEvent.ACTION_UP,com.xnotes.core.geometry.Pt(170.0,140.0))
            assertTrue(returned.current());assertEquals("Writing C1 must not rescan a stable source target",third,f.editor.backlinkContextVersion)
            assertTrue(f.editor.state.document.dirty);assertTrue(f.editor.history.canUndo)
            assertEquals(1,f.editor.state.document.pages[0].items.filterIsInstance<Stroke>().size)
            assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
        }
    }

    @Test fun legacyPdfAndInvalidLivePageDirectoriesAreExplicitAndNeverMintOrRewriteSourceData() {
        for(legacy in listOf(true,false)) runParkingScenario {f->
            val pdf=minimalPdf();val input=File(f.root,"original.pdf").apply {writeBytes(pdf)}
            var managed:File?=null
            withContext(Dispatchers.IO) {
                if(legacy) {
                    managed=f.storage.pdfResources.admit(input,"Original PDF")
                    val document=Document(mutableListOf(Page(500.0,500.0),Page(500.0,500.0)),pdfFile=managed)
                    document.identity=document.identity.copy(pdfResource=managed!!.nameWithoutExtension,externalPdf=true)
                    f.storage.pdfResources.pin("note:${document.identity.documentId}",setOf(managed!!.nameWithoutExtension))
                    f.source.outputStream().use {f.codec.write(document.fixedWriteSnapshot(),it,embedPdf=false)}
                } else f.seed()
            }
            withContext(Dispatchers.Main) {
                f.editor.openAsync(f.sourceUri,"source.xnote");f.editor.view.layout(0,0,1000,1000);f.editor.goToPage(0)
                val doc=f.editor.state.document;val history=f.editor.history;add(f.editor,120.0)
                if(!legacy) {doc.pages.add(doc.pages[1]);doc.dirty=true}
                val ids=doc.pages.map {it.referenceIdentity.id};val revision=doc.bodyRevision
                val bytes=f.source.readBytes();val writes=f.writes.get()
                val context=capture(f.editor)
                assertEquals(doc.identity.documentId,context.documentId)
                assertEquals(if(legacy) BacklinkPageIdentity.NOT_ENABLED else BacklinkPageIdentity.INVALID,context.pageIdentity)
                assertNull(context.pageId);assertTrue("The explicit unavailable-page state must remain a displayable current context",context.current())
                repeat(3) {capture(f.editor)}
                assertEquals(ids,doc.pages.map {it.referenceIdentity.id});assertEquals(revision,doc.bodyRevision)
                assertSame(history,f.editor.history);assertTrue(history.canUndo);assertTrue(doc.dirty)
                assertArrayEquals(bytes,f.source.readBytes());assertEquals(writes,f.writes.get());assertEquals(0,f.deletes.get())
            }
            assertArrayEquals(pdf,input.readBytes());managed?.let {assertArrayEquals(pdf,it.readBytes())}
            // Valid PDF byte/managed-original contract only; no PDFium page painting is claimed.
        }
    }

    @Test fun independentSecondaryAndSharedBorrowerKeepTheirOwnReadPageAndActualOwner()=runParkingScenario {f->
        val secondary=openParkingPanes(f)
        withContext(Dispatchers.Main) {
            f.editor.goToPage(0);secondary.goToPage(1)
            val primary=capture(f.editor);val independent=capture(secondary)
            assertNotEquals(primary.documentId,independent.documentId)
            assertEquals(secondary.state.document.pages[1].referenceIdentity.id,independent.pageId)
            f.editor.goToPage(1)
            assertFalse(primary.current());assertTrue(independent.current())
            assertTrue(secondary.openSharedView(f.editor));secondary.view.layout(0,0,1000,1000)
            secondary.goToPage(0);f.editor.goToPage(1)
            val owner=capture(f.editor);val borrower=capture(secondary)
            assertSame(f.editor,secondary.documentOwner);assertSame(f.editor.state.document,secondary.state.document)
            assertEquals(owner.documentId,borrower.documentId);assertNotEquals(owner.pageId,borrower.pageId)
            assertFalse(independent.current())
            f.editor.focusPane(Pane.SECONDARY)
            assertTrue(owner.current());assertTrue(borrower.current())
            secondary.goToPage(1)
            assertFalse(borrower.current());assertTrue(owner.current())
            assertEquals(f.editor.state.document.pages[1].referenceIdentity.id,capture(secondary).pageId)
            assertFalse(f.editor.state.document.dirty);assertFalse(f.editor.history.canUndo)
            assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
        }
    }

    @Test fun sameModelSaveAsAndAPendingReplacementRetireTheOldSourceContext()=runParkingScenario {f->
        withContext(Dispatchers.IO) {f.seed()}
        val original=withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri,"source.xnote");f.editor.view.layout(0,0,1000,1000);f.editor.goToPage(0)
            add(f.editor,120.0);capture(f.editor)
        }
        val model=f.editor.state.document;val page=model.pages[0];val done=CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {f.editor.saveAsToThen(f.targetUri,"independent.xnote") {done.complete(it)}}
        assertTrue(done.await())
        withContext(Dispatchers.Main) {
            assertSame(model,f.editor.state.document);assertSame(page,model.pages[0]);assertFalse(original.current())
            val copied=capture(f.editor);assertNotEquals(original.documentId,copied.documentId);assertNotEquals(original.pageId,copied.pageId)
            assertTrue(copied.current());val writes=f.writes.get();val bytes=f.target.readBytes()
            capture(f.editor);assertEquals(writes,f.writes.get());assertArrayEquals(bytes,f.target.readBytes())
            f.editor.newNote(setOf(f.editor.discardForOpen()))
            assertFalse("A queued replacement intention must already retire its former source query",copied.current())
        }
    }

    @Test fun capturingBacklinksUsesTheOriginalReadonlyBaseForLaterWritesAndNeverWashesExternalCIntoRamA()=runParkingScenario {f->
        withContext(Dispatchers.IO) {f.seed()}
        val model=withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri,"source.xnote");f.editor.view.layout(0,0,1000,1000);f.editor.goToPage(0)
            add(f.editor,120.0);f.editor.state.document
        }
        withContext(Dispatchers.IO) {
            val external=f.source.inputStream().use {f.codec.read(it)}
            external.pages[0].items+=Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),listOf(Sample(300.0,100.0,.5),Sample(340.0,130.0,.7)))
            f.source.outputStream().use {f.codec.write(external.fixedWriteSnapshot(),it)}
        }
        val externalBytes=f.source.readBytes()
        withContext(Dispatchers.Main) {
            @Suppress("UNCHECKED_CAST")
            val stamps=Editor::class.java.getDeclaredField("knownStamps").apply {isAccessible=true}.get(f.editor) as MutableMap<String,Any?>
            stamps.clear()
            val context=capture(f.editor);assertTrue(context.current());assertSame(model,f.editor.state.document)
            assertFalse("Readonly source context must retain A's baseline, not authorize overwriting same-ID external C",f.editor.flushSplitDocument())
            assertTrue(model.dirty);assertTrue(f.editor.history.canUndo);assertTrue(context.current())
            assertArrayEquals(externalBytes,f.source.readBytes());assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
        }
        assertFalse(f.storage.hasRecovery(f.sourceUri))
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
