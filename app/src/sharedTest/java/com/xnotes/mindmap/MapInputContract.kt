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

}
