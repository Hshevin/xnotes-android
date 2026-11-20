package com.xnotes.mindmap

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import com.xnotes.core.geometry.Pt
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Two fingers zoom the editable graph the same way they zoom notes and canvases. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class MapPinchZoomTest {
    private class Fixture(val view:MindMapView) {
        private val down=SystemClock.uptimeMillis();private var t=0L
        fun event(action:Int,vararg points:Pt) {
            val props=points.indices.map {i->MotionEvent.PointerProperties().apply {id=i;toolType=MotionEvent.TOOL_TYPE_FINGER}}.toTypedArray()
            val coords=points.map {p->MotionEvent.PointerCoords().apply {x=p.x.toFloat();y=p.y.toFloat();pressure=1f;size=.1f}}.toTypedArray()
            t+=16
            val e=MotionEvent.obtain(down,down+t,action,points.size,props,coords,0,0,1f,1f,1,0,InputDevice.SOURCE_TOUCHSCREEN,0)
            try {view.dispatchTouchEvent(e)} finally {e.recycle()}
        }
        /** Spread two fingers symmetrically around [c] from [from] to [to] half-span. */
        fun pinch(c:Pt,from:Double,to:Double,steps:Int=12) {
            val a={s:Double->Pt(c.x-s,c.y)};val b={s:Double->Pt(c.x+s,c.y)}
            event(MotionEvent.ACTION_DOWN,a(from))
            event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),a(from),b(from))
            for(k in 1..steps) {val s=from+(to-from)*k/steps;event(MotionEvent.ACTION_MOVE,a(s),b(s))}
            event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),a(to),b(to))
            event(MotionEvent.ACTION_UP,a(to))
        }
    }
    private fun withView(block:(MindMapView,Fixture)->Unit) {
        val host=Robolectric.buildActivity(android.app.Activity::class.java).setup().visible()
        try {
            val activity=host.get();val container=android.widget.FrameLayout(activity)
            val view=MindMapView(activity).apply {
                document=MindMapDocument(nodes=listOf(MapNode(id="root",parent=null,text="Topic",x=-100.0,y=-36.0,width=200.0),
                    MapNode(id="child",parent="root",text="Child",x=200.0,y=-36.0,width=160.0)))
            }
            container.addView(view,android.widget.FrameLayout.LayoutParams(2000,1200));activity.setContentView(container)
            view.measure(android.view.View.MeasureSpec.makeMeasureSpec(2000,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(1200,android.view.View.MeasureSpec.EXACTLY))
            view.layout(0,0,2000,1200);view.fit()
            block(view,Fixture(view))
        } finally {host.pause().stop().destroy()}
    }

    @Test fun twoCloseFingersPanAndScaleAboutTheirMovingCenter()=withView {view,f->
        val center=Pt(1500.0,1000.0)
        val before=view.viewport()
        val anchor=Pt((center.x-before.panX)/view.contentScale(),(center.y-before.panY)/view.contentScale())
        f.event(MotionEvent.ACTION_DOWN,center-Pt(20.0,0.0))
        f.event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            center-Pt(20.0,0.0),center+Pt(20.0,0.0))
        val end=center+Pt(80.0,-40.0)
        f.event(MotionEvent.ACTION_MOVE,end-Pt(30.0,0.0),end+Pt(30.0,0.0))
        assertEquals(before.zoom*1.5f,view.viewport().zoom,.01f)
        assertEquals(end.x,view.screenPoint(anchor).x,.01)
        assertEquals(end.y,view.screenPoint(anchor).y,.01)
    }

    @Test fun spreadingTwoFingersOnBlankPaperZoomsIn()=withView {view,f->
        val before=view.viewport().zoom
        f.pinch(Pt(1500.0,1000.0),150.0,450.0)
        assertTrue("zoom ${view.viewport().zoom} should exceed $before",view.viewport().zoom>before*1.5f)
    }
    @Test fun spreadingTwoFingersStartingOnANodeZoomsInWithoutMovingIt()=withView {view,f->
        val before=view.viewport().zoom;val doc=view.document
        var changes=0;view.onChange={changes++}
        f.pinch(view.screenPoint(doc.nodes.first().bounds.center),60.0,360.0)
        assertTrue("zoom ${view.viewport().zoom} should exceed $before",view.viewport().zoom>before*1.5f)
        assertEquals(0,changes)
    }
    @Test fun hostSelectionUpdatesDuringAPinchDoNotStopIt()=withView {view,f->
        // The Compose host re-applies document and selection on every viewport report.
        val doc=view.document
        view.onSelect={id->view.setSelectedNodes(setOfNotNull(id))}
        view.onViewportChanged={view.document=doc;view.setSelectedNodes(emptySet());view.documentRevision=view.documentRevision}
        val before=view.viewport().zoom
        f.pinch(view.screenPoint(doc.nodes.first().bounds.center),60.0,360.0)
        assertTrue("zoom ${view.viewport().zoom} should exceed $before",view.viewport().zoom>before*1.5f)
    }
}
