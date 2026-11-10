package com.xnotes.mindmap

import android.app.Activity
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.widget.FrameLayout
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class SourceRegionVisualViewTest {
    private class Fixture(select:Boolean=true,initial:Rect?=null) {
        val activity=Robolectric.buildActivity(Activity::class.java).setup()
        val host=FrameLayout(activity.get()).also {activity.get().setContentView(it)}
        val view=SourceRegionVisualView(activity.get())
        val regions=mutableListOf<Rect?>();val cameras=mutableListOf<SourceRegionCamera>();val busy=mutableListOf<Boolean>()
        var alive=true;var identity:Any=Any();var selecting=select
        var time=2L
        init {host.addView(view);bind(initial);view.image(android.graphics.Bitmap.createBitmap(1,1,android.graphics.Bitmap.Config.ARGB_8888),Rect(0.0,0.0,1000.0,800.0));host.layout(0,0,1000,800);view.layout(0,0,1000,800);idle();assertTrue(view.isAttachedToWindow)}
        fun bind(region:Rect?) {view.bind(identity,Rect(0.0,0.0,1000.0,800.0),null,region,selecting,null,
            android.graphics.Color.WHITE,{alive},{regions+=it;true},{busy+=it},{_,camera->cameras+=camera})}
        fun idle() {shadowOf(Looper.getMainLooper()).idle()}
        fun world(x:Double,y:Double)=view.viewport.contentToViewport(Pt(x,y))
        fun touch(action:Int,vararg positions:Pt,tools:IntArray=IntArray(positions.size) {MotionEvent.TOOL_TYPE_FINGER},
            ids:IntArray=IntArray(positions.size) {it},flags:Int=0) {
            val props=Array(positions.size) {i->MotionEvent.PointerProperties().apply {id=ids[i];toolType=tools[i]}}
            val coords=Array(positions.size) {i->MotionEvent.PointerCoords().apply {x=positions[i].x.toFloat();y=positions[i].y.toFloat();pressure=1f}}
            val event=MotionEvent.obtain(1,time++,action,positions.size,props,coords,0,0,1f,1f,0,0,
                if(tools.any {it==MotionEvent.TOOL_TYPE_STYLUS}) InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN,flags)
            try {assertTrue(view.onTouchEvent(event))} finally {event.recycle()}
        }
        fun close() {view.retire();host.removeAllViews();activity.pause().stop().destroy();idle()}
    }
    private fun scenario(select:Boolean=true,initial:Rect?=null,block:(Fixture)->Unit) {
        val f=Fixture(select,initial);try {block(f)} finally {f.close()}
    }
    private fun assertRect(expected:Rect,actual:Rect?) {
        assertNotNull(actual);assertEquals(expected.x,actual!!.x,1e-3);assertEquals(expected.y,actual.y,1e-3)
        assertEquals(expected.w,actual.w,1e-3);assertEquals(expected.h,actual.h,1e-3)
    }
    @Test fun draggingIsOnlyALocalPreviewUntilNormalUpPublishesOneFixedWorldRectangle()=scenario {f->
        val a=f.world(100.0,120.0);val b=f.world(200.0,230.0)
        f.touch(MotionEvent.ACTION_DOWN,a);f.touch(MotionEvent.ACTION_MOVE,b)
        assertTrue(f.view.busy);assertTrue(f.regions.isEmpty())
        f.touch(MotionEvent.ACTION_UP,b);assertFalse(f.view.busy);assertEquals(1,f.regions.size)
        assertRect(Rect(100.0,120.0,100.0,110.0),f.regions.single())
        f.idle();assertEquals(1,f.cameras.size) // Drawing a frame does not schedule another source render.
    }
    @Test fun aSecondFingerRollsBackTheUnconfirmedRectangleAndOwnsOnlyTheCamera()=scenario(initial=Rect(100.0,100.0,50.0,50.0)) {f->
        val a=f.world(125.0,125.0);val b=f.world(200.0,200.0)
        f.touch(MotionEvent.ACTION_DOWN,a);f.touch(MotionEvent.ACTION_MOVE,b)
        f.touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),b,Pt(b.x+100,b.y))
        f.touch(MotionEvent.ACTION_MOVE,Pt(b.x+20,b.y+30),Pt(b.x+180,b.y+30))
        f.touch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),b,Pt(b.x+180,b.y+30))
        f.touch(MotionEvent.ACTION_UP,b);f.idle()
        assertTrue(f.regions.isEmpty());assertFalse(f.view.busy);assertEquals(2,f.cameras.size)
        assertNotEquals(f.cameras.first(),f.cameras.last())
    }
    @Test fun penSelectionLocksItsTransformWhileTouchPointersRemainPalmInput()=scenario {f->
        val camera=f.view.camera();val a=f.world(100.0,100.0);val b=f.world(250.0,250.0)
        f.touch(MotionEvent.ACTION_DOWN,a,tools=intArrayOf(MotionEvent.TOOL_TYPE_STYLUS))
        f.touch(MotionEvent.ACTION_MOVE,b,tools=intArrayOf(MotionEvent.TOOL_TYPE_STYLUS))
        f.touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),b,Pt(800.0,700.0),
            tools=intArrayOf(MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.TOOL_TYPE_FINGER))
        f.touch(MotionEvent.ACTION_MOVE,b,Pt(100.0,100.0),tools=intArrayOf(MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.TOOL_TYPE_FINGER))
        assertEquals(camera,f.view.camera());assertTrue(f.regions.isEmpty())
        f.touch(MotionEvent.ACTION_POINTER_UP,b,Pt(100.0,100.0),tools=intArrayOf(MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.TOOL_TYPE_FINGER))
        f.touch(MotionEvent.ACTION_MOVE,Pt(50.0,50.0));f.touch(MotionEvent.ACTION_UP,Pt(50.0,50.0));f.idle()
        assertEquals(1,f.regions.size);assertRect(Rect(100.0,100.0,150.0,150.0),f.regions.single())
        assertEquals(camera,f.view.camera());assertEquals(1,f.cameras.size);assertFalse(f.view.busy)
    }
    @Test fun cancelIsolatesTheRemainingStreamUntilAnotherFreshDown()=scenario {f->
        val camera=f.view.camera();val a=f.world(100.0,100.0);val b=f.world(200.0,200.0)
        f.touch(MotionEvent.ACTION_DOWN,a);f.touch(MotionEvent.ACTION_MOVE,b);f.touch(MotionEvent.ACTION_CANCEL,b)
        f.touch(MotionEvent.ACTION_MOVE,Pt(600.0,600.0));f.touch(MotionEvent.ACTION_MOVE,Pt(800.0,700.0));f.touch(MotionEvent.ACTION_UP,Pt(800.0,700.0))
        assertTrue(f.regions.isEmpty());assertEquals(camera,f.view.camera())
        f.touch(MotionEvent.ACTION_DOWN,a);f.touch(MotionEvent.ACTION_MOVE,b);f.touch(MotionEvent.ACTION_UP,b)
        assertEquals(1,f.regions.size)
    }
    @Test fun rebindRejectsOldMovesAndUpInsteadOfChangingTheNextPagesCameraOrSelection()=scenario {f->
        val a=f.world(100.0,100.0);val b=f.world(300.0,300.0)
        f.touch(MotionEvent.ACTION_DOWN,a);f.touch(MotionEvent.ACTION_MOVE,b)
        f.identity=Any();f.bind(Rect(400.0,400.0,60.0,70.0));f.view.image(android.graphics.Bitmap.createBitmap(1,1,android.graphics.Bitmap.Config.ARGB_8888),Rect(0.0,0.0,1000.0,800.0));val camera=f.view.camera()
        f.touch(MotionEvent.ACTION_MOVE,Pt(700.0,600.0));f.touch(MotionEvent.ACTION_MOVE,Pt(800.0,700.0));f.touch(MotionEvent.ACTION_UP,Pt(800.0,700.0))
        assertTrue(f.regions.isEmpty());assertEquals(camera,f.view.camera())
    }
    @Test fun endingPenPanAfterAPalmTailStillPublishesTheStableCameraOnce()=scenario(select=false) {f->
        val a=Pt(200.0,200.0);val b=Pt(300.0,350.0)
        f.touch(MotionEvent.ACTION_DOWN,a,tools=intArrayOf(MotionEvent.TOOL_TYPE_STYLUS))
        f.touch(MotionEvent.ACTION_MOVE,b,tools=intArrayOf(MotionEvent.TOOL_TYPE_STYLUS))
        f.touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),b,Pt(800.0,700.0),
            tools=intArrayOf(MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.TOOL_TYPE_FINGER))
        f.touch(MotionEvent.ACTION_POINTER_UP,b,Pt(800.0,700.0),tools=intArrayOf(MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.TOOL_TYPE_FINGER))
        f.touch(MotionEvent.ACTION_UP,Pt(800.0,700.0));f.idle()
        assertEquals(2,f.cameras.size);assertNotEquals(f.cameras.first(),f.cameras.last())
        assertTrue(f.regions.isEmpty());assertFalse(f.view.busy)
    }
    @Test fun finiteLargePointerCoordinatesCannotTurnAPinchCameraIntoNaN()=scenario(select=false) {f->
        val a=Pt(-Float.MAX_VALUE.toDouble(),200.0);val b=Pt(Float.MAX_VALUE.toDouble(),200.0)
        f.touch(MotionEvent.ACTION_DOWN,a)
        f.touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),a,b)
        f.touch(MotionEvent.ACTION_MOVE,a,b)
        assertTrue(f.view.camera().valid())
    }
    @Test fun aPenTakingOverTouchPanLocksTheVisibleTransformWithoutJumpingToTheOldCamera()=scenario(select=false) {f->
        f.touch(MotionEvent.ACTION_DOWN,Pt(200.0,200.0));f.touch(MotionEvent.ACTION_MOVE,Pt(300.0,300.0))
        val visible=f.view.camera()
        f.touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),Pt(300.0,300.0),Pt(400.0,400.0),
            tools=intArrayOf(MotionEvent.TOOL_TYPE_FINGER,MotionEvent.TOOL_TYPE_STYLUS))
        assertEquals(visible,f.view.camera())
        f.touch(MotionEvent.ACTION_MOVE,Pt(800.0,700.0),Pt(400.0,400.0),tools=intArrayOf(MotionEvent.TOOL_TYPE_FINGER,MotionEvent.TOOL_TYPE_STYLUS))
        assertEquals(visible,f.view.camera());assertTrue(f.regions.isEmpty())
    }
    @Test fun aCanceledUpForTheActivePenNeverAcceptsItsRectangle()=scenario {f->
        val a=f.world(100.0,100.0);val b=f.world(250.0,250.0);val pen=intArrayOf(MotionEvent.TOOL_TYPE_STYLUS)
        f.touch(MotionEvent.ACTION_DOWN,a,tools=pen,ids=intArrayOf(7))
        f.touch(MotionEvent.ACTION_MOVE,b,tools=pen,ids=intArrayOf(7))
        f.touch(MotionEvent.ACTION_UP,b,tools=pen,ids=intArrayOf(7),flags=MotionEvent.FLAG_CANCELED)
        assertTrue(f.regions.isEmpty());assertFalse(f.view.busy)
    }
    @Test fun aCanceledPalmPointerDoesNotCancelTheActivePenWithAnotherPointerId()=scenario {f->
        val a=f.world(100.0,100.0);val b=f.world(250.0,250.0)
        val both=intArrayOf(MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.TOOL_TYPE_FINGER)
        f.touch(MotionEvent.ACTION_DOWN,a,tools=intArrayOf(MotionEvent.TOOL_TYPE_STYLUS),ids=intArrayOf(7))
        f.touch(MotionEvent.ACTION_MOVE,b,tools=intArrayOf(MotionEvent.TOOL_TYPE_STYLUS),ids=intArrayOf(7))
        f.touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),b,Pt(800.0,700.0),tools=both,ids=intArrayOf(7,17))
        f.touch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),b,Pt(800.0,700.0),tools=both,
            ids=intArrayOf(7,17),flags=MotionEvent.FLAG_CANCELED)
        assertTrue(f.regions.isEmpty());assertTrue(f.view.busy)
        f.touch(MotionEvent.ACTION_UP,b,tools=intArrayOf(MotionEvent.TOOL_TYPE_STYLUS),ids=intArrayOf(7))
        assertEquals(1,f.regions.size);assertRect(Rect(100.0,100.0,150.0,150.0),f.regions.single())
    }
    @Test fun aCanceledPointerUpForTheActivePenRollsBackAndDrainsThePalmTail()=scenario {f->
        val a=f.world(100.0,100.0);val b=f.world(250.0,250.0)
        val both=intArrayOf(MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.TOOL_TYPE_FINGER)
        f.touch(MotionEvent.ACTION_DOWN,a,tools=intArrayOf(MotionEvent.TOOL_TYPE_STYLUS),ids=intArrayOf(7))
        f.touch(MotionEvent.ACTION_MOVE,b,tools=intArrayOf(MotionEvent.TOOL_TYPE_STYLUS),ids=intArrayOf(7))
        f.touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),b,Pt(800.0,700.0),tools=both,ids=intArrayOf(7,17))
        f.touch(MotionEvent.ACTION_POINTER_UP,b,Pt(800.0,700.0),tools=both,ids=intArrayOf(7,17),flags=MotionEvent.FLAG_CANCELED)
        f.touch(MotionEvent.ACTION_MOVE,Pt(100.0,100.0),ids=intArrayOf(17))
        f.touch(MotionEvent.ACTION_UP,Pt(100.0,100.0),ids=intArrayOf(17))
        assertTrue(f.regions.isEmpty());assertFalse(f.view.busy)
    }
    private fun completedPenPanWithPalmTail(f:Fixture):SourceRegionCamera {
        f.touch(MotionEvent.ACTION_DOWN,Pt(200.0,200.0),tools=intArrayOf(MotionEvent.TOOL_TYPE_STYLUS))
        f.touch(MotionEvent.ACTION_MOVE,Pt(300.0,350.0),tools=intArrayOf(MotionEvent.TOOL_TYPE_STYLUS))
        val result=f.view.camera()
        val tools=intArrayOf(MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.TOOL_TYPE_FINGER)
        f.touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),Pt(300.0,350.0),Pt(800.0,700.0),tools=tools)
        f.touch(MotionEvent.ACTION_POINTER_UP,Pt(300.0,350.0),Pt(800.0,700.0),tools=tools)
        assertTrue(f.view.busy);assertEquals(1,f.cameras.size)
        return result
    }
    @Test fun canceledPalmTailStillPublishesTheAlreadyCompletedPenPan()=scenario(select=false) {f->
        val camera=completedPenPanWithPalmTail(f)
        f.touch(MotionEvent.ACTION_CANCEL,Pt(800.0,700.0));f.idle()
        assertFalse(f.view.busy);assertEquals(camera,f.view.camera())
        assertEquals(2,f.cameras.size);assertEquals(camera,f.cameras.last())
    }
    @Test fun focusLossDuringPalmTailStillPublishesTheAlreadyCompletedPenPan()=scenario(select=false) {f->
        val camera=completedPenPanWithPalmTail(f)
        f.view.onWindowFocusChanged(false);f.idle()
        assertFalse(f.view.busy);assertEquals(camera,f.view.camera())
        assertEquals(2,f.cameras.size);assertEquals(camera,f.cameras.last())
    }
    @Test fun cancelingAnUnfinishedPanRestoresItsStableCameraWithoutPublishingIt()=scenario(select=false) {f->
        val stable=f.view.camera()
        f.touch(MotionEvent.ACTION_DOWN,Pt(200.0,200.0));f.touch(MotionEvent.ACTION_MOVE,Pt(300.0,350.0))
        assertNotEquals(stable,f.view.camera())
        f.touch(MotionEvent.ACTION_CANCEL,Pt(300.0,350.0));f.idle()
        assertEquals(stable,f.view.camera());assertEquals(1,f.cameras.size);assertFalse(f.view.busy)
    }
    @Test fun aContactOnAnUnrenderedImageCannotBecomeASelectionAfterPixelsArrive()=scenario {f->
        val a=f.world(100.0,100.0);val b=f.world(200.0,200.0)
        f.view.image(null,null)
        f.touch(MotionEvent.ACTION_DOWN,a);f.touch(MotionEvent.ACTION_MOVE,b)
        f.view.image(android.graphics.Bitmap.createBitmap(1,1,android.graphics.Bitmap.Config.ARGB_8888),Rect(0.0,0.0,1000.0,800.0))
        f.touch(MotionEvent.ACTION_MOVE,b);f.touch(MotionEvent.ACTION_UP,b)
        assertTrue(f.regions.isEmpty());assertFalse(f.view.busy)
        f.touch(MotionEvent.ACTION_DOWN,a);f.touch(MotionEvent.ACTION_MOVE,b);f.touch(MotionEvent.ACTION_UP,b)
        assertEquals(1,f.regions.size)
    }
    @Test fun retiredOrDetachedViewsCannotCommitASelection()=scenario {f->
        val a=f.world(100.0,100.0);val b=f.world(200.0,200.0)
        f.touch(MotionEvent.ACTION_DOWN,a);f.touch(MotionEvent.ACTION_MOVE,b);f.view.retire()
        f.touch(MotionEvent.ACTION_UP,b);assertTrue(f.regions.isEmpty());assertFalse(f.view.busy)
    }
}
