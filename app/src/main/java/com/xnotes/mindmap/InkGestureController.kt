package com.xnotes.mindmap

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import com.xnotes.core.geometry.Geometry
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect

/** Shared node-local input transaction. Hosts supply a frozen transform, never synthetic events. */
class InkGestureController {
    var ink: List<MapInk> = emptyList()
        set(value) {
            if(field !== value) {
                if(active && value != before) cancel()
                if(value != field && !publishing) selection=emptySet()
                field=value
            }
        }
    var mode=InkMode.PEN
        set(value) { if(field!=value) {field=value;if(!active) selection=emptySet()} }
    var width=3.0
    var onCommit:(List<MapInk>,List<MapInk>)->Boolean={_,_->false}
    var onContact:(Boolean)->Unit={}
    var onLimit:()->Unit={}
    val active get()=pointer>=0
    private var pointer=-1
    private var publishing=false
    private var tool=InkMode.PEN
    private var strokeWidth=3.0
    private var mapper:(Float,Float)->Pt={x,y->Pt(x.toDouble(),y.toDouble())}
    private var before=emptyList<MapInk>()
    private var points=ArrayList<InkPoint>()
    private var selection=emptySet<Int>()
    private var oldSelection=emptySet<Int>()
    private var moving=false
    private var down=Pt(0.0,0.0)
    private var preview:List<MapInk>?=null
    private var rejected=false
    private var beforePointCount=0
    val displayInk get() = if(active && (tool==InkMode.PEN || tool==InkMode.LINE) && points.isNotEmpty() && !rejected)
        (preview ?: ink)+MapInk(points.toList(),strokeWidth) else preview ?: ink

    fun handle(e:MotionEvent, transform:(Float,Float)->Pt, canStart:(Pt)->Boolean):Boolean {
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN,MotionEvent.ACTION_POINTER_DOWN -> {
                if(active) return true
                val i=e.actionIndex
                if(e.getToolType(i) !in listOf(MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.TOOL_TYPE_ERASER,MotionEvent.TOOL_TYPE_MOUSE)) return false
                val p=transform(e.getX(i),e.getY(i))
                if(!canStart(p)) return false
                pointer=e.getPointerId(i);mapper=transform;down=p;before=ink;beforePointCount=before.sumOf {it.points.size};oldSelection=selection
                tool=if(e.getToolType(i)==MotionEvent.TOOL_TYPE_ERASER) InkMode.ERASER else mode
                strokeWidth=width;preview=null;points.clear();rejected=false
                moving=(tool==InkMode.LASSO || tool==InkMode.RECT_SELECT) && selectedBounds()?.outset(8.0)?.contains(p)==true
                if(!moving) selection=emptySet()
                onContact(true);sample(e,i)
            }
            MotionEvent.ACTION_MOVE -> {
                if(!active) return false
                val i=e.findPointerIndex(pointer)
                if(i<0) {cancel();return true}
                if(moving) move(mapper(e.getX(i),e.getY(i))) else {
                    for(h in 0 until e.historySize) sample(e,i,h)
                    sample(e,i)
                }
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_POINTER_UP -> {
                if(!active) return false
                if(e.getPointerId(e.actionIndex)!=pointer) return true
                if((e.flags and 0x20)!=0) {cancel();return true}
                if(moving) move(mapper(e.getX(e.actionIndex),e.getY(e.actionIndex))) else sample(e,e.actionIndex)
                val next=when {
                    rejected->null
                    moving->preview
                    (tool==InkMode.PEN || tool==InkMode.LINE) && points.isNotEmpty()->before+MapInk(points.toList(),strokeWidth)
                    tool==InkMode.ERASER->preview
                    else->null
                }
                if(!rejected && (tool==InkMode.LASSO || tool==InkMode.RECT_SELECT) && !moving) {
                    val polygon=if(tool==InkMode.RECT_SELECT) {
                        val end=points.lastOrNull()?.let {Pt(it.x,it.y)} ?: down
                        listOf(down,Pt(end.x,down.y),end,Pt(down.x,end.y))
                    } else points.map {Pt(it.x,it.y)}
                    selection=before.indices.filterTo(hashSetOf()) {i->intersectsSelection(before[i],polygon)}
                }
                // Release ownership before callback; session can now atomically commit this one operation.
                val expected=before
                finish()
                if(next!=null && next!=expected) {
                    publishing=true
                    try {if(onCommit(expected,next)) ink=next else selection=oldSelection} finally {publishing=false}
                }
            }
            MotionEvent.ACTION_CANCEL -> {if(!active) return false;cancel()}
            else -> return active
        }
        return true
    }
    private fun sample(e:MotionEvent,i:Int,h:Int?=null) {
        if(rejected) return
        val p=mapper(if(h==null)e.getX(i) else e.getHistoricalX(i,h),if(h==null)e.getY(i) else e.getHistoricalY(i,h))
        val pressure=(if(h==null)e.getPressure(i) else e.getHistoricalPressure(i,h)).toDouble()
        val time=(if(h==null)e.eventTime else e.getHistoricalEventTime(h)).toDouble()
        val nextCount=if(tool==InkMode.LINE) minOf(points.size+1,2) else points.size+1
        if(!p.x.isFinite() || !p.y.isFinite() || kotlin.math.abs(p.x)>MindMapDocument.MAX_COORD || kotlin.math.abs(p.y)>MindMapDocument.MAX_COORD ||
            ((tool==InkMode.PEN || tool==InkMode.LINE) && beforePointCount+nextCount>MindMapDocument.MAX_POINTS) || nextCount>MindMapDocument.MAX_POINTS) {
            rejected=true;preview=null;points.clear();onLimit();return
        }
        if(tool==InkMode.ERASER) {
            val a=points.lastOrNull()?.let {Pt(it.x,it.y)} ?: p
            preview=(preview ?: before).filterNot {s->s.points.any {Geometry.distancePointToSegment(Pt(it.x,it.y),a,p)<=10+s.width/2} ||
                s.points.zipWithNext().any {(c,d)->Geometry.distanceSegmentToSegment(a,p,Pt(c.x,c.y),Pt(d.x,d.y))<=10+s.width/2}}
        }
        // A line stores only the actual first and final samples; its preview follows the tip.
        if(tool==InkMode.LINE && points.size>1) points.removeAt(1)
        points.add(InkPoint(p.x,p.y,pressure,time))
    }
    private fun intersectsSelection(stroke:MapInk,polygon:List<Pt>):Boolean {
        if(polygon.size<3) return false
        val edges=polygon.zipWithNext()+listOf(polygon.last() to polygon.first())
        val samples=stroke.points.map {Pt(it.x,it.y)}
        if(samples.any {p->Geometry.pointInPolygon(polygon,p) || edges.any {(a,b)->Geometry.distancePointToSegment(p,a,b)<1e-8}}) return true
        return samples.zipWithNext().any {(a,b)->edges.any {(c,d)->Geometry.distanceSegmentToSegment(a,b,c,d)<1e-8}}
    }
    private fun move(p:Pt) {
        val dx=p.x-down.x;val dy=p.y-down.y
        preview=before.mapIndexed {i,s->if(i in selection) s.copy(points=s.points.map {it.copy(x=it.x+dx,y=it.y+dy)}) else s}
    }
    private fun selectedBounds():Rect?=selection.mapNotNull {ink.getOrNull(it)}.flatMap {s->s.points.map {Pt(it.x,it.y)}}.takeIf {it.isNotEmpty()}?.let(Rect::bounding)
    fun drawSelection(canvas:Canvas,color:Int,scale:Float) {
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {this.color=color;style=Paint.Style.STROKE;strokeWidth=1/scale}
        if(active && (tool==InkMode.LASSO || tool==InkMode.RECT_SELECT) && !moving && points.isNotEmpty()) {
            val path=Path().apply {moveTo(points.first().x.toFloat(),points.first().y.toFloat());points.drop(1).forEach {lineTo(it.x.toFloat(),it.y.toFloat())}}
            if(tool==InkMode.RECT_SELECT) {
                val end=points.last()
                canvas.drawRect(minOf(down.x,end.x).toFloat(),minOf(down.y,end.y).toFloat(),
                    maxOf(down.x,end.x).toFloat(),maxOf(down.y,end.y).toFloat(),paint)
            } else canvas.drawPath(path,paint)
        }
        selectedBounds()?.let {canvas.drawRect(it.left.toFloat(),it.top.toFloat(),it.right.toFloat(),it.bottom.toFloat(),paint)}
    }
    fun clearSelection() {if(!active) selection=emptySet()}
    fun cancel() {if(active) {selection=oldSelection;finish()}}
    private fun finish() {pointer=-1;points.clear();preview=null;before=emptyList();moving=false;onContact(false)}
}
