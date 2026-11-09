package com.xnotes.mindmap

import com.xnotes.core.geometry.Pt
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real provider files, canonical broker leases, actors/history and actual MapView cameras. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class SourceMapWorkspaceHostTest {
    private class Fixture(val base:MapBacklinkLibraryTest.Fixture) {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val workspace=MapWorkspace(base.context,scope)
        val binding=Any()
        val ids=List(17) {UUID.randomUUID().toString()}
        val models=ids.mapIndexed {i,id->MindMapDocument(id=id,nodes=listOf(MapNode(id="r",parent=null,text="Map $i"),
            MapNode(id="n",parent="r",text="Body $i",sources=ids.map {MapSource(documentId=it,fileName="Graph") })))}
        val files=mutableListOf<File>()
        val temporary=mutableListOf<MapDocumentLease>()
        val first get()=workspace.current!!
        suspend fun start() {
            withContext(Dispatchers.IO) {models.forEachIndexed {i,doc->files+=base.graph("source-map-$i.xmap",doc)}}
            withContext(Dispatchers.Main) {workspace.bindHost(binding);assertTrue(workspace.open(base.uri(files[0]),"A"))}
            await {!workspace.opening && workspace.visible}
        }
        suspend fun await(check:()->Boolean)=withTimeout(10000) {while(!withContext(Dispatchers.Main) {check()}) delay(1)}
        suspend fun incoming(index:Int,alias:Boolean=false):MapDocumentLease {
            val lease=withContext(Dispatchers.Main) {MapDocuments.acquire(base.context,base.uri(files[index],asTree=!alias)).also(temporary::add)}
            await {!lease.session.loading};return lease
        }
        fun source(entry:MapWorkspace.Entry,index:Int)=entry.session.document!!.node("n").sources.single {it.documentId==ids[index]}
        fun snapshot(entry:MapWorkspace.Entry,index:Int,camera:MindMapView.Viewport?=null):Pair<MapSourceReturnBookmark,MapPresentationDeparture> {
            val session=entry.session;val p=entry.presentation;p.synchronize(session)
            if(camera!=null) {
                p.closeSources()
                val view=MindMapView(base.context).apply {document=session.document!!;documentEpoch=session.interactionEpoch;layout(0,0,1000,700)}
                p.restore(session,view)
                view.restoreViewport(camera,1000,700,view.resources.displayMetrics.density)
                assertTrue(p.capture(session,view));assertTrue(p.showSources(session,view,"n"))
            }
            session.selected="n"
            val source=source(entry,index);val ticket=session.captureSourceNavigation("n",source.id,source.target)!!
            return session.captureSourceReturn(ticket)!! to p.captureSourceDeparture(session)
        }
        suspend fun push(index:Int,camera:MindMapView.Viewport?=null,alias:Boolean=false):MapWorkspace.Entry {
            val origin=withContext(Dispatchers.Main) {first}
            val prepared=withContext(Dispatchers.Main) {snapshot(origin,index,camera)}
            val lease=incoming(index,alias)
            withContext(Dispatchers.Main) {
                assertEquals(SourcePushResult.APPLIED,workspace.pushSource(origin,lease,"Graph $index",prepared.first,prepared.second) {true})
                assertFalse(origin.presentation.visible);assertTrue(first.presentation.visible);assertTrue(first.skipNativePersistOnPresent)
            }
            return withContext(Dispatchers.Main) {first}
        }
        fun camera(entry:MapWorkspace.Entry):MindMapView.Viewport? {
            val session=entry.session
            val view=MindMapView(base.context).apply {document=session.document!!;documentEpoch=session.interactionEpoch;layout(0,0,1000,700)}
            return if(entry.presentation.restore(session,view)) view.viewport() else null
        }
        fun edit(entry:MapWorkspace.Entry,text:String) {
            assertTrue(entry.session.operate {it.update("n") {node->node.copy(text=text)}});entry.session.cancelPendingAutosave()
        }
        suspend fun finish()=withContext(NonCancellable) {
            withContext(Dispatchers.Main) {workspace.dispose()}.join()
            val releases=withContext(Dispatchers.Main) {temporary.map {it.close()}}
            releases.forEach {it.join()};scope.cancel()
        }
    }
    private fun scenario(block:suspend(Fixture)->Unit)=MapBacklinkLibraryTest().scenario {base->
        val f=Fixture(base);try {f.start();block(f)} finally {f.finish()}
    }
    private fun assertCamera(expected:MindMapView.Viewport,actual:MindMapView.Viewport?) {
        assertNotNull(actual);assertEquals(expected.zoom,actual!!.zoom,.000001f)
        assertEquals(expected.panX,actual.panX,.001f);assertEquals(expected.panY,actual.panY,.001f)
    }
    @Test fun aThreeMapChainKeepsDirtyActorsHistorySourceRowsAndEveryDepartureCameraThroughReturnAndRevisit()=scenario {f->
        val a=withContext(Dispatchers.Main) {f.first.also {f.edit(it,"Edited A")}}
        val aBody=a.session.document!!;val aCamera=MindMapView.Viewport(1.2f,77f,-38f)
        val b=f.push(1,aCamera);withContext(Dispatchers.Main) {f.edit(b,"Edited B")}
        val bBody=b.session.document!!;val bCamera=MindMapView.Viewport(.85f,-23f,95f)
        val c=f.push(2,bCamera);withContext(Dispatchers.Main) {f.edit(c,"Edited C")}
        val cBody=c.session.document!!
        withContext(Dispatchers.Main) {
            assertTrue(f.workspace.returnSource(c) {true});assertSame(b,f.first);assertSame(bBody,b.session.document)
            assertCamera(bCamera,f.camera(b));assertEquals("n",b.presentation.sourcesFor)
            assertEquals(f.source(b,2).id,b.presentation.sourceFocus!!.sourceId)
            assertTrue(f.workspace.returnSource(b) {true});assertSame(a,f.first);assertSame(aBody,a.session.document)
            assertCamera(aCamera,f.camera(a));assertEquals(f.source(a,1).id,a.presentation.sourceFocus!!.sourceId)
            assertFalse(f.workspace.canReturnSource);assertTrue(a.lease.alive && b.lease.alive && c.lease.alive)
            for(entry in listOf(a,b,c)) {assertTrue(entry.session.dirty);assertTrue(entry.session.canUndo);assertTrue(entry.skipNativePersistOnPresent)}
        }
        val again=f.push(1)
        withContext(Dispatchers.Main) {
            assertSame(b,again);assertSame(bBody,again.session.document);assertSame(cBody,c.session.document)
            b.session.undo();b.session.cancelPendingAutosave();assertEquals("Body 1",b.session.document!!.node("n").text)
            b.session.redo();b.session.cancelPendingAutosave();assertEquals("Edited B",b.session.document!!.node("n").text)
        }
        assertEquals(0,f.base.writes.get());assertEquals(f.models[0],MapCodec.decode(f.files[0].readBytes()))
    }
    @Test fun selfAndCanonicalAliasCyclesReuseOneEntryButKeepIndependentPerHopCameraSnapshots()=scenario {f->
        val a=withContext(Dispatchers.Main) {f.first}
        val self=withContext(Dispatchers.Main) {f.snapshot(a,0)}
        val duplicate=f.incoming(0,alias=true)
        withContext(Dispatchers.Main) {
            assertEquals(SourcePushResult.SELF,f.workspace.pushSource(a,duplicate,"Self",self.first,self.second) {true})
            assertSame(a,f.first);assertFalse(duplicate.alive);assertTrue(a.lease.alive);assertFalse(f.workspace.canReturnSource)
            assertNull(a.presentation.sourcesFor);assertFalse(a.session.canUndo)
        }
        val firstCamera=MindMapView.Viewport(1.1f,12f,-25f);val secondCamera=MindMapView.Viewport(.7f,89f,21f)
        val laterCamera=MindMapView.Viewport(1.6f,-75f,48f)
        val b=f.push(1,firstCamera);val cycled=f.push(0,secondCamera,alias=true)
        assertSame(a,cycled)
        val bAgain=f.push(1,laterCamera,alias=true);assertSame(b,bAgain)
        withContext(Dispatchers.Main) {
            assertTrue(f.workspace.returnSource(b) {true});assertSame(a,f.first);assertCamera(laterCamera,f.camera(a))
            assertTrue(f.workspace.returnSource(a) {true});assertSame(b,f.first);assertCamera(secondCamera,f.camera(b))
            assertTrue(f.workspace.returnSource(b) {true});assertSame(a,f.first);assertCamera(firstCamera,f.camera(a))
            assertFalse(f.workspace.canReturnSource);assertFalse(a.session.canUndo);assertFalse(b.session.canUndo)
            assertTrue(f.temporary.filter {it.session===a.session || it.session===b.session}.count {it.alive}<=1)
        }
    }
    @Test fun anInvalidatedSourceReturnsWithoutOldSelectionAndAReloadedOriginNeverReceivesItsOldCamera()=scenario {f->
        val a=withContext(Dispatchers.Main) {f.first}
        val oldCamera=MindMapView.Viewport(1.3f,45f,-35f);val b=f.push(1,oldCamera)
        withContext(Dispatchers.Main) {
            val source=f.source(a,1)
            assertTrue(a.session.operate {it.update("n") {node->node.copy(sources=node.sources.filterNot {it.id==source.id})}})
            a.session.undo();a.session.cancelPendingAutosave();a.session.selected="r"
            assertTrue(f.workspace.returnSource(b) {true});assertSame(a,f.first);assertEquals("r",a.session.selected)
            assertNull(a.presentation.sourcesFor);assertNull(a.presentation.sourceFocus);assertCamera(oldCamera,f.camera(a))
        }
        val reopened=f.push(1)
        withContext(Dispatchers.Main) {a.session.useFileVersion()}
        f.await {!a.session.replacingDocument}
        val fresh=MindMapView.Viewport(.6f,150f,100f)
        withContext(Dispatchers.Main) {
            f.snapshot(a,1,fresh);a.session.selected="r"
            assertTrue(f.workspace.returnSource(reopened) {true});assertSame(a,f.first)
            assertEquals("r",a.session.selected);assertNull(a.presentation.sourceFocus);assertCamera(fresh,f.camera(a))
        }
    }
    @Test fun sourceParkingGatesBothActorsWithoutCancelingTheirDraftsAndIgnoresOnlyARetiredOsTombstone()=scenario {f->
        val a=withContext(Dispatchers.Main) {f.first};val incoming=f.incoming(1)
        val prepared=withContext(Dispatchers.Main) {f.snapshot(a,1)}
        withContext(Dispatchers.Main) {
            fun attempt()=f.workspace.pushSource(a,incoming,"B",prepared.first,prepared.second) {true}
            a.session.inkContact=true;assertEquals(SourcePushResult.BUSY,attempt());assertTrue(a.session.inkContact);a.session.inkContact=false
            assertTrue(a.session.beginConnectedNode("n",Pt(350.0,220.0)))
            assertEquals(SourcePushResult.BUSY,attempt());val connected=a.session.connectedDraft!!
            a.session.cancelConnectedNode(connected.token)
            assertTrue(incoming.session.beginText("n",MindMapView.Viewport(1f,0f,0f),1000,700))
            val text=incoming.session.textDraft!!;assertEquals(SourcePushResult.BUSY,attempt());assertSame(text,incoming.session.textDraft)
            incoming.session.cancelTextDraft(text.token);incoming.session.textRestore?.let {incoming.session.completeTextRestore(it.token)}
            val add=incoming.session.beginExistingSource("n")!!
            assertFalse(incoming.session.canParkSourceNavigation);assertEquals(SourcePushResult.BUSY,attempt())
            incoming.session.cancelSourceRequest(add)
            val ticket=incoming.session.captureSourceTargetEdit("n",incoming.session.document!!.node("n").sources.single {it.documentId==f.ids[0]}.id,
                MapSourceTarget(f.ids[0],SourceScope.FILE),SourceTargetEditMode.REPLACE)!!
            val owner=Any();val targets=incoming.session.sourceTargetEdits;assertTrue(targets.start(owner,ticket))
            val slot=targets.capturePicker()!!;assertFalse(incoming.session.canParkSourceNavigation);assertEquals(SourcePushResult.BUSY,attempt())
            targets.retireOwner(owner);assertTrue(targets.hasPending);assertTrue(incoming.session.canParkSourceNavigation)
            assertTrue(incoming.alive);assertEquals(SourcePushResult.APPLIED,attempt());assertTrue(incoming.alive)
            assertTrue(targets.receivePicker(slot,null));assertFalse(targets.hasPending)
        }
    }
    @Test fun anExplicitSaveConflictStaysWithTheSameTargetActorWhenReturningAndOpeningItAgain()=scenario {f->
        val a=withContext(Dispatchers.Main) {f.first};val b=f.push(1)
        withContext(Dispatchers.Main) {f.edit(b,"Unsaved B")}
        val external=MapCodec.encode(f.models[1].update("r") {it.copy(text="External B")})
        withContext(Dispatchers.IO) {f.files[1].writeBytes(external)}
        withContext(Dispatchers.Main) {
            assertTrue(b.session.saveAttempt().await() is MapSaveOutcome.Conflict);assertTrue(b.session.conflict)
            assertTrue(f.workspace.returnSource(b) {true});assertSame(a,f.first)
        }
        val revisited=f.push(1)
        withContext(Dispatchers.Main) {
            assertSame(b,revisited);assertTrue(b.session.conflict);assertTrue(b.session.dirty);assertTrue(b.session.canUndo)
            assertEquals("Unsaved B",b.session.document!!.node("n").text)
        }
        assertArrayEquals(external,f.files[1].readBytes())
    }
    @Test fun configuredLimitsRefuseNewWorkWithoutEvictingAnyVisitedHistory()=scenario {f->
        val a=withContext(Dispatchers.Main) {f.first.also {f.edit(it,"Retained A")}}
        for(i in 1..15) f.push(i)
        val last=withContext(Dispatchers.Main) {f.first};val extra=f.incoming(16)
        val prepared=withContext(Dispatchers.Main) {f.snapshot(last,16)}
        withContext(Dispatchers.Main) {
            assertEquals(SourcePushResult.LIMIT,f.workspace.pushSource(last,extra,"Over limit",prepared.first,prepared.second) {true})
            assertTrue(extra.alive);assertSame(last,f.first);assertTrue(a.lease.alive);assertEquals("Retained A",a.session.document!!.node("n").text)
        }
    }
    @Test fun sixtyFourCycleFramesAreKeptUntilExplicitReturnAndTheNextHopDoesNotConsumeItsLease()=scenario {f->
        for(i in 1..64) f.push(i%2)
        val current=withContext(Dispatchers.Main) {f.first};val nextIndex=if(current.session.document!!.id==f.ids[0]) 1 else 0
        val next=f.incoming(nextIndex);val prepared=withContext(Dispatchers.Main) {f.snapshot(current,nextIndex)}
        withContext(Dispatchers.Main) {
            assertEquals(SourcePushResult.LIMIT,f.workspace.pushSource(current,next,"Too deep",prepared.first,prepared.second) {true})
            assertTrue(next.alive);repeat(64) {assertTrue(f.workspace.returnSource(f.first) {true})}
            assertFalse(f.workspace.canReturnSource);assertNull(f.workspace.sourceReturnToken)
        }
    }
    @Test fun anOrdinarySameEntryOpenAndLaterCycleRetireTheOldPreparedOperationAndReturnFrameTokens()=scenario {f->
        val a=withContext(Dispatchers.Main) {f.first};val incoming=f.incoming(1)
        val prepared=withContext(Dispatchers.Main) {f.snapshot(a,1)}
        withContext(Dispatchers.Main) {
            val before=f.workspace.sourceNavigationGeneration
            assertTrue(f.workspace.open(a.uri,"Re-present the same entry"));assertTrue(f.workspace.sourceNavigationGeneration!=before)
            f.workspace.cancelOpen()
            assertEquals(SourcePushResult.CHANGED,f.workspace.pushSource(a,incoming,"B",prepared.first,prepared.second) {
                f.workspace.sourceNavigationGeneration==before
            })
            assertTrue(incoming.alive);assertSame(a,f.first);assertFalse(f.workspace.canReturnSource)
            assertEquals(SourcePushResult.APPLIED,f.workspace.pushSource(a,incoming,"B",prepared.first,prepared.second) {true})
            val firstToken=f.workspace.sourceReturnToken;val b=f.first
            assertTrue(f.workspace.returnSource(b) {true});assertSame(a,f.first)
            val next=f.snapshot(a,1)
            assertEquals(SourcePushResult.APPLIED,f.workspace.pushSource(a,b.lease,"B",next.first,next.second) {true})
            assertSame(b,f.first);assertNotSame(firstToken,f.workspace.sourceReturnToken)
        }
    }
    @Test fun anOrdinaryReplacementCannotRetireAVisibleSourceChainWhileItsCurrentActorHasInputOrAPicker()=scenario {f->
        val a=withContext(Dispatchers.Main) {f.first};val b=f.push(1)
        val token=withContext(Dispatchers.Main) {f.workspace.sourceReturnToken}
        val next=f.base.uri(f.files[2]);lateinit var pendingPicker:String
        withContext(Dispatchers.Main) {b.session.inkContact=true;assertTrue(f.workspace.open(next,"Blocked C"))}
        f.await {!f.workspace.opening}
        withContext(Dispatchers.Main) {
            assertSame(b,f.first);assertTrue(f.workspace.visible);assertSame(token,f.workspace.sourceReturnToken)
            assertTrue(a.lease.alive && b.lease.alive);assertTrue(b.session.inkContact)
            assertEquals(MapWorkspaceFailure.CHANGED,f.workspace.failure);b.session.inkContact=false
            pendingPicker=b.session.beginExistingSource("n")!!
            assertTrue(f.workspace.open(next,"Still blocked C"))
        }
        f.await {!f.workspace.opening}
        withContext(Dispatchers.Main) {
            assertSame(b,f.first);assertSame(token,f.workspace.sourceReturnToken);assertTrue(a.lease.alive && b.lease.alive)
            assertFalse(b.session.canParkSourceNavigation)
            assertNull(b.session.beginExistingSource("n"))
            // The actual old OS cancellation still consumes exactly its original picker request.
            assertTrue(b.session.receiveExistingSource(pendingPicker,null));assertTrue(b.session.canParkSourceNavigation)
            assertTrue(f.workspace.open(next,"Retry C"))
        }
        f.await {!f.workspace.opening}
        withContext(Dispatchers.Main) {
            assertEquals(f.ids[2],f.first.session.document!!.id);assertFalse(f.workspace.canReturnSource)
            assertFalse(a.lease.alive || b.lease.alive);assertNull(f.workspace.sourceReturnToken)
        }
    }
    @Test fun ordinaryReplacementAndHostChangesRetireOldProofsButSameCurrentOpenRetainsTheSourceTrail()=scenario {f->
        val a=withContext(Dispatchers.Main) {f.first};val b=f.push(1);val c=f.push(2)
        val token=withContext(Dispatchers.Main) {f.workspace.sourceReturnToken}
        withContext(Dispatchers.Main) {
            val generation=f.workspace.sourceNavigationGeneration;f.workspace.unbindHost(f.binding)
            assertTrue(f.workspace.sourceNavigationGeneration!=generation)
            for(entry in listOf(a,b,c)) assertFalse(entry.parkingCheck())
            f.workspace.bindHost(Any());assertSame(token,f.workspace.sourceReturnToken)
            assertTrue(f.workspace.open(c.uri,"Same actual current"))
        }
        f.await {!f.workspace.opening}
        withContext(Dispatchers.Main) {assertSame(c,f.first);assertSame(token,f.workspace.sourceReturnToken);assertTrue(a.lease.alive && b.lease.alive)}
        withContext(Dispatchers.Main) {assertTrue(f.workspace.open(f.base.uri(f.files[3]),"Ordinary replacement"))}
        f.await {!f.workspace.opening}
        withContext(Dispatchers.Main) {
            assertFalse(f.workspace.canReturnSource);assertNull(f.workspace.sourceReturnToken)
            assertFalse(a.lease.alive || b.lease.alive || c.lease.alive)
            assertEquals(f.ids[3],f.first.session.document!!.id)
            val shared=f.first.session;val external=MapDocuments.acquire(f.base.context,f.first.uri).also(f.temporary::add)
            assertTrue(shared.beginText("n",MindMapView.Viewport(1f,0f,0f),1000,700));val draft=shared.textDraft!!;val epoch=shared.interactionEpoch
            val closing=f.workspace.close(f.first)!!
            assertSame(draft,shared.textDraft);assertEquals(epoch,shared.interactionEpoch);assertTrue(external.alive)
            shared.cancelTextDraft(draft.token);shared.textRestore?.let {shared.completeTextRestore(it.token)}
            closing.join()
        }
    }
}
