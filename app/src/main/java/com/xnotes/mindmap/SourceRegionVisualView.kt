package com.xnotes.mindmap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.CanvasViewport
import com.xnotes.ui.theme.MinTouchTarget
import com.xnotes.ui.theme.ReferenceLocatorStyle
import com.xnotes.ui.theme.Space
import kotlin.math.hypot

/** Temporary image, camera and rectangle only. There is no native document or content controller. */
internal class SourceRegionVisualView(context:Context):View(context) {
    internal val viewport=CanvasViewport()
    private var token:Any?=null
    private var retired=false
    private var ready=false
    private var frame=Rect(0.0,0.0,1.0,1.0)
    private var oldRegion:Rect?=null
    private var selected:Rect?=null
    private var picture:Bitmap?=null
    private var pictureFrame:Rect?=null
    private var selecting=false
    private var restore:SourceRegionCamera?=null
    private var valid:()->Boolean={false}
    private var regionChanged:(Rect?)->Boolean={false}
    private var busyChanged:(Boolean)->Unit={}
    private var settled:(Rect,SourceRegionCamera)->Unit={_,_->}
    private val density=resources.displayMetrics.density
    private val stroke=ReferenceLocatorStyle.StrokeWidth.value*density
    private val knob=Space.s4.value*density
    private val padding=Space.s16.value*density
    private val grab=MinTouchTarget.value*density/2
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val white=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.WHITE;style=Paint.Style.STROKE;strokeWidth=stroke*3}
    private val black=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.BLACK;style=Paint.Style.STROKE;strokeWidth=stroke}
    private var background=Color.WHITE
    private data class Drag(val pointer:Int,val pen:Boolean,val downPx:Pt,val downWorld:Pt,val initial:Rect?,
        val handle:SourceRegionHandle,var moved:Boolean=false)
    private var drag:Drag?=null
    private var penId:Int?=null
    private var center:Pt?=null
    private var span=0.0
    private var cameraStart:SourceRegionCamera?=null
    private var draining=true
    private var cameraDirty=false
    var busy=false;private set
    private var notifyViewport:Runnable?=null

    fun bind(identity:Any,initialFrame:Rect,old:Rect?,region:Rect?,select:Boolean,camera:SourceRegionCamera?,
        backgroundColor:Int,current:()->Boolean,onRegion:(Rect?)->Boolean,onBusy:(Boolean)->Unit,
        onSettled:(Rect,SourceRegionCamera)->Unit) {
        if(token!==identity) {
            abort();token=identity;ready=false;frame=initialFrame;oldRegion=old;restore=camera;picture=null;pictureFrame=null
        } else if(selecting!=select) abort()
        retired=false;valid=current;regionChanged=onRegion;busyChanged=onBusy;settled=onSettled
        selecting=select;background=backgroundColor
        if(drag==null) selected=region
        if(!ready && width>0 && height>0) initializeCamera()
        invalidate()
    }
    /** Bitmap ownership remains with the publishing controller/GC, including RenderThread use. */
    fun image(bitmap:Bitmap?,worldFrame:Rect?) {picture=bitmap;pictureFrame=worldFrame;invalidate()}
    private fun current()=!retired && token!=null && valid()
    private fun setBusy(value:Boolean) {if(busy!=value) {busy=value;busyChanged(value)}}
    internal fun camera()=viewport.centerContent.let {SourceRegionCamera(it.x,it.y,viewport.zoom)}
    private fun applyCamera(camera:SourceRegionCamera) {
        if(camera.valid()) {viewport.zoom=camera.zoom;viewport.centerOn(camera.centerX,camera.centerY)}
    }
    private fun initializeCamera() {
        viewport.widthPx=width;viewport.heightPx=height
        val cover=oldRegion?.let(frame::union) ?: frame
        val fitZoom=minOf(width/cover.w,height/cover.h)
        viewport.minZoom=minOf(CanvasViewport.MIN_ZOOM,fitZoom/16).coerceAtLeast(1e-12)
        restore?.takeIf {it.valid()}?.let(::applyCamera) ?: viewport.fit(cover,padding.toDouble())
        ready=true;publishViewport()
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
        val saved=if(ready) camera() else restore
        abort();super.onSizeChanged(w,h,oldw,oldh)
        viewport.widthPx=w;viewport.heightPx=h
        if(w>0 && h>0 && token!=null) {restore=saved;ready=false;initializeCamera()}
    }
    fun fit() {
        if(!current() || !ready) return
        abort();viewport.fit(oldRegion?.let(frame::union) ?: frame,padding.toDouble());invalidate();publishViewport()
    }
    fun zoomBy(factor:Double) {
        if(!current() || !ready || !factor.isFinite() || factor<=0) return
        abort();viewport.zoomAroundCenter(viewport.zoom*factor);invalidate();publishViewport()
    }
    private fun publishViewport() {
        notifyViewport?.let(::removeCallbacks)
        val expected=token
        val callback=Runnable {
            notifyViewport=null
            if(current() && token===expected && ready && !busy && isAttachedToWindow) settled(viewport.visibleContentRect(),camera())
        }
        notifyViewport=callback;post(callback)
    }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas);canvas.drawColor(background)
        if(!ready) return
        val bitmap=picture;val source=pictureFrame
        if(bitmap!=null && !bitmap.isRecycled && source!=null) {
            val at=viewport.contentToViewport(source)
            if(listOf(at.x,at.y,at.w,at.h).all {it.isFinite() && it.toFloat().isFinite()})
                canvas.drawBitmap(bitmap,null,RectF(at.left.toFloat(),at.top.toFloat(),at.right.toFloat(),at.bottom.toFloat()),paint)
        }
        oldRegion?.takeIf {it!=selected}?.let {outline(canvas,it,true)}
        selected?.let {outline(canvas,it,false)}
    }
    private fun outline(canvas:Canvas,region:Rect,historical:Boolean) {
        if(!SourceRegionGeometry.valid(region)) return
        val r=viewport.contentToViewport(region)
        if(!listOf(r.x,r.y,r.w,r.h).all {it.toFloat().isFinite()}) return
        val effect=if(historical) DashPathEffect(floatArrayOf(Space.s8.value*density,Space.s4.value*density),0f) else null
        white.pathEffect=effect;black.pathEffect=effect
        val box=RectF(r.left.toFloat(),r.top.toFloat(),r.right.toFloat(),r.bottom.toFloat())
        canvas.drawRect(box,white);canvas.drawRect(box,black)
        if(!historical && selecting) {
            white.pathEffect=null;black.pathEffect=null
            for((x,y) in listOf(box.left to box.top,box.right to box.top,box.left to box.bottom,box.right to box.bottom,
                box.centerX() to box.top,box.centerX() to box.bottom,box.left to box.centerY(),box.right to box.centerY())) {
                paint.color=Color.WHITE;canvas.drawCircle(x,y,knob+stroke,paint)
                paint.color=Color.BLACK;canvas.drawCircle(x,y,knob,paint)
            }
        }
    }
    private fun point(event:MotionEvent,index:Int)=Pt(event.getX(index).toDouble(),event.getY(index).toDouble())
    private fun pen(event:MotionEvent,index:Int)=event.getToolType(index) in listOf(MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.TOOL_TYPE_ERASER)
    private fun begin(event:MotionEvent,index:Int) {
        val px=point(event,index);if(!px.x.isFinite() || !px.y.isFinite()) return
        val isPen=pen(event,index);penId=if(isPen) event.getPointerId(index) else null
        if(selecting) {
            val world=viewport.viewportToContent(px)
            val handle=selected?.let {SourceRegionGeometry.hit(it,world,grab/viewport.zoom)} ?: SourceRegionHandle.NEW
            drag=Drag(event.getPointerId(index),isPen,px,world,selected,handle)
        } else {cameraStart=camera();center=px;span=0.0}
        setBusy(true)
    }
    private fun rollbackDrag() {drag?.let {selected=it.initial};drag=null;invalidate()}
    private fun abort() {
        notifyViewport?.let(::removeCallbacks);notifyViewport=null
        // The pen may already have finished while a palm pointer still owns the stream.
        // Preserve that completed camera; an unfinished camera gesture still rolls back below.
        val completedCamera=cameraStart==null && cameraDirty && ready && current()
        rollbackDrag();cameraStart?.let(::applyCamera);cameraStart=null
        penId=null;center=null;span=0.0;draining=true;cameraDirty=false;setBusy(false)
        if(completedCamera && current()) settled(viewport.visibleContentRect(),camera())
    }
    private fun finishDrag(event:MotionEvent,index:Int) {
        val active=drag ?: return
        if(active.moved) {
            val proposed=SourceRegionGeometry.drag(active.initial,active.handle,active.downWorld,viewport.viewportToContent(point(event,index)))
            selected=if(current() && regionChanged(proposed)) proposed else null
        }
        drag=null;invalidate()
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(!current() || !isAttachedToWindow || !ready) {abort();return true}
        // A DOWN on an unrendered image must not become a selection when pixels arrive later.
        if(picture==null) return true
        if(event.actionMasked==MotionEvent.ACTION_CANCEL) {abort();return true}
        val ending=event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_POINTER_UP
        val owner=drag?.pointer ?: penId
        if(ending && event.flags and MotionEvent.FLAG_CANCELED!=0 &&
            (owner==null || event.getPointerId(event.actionIndex)==owner)) {abort();return true}
        if(event.actionMasked==MotionEvent.ACTION_DOWN) {
            abort();draining=false;parent?.requestDisallowInterceptTouchEvent(true);begin(event,0);return true
        }
        if(draining) {if(event.actionMasked==MotionEvent.ACTION_UP) {setBusy(false);settleCamera()};return true}
        val actionIndex=event.actionIndex
        if(event.actionMasked==MotionEvent.ACTION_POINTER_DOWN && pen(event,actionIndex)) {
            val visible=if(cameraStart!=null) camera() else null;val changed=cameraDirty
            abort();draining=false;visible?.let(::applyCamera);begin(event,actionIndex);cameraDirty=changed;return true
        }
        val active=drag
        if(active!=null) {
            val index=event.findPointerIndex(active.pointer)
            if(index<0) {abort();return true}
            if(event.actionMasked==MotionEvent.ACTION_POINTER_DOWN) {
                if(active.pen) return true
                rollbackDrag();cameraStart=camera();center=null;span=0.0
            } else if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_POINTER_UP && actionIndex==index) {
                finishDrag(event,index);penId=null
                draining=true;setBusy(event.actionMasked!=MotionEvent.ACTION_UP)
                if(!busy) settleCamera();return true
            } else if(event.actionMasked==MotionEvent.ACTION_MOVE) {
                val at=point(event,index)
                if(!at.x.isFinite() || !at.y.isFinite()) {abort();return true}
                if(hypot(at.x-active.downPx.x,at.y-active.downPx.y)>=slop) active.moved=true
                if(active.moved) {selected=SourceRegionGeometry.drag(active.initial,active.handle,active.downWorld,viewport.viewportToContent(at));invalidate()}
                return true
            } else return true
        }
        val penIndex=penId?.let(event::findPointerIndex)?.takeIf {it>=0}
        if(penId!=null && penIndex==null) {abort();return true}
        if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_POINTER_UP && penIndex==actionIndex) {
            cameraStart=null;center=null;span=0.0;penId=null
            draining=true;setBusy(event.actionMasked!=MotionEvent.ACTION_UP);if(!busy) settleCamera();return true
        }
        val indices=if(penIndex!=null) listOf(penIndex) else (0 until event.pointerCount)
            .filter {event.actionMasked!=MotionEvent.ACTION_POINTER_UP || it!=actionIndex}.take(2)
        if(indices.isEmpty()) {abort();return true}
        val at=Pt(indices.sumOf {event.getX(it).toDouble()}/indices.size,indices.sumOf {event.getY(it).toDouble()}/indices.size)
        if(!at.x.isFinite() || !at.y.isFinite()) {abort();return true}
        val distance=if(indices.size==2) hypot(event.getX(indices[0]).toDouble()-event.getX(indices[1]).toDouble(),
            event.getY(indices[0]).toDouble()-event.getY(indices[1]).toDouble()) else 0.0
        if(event.actionMasked==MotionEvent.ACTION_MOVE) center?.let {previous->
            if(distance>0 && span>0) viewport.zoomAround(previous.x,previous.y,viewport.zoom*(distance/span))
            viewport.panByViewport(at.x-previous.x,at.y-previous.y);cameraDirty=true;invalidate()
        }
        center=at;span=distance;return true
    }
    private fun settleCamera() {if(cameraDirty) {cameraDirty=false;publishViewport()}}
    override fun onDetachedFromWindow() {abort();picture=null;pictureFrame=null;super.onDetachedFromWindow()}
    override fun onWindowFocusChanged(hasWindowFocus:Boolean) {super.onWindowFocusChanged(hasWindowFocus);if(!hasWindowFocus) abort()}
    fun retire() {abort();retired=true;valid={false};picture=null;pictureFrame=null;token=null;invalidate()}
}
