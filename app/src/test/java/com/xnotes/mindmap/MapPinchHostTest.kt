package com.xnotes.mindmap

import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.runtime.snapshots.Snapshot
import com.xnotes.EditorActivity
import com.xnotes.core.geometry.Pt
import com.xnotes.pen.initializeParkingEglHandles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real window dispatch into the composed map with a host frame between each event. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class MapPinchHostTest {
    private fun document()=MapLayout.arrange(MindMapDocument(nodes=listOf(MapNode(id="root",parent=null,text="Root"),
        MapNode(id="branch",parent="root",text="Branch"),MapNode(id="other",parent="root",text="Other"))))

    private class Touch(val f:MapComposeReviewFixture) {
        private var down=0L
        private var time=SystemClock.uptimeMillis()
        var primaryTool=MotionEvent.TOOL_TYPE_FINGER
        fun pauseBetweenTaps() {time+=60}

        fun event(action:Int,vararg points:Pt) {
            time+=16
            if(action==MotionEvent.ACTION_DOWN) down=time
            val origin=IntArray(2).also {f.graph().getLocationInWindow(it)}
            val props=points.indices.map {i->MotionEvent.PointerProperties().apply {
                id=i;toolType=if(i==0) primaryTool else MotionEvent.TOOL_TYPE_FINGER
            }}.toTypedArray()
            val coords=points.map {p->MotionEvent.PointerCoords().apply {
                x=p.x.toFloat()+origin[0];y=p.y.toFloat()+origin[1];pressure=1f;size=.1f
            }}.toTypedArray()
            val source=if(primaryTool==MotionEvent.TOOL_TYPE_STYLUS) InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN
            val e=MotionEvent.obtain(down,time,action,points.size,props,coords,0,0,1f,1f,1,0,source,0)
            try {assertTrue(f.activity.dispatchTouchEvent(e))} finally {e.recycle()}
            f.settle()
        }

        fun pinch(first:Pt,from:Double,to:Double) {
            val center=first+Pt(from,0.0)
            fun move(action:Int,span:Double)=event(action,center-Pt(span,0.0),center+Pt(span,0.0))
            event(MotionEvent.ACTION_DOWN,first)
            move(secondDown,from)
            for(step in 1..4) move(MotionEvent.ACTION_MOVE,from+(to-from)*step/4)
            move(secondUp,to)
            event(MotionEvent.ACTION_UP,center-Pt(to,0.0))
        }
    }

    private fun ready(f:MapComposeReviewFixture):MindMapView {
        f.screen()
        return enabledGraph(f)
    }

    private fun enabledGraph(f:MapComposeReviewFixture):MindMapView {
        repeat(10) {
            runCatching {f.graph()}.getOrNull()?.takeIf {it.isEnabled}?.let {return it}
            f.settle()
        }
        error("The composed graph never became ready")
    }

    @Test fun successivePinchesOnBlankPaperZoomTheComposedMap() {
        MapComposeReviewFixture(document()).use {f->
            val view=ready(f)
            val touch=Touch(f)
            val start=Pt(view.width*.65,view.height*.8)
            for((from,to) in listOf(100.0 to 160.0,160.0 to 110.0,100.0 to 140.0)) {
                val before=view.viewport().zoom
                touch.pinch(start,from,to)
                assertEquals(before*(to/from).toFloat(),view.viewport().zoom,.01f)
            }
        }
    }

    @Test fun pinchStartingOnAJustSelectedNodeDoesNotEditOrMoveIt() {
        MapComposeReviewFixture(document()).use {f->
            val view=ready(f)
            val before=view.viewport().zoom
            val doc=f.session.document!!
            val revision=f.session.contentRevision
            val start=view.screenPoint(doc.node("branch").bounds.center)
            val touch=Touch(f)
            touch.event(MotionEvent.ACTION_DOWN,start)
            touch.event(MotionEvent.ACTION_UP,start)
            assertEquals("branch",f.session.selected)
            touch.pauseBetweenTaps()
            touch.pinch(start,70.0,110.0)
            assertEquals(before*110f/70f,view.viewport().zoom,.01f)
            assertSame(doc,f.session.document)
            assertEquals(revision,f.session.contentRevision)
            assertNull(f.session.textDraft)
        }
    }

    @Test fun doubleTapStillOpensTextEditingWhenTheSecondFingerDoesNotJoin() {
        MapComposeReviewFixture(document()).use {f->
            val view=ready(f)
            val start=view.screenPoint(f.session.document!!.node("branch").bounds.center)
            val touch=Touch(f)
            touch.event(MotionEvent.ACTION_DOWN,start)
            touch.event(MotionEvent.ACTION_UP,start)
            touch.pauseBetweenTaps()
            touch.event(MotionEvent.ACTION_DOWN,start)
            assertNull(f.session.textDraft)
            touch.event(MotionEvent.ACTION_UP,start)
            assertEquals("branch",f.session.textDraft?.nodeId)
        }
    }

    @Test fun oneFingerOnBlankPaperPansExactlyTheFingerDisplacement() {
        MapComposeReviewFixture(document()).use {f->
            val view=ready(f)
            val start=view.viewport()
            val point=Pt(view.width*.8,view.height*.8)
            val touch=Touch(f)
            touch.event(MotionEvent.ACTION_DOWN,point)
            touch.event(MotionEvent.ACTION_MOVE,point-Pt(1.0,1.0))
            touch.event(MotionEvent.ACTION_MOVE,point-Pt(200.0,120.0))
            touch.event(MotionEvent.ACTION_UP,point-Pt(300.0,200.0))
            assertEquals(-300f,view.viewport().panX-start.panX,.01f)
            assertEquals(-200f,view.viewport().panY-start.panY,.01f)
        }
    }

    @Test fun penDraggingANodeTreatsTheSecondPointerAsAPalm() {
        MapComposeReviewFixture(document()).use {f->
            val view=ready(f)
            val before=view.viewport()
            val node=f.session.document!!.node("branch")
            val start=view.screenPoint(node.bounds.center)
            val palm=Pt(view.width*.7,view.height*.8)
            val end=start+Pt(65.0,-75.0)
            val touch=Touch(f).apply {primaryTool=MotionEvent.TOOL_TYPE_STYLUS}
            touch.event(MotionEvent.ACTION_DOWN,start)
            touch.event(secondDown,start,palm)
            touch.event(MotionEvent.ACTION_MOVE,end,palm+Pt(80.0,40.0))
            touch.event(secondUp,end,palm)
            touch.event(MotionEvent.ACTION_UP,end)
            assertEquals(before,view.viewport())
            val moved=f.session.document!!.node(node.id)
            assertEquals(node.x+(end.x-start.x)/view.contentScale(),moved.x,.01)
            assertEquals(node.y+(end.y-start.y)/view.contentScale(),moved.y,.01)
        }
    }

    @Test fun cameraRestoreWaitsForTheTouchToFinish() {
        MapComposeReviewFixture(document()).use {f->
            val presentation=MapPresentationState()
            f.content {MindMapScreen(f.session,"Review graph",{},presentation)}
            val view=enabledGraph(f)
            val before=view.viewport()
            val point=Pt(view.width*.8,view.height*.8)
            val touch=Touch(f)
            touch.event(MotionEvent.ACTION_DOWN,point)
            presentation.restoreAfterPanelInput()
            touch.event(MotionEvent.ACTION_MOVE,point-Pt(120.0,60.0))
            assertEquals(-120f,view.viewport().panX-before.panX,.01f)
            touch.event(MotionEvent.ACTION_UP,point-Pt(120.0,60.0))
            repeat(4) {f.settle()}
            val restored=view.viewport()
            assertEquals(before.zoom,restored.zoom,.01f)
            assertEquals(before.panX,restored.panX,.01f)
            assertEquals(before.panY,restored.panY,.01f)
        }
    }

    @Test fun mainActivityDispatchAndWorkspaceFocusHooksPreservePinch() {
        initializeParkingEglHandles()
        MapComposeReviewFixture(document(),EditorActivity::class.java).use {f->
            val activity=f.activity as EditorActivity
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
            val workspace=MapWorkspace(activity,scope)
            val original=activity.mapWorkspace
            activity.mapWorkspace=workspace
            try {
                f.content {}
                assertTrue(workspace.open(Uri.fromFile(f.file),"Review graph"))
                f.await {
                    Snapshot.sendApplyNotifications()
                    !workspace.opening
                }
                assertTrue("Workspace failed to open: ${workspace.failure}",workspace.visible)
                var focusRequests=0
                f.content {
                    MapWorkspaceHost(workspace,onClosed={},hooks={
                        MapHostHooks(openSource={_,_->false},requestFocus={focusRequests++;true})
                    }) {}
                }
                val view=enabledGraph(f)
                val before=view.viewport().zoom
                Touch(f).pinch(Pt(view.width*.65,view.height*.8),100.0,150.0)
                assertEquals(before*1.5f,view.viewport().zoom,.01f)
                assertTrue(focusRequests>0)
                assertSame(view,f.graph())
                assertTrue(view.isEnabled)
            } finally {
                f.content {}
                activity.mapWorkspace=original
                val disposal=workspace.dispose()
                f.await {disposal.isCompleted}
                scope.cancel()
            }
        }
    }

    private companion object {
        const val secondDown=MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        const val secondUp=MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
    }
}
