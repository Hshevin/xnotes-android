package com.xnotes.storage

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.PdfKey
import com.xnotes.core.pdf.PdfPageGeometry
import com.xnotes.platform.AndroidRasterSurface
import com.xnotes.platform.PdfSource
import java.io.File

/** Actual PDF coordinates, including CropBox, /Rotate and the requested presentation turn. */
internal data class OriginalPdfPageInfo(val sizePoints:Pt,val geometry:PdfPageGeometry) {
    val displayBounds get()=Rect(0.0,0.0,sizePoints.x,sizePoints.y)
    init {
        require(sizePoints.x.isFinite() && sizePoints.y.isFinite() && sizePoints.x>0 && sizePoints.y>0)
        require(listOf(geometry.a,geometry.b,geometry.c,geometry.d,geometry.e,geometry.f).all {it.isFinite()})
        val det=geometry.a*geometry.d-geometry.b*geometry.c
        require(det.isFinite() && kotlin.math.abs(det)>1e-12)
    }
    fun toDisplay(region:Rect):Rect=Rect.bounding(listOf(
        geometry.toDisplay(region.left,region.top),geometry.toDisplay(region.right,region.top),
        geometry.toDisplay(region.left,region.bottom),geometry.toDisplay(region.right,region.bottom)))
    fun toUser(region:Rect):Rect=Rect.bounding(listOf(
        geometry.toUser(region.left,region.top),geometry.toUser(region.right,region.top),
        geometry.toUser(region.left,region.bottom),geometry.toUser(region.right,region.bottom)))
}

/** This surface cannot request annotations, templates, rooms or a note's PDF colour filter. IO only. */
internal interface OriginalPdfEngine {
    val pageCount:Int
    fun pageInfo(pdfKey:Int):OriginalPdfPageInfo
    fun renderRegion(pdfKey:Int,pxPerPt:Double,leftPx:Int,topPx:Int,width:Int,height:Int):AndroidRasterSurface?
    /** Blocks until all native reads and the actual native document close have finished. */
    fun closeAndAwait()
}

internal class OriginalPdfSourceEngine(file:File):OriginalPdfEngine {
    private val source=PdfSource.open(file)
    override val pageCount get()=source.pageCount
    override fun pageInfo(pdfKey:Int):OriginalPdfPageInfo {
        require(PdfKey.index(pdfKey) in 0 until pageCount)
        val size=requireNotNull(source.pageSizePoints(pdfKey))
        return OriginalPdfPageInfo(Pt(size.first.toDouble(),size.second.toDouble()),requireNotNull(source.pageGeometry(pdfKey)))
    }
    override fun renderRegion(pdfKey:Int,pxPerPt:Double,leftPx:Int,topPx:Int,width:Int,height:Int)=
        source.renderRegion(pdfKey,pxPerPt,leftPx,topPx,width,height,com.xnotes.canvas.PdfPageFilter.NONE,emptyList())
    override fun closeAndAwait()=source.closeAndAwait()
}
