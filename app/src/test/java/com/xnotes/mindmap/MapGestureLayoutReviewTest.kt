package com.xnotes.mindmap

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import com.xnotes.R
import com.xnotes.core.geometry.Pt
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class MapGestureLayoutReviewTest {
    private fun document()=MapLayout.arrange(MindMapDocument(nodes=listOf(MapNode(id="root",parent=null,text="Root"),
        MapNode(id="branch",parent="root",text="Branch"),MapNode(id="other",parent="root",text="Other"))))
    private val source=MapSource(documentId="00000000-0000-0000-0000-000000000001",fileName="Arrived source")
    private fun addSource(session:MapSession) {
        val request=session.captureSourceImport(MapReferenceDestination.Existing("branch"))!!
        assertTrue(session.importSource(source,request) is MapSourceImportResult.Applied)
    }
    private fun send(view:MindMapView,action:Int,point:Pt,time:Long) {
        val prop=MotionEvent.PointerProperties().apply {id=7;toolType=MotionEvent.TOOL_TYPE_STYLUS}
        val coord=MotionEvent.PointerCoords().apply {x=point.x.toFloat();y=point.y.toFloat();pressure=.8f}
        val event=MotionEvent.obtain(time,time+action,action,1,arrayOf(prop),arrayOf(coord),0,0,1f,1f,888,0,InputDevice.SOURCE_STYLUS,0)
        try {assertTrue(view.onTouchEvent(event))} finally {event.recycle()}
    }
    @Test fun dragUpBeforeTheNextCompositionCannotEraseAnAcceptedSourceImport() {
        MapComposeReviewFixture(document()).use {f->
            f.screen();val view=f.graph()
            // Drive the real native surface/callback while the JVM has no physical host frame clock.
            // Android camera restoration itself has separate instrumented coverage.
            view.isEnabled=true;view.fit()
            val before=f.session.document!!;val at=view.screenPoint(before.node("branch").bounds.center);val to=at+Pt(180.0,180.0)
            val time=SystemClock.uptimeMillis();send(view,MotionEvent.ACTION_DOWN,at,time);send(view,MotionEvent.ACTION_MOVE,to,time)
            addSource(f.session)
            // The source actor has accepted its operation, but AndroidView has not received a frame.
            assertSame(before,view.document)
            send(view,MotionEvent.ACTION_UP,to,time)
            assertEquals(listOf(source),f.session.document!!.node("branch").sources)
            assertTrue("A rejected move must explain why, or apply against the latest source-bearing graph",
                f.session.document!!.node("branch").bounds!=before.node("branch").bounds || f.session.problemText()!=null)
        }
    }
    @Test fun aFrameDuringDragAlreadyRetiresTheOldPreviewAndKeepsTheImportedSource() {
        MapComposeReviewFixture(document()).use {f->
            f.screen();val view=f.graph();view.isEnabled=true;view.fit();val before=f.session.document!!
            val at=view.screenPoint(before.node("branch").bounds.center);val to=at+Pt(180.0,180.0);val time=SystemClock.uptimeMillis()
            send(view,MotionEvent.ACTION_DOWN,at,time);send(view,MotionEvent.ACTION_MOVE,to,time)
            addSource(f.session);f.settle();val imported=f.session.document!!
            send(view,MotionEvent.ACTION_UP,to,time)
            assertSame(imported,f.session.document);assertEquals(listOf(source),f.session.document!!.node("branch").sources)
        }
    }
    @Test fun anOldLayoutConfirmClosureCannotEraseASourceBeforeAnyCompositionRuns() {
        MapComposeReviewFixture(document()).use {f->
            f.screen();f.click(R.string.map_menu);f.click(R.string.map_menu_layout);f.click(R.string.graph_vertical)
            val confirm=f.clickAction(R.string.map_done)
            addSource(f.session);assertTrue(confirm())
            assertEquals(listOf(source),f.session.document!!.node("branch").sources)
            assertEquals(MapDirection.DOWN,f.session.document!!.direction)
            f.session.undo();assertEquals(listOf(source),f.session.document!!.node("branch").sources)
            assertEquals(MapDirection.BOTH,f.session.document!!.direction)
        }
    }
    @Test fun menuHidesInactiveActionsPreservesPropertyDraftAndLeavesCanvasSizeUnchanged() {
        MapComposeReviewFixture(document()).use {f->
            f.screen();val view=f.graph();val dimensions=view.width to view.height
            assertFalse(f.visible(R.string.map_node_list))
            f.click(R.string.map_menu);assertTrue(f.visible(R.string.map_node_list))
            assertEquals(dimensions,view.width to view.height)
            f.click(R.string.ink_properties);f.enter("Unconfirmed title")
            val before=f.session.document
            f.click(R.string.map_menu_layout)
            assertFalse(f.visible(R.string.map_node_text));assertFalse(f.visible(R.string.map_done))
            f.click(R.string.map_menu_nodes);f.click(R.string.map_done)
            assertEquals("Unconfirmed title",f.session.document!!.node(f.session.selected!!).text)
            f.session.undo();assertEquals(before,f.session.document)
            f.click(R.string.map_return_to_canvas);assertFalse(f.visible(R.string.map_node_list))
            assertEquals(dimensions,view.width to view.height)
        }
    }
    @Test fun creationMenuCancelLeavesNoNodeOrHistory() {
        MapComposeReviewFixture(document()).use {f->
            f.screen();val before=f.session.document
            f.click(R.string.map_add_text);f.enter("Not yet created")
            assertEquals(before,f.session.document);assertFalse(f.session.canUndo)
            f.click(R.string.cancel)
            assertNull(f.session.connectedDraft);assertEquals(before,f.session.document);assertFalse(f.session.canUndo)
        }
    }

}
