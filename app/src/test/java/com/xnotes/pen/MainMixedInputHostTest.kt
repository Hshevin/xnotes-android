package com.xnotes.pen

import android.net.Uri
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.xnotes.MainActivity
import com.xnotes.MixedInputBinding
import com.xnotes.canvas.StylusButtonLatch
import com.xnotes.core.tools.Tool
import com.xnotes.mindmap.*
import com.xnotes.ui.Editor
import com.xnotes.ui.Pane
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual Main dispatch and Window touch targets, with actual native shortcut and map owners. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class MainMixedInputHostTest {
    private data class Seen(val action:Int,val buttons:Int,val pressure:Float,val tilt:Float,val ids:List<Int>)
    private class RecordingView(activity:MainActivity):View(activity) {
        val touches=mutableListOf<Seen>()
        val keys=mutableListOf<Int>()
        init {isClickable=true;isFocusable=true;isFocusableInTouchMode=true}
        override fun onTouchEvent(e:MotionEvent):Boolean {
            touches+=Seen(e.actionMasked,e.buttonState,e.pressure,e.getAxisValue(MotionEvent.AXIS_TILT),
                (0 until e.pointerCount).map {e.getPointerId(it)})
            return true
        }
        override fun onGenericMotionEvent(event:MotionEvent)=true
        override fun dispatchKeyEvent(event:KeyEvent):Boolean {keys+=event.keyCode;return true}
        fun bounds():MixedInputRouter.Bounds {
            val origin=IntArray(2);getLocationInWindow(origin)
            return MixedInputRouter.Bounds(origin[0].toFloat(),origin[1].toFloat(),
                (origin[0]+width).toFloat(),(origin[1]+height).toFloat())
        }
    }
    private class Scene(val activity:MainActivity,val editor:Editor,val maps:MapWorkspace,
        val map:RecordingView,val native:RecordingView,val input:MixedInputBinding) {
        val mapTarget=MixedInputRouter.Target(Pane.SECONDARY,MixedInputRouter.Kind.MAP)
        val nativeTarget=MixedInputRouter.Target(Pane.PRIMARY,MixedInputRouter.Kind.NATIVE)
        fun focusNative() {assertTrue(input.focus(nativeTarget))}
        fun focusMap() {assertTrue(input.focus(mapTarget))}
        fun point(view:RecordingView):Pair<Float,Float> = view.bounds().let {(it.left+it.right)/2 to (it.top+it.bottom)/2}
        fun event(action:Int,time:Long,view:RecordingView,second:RecordingView?=null):MotionEvent {
            val points=listOfNotNull(view,second).map(::point)
            val props=points.mapIndexed {index,_->MotionEvent.PointerProperties().apply {
                id=if(index==0) 7 else 19;toolType=if(index==0) MotionEvent.TOOL_TYPE_STYLUS else MotionEvent.TOOL_TYPE_FINGER
            }}.toTypedArray()
            val coords=points.map {(px,py)->MotionEvent.PointerCoords().apply {
                x=px;y=py;pressure=.37f;setAxisValue(MotionEvent.AXIS_TILT,.41f)
            }}.toTypedArray()
            return MotionEvent.obtain(1000L,time,action,points.size,props,coords,0,0x64,1f,1f,9,0,InputDevice.SOURCE_STYLUS,0)
        }
        fun send(action:Int,time:Long,view:RecordingView,second:RecordingView?=null) {
            val raw=event(action,time,view,second)
            try {
                activity.dispatchTouchEvent(raw)
                assertEquals(action,raw.action);assertEquals(0x64,raw.buttonState)
                assertEquals(.37f,raw.pressure,0f);assertEquals(.41f,raw.getAxisValue(MotionEvent.AXIS_TILT),0f)
            } finally {raw.recycle()}
        }
        fun hover(view:RecordingView,time:Long) {
            val raw=event(MotionEvent.ACTION_HOVER_MOVE,time,view)
            try {activity.dispatchGenericMotionEvent(raw)} finally {raw.recycle()}
        }
        fun key(action:Int,code:Int,time:Long)=KeyEvent(1000L,time,action,code,0,0,42,81,0,InputDevice.SOURCE_STYLUS)
    }
    private fun resetClick(scene:Scene) {
        val cancel=scene.event(MotionEvent.ACTION_CANCEL,1000L,scene.map)
        try {StylusButtonLatch.clicks.onTouch(cancel)} finally {cancel.recycle()}
        StylusButtonLatch.releaseKey()
    }
    private fun scenario(block:(Scene)->Unit)=runParkingScenario {f->
        val file=File(f.root,"mixed-input.xmap")
        withContext(Dispatchers.IO) {f.seed();file.writeBytes(MapCodec.encode(MindMapDocument(
            nodes=listOf(MapNode(id="root",parent=null,text="map")))))}
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val maps=MapWorkspace(f.context,scope)
        var scene:Scene?=null
        try {
            withContext(Dispatchers.Main) {
                f.editor.openAsync(f.sourceUri,"source.xnote");f.stopDebounce()
                assertTrue(maps.open(Uri.fromFile(file),"map"))
            }
            withTimeout(10000L) {while(!withContext(Dispatchers.Main) {maps.visible && maps.current?.session?.document!=null}) delay(1L)}
            withContext(Dispatchers.Main) {
                assertTrue(maps.showSplit(requireNotNull(maps.current),MapSplitLayout(Pane.SECONDARY,Pane.SECONDARY,.3f,true,true)))
                val activity=Robolectric.buildActivity(MainActivity::class.java).get()
                activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar)
                val container=FrameLayout(activity).apply {isMotionEventSplittingEnabled=false}
                val map=RecordingView(activity);val native=RecordingView(activity)
                container.addView(map,FrameLayout.LayoutParams(300,-1))
                container.addView(native,FrameLayout.LayoutParams(700,-1).apply {leftMargin=300})
                activity.window.setContentView(container,ViewGroup.LayoutParams(-1,-1))
                activity.window.decorView.measure(View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.EXACTLY))
                activity.window.decorView.layout(0,0,1000,1000)
                MainActivity::class.java.getDeclaredField("editor").apply {isAccessible=true}.set(activity,f.editor)
                activity.mapWorkspace=maps
                val router=MixedInputRouter()
                val input=MixedInputBinding(router,native={f.editor.takeIf {maps.splitPresentation.layout?.focusedPane==Pane.PRIMARY}},
                    focus={target->
                        if(target.kind==MixedInputRouter.Kind.NATIVE) f.editor.focusMixedNativePane(target.pane)
                        maps.focusSplit(target.pane).also {if(it) (if(target.kind==MixedInputRouter.Kind.NATIVE) native else map).requestFocus()}
                    },blocked={maps.current?.session?.inkContact==true})
                val ready=Scene(activity,f.editor,maps,map,native,input);scene=ready
                router.place(ready.mapTarget,map.bounds());router.place(ready.nativeTarget,native.bounds())
                activity.mixedInput=input;map.requestFocus();resetClick(ready)
                block(ready)
            }
        } finally {withContext(NonCancellable) {
            val disposal=withContext(Dispatchers.Main) {
                scene?.let {s->resetClick(s);s.activity.mixedInput=null;s.activity.mapWorkspace=null;s.activity.penKeyRecorder=null
                    MainActivity::class.java.getDeclaredField("editor").apply {isAccessible=true}.set(s.activity,null)
                    s.activity.window.setContentView(View(s.activity));s.editor.resetPenButtons()}
                StylusButtonLatch.releaseKey();maps.dispose()
            }
            disposal.join();scope.cancel();scope.coroutineContext[Job]?.join()
        }}
    }

    @Test fun mapVendorActionsAndAxesStayRawAfterCrossingIntoNativePane()=scenario {s->
        var recorded=0;s.activity.penKeyRecorder={recorded++;true}
        StylusButtonLatch.clicks.onPress(1000L)
        assertTrue("The real click candidate starts before the Map stream",StylusButtonLatch.clicks.mayStillClick(1000L))
        val actions=listOf(MotionEvent.ACTION_DOWN,211,213,212,MotionEvent.ACTION_UP)
        actions.forEachIndexed {index,action->s.send(action,1001L+index,if(index==0) s.map else s.native)}
        assertEquals(actions,s.map.touches.map {it.action});assertTrue(s.native.touches.isEmpty())
        s.map.touches.forEach {assertEquals(0x64,it.buttons);assertEquals(.37f,it.pressure,0f);assertEquals(.41f,it.tilt,0f);assertEquals(listOf(7),it.ids)}
        assertTrue(StylusButtonLatch.clicks.mayStillClick(1010L))
        s.activity.dispatchKeyEvent(s.key(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY,1011L))
        assertEquals(0,recorded);assertEquals(listOf(KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY),s.map.keys)
    }

    @Test fun nativeStreamRetainsSamsungCompatibilityAndItsLatchAcrossTheSeam()=scenario {s->
        StylusButtonLatch.clicks.onPress(1000L)
        s.send(MotionEvent.ACTION_DOWN,1001L,s.native)
        s.send(213,1002L,s.map);s.send(212,1003L,s.map)
        assertEquals(listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP),s.native.touches.map {it.action})
        assertTrue(s.map.touches.isEmpty());assertFalse(StylusButtonLatch.clicks.mayStillClick(1004L))
    }

    @Test fun recordedHoldReleaseReturnsToOriginalNativeOwnerAfterMapFocus()=scenario {s->
        val old=s.editor.preferences.penShortcuts
        val down=s.key(KeyEvent.ACTION_DOWN,600,1001L)
        try {
            assertTrue(s.editor.setPenShortcuts(listOf(PenShortcut("hold",listOf(requireNotNull(PenSignals.key(down))),
                PenBinding("eraser",PenBindingMode.HOLD)))))
            s.editor.selectTool(Tool.PEN);s.focusNative()
            assertTrue(s.activity.dispatchKeyEvent(down));assertEquals(Tool.ERASER,s.editor.tool)
            assertEquals(Tool.PEN,s.editor.toolbarTool)
            s.focusMap();assertEquals(Tool.ERASER,s.editor.tool)
            assertTrue(s.activity.dispatchKeyEvent(s.key(KeyEvent.ACTION_UP,600,1002L)))
            assertEquals(Tool.PEN,s.editor.tool);assertEquals(Tool.PEN,s.editor.toolbarTool)
            assertTrue(s.map.keys.isEmpty());assertTrue(s.native.keys.isEmpty())
        } finally {s.editor.setPenShortcuts(old)}
    }

    @Test fun legacyNativeKeyReleaseStillClearsTheSharedLatchAfterMapFocus()=scenario {s->
        s.focusNative()
        assertTrue(s.activity.dispatchKeyEvent(s.key(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY,1001L)))
        val latch=StylusButtonLatch();assertTrue(latch.heldWith(0))
        s.focusMap()
        assertTrue(s.activity.dispatchKeyEvent(s.key(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY,1002L)))
        assertFalse(latch.heldWith(0));assertTrue(s.map.keys.isEmpty())
    }

    @Test fun nativeOnlyFocusInheritanceStillReceivesReleaseInBothActualEditors()=scenario {s->
        val second=s.editor.secondaryPane();second.newNote()
        val old=s.editor.preferences.penShortcuts
        val down=s.key(KeyEvent.ACTION_DOWN,600,1001L)
        try {
            assertTrue(s.editor.setPenShortcuts(listOf(PenShortcut("hold",listOf(requireNotNull(PenSignals.key(down))),
                PenBinding("eraser",PenBindingMode.HOLD)))))
            s.activity.mixedInput=null;s.activity.mapWorkspace=null
            s.editor.selectTool(Tool.PEN)
            assertTrue(s.activity.dispatchKeyEvent(down));assertEquals(Tool.ERASER,s.editor.tool)
            s.editor.focusPane(Pane.SECONDARY)
            assertEquals(Tool.ERASER,second.tool);assertEquals(Tool.PEN,second.toolbarTool)
            assertTrue(s.activity.dispatchKeyEvent(s.key(KeyEvent.ACTION_UP,600,1002L)))
            assertEquals(Tool.PEN,second.tool);assertEquals(Tool.PEN,s.editor.tool)
        } finally {s.editor.setPenShortcuts(old)}
    }

    @Test fun hoverAndSecondPointerDoNotRetargetAnAcceptedDownStream()=scenario {s->
        s.hover(s.native,1001L);assertEquals(Pane.PRIMARY,s.maps.splitPresentation.layout?.focusedPane)
        s.send(MotionEvent.ACTION_DOWN,1002L,s.map)
        s.hover(s.native,1003L);assertEquals(Pane.SECONDARY,s.maps.splitPresentation.layout?.focusedPane)
        s.send(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),1004L,s.map,s.native)
        assertEquals(s.mapTarget,s.input.router.touchOwner);assertTrue(s.native.touches.isEmpty())
        assertEquals(listOf(7,19),s.map.touches.last().ids)
        s.send(MotionEvent.ACTION_CANCEL,1005L,s.map)
        s.hover(s.native,1006L);assertEquals(Pane.PRIMARY,s.maps.splitPresentation.layout?.focusedPane)
    }
}
