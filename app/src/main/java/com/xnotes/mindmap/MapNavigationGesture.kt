package com.xnotes.mindmap

import android.view.MotionEvent
import com.xnotes.core.geometry.Pt
import kotlin.math.hypot

/** Pointer ownership and camera navigation only. It never creates a document or an editing draft. */
internal class MapNavigationGesture(
    private val document:()->MindMapDocument,
    private val slop:Double,
    private val inside:(Float,Float)->Boolean,
    private val node:(Float,Float)->String?,
    private val edges:(Float,Float)->List<String>,
    private val chooseNode:(String?)->Unit,
    private val chooseEdges:(List<String>)->Unit,
    private val camera:(MotionEvent)->Unit,
    private val pan:(Float,Float)->Unit,
    private val cancelDetectors:()->Unit,
    private val contact:(Boolean)->Unit,
) {
    private class Press(val document:MindMapDocument,val pointer:Int,val pen:Boolean,val x:Float,val y:Float,
        val node:String?,val edges:List<String>,var lastX:Float=x,var lastY:Float=y,var moved:Boolean=false)
    private var press:Press?=null
    private var pinching=false
    private var focus:Pt?=null
    var active=false;private set
    var dispatchingCamera=false;private set
    private fun setContact(value:Boolean) {if(active!=value) {active=value;contact(value)}}
    private fun pen(e:MotionEvent,i:Int)=e.getToolType(i) in listOf(MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.TOOL_TYPE_ERASER)
    fun cancel() {press=null;pinching=false;focus=null;setContact(false)}
    private inline fun changingCamera(block:()->Unit) {
        dispatchingCamera=true
        try {block()} finally {dispatchingCamera=false}
    }
    private fun begin(e:MotionEvent,i:Int) {
        cancelDetectors();pinching=false;focus=null
        val x=e.getX(i);val y=e.getY(i);val hit=node(x,y)
        press=Press(document(),e.getPointerId(i),pen(e,i),x,y,hit,if(hit==null) edges(x,y) else emptyList())
        setContact(true)
        if(!pen(e,i)) changingCamera {camera(e)}
    }
    private fun centroid(e:MotionEvent):Pt {
        var x=0.0;var y=0.0
        for(i in 0 until e.pointerCount) {x+=e.getX(i);y+=e.getY(i)}
        return Pt(x/e.pointerCount,y/e.pointerCount)
    }
    fun handle(e:MotionEvent):Boolean {
        val owner=press
        // A contact from another pointer is a palm while the pen owns navigation.
        if(owner?.pen==true && e.actionMasked in listOf(MotionEvent.ACTION_POINTER_DOWN,MotionEvent.ACTION_POINTER_UP) &&
            e.getPointerId(e.actionIndex)!=owner.pointer) return true
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN->begin(e,0)
            MotionEvent.ACTION_POINTER_DOWN->{
                if(pen(e,e.actionIndex)) begin(e,e.actionIndex)
                else if((0 until e.pointerCount).all {e.getToolType(it)==MotionEvent.TOOL_TYPE_FINGER}) {
                    press=null;pinching=true;focus=centroid(e);setContact(true)
                    changingCamera {camera(e)}
                }
            }
            MotionEvent.ACTION_MOVE->{
                if(pinching) {
                    val at=centroid(e);val old=focus
                    changingCamera {
                        // Translate the old focus to the new one before scaling around the new focus.
                        if(old!=null) pan((at.x-old.x).toFloat(),(at.y-old.y).toFloat())
                        camera(e)
                    }
                    focus=at;return true
                }
                val p=press ?: return true
                val i=e.findPointerIndex(p.pointer)
                if(i<0 || p.document!==document()) {cancel();cancelDetectors();return true}
                val x=e.getX(i);val y=e.getY(i)
                if(hypot((x-p.x).toDouble(),(y-p.y).toDouble())>slop) p.moved=true
                if(p.moved) changingCamera {pan(x-p.lastX,y-p.lastY)}
                p.lastX=x;p.lastY=y
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_POINTER_UP->{
                if(pinching) {
                    changingCamera {camera(e)}
                    // The remaining finger must not become a new tap or a one-finger content gesture.
                    pinching=false;focus=null;press=null
                    if(e.actionMasked==MotionEvent.ACTION_UP) {setContact(false);cancelDetectors()}
                    return true
                }
                val p=press
                if(p==null) {
                    if(e.actionMasked==MotionEvent.ACTION_UP) {setContact(false);cancelDetectors()}
                    return true
                }
                if(e.getPointerId(e.actionIndex)!=p.pointer) return true
                val x=e.getX(e.actionIndex);val y=e.getY(e.actionIndex)
                val select=!p.moved && p.document===document() && inside(x,y) && (e.flags and 0x20)==0 &&
                    hypot((x-p.x).toDouble(),(y-p.y).toDouble())<=slop
                press=null;focus=null;cancelDetectors()
                if(e.actionMasked==MotionEvent.ACTION_UP) setContact(false)
                if(select) {
                    if(p.node!=null || p.edges.isEmpty()) chooseNode(p.node) else chooseEdges(p.edges)
                }
            }
            MotionEvent.ACTION_CANCEL->{cancel();cancelDetectors()}
        }
        return true
    }
}
