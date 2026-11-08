package com.xnotes.mindmap

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.xnotes.core.geometry.Pt
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class MapInputHostTest : MapInputContract() {
    override fun ui(block: (Context) -> Unit) { block(RuntimeEnvironment.getApplication()) }

    private fun retiredRelationPressDoesNotRunALongPress(interrupt:Int) {
        val host=Robolectric.buildActivity(android.app.Activity::class.java).setup().visible()
        try {
            val activity=host.get();val container=android.widget.FrameLayout(activity)
            val view=MindMapView(activity).apply {
                document=MindMapDocument(nodes=listOf(MapNode(id="a",parent=null,x=-300.0,y=-36.0,width=100.0),
                    MapNode(id="b",parent=null,free=true,x=200.0,y=-36.0,width=100.0)),
                    relations=listOf(MapRelation(id="e",from="a",to="b")))
            }
            container.addView(view);activity.setContentView(container)
            view.layout(0,0,1000,600);view.fit();assertTrue(view.isAttachedToWindow)
            val p=view.screenPoint(if(interrupt==4) view.document.node("a").bounds.center else Pt(0.0,0.0));val palm=Pt(30.0,40.0)
            var edits=0;var commits=0;view.onEdit={edits++};view.onChange={commits++}
            val down=SystemClock.uptimeMillis();var sequence=0L
            fun event(action:Int,ids:List<Int>,types:List<Int>,points:List<Pt>) {
                val props=types.mapIndexed {i,type->MotionEvent.PointerProperties().apply {id=ids[i];toolType=type}}.toTypedArray()
                val coords=points.map {point->MotionEvent.PointerCoords().apply {x=point.x.toFloat();y=point.y.toFloat();pressure=.7f}}.toTypedArray()
                val e=MotionEvent.obtain(down,down+sequence++,action,ids.size,props,coords,0,0,1f,1f,888,0,InputDevice.SOURCE_STYLUS,0)
                try {view.onTouchEvent(e)} finally {e.recycle()}
            }
            event(MotionEvent.ACTION_DOWN,listOf(7),listOf(MotionEvent.TOOL_TYPE_STYLUS),listOf(p))
            event(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),listOf(7,2),listOf(MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.TOOL_TYPE_FINGER),listOf(p,palm))
            when(interrupt) {
                0,4->event(MotionEvent.ACTION_POINTER_UP,listOf(7,2),listOf(MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.TOOL_TYPE_FINGER),listOf(p,palm))
                1->view.onWindowFocusChanged(false)
                2->{view.isEnabled=false;view.isEnabled=true}
                else->{container.removeView(view);assertFalse(view.isAttachedToWindow)}
            }
            if(interrupt!=4) {
                view.document=MindMapDocument(nodes=listOf(MapNode(id="new",parent=null,text="New document",x=-50.0,y=-36.0,width=100.0)))
                view.documentEpoch++
            }
            val latest=MapCodec.encode(view.document)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout().toLong()+100))
            assertEquals("A retired gesture triggered a delayed node edit",0,edits)
            event(MotionEvent.ACTION_UP,listOf(2),listOf(MotionEvent.TOOL_TYPE_FINGER),listOf(palm))
            assertEquals(0,edits);assertEquals(0,commits);assertArrayEquals(latest,MapCodec.encode(view.document))
        } finally {host.pause().stop().destroy()}
    }
    @Test fun penRelationPointerUpRetiresTheDetectorBeforeADocumentReplacement()=retiredRelationPressDoesNotRunALongPress(0)
    @Test fun losingFocusRetiresTheRelationDetectorBeforeADocumentReplacement()=retiredRelationPressDoesNotRunALongPress(1)
    @Test fun disablingRetiresTheRelationDetectorBeforeADocumentReplacement()=retiredRelationPressDoesNotRunALongPress(2)
    @Test fun detachingRetiresTheRelationDetectorBeforeADocumentReplacement()=retiredRelationPressDoesNotRunALongPress(3)
    @Test fun nodePenPointerUpRetiresTheLongPressWhileThePalmRemains()=retiredRelationPressDoesNotRunALongPress(4)
}
