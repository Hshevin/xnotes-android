package com.xnotes.mindmap

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.xnotes.core.geometry.Pt
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class MapReadOnlyNavigationHostTest:MapReadOnlyNavigationContract() {
    override fun ui(block:(Context)->Unit)=block(RuntimeEnvironment.getApplication())

    /** Direct Activity dispatch exercises the legacy key route, not Android 15 ViewRoot/OnBackInvoked. */
    private fun directActivityBackReachesTheHost(mode:Int) {
        val host=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            val activity=host.get();var backs=0;var edits=0;var writes=0
            activity.onBackPressedDispatcher.addCallback(activity,object:OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {backs++}
            })
            val view=MindMapView(activity).apply {
                document=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,text="Text")))
                readOnlyNavigation=true;onEdit={edits++};onChange={writes++};onInk={_,_,_->writes++;true}
            }
            activity.setContentView(view);shadowOf(Looper.getMainLooper()).idle()
            assertTrue(view.width>0 && view.height>0);view.fit()
            assertTrue(view.requestFocus());assertTrue(view.hasFocus())
            val p=view.screenPoint(view.document.root.bounds.center)
            val time=SystemClock.uptimeMillis()
            if(mode!=0) {
                val props=arrayOf(MotionEvent.PointerProperties().apply {id=7;toolType=MotionEvent.TOOL_TYPE_STYLUS})
                val coords=arrayOf(MotionEvent.PointerCoords().apply {x=p.x.toFloat();y=p.y.toFloat();pressure=.7f})
                val pen=MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,1,props,coords,0,0,1f,1f,888,0,InputDevice.SOURCE_STYLUS,0)
                try {view.onTouchEvent(pen)} finally {pen.recycle()}
                if(mode==2) view.readOnlyNavigation=false
            }
            val bytes=MapCodec.encode(view.document)
            activity.dispatchKeyEvent(KeyEvent(time,time,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK,0))
            activity.dispatchKeyEvent(KeyEvent(time,time+1,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK,0))
            assertEquals("A focused graph must not swallow the host's legacy Back key route",1,backs)
            assertEquals(0,edits);assertEquals(0,writes);assertArrayEquals(bytes,MapCodec.encode(view.document))
        } finally {host.pause().stop().destroy()}
    }
    @Test fun directActivityBackReachesTheHostWhileReadonlyIsIdle()=directActivityBackReachesTheHost(0)
    @Test fun directActivityBackReachesTheHostDuringReadonlyNavigation()=directActivityBackReachesTheHost(1)
    @Test fun directActivityBackReachesTheHostAfterModeOffWhileTheOldStreamIsDrained()=directActivityBackReachesTheHost(2)

    @Test fun attachedReadonlyPenPalmAndLifecycleRetireEveryDelayedEditorCallback() {
        for(interrupt in 0..5) {
            val host=Robolectric.buildActivity(android.app.Activity::class.java).setup().visible()
            try {
                val activity=host.get();val frame=android.widget.FrameLayout(activity)
                var edits=0;var writes=0;var sources=0;var contact=false
                val view=MindMapView(activity).apply {
                    document=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,text="Text",x=-80.0,y=-36.0,width=160.0)))
                    readOnlyNavigation=true
                    onEdit={edits++};onEditRelation={edits++};onSources={sources++};onChange={writes++}
                    onInk={_,_,_->writes++;true};onReadOnlyContact={contact=it}
                }
                frame.addView(view);activity.setContentView(frame)
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(view.width>0 && view.height>0);view.fit()
                shadowOf(Looper.getMainLooper()).idle()
                val size=Pair(view.width,view.height)
                assertTrue(view.isAttachedToWindow)
                val p=view.screenPoint(view.document.root.bounds.center);val palm=Pt(30.0,40.0)
                val down=SystemClock.uptimeMillis();var tick=0L
                fun event(action:Int,ids:List<Int>,types:List<Int>,points:List<Pt>) {
                    val props=types.mapIndexed {i,type->MotionEvent.PointerProperties().apply {id=ids[i];toolType=type}}.toTypedArray()
                    val coords=points.map {point->MotionEvent.PointerCoords().apply {x=point.x.toFloat();y=point.y.toFloat();pressure=.7f}}.toTypedArray()
                    val e=MotionEvent.obtain(down,down+tick++,action,ids.size,props,coords,0,0,1f,1f,888,0,InputDevice.SOURCE_STYLUS,0)
                    try {view.onTouchEvent(e)} finally {e.recycle()}
                }
                val pen=MotionEvent.TOOL_TYPE_STYLUS;val finger=MotionEvent.TOOL_TYPE_FINGER
                event(MotionEvent.ACTION_DOWN,listOf(7),listOf(pen),listOf(p))
                event(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),listOf(7,2),listOf(pen,finger),listOf(p,palm))
                assertTrue(contact)
                when(interrupt) {
                    0->event(MotionEvent.ACTION_POINTER_UP,listOf(7,2),listOf(pen,finger),listOf(p,palm))
                    1->{view.documentEpoch++;view.document=view.document.copy()}
                    2->view.readOnlyNavigation=false
                    3->{view.isEnabled=false;view.isEnabled=true}
                    4->view.onWindowFocusChanged(false)
                    else->frame.removeView(view)
                }
                val bytes=MapCodec.encode(view.document)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout().toLong()+100))
                assertEquals("A late resize must not hide a detector-lifetime failure",size,Pair(view.width,view.height))
                assertEquals(0,edits);assertEquals(0,writes);assertEquals(0,sources)
                if(interrupt!=0) event(MotionEvent.ACTION_POINTER_UP,listOf(7,2),listOf(pen,finger),listOf(p,palm))
                event(MotionEvent.ACTION_UP,listOf(2),listOf(finger),listOf(palm))
                assertFalse(contact);assertEquals(0,edits);assertEquals(0,writes);assertEquals(0,sources)
                assertArrayEquals(bytes,MapCodec.encode(view.document))
            } finally {host.pause().stop().destroy()}
        }
    }
    @Test fun attachedReadonlyDoubleTapAndEditKeyEventsNeverReachAnEditor() {
        val host=Robolectric.buildActivity(android.app.Activity::class.java).setup().visible()
        try {
            val activity=host.get();var forbidden=0
            val view=MindMapView(activity).apply {
                document=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,text="Text")))
                readOnlyNavigation=true;onEdit={forbidden++};onChange={forbidden++};onSources={forbidden++}
            }
            activity.setContentView(view);shadowOf(Looper.getMainLooper()).idle()
            assertTrue(view.width>0 && view.height>0);view.fit()
            shadowOf(Looper.getMainLooper()).idle()
            val size=Pair(view.width,view.height)
            val p=view.screenPoint(view.document.root.bounds.center);val down=SystemClock.uptimeMillis()
            repeat(2) {tap->
                for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                    val event=MotionEvent.obtain(down+tap*100,down+tap*100+if(action==MotionEvent.ACTION_DOWN) 0 else 20,
                        action,p.x.toFloat(),p.y.toFloat(),0)
                    try {view.onTouchEvent(event)} finally {event.recycle()}
                }
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout().toLong()+100))
            assertEquals("A late resize must not hide a detector-lifetime failure",size,Pair(view.width,view.height))
            for(code in listOf(KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_DEL,KeyEvent.KEYCODE_FORWARD_DEL,KeyEvent.KEYCODE_Z)) {
                val event=KeyEvent(down,down,KeyEvent.ACTION_DOWN,code,0,KeyEvent.META_CTRL_ON)
                assertTrue(view.onKeyDown(code,event));assertTrue(view.onKeyUp(code,KeyEvent(KeyEvent.ACTION_UP,code)))
            }
            assertEquals(0,forbidden)
        } finally {host.pause().stop().destroy()}
    }
}
