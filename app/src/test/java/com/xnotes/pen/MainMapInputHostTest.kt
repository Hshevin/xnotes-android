package com.xnotes.pen

import android.net.Uri
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.xnotes.MainActivity
import com.xnotes.canvas.StylusButtonLatch
import com.xnotes.mindmap.FileNetworkWorkspace
import com.xnotes.mindmap.NetworkQuery
import com.xnotes.mindmap.MapCodec
import com.xnotes.mindmap.MapNode
import com.xnotes.mindmap.MapWorkspace
import com.xnotes.mindmap.MindMapDocument
import com.xnotes.ui.Editor
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real MainActivity dispatch -> real Window/View; its native editor and map actor are actual instances. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class MainMapInputHostTest {
    private data class Seen(val action:Int,val buttons:Int,val pressure:Float,val tilt:Float,val pointerId:Int,val source:Int)
    private class RecordingView(activity:MainActivity):View(activity) {
        val touches=mutableListOf<Seen>();val keys=mutableListOf<Int>()
        init {isFocusable=true;isFocusableInTouchMode=true;isClickable=true}
        override fun onTouchEvent(event:MotionEvent):Boolean {
            touches+=Seen(event.actionMasked,event.buttonState,event.pressure,event.getAxisValue(MotionEvent.AXIS_TILT),event.getPointerId(0),event.source)
            return true
        }
        override fun dispatchKeyEvent(event:KeyEvent):Boolean {keys+=event.keyCode;return true}
    }
    private fun event(action:Int,time:Long):MotionEvent {
        val pointer=MotionEvent.PointerProperties().apply {id=7;toolType=MotionEvent.TOOL_TYPE_STYLUS}
        val coords=MotionEvent.PointerCoords().apply {x=200f;y=220f;pressure=.37f;setAxisValue(MotionEvent.AXIS_TILT,.41f)}
        return MotionEvent.obtain(1000L,time,action,1,arrayOf(pointer),arrayOf(coords),0,0x64,1f,1f,9,0,InputDevice.SOURCE_STYLUS,0)
    }
    private fun send(activity:MainActivity,action:Int,time:Long) {
        val raw=event(action,time)
        try {
            activity.dispatchTouchEvent(raw) // The recorded child stream below proves actual delivery.
            assertEquals(action,raw.actionMasked);assertEquals(0x64,raw.buttonState);assertEquals(.37f,raw.pressure,0f)
        } finally {raw.recycle()}
    }
    private fun resetClick() {
        val cancel=event(MotionEvent.ACTION_CANCEL,1000L)
        try {StylusButtonLatch.clicks.onTouch(cancel)} finally {cancel.recycle()}
        StylusButtonLatch.releaseKey()
    }
    private fun scenario(network:Boolean=false,block:(MainActivity,Editor,RecordingView)->Unit)=runParkingScenario {f->
        val graph=File(f.root,"main-input.xmap")
        withContext(Dispatchers.IO) {
            f.seed();graph.writeBytes(MapCodec.encode(MindMapDocument(nodes=listOf(MapNode(id="root",parent=null,text="graph")))))
        }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val workspace=MapWorkspace(f.context,scope);val networks=FileNetworkWorkspace(f.context,scope)
        val binding=Any();var activity:MainActivity?=null
        try {
            withContext(Dispatchers.Main) {
                f.editor.openAsync(f.sourceUri,"source.xnote");f.addStroke(110.0,recordHistory=true);f.stopDebounce()
                if(network) {
                    val source=requireNotNull(f.editor.captureBacklinkContext())
                    networks.bindHost(binding)
                    assertNotNull(networks.admit(binding,NetworkQuery(source.documentId,"source.xnote",source.libraryRoots)))
                    assertFalse(workspace.visible);assertTrue(networks.visible)
                } else assertTrue(workspace.open(Uri.fromFile(graph),"graph"))
            }
            withTimeout(10000L) {while(!withContext(Dispatchers.Main) {if(network) networks.visible && networks.current!=null else workspace.visible && workspace.current?.session?.document!=null}) delay(1L)}
            withContext(Dispatchers.Main) {
                // No onCreate: the real dispatch path is exercised without loading the complete app UI.
                val actual=Robolectric.buildActivity(MainActivity::class.java).get().also {activity=it}
                actual.setTheme(android.R.style.Theme_Material_Light_NoActionBar)
                val window=requireNotNull(actual.window) {"Bare MainActivity must have its real attached Window"}
                val child=RecordingView(actual)
                window.setContentView(child,ViewGroup.LayoutParams(-1,-1))
                window.decorView.measure(View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.EXACTLY))
                window.decorView.layout(0,0,1000,1000);child.requestFocus()
                MainActivity::class.java.getDeclaredField("editor").apply {isAccessible=true}.set(actual,f.editor)
                actual.mapWorkspace=workspace;actual.networkWorkspace=networks
                if(network) {assertFalse(actual.mapWorkspace!!.visible);assertTrue(actual.networkWorkspace!!.visible)}
                resetClick();block(actual,f.editor,child)
            }
        } finally {withContext(NonCancellable) {
            val disposal=withContext(Dispatchers.Main) {
                resetClick()
                activity?.let {it.penKeyRecorder=null;it.mapWorkspace=null;it.networkWorkspace=null
                    MainActivity::class.java.getDeclaredField("editor").apply {isAccessible=true}.set(it,null)
                    it.window?.setContentView(View(it))}
                networks.dispose();workspace.dispose()
            }
            disposal.join();scope.cancel();scope.coroutineContext[Job]?.join()
        }}
    }

    private fun checkNativeInputIsolation(activity:MainActivity,editor:Editor,view:RecordingView) {
        val recorder=AtomicInteger();activity.penKeyRecorder={recorder.incrementAndGet();true}
        val tool=editor.tool;val model=editor.state.document;val items=model.pages.first().items.toList();val history=editor.history
        assertTrue(activity.dispatchKeyEvent(KeyEvent(1000L,1001L,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY,0)))
        assertTrue(activity.dispatchKeyEvent(KeyEvent(1000L,1002L,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY,0)))
        assertEquals(listOf(KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY,KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY),view.keys)
        assertEquals(0,recorder.get())
        StylusButtonLatch.clicks.onPress(1000L);assertTrue(StylusButtonLatch.clicks.mayStillClick(1003L))
        send(activity,MotionEvent.ACTION_DOWN,1004L);send(activity,MotionEvent.ACTION_MOVE,1005L);send(activity,MotionEvent.ACTION_UP,1006L)
        assertEquals(listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP),view.touches.map {it.action})
        assertTrue("A graphical surface contact must not turn the hidden native click candidate into a hold",StylusButtonLatch.clicks.mayStillClick(1007L))
        assertSame(model,editor.state.document);assertEquals(tool,editor.tool);assertEquals(items,model.pages.first().items)
        assertSame(history,editor.history);assertTrue(history.canUndo)
    }

    private fun checkVendorEventTransparency(activity:MainActivity,editor:Editor,view:RecordingView) {
        val model=editor.state.document;val tool=editor.tool;val items=model.pages.first().items.toList()
        // Open a genuine Window touch target first so Android itself can deliver the unknown raw
        // vendor actions to that same child, rather than dropping an unknown initial action.
        val actions=listOf(MotionEvent.ACTION_DOWN,211,213,212,MotionEvent.ACTION_UP)
        actions.forEachIndexed {index,action->send(activity,action,1010L+index)}
        assertEquals("Main must not normalize native Samsung actions while a graphical surface owns the Window",actions,view.touches.map {it.action})
        view.touches.forEach {seen->
            assertEquals(0x64,seen.buttons);assertEquals(.37f,seen.pressure,0f);assertEquals(.41f,seen.tilt,0f)
            assertEquals(7,seen.pointerId);assertEquals(InputDevice.SOURCE_STYLUS,seen.source)
        }
        assertSame(model,editor.state.document);assertEquals(tool,editor.tool);assertEquals(items,model.pages.first().items);assertTrue(editor.history.canUndo)
    }
    @Test fun foregroundMapTouchesCannotConsumeTheNativeSideButtonClickOrNativeKeyRecorder()=scenario(block=::checkNativeInputIsolation)
    @Test fun foregroundMapReceivesProprietaryPenActionsAndRawAxesWithoutNativeSamsungRewriting()=scenario(block=::checkVendorEventTransparency)
    @Test fun foregroundNetworkTouchesCannotConsumeTheNativeSideButtonClickOrNativeKeyRecorder()=scenario(network=true,block=::checkNativeInputIsolation)
    @Test fun foregroundNetworkReceivesProprietaryPenActionsAndRawAxesWithoutNativeSamsungRewriting()=scenario(network=true,block=::checkVendorEventTransparency)

}
