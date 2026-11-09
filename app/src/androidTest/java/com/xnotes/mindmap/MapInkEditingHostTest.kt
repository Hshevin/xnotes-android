package com.xnotes.mindmap

import android.content.Intent
import android.net.Uri
import android.view.MotionEvent
import android.view.InputDevice
import android.view.View
import android.view.ViewGroup
import com.xnotes.pen.PenTestRunner
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real Compose/AndroidView host regression. Compile on host; execute only with explicit device authorization. */
class MapInkEditingHostTest {
    private val runner get()=PenTestRunner.instance
    private fun <T> main(block:()->T):T {
        var result:Any?=null;var failure:Throwable?=null
        runner.runOnMainSync {try {result=block()} catch(t:Throwable) {failure=t}}
        failure?.let {throw it}
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun views(root:View):List<View> = listOf(root)+(if(root is ViewGroup) (0 until root.childCount).flatMap {views(root.getChildAt(it))} else emptyList())
    private fun waitUntil(condition:()->Boolean) {
        repeat(500) {runner.waitForIdleSync();if(condition()) return;Thread.sleep(10)}
        fail("Editor host did not settle")
    }
    private fun send(v:MindMapView,action:Int,x:Float,y:Float,time:Long) = main {
        val p=MotionEvent.PointerProperties().apply {id=7;toolType=MotionEvent.TOOL_TYPE_STYLUS}
        val c=MotionEvent.PointerCoords().apply {this.x=x;this.y=y;pressure=.7f}
        val e=MotionEvent.obtain(100,time,action,1,arrayOf(p),arrayOf(c),0,0,1f,1f,123,0,InputDevice.SOURCE_STYLUS,0)
        try {v.onTouchEvent(e)} finally {e.recycle()}
    }
    @Test fun firstUnselectedInkStrokeSurvivesRecompositionAndEnlargedReturnKeepsView() {
        val context=runner.targetContext
        val doc=MindMapDocument(nodes=listOf(MapNode(id="text",parent=null,text="Main"),MapNode(id="ink",parent=null,kind=NodeKind.INK,free=true,x=600.0)))
        val file=File.createTempFile("ink-host-",".xmap",context.cacheDir).apply {writeBytes(MapCodec.encode(doc))}
        val activity=runner.startActivitySync(MindMapActivity.intent(context,Uri.fromFile(file).toString(),"Ink host").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MindMapActivity
        try {
            val session=main {activity.documentSession}
            waitUntil {main {!session.loading && views(activity.window.decorView).any {it is MindMapView && it.height>0}}}
            val graph=main {views(activity.window.decorView).filterIsInstance<MindMapView>().single()}
            main {graph.centerOn("ink")}
            val before=main {graph.viewport()}
            val height=main {graph.height}
            val point=main {graph.screenPoint(doc.node("ink").bounds.center)}
            send(graph,MotionEvent.ACTION_DOWN,point.x.toFloat(),point.y.toFloat(),100)
            runner.waitForIdleSync()
            assertEquals(height,main {graph.height})
            assertTrue(main {graph.inkInput.active})
            assertEquals(before,main {graph.viewport()})
            send(graph,MotionEvent.ACTION_UP,point.x.toFloat()+20,point.y.toFloat()+20,110)
            assertEquals(1,main {session.document!!.node("ink").ink.size})
            val saved=main {session.document}
            main {graph.onEdit("ink")}
            waitUntil {main {views(activity.window.decorView).any {it is InkPadView}}}
            assertSame(graph,main {views(activity.window.decorView).filterIsInstance<MindMapView>().single()})
            assertFalse(main {graph.isEnabled})
            main {activity.onBackPressedDispatcher.onBackPressed()}
            waitUntil {main {views(activity.window.decorView).none {it is InkPadView}}}
            assertTrue(main {graph.isEnabled})
            assertEquals(saved,main {session.document})
            assertEquals("ink",main {session.selected})
            assertEquals(before,main {graph.viewport()})
        } finally {main {activity.finish()}}
    }
}
