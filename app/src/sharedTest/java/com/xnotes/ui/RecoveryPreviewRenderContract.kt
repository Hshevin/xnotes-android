package com.xnotes.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.xnotes.core.geometry.Pt
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.text.Paragraph
import com.xnotes.core.text.Run
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.BundleMigration
import com.xnotes.format.BundleIdentity
import com.xnotes.format.BundleSnapshot
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.storage.NotebookStorage
import com.xnotes.storage.PreparedRecoveryPreview
import com.xnotes.ui.theme.monochromePalette
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

/** Actual Editor CPU rendering from a held, strictly decoded recovery bundle. */
abstract class RecoveryPreviewRenderContract {
    protected abstract fun scenario(block:suspend (Editor,Context,File)->Unit)
    private class Material(val context:Context,val root:File):AutoCloseable {
        val codec=DocumentCodec(AndroidImageCodec(),AndroidTextMeasurer())
        val canvasCodec=CanvasCodec(AndroidImageCodec(),AndroidTextMeasurer())
        val storage=NotebookStorage(context)
        val pdfs=File(root,"preview-pdfs").apply {mkdirs()}
        val assets=File(root,"preview-assets").apply {mkdirs()}
        fun prepare(file:File,kind:DocumentKind)=PreparedRecoveryPreview.readLegacy(
            BundleSnapshot.take(file,root),kind,storage.pdfResources,codec,canvasCodec,pdfs,assets,"Historical body")
        fun note(doc:Document):File=File(root,"note-${UUID.randomUUID()}.xnote").apply {outputStream().use {codec.write(doc,it)}}
        fun canvas(doc:InfiniteDocument):File=File(root,"canvas-${UUID.randomUUID()}.xcanvas").apply {outputStream().use {canvasCodec.write(doc,it)}}
        override fun close()=storage.registry.close()
    }
    private fun shape(x:Double,y:Double,w:Double,h:Double)=ShapeItem(ShapeKind.RECTANGLE,Pt(x,y),Pt(x+w,y+h),Rgba(0,0,0),fillRgba=Rgba(0,0,0))
    private fun pixels(bitmap:Bitmap)=IntArray(bitmap.width*bitmap.height).also {bitmap.getPixels(it,0,bitmap.width,0,0,bitmap.width,bitmap.height)}
    private suspend fun rendered(editor:Editor,p:PreparedRecoveryPreview,index:Int=0,max:Int=160)=
        requireNotNull(editor.renderRecoveryPreviewPage(p,index,max))

    @Test fun heldNotesAndCanvasesIgnoreLiveViewStylesAndBoundBothImageDimensions()=scenario {editor,context,root->
        Material(context,root).use {f->
            val note=Document(mutableListOf(Page(500.0,800.0,mutableListOf(shape(50.0,300.0,120.0,80.0))),Page(12000.0,100.0),Page(100.0,12000.0)),dpi=72)
            note.flow.paragraphs+=Paragraph(mutableListOf(Run("val history = 42")),codeLang="kotlin")
            val canvas=InfiniteDocument().apply {add(shape(0.0,0.0,12000.0,100.0))}
            val inputs=listOf(f.note(note) to DocumentKind.NOTE,f.canvas(canvas) to DocumentKind.CANVAS)
            for((source,kind) in inputs) {
                val bytes=source.readBytes();val before=BundleMigration.fingerprints(source)
                f.prepare(source,kind).use {prepared->
                    editor.resetCodeTheme();editor.applyPreferences(editor.preferences.copy(uiAppearance="light"));editor.state.palette=monochromePalette(false)
                    editor.state.pageColorOverride=null
                    val first=rendered(editor,prepared)
                    val expected=pixels(first);val dimensions=first.width to first.height
                    assertTrue(expected.any {it!=Color.WHITE});assertTrue(first.width<=160 && first.height<=160);first.recycle()
                    editor.importCodeTheme("""
                        keyword = "#ff0000"
                        function = "#ff0000"
                        string = "#ff0000"
                        number = "#ff0000"
                        "ui.background" = { bg = "#00ff00" }
                    """.trimIndent().toByteArray(),"Recovery isolation fixture")
                    assertTrue(editor.hasCustomCodeTheme)
                    editor.applyPreferences(editor.preferences.copy(uiAppearance="dark"));editor.state.palette=monochromePalette(true)
                    editor.state.pageColorOverride=Rgba(255,0,0)
                    val live=editor.state.document;val dirty=live.dirty;val pane=editor.pane
                    val again=rendered(editor,prepared)
                    try {assertEquals(dimensions,again.width to again.height);assertArrayEquals(expected,pixels(again))} finally {again.recycle()}
                    for(index in 0 until prepared.pageCount) {
                        val image=rendered(editor,prepared,index,96)
                        try {assertTrue(image.width in 1..96);assertTrue(image.height in 1..96)} finally {image.recycle()}
                    }
                    assertSame(live,editor.state.document);assertEquals(dirty,live.dirty);assertEquals(pane,editor.pane)
                    assertNull(prepared.note?.path ?: prepared.canvas?.path)
                    val copy=prepared.prepareCopy(UUID.randomUUID().toString(),root)
                    try {assertEquals(before.filterKeys {it!=BundleIdentity.ENTRY},BundleMigration.fingerprints(copy.file).filterKeys {it!=BundleIdentity.ENTRY})}
                    finally {copy.file.delete()}
                }
                assertArrayEquals(bytes,source.readBytes())
            }
        }
    }
    @Test fun closedOrCancelledRenderingCannotConsumeTheHistoricalBodyOrChangeTheLiveDocument()=scenario {editor,context,root->
        Material(context,root).use {f->
            val source=f.note(Document(mutableListOf(Page(600.0,800.0,MutableList(120){i->shape((i%10)*45.0,(i/10)*45.0,20.0,20.0)}))))
            val bytes=source.readBytes();val live=editor.state.document;val dirty=live.dirty
            val prepared=f.prepare(source,DocumentKind.NOTE)
            try {
                var checks=0
                try {
                    editor.renderRecoveryPreviewPage(prepared,0,160) {if(++checks==25) throw CancellationException("cancel recovery paint")}
                    fail("A cancelled render returned a successful image")
                } catch(_:CancellationException) {assertEquals(25,checks)}
                val retry=rendered(editor,prepared);retry.recycle()
                assertSame(live,editor.state.document);assertEquals(dirty,live.dirty);assertArrayEquals(bytes,source.readBytes())
                prepared.close()
                try {editor.renderRecoveryPreviewPage(prepared,0,160);fail("A closed preparation rendered")}
                catch(_:IllegalStateException) { }
                assertSame(live,editor.state.document);assertArrayEquals(bytes,source.readBytes())
            } finally {prepared.close()}
        }
    }
    @Test fun anUnreadableEmbeddedPdfCannotBecomeASuccessfulBlankOrInkOnlyPreview()=scenario {editor,context,root->
        Material(context,root).use {f->
            val pdf="%PDF-1.7 invalid historical original".toByteArray()
            val source=File(root,"bad-pdf.xnote")
            ZipOutputStream(source.outputStream()).use {zip->
                zip.putNextEntry(ZipEntry("manifest.json"));zip.write("""{"format":"xnote","version":1,"has_pdf":true,"pages":[{"width":300,"height":400,"pdf_page":0,"items":[]}]}""".toByteArray());zip.closeEntry()
                zip.putNextEntry(ZipEntry("assets/source.pdf"));zip.write(pdf);zip.closeEntry()
            }
            val bytes=source.readBytes();val live=editor.state.document;val dirty=live.dirty
            f.prepare(source,DocumentKind.NOTE).use {prepared->
                val original=requireNotNull(prepared.note?.pdfFile);assertArrayEquals(pdf,original.readBytes())
                var failure:Throwable?=null;var successful:Bitmap?=null
                try {successful=editor.renderRecoveryPreviewPage(prepared,0,160)} catch(error:Throwable) {failure=error}
                try {assertNotNull("PDF failure must not return a successful preview",failure);assertNull(successful)} finally {successful?.recycle()}
                assertTrue(original.isFile);assertArrayEquals(pdf,original.readBytes())
                assertSame(live,editor.state.document);assertEquals(dirty,live.dirty)
            }
            assertArrayEquals(bytes,source.readBytes())
        }
    }
}
