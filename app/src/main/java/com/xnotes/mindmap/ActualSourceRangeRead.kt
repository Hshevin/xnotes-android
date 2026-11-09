package com.xnotes.mindmap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.PdfKey
import com.xnotes.platform.NativeDocumentPreview
import com.xnotes.storage.OriginalPdfAccess
import com.xnotes.storage.OriginalPdfCatalog
import com.xnotes.storage.PreparedNativeOpen
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

internal sealed interface SourceRangePayload {
    data class Native(val prepared:PreparedNativeOpen,val offer:SourceTargetOffer.Native,
        val domain:OriginalPdfCatalog.IdentityDomainsBatch):SourceRangePayload
    data class Map(val offer:SourceTargetOffer.Map,val domain:OriginalPdfCatalog.IdentityDomainsBatch):SourceRangePayload
    data class Original(val read:OriginalPdfAccess.Read):SourceRangePayload
}

/** A private decoded source and one serial painter. No Editor, source writer, or native adoption. */
internal class ActualSourceRangeRead(private val context:Context,private val payload:SourceRangePayload,
    private val initial:MapSourceTarget,private val verify:(MapSourceTarget,()->Unit)->Unit):SourceRangeRead() {
    private val owner=Any()
    private val gate=Any()
    private val retired=AtomicBoolean()
    private var closed=false
    private var painter:NativeDocumentPreview?=null
    private var mapPainter:MapPainter?=null
    override val kind=when(payload) {
        is SourceRangePayload.Native->if(payload.prepared.note!=null) SourceRangeKind.NOTE else SourceRangeKind.CANVAS
        is SourceRangePayload.Map->SourceRangeKind.MAP
        is SourceRangePayload.Original->SourceRangeKind.PDF_ORIGINAL
    }
    override val documentId=when(payload) {
        is SourceRangePayload.Native->payload.prepared.version.documentId
        is SourceRangePayload.Map->payload.offer.documentId
        is SourceRangePayload.Original->payload.read.observation.record.id
    }
    override val displayName=when(payload) {
        is SourceRangePayload.Native->payload.offer.displayName
        is SourceRangePayload.Map->payload.offer.displayName
        is SourceRangePayload.Original->payload.read.observation.record.title
    }
    override val rawVersion=when(payload) {
        is SourceRangePayload.Native->payload.prepared.version.sha256
        is SourceRangePayload.Map->payload.offer.rawVersion
        is SourceRangePayload.Original->payload.read.observation.record.sha256
    }
    override val nativeOffer get()=(payload as? SourceRangePayload.Native)?.offer
    override val needsPageActivation=(payload as? SourceRangePayload.Native)?.offer?.offer?.catalog?.pages==
        com.xnotes.storage.NativeSourceSnapshot.ReferenceCatalog.Pages.NeedsActivation
    override val pages:List<SourceRangePage> = Collections.unmodifiableList(when(payload) {
        is SourceRangePayload.Native->(payload.offer.offer.catalog?.pages as? com.xnotes.storage.NativeSourceSnapshot.ReferenceCatalog.Pages.Available)
            ?.orderedIds?.mapIndexed {i,id->SourceRangePage(id,i+1)}.orEmpty()
        is SourceRangePayload.Map->emptyList()
        is SourceRangePayload.Original->payload.read.observation.record.pages.mapIndexed {i,id->SourceRangePage(id,i+1)}
    })
    override val initialPageId=initial.pageId?.takeIf {id->pages.any {it.id==id}}
    override val initialTurns=if(kind==SourceRangeKind.PDF_ORIGINAL) initial.originalPdf?.viewTurns?.takeIf {it in 0..3} ?: 0 else 0
    override val initialIssue:SourceRangeInitialIssue?=when {
        initial.pageId!=null && initialPageId==null->SourceRangeInitialIssue.PAGE_DELETED
        initial.region?.let {!validRect(it)}==true || initial.originalPdf?.userRegion?.let {!validRect(it)}==true->SourceRangeInitialIssue.INVALID_REGION
        payload is SourceRangePayload.Original && initialPageId!=null && initial.originalPdf?.userRegion!=null &&
            !OriginalPdfAccess.contains(payload.read.pageInfo(PdfKey.of(pages.indexOfFirst {it.id==initialPageId},initialTurns)).displayBounds,
                payload.read.pageInfo(PdfKey.of(pages.indexOfFirst {it.id==initialPageId},initialTurns)).toDisplay(initial.originalPdf.userRegion))->SourceRangeInitialIssue.INVALID_REGION
        else->null
    }
    override fun current()=!retired.get() && when(payload) {
        is SourceRangePayload.Native->payload.offer.current() && payload.domain.current()
        is SourceRangePayload.Map->payload.offer.current() && payload.domain.current()
        is SourceRangePayload.Original->payload.read.current()
    }
    override fun frame(pageId:String?,viewTurns:Int):SourceRangeFrame=synchronized(gate) {
        check(current());require(viewTurns in 0..3)
        val index=if(pageId==null) 0 else pages.indexOfFirst {it.id==pageId}.also {require(it>=0)}
        when(payload) {
            is SourceRangePayload.Native->{
                val prepared=payload.prepared
                val note=prepared.note
                if(note!=null) {
                    require(viewTurns==0);val page=note.pages.getOrNull(index) ?: error("Source has no page")
                    val seed=initial.region?.takeIf {pageId!=null && pageId==initial.pageId && validRect(it)}
                    SourceRangeFrame.issue(owner,pageId,0,NativeDocumentPreview.footprint(note,page),seed,
                        SourceRangeSpace.PAGE_LOCAL,pageId!=null && !needsPageActivation,true,index)
                } else {
                    require(pageId==null && viewTurns==0)
                    val canvas=requireNotNull(prepared.canvas)
                    val seed=initial.region?.takeIf(::validRect)
                    val content=canvas.contentBounds()?.takeIf(::validRect)
                    val bounds=if(content!=null && seed!=null) content.union(seed) else content ?: seed ?: EMPTY_WORLD
                    SourceRangeFrame.issue(owner,null,0,bounds,seed,SourceRangeSpace.CANVAS_WORLD,true,true,0)
                }
            }
            is SourceRangePayload.Map->{
                require(pageId==null && viewTurns==0)
                val p=mapPainter ?: MapPainter().also {mapPainter=it}
                SourceRangeFrame.issue(owner,null,0,p.drawingBounds(payload.offer.document),null,SourceRangeSpace.MAP_WORLD,false,true,0)
            }
            is SourceRangePayload.Original->{
                val info=payload.read.pageInfo(PdfKey.of(index,viewTurns))
                val seed=initial.originalPdf?.userRegion?.takeIf {pageId!=null && pageId==initial.pageId && validRect(it)}?.let(info::toDisplay)
                SourceRangeFrame.issue(owner,pageId,viewTurns,info.displayBounds,seed,SourceRangeSpace.PDF_DISPLAY,pageId!=null,false,index,info)
            }
        }
    }
    override fun render(frame:SourceRangeFrame,viewport:Rect,maxSide:Int,check:()->Unit):OwnedVisualFrame=synchronized(gate) {
        fun live() {check();kotlin.check(current() && frame.owner===owner)}
        live();require(validViewport(viewport) && maxSide in 1..2048)
        val bitmap=when(payload) {
            is SourceRangePayload.Native->{
                val p=painter ?: NativeDocumentPreview(context).also {painter=it}
                payload.prepared.note?.let {p.note(it,frame.index,maxSide,viewport,::live) ?: blank(viewport,maxSide)}
                    ?: p.canvasRegion(requireNotNull(payload.prepared.canvas),viewport,maxSide,::live)
            }
            is SourceRangePayload.Map->{
                val p=mapPainter ?: MapPainter().also {mapPainter=it}
                val bitmap=blank(viewport,maxSide)
                try {
                    val scale=maxSide/maxOf(viewport.w,viewport.h)
                    Canvas(bitmap).apply {scale(scale.toFloat(),scale.toFloat());translate(-viewport.left.toFloat(),-viewport.top.toFloat())
                        live();p.draw(this,payload.offer.document);live()}
                    bitmap
                } catch(error:Throwable) {bitmap.recycle();throw error}
            }
            is SourceRangePayload.Original->originalRaster(payload,frame,viewport,maxSide,::live)
        }
        val owned=OwnedVisualFrame(bitmap,viewport)
        try {live();owned} catch(error:Throwable) {owned.close();throw error}
    }
    override fun prepareChoice(frame:SourceRangeFrame,scope:SourceRangeScope,region:Rect?,check:()->Unit):PreparedSourceTargetChoice=synchronized(gate) {
        fun live() {check();kotlin.check(current() && frame.owner===owner)}
        live()
        val target=when(scope) {
            SourceRangeScope.WHOLE_FILE->{require(kind!=SourceRangeKind.PDF_ORIGINAL);MapSourceTarget(documentId,SourceScope.FILE)}
            SourceRangeScope.WHOLE_PAGE->{
                require(frame.pageId!=null && kind in setOf(SourceRangeKind.NOTE,SourceRangeKind.PDF_ORIGINAL))
                if(kind==SourceRangeKind.NOTE) MapSourceTarget(documentId,SourceScope.PAGE,frame.pageId)
                else originalTarget(frame,null)
            }
            SourceRangeScope.REGION->{
                require(frame.regionAllowed && region!=null && validRect(region))
                when(kind) {
                    SourceRangeKind.NOTE->MapSourceTarget(documentId,SourceScope.PAGE,requireNotNull(frame.pageId),region)
                    SourceRangeKind.CANVAS->MapSourceTarget(documentId,SourceScope.CANVAS_REGION,region=region)
                    SourceRangeKind.PDF_ORIGINAL->{
                        require(OriginalPdfAccess.contains(frame.bounds,region));originalTarget(frame,requireNotNull(frame.pdfInfo).toUser(region))
                    }
                    SourceRangeKind.MAP->error("Map files have no page or region target")
                }
            }
        }
        target.validate();verify(target,::live);live()
        PreparedSourceTargetChoice.issue(target,displayName,rawVersion,target.pageId?.let {frame.index+1},::current)
    }
    private fun originalTarget(frame:SourceRangeFrame,userRegion:Rect?)=MapSourceTarget(documentId,SourceScope.PDF_ORIGINAL,
        requireNotNull(frame.pageId),originalPdf=PdfOriginalProjection(rawVersion,frame.index,frame.viewTurns,userRegion))
    override fun closeAndAwait() {
        retired.set(true)
        synchronized(gate) {
            if(closed) return
            painter?.closeAndAwait();painter=null;mapPainter?.clear();mapPainter=null
            when(payload) {
                is SourceRangePayload.Native->{payload.prepared.close();payload.offer.closeAndAwait()}
                is SourceRangePayload.Map->payload.offer.closeAndAwait()
                is SourceRangePayload.Original->payload.read.closeAndAwait()
            }
            closed=true
        }
    }
    private fun originalRaster(payload:SourceRangePayload.Original,frame:SourceRangeFrame,viewport:Rect,maxSide:Int,check:()->Unit):Bitmap {
        val bitmap=blank(viewport,maxSide)
        try {
            val visible=NativeDocumentPreview.intersect(frame.bounds,viewport) ?: return bitmap
            val scale=maxSide/maxOf(viewport.w,viewport.h)
            val left=floor(visible.left*scale).toInt();val top=floor(visible.top*scale).toInt()
            val width=ceil(visible.right*scale).toInt()-left;val height=ceil(visible.bottom*scale).toInt()-top
            require(width in 1..maxSide+2 && height in 1..maxSide+2)
            val raster=requireNotNull(payload.read.engine.renderRegion(PdfKey.of(frame.index,frame.viewTurns),scale,left,top,width,height))
            try {check();Canvas(bitmap).apply {translate((-viewport.left*scale).toFloat(),(-viewport.top*scale).toFloat())
                drawBitmap(raster.bitmap,left.toFloat(),top.toFloat(),Paint(Paint.FILTER_BITMAP_FLAG))};check()}
            finally {raster.recycle()}
            return bitmap
        } catch(error:Throwable) {bitmap.recycle();throw error}
    }
    private companion object {
        val EMPTY_WORLD=Rect(-512.0,-384.0,1024.0,768.0)
        fun validViewport(rect:Rect)=rect.w>0 && rect.h>0 &&
            listOf(rect.x,rect.y,rect.w,rect.h,rect.right,rect.bottom).all {it.isFinite()}
        fun validRect(rect:Rect)=validViewport(rect) && listOf(rect.x,rect.y,rect.w,rect.h).all {
            kotlin.math.abs(it)<=MindMapDocument.MAX_COORD
        }
        fun blank(frame:Rect,maxSide:Int):Bitmap {
            val scale=maxSide/maxOf(frame.w,frame.h);require(scale.isFinite() && scale>0 && scale<=Float.MAX_VALUE)
            val width=(frame.w*scale).roundToInt().coerceIn(1,maxSide);val height=(frame.h*scale).roundToInt().coerceIn(1,maxSide)
            return Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.WHITE)}
        }
    }
}
