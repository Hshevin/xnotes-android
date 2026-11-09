package com.xnotes.pen

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.tools.Tool
import com.xnotes.ui.InfiniteEditor
import com.xnotes.ui.ReferenceRegionReveal
import com.xnotes.ui.theme.ReferenceLocatorStyle
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Actual InfiniteEditor GL View/retained overlay lifecycles, without claiming GPU/device painting. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class CanvasReferenceLocatorHostTest {
    private class Outlines:Canvas() {
        val boxes=mutableListOf<Rect>()
        override fun drawRect(left:Float,top:Float,right:Float,bottom:Float,paint:Paint) {
            boxes+=Rect(left.toDouble(),top.toDouble(),(right-left).toDouble(),(bottom-top).toDouble())
        }
    }
    private fun outlines(editor:InfiniteEditor)=Outlines().also {editor.toolOverlay.draw(it)}.boxes
    private fun scenario(block:suspend(InfiniteEditor,FrameLayout)->Unit)=runParkingScenario {f->
        withContext(Dispatchers.Main) {
            val host=Robolectric.buildActivity(Activity::class.java).setup().visible()
            val holder=FrameLayout(host.get());host.get().setContentView(holder)
            val canvas=f.editor.infinite;canvas.pad.visibility=View.GONE
            try {block(canvas,holder)} finally {holder.removeAllViews();host.pause().stop().destroy()}
        }
    }
    private fun attach(editor:InfiniteEditor,holder:FrameLayout) {
        holder.addView(editor.surfacesForAttach(),FrameLayout.LayoutParams(-1,-1))
        holder.measure(View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY))
        holder.layout(0,0,1000,800)
        assertTrue(editor.view.isAttachedToWindow);assertTrue(editor.toolOverlay.isAttachedToWindow)
    }
    private fun frames(editor:InfiniteEditor,count:Int=3)=repeat(count) {editor.toolOverlay.viewTreeObserver.dispatchOnPreDraw()}
    private var time=100L
    private fun pen(editor:InfiniteEditor,action:Int,point:Pt) {
        val p=editor.viewport.contentToViewport(point)
        val properties=MotionEvent.PointerProperties().apply {id=7;toolType=MotionEvent.TOOL_TYPE_STYLUS}
        val coordinates=MotionEvent.PointerCoords().apply {x=p.x.toFloat();y=p.y.toFloat();pressure=.6f}
        val event=MotionEvent.obtain(100,time++,action,1,arrayOf(properties),arrayOf(coordinates),0,0,1f,1f,0,0,InputDevice.SOURCE_STYLUS,0)
        try {assertTrue(editor.view.onTouchEvent(event))} finally {event.recycle()}
    }
    @Test fun anUnshownFixedEmptyRegionWaitsForItsRealHostAndStartsItsLifetimeOnlyWhenDrawn()=scenario {canvas,holder->
        val doc=canvas.document;val region=Rect(4000.0,-3000.0,200.0,120.0)
        val history=canvas.history;val revision=doc.bodyRevision;val dirty=doc.dirty;val selected=canvas.selection.items
        assertEquals(ReferenceRegionReveal.SHOWN,canvas.showReferenceRegion(doc,region));assertTrue(outlines(canvas).isEmpty())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ReferenceLocatorStyle.DurationMillis+1))
        attach(canvas,holder)
        frames(canvas,1);assertTrue(outlines(canvas).isEmpty())
        frames(canvas,2)
        val boxes=outlines(canvas);assertEquals(2,boxes.size)
        assertEquals(canvas.viewport.contentToViewport(region).x,boxes.last().x,.01)
        assertEquals(region.centerX,canvas.viewport.centerContent.x,.01);assertEquals(region.centerY,canvas.viewport.centerContent.y,.01)
        assertSame(history,canvas.history);assertFalse(history.canUndo);assertEquals(revision,doc.bodyRevision);assertEquals(dirty,doc.dirty);assertEquals(selected,canvas.selection.items)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ReferenceLocatorStyle.DurationMillis+1));assertTrue(outlines(canvas).isEmpty())
    }
    @Test fun anUnshownNavigationCanUseTheNewActualSurfaceButAShownMarkNeverReplaysAfterDetach()=scenario {canvas,holder->
        attach(canvas,holder);val first=canvas.view;holder.removeView(canvas.surfaces)
        val region=Rect(-800.0,1100.0,300.0,200.0)
        assertEquals(ReferenceRegionReveal.SHOWN,canvas.showReferenceRegion(canvas.document,region))
        attach(canvas,holder);assertNotSame(first,canvas.view);frames(canvas);assertEquals(2,outlines(canvas).size)
        holder.removeView(canvas.surfaces);attach(canvas,holder);frames(canvas)
        assertTrue(outlines(canvas).isEmpty());assertFalse(canvas.document.dirty);assertFalse(canvas.history.canUndo)
    }
    @Test fun aRequestThatEnteredItsActualHostCannotReplayAfterLeavingBeforeItsSecondStableFrame()=scenario {canvas,holder->
        attach(canvas,holder)
        val document=canvas.document;val history=canvas.history;val revision=document.bodyRevision
        val selected=canvas.selection.items;val camera=canvas.viewport.toWaypoint()
        assertEquals(ReferenceRegionReveal.SHOWN,canvas.showReferenceRegion(document,Rect(9000.0,-7000.0,300.0,200.0)))
        frames(canvas,1);assertTrue(outlines(canvas).isEmpty())
        holder.removeView(canvas.surfaces)
        attach(canvas,holder);frames(canvas)
        assertEquals("Leaving an entered host retires even a not-yet-drawn navigation",camera,canvas.viewport.toWaypoint())
        assertTrue(outlines(canvas).isEmpty());assertSame(document,canvas.document);assertSame(history,canvas.history)
        assertEquals(selected,canvas.selection.items);assertEquals(revision,document.bodyRevision)
        assertFalse(document.dirty);assertFalse(history.canUndo)
    }
    @Test fun aFreshPenRetiresPendingNavigationBeforeItCanMoveTheActiveStrokeCamera()=scenario {canvas,holder->
        attach(canvas,holder);canvas.pad.frontBuffering=false;canvas.armTool(Tool.PEN)
        val center=canvas.viewport.centerContent
        canvas.showReferenceRegion(canvas.document,Rect(6000.0,5000.0,300.0,200.0))
        val camera=Triple(canvas.viewport.zoom,canvas.viewport.scrollX,canvas.viewport.scrollY)
        pen(canvas,MotionEvent.ACTION_DOWN,center);frames(canvas)
        assertEquals(camera,Triple(canvas.viewport.zoom,canvas.viewport.scrollX,canvas.viewport.scrollY));assertTrue(outlines(canvas).isEmpty())
        pen(canvas,MotionEvent.ACTION_MOVE,Pt(center.x+30,center.y+20));pen(canvas,MotionEvent.ACTION_UP,Pt(center.x+60,center.y+45))
        assertEquals(1,canvas.document.itemCount);assertTrue(canvas.document.dirty);assertTrue(canvas.history.canUndo)
    }
    @Test fun manualNavigationAndModelReplacementClearPendingAndVisibleMarkersWithoutContentHistory()=scenario {canvas,holder->
        attach(canvas,holder);val old=canvas.document
        canvas.showReferenceRegion(old,Rect(1200.0,800.0,300.0,200.0));canvas.zoomBy(1.1);val camera=canvas.viewport.toWaypoint()
        frames(canvas);assertTrue(outlines(canvas).isEmpty());assertEquals(camera,canvas.viewport.toWaypoint())
        canvas.showReferenceRegion(old,Rect(-1200.0,800.0,300.0,200.0));frames(canvas);assertEquals(2,outlines(canvas).size)
        assertEquals(ReferenceRegionReveal.NONE,canvas.showReferenceRegion(old,null));assertTrue(outlines(canvas).isEmpty())
        canvas.showReferenceRegion(old,Rect(900.0,800.0,200.0,200.0));canvas.replaceDocument(InfiniteDocument());frames(canvas)
        assertTrue(outlines(canvas).isEmpty());assertFalse(old.dirty);assertFalse(canvas.document.dirty);assertFalse(canvas.history.canUndo)
    }
    @Test fun selectionAndScreenshotSourcesFreezeWorldGeometryWithoutCompletingAnActivePreview()=scenario {canvas,holder->
        attach(canvas,holder)
        val item=ShapeItem(ShapeKind.RECTANGLE,Pt(-200.0,100.0),Pt(0.0,180.0),Rgba(0,0,0),rotation=Math.PI/4)
        canvas.document.add(item);canvas.selectAll()
        val expected=Rect.bounding(canvas.selection.box!!.corners())
        val revision=canvas.document.bodyRevision
        assertEquals(expected,canvas.sourceSelectionRegion())
        canvas.viewport.zoom=2.3;canvas.viewport.scrollX=-1000.0;canvas.viewport.scrollY=500.0
        assertEquals(expected,canvas.sourceSelectionRegion());assertNotEquals(canvas.selectionMenuRect,expected)
        canvas.restyleSelection(Rgba(70,70,70),null,preview=true)
        assertNull(canvas.sourceSelectionRegion());assertFalse(canvas.history.canUndo)
        canvas.finishSelectionPreviews()
        canvas.armTool(Tool.SCREENSHOT)
        val start=canvas.viewport.centerContent;val finish=Pt(start.x+100.0,start.y+60.0)
        pen(canvas,MotionEvent.ACTION_DOWN,start);pen(canvas,MotionEvent.ACTION_MOVE,finish)
        assertNull(canvas.sourceCaptureRegion())
        pen(canvas,MotionEvent.ACTION_UP,finish)
        assertEquals(Rect.fromPoints(start,finish),canvas.sourceCaptureRegion())
        val saved=canvas.sourceCaptureRegion();canvas.viewport.scrollX+=700;assertEquals(saved,canvas.sourceCaptureRegion())
        assertTrue(canvas.document.bodyRevision>=revision)
    }
}
