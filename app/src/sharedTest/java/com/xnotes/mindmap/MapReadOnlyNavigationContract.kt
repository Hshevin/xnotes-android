package com.xnotes.mindmap

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.xnotes.core.geometry.Pt
import org.junit.Assert.*
import org.junit.Test

/** Real readonly native input contract, shared by host and Android event runners. */
abstract class MapReadOnlyNavigationContract {
    protected abstract fun ui(block:(Context)->Unit)
    private var time=100L
    private class Fixture(context:Context) {
        val original=MindMapDocument(nodes=listOf(
            MapNode(id="r",parent=null,x=-300.0,y=-100.0,width=120.0,height=80.0,text="Text"),
            MapNode(id="ink",parent=null,free=true,x=0.0,y=-100.0,width=160.0,height=100.0,kind=NodeKind.INK,
                ink=listOf(MapInk(listOf(InkPoint(15.0,20.0),InkPoint(45.0,40.0))))),
            MapNode(id="ref",parent=null,free=true,x=300.0,y=-100.0,width=120.0,height=80.0,kind=NodeKind.REFERENCE,
                sources=listOf(MapSource(documentId="00000000-0000-0000-0000-000000000001",fileName="Source.xnote")))),
            relations=listOf(MapRelation(id="e1",from="r",to="ref"),MapRelation(id="e2",from="r",to="ref",kind=RelationKind.DEPENDS_ON)))
        var nodeCalls=0;var edgeCalls=0;var forbidden=0;var inkCommits=0
        var candidates:List<String> = emptyList()
        var contact=false
        val events=mutableListOf<String>()
        val view=MindMapView(context).apply {
            document=original;readOnlyNavigation=true
            onGraphContact={events+="graph"}
            onReadOnlyContact={contact=it;events+="contact:$it"}
            onSelect={nodeCalls++};onSelectRelation={edgeCalls++}
            onReadOnlyRelationCandidates={candidates=it}
            onEdit={forbidden++};onEditRelation={forbidden++};onSources={forbidden++};onChange={forbidden++}
            onConnectionTarget={_,_->forbidden++};onConnectionBlank={_,_->forbidden++}
            onMoveNodes={_,_,_->forbidden++;false};onInk={_,_,_->inkCommits++;true}
            layout(0,0,1000,700);fit()
        }
        val frame=android.widget.FrameLayout(context).apply {
            addView(view,android.widget.FrameLayout.LayoutParams(-1,-1))
            measure(android.view.View.MeasureSpec.makeMeasureSpec(1000,android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(700,android.view.View.MeasureSpec.EXACTLY))
            layout(0,0,1000,700)
        }
        fun at(id:String)=view.screenPoint(original.node(id).bounds.center)
        fun unchanged() {assertSame(original,view.document);assertEquals(0,forbidden);assertEquals(0,inkCommits);assertFalse(contact)}
    }
    private fun pointers(v:MindMapView,action:Int,ids:List<Int>,types:List<Int>,points:List<Pt>,flags:Int=0) {
        val properties=types.mapIndexed {i,type->MotionEvent.PointerProperties().apply {id=ids[i];toolType=type}}.toTypedArray()
        val coordinates=points.map {p->MotionEvent.PointerCoords().apply {x=p.x.toFloat();y=p.y.toFloat();pressure=.7f}}.toTypedArray()
        val event=MotionEvent.obtain(100,time++,action,ids.size,properties,coordinates,0,0,1f,1f,888,0,
            if(types.any {it==MotionEvent.TOOL_TYPE_STYLUS}) InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN,flags)
        try {assertTrue(v.onTouchEvent(event))} finally {event.recycle()}
    }
    private fun send(v:MindMapView,action:Int,p:Pt,type:Int=MotionEvent.TOOL_TYPE_FINGER,flags:Int=0)=
        pointers(v,action,listOf(7),listOf(type),listOf(p),flags)

    @Test fun tapsSelectNodesAndOrderedOverlappingRelationsOnlyOnValidUp()=ui {context->
        val f=Fixture(context);val v=f.view
        for(id in listOf("r","ink","ref")) {
            val p=f.at(id);val before=f.nodeCalls
            val events=f.events.size
            send(v,MotionEvent.ACTION_DOWN,p,MotionEvent.TOOL_TYPE_STYLUS);assertEquals(before,f.nodeCalls)
            assertEquals("graph",f.events[events]);assertTrue(f.contact)
            send(v,MotionEvent.ACTION_UP,p,MotionEvent.TOOL_TYPE_STYLUS);assertEquals(before+1,f.nodeCalls);assertEquals(id,v.selected)
        }
        val footer=v.screenPoint(Pt(320.0,-30.0));send(v,MotionEvent.ACTION_DOWN,footer);send(v,MotionEvent.ACTION_UP,footer)
        assertEquals("ref",v.selected);assertEquals(0,f.forbidden)
        val edge=v.screenPoint(Pt(-100.0,-60.0));send(v,MotionEvent.ACTION_DOWN,edge);assertEquals(0,f.edgeCalls)
        send(v,MotionEvent.ACTION_UP,edge);assertEquals(1,f.edgeCalls);assertEquals(listOf("e2","e1"),f.candidates)
        val before=f.edgeCalls
        send(v,MotionEvent.ACTION_DOWN,edge);send(v,MotionEvent.ACTION_UP,edge,flags=0x20)
        send(v,MotionEvent.ACTION_DOWN,edge);send(v,MotionEvent.ACTION_UP,edge+Pt(100.0,0.0))
        send(v,MotionEvent.ACTION_DOWN,edge);send(v,MotionEvent.ACTION_CANCEL,edge)
        assertEquals(before,f.edgeCalls);f.unchanged()
    }
    @Test fun draggingAnyBodyOrEdgePansInsteadOfPreviewingMovingOrWritingContent()=ui {context->
        for(type in listOf(MotionEvent.TOOL_TYPE_FINGER,MotionEvent.TOOL_TYPE_STYLUS)) for(id in listOf("r","ink","ref","edge")) {
            val f=Fixture(context);val v=f.view;val before=v.viewport()
            val p=if(id=="edge") v.screenPoint(Pt(-100.0,-60.0)) else f.at(id)
            send(v,MotionEvent.ACTION_DOWN,p,type);send(v,MotionEvent.ACTION_MOVE,p+Pt(80.0,30.0),type)
            assertNotEquals("MOVE itself must navigate the camera, not start a hidden node preview",before,v.viewport())
            send(v,MotionEvent.ACTION_UP,p+Pt(80.0,30.0),type)
            assertNull(v.connectionPort());assertFalse(v.beginConnection("r"));assertEquals(0,f.nodeCalls);assertEquals(0,f.edgeCalls);f.unchanged()
        }
    }
    @Test fun twoFingerCameraAndPenPalmOwnershipNeverCreateSelectionsOrContent()=ui {context->
        val f=Fixture(context);val v=f.view;val pen=MotionEvent.TOOL_TYPE_STYLUS;val finger=MotionEvent.TOOL_TYPE_FINGER
        val p=f.at("ink");val palm=Pt(30.0,40.0);val before=v.viewport()
        pointers(v,MotionEvent.ACTION_DOWN,listOf(7),listOf(pen),listOf(p))
        pointers(v,MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),listOf(7,2),listOf(pen,finger),listOf(p,palm))
        pointers(v,MotionEvent.ACTION_MOVE,listOf(7,2),listOf(pen,finger),listOf(p,palm+Pt(100.0,80.0)))
        assertEquals(before,v.viewport())
        pointers(v,MotionEvent.ACTION_MOVE,listOf(7,2),listOf(pen,finger),listOf(p+Pt(60.0,20.0),palm))
        assertNotEquals(before,v.viewport())
        pointers(v,MotionEvent.ACTION_POINTER_UP,listOf(7,2),listOf(pen,finger),listOf(p+Pt(60.0,20.0),palm))
        val ended=v.viewport();pointers(v,MotionEvent.ACTION_MOVE,listOf(2),listOf(finger),listOf(palm+Pt(90.0,50.0)))
        pointers(v,MotionEvent.ACTION_UP,listOf(2),listOf(finger),listOf(palm));assertEquals(ended,v.viewport())
        val a=Pt(100.0,180.0);val b=Pt(220.0,180.0)
        pointers(v,MotionEvent.ACTION_DOWN,listOf(1),listOf(finger),listOf(a))
        pointers(v,MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),listOf(1,2),listOf(finger,finger),listOf(a,b))
        pointers(v,MotionEvent.ACTION_MOVE,listOf(1,2),listOf(finger,finger),listOf(a+Pt(50.0,0.0),b+Pt(50.0,0.0)))
        assertNotEquals(ended,v.viewport());val zoom=v.viewport().zoom
        val configuration=ViewConfiguration.get(context)
        val beginSpan=maxOf(260.0,configuration.scaledMinimumScalingSpan+configuration.scaledTouchSlop*4.0)
        // The first MOVE crossing min-span/slop starts scaling with prevSpan == currentSpan.
        pointers(v,MotionEvent.ACTION_MOVE,listOf(1,2),listOf(finger,finger),listOf(Pt(500-beginSpan/2,180.0),Pt(500+beginSpan/2,180.0)))
        // A real subsequent MOVE is needed before ScaleGestureDetector can report a non-unit factor.
        pointers(v,MotionEvent.ACTION_MOVE,listOf(1,2),listOf(finger,finger),listOf(Pt(500-beginSpan*.75,180.0),Pt(500+beginSpan*.75,180.0)))
        assertTrue(v.viewport().zoom>zoom*1.1f)
        pointers(v,MotionEvent.ACTION_POINTER_UP or (1 shl 8),listOf(1,2),listOf(finger,finger),listOf(a,b))
        pointers(v,MotionEvent.ACTION_UP,listOf(1),listOf(finger),listOf(a))
        assertEquals(0,f.nodeCalls);assertEquals(0,f.edgeCalls);f.unchanged()
    }
    @Test fun retirementDrainsTheOldStreamButAFreshDownUsesTheChosenMode()=ui {context->
        for(interrupt in 0..3) {
            val f=Fixture(context);val v=f.view;val p=f.at("ink");val palm=Pt(30.0,40.0)
            val pen=MotionEvent.TOOL_TYPE_STYLUS;val finger=MotionEvent.TOOL_TYPE_FINGER
            pointers(v,MotionEvent.ACTION_DOWN,listOf(7),listOf(pen),listOf(p))
            pointers(v,MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),listOf(7,2),listOf(pen,finger),listOf(p,palm))
            when(interrupt) {0->v.readOnlyNavigation=false;1->{v.documentEpoch++;v.document=v.document.copy()};2->{v.isEnabled=false;v.isEnabled=true};else->v.onWindowFocusChanged(false)}
            pointers(v,MotionEvent.ACTION_POINTER_UP,listOf(7,2),listOf(pen,finger),listOf(p,palm))
            pointers(v,MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),listOf(2,7),listOf(finger,pen),listOf(palm,p))
            pointers(v,MotionEvent.ACTION_MOVE,listOf(2,7),listOf(finger,pen),listOf(palm,p+Pt(30.0,20.0)))
            pointers(v,MotionEvent.ACTION_POINTER_UP or (1 shl 8),listOf(2,7),listOf(finger,pen),listOf(palm,p))
            pointers(v,MotionEvent.ACTION_UP,listOf(2),listOf(finger),listOf(palm))
            assertEquals(0,f.nodeCalls);assertEquals(0,f.edgeCalls);assertEquals(0,f.inkCommits)
            val fresh=v.screenPoint(v.document.node("ink").bounds.center)
            send(v,MotionEvent.ACTION_DOWN,fresh,pen);send(v,MotionEvent.ACTION_MOVE,fresh+Pt(15.0,10.0),pen)
            send(v,MotionEvent.ACTION_UP,if(interrupt==0) fresh+Pt(30.0,20.0) else fresh,pen)
            if(interrupt==0) assertEquals(1,f.inkCommits) else assertEquals(0,f.inkCommits)
            assertEquals(0,f.forbidden)
        }
    }
    @Test fun editKeysAndDoubleTapCannotDispatchEditingCallbacks()=ui {context->
        val f=Fixture(context);val v=f.view;val p=f.at("r")
        repeat(2) {send(v,MotionEvent.ACTION_DOWN,p);send(v,MotionEvent.ACTION_UP,p)}
        for(code in listOf(KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_DEL,KeyEvent.KEYCODE_FORWARD_DEL,KeyEvent.KEYCODE_Z)) {
            v.onKeyDown(code,KeyEvent(KeyEvent.ACTION_DOWN,code));v.onKeyUp(code,KeyEvent(KeyEvent.ACTION_UP,code))
        }
        f.unchanged()
    }
    private fun cancelledReadonlyStream(context:Context,modeOff:Boolean) {
            val f=Fixture(context);val v=f.view;val p=f.at("ink");val palm=Pt(30.0,40.0)
            val pen=MotionEvent.TOOL_TYPE_STYLUS;val finger=MotionEvent.TOOL_TYPE_FINGER
            pointers(v,MotionEvent.ACTION_DOWN,listOf(7),listOf(pen),listOf(p))
            pointers(v,MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),listOf(7,2),listOf(pen,finger),listOf(p,palm))
            pointers(v,MotionEvent.ACTION_CANCEL,listOf(7,2),listOf(pen,finger),listOf(p,palm))
            assertFalse(f.contact)
            if(modeOff) v.readOnlyNavigation=false
            val old=p+if(modeOff) Pt(30.0,20.0) else Pt(1.0,1.0)
            pointers(v,MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),listOf(2,7),listOf(finger,pen),listOf(palm,p))
            pointers(v,MotionEvent.ACTION_MOVE,listOf(2,7),listOf(finger,pen),listOf(palm,old))
            pointers(v,MotionEvent.ACTION_POINTER_UP or (1 shl 8),listOf(2,7),listOf(finger,pen),listOf(palm,old))
            pointers(v,MotionEvent.ACTION_UP,listOf(2),listOf(finger),listOf(palm))
            assertEquals("A cancelled stream must not write after leaving readonly mode",0,f.inkCommits)
            assertEquals("A cancelled stream must not select through a later POINTER_DOWN",0,f.nodeCalls)
            assertEquals(0,f.edgeCalls);assertEquals(0,f.forbidden);assertFalse(f.contact)
            val fresh=v.screenPoint(v.document.node("ink").bounds.center)
            send(v,MotionEvent.ACTION_DOWN,fresh,pen)
            if(modeOff) send(v,MotionEvent.ACTION_MOVE,fresh+Pt(30.0,20.0),pen)
            send(v,MotionEvent.ACTION_UP,if(modeOff) fresh+Pt(30.0,20.0) else fresh,pen)
            if(modeOff) assertEquals(1,f.inkCommits) else {assertEquals(1,f.nodeCalls);f.unchanged()}
    }
    @Test fun cancelCannotResumeAReadonlySelectionFromTheOldPointerStream()=ui {context->cancelledReadonlyStream(context,false)}
    @Test fun cancelCannotEnterWritableInkFromTheOldPointerStreamAfterModeSwitch()=ui {context->cancelledReadonlyStream(context,true)}
    @Test fun escapeCancelsActiveReadonlyNavigationAndDrainsTheRestOfItsPointerStream()=ui {context->
        val f=Fixture(context);val v=f.view;val p=f.at("ink");val palm=Pt(30.0,40.0)
        val pen=MotionEvent.TOOL_TYPE_STYLUS;val finger=MotionEvent.TOOL_TYPE_FINGER
        pointers(v,MotionEvent.ACTION_DOWN,listOf(7),listOf(pen),listOf(p))
        pointers(v,MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),listOf(7,2),listOf(pen,finger),listOf(p,palm))
        assertTrue(f.contact)
        assertTrue(v.onKeyDown(KeyEvent.KEYCODE_ESCAPE,KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ESCAPE)))
        assertFalse("Escape must reach cancellation even while navigation owns a pointer",f.contact)
        pointers(v,MotionEvent.ACTION_POINTER_UP,listOf(7,2),listOf(pen,finger),listOf(p,palm))
        pointers(v,MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),listOf(2,7),listOf(finger,pen),listOf(palm,p))
        pointers(v,MotionEvent.ACTION_POINTER_UP or (1 shl 8),listOf(2,7),listOf(finger,pen),listOf(palm,p))
        pointers(v,MotionEvent.ACTION_UP,listOf(2),listOf(finger),listOf(palm))
        assertEquals(0,f.nodeCalls);assertEquals(0,f.edgeCalls);f.unchanged()
        send(v,MotionEvent.ACTION_DOWN,p,pen);send(v,MotionEvent.ACTION_UP,p,pen)
        assertEquals(1,f.nodeCalls);f.unchanged()
    }

}
