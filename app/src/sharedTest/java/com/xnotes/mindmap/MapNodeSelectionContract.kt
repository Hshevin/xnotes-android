package com.xnotes.mindmap

import android.content.Context
import android.view.InputDevice
import android.view.MotionEvent
import com.xnotes.core.geometry.Pt
import org.junit.Assert.*
import org.junit.Test

/** The same native node-selection events run on the host and the Android test runner. */
abstract class MapNodeSelectionContract {
    protected abstract fun ui(block:(Context)->Unit)
    private var time=100L
    protected class Fixture(context:Context) {
        val original=MindMapDocument(nodes=listOf(
            MapNode(id="root",parent=null,x=-460.0,y=-120.0,width=100.0,height=80.0),
            MapNode(id="child",parent="root",collapsed=true,x=-300.0,y=-120.0,width=100.0,height=80.0),
            MapNode(id="hidden",parent="child",x=-430.0,y=-100.0,width=60.0,height=50.0),
            MapNode(id="ink",parent=null,free=true,kind=NodeKind.INK,x=-40.0,y=-120.0,width=140.0,height=100.0,
                ink=listOf(MapInk(listOf(InkPoint(20.0,20.0),InkPoint(50.0,50.0))))),
            MapNode(id="ref",parent=null,free=true,kind=NodeKind.REFERENCE,x=180.0,y=-120.0,width=100.0,height=80.0,
                sources=listOf(MapSource(documentId="00000000-0000-0000-0000-000000000001",fileName="Source.pdf"))),
            MapNode(id="free",parent=null,free=true,x=180.0,y=80.0,width=100.0,height=80.0)),
            relations=listOf(MapRelation(id="edge",from="root",to="free")))
        val history=MapHistory(original)
        var selection:Set<String> = emptySet()
        var selections=0
        var moves=0
        var forbidden=0
        var busy=false
        private var captured:MapNodeSelectionCapture?=null
        val capturedIds get()=captured?.ids
        val view=MindMapView(context).apply {
            document=original;nodeSelectionMode=true
            onSelectNodes={_,ids->selection=ids;selections++;true}
            onCaptureNodes={doc,ids,originalSelection->
                assertFalse("Capture must precede pen busy",busy)
                assertEquals(selection,originalSelection)
                MapNodeSelectionCapture(doc,ids).also {captured=it}
            }
            onMoveNodes={capture,dx,dy->
                assertFalse("Release pen busy before committing",busy)
                assertSame(history.document,capture.document)
                moves++;history.commit(capture.document.translateNodes(capture.ids,dx,dy));selection=capture.ids;document=history.document;true
            }
            onInkContact={busy=it}
            onChange={forbidden++};onEdit={forbidden++};onSources={forbidden++}
            onSelectRelation={forbidden++};onEditRelation={forbidden++};onConnectionTarget={_,_->forbidden++}
            layout(0,0,1000,700);fit()
        }
        fun select(ids:Set<String>) {selection=ids;view.setSelectedNodes(ids)}
        fun at(id:String)=view.screenPoint(view.document.node(id).bounds.center)
        fun clean() {assertEquals(original,view.document);assertFalse(history.canUndo);assertEquals(0,moves);assertEquals(0,forbidden);assertFalse(busy)}
    }
    protected fun fixture(context:Context)=Fixture(context)
    protected fun pointers(v:MindMapView,action:Int,ids:List<Int>,types:List<Int>,points:List<Pt>,flags:Int=0) {
        val properties=types.mapIndexed {i,type->MotionEvent.PointerProperties().apply {id=ids[i];toolType=type}}.toTypedArray()
        val coordinates=points.map {p->MotionEvent.PointerCoords().apply {x=p.x.toFloat();y=p.y.toFloat();pressure=.7f}}.toTypedArray()
        val e=MotionEvent.obtain(100,time++,action,ids.size,properties,coordinates,0,0,1f,1f,888,0,
            if(types.any {it==MotionEvent.TOOL_TYPE_STYLUS}) InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN,flags)
        try {v.onTouchEvent(e)} finally {e.recycle()}
    }
    protected fun send(v:MindMapView,action:Int,p:Pt,type:Int=MotionEvent.TOOL_TYPE_FINGER,flags:Int=0)=
        pointers(v,action,listOf(7),listOf(type),listOf(p),flags)
    private fun tap(f:Fixture,id:String,type:Int=MotionEvent.TOOL_TYPE_FINGER) {
        val p=f.at(id);send(f.view,MotionEvent.ACTION_DOWN,p,type);send(f.view,MotionEvent.ACTION_UP,p,type)
    }
    @Test fun selectionTapsToggleOnReleaseAndNeverWriteInkOpenSourcesOrEditBodies()=ui {context->
        val f=fixture(context)
        for((id,type) in listOf("root" to MotionEvent.TOOL_TYPE_FINGER,"ink" to MotionEvent.TOOL_TYPE_STYLUS,"ref" to MotionEvent.TOOL_TYPE_FINGER)) {
            val before=f.selection;val p=f.at(id)
            send(f.view,MotionEvent.ACTION_DOWN,p,type);assertEquals(before,f.selection)
            send(f.view,MotionEvent.ACTION_UP,p,type);assertEquals(before+id,f.selection)
        }
        tap(f,"ink",MotionEvent.TOOL_TYPE_STYLUS);assertEquals(setOf("root","ref"),f.selection)
        val footer=f.view.document.node("ref").let {f.view.screenPoint(Pt(it.x+20,it.y+it.height-10))}
        send(f.view,MotionEvent.ACTION_DOWN,footer);send(f.view,MotionEvent.ACTION_UP,footer)
        assertEquals(setOf("root"),f.selection)
        assertNull(f.view.connectionPort());assertFalse(f.view.inkInput.active)
        f.view.onKeyDown(android.view.KeyEvent.KEYCODE_ENTER,android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_ENTER))
        f.clean()
    }
    @Test fun aBlankMarqueeReplacesSelectionByVisibleIntersectionInBothDirections()=ui {context->
        for(reverse in listOf(false,true)) {
            val f=fixture(context);f.select(setOf("free"))
            val corners=listOf(Pt(-490.0,-150.0),Pt(-195.0,-60.0)).let {if(reverse) it.reversed() else it}
            val a=f.view.screenPoint(corners[0]);val b=f.view.screenPoint(corners[1])
            send(f.view,MotionEvent.ACTION_DOWN,a);send(f.view,MotionEvent.ACTION_MOVE,b)
            assertEquals(setOf("free"),f.selection)
            send(f.view,MotionEvent.ACTION_UP,b)
            assertEquals(setOf("root","child"),f.selection);assertFalse("hidden" in f.selection);f.clean()
        }
    }
    @Test fun draggingAnAlreadySelectedGroupMovesEachSubtreeMemberOnceAndCommitsOneHistoryEntry()=ui {context->
        val f=fixture(context);val ids=setOf("root","child","free");f.select(ids)
        val p=f.at("child");val end=p+Pt(70.0,40.0);val dx=70.0/f.view.contentScale();val dy=40.0/f.view.contentScale()
        send(f.view,MotionEvent.ACTION_DOWN,p,MotionEvent.TOOL_TYPE_STYLUS)
        assertTrue(f.busy);assertEquals(ids,f.capturedIds)
        send(f.view,MotionEvent.ACTION_MOVE,end,MotionEvent.TOOL_TYPE_STYLUS)
        assertEquals(0,f.moves);assertFalse(f.history.canUndo)
        send(f.view,MotionEvent.ACTION_UP,end,MotionEvent.TOOL_TYPE_STYLUS)
        assertEquals(1,f.moves);assertEquals(ids,f.selection);assertFalse(f.busy);assertEquals(0,f.forbidden)
        val changed=f.view.document
        for(id in listOf("root","child","hidden","free")) {
            assertEquals(f.original.node(id).x+dx,changed.node(id).x,1e-4)
            assertEquals(f.original.node(id).y+dy,changed.node(id).y,1e-4)
            assertEquals(f.original.node(id).parent,changed.node(id).parent)
        }
        assertSame(f.original.node("ink"),changed.node("ink"));assertEquals(f.original.relations,changed.relations)
        f.history.undo();assertEquals(f.original,f.history.document);assertFalse(f.history.canUndo)
        f.history.redo();assertEquals(changed,f.history.document)
    }
    @Test fun draggingAnUnselectedInkNodeMovesOnlyThatNodeWithoutWritingOrReparenting()=ui {context->
        val f=fixture(context);f.select(setOf("free","ref"));val p=f.at("ink");val end=f.at("root")
        send(f.view,MotionEvent.ACTION_DOWN,p,MotionEvent.TOOL_TYPE_STYLUS)
        send(f.view,MotionEvent.ACTION_MOVE,end,MotionEvent.TOOL_TYPE_STYLUS)
        send(f.view,MotionEvent.ACTION_UP,end,MotionEvent.TOOL_TYPE_STYLUS)
        assertEquals(setOf("ink"),f.selection);assertEquals(setOf("ink"),f.capturedIds)
        assertEquals("ink",f.view.selected)
        assertEquals(1,f.moves);assertEquals(0,f.forbidden);assertFalse(f.busy)
        assertNull(f.view.document.node("ink").parent);assertTrue(f.view.document.node("ink").free)
        assertSame(f.original.node("ink").ink,f.view.document.node("ink").ink)
        for(id in listOf("root","child","hidden","free","ref")) assertSame(f.original.node(id),f.view.document.node(id))
    }
    @Test fun cancelledFlaggedOrOutOfBoundsSelectionGesturesKeepTheOriginalSetAndContent()=ui {context->
        for(case in 0..3) {
            val f=fixture(context);f.select(setOf("free"));val p=f.at("root");val end=p+Pt(70.0,40.0)
            send(f.view,MotionEvent.ACTION_DOWN,p,MotionEvent.TOOL_TYPE_STYLUS)
            if(case!=2) send(f.view,MotionEvent.ACTION_MOVE,end,MotionEvent.TOOL_TYPE_STYLUS)
            when(case) {
                0->send(f.view,MotionEvent.ACTION_CANCEL,end,MotionEvent.TOOL_TYPE_STYLUS)
                1->send(f.view,MotionEvent.ACTION_UP,end,MotionEvent.TOOL_TYPE_STYLUS,0x20)
                2->send(f.view,MotionEvent.ACTION_UP,end,MotionEvent.TOOL_TYPE_STYLUS)
                else->send(f.view,MotionEvent.ACTION_UP,Pt(-20.0,end.y),MotionEvent.TOOL_TYPE_STYLUS)
            }
            assertEquals(setOf("free"),f.selection);f.clean()
        }
    }
    @Test fun anEpochRevisionDocumentOrModeChangeRetiresTheOldGroupPreview()=ui {context->
        for(case in 0..5) {
            val f=fixture(context);f.select(setOf("free","ref"));val p=f.at("free");val end=p+Pt(70.0,40.0)
            send(f.view,MotionEvent.ACTION_DOWN,p,MotionEvent.TOOL_TYPE_STYLUS)
            send(f.view,MotionEvent.ACTION_MOVE,end,MotionEvent.TOOL_TYPE_STYLUS)
            when(case) {
                0->f.view.documentEpoch++
                1->f.view.documentRevision++
                2->f.view.document=f.view.document.update("root"){it.copy(text="newer")}
                3->{f.view.isEnabled=false;f.view.isEnabled=true}
                4->f.view.zoomBy(1.2f)
                else->f.view.nodeSelectionMode=false
            }
            val latest=f.view.document
            send(f.view,MotionEvent.ACTION_UP,end,MotionEvent.TOOL_TYPE_STYLUS)
            assertEquals(latest,f.view.document);assertEquals(setOf("free","ref"),f.selection)
            assertEquals(0,f.moves);assertFalse(f.busy);assertEquals(0,f.forbidden)
        }
    }
    @Test fun anEqualPayloadReplacementIsActuallyAdoptedBeforeTheNextSelectionCapture()=ui {context->
        val f=fixture(context);val next=f.original.copy()
        f.view.document=next;assertSame(next,f.view.document)
        f.view.onCaptureNodes={doc,ids,_->assertSame(next,doc);MapNodeSelectionCapture(doc,ids)}
        f.view.onMoveNodes={capture,_,_->assertSame(next,capture.document);f.moves++;true}
        f.select(setOf("free"));val p=f.at("free");val end=p+Pt(60.0,30.0)
        send(f.view,MotionEvent.ACTION_DOWN,p);send(f.view,MotionEvent.ACTION_MOVE,end);send(f.view,MotionEvent.ACTION_UP,end)
        assertEquals(1,f.moves);assertEquals(setOf("free"),f.selection)
    }
    private fun cancelledStream(context:Context,retire:(MindMapView)->Unit) {
        val f=fixture(context);f.select(setOf("free"));val palm=Pt(20.0,30.0);val p=f.at("root");val ink=f.at("ink")
        val finger=MotionEvent.TOOL_TYPE_FINGER;val pen=MotionEvent.TOOL_TYPE_STYLUS
        pointers(f.view,MotionEvent.ACTION_DOWN,listOf(2),listOf(finger),listOf(palm))
        pointers(f.view,MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),listOf(2,7),listOf(finger,pen),listOf(palm,p))
        pointers(f.view,MotionEvent.ACTION_MOVE,listOf(2,7),listOf(finger,pen),listOf(palm,p+Pt(60.0,30.0)))
        retire(f.view);assertFalse(f.busy)
        pointers(f.view,MotionEvent.ACTION_POINTER_UP or (1 shl 8),listOf(2,7),listOf(finger,pen),listOf(palm,p))
        pointers(f.view,MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),listOf(2,7),listOf(finger,pen),listOf(palm,ink))
        assertFalse("A cancelled stream must not revive selection or begin ink",f.busy)
        assertFalse(f.view.inkInput.active)
        pointers(f.view,MotionEvent.ACTION_POINTER_UP or (1 shl 8),listOf(2,7),listOf(finger,pen),listOf(palm,ink))
        pointers(f.view,MotionEvent.ACTION_UP,listOf(2),listOf(finger),listOf(palm))
        assertEquals(setOf("free"),f.selection);f.clean()
        f.view.nodeSelectionMode=true;tap(f,"root")
        assertEquals(setOf("free","root"),f.selection);f.clean()
    }
    @Test fun anEpochCancellationDrainsAllRemainingPointersUntilAFreshDown()=ui {context->cancelledStream(context){it.documentEpoch++}}
    @Test fun disablingSelectionModeCannotTurnItsRemainingPenPointersIntoInk()=ui {context->cancelledStream(context){it.nodeSelectionMode=false}}
    @Test fun selectionPenOwnsItsTapBeforeOrAfterThePalmWithoutWritingInk()=ui {context->
        for(palmFirst in listOf(false,true)) {
            val f=fixture(context);f.select(setOf("free"));val penAt=f.at("ink");val palm=Pt(20.0,30.0)
            val finger=MotionEvent.TOOL_TYPE_FINGER;val pen=MotionEvent.TOOL_TYPE_STYLUS
            if(palmFirst) pointers(f.view,MotionEvent.ACTION_DOWN,listOf(2),listOf(finger),listOf(palm))
            else pointers(f.view,MotionEvent.ACTION_DOWN,listOf(7),listOf(pen),listOf(penAt))
            pointers(f.view,MotionEvent.ACTION_POINTER_DOWN or ((if(palmFirst) 1 else 0) shl 8),listOf(2,7),listOf(finger,pen),listOf(palm,penAt))
            assertTrue(f.busy);assertEquals(setOf("free"),f.selection)
            pointers(f.view,MotionEvent.ACTION_POINTER_UP or (1 shl 8),listOf(2,7),listOf(finger,pen),listOf(palm,penAt))
            assertFalse(f.busy);assertEquals(setOf("free","ink"),f.selection)
            pointers(f.view,MotionEvent.ACTION_MOVE,listOf(2),listOf(finger),listOf(palm+Pt(60.0,30.0)))
            pointers(f.view,MotionEvent.ACTION_UP,listOf(2),listOf(finger),listOf(palm))
            assertEquals(setOf("free","ink"),f.selection);f.clean()
        }
    }
    @Test fun twoFingerTranslationCancelsTheMarqueeAndPansWithoutChangingItsSelection()=ui {context->
        val f=fixture(context);f.select(setOf("free"));val old=f.view.viewport()
        val finger=MotionEvent.TOOL_TYPE_FINGER;val a=Pt(100.0,500.0);val b=Pt(400.0,500.0)
        pointers(f.view,MotionEvent.ACTION_DOWN,listOf(2),listOf(finger),listOf(a))
        pointers(f.view,MotionEvent.ACTION_MOVE,listOf(2),listOf(finger),listOf(a+Pt(20.0,0.0)))
        pointers(f.view,MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),listOf(2,7),listOf(finger,finger),listOf(a,b))
        pointers(f.view,MotionEvent.ACTION_MOVE,listOf(2,7),listOf(finger,finger),listOf(a+Pt(70.0,30.0),b+Pt(70.0,30.0)))
        pointers(f.view,MotionEvent.ACTION_MOVE,listOf(2,7),listOf(finger,finger),listOf(a+Pt(110.0,45.0),b+Pt(110.0,45.0)))
        pointers(f.view,MotionEvent.ACTION_POINTER_UP or (1 shl 8),listOf(2,7),listOf(finger,finger),listOf(a+Pt(70.0,30.0),b+Pt(70.0,30.0)))
        pointers(f.view,MotionEvent.ACTION_UP,listOf(2),listOf(finger),listOf(a+Pt(70.0,30.0)))
        assertNotEquals(old,f.view.viewport());assertEquals(old.zoom,f.view.viewport().zoom,1e-5f)
        assertEquals(old.panX+110,f.view.viewport().panX,1e-4f);assertEquals(old.panY+45,f.view.viewport().panY,1e-4f)
        assertEquals(setOf("free"),f.selection);f.clean()
    }
    private fun cancelledPinch(context:Context,retire:(MindMapView)->Unit) {
        val f=fixture(context);f.select(setOf("free"));val finger=MotionEvent.TOOL_TYPE_FINGER;val pen=MotionEvent.TOOL_TYPE_STYLUS
        val a=Pt(100.0,500.0);val b=Pt(400.0,500.0);val ink=f.at("ink")
        pointers(f.view,MotionEvent.ACTION_DOWN,listOf(2),listOf(finger),listOf(a))
        pointers(f.view,MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),listOf(2,7),listOf(finger,finger),listOf(a,b))
        retire(f.view)
        pointers(f.view,MotionEvent.ACTION_POINTER_DOWN or (2 shl 8),listOf(2,7,9),listOf(finger,finger,pen),listOf(a,b,ink))
        assertFalse("Retired camera pointers must not become a new node or ink transaction",f.busy)
        assertFalse(f.view.inkInput.active)
        pointers(f.view,MotionEvent.ACTION_POINTER_UP or (2 shl 8),listOf(2,7,9),listOf(finger,finger,pen),listOf(a,b,ink))
        pointers(f.view,MotionEvent.ACTION_POINTER_UP or (1 shl 8),listOf(2,7),listOf(finger,finger),listOf(a,b))
        pointers(f.view,MotionEvent.ACTION_UP,listOf(2),listOf(finger),listOf(a))
        assertEquals(setOf("free"),f.selection);f.clean()
        f.view.nodeSelectionMode=true;tap(f,"root");assertEquals(setOf("free","root"),f.selection)
    }
    @Test fun changingModeDuringAPinchDrainsItsRemainingPenPointers()=ui {context->cancelledPinch(context){it.nodeSelectionMode=false}}
    @Test fun changingEpochDuringAPinchDrainsItsRemainingPenPointers()=ui {context->cancelledPinch(context){it.documentEpoch++}}
}
