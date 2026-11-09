package com.xnotes.pen

import android.net.Uri
import android.view.MotionEvent
import com.xnotes.core.geometry.Pt
import com.xnotes.mindmap.*
import com.xnotes.ui.NativeBacklinkContext
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real native owners, shared map actor and workspace presentation; no alternate editor. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class MapBacklinkNavigatorHostTest {
    private class Fixture(val native:EditorPageCopyFixture) {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val workspace=MapWorkspace(native.context,scope)
        val binding=Any()
        val file=File(com.xnotes.platform.AppStorageDocumentsProvider.rootDir(native.context),
            "backlinks-navigation-${java.util.UUID.randomUUID()}.xmap")
        lateinit var lease:MapDocumentLease
        lateinit var navigator:MapBacklinkNavigator
        lateinit var context:NativeBacklinkContext
        var alive=true
        var hostAlive=true
        var presented=0
        val session get()=lease.session
        suspend fun start() {
            openParkingPanes(native)
            val sources=withContext(Dispatchers.Main) {
                context=requireNotNull(native.editor.captureBacklinkContext())
                val pages=native.editor.state.document.pages
                pages.map {page->MapSource(documentId=context.documentId,scope=SourceScope.PAGE,
                    pageId=page.referenceIdentity.id,fileName="Source note")}
            }
            file.writeBytes(MapCodec.encode(MindMapDocument(nodes=listOf(
                MapNode(id="r",parent=null,text="root",collapsed=true),
                MapNode(id="n",parent="r",text="Body",sources=sources)))))
            withContext(Dispatchers.Main) {
                workspace.bindHost(binding)
                lease=MapDocuments.acquire(native.context,Uri.fromFile(file))
                navigator=MapBacklinkNavigator(native.editor,workspace,binding,scope,
                    captureHost={ {hostAlive} },onPresented={presented++})
            }
            await {!session.loading}
        }
        fun row(index:Int=1):MapBacklinkRow {
            val doc=requireNotNull(session.document)
            val source=doc.node("n").sources[index]
            val observation=MapDocuments.observations(native.context).single {it.session===session}
            return MapBacklinkRow(BacklinkLocation(Uri.fromFile(file),observation.knownAliases,
                MapSaves.key(Uri.fromFile(file)),doc.id,file.name),"n",source.id,source.target,
                "Body",NodeKind.TEXT,source.fileName,source.note,source.lastKnownPage,source.status,null,
                BacklinkOrigin.LIVE,membership=requireNotNull(observation.captureMembership("n",source.id,source.target)))
        }
        fun request(row:MapBacklinkRow=row())=MapBacklinkOpenRequest.capture(context,row,{alive})
        suspend fun await(check:()->Boolean)=withTimeout(10000L) {
            while(!withContext(Dispatchers.Main) {check()}) delay(1)
        }
        suspend fun finish()=withContext(NonCancellable) {
            val jobs=withContext(Dispatchers.Main) {
                if(this@Fixture::navigator.isInitialized) navigator.dispose()
                listOfNotNull(workspace.dispose(),if(this@Fixture::lease.isInitialized) lease.close() else null)
            }
            jobs.forEach {it.join()};scope.cancel();file.delete()
        }
    }
    private fun scenario(block:suspend(Fixture)->Unit)=runParkingScenario {native->
        val f=Fixture(native)
        try {f.start();block(f)} finally {f.finish()}
    }

    @Test fun exactSecondSourceRevealsTheNodeWithoutEditingAndRetainsTheRealNativeOwners()=scenario {f->
        val native=f.native.editor.state.document;val second=f.native.editor.secondary!!.state.document
        val doc=f.session.document!!;val bytes=MapCodec.encode(doc)
        val source=f.row().sourceId
        withContext(Dispatchers.Main) {assertTrue(f.navigator.open(f.request()))}
        f.await {!f.navigator.busy}
        withContext(Dispatchers.Main) {
            assertTrue(f.workspace.visible);assertEquals(1,f.presented)
            assertSame(f.session,f.workspace.current!!.session);assertSame(doc,f.session.document)
            assertEquals("n",f.session.selected)
            assertEquals(source,f.workspace.current!!.presentation.pendingBacklinkFocus?.sourceId)
            assertTrue(f.session.effectiveVisible().any {it.id=="n"})
            assertTrue(doc.node("r").collapsed);assertFalse(f.session.canUndo)
            assertSame(native,f.native.editor.state.document);assertSame(second,f.native.editor.secondary!!.state.document)
            // The source panel disappears after success. It must not roll back the accepted map.
            f.alive=false
        }
        assertArrayEquals(bytes,MapCodec.encode(f.session.document!!));assertEquals(0,f.native.writes.get())
    }

    @Test fun eitherPhysicalPaneCanKeepItsPenWhileParkingIsCancelledAndTheOtherCanFinishTheHandoff()=scenario {f->
        val first=f.native.editor;val second=first.secondary!!
        withContext(Dispatchers.Main) {
            parkingPen(first,MotionEvent.ACTION_DOWN,Pt(100.0,100.0))
            assertTrue(f.navigator.open(f.request()))
        }
        f.await {f.workspace.opening}
        withContext(Dispatchers.Main) {
            assertTrue(f.navigator.busy);assertFalse(f.workspace.visible)
            f.navigator.cancel()
            assertTrue(first.stylusInContact)
            parkingPen(first,MotionEvent.ACTION_UP,Pt(140.0,120.0))
            parkingPen(second,MotionEvent.ACTION_DOWN,Pt(100.0,100.0))
            assertTrue(f.navigator.open(f.request()))
        }
        f.await {f.workspace.opening}
        withContext(Dispatchers.Main) {
            assertTrue(second.stylusInContact);assertFalse(f.workspace.visible)
            parkingPen(second,MotionEvent.ACTION_UP,Pt(140.0,120.0))
        }
        f.await {!f.navigator.busy}
        withContext(Dispatchers.Main) {
            assertTrue(f.workspace.visible);assertEquals(1,f.presented)
            assertEquals(1,first.state.document.pages.first().items.size)
            assertEquals(1,second.state.document.pages.first().items.size)
        }
    }

    @Test fun retiredPanelTargetAbaAndHostReplacementCannotPresentAnOldRow()=scenario {f->
        for(which in 0..2) {
            withContext(Dispatchers.Main) {
                f.alive=true;f.hostAlive=true
                parkingPen(f.native.editor,MotionEvent.ACTION_DOWN,Pt(100.0,100.0))
                assertTrue(f.navigator.open(f.request()))
            }
            parkingDispatch()
            withContext(Dispatchers.Main) {
                when(which) {
                    0->f.alive=false
                    1->{
                        val old=f.session.document!!.node("n").sources
                        f.session.operate {d->d.update("n") {it.copy(sources=old.dropLast(1))}}
                        f.session.undo()
                    }
                    else->f.hostAlive=false
                }
                parkingPen(f.native.editor,MotionEvent.ACTION_UP,Pt(140.0,120.0))
            }
            f.await {!f.navigator.busy}
            withContext(Dispatchers.Main) {
                assertFalse(f.workspace.visible);assertEquals(0,f.presented)
                assertTrue(f.native.editor.noteOpen);assertTrue(f.native.editor.secondary!!.noteOpen)
            }
        }
    }

    @Test fun aStoredRowCannotBeResignedToAnAlreadyLiveActorAndAnUnconfirmedMapEditIsNotCancelled()=scenario {f->
        withContext(Dispatchers.Main) {
            val stored=f.row().copy(origin=BacklinkOrigin.STORED,membership=null)
            assertTrue(f.navigator.open(f.request(stored)))
        }
        f.await {!f.navigator.busy}
        withContext(Dispatchers.Main) {
            assertFalse(f.workspace.visible);assertEquals(BacklinkProblem.CHANGED,f.navigator.failureFor(f.context))
            assertTrue(f.session.beginText("n",MindMapView.Viewport(1f,0f,0f),1000,700,1f))
            assertTrue(f.navigator.open(f.request()))
        }
        f.await {!f.navigator.busy}
        withContext(Dispatchers.Main) {
            assertFalse(f.workspace.visible);assertNotNull(f.session.textDraft)
            assertEquals(BacklinkProblem.BUSY,f.navigator.failureFor(f.context))
            f.session.cancelTextDraft(f.session.textDraft!!.token)
            f.session.textRestore?.let {f.session.completeTextRestore(it.token)}
        }
    }

    @Test fun aNewFocusReusesTheParkedMapEntryAndDoesNotConsumeItsOldReturnBookmark()=scenario {f->
        withContext(Dispatchers.Main) {assertTrue(f.navigator.open(f.request( f.row(0))))}
        f.await {!f.navigator.busy}
        val entry=f.workspace.current!!
        withContext(Dispatchers.Main) {
            val source=f.session.captureSourceNavigation("n",f.row(0).sourceId)!!
            entry.sourceReturn=f.session.captureSourceReturn(source)
            assertTrue(f.workspace.hide(entry))
            assertTrue(f.navigator.open(f.request(f.row(1))))
        }
        f.await {!f.navigator.busy}
        withContext(Dispatchers.Main) {
            assertTrue(f.workspace.visible);assertSame(entry,f.workspace.current)
            assertEquals(f.row(1).sourceId,entry.presentation.pendingBacklinkFocus?.sourceId)
            assertNull(entry.sourceReturn);assertEquals(2,f.presented)
        }
    }
    @Test fun aClosedStoredRowCanAcquireItsOwnFirstActorAndStillUsesExactGraphAndSourceIdentity()=scenario {f->
        val old=f.session
        val row=withContext(Dispatchers.Main) {f.row().copy(origin=BacklinkOrigin.STORED,membership=null)}
        withContext(Dispatchers.Main) {f.lease.close()}.join()
        f.await {MapDocuments.observations(f.native.context).none {it.key==row.location.key}}
        withContext(Dispatchers.Main) {assertTrue(f.navigator.open(f.request(row)))}
        f.await {!f.navigator.busy}
        withContext(Dispatchers.Main) {
            val entry=requireNotNull(f.workspace.current)
            assertTrue(f.workspace.visible);assertNotSame(old,entry.session)
            assertEquals(row.location.graphId,entry.session.document!!.id)
            assertEquals(row.sourceId,entry.presentation.pendingBacklinkFocus?.sourceId)
            assertFalse(entry.session.canUndo);assertEquals(1,f.presented)
        }
    }

    @Test fun anAppliedRemovalReceiptReviewsOnlyItsSurvivingNodeAndCannotReviveTheDeletedSource()=scenario {f->
        val row=withContext(Dispatchers.Main) {f.row()}
        withContext(Dispatchers.Main) {
            val removal=requireNotNull(f.session.captureBacklinkRemoval(row.location.graphId,row.nodeId,row.sourceId,row.target,row.membership))
            assertEquals(MapBacklinkRemovalResult.Applied,f.session.removeBacklinkSource(removal))
            val request=MapBacklinkOpenRequest.capture(f.context,row,{f.alive},removal)
            assertTrue(f.navigator.open(request))
        }
        f.await {!f.navigator.busy}
        withContext(Dispatchers.Main) {
            assertTrue(f.workspace.visible);assertEquals("n",f.session.selected)
            assertFalse(f.workspace.current!!.presentation.pendingBacklinkFocus!!.opensSource)
            assertTrue(f.session.document!!.node("n").sources.none {it.id==row.sourceId})
            assertEquals("Body",f.session.document!!.node("n").text)
            assertTrue(f.session.canUndo)
        }
    }

    @Test fun anOldPanelCancelCannotCancelANewerPanelsAcceptedParkingOperation()=scenario {f->
        val first=f.context
        withContext(Dispatchers.Main) {
            parkingPen(f.native.editor,MotionEvent.ACTION_DOWN,Pt(100.0,100.0))
            assertTrue(f.navigator.open(f.request()))
        }
        parkingDispatch()
        withContext(Dispatchers.Main) {f.navigator.cancel(first)}
        f.await {!f.navigator.busy}
        withContext(Dispatchers.Main) {
            f.context=requireNotNull(f.native.editor.captureBacklinkContext())
            assertNotSame(first,f.context)
            assertTrue(f.navigator.open(f.request()))
        }
        parkingDispatch()
        withContext(Dispatchers.Main) {
            assertTrue(f.navigator.canCancel(f.context));assertFalse(f.navigator.canCancel(first))
            f.navigator.cancel(first)
            assertTrue(f.navigator.busy);assertFalse(f.workspace.visible)
            parkingPen(f.native.editor,MotionEvent.ACTION_UP,Pt(140.0,120.0))
        }
        f.await {!f.navigator.busy}
        withContext(Dispatchers.Main) {assertTrue(f.workspace.visible);assertEquals(1,f.presented)}
    }

    @Test fun aMapMovedToTrashDuringNativeParkingIsRecheckedBeforeAnyPresentation()=scenario {f->
        val trash=File(f.file.parentFile,".xnote/trash/${f.file.name}")
        try {
            withContext(Dispatchers.Main) {
                parkingPen(f.native.editor,MotionEvent.ACTION_DOWN,Pt(100.0,100.0))
                assertTrue(f.navigator.open(f.request()))
            }
            f.await {f.workspace.opening}
            withContext(Dispatchers.IO) {
                trash.parentFile!!.mkdirs();f.file.copyTo(trash);assertTrue(f.file.delete())
                f.native.storage.registry.moved(Uri.fromFile(f.file).toString(),Uri.fromFile(trash).toString())
            }
            withContext(Dispatchers.Main) {parkingPen(f.native.editor,MotionEvent.ACTION_UP,Pt(140.0,120.0))}
            f.await {!f.navigator.busy}
            withContext(Dispatchers.Main) {
                assertFalse(f.workspace.visible);assertEquals(0,f.presented)
                assertEquals(BacklinkProblem.TRASHED,f.navigator.failureFor(f.context))
                assertTrue(f.native.editor.noteOpen);assertFalse(f.file.exists())
            }
            assertTrue(trash.exists())
        } finally {trash.delete()}
    }

}
