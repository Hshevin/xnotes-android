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

    private fun relationView(context:Context)=MindMapView(context).apply {
        document=MindMapDocument(nodes=listOf(
            MapNode(id="a",parent=null,x=-300.0,y=-36.0,width=100.0),
            MapNode(id="b",parent=null,free=true,x=200.0,y=-36.0,width=100.0)),
            relations=listOf(MapRelation(id="e",from="a",to="b")))
        onSelect={selected=it};onSelectRelation={selectedRelation=it}
        layout(0,0,1000,600);fit()
    }
    private fun tapRelation(v:MindMapView,dy:Double=0.0) {
        val p=v.screenPoint(com.xnotes.core.geometry.Pt(0.0,0.0))
        send(v,MotionEvent.ACTION_DOWN,p.x.toFloat(),(p.y+dy).toFloat(),MotionEvent.TOOL_TYPE_FINGER)
        send(v,MotionEvent.ACTION_UP,p.x.toFloat(),(p.y+dy).toFloat(),MotionEvent.TOOL_TYPE_FINGER)
    }
    @Test fun ordinaryRelationTapUsesWideScreenTargetAndEnterEditsTheExistingIdWithoutCommitting()=ui {context->
        val v=relationView(context);var commits=0;var edit:String?=null
        v.onChange={commits++};v.onEditRelation={edit=it}
        v.selected="a"
        tapRelation(v,12.0*context.resources.displayMetrics.density)
        assertEquals("e",v.selectedRelation);assertNull(v.selected);assertEquals(0,commits)
        v.onKeyDown(android.view.KeyEvent.KEYCODE_ENTER,android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_ENTER))
        assertEquals("e",edit);assertEquals(0,commits)
        val node=v.screenPoint(v.document.node("a").bounds.center)
        send(v,MotionEvent.ACTION_DOWN,node.x.toFloat(),node.y.toFloat(),MotionEvent.TOOL_TYPE_FINGER)
        send(v,MotionEvent.ACTION_UP,node.x.toFloat(),node.y.toFloat(),MotionEvent.TOOL_TYPE_FINGER)
        assertEquals("a",v.selected);assertNull(v.selectedRelation)
    }
    @Test fun nodeOverARelationWinsAndInkStillOwnsItsBody()=ui {context->
        val v=relationView(context)
        val ink=MapNode(id="ink",parent=null,free=true,kind=NodeKind.INK,x=-60.0,y=-50.0,width=120.0,height=100.0)
        v.document=v.document.copy(nodes=v.document.nodes+ink);v.fit()
        val p=v.screenPoint(ink.bounds.center);var commits=0
        v.onChange={commits++;v.document=it}
        send(v,MotionEvent.ACTION_DOWN,p.x.toFloat(),p.y.toFloat())
        assertTrue(v.inkInput.active);assertNull(v.selectedRelation)
        send(v,MotionEvent.ACTION_UP,(p.x+15).toFloat(),(p.y+15).toFloat())
        assertEquals(1,commits);assertEquals("ink",v.selected);assertNull(v.selectedRelation)
        val before=v.document
        send(v,MotionEvent.ACTION_DOWN,p.x.toFloat(),p.y.toFloat(),MotionEvent.TOOL_TYPE_FINGER)
        send(v,MotionEvent.ACTION_MOVE,(p.x+50).toFloat(),(p.y+30).toFloat(),MotionEvent.TOOL_TYPE_FINGER)
        send(v,MotionEvent.ACTION_UP,(p.x+50).toFloat(),(p.y+30).toFloat(),MotionEvent.TOOL_TYPE_FINGER)
        assertNotEquals(before,v.document);assertSame(before.node("ink").ink,v.document.node("ink").ink);assertNull(v.selectedRelation)
    }
    @Test fun cancelledOrFarReleaseNeverSelectsThePressedRelation()=ui {context->
        for(case in 0..2) {
            val v=relationView(context);val p=v.screenPoint(com.xnotes.core.geometry.Pt(0.0,0.0));var commits=0
            v.onChange={commits++}
            send(v,MotionEvent.ACTION_DOWN,p.x.toFloat(),p.y.toFloat(),MotionEvent.TOOL_TYPE_FINGER)
            when(case) {
                0->send(v,MotionEvent.ACTION_CANCEL,p.x.toFloat(),p.y.toFloat(),MotionEvent.TOOL_TYPE_FINGER)
                1->send(v,MotionEvent.ACTION_UP,p.x.toFloat(),p.y.toFloat(),MotionEvent.TOOL_TYPE_FINGER,0x20)
                else->send(v,MotionEvent.ACTION_UP,(p.x+100).toFloat(),p.y.toFloat(),MotionEvent.TOOL_TYPE_FINGER)
            }
            assertNull(v.selectedRelation);assertEquals(0,commits)
        }
    }
    @Test fun relationPressThatBecomesAPanOrPinchDoesNotSelectOrEditTheGraph()=ui {context->
        val v=relationView(context);val p=v.screenPoint(com.xnotes.core.geometry.Pt(0.0,0.0));val before=v.viewport();var commits=0
        v.onChange={commits++}
        send(v,MotionEvent.ACTION_DOWN,p.x.toFloat(),p.y.toFloat(),MotionEvent.TOOL_TYPE_FINGER)
        send(v,MotionEvent.ACTION_MOVE,(p.x+70).toFloat(),(p.y+40).toFloat(),MotionEvent.TOOL_TYPE_FINGER)
        send(v,MotionEvent.ACTION_UP,(p.x+70).toFloat(),(p.y+40).toFloat(),MotionEvent.TOOL_TYPE_FINGER)
        assertNull(v.selectedRelation);assertNotEquals(before,v.viewport());assertEquals(0,commits)
        val mid=v.screenPoint(com.xnotes.core.geometry.Pt(0.0,0.0))
        multi(v,MotionEvent.ACTION_DOWN,listOf(MotionEvent.TOOL_TYPE_FINGER),listOf(mid))
        multi(v,MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            listOf(MotionEvent.TOOL_TYPE_FINGER,MotionEvent.TOOL_TYPE_FINGER),listOf(mid,mid+com.xnotes.core.geometry.Pt(100.0,0.0)))
        multi(v,MotionEvent.ACTION_UP,listOf(MotionEvent.TOOL_TYPE_FINGER),listOf(mid))
        assertNull(v.selectedRelation);assertEquals(0,commits)
    }
    @Test fun relationPendingTapIsInvalidatedByDocumentOrHostOrCameraChanges()=ui {context->
        for(case in 0..4) {
            val v=relationView(context);val p=v.screenPoint(com.xnotes.core.geometry.Pt(0.0,0.0))
            send(v,MotionEvent.ACTION_DOWN,p.x.toFloat(),p.y.toFloat(),MotionEvent.TOOL_TYPE_FINGER)
            when(case) {
                0->v.document=v.document.copy(id="replacement")
                1->{v.isEnabled=false;v.isEnabled=true}
                2->v.onWindowFocusChanged(false)
                3->v.layout(0,0,1100,600)
                else->v.zoomBy(1.2f)
            }
            send(v,MotionEvent.ACTION_UP,p.x.toFloat(),p.y.toFloat(),MotionEvent.TOOL_TYPE_FINGER)
            assertNull(v.selectedRelation)
        }
    }
    @Test fun relationSelectionDoesNotStealAnExplicitConnectionTargetTap()=ui {context->
        val v=relationView(context);var target:String?=null
        v.onConnectionTarget={_,to->target=to}
        assertTrue(v.beginConnection("a"))
        val to=v.screenPoint(v.document.node("b").bounds.center)
        send(v,MotionEvent.ACTION_DOWN,to.x.toFloat(),to.y.toFloat(),MotionEvent.TOOL_TYPE_FINGER)
        send(v,MotionEvent.ACTION_UP,to.x.toFloat(),to.y.toFloat(),MotionEvent.TOOL_TYPE_FINGER)
        assertEquals("b",target);assertNull(v.selectedRelation)
    }
    @Test fun selectedRelationRetiresOnNewDocumentsAndPendingTapsRespectTheSessionEpoch()=ui {context->
        val v=relationView(context)
        tapRelation(v);assertEquals("e",v.selectedRelation)
        v.document=v.document.copy(id="another-document")
        assertNull(v.selectedRelation)
        val p=v.screenPoint(com.xnotes.core.geometry.Pt(0.0,0.0))
        send(v,MotionEvent.ACTION_DOWN,p.x.toFloat(),p.y.toFloat(),MotionEvent.TOOL_TYPE_FINGER)
        v.documentEpoch++
        send(v,MotionEvent.ACTION_UP,p.x.toFloat(),p.y.toFloat(),MotionEvent.TOOL_TYPE_FINGER)
        assertNull(v.selectedRelation)
    }
    private fun relationPointers(v:MindMapView,action:Int,ids:List<Int>,types:List<Int>,points:List<com.xnotes.core.geometry.Pt>) {
        val props=types.mapIndexed {i,type->MotionEvent.PointerProperties().apply {id=ids[i];toolType=type}}.toTypedArray()
        val coords=points.map {p->MotionEvent.PointerCoords().apply {x=p.x.toFloat();y=p.y.toFloat();pressure=.7f}}.toTypedArray()
        val e=MotionEvent.obtain(100,time++,action,props.size,props,coords,0,0,1f,1f,888,0,InputDevice.SOURCE_STYLUS,0)
        try {v.onTouchEvent(e)} finally {e.recycle()}
    }
    @Test fun penRelationTapSurvivesAnIncidentalPalmAndOnlyThePenUpSelects()=ui {context->
        for(pen in listOf(MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.TOOL_TYPE_ERASER)) {
            val v=relationView(context);val p=v.screenPoint(com.xnotes.core.geometry.Pt(0.0,0.0))
            val palm=com.xnotes.core.geometry.Pt(30.0,40.0);val camera=v.viewport();val bytes=MapCodec.encode(v.document)
            var selected=0;var commits=0
            v.onSelectRelation={selected++;v.selectedRelation=it};v.onChange={commits++}
            relationPointers(v,MotionEvent.ACTION_DOWN,listOf(7),listOf(pen),listOf(p))
            relationPointers(v,MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),listOf(7,2),listOf(pen,MotionEvent.TOOL_TYPE_FINGER),listOf(p,palm))
            assertEquals(0,selected)
            relationPointers(v,MotionEvent.ACTION_POINTER_UP,listOf(7,2),listOf(pen,MotionEvent.TOOL_TYPE_FINGER),listOf(p,palm))
            assertEquals("e",v.selectedRelation);assertEquals(1,selected);assertNull(v.selected)
            relationPointers(v,MotionEvent.ACTION_MOVE,listOf(2),listOf(MotionEvent.TOOL_TYPE_FINGER),listOf(palm+com.xnotes.core.geometry.Pt(50.0,20.0)))
            // A new independent pen press is allowed even while the same palm remains down.
            relationPointers(v,MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),listOf(2,7),listOf(MotionEvent.TOOL_TYPE_FINGER,pen),listOf(palm,p))
            assertEquals(1,selected)
            relationPointers(v,MotionEvent.ACTION_POINTER_UP or (1 shl 8),listOf(2,7),listOf(MotionEvent.TOOL_TYPE_FINGER,pen),listOf(palm,p))
            assertEquals("e",v.selectedRelation);assertEquals(2,selected)
            relationPointers(v,MotionEvent.ACTION_UP,listOf(2),listOf(MotionEvent.TOOL_TYPE_FINGER),listOf(palm))
            assertEquals("e",v.selectedRelation);assertEquals(2,selected);assertEquals(0,commits)
            assertEquals(camera,v.viewport());assertArrayEquals(bytes,MapCodec.encode(v.document))
        }
    }
    @Test fun penTakingOverAPalmRehitsItsOwnRelationInsteadOfThePalmsCandidate()=ui {context->
        val v=relationView(context)
        v.document=v.document.copy(nodes=v.document.nodes+listOf(
            MapNode(id="c",parent=null,free=true,x=-300.0,y=164.0,width=100.0),
            MapNode(id="d",parent=null,free=true,x=200.0,y=164.0,width=100.0)),
            relations=v.document.relations+MapRelation(id="other",from="c",to="d"))
        v.fit()
        val palm=v.screenPoint(com.xnotes.core.geometry.Pt(0.0,0.0));val pen=v.screenPoint(com.xnotes.core.geometry.Pt(0.0,200.0))
        val camera=v.viewport();val bytes=MapCodec.encode(v.document);var selected=0;var commits=0
        v.onSelectRelation={selected++;v.selectedRelation=it};v.onChange={commits++}
        relationPointers(v,MotionEvent.ACTION_DOWN,listOf(2),listOf(MotionEvent.TOOL_TYPE_FINGER),listOf(palm))
        relationPointers(v,MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),listOf(2,7),listOf(MotionEvent.TOOL_TYPE_FINGER,MotionEvent.TOOL_TYPE_STYLUS),listOf(palm,pen))
        assertEquals(0,selected)
        relationPointers(v,MotionEvent.ACTION_POINTER_UP or (1 shl 8),listOf(2,7),listOf(MotionEvent.TOOL_TYPE_FINGER,MotionEvent.TOOL_TYPE_STYLUS),listOf(palm,pen))
        assertEquals("other",v.selectedRelation);assertEquals(1,selected);assertNull(v.selected)
        relationPointers(v,MotionEvent.ACTION_UP,listOf(2),listOf(MotionEvent.TOOL_TYPE_FINGER),listOf(palm))
        assertEquals("other",v.selectedRelation);assertEquals(1,selected);assertEquals(0,commits)
        assertEquals(camera,v.viewport());assertArrayEquals(bytes,MapCodec.encode(v.document))
    }
    @Test fun documentUpdatesRetireAnOldNodeDragInsteadOfOverwritingNewContentOnRelease()=ui {context->
        val v=relationView(context);val p=v.screenPoint(v.document.node("a").bounds.center);var commits=0
        v.onChange={commits++;v.document=it}
        send(v,MotionEvent.ACTION_DOWN,p.x.toFloat(),p.y.toFloat(),MotionEvent.TOOL_TYPE_FINGER)
        send(v,MotionEvent.ACTION_MOVE,(p.x+80).toFloat(),(p.y+40).toFloat(),MotionEvent.TOOL_TYPE_FINGER)
        v.document=v.document.update("a"){it.copy(text="newer asynchronous content")}
        val latest=v.document
        send(v,MotionEvent.ACTION_UP,(p.x+80).toFloat(),(p.y+40).toFloat(),MotionEvent.TOOL_TYPE_FINGER)
        assertEquals(latest,v.document);assertEquals(0,commits)
    }
    private fun retiredNodeDrag(context:Context,retire:(MindMapView)->Unit) {
        val v=relationView(context);val original=v.document;val p=v.screenPoint(v.document.node("a").bounds.center);var commits=0
        v.onChange={commits++;v.document=it}
        send(v,MotionEvent.ACTION_DOWN,p.x.toFloat(),p.y.toFloat(),MotionEvent.TOOL_TYPE_FINGER)
        send(v,MotionEvent.ACTION_MOVE,(p.x+80).toFloat(),(p.y+40).toFloat(),MotionEvent.TOOL_TYPE_FINGER)
        retire(v)
        send(v,MotionEvent.ACTION_UP,(p.x+80).toFloat(),(p.y+40).toFloat(),MotionEvent.TOOL_TYPE_FINGER)
        assertEquals(original,v.document);assertEquals(0,commits)
    }
    @Test fun equalDocumentReloadRetiresTheCapturedNodeDragByItsSessionEpoch()=ui {context->
        retiredNodeDrag(context) {v->v.documentEpoch++;v.document=v.document.copy()}
    }
    @Test fun disablingRetiresTheCapturedNodeDragBeforeReenabling()=ui {context->
        retiredNodeDrag(context) {v->v.isEnabled=false;v.isEnabled=true}
    }
    @Test fun aSessionEpochRetiresCompletedTapTimingButNewDoubleTapsStillEditNodes()=ui {context->
        val v=relationView(context);val p=v.screenPoint(v.document.node("a").bounds.center);var edits=0
        v.onEdit={edits++}
        fun tap() {
            send(v,MotionEvent.ACTION_DOWN,p.x.toFloat(),p.y.toFloat(),MotionEvent.TOOL_TYPE_FINGER);time+=40
            send(v,MotionEvent.ACTION_UP,p.x.toFloat(),p.y.toFloat(),MotionEvent.TOOL_TYPE_FINGER)
        }
        tap();v.documentEpoch++;v.document=v.document.copy();time+=80;tap()
        assertEquals("A tap from the retired session must not become the new session's double tap",0,edits)
        time+=400;tap();time+=80;tap();assertEquals(1,edits)
    }

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
    @Test fun fingerOnInkBodyMovesNodeOnceAndPreservesInk() = ui { context ->
        val doc=MindMapDocument(nodes=listOf(MapNode(id="ink",parent=null,kind=NodeKind.INK)))
        val v=MindMapView(context).apply {document=doc;layout(0,0,500,400);fit()}
        var commits=0;v.onChange={commits++;v.document=it}
        val scale=v.contentScale()
        send(v,MotionEvent.ACTION_DOWN,250f,200f,type=MotionEvent.TOOL_TYPE_FINGER)
        send(v,MotionEvent.ACTION_MOVE,300f,230f,type=MotionEvent.TOOL_TYPE_FINGER)
        send(v,MotionEvent.ACTION_UP,300f,230f,type=MotionEvent.TOOL_TYPE_FINGER)
        assertEquals(1,commits)
        assertEquals(doc.root.x+50/scale,v.document.root.x,.001)
        assertEquals(doc.root.y+30/scale,v.document.root.y,.001)
        assertSame(doc.root.ink,v.document.root.ink)
    }
    @Test fun fingerInkDragUsesReleasePositionAndCancellationNeverWrites() = ui { context ->
        for(cancel in listOf(false,true)) {
            val doc=MindMapDocument(nodes=listOf(MapNode(id="ink",parent=null,kind=NodeKind.INK)))
            val v=MindMapView(context).apply {document=doc;layout(0,0,500,400);fit()}
            val scale=v.contentScale();var commits=0
            v.onChange={commits++;v.document=it}
            send(v,MotionEvent.ACTION_DOWN,250f,200f,type=MotionEvent.TOOL_TYPE_FINGER)
            send(v,MotionEvent.ACTION_MOVE,280f,220f,type=MotionEvent.TOOL_TYPE_FINGER)
            assertEquals(doc,v.document)
            send(v,if(cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP,320f,250f,type=MotionEvent.TOOL_TYPE_FINGER)
            assertEquals(if(cancel) 0 else 1,commits)
            assertEquals(doc.root.x+(if(cancel) 0.0 else 70/scale),v.document.root.x,.001)
            assertSame(doc.root.ink,v.document.root.ink)
        }
    }
    @Test fun draggingSourceFooterDoesNotOpenAPanel() = ui { context ->
        val node=MapNode(id="root",parent=null,sources=listOf(MapSource(documentId="00000000-0000-0000-0000-000000000001")))
        val v=MindMapView(context).apply {document=MindMapDocument(nodes=listOf(node));layout(0,0,500,400);fit()}
        val p=v.screenPoint(com.xnotes.core.geometry.Pt(node.x+node.width/2,node.y+node.height-14))
        var opened=0;var commits=0
        v.onSources={opened++};v.onChange={commits++;v.document=it}
        send(v,MotionEvent.ACTION_DOWN,p.x.toFloat(),p.y.toFloat(),MotionEvent.TOOL_TYPE_FINGER)
        assertEquals(0,opened)
        send(v,MotionEvent.ACTION_MOVE,(p.x+50).toFloat(),p.y.toFloat(),MotionEvent.TOOL_TYPE_FINGER)
        send(v,MotionEvent.ACTION_UP,(p.x+65).toFloat(),p.y.toFloat(),MotionEvent.TOOL_TYPE_FINGER)
        assertEquals(0,opened);assertEquals(1,commits)
    }
    @Test fun lineToolKeepsOnlyActualEndpointsAndFreezesWidthUntilRelease() = ui { context ->
        val v=pad(context);v.mode=InkMode.LINE;v.penWidth=5.0
        send(v,MotionEvent.ACTION_DOWN,20f,20f)
        send(v,MotionEvent.ACTION_MOVE,40f,80f)
        v.penWidth=8.0
        send(v,MotionEvent.ACTION_UP,100f,40f)
        val stroke=v.ink.single()
        assertEquals(2,stroke.points.size);assertEquals(5.0,stroke.width,.001)
        assertEquals(20.0,stroke.points.first().x,.001);assertEquals(40.0,stroke.points.last().y,.001)
        assertEquals(.7,stroke.points.first().pressure,.001)
        val before=v.ink
        send(v,MotionEvent.ACTION_DOWN,30f,30f);send(v,MotionEvent.ACTION_MOVE,70f,70f)
        send(v,MotionEvent.ACTION_CANCEL)
        assertSame(before,v.ink)
    }
    @Test fun rectangleSelectionMovesOnlySelectedInkAndCancelsWithoutHistory() = ui { context ->
        for(reverse in listOf(false,true)) {
            val v=pad(context)
            val first=MapInk(listOf(InkPoint(20.0,20.0),InkPoint(40.0,40.0)))
            val other=MapInk(listOf(InkPoint(120.0,120.0),InkPoint(140.0,140.0)))
            v.ink=listOf(first,other);v.mode=InkMode.RECT_SELECT
            var commits=0;v.onCommit={commits++;v.ink=it}
            send(v,MotionEvent.ACTION_DOWN,if(reverse) 60f else 10f,if(reverse) 60f else 10f)
            send(v,MotionEvent.ACTION_UP,if(reverse) 10f else 60f,if(reverse) 10f else 60f)
            assertEquals(0,commits)
            send(v,MotionEvent.ACTION_DOWN,30f,30f);send(v,MotionEvent.ACTION_MOVE,80f,80f)
            send(v,MotionEvent.ACTION_CANCEL)
            assertEquals(listOf(first,other),v.ink);assertEquals(0,commits)
            send(v,MotionEvent.ACTION_DOWN,30f,30f);send(v,MotionEvent.ACTION_UP,80f,80f)
            assertEquals(1,commits);assertEquals(70.0,v.ink.first().points.first().x,.001)
            assertSame(other,v.ink.last())
        }
    }
    @Test fun rectangleCanSelectTheMiddleOfASparseStraightLine() = ui { context ->
        val v=pad(context);v.mode=InkMode.RECT_SELECT
        v.ink=listOf(MapInk(listOf(InkPoint(10.0,50.0),InkPoint(150.0,50.0))))
        send(v,MotionEvent.ACTION_DOWN,60f,40f);send(v,MotionEvent.ACTION_UP,90f,60f)
        send(v,MotionEvent.ACTION_DOWN,70f,50f);send(v,MotionEvent.ACTION_UP,80f,70f)
        assertEquals(20.0,v.ink.single().points.first().x,.001)
        assertEquals(70.0,v.ink.single().points.first().y,.001)
    }
    @Test fun lineCanUseTheLastTwoPointsOfTheInkBudget() = ui { context ->
        val v=pad(context);v.mode=InkMode.LINE
        val existing=MapInk(List(MindMapDocument.MAX_POINTS-2) {InkPoint(1.0,1.0)})
        v.ink=listOf(existing);var limits=0;v.onLimit={limits++}
        send(v,MotionEvent.ACTION_DOWN,20f,20f);send(v,MotionEvent.ACTION_MOVE,40f,80f)
        send(v,MotionEvent.ACTION_UP,100f,40f)
        assertEquals(0,limits);assertEquals(2,v.ink.size);assertSame(existing,v.ink.first())
        assertEquals(2,v.ink.last().points.size)
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
    private fun portView(context:Context)=connectView(context).apply {
        val density=resources.displayMetrics.density
        layout(0,0,(800*density).toInt(),(500*density).toInt());fit()
    }
    private fun rightPort(v:MindMapView)=requireNotNull(v.connectionPort()).center
    @Test fun blankDropReleasesPenBeforeItsCallbackAndKeepsTheDocumentUnchanged()=ui {context->
        val v=portView(context);val before=v.document;val camera=v.viewport();val port=rightPort(v)
        val blank=com.xnotes.core.geometry.Pt(v.width/2.0,v.height-10.0*context.resources.displayMetrics.density)
        val expected=com.xnotes.core.geometry.Pt((blank.x-camera.panX)/v.contentScale(),(blank.y-camera.panY)/v.contentScale())
        var busy=false;val drops=mutableListOf<Pair<String,com.xnotes.core.geometry.Pt>>()
        v.onInkContact={busy=it};v.onConnectionBlank={from,p->assertFalse(busy);assertNull(v.connectionFrom);drops+=from to p}
        send(v,MotionEvent.ACTION_DOWN,port.x.toFloat(),port.y.toFloat());assertTrue(busy)
        send(v,MotionEvent.ACTION_MOVE,blank.x.toFloat(),blank.y.toFloat());send(v,MotionEvent.ACTION_UP,blank.x.toFloat(),blank.y.toFloat())
        assertEquals(1,drops.size);assertEquals("source",drops.single().first)
        assertEquals(expected.x,drops.single().second.x,.001);assertEquals(expected.y,drops.single().second.y,.001)
        assertEquals(before,v.document);assertEquals(camera,v.viewport());assertFalse(v.inkInput.active)
    }
    @Test fun microPortTapAndAnyNodeHitAreNeverBlankButAFollowingBlankTapCanCreate()=ui {context->
        val v=portView(context);val port=rightPort(v);var blanks=0;var targets=0
        v.onConnectionBlank={_,_->blanks++};v.onConnectionTarget={_,_->targets++}
        send(v,MotionEvent.ACTION_DOWN,port.x.toFloat(),port.y.toFloat());send(v,MotionEvent.ACTION_UP,port.x.toFloat()+1,port.y.toFloat()+1)
        assertEquals(0,blanks);assertEquals("source",v.connectionFrom)
        touchNode(v,MotionEvent.ACTION_DOWN,"source");touchNode(v,MotionEvent.ACTION_UP,"source")
        assertEquals(0,blanks);assertEquals(0,targets)
        touchNode(v,MotionEvent.ACTION_DOWN,"target");touchNode(v,MotionEvent.ACTION_UP,"target")
        assertEquals(0,blanks);assertEquals(1,targets)
        assertTrue(v.beginConnection("source"))
        val x=v.width/2f;val y=v.height-10f*context.resources.displayMetrics.density
        send(v,MotionEvent.ACTION_DOWN,x,y);send(v,MotionEvent.ACTION_UP,x,y)
        assertEquals(1,blanks);assertNull(v.connectionFrom)
    }
    @Test fun cancelledBlankDropAndResidualPalmCannotRequestAnyNewNode()=ui {context->
        for(success in listOf(false,true)) {
            val v=portView(context);val before=v.document;val port=rightPort(v)
            val blank=com.xnotes.core.geometry.Pt(v.width/2.0,v.height-10.0*context.resources.displayMetrics.density)
            val types=listOf(MotionEvent.TOOL_TYPE_FINGER,MotionEvent.TOOL_TYPE_STYLUS);var blanks=0;var busy=false
            v.onInkContact={busy=it};v.onConnectionBlank={_,_->blanks++}
            multi(v,MotionEvent.ACTION_DOWN,listOf(types[0]),listOf(port))
            multi(v,MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),types,listOf(port,port))
            multi(v,MotionEvent.ACTION_MOVE,types,listOf(port,blank))
            multi(v,MotionEvent.ACTION_POINTER_UP or (1 shl 8),types,listOf(port,blank),flags=if(success) 0 else 0x20)
            assertFalse(busy);assertEquals(if(success) 1 else 0,blanks)
            multi(v,MotionEvent.ACTION_MOVE,listOf(types[0]),listOf(blank));multi(v,MotionEvent.ACTION_UP,listOf(types[0]),listOf(blank))
            assertEquals(if(success) 1 else 0,blanks);assertEquals(before,v.document)
            v.cancelConnection();assertTrue(v.beginConnection("source"))
            send(v,MotionEvent.ACTION_DOWN,blank.x.toFloat(),blank.y.toFloat());send(v,MotionEvent.ACTION_CANCEL)
            assertEquals(if(success) 1 else 0,blanks)
        }
    }
    @Test fun releasingOutsideTheGraphNeverRequestsANewNode()=ui {context->
        val v=portView(context);val before=v.document;var blanks=0;v.onConnectionBlank={_,_->blanks++}
        for(point in listOf(com.xnotes.core.geometry.Pt(-1.0,10.0),com.xnotes.core.geometry.Pt(10.0,-1.0),
            com.xnotes.core.geometry.Pt(v.width.toDouble(),10.0),com.xnotes.core.geometry.Pt(10.0,v.height.toDouble()))) {
            val port=rightPort(v);send(v,MotionEvent.ACTION_DOWN,port.x.toFloat(),port.y.toFloat())
            send(v,MotionEvent.ACTION_MOVE,point.x.toFloat(),point.y.toFloat());send(v,MotionEvent.ACTION_UP,point.x.toFloat(),point.y.toFloat())
            assertEquals(0,blanks);assertEquals(before,v.document);v.cancelConnection()
        }
    }
    @Test fun draggingTheSelectedNodePortRequestsOneRelationWithoutEditingInkOrMovingTheGraph()=ui {context->
        for(type in listOf(MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.TOOL_TYPE_FINGER)) {
            val v=portView(context);val before=v.document;val camera=v.viewport();val port=rightPort(v)
            val requested=mutableListOf<Pair<String,String>>();var commits=0;var busy=false
            v.onConnectionTarget={from,to->requested+=from to to};v.onChange={commits++};v.onInkContact={busy=it}
            send(v,MotionEvent.ACTION_DOWN,port.x.toFloat(),port.y.toFloat(),type)
            assertEquals("source",v.connectionFrom);assertEquals(type==MotionEvent.TOOL_TYPE_STYLUS,busy)
            touchNode(v,MotionEvent.ACTION_MOVE,"target",type);assertEquals("target",v.connectionTarget)
            touchNode(v,MotionEvent.ACTION_UP,"target",type)
            assertEquals(listOf("source" to "target"),requested);assertFalse(busy)
            assertEquals(before,v.document);assertEquals(camera,v.viewport());assertEquals(0,commits);assertFalse(v.inkInput.active)
        }
    }
    @Test fun tappingAPortArmsClickConnectionAndCancellingAPortDragCreatesNothing()=ui {context->
        val v=portView(context);val port=rightPort(v);var requests=0;v.onConnectionTarget={_,_->requests++}
        v.onChange={v.document=it}
        send(v,MotionEvent.ACTION_DOWN,port.x.toFloat(),port.y.toFloat())
        send(v,MotionEvent.ACTION_UP,port.x.toFloat(),port.y.toFloat())
        assertEquals("source",v.connectionFrom);assertEquals(0,requests)
        touchNode(v,MotionEvent.ACTION_DOWN,"target");touchNode(v,MotionEvent.ACTION_UP,"target")
        assertEquals(1,requests)
        send(v,MotionEvent.ACTION_DOWN,port.x.toFloat(),port.y.toFloat());touchNode(v,MotionEvent.ACTION_MOVE,"target")
        send(v,MotionEvent.ACTION_CANCEL)
        assertEquals(1,requests);v.cancelConnection()
        touchNode(v,MotionEvent.ACTION_DOWN,"source");touchNode(v,MotionEvent.ACTION_UP,"source")
        assertEquals(1,v.document.node("source").ink.size)
    }
    @Test fun portTargetsKeep48DpAtDifferentZoomsAndYieldToOtherNodesInk()=ui {context->
        for(factor in listOf(.1f,1f,4f)) {
            val v=portView(context);v.zoomBy(factor)
            val port=requireNotNull(v.connectionPort());val size=48.0*v.resources.displayMetrics.density
            assertEquals(size,port.w,.001);assertEquals(size,port.h,.001)
            assertTrue(port.left>=0 && port.top>=0 && port.right<=v.width && port.bottom<=v.height)
        }
        val v=portView(context);v.centerOn("source")
        val original=requireNotNull(v.connectionPort());val camera=v.viewport();val scale=v.contentScale()
        val block=MapNode(id="blocking",parent=null,free=true,kind=NodeKind.INK,
            x=(original.x-camera.panX)/scale,y=(original.y-camera.panY)/scale)
        v.document=v.document.copy(nodes=v.document.nodes+block);v.onChange={v.document=it}
        val moved=requireNotNull(v.connectionPort());assertTrue(moved.right<=v.screenPoint(v.document.node("source").bounds.topLeft).x)
        send(v,MotionEvent.ACTION_DOWN,original.centerX.toFloat(),original.centerY.toFloat())
        send(v,MotionEvent.ACTION_UP,original.centerX.toFloat(),original.centerY.toFloat())
        assertNull(v.connectionFrom);assertEquals(1,v.document.node("blocking").ink.size);assertTrue(v.document.node("source").ink.isEmpty())
        v.selected="source"
        v.document=v.document.update("source") {it.copy(x=-camera.panX/scale,y=-camera.panY/scale,width=v.width/scale,height=v.height/scale)}
        assertNull(v.connectionPort())
    }
    @Test fun portMicroMotionOnlyArmsEvenIfATargetMovesUnderItsRelease()=ui {context->
        val v=portView(context);val port=requireNotNull(v.connectionPort());var requests=0
        v.onConnectionTarget={_,_->requests++}
        send(v,MotionEvent.ACTION_DOWN,port.centerX.toFloat(),port.centerY.toFloat())
        val camera=v.viewport();val scale=v.contentScale()
        v.document=v.document.update("target") {it.copy(x=(port.centerX-camera.panX)/scale-20,y=(port.centerY-camera.panY)/scale-20)}
        send(v,MotionEvent.ACTION_UP,port.centerX.toFloat()+1,port.centerY.toFloat()+1)
        assertEquals(0,requests);assertEquals("source",v.connectionFrom)
        touchNode(v,MotionEvent.ACTION_DOWN,"target");touchNode(v,MotionEvent.ACTION_UP,"target")
        assertEquals(1,requests)
    }
    @Test fun portsTryEachFreeSideThenHideWhenEveryCompleteTargetIsBlocked()=ui {context->
        val v=portView(context);v.centerOn("source");v.document=v.document.copy(nodes=listOf(v.document.node("source")));v.zoomBy(.5f)
        val source=v.document.node("source");val a=v.screenPoint(source.bounds.topLeft);val b=v.screenPoint(com.xnotes.core.geometry.Pt(source.bounds.right,source.bounds.bottom))
        val camera=v.viewport();val scale=v.contentScale()
        repeat(4) {index->
            val port=requireNotNull(v.connectionPort())
            when(index) {
                0->assertTrue(port.left>=b.x)
                1->assertTrue(port.right<=a.x)
                2->assertTrue(port.top>=b.y)
                3->assertTrue(port.bottom<=a.y)
            }
            val blocker=MapNode(id="block$index",parent=null,free=true,x=(port.x-camera.panX)/scale,y=(port.y-camera.panY)/scale,
                width=port.w/scale,height=port.h/scale)
            v.document=v.document.copy(nodes=v.document.nodes+blocker)
        }
        assertNull(v.connectionPort());assertTrue(v.beginConnection("source")) // Toolbar remains available.
    }
    @Test fun portDragKeepsPenOwnershipOverPalmAndHandsTwoFingersToTheViewport()=ui {context->
        for(pen in listOf(false,true)) {
            val v=portView(context);val before=v.document;var requests=0;var busy=false
            v.onConnectionTarget={_,_->requests++};v.onInkContact={busy=it}
            val port=requireNotNull(v.connectionPort()).center;val target=at(v,"target");val palm=com.xnotes.core.geometry.Pt(100.0,100.0)
            val type=if(pen) MotionEvent.TOOL_TYPE_STYLUS else MotionEvent.TOOL_TYPE_FINGER
            multi(v,MotionEvent.ACTION_DOWN,listOf(type),listOf(port))
            multi(v,MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),listOf(type,MotionEvent.TOOL_TYPE_FINGER),listOf(port,palm))
            if(pen) {
                assertEquals("source",v.connectionFrom);assertTrue(busy)
                multi(v,MotionEvent.ACTION_MOVE,listOf(type,MotionEvent.TOOL_TYPE_FINGER),listOf(target,palm))
                multi(v,MotionEvent.ACTION_POINTER_UP or (1 shl 8),listOf(type,MotionEvent.TOOL_TYPE_FINGER),listOf(target,palm))
                assertEquals(0,requests);multi(v,MotionEvent.ACTION_UP,listOf(type),listOf(target))
                assertEquals(1,requests);assertFalse(busy)
            } else {
                assertNull(v.connectionFrom);assertFalse(busy)
                multi(v,MotionEvent.ACTION_POINTER_UP or (1 shl 8),listOf(type,type),listOf(port,palm));multi(v,MotionEvent.ACTION_UP,listOf(type),listOf(port))
                assertEquals(0,requests)
            }
            assertEquals(before,v.document)
        }
    }
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

    private fun multi(v:MindMapView,action:Int,types:List<Int>,points:List<com.xnotes.core.geometry.Pt>,oracle:android.view.ScaleGestureDetector?=null,flags:Int=0) {
        val props=types.mapIndexed {i,type->MotionEvent.PointerProperties().apply {id=if(i==0) 2 else 7;toolType=type}}.toTypedArray()
        val coords=points.map {p->MotionEvent.PointerCoords().apply {x=p.x.toFloat();y=p.y.toFloat();pressure=.7f}}.toTypedArray()
        val e=MotionEvent.obtain(100,time++,action,props.size,props,coords,0,0,1f,1f,888,0,
            if(types.any {it==MotionEvent.TOOL_TYPE_STYLUS}) InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN,flags)
        try {oracle?.onTouchEvent(e);v.onTouchEvent(e)} finally {e.recycle()}
    }
    @Test fun penTakingOverAFingerPortDragOwnsItsUpAndDrainsRemainingFingerEvents()=ui {context->
        for(success in listOf(false,true)) {
            val v=portView(context);val before=v.document;val port=requireNotNull(v.connectionPort()).center
            val target=at(v,"target");val source=at(v,"source");val types=listOf(MotionEvent.TOOL_TYPE_FINGER,MotionEvent.TOOL_TYPE_STYLUS)
            var busy=false;var requests=0;v.onInkContact={busy=it};v.onConnectionTarget={_,_->requests++};v.onChange={v.document=it}
            multi(v,MotionEvent.ACTION_DOWN,listOf(types[0]),listOf(port))
            multi(v,MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),types,listOf(port,port));assertTrue(busy)
            multi(v,MotionEvent.ACTION_MOVE,types,listOf(port,target))
            multi(v,MotionEvent.ACTION_POINTER_UP or (1 shl 8),types,listOf(port,target),flags=if(success) 0 else 0x20)
            assertFalse(busy);assertEquals(if(success) 1 else 0,requests)
            multi(v,MotionEvent.ACTION_MOVE,listOf(types[0]),listOf(source));multi(v,MotionEvent.ACTION_UP,listOf(types[0]),listOf(source))
            assertEquals(before,v.document)
            v.cancelConnection();touchNode(v,MotionEvent.ACTION_DOWN,"source");touchNode(v,MotionEvent.ACTION_UP,"source")
            assertEquals(1,v.document.node("source").ink.size)
        }
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
