package com.xnotes.mindmap

import android.app.Activity
import android.graphics.Color
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.enableReferenceAnchors
import com.xnotes.core.model.fixedWriteSnapshot
import com.xnotes.format.BundleHeader
import com.xnotes.format.DocumentCodec
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual source reader/controller/View with the same detach/rebind boundary used by composition. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class SourcePanelRecreationHostTest {
    private class Fixture(val base:MapBacklinkLibraryTest.Fixture) {
        val note=Document(mutableListOf(Page(500.0,400.0)),dpi=72).apply {enableReferenceAnchors()}
        val file=File(base.notes,"panel-source.xnote")
        val source=MapSource(documentId=note.identity.documentId,scope=SourceScope.PAGE,pageId=note.pages[0].referenceIdentity.id)
        val presentation=MapPresentationState()
        val activity=Robolectric.buildActivity(Activity::class.java).setup().visible()
        val holder=FrameLayout(activity.get()).also {activity.get().setContentView(it)}
        lateinit var session:MapSession
        lateinit var map:MindMapView
        val controller get()=session.sourceTargetEdits
        val views=mutableListOf<SourceRegionVisualView>()
        var time=1L
        suspend fun start() {
            withContext(Dispatchers.IO) {
                file.outputStream().use {DocumentCodec(AndroidImageCodec(),AndroidTextMeasurer()).write(note.fixedWriteSnapshot(),it)}
                base.storage.registry.registerObserved(BundleHeader.read(file),"xnote",file.name,base.uri(file).toString())
            }
            session=base.acquire(base.uri(base.graph("panel.xmap",MindMapDocument(nodes=listOf(MapNode(id="root",parent=null),
                MapNode(id="concept",parent="root",text="Body",sources=listOf(source),primarySource=source.id)))))).session
            withContext(Dispatchers.Main) {
                presentation.synchronize(session);session.selected="concept"
                map=MindMapView(activity.get()).apply {document=session.document!!;documentEpoch=session.interactionEpoch}
                install(map);assertTrue(presentation.showSources(session,map,"concept"))
            }
        }
        fun bind()=presentation.bindSourceEditor(session,session.sourceEditSnapshot("concept")!!)
        suspend fun begin(editor:SourceListEditor,mode:SourceTargetEditMode=SourceTargetEditMode.RANGE) {
            withContext(Dispatchers.Main) {assertTrue(editor.startTargetEdit(editor.captureTargetEdit(source.id,source.target,mode)!!))}
            if(mode==SourceTargetEditMode.RANGE) {
                await {controller.state?.step==SourceTargetEditStep.VISUAL}
                withContext(Dispatchers.Main) {assertTrue(controller.chooseScope(SourceRangeScope.REGION))}
                await {controller.state?.step==SourceTargetEditStep.VISUAL && controller.state?.selectionScope==SourceRangeScope.REGION}
            }
        }
        suspend fun await(check:()->Boolean)=withTimeout(10000) {while(!withContext(Dispatchers.Main) {check()}) delay(1)}
        suspend fun awaitImage()=withTimeout(10000) {
            while(!withContext(Dispatchers.Main) {
                // The controller's render debounce follows Android Handler time in this host.
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(10,TimeUnit.MILLISECONDS)
                assertNotEquals("Actual source rendering failed",SourceTargetEditProblem.READ,controller.state?.problem)
                controller.image!=null
            }) delay(1)
        }
        fun install(view:View) {
            holder.removeAllViews();holder.addView(view,FrameLayout.LayoutParams(-1,-1))
            holder.measure(View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY))
            holder.layout(0,0,1000,800)
        }
        fun visual(token:Any):SourceRegionVisualView {
            val shown=controller.state!!;val frame=shown.frame!!
            assertTrue(controller.attachVisualHost(token,frame))
            return SourceRegionVisualView(activity.get()).also {view->
                views+=view
                view.bind(frame,frame.bounds,frame.initialRegion,shown.region,true,shown.camera,Color.WHITE,
                    {controller.visualCurrent(token,frame)},{controller.setRegion(frame,it)},
                    {controller.setVisualBusy(token,frame,it)}, {viewport,camera->controller.render(viewport,token,camera)})
                install(view)
                controller.image?.let {view.image(it.bitmap,it.frame)}
                assertTrue(controller.render(view.viewport.visibleContentRect(),token,view.camera()))
            }
        }
        suspend fun adoptImage(view:SourceRegionVisualView) {
            awaitImage()
            withContext(Dispatchers.Main) {
                val image=controller.image
                assertNotNull("The actual source render must succeed before input: ${controller.state?.problem}",image)
                view.image(image!!.bitmap,image.frame)
            }
        }
        fun touch(view:SourceRegionVisualView,action:Int,at:Pt) {
            val p=MotionEvent.PointerProperties().apply {id=7;toolType=MotionEvent.TOOL_TYPE_STYLUS}
            val c=MotionEvent.PointerCoords().apply {x=at.x.toFloat();y=at.y.toFloat();pressure=.6f}
            val event=MotionEvent.obtain(1,time++,action,1,arrayOf(p),arrayOf(c),0,0,1f,1f,0,0,InputDevice.SOURCE_STYLUS,0)
            try {assertTrue(view.onTouchEvent(event))} finally {event.recycle()}
        }
        suspend fun finish()=withContext(NonCancellable+Dispatchers.Main) {
            views.forEach {it.retire()};holder.removeAllViews();presentation.closeSources()
            if(this@Fixture::session.isInitialized) {controller.close();session.cancelPendingAutosave()}
            activity.pause().stop().destroy()
        }
    }
    private fun scenario(block:suspend(Fixture)->Unit)=MapBacklinkLibraryTest().scenario {base->
        val f=withContext(Dispatchers.Main) {Fixture(base)}
        try {f.start();block(f)} finally {f.finish()}
    }
    @Test fun parentUnmountAndRebindKeepTheSameReadFrameDraftAndCameraButRetireOldFacadeAndPendingUp()=scenario {f->
        val first=withContext(Dispatchers.Main) {f.bind()};f.begin(first)
        val stable=Rect(40.0,60.0,120.0,100.0);val camera=SourceRegionCamera(250.0,200.0,.75)
        val token=Any()
        lateinit var oldView:SourceRegionVisualView
        lateinit var frame:SourceRangeFrame
        lateinit var requestId:String
        lateinit var oldEnd:Pt
        lateinit var replacement:SourceRegionVisualView
        lateinit var next:SourceListEditor
        withContext(Dispatchers.Main) {
            frame=f.controller.state!!.frame!!;requestId=f.controller.state!!.id
            assertTrue(f.controller.setRegion(frame,stable));assertTrue(f.controller.attachVisualHost(token,frame))
            assertTrue(f.controller.render(frame.bounds,token,camera))
        }
        f.awaitImage()
        withContext(Dispatchers.Main) {
            assertNotNull("The actual initial source render must succeed",f.controller.image)
            oldView=f.visual(token)
            val down=oldView.viewport.contentToViewport(Pt(stable.centerX,stable.centerY))
            oldEnd=Pt(down.x+80,down.y+70)
            f.touch(oldView,MotionEvent.ACTION_DOWN,down);f.touch(oldView,MotionEvent.ACTION_MOVE,oldEnd)
            assertTrue(f.controller.visualBusy);assertEquals(stable,f.controller.state!!.region)
            // The parent's old composition leaves before a replacement exists.
            first.detach();f.controller.detachVisualHost(token);f.holder.removeAllViews()
            assertEquals(requestId,f.controller.state!!.id);assertSame(frame,f.controller.state!!.frame)
            assertEquals(stable,f.controller.state!!.region);assertEquals(camera,f.controller.state!!.camera)
            assertNull(first.captureNavigation(f.source.id,f.source.target));assertFalse(first.note(f.source.id,"Late callback"))
            next=f.bind();assertNotSame(first,next)
            replacement=f.visual(Any())
            assertEquals(camera,replacement.camera())
        }
        f.adoptImage(replacement)
        withContext(Dispatchers.Main) {
            f.touch(oldView,MotionEvent.ACTION_UP,oldEnd)
            first.close();first.detach() // Late old dismissal/disposal cannot cancel the new host.
            assertEquals(requestId,f.controller.state!!.id);assertSame(frame,f.controller.state!!.frame)
            assertEquals(stable,f.controller.state!!.region);assertFalse(f.controller.visualBusy)
            next.close();assertNull(f.controller.state);assertFalse(f.controller.hasPending)
            assertEquals(f.source.target,f.session.document!!.node("concept").sources.single().target)
            assertFalse(f.session.canUndo)
        }
        assertEquals(0,f.base.writes.get())
    }
    @Test fun anOriginalPendingPickerSurvivesHostReplacementThenAnExplicitNewHostCloseRetiresOnlyItsRequest()=scenario {f->
        val first=withContext(Dispatchers.Main) {f.bind()};f.begin(first,SourceTargetEditMode.REPLACE)
        withContext(Dispatchers.Main) {
            val id=f.controller.state!!.id;val slot=f.controller.capturePicker()!!
            first.detach();val next=f.bind();first.close();first.detach()
            assertEquals(id,f.controller.state!!.id);assertSame(slot,f.controller.pendingPicker)
            assertNull(first.captureTargetEdit(f.source.id,f.source.target,SourceTargetEditMode.REPLACE))
            next.close();f.presentation.closeSources()
            assertNull(f.controller.state);assertSame(slot,f.controller.pendingPicker);assertTrue(f.controller.hasPending)
            // A retired slot consumes the actual late URI without loading or mutating it.
            assertTrue(f.controller.receivePicker(slot,f.base.uri(f.file)));assertFalse(f.controller.hasPending)
            assertTrue(f.presentation.showSources(f.session,f.map,"concept"))
            val newest=f.bind();val ticket=newest.captureTargetEdit(f.source.id,f.source.target,SourceTargetEditMode.REPLACE)!!
            assertTrue(newest.startTargetEdit(ticket));val replacementId=f.controller.state!!.id
            first.close();next.close();first.detach();next.detach()
            assertEquals(replacementId,f.controller.state!!.id)
            newest.close();assertNull(f.controller.state);assertFalse(f.session.canUndo)
        }
        assertEquals(0,f.base.writes.get())
    }
    @Test fun actualEntryCloseReplacementAndDisposalRetireItsPanelEvenWhileAnotherLeaseKeepsTheSessionAlive()=scenario {f->
        for(action in listOf("close","replace","dispose")) {
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
            val workspace=MapWorkspace(f.base.context,scope)
            try {
                withContext(Dispatchers.Main) {assertTrue(workspace.open(f.base.leases.single().uri,"Shared source panel"))}
                f.await {workspace.visible && !workspace.opening}
                val entry=withContext(Dispatchers.Main) {workspace.current!!}
                assertSame(f.session,entry.session)
                val owner=withContext(Dispatchers.Main) {
                    entry.presentation.synchronize(f.session)
                    assertTrue(entry.presentation.showSources(f.session,f.map,"concept"))
                    entry.presentation.bindSourceEditor(f.session,f.session.sourceEditSnapshot("concept")!!)
                }
                f.begin(owner)
                when(action) {
                    "close"->withContext(Dispatchers.Main) {workspace.close(entry)}?.join()
                    "replace"->{
                        val other=withContext(Dispatchers.IO) {f.base.graph("replacement.xmap",MindMapDocument(nodes=listOf(MapNode(id="other",parent=null))))}
                        withContext(Dispatchers.Main) {assertTrue(workspace.open(f.base.uri(other),"Replacement"))}
                        f.await {workspace.visible && !workspace.opening && workspace.current!==entry}
                    }
                    else->withContext(Dispatchers.Main) {workspace.dispose()}.join()
                }
                withContext(Dispatchers.Main) {
                    assertTrue("The independent lease still owns the actual session",f.base.leases.single().alive)
                    assertNotNull(f.session.document);assertNull(f.controller.state)
                    assertNull(owner.captureTargetEdit(f.source.id,f.source.target,SourceTargetEditMode.REPLACE))
                    owner.detach();owner.close();assertFalse(f.session.canUndo)
                }
            } finally {withContext(Dispatchers.Main) {workspace.dispose()}.join();scope.cancel()}
        }
        assertEquals(0,f.base.writes.get())
    }
    @Test fun explicitlyClosingThePanelInTheDetachedGapRetiresTheStableOwnerAndCannotReviveOnRebind()=scenario {f->
        val first=withContext(Dispatchers.Main) {f.bind()};f.begin(first)
        withContext(Dispatchers.Main) {
            val old=f.controller.state!!.id;first.detach();f.presentation.closeSources()
            assertNull(f.controller.state)
            assertTrue(f.presentation.showSources(f.session,f.map,"concept"))
            val next=f.bind();assertTrue(next.startTargetEdit(next.captureTargetEdit(f.source.id,f.source.target,SourceTargetEditMode.REPLACE)!!))
            assertNotEquals(old,f.controller.state!!.id)
            first.close();first.detach();assertNotNull(f.controller.state)
            next.close();assertNull(f.controller.state);assertFalse(f.session.canUndo)
        }
        assertEquals(0,f.base.writes.get())
    }
}
