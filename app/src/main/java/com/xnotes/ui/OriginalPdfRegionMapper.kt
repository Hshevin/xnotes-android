package com.xnotes.ui

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Room
import com.xnotes.core.model.Rooms
import com.xnotes.storage.OriginalPdfPageInfo

/** A fixed source range before asynchronous original-PDF reads, never a viewport or menu box. */
internal data class OriginalPdfRegionSnapshot(val pdfKey:Int,val dpi:Int,val sourceWidth:Double,val sourceHeight:Double,
    val rooms:List<Room>,val region:Rect?) {
    internal data class Mapped(val userRegion:Rect?)

    fun map(actual:OriginalPdfPageInfo):Mapped? {
        // A whole original PDF page is independent of note paper dimensions, margins and rooms.
        if(region==null) return Mapped(null)
        val points=displayRegionInPoints() ?: return null
        // Rendering uses DPI, not a fit to the note paper; older imports can crop under a point.
        if(!contains(actual.displayBounds,points)) return null
        val user=actual.toUser(points)
        return user.takeIf(::valid)?.let {Mapped(it)}
    }
    fun displayRegionInPoints():Rect? {
        val region=region ?: return null
        if(dpi<=0 || !valid(region) || !sourceWidth.isFinite() || !sourceHeight.isFinite() || sourceWidth<=0 || sourceHeight<=0 ||
            rooms.any {!it.at.isFinite() || !it.size.isFinite() || it.size<=0}) return null
        val tile=Rooms.tiles(rooms,sourceWidth,sourceHeight).singleOrNull {contains(it.display,region)} ?: return null
        val source=region.translate(-tile.dx,-tile.dy)
        val scale=72.0/dpi
        return Rect(source.x*scale,source.y*scale,source.w*scale,source.h*scale)
    }
    private fun valid(r:Rect)=r.w>0 && r.h>0 && listOf(r.x,r.y,r.w,r.h,r.right,r.bottom).all {it.isFinite()}
    private fun contains(outer:Rect,inner:Rect)=inner.left>=outer.left && inner.top>=outer.top &&
        inner.right<=outer.right && inner.bottom<=outer.bottom
}
