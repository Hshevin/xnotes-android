package com.xnotes.mindmap

import android.view.MotionEvent
import com.xnotes.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w1800dp-h1400dp-mdpi")
class MapInteractionUsabilityTest {
    private fun document()=MindMapDocument(nodes=listOf(MapNode(id="root",parent=null,text="Root")))

    @Test fun outsideTapCancelsCreationWithoutAddingNodeOrHistoryAndRestoresCanvas() {
        MapComposeReviewFixture(document()).use {f->
            f.screen();val before=f.session.document!!
            f.click(R.string.map_add_text)
            assertNotNull(f.session.connectedDraft);assertFalse(f.graph().isEnabled)
            val panel=f.taggedBounds("map-menu-panel")
            val outside=f.taggedBounds("map-menu-dismiss-area")
            f.tapAt((outside.left+panel.left)/2,outside.center.y)
            assertNull(f.session.connectedDraft);assertEquals(before,f.session.document)
            assertFalse(f.session.canUndo);assertTrue(f.graph().isEnabled)
            assertFalse(f.visible(R.string.map_create_confirm))
            f.click(R.string.map_menu)
            assertNull("Reopening menu recreated draft",f.session.connectedDraft)
            assertTrue(f.visible(R.string.map_node_list));assertEquals(before,f.session.document)
        }
    }

    @Test fun outsideDragDoesNotCancelANewNodeDraft() {
        MapComposeReviewFixture(document()).use {f->
            f.screen();f.click(R.string.map_add_text)
            val draft=f.session.connectedDraft
            val panel=f.taggedBounds("map-menu-panel")
            val outside=f.taggedBounds("map-menu-dismiss-area")
            val x=(outside.left+panel.left)/2;val y=outside.center.y
            val time=android.os.SystemClock.uptimeMillis()
            f.touch(x,y,MotionEvent.ACTION_DOWN,time)
            f.touch(x+100,y,MotionEvent.ACTION_MOVE,time)
            f.touch(x+100,y,MotionEvent.ACTION_UP,time)
            assertSame(draft,f.session.connectedDraft);assertTrue(f.visible(R.string.map_create_confirm))
            f.click(R.string.map_return_to_canvas)
        }
    }

    @Test fun nearbyBackCancelsCreationAndTextWithoutLeavingTheMap() {
        MapComposeReviewFixture(document()).use {f->
            var closed=0;f.screen {closed++};val before=f.session.document!!
            f.click(R.string.map_add_text);f.click(R.string.map_return_to_canvas)
            assertNull(f.session.connectedDraft);assertEquals(before,f.session.document)
            f.click(R.string.map_edit)
            f.await {f.session.textDraft!=null}
            f.enter("Unconfirmed edit")
            val actions=f.taggedBounds("map-text-actions")
            assertTrue(actions.width>0);assertTrue(actions.height>0)
            f.click(R.string.map_cancel_and_return)
            assertNull(f.session.textDraft);assertEquals(before,f.session.document)
            assertFalse(f.session.canUndo);assertEquals(0,closed)
        }
    }

    @Test fun dockDragsLeftAndKeepsItsSideWhenReopenedWithoutResizingCanvas() {
        MapComposeReviewFixture(document()).use {f->
            f.screen();f.click(R.string.map_menu)
            val graph=f.graph();val width=graph.width;val height=graph.height;val camera=graph.viewport()
            val before=f.taggedBounds("map-menu-panel")
            assertTrue("Expected right dock: $before, graph ${graph.width}x${graph.height}",before.left>0)
            val x=before.left+30;val y=before.top+20
            val time=android.os.SystemClock.uptimeMillis()
            f.touch(x,y,MotionEvent.ACTION_DOWN,time)
            f.touch(x-100,y,MotionEvent.ACTION_MOVE,time)
            f.touch(20f,y,MotionEvent.ACTION_MOVE,time)
            f.touch(20f,y,MotionEvent.ACTION_UP,time)
            val after=f.taggedBounds("map-menu-panel")
            assertEquals(0f,after.left,1f)
            assertEquals(width,graph.width);assertEquals(height,graph.height);assertEquals(camera,graph.viewport())
            f.click(R.string.map_return_to_canvas);f.click(R.string.map_menu)
            assertEquals(after.left,f.taggedBounds("map-menu-panel").left,1f)
            val outside=f.taggedBounds("map-menu-dismiss-area")
            f.tapAt((after.right+outside.right)/2,outside.center.y)
            assertFalse(f.visible(R.string.map_return_to_canvas))
        }
    }

    @Test fun dockGeometryClampsNarrowPanesAndSnapsAtTheMiddle() {
        assertEquals(0f,mapDockOffset(200f,400f,true),0f)
        assertEquals(600f,mapDockOffset(1000f,400f,true),0f)
        assertEquals(0f,mapDockOffset(1000f,400f,false),0f)
        assertFalse(mapDockSnapRight(299f,1000f,400f))
        assertTrue(mapDockSnapRight(300f,1000f,400f))
    }
}
