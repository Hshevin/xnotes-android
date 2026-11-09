package com.xnotes.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.CanvasViewport
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.ui.theme.ReferenceLocatorStyle

/** View-only fixed-world navigation, owned by the retained overlay rather than a renderer callback. */
internal class CanvasReferenceLocator(
    private val document:()->InfiniteDocument,
    private val surface:()->View,
    private val viewport:()->CanvasViewport,
    private val overlay:View,
    private val initialViewReady:()->Boolean,
    private val onLocated:()->Unit,
) {
    private val handler=Handler(Looper.getMainLooper())
    private var model:InfiniteDocument?=null
    private var region:Rect?=null
    private var pending=false
    private var shown=false
    private var enteredHost=false
    private var expires:Runnable?=null
    private var observedSurface:View?=null
    private var observer:ViewTreeObserver?=null
    private var previousFrame:List<Any?>?=null
    private val beforeDraw=ViewTreeObserver.OnPreDrawListener {observeFrame();true}
    private val attachment=object:View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v:View) {applyIfReady()}
        override fun onViewDetachedFromWindow(v:View) {
            removeObserver();previousFrame=null
            // Only a request made before its first host may wait. Leaving a host is new intent,
            // even when that host had not yet produced the second stable navigation frame.
            if(enteredHost || !pending) clear()
        }
    }
    init {overlay.addOnAttachStateChangeListener(attachment);bindSurface()}
    fun bindSurface() {
        val current=surface()
        if(observedSurface!==current) {
            if(enteredHost) clear()
            removeObserver();previousFrame=null
            observedSurface?.removeOnAttachStateChangeListener(attachment)
            observedSurface=current;current.addOnAttachStateChangeListener(attachment)
        }
        applyIfReady()
    }
    private fun removeObserver() {observer?.takeIf {it.isAlive}?.removeOnPreDrawListener(beforeDraw);observer=null}
    fun clear() {
        expires?.let(handler::removeCallbacks);expires=null
        removeObserver();previousFrame=null;model=null;region=null;pending=false;shown=false;enteredHost=false
        overlay.invalidate()
    }
    fun show(expected:InfiniteDocument,hint:Rect?):ReferenceRegionReveal {
        clear()
        if(hint==null || document()!==expected) return ReferenceRegionReveal.NONE
        if(!validCanvasSourceRegion(hint)) return ReferenceRegionReveal.OUTSIDE
        model=expected;region=hint.copy();pending=true;bindSurface()
        return ReferenceRegionReveal.SHOWN
    }
    fun applyIfReady() {
        if(!pending || !overlay.isAttachedToWindow || !surface().isAttachedToWindow) return
        enteredHost=true
        val actual=overlay.viewTreeObserver
        if(observer!==actual) {removeObserver();observer=actual;previousFrame=null;actual.addOnPreDrawListener(beforeDraw)}
        overlay.postInvalidateOnAnimation()
    }
    private fun observeFrame() {
        if(model!==document()) {clear();return}
        if(!pending) {removeObserver();return}
        val view=surface();val v=viewport()
        if(view!==observedSurface) {bindSurface();return}
        if(!view.isAttachedToWindow || !overlay.isAttachedToWindow || !view.isShown || !overlay.isShown ||
            !view.isLaidOut || !overlay.isLaidOut || view.isLayoutRequested || overlay.isLayoutRequested ||
            !initialViewReady() || view.width<=0 || view.height<=0 || overlay.width!=view.width || overlay.height!=view.height ||
            v.widthPx!=view.width || v.heightPx!=view.height || !v.zoom.isFinite() || v.zoom<=0) {previousFrame=null;return}
        val frame=listOf(view,view.width,view.height,overlay.width,overlay.height,v.widthPx,v.heightPx,
            v.insetLeft,v.insetTop,v.insetRight,v.insetBottom,v.zoom,v.scrollX,v.scrollY)
        if(frame!=previousFrame) {previousFrame=frame;overlay.postInvalidateOnAnimation();return}
        val hint=region ?: return
        pending=false;shown=true;removeObserver();previousFrame=null
        val density=overlay.resources.displayMetrics.density.toDouble()
        v.fit(hint,ReferenceLocatorStyle.Inset.value*density)
        onLocated();overlay.invalidate()
        val task=Runnable {clear()};expires=task
        handler.postDelayed(task,ReferenceLocatorStyle.DurationMillis)
    }
    fun draw(canvas:Canvas) {
        val expected=model ?: return
        if(expected!==document()) {clear();return}
        if(!shown || pending) return
        val rect=region?.let {viewport().contentToViewport(it)} ?: return
        val width=ReferenceLocatorStyle.StrokeWidth.value*overlay.resources.displayMetrics.density
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {style=Paint.Style.STROKE;color=Color.WHITE;strokeWidth=width*2}
        canvas.drawRect(rect.left.toFloat(),rect.top.toFloat(),rect.right.toFloat(),rect.bottom.toFloat(),paint)
        paint.color=Color.rgb(1,1,1);paint.strokeWidth=width
        canvas.drawRect(rect.left.toFloat(),rect.top.toFloat(),rect.right.toFloat(),rect.bottom.toFloat(),paint)
    }
}

internal fun validCanvasSourceRegion(rect:Rect)=rect.w>0 && rect.h>0 &&
    listOf(rect.x,rect.y,rect.w,rect.h,rect.right,rect.bottom).all {it.isFinite()}
