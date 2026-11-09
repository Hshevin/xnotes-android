package com.xnotes.mindmap

import android.view.MotionEvent
import com.xnotes.canvas.CanvasState
import com.xnotes.core.geometry.Pt
import kotlin.math.hypot

/** Deliberately has no content controller, clipboard, IME, pen tool, history or insertion command. */
internal class OriginalPdfReadOnlyInput(private val state:CanvasState,private val current:()->Boolean,
    private val changed:()->Unit,private val newContact:()->Unit) {
    private var center:Pt?=null
    private var span=0.0
    var busy=false;private set
    fun cancel() {center=null;span=0.0;busy=false;state.zoomingInProgress=false}
    fun onTouch(event:MotionEvent):Boolean {
        if(!current()) {cancel();return true}
        if(event.actionMasked==MotionEvent.ACTION_CANCEL || event.actionMasked==MotionEvent.ACTION_UP) {cancel();changed();return true}
        val indices=(0 until event.pointerCount).filter {event.actionMasked!=MotionEvent.ACTION_POINTER_UP || it!=event.actionIndex}.take(2)
        if(indices.isEmpty()) {cancel();return true}
        val at=Pt(indices.sumOf {event.getX(it).toDouble()}/indices.size,indices.sumOf {event.getY(it).toDouble()}/indices.size)
        val distance=if(indices.size==2) hypot((event.getX(indices[0])-event.getX(indices[1])).toDouble(),
            (event.getY(indices[0])-event.getY(indices[1])).toDouble()) else 0.0
        if(event.actionMasked==MotionEvent.ACTION_DOWN) {newContact();busy=true}
        if(event.actionMasked==MotionEvent.ACTION_MOVE) {
            center?.let {previous->
                if(distance>0 && span>0) state.setZoomAnchored(previous,state.zoom*(distance/span))
                state.scrollBy(previous.x-at.x,previous.y-at.y)
                changed()
            }
        }
        state.zoomingInProgress=indices.size==2
        center=at;span=distance;return true
    }
}
