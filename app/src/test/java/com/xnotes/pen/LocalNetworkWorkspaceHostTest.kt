package com.xnotes.pen

import android.net.Uri
import android.view.MotionEvent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.xnotes.core.geometry.Pt
import com.xnotes.mindmap.*
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class LocalNetworkWorkspaceHostTest {
    private class Fixture(val native:EditorPageCopyFixture) {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val maps=MapWorkspace(native.context,scope)
        val networks=FileNetworkWorkspace(native.context,scope)
        val binding=Any()
        lateinit var coordinator:LocalNetworkCoordinator
        var panel=true
        var shown=0
        suspend fun start(openNative:Boolean=true) {
            if(openNative) openParkingPanes(native)
            withContext(Dispatchers.Main) {
                if(openNative) native.editor.goToPage(0)
                maps.bindHost(binding);networks.bindHost(binding)
                coordinator=LocalNetworkCoordinator(native.editor,maps,networks,binding,scope,onShown={shown++})
            }
        }
        fun launch()=LocalNetworkLaunchRequest.capture(requireNotNull(native.editor.captureBacklinkContext()),"Current source") {panel}!!
        suspend fun await(check:()->Boolean)=withTimeout(10000L) {while(!withContext(Dispatchers.Main) {check()}) delay(1)}
        suspend fun finish()=withContext(NonCancellable) {
            withContext(Dispatchers.Main) {coordinator.dispose();networks.dispose();maps.dispose()}.join();scope.cancel()
        }
    }
    private fun scenario(openNative:Boolean=true,block:suspend(Fixture)->Unit)=runParkingScenario {native->val f=Fixture(native)
        try {f.start(openNative);block(f)} finally {f.finish()}
    }
    @Test fun selectedFilesBecomeAnEditableSavedCanvasAndReturnToTheOriginalNetworkAfterReopening()=scenario {f->
        val folder=com.xnotes.platform.AppStorageDocumentsProvider.rootDir(f.native.context)
        val evidence=File(folder,"canvas-network-evidence.xmap")
        // Like SAF's CreateDocument result: the caller supplies a readable empty destination.
        val destination=File(folder,"selected-files-canvas.xmap").apply {writeBytes(byteArrayOf())}
        val originalNative=f.native.source.readBytes()
        val ids=withContext(Dispatchers.Main) {setOf(f.native.editor.state.document.identity.documentId,
            f.native.editor.secondary!!.state.document.identity.documentId)}
        val evidenceDocument=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,
            sources=ids.mapIndexed {i,id->MapSource(documentId=id,fileName="File $i.xnote")})))
        evidence.writeBytes(MapCodec.encode(evidenceDocument))
        withContext(Dispatchers.IO) {f.native.storage.registry.registerMapObserved(evidenceDocument.id,evidence.name,Uri.fromFile(evidence).toString())}
        val entry=withContext(Dispatchers.Main) {f.networks.admit(f.binding,NetworkQuery.library("Library",
            listOf(com.xnotes.platform.AppStorageDocumentsProvider.treeUri(f.native.context).toString())))!!}
        var flow:NetworkToCanvasController?=null
        try {
            f.await {!entry.controller.loading && entry.controller.fileChoices.map {it.documentId}.containsAll(ids)}
            withContext(Dispatchers.Main) {assertTrue(entry.controller.showCollection(ids))}
            f.await {!entry.controller.loading && entry.controller.browseMode==NetworkBrowseMode.FILES}
            fun selection():NetworkCanvasSelection {
                val p=entry.presentation
                val scene=entry.builder.build(entry.controller.report!!,LocalNetworkLabels("File","Concept","Ink","Reference","References %d","Belongs"))
                p.reconcile(scene)
                val attach=p.attach();val size=LocalNetworkGeometry(1000,700,1f)
                val plan=p.prepareCamera(attach,size)!!
                assertTrue(p.finishCamera(plan,LocalNetworkCamera(MindMapView.Viewport(.8f,93f,-41f),1000,700,1f)))
                p.selectNode(NetworkSceneNodeKey.File(ids.first()))
                return requireNotNull(NetworkCanvasSelection.capture(entry,scene))
            }
            withContext(Dispatchers.Main) {
                flow=NetworkToCanvasController(f.native.context,selection(),f.scope)
                assertTrue(flow!!.chooseNew(Uri.fromFile(destination)));assertEquals(0L,destination.length())
                assertTrue(flow!!.confirm())
            }
            f.await {flow!!.step==NetworkCanvasStep.ADDED}
            val imported=withContext(Dispatchers.Main) {
                val s=flow!!.targetSession!!;val doc=s.document!!
                assertEquals(MapOrganization.RELATION_CANVAS,doc.organization);assertNull(doc.centralRoot)
                assertEquals(ids,doc.nodes.map {it.fileCardId}.toSet());assertEquals(2,doc.nodes.size)
                assertTrue(f.coordinator.openCanvas(entry,flow!!.captureOpen()!!));doc
            }
            f.await {!f.coordinator.busy && f.maps.visible}
            val saved=withContext(Dispatchers.Main) {
                val s=f.maps.current!!.session;assertEquals(imported,s.document)
                val nodes=s.document!!.nodes
                val line=MapRelation(from=nodes[0].id,to=nodes[1].id,kind=RelationKind.DEPENDS_ON,label="Read after this file")
                assertTrue(s.changeRelation(null,line) is RelationEditResult.Applied)
                s.undo();assertTrue(s.document!!.relations.isEmpty());s.redo();assertEquals(listOf(line),s.document!!.relations)
                assertTrue(s.operate {it.translateNodes(setOf(nodes[0].id),63.0,29.0)})
                s.cancelPendingAutosave();val accepted=s.document!!
                assertTrue(s.saveAttempt().await() is MapSaveOutcome.Written)
                assertEquals(accepted,MapCodec.decode(destination.readBytes()))
                assertTrue(f.coordinator.resume(entry,f.maps.current!!,closeMap=true));accepted
            }
            f.await {!f.coordinator.busy && f.networks.visible && f.maps.current==null}
            f.await {!entry.controller.loading && !entry.controller.filtering &&
                MapDocuments.observations(f.native.context).none {it.uri==Uri.fromFile(destination)}}
            withContext(Dispatchers.Main) {
                assertSame(entry,f.networks.current);assertEquals(ids,entry.controller.centreFiles)
                assertEquals(MindMapView.Viewport(.8f,93f,-41f),entry.presentation.camera!!.viewport)
                assertEquals(NetworkSceneNodeKey.File(ids.first()),entry.presentation.selectedNode)
                flow=NetworkToCanvasController(f.native.context,selection(),f.scope)
                assertTrue(flow!!.chooseExisting(Uri.fromFile(destination)));assertTrue(flow!!.confirm())
            }
            f.await {flow!!.step==NetworkCanvasStep.ADDED}
            withContext(Dispatchers.Main) {assertEquals(saved,flow!!.targetSession!!.document)
                assertTrue(f.coordinator.openCanvas(entry,flow!!.captureOpen()!!))}
            f.await {!f.coordinator.busy && f.maps.visible}
            withContext(Dispatchers.Main) {
                assertEquals(saved,f.maps.current!!.session.document)
                assertTrue(f.coordinator.resume(entry,f.maps.current!!,closeMap=true))
            }
            f.await {!f.coordinator.busy && f.networks.visible}
            assertArrayEquals(originalNative,f.native.source.readBytes());assertEquals(0,f.native.writes.get())
        } finally {withContext(Dispatchers.Main) {flow?.close()?.join()};evidence.delete();destination.delete()}
    }
    @Test fun theHomepageCanOpenAndCloseALibraryWithoutAnOpenDocumentOrAnInventedNativeContext()=scenario(openNative=false) {f->
        val model=f.native.editor.state.document
        withContext(Dispatchers.Main) {
            assertFalse(f.native.editor.noteOpen);assertNull(f.native.editor.captureBacklinkContext())
            val request=LibraryNetworkLaunchRequest.capture("Library",emptyList()) {f.panel}!!
            assertTrue(f.coordinator.openLibrary(request))
        }
        f.await {!f.coordinator.busy}
        withContext(Dispatchers.Main) {
            val entry=requireNotNull(f.networks.current)
            assertTrue(f.networks.visible);assertFalse(entry.query.hasLocalFocus)
            assertEquals(NetworkBrowseMode.LIBRARY,entry.controller.browseMode)
            assertTrue(entry.controller.centreFiles.isEmpty());assertFalse(entry.controller.showLocal())
            assertSame(model,f.native.editor.state.document);assertFalse(f.native.editor.noteOpen)
            f.panel=false // Only the initial composition depends on the homepage request.
            assertTrue(f.networks.current(f.binding,entry));assertTrue(f.coordinator.close(entry))
            assertFalse(f.networks.visible);assertFalse(f.native.editor.noteOpen)
        }
        assertEquals(0,f.native.writes.get())
    }
    @Test fun cancellingTheLibraryHandoffWhileThePenIsDownRetainsTheNativeStrokeAndNeverOpensLater()=scenario {f->
        lateinit var request:LibraryNetworkLaunchRequest
        val original=f.native.source.readBytes()
        withContext(Dispatchers.Main) {
            parkingPen(f.native.editor,MotionEvent.ACTION_DOWN,Pt(100.0,100.0))
            request=LibraryNetworkLaunchRequest.capture("Library",emptyList()) {f.panel}!!
            assertTrue(f.coordinator.openLibrary(request))
        }
        parkingDispatch()
        withContext(Dispatchers.Main) {
            assertTrue(f.coordinator.busy);assertFalse(f.networks.visible)
            assertTrue(f.coordinator.canCancel(request));f.coordinator.cancel(request)
            assertTrue(f.native.editor.stylusInContact)
            parkingPen(f.native.editor,MotionEvent.ACTION_UP,Pt(160.0,130.0))
        }
        parkingDispatch()
        withContext(Dispatchers.Main) {
            assertFalse(f.coordinator.busy);assertNull(f.networks.current)
            assertTrue(f.native.editor.state.document.dirty);assertTrue(f.native.editor.history.canUndo)
        }
        assertArrayEquals(original,f.native.source.readBytes());assertEquals(0,f.native.writes.get())
    }
    @Test fun aNetworkWaitsForTheRealPenAndOwnsItsQueryAfterTheSidebarAndReadPageChange()=scenario {f->
        val model=f.native.editor.state.document;val history=f.native.editor.history
        val original=f.native.source.readBytes()
        withContext(Dispatchers.Main) {
            parkingPen(f.native.editor,MotionEvent.ACTION_DOWN,Pt(100.0,100.0))
            val request=f.launch();assertTrue(f.coordinator.open(request))
        }
        parkingDispatch()
        withContext(Dispatchers.Main) {
            assertFalse(f.networks.visible);assertTrue(f.coordinator.busy)
            f.coordinator.cancel(f.native.editor.captureBacklinkContext()!!) // Another capture cannot cancel the original request.
            assertTrue(f.coordinator.busy)
            parkingPen(f.native.editor,MotionEvent.ACTION_UP,Pt(140.0,120.0))
        }
        f.await {!f.coordinator.busy}
        withContext(Dispatchers.Main) {
            val entry=requireNotNull(f.networks.current);val focus=entry.query.focusId
            assertTrue(f.networks.visible);assertEquals(1,f.shown)
            f.panel=false;f.native.editor.goToPage(1)
            assertEquals(focus,entry.query.focusId);assertTrue(entry.controller.refresh())
            assertSame(model,f.native.editor.state.document);assertSame(history,f.native.editor.history)
            assertTrue(model.dirty);assertTrue(history.canUndo)
        }
        assertArrayEquals(original,f.native.source.readBytes());assertEquals(0,f.native.writes.get())
    }
    @Test fun anOldHostOrClosedPanelCannotAdoptAfterNativeParkingFinishes()=scenario {f->
        withContext(Dispatchers.Main) {
            parkingPen(f.native.editor,MotionEvent.ACTION_DOWN,Pt(100.0,100.0))
            assertTrue(f.coordinator.open(f.launch()))
        }
        parkingDispatch()
        withContext(Dispatchers.Main) {
            f.panel=false;f.networks.bindHost(Any())
            parkingPen(f.native.editor,MotionEvent.ACTION_UP,Pt(140.0,120.0))
        }
        f.await {!f.coordinator.busy}
        withContext(Dispatchers.Main) {assertFalse(f.networks.visible);assertNull(f.networks.current);assertEquals(0,f.shown)}
    }
    @Test fun theActualViewModelRetainsOnlyThePureNetworkEntryUntilANewHostParksItsOwnNativeOwners()=scenario {f->
        val store=ViewModelStore()
        try {
            withContext(Dispatchers.Main) {
                val owner=ViewModelProvider(store,MapWorkspaceOwner.Factory(f.native.context))[MapWorkspaceOwner::class.java]
                val network=owner.networks;network.bindHost(f.binding)
                val q=NetworkQuery(f.native.editor.state.document.identity.documentId,"source",emptyList())
                val entry=network.admit(f.binding,q)!!
                assertTrue(network.unbindHost(f.binding));assertTrue(network.resumeRequested);assertFalse(network.visible)
                val same=ViewModelProvider(store,MapWorkspaceOwner.Factory(f.native.context))[MapWorkspaceOwner::class.java]
                assertSame(owner,same);assertSame(entry,same.networks.current)
                val next=Any();network.bindHost(next)
                assertFalse(network.show(f.binding,entry));assertTrue(network.show(next,entry))
                assertSame(q,entry.controller.query);assertFalse(network.resumeRequested)
                assertTrue(network.close(next,entry));assertNull(network.current)
            }
        } finally {withContext(Dispatchers.Main) {store.clear()}}
    }
    @Test fun returningFromTheRealMapPreservesItsActorAndRefusesAnUnconfirmedTextDraft()=scenario {f->
        val graph=File(f.native.root,"network-return.xmap")
        graph.writeBytes(MapCodec.encode(MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,text="Graph")))))
        val entry=withContext(Dispatchers.Main) {f.networks.admit(f.binding,NetworkQuery(f.native.editor.state.document.identity.documentId,"source",emptyList()))!!}
        withContext(Dispatchers.Main) {assertTrue(f.maps.open(Uri.fromFile(graph),"Graph",flushNativeOnPresent=false))}
        f.await {f.maps.visible && !f.maps.opening}
        val map=f.maps.current!!
        withContext(Dispatchers.Main) {
            assertTrue(f.networks.parkForMap(f.binding,entry,map))
            assertTrue(map.session.beginText("r",MindMapView.Viewport(1f,0f,0f),1000,700,1f))
            assertFalse(f.coordinator.resume(entry,map));assertTrue(f.maps.visible);assertFalse(f.networks.visible)
            map.session.cancelTextDraft(map.session.textDraft!!.token)
            map.session.completeTextRestore(map.session.textRestore!!.token)
            assertTrue(f.coordinator.resume(entry,map))
        }
        f.await {!f.coordinator.busy}
        withContext(Dispatchers.Main) {
            assertTrue(f.networks.visible);assertFalse(f.maps.visible);assertSame(map,f.maps.current)
            assertSame(entry,f.networks.current);assertFalse(map.session.canUndo)
        }
    }
    @Test fun aNetworkMapPresentationDoesNotFlushEitherDirtyNativeDocument()=scenario {f->
        val graph=File(f.native.root,"network-persistence.xmap")
        graph.writeBytes(MapCodec.encode(MindMapDocument(nodes=listOf(MapNode(id="r",parent=null)))))
        withContext(Dispatchers.Main) {f.native.addStroke(120.0,recordHistory=true);f.native.stopDebounce()
            assertTrue(f.maps.open(Uri.fromFile(graph),"Graph",flushNativeOnPresent=false))}
        f.await {f.maps.visible && !f.maps.opening}
        val original=f.native.source.readBytes()
        withContext(Dispatchers.Main) {
            val map=f.maps.current!!;assertFalse(map.flushNativeOnPresent)
            com.xnotes.persistNativeForMapPresentation(f.native.editor,map)
        }
        parkingDispatch()
        withContext(Dispatchers.Main) {assertTrue(f.native.editor.state.document.dirty);assertTrue(f.native.editor.history.canUndo)}
        assertEquals(0,f.native.writes.get());assertArrayEquals(original,f.native.source.readBytes())
    }
    @Test fun closingTheMapBeforeReturningToTheNetworkRetiresItsNativeProofAcrossHostRebinding()=scenario {f->
        val graph=File(f.native.root,"network-close-return.xmap")
        graph.writeBytes(MapCodec.encode(MindMapDocument(nodes=listOf(MapNode(id="r",parent=null)))))
        val entry=withContext(Dispatchers.Main) {
            f.networks.admit(f.binding,NetworkQuery(f.native.editor.state.document.identity.documentId,"source",emptyList()))!!
        }
        withContext(Dispatchers.Main) {assertTrue(f.maps.open(Uri.fromFile(graph),"Graph",flushNativeOnPresent=false))}
        f.await {f.maps.visible && !f.maps.opening}
        val map=f.maps.current!!
        withContext(Dispatchers.Main) {
            assertTrue(map.parkingCheck())
            assertTrue(f.networks.parkForMap(f.binding,entry,map))
            assertTrue(f.coordinator.resume(entry,map,closeMap=true))
        }
        f.await {!f.coordinator.busy}
        withContext(Dispatchers.Main) {
            assertTrue(f.networks.visible);assertNull(f.maps.current)
            assertNull(entry.graphReturn);assertFalse(map.parkingCheck())
            f.maps.unbindHost(f.binding);f.networks.unbindHost(f.binding)
            val next=Any();f.maps.bindHost(next);f.networks.bindHost(next)
            assertFalse(f.networks.show(f.binding,entry));assertTrue(f.networks.show(next,entry))
            assertNull(f.networks.returnEntry(map));assertSame(entry,f.networks.current)
        }
    }
    @Test fun replacingTheParkedMapRetiresItsOldNativeProofAndNetworkReturn()=scenario {f->
        val first=File(f.native.root,"network-prior.xmap")
        val next=File(f.native.root,"ordinary-next.xmap")
        first.writeBytes(MapCodec.encode(MindMapDocument(nodes=listOf(MapNode(id="r",parent=null)))))
        next.writeBytes(MapCodec.encode(MindMapDocument(nodes=listOf(MapNode(id="r",parent=null)))))
        val entry=withContext(Dispatchers.Main) {
            f.networks.admit(f.binding,NetworkQuery(f.native.editor.state.document.identity.documentId,"source",emptyList()))!!
        }
        withContext(Dispatchers.Main) {assertTrue(f.maps.open(Uri.fromFile(first),"First",flushNativeOnPresent=false))}
        f.await {f.maps.visible && !f.maps.opening}
        val prior=f.maps.current!!
        withContext(Dispatchers.Main) {
            assertTrue(prior.parkingCheck());assertTrue(f.networks.parkForMap(f.binding,entry,prior))
            assertTrue(f.maps.hide(prior)) // Its source is now the foreground native document.
            assertTrue(f.maps.open(Uri.fromFile(next),"Next",onPresented={f.networks.retireGraphReturn()}))
        }
        f.await {f.maps.visible && !f.maps.opening}
        withContext(Dispatchers.Main) {
            assertNotSame(prior,f.maps.current);assertFalse(prior.parkingCheck())
            assertNull(entry.graphReturn);assertNull(f.networks.returnEntry(prior))
            f.maps.unbindHost(f.binding);f.networks.unbindHost(f.binding)
            val rebound=Any();f.maps.bindHost(rebound);f.networks.bindHost(rebound)
            assertFalse(prior.parkingCheck());assertSame(entry,f.networks.current)
        }
    }
}
