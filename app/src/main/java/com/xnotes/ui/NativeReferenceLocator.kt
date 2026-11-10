package com.xnotes.ui

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import com.xnotes.canvas.CanvasState
import com.xnotes.canvas.CanvasView
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.Renderer
import com.xnotes.ui.theme.ReferenceLocatorStyle

/** Transient reading aid. It never borrows the content selection or a history operation. */
internal class NativeReferenceLocator(private val state:CanvasState,private val view:CanvasView,private val onLocated:()->Unit) {
    private val handler=Handler(Looper.getMainLooper())
    private var model:Document?=null
    private var page:Page?=null
    private var hint:Rect?=null
    private var pending=false
    private var enteredHost=false
    private var expires:Runnable?=null
    private var observer:ViewTreeObserver?=null
    private var previousFrame:List<Any?>?=null
    private val beforeDraw=ViewTreeObserver.OnPreDrawListener {observeFrame();true}
    init {
        view.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v:View) {applyIfReady()}
            override fun onViewDetachedFromWindow(v:View) {
                removeObserver();previousFrame=null
                // A request made before its first host may wait; leaving an entered host retires it,
                // even if that host did not reach its second stable navigation frame.
                if(enteredHost || !pending) clear()
            }
        })
    }
    private fun removeObserver() {observer?.takeIf {it.isAlive}?.removeOnPreDrawListener(beforeDraw);observer=null}
    fun clear() {
        expires?.let(handler::removeCallbacks);expires=null
        removeObserver();previousFrame=null;model=null;page=null;hint=null;pending=false;enteredHost=false
    }
    /** A fresh contact is the reader's new intent; never move its active camera on a later frame. */
    fun cancelPending() {if(pending) clear()}
    fun show(document:Document,target:Page?,region:Rect?):ReferenceRegionReveal {
        clear()
        if(target==null) return ReferenceRegionReveal.NONE
        model=document;page=target;pending=true
        val footprint=state.footprint(target)
        val clipped=region?.let {r->
            val l=maxOf(r.left,footprint.left);val t=maxOf(r.top,footprint.top)
            val right=minOf(r.right,footprint.right);val bottom=minOf(r.bottom,footprint.bottom)
            if(right>l && bottom>t) Rect(l,t,right-l,bottom-t) else null
        }
        hint=clipped;applyIfReady()
        return when {region==null->ReferenceRegionReveal.NONE;clipped==null->ReferenceRegionReveal.OUTSIDE;else->ReferenceRegionReveal.SHOWN}
    }
    /** Layout callbacks only arm observation; detached viewport dimensions are never a layout proof. */
    fun applyIfReady() {
        if(!pending || !view.isAttachedToWindow) return
        enteredHost=true
        val actual=view.viewTreeObserver
        if(observer!==actual) {removeObserver();observer=actual;previousFrame=null;actual.addOnPreDrawListener(beforeDraw)}
        view.requestRender()
    }
    private fun observeFrame() {
        val doc=model ?: return;val target=page ?: return
        val index=doc.pages.indexOfFirst {it===target}
        if(state.document!==doc || index<0) {clear();return}
        if(!pending) {removeObserver();return}
        if(!view.isAttachedToWindow || !view.isShown || !view.isLaidOut || view.isLayoutRequested || !state.didInitialFit ||
            state.viewportW<=0 || state.viewportH<=0 || index !in state.pageRects.indices) {previousFrame=null;return}
        val frame=listOf(view.width,view.height,state.viewportW,state.viewportH,state.insetLeft,state.insetTop,state.insetRight,
            state.insetBottom,state.rotationDeg,state.zoom,state.pageRects)
        if(frame!=previousFrame) {previousFrame=frame;view.requestRender();return}
        pending=false;removeObserver();previousFrame=null
        state.goToPage(index)
        hint?.let {state.scrollIntoView(state.fromPageSpaceRect(index,it),ReferenceLocatorStyle.Inset.value*state.devicePxPerDp)}
        val task=Runnable {clear();view.requestRender()};expires=task
        handler.postDelayed(task,ReferenceLocatorStyle.DurationMillis)
        onLocated();view.requestRender()
    }
    fun draw(renderer:Renderer) {
        val doc=model ?: return;val target=page ?: return
        val index=doc.pages.indexOfFirst {it===target}
        if(state.document!==doc || index !in state.pageRects.indices) {clear();return}
        if(pending) return
        val rect=hint?.let {state.fromPageSpaceRect(index,it)} ?: state.pageRects[index]
        val light=target.pdfPage!=null || state.paperColor(target).luminance()>=.5
        val color=if(light) Rgba(1,1,1) else Rgba(255,255,255)
        renderer.strokeRect(rect,Pen(color,ReferenceLocatorStyle.StrokeWidth.value*state.devicePxPerDp,cosmetic=true))
    }
}
