package com.xnotes.mindmap

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import kotlin.math.abs

/** Only the temporary selection camera; never serialized into a document. */
internal data class SourceRegionCamera(val centerX:Double,val centerY:Double,val zoom:Double) {
    fun valid()=centerX.isFinite() && centerY.isFinite() && zoom.isFinite() && zoom>0
}
internal enum class SourceRegionHandle(val left:Boolean=false,val top:Boolean=false,val right:Boolean=false,val bottom:Boolean=false) {
    NEW,MOVE,LEFT(left=true),TOP(top=true),RIGHT(right=true),BOTTOM(bottom=true),
    TOP_LEFT(left=true,top=true),TOP_RIGHT(top=true,right=true),BOTTOM_LEFT(left=true,bottom=true),BOTTOM_RIGHT(right=true,bottom=true)
}

/** Fixed world/page coordinates. Image bounds never constrain a native selection. */
internal object SourceRegionGeometry {
    fun valid(rect:Rect)=listOf(rect.x,rect.y,rect.w,rect.h).all {it.isFinite() && abs(it)<=MindMapDocument.MAX_COORD} && rect.w>0 && rect.h>0
    fun contains(bounds:Rect,rect:Rect):Boolean=valid(rect) && rect.left>=bounds.left-1e-6 && rect.top>=bounds.top-1e-6 &&
        rect.right<=bounds.right+1e-6 && rect.bottom<=bounds.bottom+1e-6
    fun hit(rect:Rect,p:Pt,grab:Double):SourceRegionHandle? {
        if(!valid(rect) || !p.x.isFinite() || !p.y.isFinite() || !grab.isFinite() || grab<=0) return null
        val ix=minOf(grab,rect.w/3);val iy=minOf(grab,rect.h/3)
        val alongX=p.x in rect.left-grab..rect.right+grab
        val alongY=p.y in rect.top-grab..rect.bottom+grab
        val left=alongY && p.x in rect.left-grab..rect.left+ix
        val right=alongY && p.x in rect.right-ix..rect.right+grab
        val top=alongX && p.y in rect.top-grab..rect.top+iy
        val bottom=alongX && p.y in rect.bottom-iy..rect.bottom+grab
        return when {
            left && top->SourceRegionHandle.TOP_LEFT
            right && top->SourceRegionHandle.TOP_RIGHT
            left && bottom->SourceRegionHandle.BOTTOM_LEFT
            right && bottom->SourceRegionHandle.BOTTOM_RIGHT
            left->SourceRegionHandle.LEFT
            right->SourceRegionHandle.RIGHT
            top->SourceRegionHandle.TOP
            bottom->SourceRegionHandle.BOTTOM
            p.x in rect.left..rect.right && p.y in rect.top..rect.bottom->SourceRegionHandle.MOVE
            else->null
        }
    }
    fun drag(initial:Rect?,handle:SourceRegionHandle,down:Pt,at:Pt):Rect? {
        if(!listOf(down.x,down.y,at.x,at.y).all {it.isFinite()}) return null
        val dx=at.x-down.x;val dy=at.y-down.y
        val candidate=when(handle) {
            SourceRegionHandle.NEW->normalized(down.x,down.y,at.x,at.y)
            SourceRegionHandle.MOVE->initial?.let {Rect(it.x+dx,it.y+dy,it.w,it.h)}
            else->initial?.let {normalized(if(handle.left) it.left+dx else it.left,if(handle.top) it.top+dy else it.top,
                if(handle.right) it.right+dx else it.right,if(handle.bottom) it.bottom+dy else it.bottom)}
        }
        return candidate?.takeIf(::valid)
    }
    private fun normalized(x1:Double,y1:Double,x2:Double,y2:Double)=Rect(minOf(x1,x2),minOf(y1,y2),abs(x2-x1),abs(y2-y1))
}
