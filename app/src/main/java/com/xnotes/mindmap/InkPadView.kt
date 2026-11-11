package com.xnotes.mindmap

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import com.xnotes.core.geometry.Pt

enum class InkMode { PEN, ERASER, LASSO }

/** Enlarged host for the same transaction engine used by the graph. */
class InkPadView(context:Context):View(context) {
    var nodeWidth=320.0
    var nodeHeight=200.0
    val input=InkGestureController()
    var ink:List<MapInk>
        get()=input.ink
        set(value) {input.ink=value;painter.retainInk(value);invalidate()}
    var mode:InkMode
        get()=input.mode
        set(value) {input.mode=value;invalidate()}
    var penWidth:Double
        get()=input.width
        set(value) {input.width=value}
    var onCommit:(List<MapInk>)->Unit={}
    var onCommitChecked:((List<MapInk>,List<MapInk>)->Boolean)?=null
    var onContact:(Boolean)->Unit={}
    var onLimit:()->Unit={}
    private val painter=MapPainter()
    private val scale get()=minOf(width/nodeWidth,height/nodeHeight).coerceAtLeast(.001).toFloat()
    private val offsetX get()=(width-nodeWidth*scale).toFloat()/2
    private val offsetY get()=(height-nodeHeight*scale).toFloat()/2
    init {
        isFocusable=true
        input.onCommit={old,next->onCommitChecked?.invoke(old,next) ?: run {onCommit(next);true}}
        input.onContact={onContact(it);parent?.requestDisallowInterceptTouchEvent(it)}
        input.onLimit={onLimit()}
    }
    override fun performClick():Boolean {super.performClick();return true}
    override fun onDraw(canvas:Canvas) {
        // An AndroidView canvas is not clipped to the View; drawColor alone would cover sibling chrome.
        // One save covers the View clip, node transform and node clip; restoring to its count keeps the stack balanced.
        val saved=canvas.save();canvas.clipRect(0,0,width,height);canvas.drawColor(Color.LTGRAY)
        val s=scale;val ox=offsetX;val oy=offsetY
        canvas.translate(ox,oy);canvas.scale(s,s)
        canvas.drawRect(0f,0f,nodeWidth.toFloat(),nodeHeight.toFloat(),android.graphics.Paint().apply {color=Color.WHITE})
        canvas.clipRect(0f,0f,nodeWidth.toFloat(),nodeHeight.toFloat())
        painter.retainInk(input.ink)
        painter.drawInk(canvas,input.displayInk)
        input.drawSelection(canvas,Color.BLACK,s)
        canvas.restoreToCount(saved)
    }
    override fun onTouchEvent(e:MotionEvent):Boolean {
        val s=scale;val ox=offsetX;val oy=offsetY
        input.handle(e,{x,y->Pt(((x-ox)/s).toDouble(),((y-oy)/s).toDouble())},{it.x in 0.0..nodeWidth && it.y in 0.0..nodeHeight})
        invalidate();return true
    }
    override fun onWindowFocusChanged(hasWindowFocus:Boolean) {if(!hasWindowFocus) input.cancel();super.onWindowFocusChanged(hasWindowFocus);invalidate()}
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {input.cancel();super.onSizeChanged(w,h,oldw,oldh)}
    override fun onDetachedFromWindow() {input.cancel();super.onDetachedFromWindow()}
}
