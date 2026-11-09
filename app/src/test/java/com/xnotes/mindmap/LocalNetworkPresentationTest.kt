package com.xnotes.mindmap

import android.app.Activity
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import com.xnotes.core.geometry.Pt
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Actual attached native camera consumers; the Compose two-frame wait has separate host coverage. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class LocalNetworkPresentationTest {
    private class Fixture:AutoCloseable {
        val host=Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity=host.get()
        val presentation=LocalNetworkPresentation()
        val focus="00000000-0000-0000-0000-000000000001"
        val graph="00000000-0000-0000-0000-000000000002"
        val target="00000000-0000-0000-0000-000000000003"
        val location=BacklinkLocation(Uri.parse("content://network-presentation/document/map"),emptySet(),"scene:map",graph,"Graph.xmap")
        val key=NetworkConceptKey(location.key,graph,"concept")
        val owner=Any()
        fun row(id:String,file:String)=MapBacklinkRow(location,"concept",id,MapSourceTarget(file,SourceScope.FILE),
            "Concept",NodeKind.TEXT,"Source.xnote","",null,SourceStatus.UNVERIFIED,null,BacklinkOrigin.STORED)
        val report=LocalFileNetworkReport(focus,listOf(LocalNetworkFile(focus,listOf("Source.xnote")),LocalNetworkFile(graph,listOf("Graph.xmap")),
            LocalNetworkFile(target,listOf("Target.xnote"))),listOf(LocalNetworkConcept(key,location,"Concept",NodeKind.TEXT,
                listOf(LocalNetworkEvidence(row("s",focus),owner,0),LocalNetworkEvidence(row("t",target),owner,0)))),emptyList(),1,true)
        val scene=LocalNetworkSceneBuilder().build(report,LocalNetworkLabels("File","Concept","Ink","Reference","References %d","Belongs"))
        var ime=0
        init {presentation.visible=true;presentation.reconcile(scene)}
        data class Bound(val view:MindMapView,val token:LocalNetworkAttachment)
        fun geometry(v:MindMapView)=LocalNetworkGeometry(v.width,v.height,v.resources.displayMetrics.density)
        fun camera(v:MindMapView)=LocalNetworkCamera(v.viewport(),v.width,v.height,v.resources.displayMetrics.density)
        fun mount(width:Int=1000,height:Int=700,automaticFit:Boolean=false):Bound {
            val view=MindMapView(activity).apply {document=scene.document;readOnlyNavigation=true;automaticInitialFit=automaticFit}
            activity.setContentView(view);shadowOf(Looper.getMainLooper()).idle()
            view.layout(0,0,width,height)
            assertTrue(view.isAttachedToWindow)
            val token=presentation.attach();view.documentEpoch=token.epoch
            view.onGraphContact={presentation.graphContact(token)}
            view.onReadOnlyContact={presentation.contact(token,it)}
            view.onSelect={id->if(presentation.attachmentCurrent(token)) presentation.selectNode(id?.let(scene.nodes::get))}
            view.onSelectRelation={id->if(presentation.attachmentCurrent(token)) presentation.selectEdge(id?.let(scene.edges::get))}
            view.onReadOnlyRelationCandidates={ids->if(presentation.attachmentCurrent(token))
                presentation.selectEdge(view.selectedRelation?.let(scene.edges::get),ids.mapNotNull(scene.edges::get))}
            view.onViewportChanged={presentation.layout(token,geometry(view),ime);presentation.captureCamera(token,camera(view),ime)}
            presentation.layout(token,geometry(view),ime)
            return Bound(view,token)
        }
        /** This is the final consumer step after the host has separately observed two stable frames. */
        fun settle(bound:Bound):LocalNetworkCameraPlan {
            val plan=presentation.prepareCamera(bound.token,geometry(bound.view),ime)!!
            assertTrue(applyLocalNetworkCamera(presentation,bound.view,plan,ime))
            return plan
        }
        fun center(v:MindMapView):Pt {
            val p=v.viewport();val scale=v.contentScale()
            return Pt((v.width/2.0-p.panX)/scale,(v.height/2.0-p.panY)/scale)
        }
        override fun close() {presentation.visible=false;host.pause().stop().destroy()}
    }
    private var tick=0L
    private fun touch(v:MindMapView,action:Int,p:Pt) {
        val now=SystemClock.uptimeMillis()
        val props=arrayOf(MotionEvent.PointerProperties().apply {id=7;toolType=MotionEvent.TOOL_TYPE_STYLUS})
        val coords=arrayOf(MotionEvent.PointerCoords().apply {x=p.x.toFloat();y=p.y.toFloat();pressure=.7f})
        val event=MotionEvent.obtain(now,now+tick++,action,1,props,coords,0,0,1f,1f,888,0,InputDevice.SOURCE_STYLUS,0)
        try {assertTrue(v.onTouchEvent(event))} finally {event.recycle()}
    }
    @Test fun explicitHostCameraOwnershipDefersFirstFitAndConsumesTheActualPlanOnlyOnce() {
        Fixture().use {f->
            val bound=f.mount();val v=bound.view
            assertEquals(MindMapView.Viewport(1f,0f,0f),v.viewport());assertFalse(f.presentation.cameraReady)
            val bytes=MapCodec.encode(v.document);val plan=f.settle(bound)
            assertNotEquals(MindMapView.Viewport(1f,0f,0f),v.viewport());assertTrue(f.presentation.cameraReady)
            assertNull(f.presentation.prepareCamera(bound.token,f.geometry(v)))
            assertFalse(applyLocalNetworkCamera(f.presentation,v,plan));assertArrayEquals(bytes,MapCodec.encode(v.document))
        }
        Fixture().use {f->assertNotEquals(MindMapView.Viewport(1f,0f,0f),f.mount(automaticFit=true).view.viewport())}
    }
    @Test fun realCameraAndDomainSelectionSurviveNewViewSizeWhileOldTokensCannotOverwriteThem() {
        Fixture().use {f->
            val first=f.mount();f.settle(first);first.view.zoomBy(1.35f);first.view.panText(93f,-47f)
            val selected=NetworkSceneNodeKey.Concept(f.key);assertTrue(f.presentation.selectNode(selected))
            val world=f.center(first.view);val zoom=first.view.viewport().zoom
            f.presentation.detach(first.token)
            val next=f.mount(800,600);f.settle(next)
            assertEquals(world.x,f.center(next.view).x,.001);assertEquals(world.y,f.center(next.view).y,.001)
            assertEquals(zoom,next.view.viewport().zoom,1e-6f);assertEquals(selected,f.presentation.selectedNode)
            val normal=f.presentation.camera
            first.view.panText(200f,150f)
            assertFalse(f.presentation.captureCamera(first.token,f.camera(first.view)));assertFalse(f.presentation.detach(first.token))
            assertTrue(f.presentation.attachmentCurrent(next.token));assertEquals(normal,f.presentation.camera)
        }
    }
    @Test fun aRealFreshDownRetiresInputAndPendingRestorePermanentlyEvenAfterUp() {
        Fixture().use {f->
            val first=f.mount();f.settle(first);first.view.panText(100f,70f)
            val idle=f.presentation.captureInput()!!;assertTrue(idle.current())
            val point=first.view.screenPoint(first.view.document.root.bounds.center)
            touch(first.view,MotionEvent.ACTION_DOWN,point)
            assertFalse(idle.current());assertFalse(f.presentation.idle);assertNull(f.presentation.captureInput())
            touch(first.view,MotionEvent.ACTION_UP,point);assertTrue(f.presentation.idle);assertFalse(idle.current())
            f.presentation.detach(first.token)
            val next=f.mount(800,600)
            val pending=f.presentation.prepareCamera(next.token,f.geometry(next.view))!!
            val fresh=next.view.screenPoint(next.view.document.root.bounds.center)
            touch(next.view,MotionEvent.ACTION_DOWN,fresh)
            touch(next.view,MotionEvent.ACTION_MOVE,fresh+Pt(80.0,30.0));touch(next.view,MotionEvent.ACTION_UP,fresh+Pt(80.0,30.0))
            val userCamera=next.view.viewport()
            assertFalse(applyLocalNetworkCamera(f.presentation,next.view,pending))
            f.settle(next);assertEquals("The new plan must adopt the user's camera without a late restore",userCamera,next.view.viewport())
            val again=f.presentation.captureInput()!!;assertTrue(again.current())
            val serial=f.presentation.inputSerial
            touch(next.view,MotionEvent.ACTION_CANCEL,fresh)
            assertTrue(f.presentation.inputSerial>serial);assertFalse(again.current())
            val current=f.presentation.captureInput()!!
            next.view.onWindowFocusChanged(false);assertFalse(current.current())
            f.presentation.detach(next.token);assertNull(f.presentation.captureInput())
        }
    }
    @Test fun hiddenAndImeGeometryNeverReplaceTheLastNormalCameraOrReviveAnOldView() {
        Fixture().use {f->
            val first=f.mount();f.settle(first);first.view.panText(90f,40f)
            val old=f.presentation.camera!!;f.presentation.visible=false
            first.view.panText(150f,-30f);assertEquals(old,f.presentation.camera)
            f.presentation.visible=true
            val next=f.mount(800,600)
            val pending=f.presentation.prepareCamera(next.token,f.geometry(next.view),0)!!
            val before=next.view.viewport()
            // Insets can change without a native resize/graph contact. A→B→A must still retire the old plan.
            f.presentation.layout(next.token,f.geometry(next.view),180)
            f.presentation.layout(next.token,f.geometry(next.view),0)
            assertFalse(applyLocalNetworkCamera(f.presentation,next.view,pending,0));assertEquals(before,next.view.viewport())
            f.settle(next);val normal=f.presentation.camera!!;val world=f.center(next.view)
            f.ime=180;next.view.layout(0,0,800,300)
            assertFalse(f.presentation.captureCamera(next.token,f.camera(next.view),180));assertEquals(normal,f.presentation.camera)
            assertFalse(f.presentation.captureCamera(next.token,f.camera(next.view),0));assertEquals(normal,f.presentation.camera)
            f.ime=0;next.view.layout(0,0,800,600);f.settle(next)
            assertEquals(world.x,f.center(next.view).x,.001);assertEquals(world.y,f.center(next.view).y,.001)
            assertFalse(f.presentation.captureCamera(first.token,f.camera(first.view)))
            assertFalse(f.presentation.detach(first.token));assertTrue(f.presentation.attachmentCurrent(next.token))
        }
    }
}
