package com.xnotes.pen

import android.app.Activity
import android.net.Uri
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.xnotes.core.geometry.Pt
import com.xnotes.mindmap.*
import com.xnotes.persistNativeForMapPresentation
import com.xnotes.ui.Editor
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real native owners, map actor, Main presentation consumer and readonly network input capability. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class MapNetworkNavigatorHostTest {
    private class Fixture(val native:EditorPageCopyFixture) {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val workspace=MapWorkspace(native.context,scope)
        val binding=Any();val owner=Any()
        val file=File(com.xnotes.platform.AppStorageDocumentsProvider.rootDir(native.context),"network-nav-${UUID.randomUUID()}.xmap")
        lateinit var lease:MapDocumentLease
        lateinit var navigator:MapBacklinkNavigator
        lateinit var query:NetworkQuery
        var presented=0
        val session get()=lease.session
        suspend fun start() {
            val secondary=openParkingPanes(native)
            val sources=withContext(Dispatchers.Main) {
                val s=native.editor.state.document;val t=secondary.state.document
                assertNotEquals("The native source files really have independent identities",s.identity.documentId,t.identity.documentId)
                query=NetworkQuery(s.identity.documentId,"Original center S",emptyList())
                listOf(MapSource(documentId=s.identity.documentId,fileName="S.xnote",scope=SourceScope.PAGE,pageId=s.pages[0].referenceIdentity.id),
                    MapSource(documentId=t.identity.documentId,fileName="T.xnote",scope=SourceScope.PAGE,pageId=t.pages[1].referenceIdentity.id))
            }
            file.writeBytes(MapCodec.encode(MindMapDocument(nodes=listOf(MapNode(id="root",parent=null,text="Root",collapsed=true),
                MapNode(id="concept",parent="root",text="S and T share this concept",sources=sources,primarySource=sources[0].id)))))
            withContext(Dispatchers.Main) {
                workspace.bindHost(binding)
                lease=MapDocuments.acquire(native.context,Uri.fromFile(file))
                navigator=MapBacklinkNavigator(native.editor,workspace,binding,scope,onPresented={presented++})
            }
            await {!session.loading};assertFalse(session.loadFailed)
        }
        fun row(index:Int):MapBacklinkRow {
            val doc=session.document!!;val source=doc.node("concept").sources[index]
            val observation=MapDocuments.observations(native.context).single {it.session===session}
            return MapBacklinkRow(BacklinkLocation(Uri.fromFile(file),observation.knownAliases,
                MapSaves.key(Uri.fromFile(file)),doc.id,file.name),"concept",source.id,source.target,"S and T share this concept",NodeKind.TEXT,
                source.fileName,source.note,source.lastKnownPage,source.status,null,BacklinkOrigin.LIVE,
                membership=requireNotNull(observation.captureMembership("concept",source.id,source.target)))
        }
        fun request()=MapNetworkOpenRequest.capture(query,row(0),row(1),owner) {true}!!
        suspend fun await(check:()->Boolean)=withTimeout(10000L) {while(!withContext(Dispatchers.Main) {check()}) delay(1)}
        fun references():Int {
            val field=MapDocumentLease::class.java.getDeclaredField("entry").apply {isAccessible=true}
            return (field.get(lease) as MapDocuments.Entry).references
        }
        suspend fun finish()=withContext(NonCancellable) {
            val jobs=withContext(Dispatchers.Main) {
                if(this@Fixture::navigator.isInitialized) navigator.dispose()
                listOfNotNull(workspace.dispose(),if(this@Fixture::lease.isInitialized) lease.close() else null)
            }
            jobs.forEach {it.join()};scope.cancel();file.delete()
        }
    }
    private fun scenario(block:suspend (Fixture)->Unit)=runParkingScenario {native->
        val f=Fixture(native);try {f.start();block(f)} finally {f.finish()}
    }
    private fun stopDebounce(editor:Editor) {
        (Editor::class.java.getDeclaredField("noteDebounceJob").apply {isAccessible=true}.get(editor) as? Job)?.cancel()
    }
    private var time=100L
    private fun networkPen(view:MindMapView,action:Int) {
        val pointer=MotionEvent.PointerProperties().apply {id=5;toolType=MotionEvent.TOOL_TYPE_STYLUS}
        val coordinates=MotionEvent.PointerCoords().apply {x=10f;y=10f;pressure=.7f}
        val event=MotionEvent.obtain(100,time++,action,1,arrayOf(pointer),arrayOf(coordinates),0,0,1f,1f,0,0,InputDevice.SOURCE_STYLUS,0)
        try {assertTrue(view.onTouchEvent(event))} finally {event.recycle()}
    }

    @Test fun theNetworkFocusesTheActualSecondaryTSourceAndTheMainConsumerDoesNotPersistEitherDirtyNativeOwner()=scenario {f->
        val first=f.native.editor;val second=first.secondary!!
        val sourceBytes=f.native.source.readBytes();val targetBytes=f.native.target.readBytes();val mapBytes=f.file.readBytes()
        val sourceModel=first.state.document;val targetModel=second.state.document
        val firstHistory=first.history;val secondHistory=second.history
        val map=f.session.document!!;var committed=0
        withContext(Dispatchers.Main) {
            for(pane in listOf(first,second)) {
                parkingPen(pane,MotionEvent.ACTION_DOWN,Pt(100.0,100.0));parkingPen(pane,MotionEvent.ACTION_MOVE,Pt(120.0,110.0))
                parkingPen(pane,MotionEvent.ACTION_UP,Pt(140.0,120.0));stopDebounce(pane)
            }
            first.state.zoom=1.25;first.state.scrollX=17.0;first.state.scrollY=43.0
            second.state.zoom=1.1;second.state.scrollX=9.0;second.state.scrollY=21.0
            val requested=f.request()
            assertEquals(sourceModel.identity.documentId,requested.query.focusId)
            assertEquals(targetModel.identity.documentId,requested.target.target.documentId)
            assertNotEquals(requested.anchor.target,requested.target.target)
            assertTrue(f.navigator.openNetwork(requested) {entry->
                assertFalse("The actual network producer opts out of the normal map native flush",entry.flushNativeOnPresent)
                persistNativeForMapPresentation(first,entry);committed++
            })
        }
        f.await {!f.navigator.busy}
        withContext(Dispatchers.Main) {
            val entry=f.workspace.current!!;val focus=entry.presentation.pendingBacklinkFocus!!
            assertTrue(f.workspace.visible);assertEquals(1,committed);assertEquals(1,f.presented)
            assertSame(f.session,entry.session);assertSame(map,f.session.document)
            assertEquals("concept",f.session.selected);assertEquals(map.node("concept").sources[1].id,focus.sourceId)
            assertEquals(targetModel.identity.documentId,focus.target.documentId);assertEquals(targetModel.pages[1].referenceIdentity.id,focus.target.pageId)
            assertEquals(sourceModel.identity.documentId,focus.networkAnchor!!.source.target.documentId)
            assertFalse(f.session.canUndo);assertFalse(f.session.canRedo);assertTrue(map.root.collapsed)
            assertSame(sourceModel,first.state.document);assertSame(targetModel,second.state.document)
            assertSame(firstHistory,first.history);assertSame(secondHistory,second.history)
            assertTrue(sourceModel.dirty);assertTrue(targetModel.dirty);assertTrue(first.history.canUndo);assertTrue(second.history.canUndo)
            assertEquals(Triple(1.25,17.0,43.0),Triple(first.state.zoom,first.state.scrollX,first.state.scrollY))
            assertEquals(Triple(1.1,9.0,21.0),Triple(second.state.zoom,second.state.scrollX,second.state.scrollY))
        }
        parkingDispatch()
        assertArrayEquals(sourceBytes,f.native.source.readBytes());assertArrayEquals(targetBytes,f.native.target.readBytes())
        assertArrayEquals(mapBytes,f.file.readBytes());assertEquals(0,f.native.writes.get());assertEquals(0,f.native.writeOpens.get())
    }

    @Test fun eitherSourceAbaDuringActualNativePenParkingPermanentlyRetiresTheOriginalTwoMemberHandoff()=scenario {f->
        val native=f.native.editor;val source=native.state.document;val secondary=native.secondary!!.state.document
        val bytes=f.native.source.readBytes();val otherBytes=f.native.target.readBytes()
        for(anchor in listOf(true,false)) {
            withContext(Dispatchers.Main) {
                parkingPen(native,MotionEvent.ACTION_DOWN,Pt(100.0,100.0))
                assertTrue(f.navigator.openNetwork(f.request()))
            }
            f.await {f.workspace.opening}
            withContext(Dispatchers.Main) {
                assertTrue(native.stylusInContact);assertFalse(f.workspace.visible)
                val old=f.session.document!!.node("concept").sources
                if(anchor) assertTrue(f.session.operate {doc->doc.update("concept") {node->node.copy(sources=old.drop(1),primarySource=null)}})
                else assertTrue(f.session.operate {doc->doc.update("concept") {node->node.copy(sources=old.mapIndexed {at,s->
                    if(at==1) s.copy(pageId=UUID.randomUUID().toString()) else s})}})
                f.session.undo();f.session.cancelPendingAutosave()
                parkingPen(native,MotionEvent.ACTION_MOVE,Pt(120.0,110.0));parkingPen(native,MotionEvent.ACTION_UP,Pt(140.0,120.0));stopDebounce(native)
            }
            f.await {!f.navigator.busy}
            withContext(Dispatchers.Main) {
                assertFalse(f.workspace.visible);assertEquals(0,f.presented)
                assertSame(source,native.state.document);assertSame(secondary,native.secondary!!.state.document)
                assertTrue(native.noteOpen);assertTrue(native.secondary!!.noteOpen)
                assertNull(f.workspace.current?.presentation?.pendingBacklinkFocus)
            }
        }
        assertArrayEquals(bytes,f.native.source.readBytes());assertArrayEquals(otherBytes,f.native.target.readBytes());assertEquals(0,f.native.writes.get())
    }

    @Test fun aRealReadonlyNetworkDownDuringBlockedAccessRetiresTheInputTicketAndUpCannotReviveTheOldNavigation()=scenario {f->
        val activity=withContext(Dispatchers.Main) {Robolectric.buildActivity(Activity::class.java).setup().visible()}
        val holder=withContext(Dispatchers.Main) {FrameLayout(activity.get())}
        val presentation=LocalNetworkPresentation()
        val lock=MapSaves.lockFor(Uri.fromFile(f.file));var locked=false
        try {
            val pair=withContext(Dispatchers.Main) {
                val projection=NetworkConceptProjection(NetworkConceptKey(MapSaves.key(Uri.fromFile(f.file)),f.session.document!!.id,"concept"),
                    f.row(0).location,"S and T",NodeKind.TEXT,listOf(f.row(0),f.row(1)))
                val report=LocalNetworkAssembler.assemble(f.query,listOf(projection),issuer=Any(),generation=0)
                val scene=LocalNetworkSceneBuilder().build(report,LocalNetworkLabels("File","Concept","Ink","Reference","References (%d)","Belongs"))
                presentation.visible=true;presentation.reconcile(scene)
                val token=presentation.attach()
                val view=MindMapView(f.native.context).apply {
                    automaticInitialFit=false;document=scene.document;readOnlyNavigation=true;documentEpoch=token.epoch
                    onGraphContact={presentation.graphContact(token)};onReadOnlyContact={presentation.contact(token,it)}
                }
                activity.get().setContentView(holder);holder.addView(view,FrameLayout.LayoutParams(-1,-1))
                holder.measure(View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(700,View.MeasureSpec.EXACTLY))
                holder.layout(0,0,1000,700);assertTrue(view.isAttachedToWindow)
                val geometry=LocalNetworkGeometry(view.width,view.height,view.resources.displayMetrics.density)
                presentation.layout(token,geometry,0)
                val plan=presentation.prepareCamera(token,geometry,0)!!
                assertTrue(applyLocalNetworkCamera(presentation,view,plan,0))
                view to token
            }
            // The actual Window may finish its first measure after the manually-sized content.
            // Initial layout retirement is correct; sign the navigation input only after that dispatch.
            parkingDispatch()
            lock.lock();locked=true
            val input=withContext(Dispatchers.Main) {
                val geometry=LocalNetworkGeometry(pair.first.width,pair.first.height,pair.first.resources.displayMetrics.density)
                presentation.layout(pair.second,geometry,0)
                presentation.prepareCamera(pair.second,geometry,0)?.let {assertTrue(applyLocalNetworkCamera(presentation,pair.first,it,0))}
                val ticket=requireNotNull(presentation.captureInput()) {"The actual Window must be camera-ready before signing this navigation"}
                val restricted=requireNotNull(f.request().withPresentationGuard(ticket::current))
                assertTrue(f.navigator.openNetwork(restricted))
                ticket
            }
            // The one extra Access lease proves it reached its canonical journal lock; Workspace has not presented.
            f.await {f.references()==2 && f.navigator.busy && !f.workspace.opening}
            parkingDispatch()
            withContext(Dispatchers.Main) {
                assertTrue(input.current());assertTrue(presentation.idle)
                networkPen(pair.first,MotionEvent.ACTION_DOWN)
                assertFalse(presentation.idle);assertFalse(input.current())
                networkPen(pair.first,MotionEvent.ACTION_UP)
                assertTrue(presentation.idle);assertNotNull(presentation.captureInput())
                assertFalse("UP makes the surface idle but never revives the old input generation",input.current())
            }
            lock.unlock();locked=false
            f.await {!f.navigator.busy}
            withContext(Dispatchers.Main) {
                assertFalse(f.workspace.visible);assertEquals(0,f.presented)
                assertTrue(presentation.visible);assertNull(f.workspace.current?.presentation?.pendingBacklinkFocus)
                assertFalse(f.session.canUndo);assertTrue(f.native.editor.noteOpen);assertTrue(f.native.editor.secondary!!.noteOpen)
                presentation.detach(pair.second)
            }
            assertEquals(0,f.native.writes.get())
        } finally {
            if(locked) lock.unlock()
            withContext(Dispatchers.Main) {presentation.visible=false;holder.removeAllViews();activity.pause().stop().destroy()}
        }
    }
}
