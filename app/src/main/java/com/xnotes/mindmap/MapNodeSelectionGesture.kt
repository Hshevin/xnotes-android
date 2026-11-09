package com.xnotes.mindmap

import android.view.MotionEvent
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import kotlin.math.hypot

/** Node-only gesture drafts. Ink continues to use the existing InkGestureController. */
internal class MapNodeSelectionGesture(
    private val document:()->MindMapDocument,
    private val selection:()->Set<String>,
    private val point:(Float,Float)->Pt,
    private val scale:()->Double,
    private val slop:Double,
    private val inside:(Float,Float)->Boolean,
    private val capture:(MindMapDocument,Set<String>,Set<String>)->MapNodeSelectionCapture?,
    private val contact:(Boolean)->Unit,
    private val choose:(MapNodeSelectionCapture,Set<String>)->Boolean,
    private val move:(MapNodeSelectionCapture,Double,Double)->Boolean,
    private val cancelDetectors:()->Unit,
    private val camera:(MotionEvent)->Unit,
    private val pan:(Float,Float)->Unit,
    private val aborted:()->Unit,
    private val redraw:()->Unit,
    private val visible:(MindMapDocument)->List<MapNode> = {it.visible()},
) {
    private data class Press(val doc:MindMapDocument,val original:Set<String>,val id:String?,val pointer:Int,val pen:Boolean,
        val x:Float,val y:Float,val origin:Pt,val scale:Double,val capture:MapNodeSelectionCapture,var moved:Boolean=false)
    private var press:Press?=null
    private var pinching=false
    private var focus:Pt?=null
    val active get()=press!=null || pinching
    val penContact get()=press?.pen==true
    var dispatchingCamera=false;private set
    var preview:MindMapDocument?=null;private set
    var previewSelection:Set<String>?=null;private set
    var marquee:Rect?=null;private set
    fun cancel() {
        val wasPen=press?.pen==true
        press=null;preview=null;previewSelection=null;marquee=null;pinching=false;focus=null
        if(wasPen) contact(false)
        redraw()
    }
    private fun pen(e:MotionEvent,i:Int)=e.getToolType(i) in listOf(MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.TOOL_TYPE_ERASER)
    private fun begin(e:MotionEvent,i:Int) {
        cancel();cancelDetectors();pinching=false;focus=null
        val doc=document();val at=point(e.getX(i),e.getY(i))
        val id=visible(doc).lastOrNull {it.bounds.contains(at)}?.id
        val original=selection().toSet();val ids=if(id==null) emptySet() else if(id in original) original else setOf(id)
        val token=capture(doc,ids,original) ?: return
        press=Press(doc,original,id,e.getPointerId(i),pen(e,i),e.getX(i),e.getY(i),at,scale(),token)
        if(pen(e,i)) contact(true) else cameraEvent(e)
    }
    private fun position(p:Press,x:Float,y:Float):Pt = p.origin+Pt((x-p.x)/p.scale,(y-p.y)/p.scale)
    private fun update(p:Press,x:Float,y:Float) {
        if(hypot((x-p.x).toDouble(),(y-p.y).toDouble())>slop) p.moved=true
        if(!p.moved) return
        if(p.id==null) {
            marquee=Rect.fromPoints(p.origin,position(p,x,y))
            previewSelection=visible(p.doc).filter {it.bounds.intersects(requireNotNull(marquee))}.mapTo(linkedSetOf()) {it.id}
        } else {
            preview=runCatching {p.doc.translateNodes(p.capture.ids,(x-p.x)/p.scale,(y-p.y)/p.scale)}.getOrNull()
            previewSelection=p.capture.ids
        }
        redraw()
    }
    fun handle(e:MotionEvent):Boolean {
        val current=press
        if(current?.pen==true && e.actionMasked in listOf(MotionEvent.ACTION_POINTER_DOWN,MotionEvent.ACTION_POINTER_UP) && e.getPointerId(e.actionIndex)!=current.pointer) return true
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN->begin(e,0)
            MotionEvent.ACTION_POINTER_DOWN->{
                if(pen(e,e.actionIndex)) begin(e,e.actionIndex)
                else if((0 until e.pointerCount).all {e.getToolType(it)==MotionEvent.TOOL_TYPE_FINGER}) {
                    cancel();cancelDetectors();pinching=true;focus=centroid(e);cameraEvent(e)
                }
            }
            MotionEvent.ACTION_MOVE->{
                if(pinching) {
                    cameraEvent(e,true);return true
                }
                val p=press ?: return true;val i=e.findPointerIndex(p.pointer)
                if(i<0 || p.doc!==document()) {aborted();cancel();return true}
                update(p,e.getX(i),e.getY(i))
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_POINTER_UP->{
                if(pinching) {cameraEvent(e);if(e.actionMasked==MotionEvent.ACTION_UP || e.pointerCount<=2) {pinching=false;focus=null};return true}
                val p=press ?: return true
                if(e.getPointerId(e.actionIndex)!=p.pointer) return true
                val x=e.getX(e.actionIndex);val y=e.getY(e.actionIndex)
                val valid=(e.flags and 0x20)==0 && inside(x,y) && p.doc===document() &&
                    (p.moved || hypot((x-p.x).toDouble(),(y-p.y).toDouble())<=slop)
                if(valid && p.moved) update(p,x,y)
                val selected=previewSelection
                val validMove=preview!=null
                cancel();cancelDetectors()
                if(valid) {
                    val accepted=when {
                        !p.moved->choose(p.capture,if(p.id==null) emptySet() else if(p.id in p.original) p.original-p.id else p.original+p.id)
                        p.id==null->choose(p.capture,selected.orEmpty())
                        else->validMove && move(p.capture,(x-p.x)/p.scale,(y-p.y)/p.scale)
                    }
                    if(!accepted) aborted()
                } else aborted()
            }
            MotionEvent.ACTION_CANCEL->{if(press!=null) aborted();cancel();cancelDetectors();pinching=false;focus=null}
        }
        return true
    }
    private fun centroid(e:MotionEvent)=Pt((0 until e.pointerCount).sumOf {e.getX(it).toDouble()}/e.pointerCount,
        (0 until e.pointerCount).sumOf {e.getY(it).toDouble()}/e.pointerCount)
    private fun cameraEvent(e:MotionEvent,translate:Boolean=false) {
        dispatchingCamera=true
        try {
            if(translate) {
                val now=centroid(e);focus?.let {previous->pan((now.x-previous.x).toFloat(),(now.y-previous.y).toFloat())};focus=now
            }
            camera(e)
        } finally {dispatchingCamera=false}
    }
}
