package com.xnotes.mindmap

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.PdfKey
import com.xnotes.core.pdf.PdfPageGeometry
import com.xnotes.platform.AndroidRasterSurface
import com.xnotes.storage.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Injected original renderer + actual SQLite/leases/CPU PNG. No PDFium pixel claim. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OriginalPdfPreviewTest {
    private class Fixture(val root:File,val context:Context) {
        val storage=NotebookStorage(context);val catalog=OriginalPdfCatalog(storage.registry)
        var opens=0;var closes=0;var renders=0
        val keys=mutableListOf<Int>();val regions=mutableListOf<Rect>()
        var beforeRender:()->Unit={}
        var afterRender:(AndroidRasterSurface)->Unit={}
        val access=OriginalPdfAccess(context,{
            opens++
            object:OriginalPdfEngine {
                override val pageCount=3
                override fun pageInfo(pdfKey:Int):OriginalPdfPageInfo {
                    keys+=pdfKey
                    val base=PdfPageGeometry.of(20.0,30.0,620.0,830.0,0)
                    // For these preview assertions use unturned actual CropBox geometry.
                    return OriginalPdfPageInfo(Pt(600.0,800.0),base)
                }
                override fun renderRegion(pdfKey:Int,pxPerPt:Double,leftPx:Int,topPx:Int,width:Int,height:Int):AndroidRasterSurface {
                    beforeRender();renders++;regions+=Rect(leftPx/pxPerPt,topPx/pxPerPt,width/pxPerPt,height/pxPerPt)
                    return AndroidRasterSurface(Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.WHITE)}).also {afterRender(it)}
                }
                override fun closeAndAwait() {closes++}
            }
        },storage)
        val input=File(root,"original.pdf").apply {writeText("PDF bytes retained independently of any annotation note")}
        val observation:OriginalPdfCatalog.Observation
        init {
            val c=access.candidate(input,"Book original")
            try {observation=(catalog.retain(c) as OriginalPdfCatalog.Retention.Retained).observation} finally {c.closeAndAwait()}
        }
        fun target(index:Int=0,region:Rect?=null)=MapSourceTarget(observation.record.id,SourceScope.PDF_ORIGINAL,
            observation.record.pages[index],originalPdf=PdfOriginalProjection(observation.record.sha256,index,0,region))
        fun load(targets:List<MapSourceTarget>,render:Set<MapSourceTarget> = targets.toSet(),check:()->Unit={})=
            OriginalPdfSourcePreviewBackend.load(context,targets,render,check,access,catalog)
    }
    private fun scenario(block:(Fixture)->Unit) {
        val root=Files.createTempDirectory("original-pdf-preview-").toFile()
        val context=object:ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext():Context=this
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
        }
        val f=Fixture(root,context)
        try {block(f)} finally {f.storage.registry.close();root.deleteRecursively()}
    }
    @Test fun sameOriginalBatchSharesOneActualEngineAndPermanentPagesDetermineIndexes()=scenario {f->
        val a=f.target();val b=f.target(2,Rect(120.0,200.0,160.0,80.0));val before=f.opens
        val reads=f.load(listOf(a,b));assertEquals(before+1,f.opens);assertEquals(f.opens,f.closes)
        assertEquals(listOf(1,3),reads.map {it.lastKnownPage});assertTrue(reads.all {it.status==SourceStatus.CURRENT && it.asset!=null})
        assertTrue(PdfKey.of(2,0) in f.keys);assertEquals(f.observation.record.sha256,reads[0].rawVersion)
        assertTrue(reads[0].renderedVersion!!.startsWith("original-pdf-v1:"))
        assertEquals(256,reads[1].asset!!.width);assertEquals(128,reads[1].asset!!.height)
        val bitmap=BitmapFactory.decodeByteArray(reads[1].asset!!.pngBytes(),0,reads[1].asset!!.byteSize)
        try {assertEquals(Color.WHITE,bitmap.getPixel(bitmap.width/2,bitmap.height/2))} finally {bitmap.recycle()}
        assertEquals(f.input.readText(),File(f.storage.pdfResources.directory,"${f.observation.record.sha256}.pdf").readText())
    }
    @Test fun originalPreviewDoesNotNeedAnAnnotationNoteAndIgnoresItsUnrelatedChanges()=scenario {f->
        val target=f.target(region=Rect(50.0,200.0,80.0,40.0));val first=f.load(listOf(target)).single()
        // There are no note identity/location rows at all; this source cannot resolve through one.
        assertTrue(f.storage.registry.locations(target.documentId).isEmpty())
        File(f.root,"annotation.xnote").writeText("new ink, templates, text annotations, crop and room edits")
        val second=f.load(listOf(target)).single()
        assertEquals(first.rawVersion,second.rawVersion);assertEquals(first.renderedVersion,second.renderedVersion)
        assertEquals(first.asset,second.asset)
    }
    @Test fun deletedOriginalReportsTrashedWithoutOpeningOrRenderingAndPreservesStoredPngInHistory()=scenario {f->
        val target=f.target();val old=f.load(listOf(target)).single();val png=old.asset!!
        val s=MapSource(documentId=target.documentId,scope=target.scope,pageId=target.pageId,originalPdf=target.originalPdf,
            previewKey=png.key,lastPreview=MapLastPreview(png.key,target,old.renderedVersion!!,1),sourceVersion=old.rawVersion,status=SourceStatus.CURRENT)
        val initial=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,sources=listOf(s))),previewAssets=mapOf(png.key to png))
        val history=MapHistory(initial);history.commit(initial.update("r") {it.copy(text="edited body")});history.undo()
        f.catalog.delete(f.catalog.observe(target.documentId)!!);val opens=f.opens
        val gone=f.load(listOf(target)).single();assertEquals(SourceStatus.TRASHED,gone.status);assertNull(gone.asset);assertEquals(opens,f.opens)
        val patch=MapSourceDerivedPatch("r",s.id,target,gone.status,gone.fileName,null,null)
        assertTrue(history.acceptDerived(MapHistory.prepareDerived(history.captureDerived(),listOf(patch))))
        assertTrue(history.canRedo);assertEquals(png,history.document.previewAssets[png.key])
        assertEquals(png.key,history.document.root.sources.single().lastPreview!!.assetKey)
        assertTrue(history.document.root.sources.single().hasHistoricalPreview)
        history.redo();assertEquals(SourceStatus.TRASHED,history.document.root.sources.single().status)
    }
    @Test fun wrongPageProjectionCannotRenderAnotherPageOrFallBackToItsOrdinal()=scenario {f->
        val good=f.target(1);val bad=good.copy(originalPdf=good.originalPdf!!.copy(pageIndex=0))
        val reads=f.load(listOf(bad,good));assertEquals(SourceStatus.UNAVAILABLE,reads[0].status);assertNull(reads[0].asset)
        assertEquals(SourceStatus.CURRENT,reads[1].status);assertEquals(1,f.renders)
    }
    @Test fun anOutOfBoundsFirstFrameCannotPoisonOtherTargetsInTheSameOriginalBatch()=scenario {f->
        val outside=f.target(region=Rect(-100.0,-100.0,40.0,40.0))
        val page=f.target(1);val region=f.target(2,Rect(120.0,200.0,160.0,80.0));val opens=f.opens
        val reads=f.load(listOf(outside,page,region))
        assertEquals(SourceStatus.UNAVAILABLE,reads[0].status);assertNull(reads[0].asset)
        assertTrue(reads.drop(1).all {it.status==SourceStatus.CURRENT && it.asset!=null})
        assertEquals(opens+1,f.opens);assertEquals(2,f.renders);assertEquals(f.opens,f.closes)
    }
    @Test fun cancellationDuringRenderRetiresTheBatchAndClosesTheRealHolder()=scenario {f->
        f.beforeRender={throw CancellationException("source ticket retired")}
        assertThrows(CancellationException::class.java) {f.load(listOf(f.target()))}
        assertEquals(f.opens,f.closes)
        f.storage.registry.readableDatabase.rawQuery("SELECT COUNT(*) FROM leases",null).use {assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))}
    }
    @Test fun cancellationAfterTheActualSurfaceReturnsRecyclesItBeforeClosingTheOriginalHolder()=scenario {f->
        var returned:AndroidRasterSurface?=null
        f.afterRender={returned=it}
        assertThrows(CancellationException::class.java) {
            f.load(listOf(f.target()),check={if(returned!=null) throw CancellationException("retired after raster returned")})
        }
        assertNotNull(returned)
        assertTrue(requireNotNull(returned).bitmap.isRecycled)
        assertEquals(f.opens,f.closes)
        f.storage.registry.readableDatabase.rawQuery("SELECT COUNT(*) FROM leases",null).use {assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))}
    }
    @Test fun missingCorruptFutureAndOutOfBoundsOriginalNeverClaimAnEmptyOrCurrentPreview()=scenario {f->
        val outside=f.target(region=Rect(-100.0,-100.0,40.0,40.0));assertEquals(SourceStatus.UNAVAILABLE,f.load(listOf(outside)).single().status)
        val target=f.target();File(f.storage.pdfResources.directory,"${f.observation.record.sha256}.pdf").delete()
        assertEquals(SourceStatus.UNAVAILABLE,f.load(listOf(target)).single().status)
        f.storage.registry.writableDatabase.execSQL("UPDATE original_pdf_documents SET row_schema=8")
        assertEquals(SourceStatus.UNAVAILABLE,f.load(listOf(target)).single().status)
    }
}
