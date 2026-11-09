package com.xnotes.pen

import android.net.Uri
import android.view.MotionEvent
import com.xnotes.core.geometry.Pt
import com.xnotes.mindmap.*
import com.xnotes.storage.NativeReferenceIssue
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Source handoff uses actual native owners, broker actors and provider files. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class MapSourceNavigatorHostTest {
    private class Fixture(val native:EditorPageCopyFixture) {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val workspace=MapWorkspace(native.context,scope)
        val binding=Any()
        lateinit var entry:MapWorkspace.Entry
        lateinit var navigator:MapSourceNavigator
        val file=File(native.root,"navigation.xmap")
        var opened=0
        var guard:suspend ()->MapSourceGuardResult={MapSourceGuardResult.Ready(emptySet())}
        suspend fun start() {
            openParkingPanes(native)
            val sources=withContext(Dispatchers.Main) {native.editor.openPanes.map {pane->
                MapSource(documentId=pane.state.document.identity.documentId,scope=SourceScope.PAGE,
                    pageId=pane.state.document.pages.last().referenceIdentity.id,fileName=pane.title)
            }}
            file.writeBytes(MapCodec.encode(MindMapDocument(nodes=listOf(MapNode(id="r",parent=null),
                MapNode(id="n",parent="r",text="Body remains",sources=sources)))))
            withContext(Dispatchers.Main) {
                workspace.bindHost(binding)
                workspace.open(Uri.fromFile(file),"navigation")
                navigator=MapSourceNavigator(native.editor,workspace,binding,scope,
                    guard={_,_->guard()},onOpened={_,_->opened++})
            }
            await {workspace.visible && !workspace.opening}
            withContext(Dispatchers.Main) {
                entry=workspace.current!!;entry.presentation.synchronize(entry.session)
                entry.session.selected="n"
            }
        }
        suspend fun await(check:()->Boolean)=withTimeout(10000L) {
            while(!withContext(Dispatchers.Main) {check()}) delay(1)
        }
        fun open(index:Int=1):Boolean {
            val source=entry.session.document!!.node("n").sources[index]
            val ticket=requireNotNull(entry.session.captureSourceNavigation("n",source.id))
            val bookmark=requireNotNull(entry.session.captureSourceReturn(ticket))
            return navigator.open(entry,ticket,bookmark)
        }
        suspend fun finish()=withContext(NonCancellable) {
            withContext(Dispatchers.Main) {if(this@Fixture::navigator.isInitialized) navigator.dispose();workspace.dispose()}.join()
            scope.cancel()
        }
    }
    private fun scenario(block:suspend(Fixture)->Unit)=runParkingScenario {native->
        val f=Fixture(native)
        try {f.start();block(f)} finally {f.finish()}
    }

    @Test fun aSpecificSecondSourceKeepsItsRealOwnerAndReturnsToTheSameActorAndSourceRow()=scenario {f->
        val secondary=f.native.editor.secondary!!
        val nativeModel=secondary.state.document;val history=secondary.history
        val body=f.entry.session.document!!
        val before=f.native.source.readBytes();val beforeSecond=f.native.target.readBytes()
        withContext(Dispatchers.Main) {assertTrue(f.open())}
        f.await {!f.navigator.busy && !f.workspace.visible}
        withContext(Dispatchers.Main) {
            assertEquals(1,f.opened);assertFalse(f.native.editor.noteOpen);assertTrue(secondary.noteOpen)
            assertSame(nativeModel,secondary.state.document);assertSame(history,secondary.history)
            assertSame(f.entry,f.workspace.current);assertSame(body,f.entry.session.document)
            assertTrue(f.navigator.returnToMap())
        }
        f.await {!f.navigator.busy && f.workspace.visible}
        withContext(Dispatchers.Main) {
            assertSame(f.entry,f.workspace.current);assertEquals("n",f.entry.session.selected)
            assertEquals("n",f.entry.presentation.sourcesFor)
            assertEquals(body.node("n").sources[1].id,f.entry.presentation.sourceFocus?.sourceId)
            assertFalse(f.entry.session.canUndo)
        }
        assertArrayEquals(before,f.native.source.readBytes());assertArrayEquals(beforeSecond,f.native.target.readBytes())
        assertEquals(0,f.native.writes.get())
    }

    @Test fun guardCancellationAndFailureKeepTheMapAndExactSourceWithoutChangingEitherNativeModel()=scenario {f->
        val primary=f.native.editor.state.document;val secondary=f.native.editor.secondary!!.state.document
        for(result in listOf(MapSourceGuardResult.Cancelled,MapSourceGuardResult.Failed)) {
            f.guard={result}
            withContext(Dispatchers.Main) {assertTrue(f.open())}
            f.await {!f.navigator.busy}
            withContext(Dispatchers.Main) {
                assertTrue(f.workspace.visible);assertSame(f.entry,f.workspace.current)
                assertSame(primary,f.native.editor.state.document);assertSame(secondary,f.native.editor.secondary!!.state.document)
                assertEquals("n",f.entry.presentation.sourcesFor);assertNull(f.entry.sourceReturn)
                assertEquals(result==MapSourceGuardResult.Failed,f.navigator.saveFailed)
                assertEquals(0,f.opened)
            }
        }
    }

    @Test fun aLateGuardResultCannotAdoptAfterTheHostOrSourceMembershipChanges()=scenario {f->
        val gate=CompletableDeferred<MapSourceGuardResult>();val entered=CompletableDeferred<Unit>()
        f.guard={entered.complete(Unit);gate.await()}
        withContext(Dispatchers.Main) {assertTrue(f.open())};entered.await()
        withContext(Dispatchers.Main) {
            f.entry.session.operate {doc->doc.update("n") {it.copy(sources=it.sources.dropLast(1))}}
            f.workspace.bindHost(Any());gate.complete(MapSourceGuardResult.Ready(emptySet()))
        }
        f.await {!f.navigator.busy}
        withContext(Dispatchers.Main) {
            assertTrue(f.workspace.visible);assertTrue(f.native.editor.noteOpen);assertTrue(f.native.editor.secondary!!.noteOpen)
            assertNull(f.entry.sourceReturn);assertEquals(0,f.opened)
        }
    }

    @Test fun startingAnUnconfirmedMapEditDuringSourceIoRefusesNativeAdoptionAndDoesNotCancelTheEdit()=scenario {f->
        val gate=CompletableDeferred<MapSourceGuardResult>();val entered=CompletableDeferred<Unit>()
        f.guard={entered.complete(Unit);gate.await()}
        withContext(Dispatchers.Main) {assertTrue(f.open())};entered.await()
        withContext(Dispatchers.Main) {
            assertTrue(f.entry.session.beginText("n",MindMapView.Viewport(1f,0f,0f),1000,700,1f))
            gate.complete(MapSourceGuardResult.Ready(emptySet()))
        }
        f.await {!f.navigator.busy}
        withContext(Dispatchers.Main) {
            assertTrue(f.workspace.visible);assertNotNull(f.entry.session.textDraft)
            assertTrue(f.native.editor.noteOpen);assertTrue(f.native.editor.secondary!!.noteOpen)
            assertEquals(0,f.opened);assertEquals(NativeReferenceIssue.CHANGED,f.navigator.failure)
            f.entry.session.cancelTextDraft(f.entry.session.textDraft!!.token)
            f.entry.session.textRestore?.let {f.entry.session.completeTextRestore(it.token)}
        }
    }

    @Test fun returnWaitsForTheActualSourcePenAndCancellationDoesNotLoseTheStrokeOrBookmark()=scenario {f->
        withContext(Dispatchers.Main) {assertTrue(f.open())}
        f.await {!f.navigator.busy && !f.workspace.visible}
        val pane=f.native.editor.active
        withContext(Dispatchers.Main) {
            parkingPen(pane,MotionEvent.ACTION_DOWN,Pt(100.0,100.0))
            parkingPen(pane,MotionEvent.ACTION_MOVE,Pt(130.0,120.0))
            assertTrue(f.navigator.returnToMap())
        }
        parkingDispatch()
        withContext(Dispatchers.Main) {
            assertFalse(f.workspace.visible);assertTrue(f.navigator.busy);f.navigator.cancel()
            assertTrue(pane.stylusInContact);assertNotNull(f.entry.sourceReturn)
            parkingPen(pane,MotionEvent.ACTION_UP,Pt(160.0,140.0))
            assertTrue(f.navigator.returnToMap())
        }
        f.await {!f.navigator.busy && f.workspace.visible}
        withContext(Dispatchers.Main) {assertEquals(1,pane.state.document.pages.first().items.size)}
    }

    @Test fun unsupportedMapSourcesKeepTheCurrentGraphAndTheExistingSourceEntry()=scenario {f->
        val documentId=f.entry.session.document!!.id
        withContext(Dispatchers.IO) {f.native.storage.registry.registerMapObserved(documentId,"navigation.xmap",Uri.fromFile(f.file).toString())}
        withContext(Dispatchers.Main) {
            assertTrue(f.entry.session.operate {doc->doc.update("n") {it.copy(sources=it.sources+MapSource(documentId=documentId))}})
            assertTrue(f.open(2))
        }
        f.await {!f.navigator.busy}
        withContext(Dispatchers.Main) {
            assertEquals(NativeReferenceIssue.UNSUPPORTED,f.navigator.failure);assertTrue(f.workspace.visible)
            assertEquals(3,f.entry.session.document!!.node("n").sources.size);assertEquals("Body remains",f.entry.session.document!!.node("n").text)
            assertTrue(f.native.editor.noteOpen);assertTrue(f.native.editor.secondary!!.noteOpen);assertEquals(0,f.opened)
        }
        assertEquals(0,f.native.writes.get())
    }

    @Test fun ordinaryViewMapDoesNotConsumeTheOldSourceBookmarkOrOverwriteANewNodeSelection()=scenario {f->
        withContext(Dispatchers.Main) {assertTrue(f.open())}
        f.await {!f.navigator.busy && !f.workspace.visible}
        withContext(Dispatchers.Main) {
            assertNotNull(f.entry.sourceReturn)
            f.entry.session.selected="r" // A new page-reference import chose its destination.
            f.navigator.retire()
            assertTrue(f.workspace.open(f.entry.uri,f.entry.title,preparePresentation={
                f.native.editor.awaitHostParking()?.let {proof->({proof.current()})}
            }))
        }
        f.await {!f.workspace.opening && f.workspace.visible}
        withContext(Dispatchers.Main) {
            assertSame(f.entry,f.workspace.current);assertNull(f.entry.sourceReturn)
            assertEquals("r",f.entry.session.selected);assertNull(f.entry.presentation.sourceFocus)
        }
    }

    @Test fun aFileReloadWhileReturnIsWaitingForNativeInputCannotShowTheWrongGraphEpoch()=scenario {f->
        withContext(Dispatchers.Main) {assertTrue(f.open())}
        f.await {!f.navigator.busy && !f.workspace.visible}
        val pane=f.native.editor.active
        withContext(Dispatchers.Main) {
            parkingPen(pane,MotionEvent.ACTION_DOWN,Pt(100.0,100.0));assertTrue(f.navigator.returnToMap())
            f.entry.session.useFileVersion()
        }
        f.await {!f.entry.session.replacingDocument}
        withContext(Dispatchers.Main) {parkingPen(pane,MotionEvent.ACTION_UP,Pt(140.0,120.0))}
        f.await {!f.navigator.busy}
        withContext(Dispatchers.Main) {
            assertFalse(f.workspace.visible);assertSame(f.entry,f.workspace.current)
            assertNull(f.entry.sourceReturn);assertNull(f.entry.presentation.sourceFocus)
            assertEquals(NativeReferenceIssue.CHANGED,f.navigator.failure)
        }
    }
}
