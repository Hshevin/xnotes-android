package com.xnotes.mindmap

import android.content.Context
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import org.junit.Assert.*
import org.junit.Test

/** Executed unchanged on the host and by the device instrumentation runner. */
abstract class MapInputContract {
    protected abstract fun ui(block: (Context) -> Unit)
    private var time = 100L
    private fun event(action: Int, x: Float = 20f, y: Float = 20f, type: Int = MotionEvent.TOOL_TYPE_STYLUS, flags: Int = 0): MotionEvent {
        val props = MotionEvent.PointerProperties().apply { id = 7; toolType = type }
        val coords = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = .7f }
        return MotionEvent.obtain(100, time++, action, 1, arrayOf(props), arrayOf(coords), 0, 0,
            1f, 1f, 888, 0, InputDevice.SOURCE_STYLUS, flags)
    }
    private fun send(v: View, action: Int, x: Float = 20f, y: Float = 20f, type: Int = MotionEvent.TOOL_TYPE_STYLUS, flags: Int = 0) {
        event(action, x, y, type, flags).let { try { v.onTouchEvent(it) } finally { it.recycle() } }
    }
    private fun pad(context: Context) = InkPadView(context).also { it.layout(0, 0, 320, 200); it.onCommit = { ink -> it.ink = ink } }

    @Test fun stylusRetainsPressureAndCancellingNextStrokePreservesFinishedInk() = ui { context ->
        val v = pad(context)
        send(v, MotionEvent.ACTION_DOWN)
        send(v, MotionEvent.ACTION_MOVE, 40f, 30f)
        send(v, MotionEvent.ACTION_UP, 60f, 40f)
        val ink = v.ink
        assertEquals(1, ink.size)
        assertEquals(.7, ink.single().points.first().pressure, .001)
        send(v, MotionEvent.ACTION_DOWN)
        send(v, MotionEvent.ACTION_MOVE, 100f, 50f)
        send(v, MotionEvent.ACTION_CANCEL)
        assertEquals(ink, v.ink)
    }
    @Test fun fingerAndCancelledUpDoNotCreateInk() = ui { context ->
        val v = pad(context)
        send(v, MotionEvent.ACTION_DOWN, type = MotionEvent.TOOL_TYPE_FINGER)
        send(v, MotionEvent.ACTION_UP, type = MotionEvent.TOOL_TYPE_FINGER)
        assertTrue(v.ink.isEmpty())
        send(v, MotionEvent.ACTION_DOWN)
        send(v, MotionEvent.ACTION_UP, flags = 0x20)
        assertTrue(v.ink.isEmpty())
    }
    @Test fun penStyleIsLatchedForTheWholeStroke() = ui { context ->
        val v = pad(context)
        v.penWidth = 2.0
        send(v, MotionEvent.ACTION_DOWN)
        v.mode = InkMode.ERASER
        v.penWidth = 8.0
        send(v, MotionEvent.ACTION_UP, 40f, 40f)
        assertEquals(2.0, v.ink.single().width, .001)
    }
    @Test fun eraserCancelDoesNotEraseAndSparseCrossingErasesWholeStroke() = ui { context ->
        val v = pad(context)
        val ink = listOf(MapInk(listOf(InkPoint(50.0, 20.0), InkPoint(50.0, 180.0))))
        v.ink = ink; v.mode = InkMode.ERASER
        send(v, MotionEvent.ACTION_DOWN, 50f, 50f)
        send(v, MotionEvent.ACTION_CANCEL)
        assertEquals(ink, v.ink)
        send(v, MotionEvent.ACTION_DOWN, 10f, 100f)
        send(v, MotionEvent.ACTION_UP, 100f, 100f)
        assertTrue(v.ink.isEmpty())
    }
    @Test fun movingAndCancellingGraphDragDoesNotCommitThenNormalDragCommitsOnce() = ui { context ->
        val v = MindMapView(context)
        v.document = MindMapDocument(nodes = listOf(MapNode(id = "root", parent = null)))
        v.layout(0, 0, 500, 400); v.fit()
        var commits = 0
        v.onChange = { commits++; v.document = it }
        send(v, MotionEvent.ACTION_DOWN, 250f, 200f)
        send(v, MotionEvent.ACTION_MOVE, 310f, 240f)
        send(v, MotionEvent.ACTION_CANCEL)
        assertEquals(0, commits)
        send(v, MotionEvent.ACTION_DOWN, 250f, 200f)
        send(v, MotionEvent.ACTION_MOVE, 320f, 250f)
        send(v, MotionEvent.ACTION_UP, 320f, 250f)
        assertEquals(1, commits)
        assertNotEquals(0.0, v.document.root.x)
    }
    @Test fun cancelledGraphUpDoesNotCommit() = ui { context ->
        val v = MindMapView(context)
        v.layout(0, 0, 500, 400); v.fit()
        var commits = 0; v.onChange = { commits++ }
        send(v, MotionEvent.ACTION_DOWN, 250f, 200f)
        send(v, MotionEvent.ACTION_MOVE, 320f, 250f)
        send(v, MotionEvent.ACTION_UP, 320f, 250f, flags = 0x20)
        assertEquals(0, commits)
    }
    @Test fun jsonNullParentUsesTheSameContractOnAndroidAndHost() = ui { _ ->
        val doc = MindMapDocument()
        assertEquals(doc, MapCodec.decode(MapCodec.encode(doc)))
    }
    private fun palmAndPen(v: View, action: Int, penX: Float, penY: Float) {
        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 2; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 7; toolType = MotionEvent.TOOL_TYPE_STYLUS })
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply { x = 150f; y = 150f; pressure = 1f },
            MotionEvent.PointerCoords().apply { x = penX; y = penY; pressure = .7f })
        val e = MotionEvent.obtain(100, time++, action, 2, props, coords, 0, 0, 1f, 1f, 888, 0, InputDevice.SOURCE_STYLUS, 0)
        try { v.onTouchEvent(e) } finally { e.recycle() }
    }
    @Test fun penCanStartAfterThePalmAndLiftWhilePalmRemains() = ui { context ->
        val v = pad(context)
        send(v, MotionEvent.ACTION_DOWN, 150f, 150f, type = MotionEvent.TOOL_TYPE_FINGER)
        palmAndPen(v, MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), 20f, 20f)
        palmAndPen(v, MotionEvent.ACTION_MOVE, 40f, 40f)
        palmAndPen(v, MotionEvent.ACTION_POINTER_UP or (1 shl 8), 50f, 50f)
        assertEquals(1, v.ink.size)
        assertEquals(20.0, v.ink.single().points.first().x, .001)
        assertEquals(50.0, v.ink.single().points.last().x, .001)
    }
    @Test fun graphDragCommitsWhenPenLiftsBeforePalm() = ui { context ->
        val v = MindMapView(context)
        v.layout(0, 0, 500, 400); v.fit()
        var commits = 0; v.onChange = { commits++ }
        send(v, MotionEvent.ACTION_DOWN, 250f, 200f)
        palmAndPen(v, MotionEvent.ACTION_MOVE, 310f, 240f)
        palmAndPen(v, MotionEvent.ACTION_POINTER_UP or (1 shl 8), 310f, 240f)
        assertEquals(1, commits)
    }
    @Test fun lassoMovesInkLocallyAndCancellationKeepsThePriorGeometry() = ui { context ->
        val v = pad(context)
        val ink = listOf(MapInk(listOf(InkPoint(20.0, 20.0), InkPoint(40.0, 40.0))))
        v.ink = ink; v.mode = InkMode.LASSO
        send(v, MotionEvent.ACTION_DOWN, 10f, 10f)
        send(v, MotionEvent.ACTION_MOVE, 60f, 10f)
        send(v, MotionEvent.ACTION_MOVE, 60f, 60f)
        send(v, MotionEvent.ACTION_MOVE, 10f, 60f)
        send(v, MotionEvent.ACTION_UP, 10f, 10f)
        send(v, MotionEvent.ACTION_DOWN, 30f, 30f)
        send(v, MotionEvent.ACTION_MOVE, 80f, 80f)
        send(v, MotionEvent.ACTION_CANCEL)
        assertEquals(ink, v.ink)
        send(v, MotionEvent.ACTION_DOWN, 30f, 30f)
        send(v, MotionEvent.ACTION_MOVE, 80f, 80f)
        send(v, MotionEvent.ACTION_UP, 80f, 80f)
        assertEquals(70.0, v.ink.single().points.first().x, .001)
        assertEquals(70.0, v.ink.single().points.first().y, .001)
    }

    @Test fun graphSourceNullsAndRelationsRoundTripOnBothPlatforms() = ui { _ ->
        val doc=MindMapDocument(nodes=listOf(MapNode(id="root",parent=null,sources=listOf(MapSource(documentId="00000000-0000-0000-0000-000000000001"))),MapNode(id="free",parent=null,free=true)),relations=listOf(MapRelation(from="root",to="free")))
        assertEquals(doc,MapCodec.decode(MapCodec.encode(doc)))
    }

    @Test fun tappingSourceFooterOpensItsListWithoutMovingTheNode() = ui { context ->
        val v=MindMapView(context)
        val doc=MindMapDocument(nodes=listOf(MapNode(id="root",parent=null,sources=listOf(MapSource(documentId="00000000-0000-0000-0000-000000000001")))))
        v.document=doc;v.layout(0,0,500,400);v.fit()
        var opened:String?=null;var commits=0
        v.onSources={opened=it};v.onChange={commits++}
        send(v,MotionEvent.ACTION_DOWN,250f,240f)
        send(v,MotionEvent.ACTION_UP,250f,240f)
        assertEquals("root",opened);assertEquals(0,commits);assertEquals(doc,v.document)
    }

    @Test fun inlineInkLocksNodeAndKeepsSamplesOutsideItsFrame() = ui { context ->
        val n=MapNode(id="ink",parent=null,kind=NodeKind.INK)
        val v=MindMapView(context).apply {document=MindMapDocument(nodes=listOf(n));layout(0,0,500,400);fit()}
        var commits=0
        v.onChange={commits++;v.document=it}
        send(v,MotionEvent.ACTION_DOWN,250f,200f)
        send(v,MotionEvent.ACTION_MOVE,1200f,900f)
        send(v,MotionEvent.ACTION_UP,1400f,1000f)
        assertEquals(1,commits)
        val result=v.document.root
        assertEquals(n.x,result.x,.001);assertEquals(n.y,result.y,.001)
        assertEquals(n.width,result.width,.001);assertEquals(n.height,result.height,.001)
        assertTrue(result.ink.single().points.last().x>n.width)
        assertTrue(result.ink.single().points.last().y>n.height)
        assertEquals(v.document,MapCodec.decode(MapCodec.encode(v.document)))
    }
    @Test fun fingerOnInkBodyDoesNotMoveNode() = ui { context ->
        val doc=MindMapDocument(nodes=listOf(MapNode(id="ink",parent=null,kind=NodeKind.INK)))
        val v=MindMapView(context).apply {document=doc;layout(0,0,500,400);fit()}
        var commits=0;v.onChange={commits++}
        send(v,MotionEvent.ACTION_DOWN,250f,200f,type=MotionEvent.TOOL_TYPE_FINGER)
        send(v,MotionEvent.ACTION_MOVE,300f,230f,type=MotionEvent.TOOL_TYPE_FINGER)
        send(v,MotionEvent.ACTION_UP,300f,230f,type=MotionEvent.TOOL_TYPE_FINGER)
        assertEquals(0,commits);assertEquals(doc,v.document)
    }
    @Test fun nodeInkLostFocusDiscardsOnlyLiveStroke() = ui { context ->
        val v=pad(context)
        send(v,MotionEvent.ACTION_DOWN);send(v,MotionEvent.ACTION_UP,40f,40f)
        val old=v.ink
        send(v,MotionEvent.ACTION_DOWN);send(v,MotionEvent.ACTION_MOVE,60f,60f)
        v.onWindowFocusChanged(false)
        send(v,MotionEvent.ACTION_UP,80f,80f)
        assertEquals(old,v.ink)
    }
    @Test fun enlargedInkAlsoPreservesOutOfFrameSamples() = ui { context ->
        val v=pad(context)
        send(v,MotionEvent.ACTION_DOWN)
        send(v,MotionEvent.ACTION_MOVE,-40f,250f)
        send(v,MotionEvent.ACTION_UP,-70f,300f)
        assertEquals(-70.0,v.ink.single().points.last().x,.001)
        assertEquals(300.0,v.ink.single().points.last().y,.001)
    }

    @Test fun fullInkBudgetStillAllowsErasingAndCancellingErase() = ui { context ->
        val v=pad(context)
        val ink=listOf(MapInk(List(MindMapDocument.MAX_POINTS) {InkPoint(30.0,30.0)}))
        v.ink=ink;v.mode=InkMode.ERASER
        send(v,MotionEvent.ACTION_DOWN,30f,30f);send(v,MotionEvent.ACTION_CANCEL)
        assertEquals(ink,v.ink)
        send(v,MotionEvent.ACTION_DOWN,30f,30f);send(v,MotionEvent.ACTION_UP,30f,30f)
        assertTrue(v.ink.isEmpty())
    }
    @Test fun successfulLassoMoveKeepsSelectionForAnotherMoveAndCancel() = ui { context ->
        val v=pad(context)
        v.ink=listOf(MapInk(listOf(InkPoint(20.0,20.0),InkPoint(40.0,40.0))))
        v.mode=InkMode.LASSO
        send(v,MotionEvent.ACTION_DOWN,10f,10f);send(v,MotionEvent.ACTION_MOVE,60f,10f)
        send(v,MotionEvent.ACTION_MOVE,60f,60f);send(v,MotionEvent.ACTION_MOVE,10f,60f);send(v,MotionEvent.ACTION_UP,10f,10f)
        send(v,MotionEvent.ACTION_DOWN,30f,30f);send(v,MotionEvent.ACTION_UP,80f,80f)
        val once=v.ink
        send(v,MotionEvent.ACTION_DOWN,80f,80f);send(v,MotionEvent.ACTION_MOVE,100f,100f);send(v,MotionEvent.ACTION_CANCEL)
        assertEquals(once,v.ink)
        send(v,MotionEvent.ACTION_DOWN,80f,80f);send(v,MotionEvent.ACTION_UP,100f,100f)
        assertEquals(90.0,v.ink.single().points.first().x,.001)
    }

    @Test fun quickInlineStrokesDoNotTriggerDoubleTapEditingAndFreezeViewport() = ui { context ->
        val v=MindMapView(context).apply {document=MindMapDocument(nodes=listOf(MapNode(id="ink",parent=null,kind=NodeKind.INK)));layout(0,0,500,400);fit()}
        var edits=0;v.onEdit={edits++};v.onChange={v.document=it}
        val camera=v.viewport()
        send(v,MotionEvent.ACTION_DOWN,250f,200f)
        v.zoomBy(2f);v.fit();v.centerOn("ink")
        send(v,MotionEvent.ACTION_UP,260f,210f)
        send(v,MotionEvent.ACTION_DOWN,250f,200f);send(v,MotionEvent.ACTION_UP,270f,220f)
        assertEquals(2,v.document.root.ink.size);assertEquals(0,edits);assertEquals(camera,v.viewport())
    }

    @Test fun deferredHostInkUpdatePreservesMovedSelection() = ui { context ->
        val v=pad(context)
        v.ink=listOf(MapInk(listOf(InkPoint(20.0,20.0),InkPoint(40.0,40.0))))
        var pending:List<MapInk>?=null
        v.onCommitChecked={_,next->pending=next;true}
        v.mode=InkMode.LASSO
        send(v,MotionEvent.ACTION_DOWN,10f,10f);send(v,MotionEvent.ACTION_MOVE,60f,10f)
        send(v,MotionEvent.ACTION_MOVE,60f,60f);send(v,MotionEvent.ACTION_MOVE,10f,60f);send(v,MotionEvent.ACTION_UP,10f,10f)
        send(v,MotionEvent.ACTION_DOWN,30f,30f);send(v,MotionEvent.ACTION_UP,80f,80f)
        v.ink=requireNotNull(pending)
        send(v,MotionEvent.ACTION_DOWN,80f,80f);send(v,MotionEvent.ACTION_UP,100f,100f)
        v.ink=requireNotNull(pending)
        assertEquals(90.0,v.ink.single().points.first().x,.001)
    }

    private fun connectView(context:Context)=MindMapView(context).apply {
        document=MindMapDocument(nodes=listOf(MapNode(id="source",parent=null,kind=NodeKind.INK),
            MapNode(id="target",parent=null,free=true,kind=NodeKind.INK,x=500.0)))
        layout(0,0,800,500);fit();selected="source"
    }
    private fun at(v:MindMapView,id:String)=v.screenPoint(v.document.node(id).bounds.center)
    private fun touchNode(v:MindMapView,action:Int,id:String,type:Int=MotionEvent.TOOL_TYPE_STYLUS,flags:Int=0) {
        val p=at(v,id);send(v,action,p.x.toFloat(),p.y.toFloat(),type,flags)
    }
    @Test fun explicitConnectionSelectsInkTargetsWithoutWritingDraggingOrEditingTheirBodies()=ui {context->
        for(type in listOf(MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.TOOL_TYPE_FINGER)) {
            val v=connectView(context);val before=v.document;val camera=v.viewport();val requested=mutableListOf<Pair<String,String>>()
            var edits=0;var commits=0
            v.onConnectionTarget={from,to->requested.add(from to to)};v.onChange={commits++};v.onEdit={edits++}
            assertTrue(v.beginConnection("source"));touchNode(v,MotionEvent.ACTION_DOWN,"source",type)
            touchNode(v,MotionEvent.ACTION_MOVE,"target",type)
            assertEquals("target",v.connectionTarget);assertNotNull(v.connectionPreview)
            assertEquals(before,v.document);assertEquals(camera,v.viewport())
            touchNode(v,MotionEvent.ACTION_UP,"target",type)
            assertEquals(listOf("source" to "target"),requested);assertNull(v.connectionFrom)
            assertEquals(before,v.document);assertEquals(0,commits);assertEquals(0,edits);assertFalse(v.inkInput.active)
        }
    }
    @Test fun connectionCancelFlaggedUpSelfAndBlankDoNotProduceDraftsAndCanRetry()=ui {context->
        val v=connectView(context);val before=v.document;var requests=0;v.onConnectionTarget={_,_->requests++}
        assertTrue(v.beginConnection("source"))
        touchNode(v,MotionEvent.ACTION_DOWN,"target");send(v,MotionEvent.ACTION_CANCEL)
        touchNode(v,MotionEvent.ACTION_DOWN,"target");touchNode(v,MotionEvent.ACTION_UP,"target",flags=0x20)
        touchNode(v,MotionEvent.ACTION_DOWN,"source");touchNode(v,MotionEvent.ACTION_UP,"source")
        send(v,MotionEvent.ACTION_DOWN,1f,1f);send(v,MotionEvent.ACTION_UP,1f,1f)
        assertEquals(0,requests);assertEquals("source",v.connectionFrom);assertNull(v.connectionPreview)
        touchNode(v,MotionEvent.ACTION_DOWN,"target");touchNode(v,MotionEvent.ACTION_UP,"target")
        assertEquals(1,requests);assertEquals(before,v.document)
    }
    @Test fun connectionCannotStartInsideAPressOrStrokeAndPenConnectionFreezesCameraAndKeys()=ui {context->
        val v=connectView(context);val before=v.document
        touchNode(v,MotionEvent.ACTION_DOWN,"source",MotionEvent.TOOL_TYPE_FINGER)
        assertFalse(v.beginConnection("target"));touchNode(v,MotionEvent.ACTION_UP,"source",MotionEvent.TOOL_TYPE_FINGER)
        touchNode(v,MotionEvent.ACTION_DOWN,"source");assertFalse(v.beginConnection("target"));send(v,MotionEvent.ACTION_CANCEL)
        var busy=false;v.onInkContact={busy=it}
        assertTrue(v.beginConnection("source"));touchNode(v,MotionEvent.ACTION_DOWN,"target");assertTrue(busy)
        val camera=v.viewport();v.zoomBy(2f);v.fit();v.centerOn("target")
        v.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_DOWN,android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_DPAD_DOWN))
        v.onKeyDown(android.view.KeyEvent.KEYCODE_ENTER,android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_ENTER))
        assertEquals(camera,v.viewport());assertEquals("source",v.connectionFrom)
        v.onKeyDown(android.view.KeyEvent.KEYCODE_ESCAPE,android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_ESCAPE))
        assertNull(v.connectionFrom);assertFalse(busy);touchNode(v,MotionEvent.ACTION_UP,"target")
        assertEquals(before,v.document)
    }
    @Test fun connectionLifecycleCancellationReleasesBusyAndSwallowsOldUp()=ui {context->
        for(cancel in listOf<(MindMapView)->Unit>({it.cancelConnection()},{it.onWindowFocusChanged(false)},
            {it.isEnabled=false},{it.layout(0,0,700,400)},{it.document=it.document.copy(id="another")},
            {it.document=it.document.delete("target").update("source") {n->n.copy(id="other-source")}})) {
            val v=connectView(context);var busy=false;var requests=0
            v.onInkContact={busy=it};v.onConnectionTarget={_,_->requests++};v.onChange={fail("Old stream must not write")}
            val target=at(v,"target")
            assertTrue(v.beginConnection("source"));touchNode(v,MotionEvent.ACTION_DOWN,"target");assertTrue(busy)
            cancel(v);assertFalse(busy);assertNull(v.connectionFrom)
            v.isEnabled=true;send(v,MotionEvent.ACTION_UP,target.x.toFloat(),target.y.toFloat())
            assertEquals(0,requests);assertFalse(v.inkInput.active)
        }
    }

    private fun multi(v:MindMapView,action:Int,types:List<Int>,points:List<com.xnotes.core.geometry.Pt>,oracle:android.view.ScaleGestureDetector?=null) {
        val props=types.mapIndexed {i,type->MotionEvent.PointerProperties().apply {id=if(i==0) 2 else 7;toolType=type}}.toTypedArray()
        val coords=points.map {p->MotionEvent.PointerCoords().apply {x=p.x.toFloat();y=p.y.toFloat();pressure=.7f}}.toTypedArray()
        val e=MotionEvent.obtain(100,time++,action,props.size,props,coords,0,0,1f,1f,888,0,
            if(types.any {it==MotionEvent.TOOL_TYPE_STYLUS}) InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN,0)
        try {oracle?.onTouchEvent(e);v.onTouchEvent(e)} finally {e.recycle()}
    }
    @Test fun connectionPenOwnsItsPointerBeforeAndAfterPalmAndOnlyFinishesWhenItLifts()=ui {context->
        for(palmFirst in listOf(false,true)) {
            val v=connectView(context);val before=v.document;val camera=v.viewport();var busy=false;var requests=0
            val source=at(v,"source");val target=at(v,"target");val palm=com.xnotes.core.geometry.Pt(150.0,150.0)
            v.onInkContact={busy=it};v.onConnectionTarget={from,to->assertFalse(busy);assertEquals("source",from);assertEquals("target",to);requests++}
            assertTrue(v.beginConnection("source"))
            if(palmFirst) multi(v,MotionEvent.ACTION_DOWN,listOf(MotionEvent.TOOL_TYPE_FINGER),listOf(palm))
            else touchNode(v,MotionEvent.ACTION_DOWN,"source")
            val types=listOf(MotionEvent.TOOL_TYPE_FINGER,MotionEvent.TOOL_TYPE_STYLUS)
            multi(v,MotionEvent.ACTION_POINTER_DOWN or (if(palmFirst) 1 shl 8 else 0),types,listOf(palm,source))
            assertTrue(busy)
            multi(v,MotionEvent.ACTION_MOVE,types,listOf(palm,target));assertEquals("target",v.connectionTarget)
            multi(v,MotionEvent.ACTION_POINTER_UP or (1 shl 8),types,listOf(palm,target))
            multi(v,MotionEvent.ACTION_MOVE,listOf(MotionEvent.TOOL_TYPE_FINGER),listOf(target))
            multi(v,MotionEvent.ACTION_UP,listOf(MotionEvent.TOOL_TYPE_FINGER),listOf(target))
            assertEquals(1,requests);assertFalse(busy);assertEquals(camera,v.viewport());assertEquals(before,v.document)
        }
    }
    @Test fun connectionRetriesCannotQuickScaleAndSubsequentTruePinchKeepsItsRatioAndFocus()=ui {context->
        val v=connectView(context);val before=v.document;val camera=v.viewport();var requests=0;var edits=0
        v.onConnectionTarget={_,_->requests++};v.onEdit={edits++};assertTrue(v.beginConnection("source"))
        // Respect Android's double-tap minimum delay; a stream of 1 ms taps would never test quick-scale.
        repeat(2) {
            send(v,MotionEvent.ACTION_DOWN,780f,480f,MotionEvent.TOOL_TYPE_FINGER);time+=40
            send(v,MotionEvent.ACTION_UP,780f,480f,MotionEvent.TOOL_TYPE_FINGER);time+=80
            send(v,MotionEvent.ACTION_DOWN,780f,480f,MotionEvent.TOOL_TYPE_FINGER);time+=50
            send(v,MotionEvent.ACTION_MOVE,780f,380f,MotionEvent.TOOL_TYPE_FINGER);time+=50
            send(v,MotionEvent.ACTION_MOVE,780f,280f,MotionEvent.TOOL_TYPE_FINGER)
            send(v,MotionEvent.ACTION_UP,780f,280f,MotionEvent.TOOL_TYPE_FINGER);time+=400
            touchNode(v,MotionEvent.ACTION_DOWN,"source",MotionEvent.TOOL_TYPE_FINGER)
            touchNode(v,MotionEvent.ACTION_UP,"source",MotionEvent.TOOL_TYPE_FINGER);time+=80
        }
        assertEquals(camera,v.viewport());assertEquals(0,requests);assertEquals(0,edits)
        val oldPoint=at(v,"source");val finger=MotionEvent.TOOL_TYPE_FINGER
        var expected=1f
        val oracle=android.view.ScaleGestureDetector(context,object:android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector:android.view.ScaleGestureDetector):Boolean {expected*=detector.scaleFactor;return true}
        })
        multi(v,MotionEvent.ACTION_DOWN,listOf(finger),listOf(com.xnotes.core.geometry.Pt(100.0,250.0)),oracle)
        multi(v,MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),listOf(finger,finger),listOf(com.xnotes.core.geometry.Pt(100.0,250.0),com.xnotes.core.geometry.Pt(700.0,250.0)),oracle)
        assertNull(v.connectionFrom)
        multi(v,MotionEvent.ACTION_MOVE,listOf(finger,finger),listOf(com.xnotes.core.geometry.Pt(50.0,250.0),com.xnotes.core.geometry.Pt(750.0,250.0)),oracle)
        multi(v,MotionEvent.ACTION_MOVE,listOf(finger,finger),listOf(com.xnotes.core.geometry.Pt(-50.0,250.0),com.xnotes.core.geometry.Pt(850.0,250.0)),oracle)
        val ratio=v.viewport().zoom/camera.zoom
        assertTrue("A genuine pinch must still zoom",ratio>1.1f);assertEquals(expected,ratio,.001f)
        val newPoint=at(v,"source")
        assertEquals(400.0+(oldPoint.x-400.0)*ratio,newPoint.x,.1)
        assertEquals(250.0+(oldPoint.y-250.0)*ratio,newPoint.y,.1)
        multi(v,MotionEvent.ACTION_POINTER_UP or (1 shl 8),listOf(finger,finger),listOf(com.xnotes.core.geometry.Pt(-50.0,250.0),com.xnotes.core.geometry.Pt(850.0,250.0)))
        multi(v,MotionEvent.ACTION_UP,listOf(finger),listOf(com.xnotes.core.geometry.Pt(-50.0,250.0)))
        assertEquals(before,v.document);assertEquals(0,requests)
    }
    @Test fun keyboardConnectionKeepsItsSourceAndDoesNotEnterBodyEditing()=ui {context->
        val v=connectView(context);val before=v.document;var requested:Pair<String,String>?=null;var edits=0
        assertTrue(v.isFocusableInTouchMode)
        v.onConnectionTarget={from,to->requested=from to to};v.onEdit={edits++}
        assertTrue(v.beginConnection("source"));v.selected="target"
        v.onKeyDown(android.view.KeyEvent.KEYCODE_ENTER,android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_ENTER))
        v.onKeyDown(android.view.KeyEvent.KEYCODE_ENTER,android.view.KeyEvent(100,200,android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_ENTER,1))
        assertEquals("source" to "target",requested);assertEquals(0,edits);assertEquals(before,v.document)
        v.onKeyUp(android.view.KeyEvent.KEYCODE_ENTER,android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,android.view.KeyEvent.KEYCODE_ENTER))
    }
    @Test fun connectionCancellationAndSuccessfulSelectionDrainNewPenPointersInTheRemainingStream()=ui {context->
        for(success in listOf(false,true)) {
            val v=connectView(context);val before=v.document;var requests=0;var commits=0
            val finger=MotionEvent.TOOL_TYPE_FINGER;val pen=MotionEvent.TOOL_TYPE_STYLUS
            val palm=com.xnotes.core.geometry.Pt(150.0,150.0);val target=at(v,"target")
            v.onConnectionTarget={_,_->requests++};v.onChange={commits++;v.document=it}
            assertTrue(v.beginConnection("source"))
            multi(v,MotionEvent.ACTION_DOWN,listOf(finger),listOf(palm))
            if(success) {
                multi(v,MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),listOf(finger,pen),listOf(palm,target))
                multi(v,MotionEvent.ACTION_POINTER_UP or (1 shl 8),listOf(finger,pen),listOf(palm,target))
            } else v.cancelConnection()
            // A new pen pointer in the unended palm stream must not fall through to normal ink.
            multi(v,MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),listOf(finger,pen),listOf(palm,target))
            multi(v,MotionEvent.ACTION_MOVE,listOf(finger,pen),listOf(palm,target+com.xnotes.core.geometry.Pt(15.0,15.0)))
            multi(v,MotionEvent.ACTION_POINTER_UP or (1 shl 8),listOf(finger,pen),listOf(palm,target))
            multi(v,MotionEvent.ACTION_UP,listOf(finger),listOf(palm))
            assertEquals(if(success) 1 else 0,requests);assertEquals(0,commits);assertEquals(before,v.document)
            // Fresh DOWN leaves the drain and restores ordinary direct handwriting.
            touchNode(v,MotionEvent.ACTION_DOWN,"target");touchNode(v,MotionEvent.ACTION_UP,"target")
            assertEquals(1,commits)
        }
    }
    @Test fun collapsingTheArmedSourcesAncestorCancelsAndReleasesItsPenContact()=ui {context->
        val v=connectView(context);val parent=MapNode(id="ancestor",parent=null)
        v.document=v.document.copy(nodes=listOf(parent)+v.document.nodes.map {if(it.id=="source") it.copy(parent=parent.id) else it})
        var busy=false;var requests=0;v.onInkContact={busy=it};v.onConnectionTarget={_,_->requests++}
        assertTrue(v.beginConnection("source"));touchNode(v,MotionEvent.ACTION_DOWN,"target");assertTrue(busy)
        v.document=v.document.update(parent.id) {it.copy(collapsed=true)}
        assertFalse(busy);assertNull(v.connectionFrom)
        touchNode(v,MotionEvent.ACTION_UP,"target");assertEquals(0,requests);assertTrue(v.document.node("target").ink.isEmpty())
    }

}
