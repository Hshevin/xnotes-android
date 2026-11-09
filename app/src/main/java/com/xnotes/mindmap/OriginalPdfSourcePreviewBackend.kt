package com.xnotes.mindmap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.PdfKey
import com.xnotes.storage.OriginalPdfAccess
import com.xnotes.storage.OriginalPdfCatalog
import com.xnotes.storage.OriginalPdfEngine
import com.xnotes.storage.OriginalPdfPageInfo
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.math.ceil
import kotlin.math.floor

/** One verified original engine/lease per batch. Never decodes a note or paints its annotations. */
internal object OriginalPdfSourcePreviewBackend {
    private const val SIDE=256
    fun load(context:Context,targets:List<MapSourceTarget>,render:Set<MapSourceTarget>,checkActive:()->Unit={},
        access:OriginalPdfAccess=OriginalPdfAccess(context),catalog:OriginalPdfCatalog=OriginalPdfCatalog.get(context)):List<MapSourcePreviewRead> {
        require(targets.isNotEmpty() && targets.all {it.documentId==targets.first().documentId})
        targets.forEach {it.validate()};checkActive()
        val original=targets.filter {it.scope==SourceScope.PDF_ORIGINAL}
        fun unavailable(target:MapSourceTarget,name:String?=null)=MapSourcePreviewRead(target,SourceStatus.UNAVAILABLE,fileName=name,failedPreview=target in render)
        val observed=try {catalog.observe(targets.first().documentId)} catch(error:CancellationException) {throw error} catch(_:Exception) {null}
        if(observed==null) return targets.map {unavailable(it)}
        val record=observed.record
        if(record.deleted) return targets.map {if(it.scope==SourceScope.PDF_ORIGINAL)
            MapSourcePreviewRead(it,SourceStatus.TRASHED,fileName=record.title,failedPreview=it in render) else unavailable(it)}
        // Resolve a valid first target; one malformed member must not silently redirect another member.
        val first=original.firstOrNull {t->t.originalPdf?.let {p->p.sha256==record.sha256 && record.pages.indexOf(t.pageId)==p.pageIndex}==true}
            ?: return targets.map {unavailable(it,record.title)}
        // The shared reader proves this actual permanent page, independent of any member's frame.
        // An invalid first crop is a failure of that card, not of the whole original batch.
        val readerTarget=first.copy(originalPdf=requireNotNull(first.originalPdf).copy(userRegion=null))
        val read=try {access.open(readerTarget,checkActive)} catch(error:CancellationException) {throw error} catch(_:Exception) {return targets.map {unavailable(it,record.title)}}
        try {
            fun check() {checkActive();kotlin.check(observed.current() && read.current())}
            check()
            require(read.observation.record==record)
            return targets.map {target->
                check()
                if(target.scope!=SourceScope.PDF_ORIGINAL) return@map unavailable(target,record.title)
                try {
                    val projection=requireNotNull(target.originalPdf)
                    val index=record.pages.indexOf(target.pageId)
                    require(index>=0 && index==projection.pageIndex && projection.sha256==record.sha256)
                    val key=PdfKey.of(index,projection.viewTurns)
                    val info=read.engine.pageInfo(key);check()
                    val frame=projection.userRegion?.let(info::toDisplay) ?: info.displayBounds
                    require(OriginalPdfAccess.contains(info.displayBounds,frame))
                    val version=semanticVersion(target,info,frame)
                    val asset=if(target in render) png(read.engine,key,info,frame,::check) else null
                    check()
                    MapSourcePreviewRead(target,SourceStatus.CURRENT,record.sha256,version,record.title,index+1,asset)
                } catch(error:CancellationException) {throw error}
                catch(_:Exception) {unavailable(target,record.title)}
            }.also {check()}
        } finally {read.closeAndAwait()}
    }
    /** Includes exactly the actual original projection/frame; note edits and derived PNGs are absent. */
    internal fun semanticVersion(target:MapSourceTarget,info:OriginalPdfPageInfo,frame:Rect):String {
        val bytes=ByteArrayOutputStream()
        DataOutputStream(bytes).use {out->
            fun text(s:String) {val b=s.toByteArray(Charsets.UTF_8);out.writeInt(b.size);out.write(b)}
            text("original-pdf-v1");text(target.documentId);text(requireNotNull(target.pageId))
            val p=requireNotNull(target.originalPdf);text(p.sha256);out.writeInt(p.pageIndex);out.writeInt(p.viewTurns)
            out.writeBoolean(p.userRegion!=null)
            listOf(frame.x,frame.y,frame.w,frame.h,info.sizePoints.x,info.sizePoints.y,
                info.geometry.a,info.geometry.b,info.geometry.c,info.geometry.d,info.geometry.e,info.geometry.f).forEach(out::writeDouble)
        }
        return "original-pdf-v1:"+MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") {"%02x".format(it)}
    }
    private fun png(engine:OriginalPdfEngine,key:Int,info:OriginalPdfPageInfo,frame:Rect,check:()->Unit):MapPreviewAsset {
        check()
        val scale=minOf(SIDE/frame.w,SIDE/frame.h)
        require(scale.isFinite() && scale>0)
        val w=ceil(frame.w*scale).toInt().coerceIn(1,SIDE);val h=ceil(frame.h*scale).toInt().coerceIn(1,SIDE)
        val left=floor(frame.left*scale).toInt();val top=floor(frame.top*scale).toInt()
        val rw=ceil(frame.right*scale).toInt()-left;val rh=ceil(frame.bottom*scale).toInt()-top
        require(rw in 1..SIDE+2 && rh in 1..SIDE+2)
        val raster=requireNotNull(engine.renderRegion(key,scale,left,top,rw,rh))
        try {
            check()
            val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
            try {
                // Subpixel crop binds the PNG to the fixed frame instead of rounding it outwards.
                Canvas(bitmap).apply {
                    scale((w/(frame.w*scale)).toFloat(),(h/(frame.h*scale)).toFloat())
                    translate(-(frame.left*scale-left).toFloat(),-(frame.top*scale-top).toFloat())
                    drawBitmap(raster.bitmap,0f,0f,Paint(Paint.FILTER_BITMAP_FLAG))
                }
                check();val out=ByteArrayOutputStream();kotlin.check(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));check()
                return MapPreviewAsset.fromPng(out.toByteArray())
            } finally {bitmap.recycle()}
        } finally {raster.recycle()}
    }
}
