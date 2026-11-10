package com.xnotes.pen

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.MotionEvent
import android.view.InputDevice
import android.os.SystemClock
import androidx.compose.ui.text.input.TextFieldValue
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.TextItem
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.enableReferenceAnchors
import com.xnotes.core.model.fixedWriteSnapshot
import com.xnotes.core.pdf.PdfPageGeometry
import com.xnotes.core.tools.Tool
import com.xnotes.mindmap.*
import com.xnotes.storage.OriginalPdfAccess
import com.xnotes.storage.OriginalPdfCatalog
import com.xnotes.storage.OriginalPdfEngine
import com.xnotes.storage.OriginalPdfPageInfo
import com.xnotes.ui.Pane
import com.xnotes.ui.PreparedPagedPairOpen
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** Real canonical map actors, staged native opens and provider bytes; no substitute coordinator. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class MapPairOpenHostTest {
    private class Fixture(val native:EditorPageCopyFixture) {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val workspace=MapWorkspace(native.context,scope)
        val authority="map-pair-${UUID.randomUUID()}"
        val uri=DocumentsContract.buildDocumentUri(authority,"root/pair.xmap")
        val alias=DocumentsContract.buildDocumentUriUsingTree(DocumentsContract.buildTreeDocumentUri(authority,"root"),"root/pair.xmap")
        val other=DocumentsContract.buildDocumentUri(authority,"root/old.xmap")
        val reads=AtomicInteger();val writes=AtomicInteger()
        @Volatile var beforeRead:((Uri)->Unit)?=null
        @Volatile var denyWrite=false
        val held=mutableListOf<MapDocumentLease>()
        val document=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,text="root"),MapNode(id="n",parent="r",text="saved")))
        fun file(at:Uri)=File(native.root,DocumentsContract.getDocumentId(at).substringAfterLast('/'))
        private val provider=object:ContentProvider() {
            override fun onCreate()=true
            override fun getType(uri:Uri)="application/octet-stream"
            override fun query(uri:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,sort:String?):Cursor =
                MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)).apply {addRow(arrayOf(file(uri).name,file(uri).length()))}
            override fun insert(uri:Uri,values:ContentValues?):Uri?=null
            override fun update(uri:Uri,values:ContentValues?,s:String?,a:Array<out String>?)=0
            override fun delete(uri:Uri,s:String?,a:Array<out String>?):Int=error("A pair open must not delete a map")
            override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
                if('w' in mode) {if(denyWrite) throw IOException("Controlled map write failure");writes.incrementAndGet()}
                else {reads.incrementAndGet();beforeRead?.invoke(uri)}
                return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.parseMode(mode))
            }
        }
        init {
            provider.attachInfo(native.context,ProviderInfo().apply {this.authority=this@Fixture.authority})
            ShadowContentResolver.registerProviderInternal(authority,provider)
            file(uri).writeBytes(MapCodec.encode(document))
            file(other).writeBytes(MapCodec.encode(document.copy(id=UUID.randomUUID().toString())))
        }
        suspend fun await(check:()->Boolean)=withTimeout(10000L) {while(!withContext(Dispatchers.Main) {check()}) delay(1L)}
        suspend fun seedNative() {
            withContext(Dispatchers.IO) {native.seed();native.seed(native.target)}
            withContext(Dispatchers.Main) {native.editor.openAsync(native.sourceUri,"source.xnote");native.stopDebounce()}
        }
        suspend fun opened(at:Uri=other):MapWorkspace.Entry {
            withContext(Dispatchers.Main) {assertTrue(workspace.open(at,file(at).name))}
            await {!workspace.opening && workspace.visible}
            return withContext(Dispatchers.Main) {requireNotNull(workspace.current).also {assertEquals(MapSaves.key(at),MapSaves.key(it.uri))}}
        }
        suspend fun acquire(at:Uri=uri):MapDocumentLease=withContext(Dispatchers.Main) {MapDocuments.acquire(native.context,at).also {held+=it}}
        suspend fun pair(at:Uri=uri,nativeUri:String=native.targetUri,mapFirst:Boolean=true,
            hostCurrent:()->Boolean={true},afterReady:suspend (PreparedPagedPairOpen)->Unit={}):Boolean {
            var result:Boolean?=null
            withContext(Dispatchers.Main) {assertTrue(workspace.openPair(at,file(at).name,mapFirst,
                prepareNative={valid->native.editor.preparePagedPairOpen(nativeUri,"target.xnote",stillCurrent=valid)?.also {afterReady(it)}},
                stillCurrent=hostCurrent,onFinished={result=it}))}
            await {result!=null};return withContext(Dispatchers.Main) {result!!}
        }
        suspend fun finish()=withContext(NonCancellable) {
            native.release.countDown();beforeRead=null;denyWrite=false
            val disposal=withContext(Dispatchers.Main) {
                for(s in (held.map {it.session}+listOfNotNull(workspace.current?.session)).distinct()) {
                    s.inkContact=false;s.textDraft?.let {s.cancelTextDraft(it.token)};s.textRestore?.let {s.completeTextRestore(it.token)}
                }
                workspace.dispose()
            }
            disposal.join()
            withContext(Dispatchers.Main) {held.map {it.close()}}.forEach {it.join()}
            scope.cancel()
        }
    }
    private fun scenario(block:suspend (Fixture)->Unit)=runParkingScenario {native->
        val f=Fixture(native);try {block(f)} finally {f.finish()}
    }

    @Test fun freshPairAdoptsBothActualDocumentsTogetherAndHonoursBothFileOrders() {
        for(mapFirst in listOf(true,false)) scenario {f->
            f.seedNative();val old=f.opened();val original=withContext(Dispatchers.Main) {f.native.editor.state.document}
            val incomingNativeId=withContext(Dispatchers.IO) {f.native.target.inputStream().use {f.native.codec.read(it,File(f.native.root,"pdf"),File(f.native.root,"images"))}.identity.documentId}
            var ready=false;var adopted=false;var finished:Boolean?=null
            withContext(Dispatchers.Main) {assertTrue(f.workspace.openPair(f.uri,"pair",mapFirst,
                prepareNative={valid->
                    val candidate=f.native.editor.preparePagedPairOpen(f.native.targetUri,"target.xnote",stillCurrent=valid)
                    assertSame("Native preparation must not replace the first side",original,f.native.editor.state.document)
                    assertSame("Map preparation must not replace the other side",old,f.workspace.current)
                    ready=candidate!=null;candidate
                },onPresented={entry,candidate->
                    assertTrue("Both sides become visible in the same synchronous callback",ready)
                    assertNotSame(original,candidate.target.state.document)
                    assertEquals(incomingNativeId,candidate.target.state.document.identity.documentId)
                    assertSame(entry,f.workspace.current);assertEquals(f.document.id,entry.session.document!!.id)
                    val layout=requireNotNull(f.workspace.splitPresentation.layout)
                    assertEquals(candidate.nativePane,layout.nativePane)
                    assertEquals(if(mapFirst) layout.mapPane else layout.nativePane,layout.focusedPane)
                    assertEquals(mapFirst,(layout.mapPane==Pane.PRIMARY)!=layout.reversed)
                    assertEquals(.5f,layout.ratio,0f);adopted=true
                },onFinished={finished=it}))}
            f.await {finished!=null}
            withContext(Dispatchers.Main) {
                assertEquals(true,finished);assertTrue(adopted);assertTrue(f.workspace.visible)
                assertNotSame(old,f.workspace.current);assertFalse(old.lease.alive)
                assertFalse(f.workspace.canReturnSource);assertNull(f.workspace.failure)
                assertEquals(incomingNativeId,f.native.editor.openPanes.single().state.document.identity.documentId)
            }
        }
    }

    @Test fun invalidSecondInitialReadLeavesOldMapAndUnconfirmedNativeTextUntouched()=scenario {f->
        f.seedNative();val old=f.opened()
        withContext(Dispatchers.IO) {f.native.target.writeText("not a native note")}
        var finished:Boolean?=null
        withContext(Dispatchers.Main) {
            f.native.editor.view.layout(0,0,1000,1000);f.native.editor.selectTool(Tool.TEXT_BOX)
            parkingPen(f.native.editor,MotionEvent.ACTION_DOWN,Pt(100.0,100.0));parkingPen(f.native.editor,MotionEvent.ACTION_UP,Pt(100.0,100.0))
            f.native.editor.updateEditingText("still an unconfirmed old-owner draft");old.session.selected="n"
        }
        val model=withContext(Dispatchers.Main) {f.native.editor.state.document}
        val history=withContext(Dispatchers.Main) {f.native.editor.history}
        val draft=withContext(Dispatchers.Main) {requireNotNull(f.native.editor.controller.activeTextItem())}
        val revision=withContext(Dispatchers.Main) {model.bodyRevision}
        val bytes=f.native.source.readBytes()
        withContext(Dispatchers.Main) {assertTrue(f.workspace.openPair(f.uri,"pair",true,
            prepareNative={valid->f.native.editor.preparePagedPairOpen(f.native.targetUri,"target.xnote",stillCurrent=valid)},
            onPresented={_,_->fail("An invalid second file cannot adopt either side")},onFinished={finished=it}))}
        f.await {finished!=null}
        withContext(Dispatchers.Main) {
            assertEquals(false,finished);assertSame(old,f.workspace.current);assertTrue(old.lease.alive)
            assertSame(model,f.native.editor.state.document);assertSame(history,f.native.editor.history)
            assertEquals(revision,model.bodyRevision);assertSame(draft,f.native.editor.controller.activeTextItem())
            assertEquals("still an unconfirmed old-owner draft",(draft as TextItem).text)
            assertEquals("n",old.session.selected);assertNull(f.workspace.splitPresentation.layout)
        }
        assertArrayEquals(bytes,f.native.source.readBytes());assertEquals(0,f.native.writes.get())
    }

    @Test fun aMapChangedAfterTheNativeCandidateIsReadyCannotPublishOnlyTheNativeHalf()=scenario {f->
        f.seedNative();val old=f.opened();val original=withContext(Dispatchers.Main) {f.native.editor.state.document}
        val nativeHistory=withContext(Dispatchers.Main) {f.native.editor.history}
        var ready=false
        assertFalse(f.pair(afterReady={candidate->
            assertTrue(candidate.current());ready=true
            withContext(Dispatchers.IO) {f.file(f.uri).writeBytes(MapCodec.encode(f.document.update("n") {it.copy(text="external final change")}))}
            assertSame(original,f.native.editor.state.document);assertSame(old,f.workspace.current)
        }))
        withContext(Dispatchers.Main) {
            assertTrue(ready);assertSame(original,f.native.editor.state.document);assertSame(nativeHistory,f.native.editor.history)
            assertSame(old,f.workspace.current);assertTrue(old.lease.alive);assertNull(f.workspace.splitPresentation.layout)
            assertEquals(MapWorkspaceFailure.CHANGED,f.workspace.failure)
        }
        assertEquals("external final change",MapCodec.decode(f.file(f.uri).readBytes()).node("n").text)
    }

    @Test fun aRetiredHostAfterBothReadsCannotAdoptEitherSideOrRebindTheNewWorkspace()=scenario {f->
        f.seedNative();val old=f.opened();val model=withContext(Dispatchers.Main) {f.native.editor.state.document}
        var live=true;var ready=false
        assertFalse(f.pair(hostCurrent={live},afterReady={candidate->
            assertTrue(candidate.current());ready=true;live=false
        }))
        withContext(Dispatchers.Main) {
            assertTrue(ready);assertSame(old,f.workspace.current);assertSame(model,f.native.editor.state.document)
            assertTrue(old.lease.alive);assertNull(f.workspace.splitPresentation.layout)
        }
        live=true
        assertTrue(f.pair(hostCurrent={live}))
        withContext(Dispatchers.Main) {assertEquals(f.document.id,f.workspace.current!!.session.document!!.id);assertNotSame(model,f.native.editor.state.document)}
    }

    @Test fun anotherOwnersSelectedMapTextMustFinishNaturallyEvenWhenNativeIsAlreadyReady()=scenario {f->
        f.seedNative();val old=f.opened();val held=f.acquire();f.await {!held.session.loading}
        val session=held.session;val nativeModel=withContext(Dispatchers.Main) {f.native.editor.state.document}
        var ready=false
        assertFalse(f.pair(afterReady={candidate->
            assertTrue(candidate.current());ready=true
            assertTrue(session.beginText("n",MindMapView.Viewport(1f,0f,0f),1000,700))
            assertTrue(session.updateTextDraft(session.textDraft!!.token,TextFieldValue("another owner's unfinished draft")))
        }))
        val draft=withContext(Dispatchers.Main) {requireNotNull(session.textDraft)}
        withContext(Dispatchers.Main) {
            assertTrue(ready);assertSame(old,f.workspace.current);assertSame(nativeModel,f.native.editor.state.document)
            assertTrue(held.alive);assertSame(draft,session.textDraft);assertFalse(session.canUndo)
            f.workspace.cancelOpen();assertSame(draft,session.textDraft)
            assertTrue(session.finishTextDraft(draft.token));session.textRestore?.let {session.completeTextRestore(it.token)}
            session.cancelPendingAutosave();assertEquals("another owner's unfinished draft",session.document!!.node("n").text)
        }
        val finishedModel=withContext(Dispatchers.Main) {session.document}
        assertTrue(f.pair())
        withContext(Dispatchers.Main) {
            assertSame(session,f.workspace.current!!.session);assertSame(finishedModel,session.document)
            assertTrue(session.canUndo);assertTrue(held.alive);assertNull(session.textDraft)
        }
    }

    @Test fun aRealSelectedMapStrokeSurvivesARejectedPairAndCommitsBeforeTheNextAttempt()=scenario {f->
        f.seedNative();val old=f.opened();val lease=f.acquire();f.await {!lease.session.loading}
        val session=lease.session
        val view=withContext(Dispatchers.Main) {
            assertTrue(session.operate {it.copy(nodes=it.nodes+MapNode(id="ink",parent="r",kind=NodeKind.INK,x=400.0,y=120.0,width=320.0,height=220.0))})
            session.cancelPendingAutosave()
            MindMapView(f.native.context).apply {
                document=session.document!!;documentEpoch=session.interactionEpoch;documentRevision=session.contentRevision
                onSelect={session.selected=it};onInkContact={session.inkContact=it}
                onInk={id,before,after->session.commitInk(id,before,after).also {if(it) document=session.document!!}}
                layout(0,0,1000,800);fit()
            }
        }
        val start=SystemClock.uptimeMillis();var tick=0L
        fun event(action:Int,point:Pt) {
            val p=view.screenPoint(point)
            val prop=MotionEvent.PointerProperties().apply {id=9;toolType=MotionEvent.TOOL_TYPE_STYLUS}
            val coord=MotionEvent.PointerCoords().apply {x=p.x.toFloat();y=p.y.toFloat();pressure=.6f}
            val raw=MotionEvent.obtain(start,start+tick++,action,1,arrayOf(prop),arrayOf(coord),0,0,1f,1f,0,0,InputDevice.SOURCE_STYLUS,0)
            try {assertTrue(view.onTouchEvent(raw))} finally {raw.recycle()}
        }
        val model=withContext(Dispatchers.Main) {f.native.editor.state.document}
        assertFalse(f.pair(afterReady={candidate->
            assertTrue(candidate.current());event(MotionEvent.ACTION_DOWN,Pt(480.0,200.0));event(MotionEvent.ACTION_MOVE,Pt(515.0,225.0))
            assertTrue(session.inkContact);assertTrue(view.inkInput.active)
        }))
        withContext(Dispatchers.Main) {
            assertSame(old,f.workspace.current);assertSame(model,f.native.editor.state.document)
            f.workspace.cancelOpen();assertTrue(session.inkContact);assertTrue(view.inkInput.active)
            assertTrue(session.document!!.node("ink").ink.isEmpty())
            event(MotionEvent.ACTION_UP,Pt(550.0,245.0))
            assertFalse(session.inkContact);assertFalse(view.inkInput.active)
            assertEquals(1,session.document!!.node("ink").ink.size);assertTrue(session.document!!.node("ink").ink.single().points.size>=3)
            session.cancelPendingAutosave()
        }
        assertTrue(f.pair())
        withContext(Dispatchers.Main) {assertSame(session,f.workspace.current!!.session);assertEquals(1,session.document!!.node("ink").ink.size);assertTrue(session.canUndo)}
    }

    @Test fun aVisitedMapBecomesTheNewPairRootWithItsExistingHistoryAndCamera()=scenario {f->
        f.seedNative()
        withContext(Dispatchers.IO) {
            val old=MapCodec.decode(f.file(f.other).readBytes())
            val source=MapSource(documentId=f.document.id,fileName="pair.xmap")
            f.file(f.other).writeBytes(MapCodec.encode(old.update("n") {it.copy(sources=listOf(source),primarySource=source.id)}))
        }
        val origin=f.opened();val incoming=f.acquire();f.await {!incoming.session.loading}
        val camera=MindMapView.Viewport(1.3f,45f,-35f)
        val prepared=withContext(Dispatchers.Main) {
            val session=origin.session;val presentation=origin.presentation
            assertTrue(session.operate {it.update("n") {node->node.copy(text="retained visited text")}});session.cancelPendingAutosave()
            val view=MindMapView(f.native.context).apply {document=session.document!!;documentEpoch=session.interactionEpoch;layout(0,0,1000,700)}
            presentation.synchronize(session);view.restoreViewport(camera,1000,700,view.resources.displayMetrics.density)
            assertTrue(presentation.capture(session,view));session.selected="n"
            val source=session.document!!.node("n").sources.single()
            val ticket=session.captureSourceNavigation("n",source.id,source.target)!!
            session.captureSourceReturn(ticket)!! to presentation.captureSourceDeparture(session)
        }
        val originalDocument=withContext(Dispatchers.Main) {origin.session.document}
        withContext(Dispatchers.Main) {
            assertEquals(SourcePushResult.APPLIED,f.workspace.pushSource(origin,incoming,"pair",prepared.first,prepared.second) {true})
            assertTrue(f.workspace.canReturnSource);assertNotSame(origin,f.workspace.current)
        }
        assertTrue(f.pair(at=f.other,nativeUri=f.native.sourceUri))
        withContext(Dispatchers.Main) {
            assertSame(origin,f.workspace.current);assertSame(originalDocument,origin.session.document);assertTrue(origin.session.canUndo)
            assertTrue(origin.lease.alive);assertFalse(f.workspace.canReturnSource);assertNull(f.workspace.sourceReturnToken)
            val restored=MindMapView(f.native.context).apply {document=origin.session.document!!;documentEpoch=origin.session.interactionEpoch;layout(0,0,1000,700)}
            assertTrue(origin.presentation.restore(origin.session,restored));val actual=restored.viewport()
            assertEquals(camera.zoom,actual.zoom,.000001f);assertEquals(camera.panX,actual.panX,.001f);assertEquals(camera.panY,actual.panY,.001f)
            origin.session.undo();origin.session.cancelPendingAutosave();assertEquals("saved",origin.session.document!!.node("n").text)
        }
    }

    @Test fun rejectingAnIncomingPairLeaseDoesNotCancelAnotherOwnersRealAutosave()=scenario {f->
        f.seedNative();val old=f.opened();val held=f.acquire();f.await {!held.session.loading}
        val session=held.session
        val debounce=withContext(Dispatchers.Main) {
            assertTrue(session.operate {it.update("n") {node->node.copy(text="another owner's committed body")}})
            MapSession::class.java.getDeclaredField("debounce").apply {isAccessible=true}.get(session) as Job
        }
        withContext(Dispatchers.IO) {f.native.target.writeText("invalid native candidate")}
        assertFalse(f.pair())
        withContext(Dispatchers.Main) {
            assertSame(old,f.workspace.current);assertTrue(held.alive);assertFalse(session.closing)
            assertFalse("Only this pair lease failed; another owner's debounce must not be cancelled",debounce.isCancelled)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(400,java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        f.await {!session.saving && f.writes.get()>0}
        assertEquals("another owner's committed body",MapCodec.decode(f.file(f.uri).readBytes()).node("n").text)
    }

    @Test fun theSameUuidAtTwoLiveMapLocationsIsRejectedBeforeNativePreparation()=scenario {f->
        f.seedNative()
        withContext(Dispatchers.IO) {f.file(f.other).writeBytes(MapCodec.encode(f.document))}
        val old=f.opened();val native=withContext(Dispatchers.Main) {f.native.editor.state.document}
        var prepared=false;var result:Boolean?=null
        withContext(Dispatchers.Main) {assertTrue(f.workspace.openPair(f.uri,"duplicate",true,
            prepareNative={valid->prepared=true;f.native.editor.preparePagedPairOpen(f.native.targetUri,"target.xnote",stillCurrent=valid)},onFinished={result=it}))}
        f.await {result!=null}
        withContext(Dispatchers.Main) {
            assertEquals(false,result);assertFalse(prepared);assertSame(old,f.workspace.current);assertSame(native,f.native.editor.state.document)
            assertTrue(old.lease.alive);assertNull(f.workspace.splitPresentation.layout)
        }
    }

    @Test fun aNewPairWaitsForTheCancelledCandidatesCleanupAndTheOldCompletionCannotClearIt()=scenario {f->
        f.seedNative();val old=f.opened();val initial=withContext(Dispatchers.Main) {f.native.editor.state.document}
        val firstReady=CompletableDeferred<Unit>();val holdFirst=CompletableDeferred<Unit>()
        var firstCandidate:PreparedPagedPairOpen?=null;var firstFinished:Boolean?=null;var secondFinished:Boolean?=null
        withContext(Dispatchers.Main) {assertTrue(f.workspace.openPair(f.uri,"first",true,
            prepareNative={valid->
                val candidate=f.native.editor.preparePagedPairOpen(f.native.targetUri,"target.xnote",stillCurrent=valid)
                if(candidate!=null) {
                    firstCandidate=candidate;firstReady.complete(Unit)
                    try {holdFirst.await()}
                    catch(cancelled:CancellationException) {withContext(NonCancellable) {candidate.close()};throw cancelled}
                }
                candidate
            },onPresented={_,_->fail("The retired first attempt must never publish")},onFinished={firstFinished=it}))}
        firstReady.await()
        withContext(Dispatchers.Main) {
            assertNotNull(firstCandidate);assertTrue(firstCandidate!!.current())
            assertSame(old,f.workspace.current);assertSame(initial,f.native.editor.state.document)
            assertTrue(f.workspace.openPair(f.alias,"second",false,
                prepareNative={valid->f.native.editor.preparePagedPairOpen(f.native.targetUri,"target.xnote",stillCurrent=valid)},
                onFinished={secondFinished=it}))
        }
        f.await {firstFinished!=null && secondFinished!=null}
        withContext(Dispatchers.Main) {
            assertEquals(false,firstFinished);assertEquals(true,secondFinished);assertFalse(f.workspace.opening)
            assertEquals(f.document.id,f.workspace.current!!.session.document!!.id);assertTrue(f.workspace.visible)
            assertNotSame(initial,f.native.editor.state.document);assertFalse(firstCandidate!!.current());assertFalse(firstCandidate!!.commit())
            val layout=f.workspace.splitPresentation.layout!!;assertEquals(layout.nativePane,layout.focusedPane)
            assertNull(f.workspace.failure)
        }
    }

    @Test fun aForeignNonPendingCheckpointIsRejectedEvenWhenTheRawMapWouldWinRecovery()=scenario {f->
        f.seedNative();val old=f.opened()
        val model=withContext(Dispatchers.Main) {f.native.editor.state.document}
        val nativeHistory=withContext(Dispatchers.Main) {f.native.editor.history}
        val mapModel=withContext(Dispatchers.Main) {old.session.document}
        val mapHistory=withContext(Dispatchers.Main) {
            old.session.selected="n"
            MapSession::class.java.getDeclaredField("history").apply {isAccessible=true}.get(old.session)
        }
        val originalRaw=f.file(f.uri).readBytes();val nativeSource=f.native.source.readBytes();val nativeTarget=f.native.target.readBytes()
        val foreign=f.document.copy(id=UUID.randomUUID().toString()).update("n") {it.copy(text="another map's completed checkpoint")}
        val journal=withContext(Dispatchers.IO) {
            MapSaves.lockFor(f.uri).withLock {
                val store=MapFiles.store(f.native.context,f.uri)
                assertEquals(f.document.id,store.open().document.id)
                store.save(foreign)
                assertEquals(foreign.id,MapCodec.decode(f.file(f.uri).readBytes()).id)
                val actual=File(f.native.context.noBackupFilesDir,"mind-map-recovery").walkTopDown().filter {it.isFile && it.extension=="json"}
                    .single {file->runCatching {org.json.JSONObject(file.readText()).getJSONObject("document").getString("id")==foreign.id}.getOrDefault(false)}
                val checkpoint=org.json.JSONObject(actual.readText())
                assertFalse("The actual save completed its checkpoint",checkpoint.getBoolean("pending"))
                assertEquals(foreign.id,checkpoint.getJSONObject("document").getString("id"))
                // Reproduce a readable A beside a completed checkpoint for unrelated B. Default
                // winner selection uses A, but a pair must inspect every retained checkpoint ID.
                f.file(f.uri).writeBytes(originalRaw)
                actual
            }
        }
        val checkpointBytes=journal.readBytes();val setupWrites=f.writes.get();var prepared=false;var result:Boolean?=null
        assertTrue("Setup must create a real completed save rather than a forged journal",setupWrites>0)
        withContext(Dispatchers.Main) {assertTrue(f.workspace.openPair(f.uri,"foreign checkpoint",true,
            prepareNative={valid->prepared=true;f.native.editor.preparePagedPairOpen(f.native.targetUri,"target.xnote",stillCurrent=valid)},onFinished={result=it}))}
        f.await {result!=null}
        withContext(Dispatchers.Main) {
            assertEquals(false,result);assertFalse("Foreign recovery identity must fail before native preparation",prepared)
            assertSame(old,f.workspace.current);assertTrue(old.lease.alive);assertSame(mapModel,old.session.document)
            assertSame(mapHistory,MapSession::class.java.getDeclaredField("history").apply {isAccessible=true}.get(old.session))
            assertEquals("n",old.session.selected);assertSame(model,f.native.editor.state.document);assertSame(nativeHistory,f.native.editor.history)
            assertNull(f.workspace.splitPresentation.layout)
        }
        assertArrayEquals("A must not be republished or replaced",originalRaw,f.file(f.uri).readBytes())
        assertArrayEquals("The foreign checkpoint remains available for explicit recovery",checkpointBytes,journal.readBytes())
        assertArrayEquals(nativeSource,f.native.source.readBytes());assertArrayEquals(nativeTarget,f.native.target.readBytes())
        assertEquals(setupWrites,f.writes.get());assertEquals(0,f.native.writes.get())
    }

    private fun aRawChangeDuringFreshActorLoad(returnToA:Boolean)=scenario {f->
        f.seedNative();val old=f.opened()
        val nativeModel=withContext(Dispatchers.Main) {f.native.editor.state.document}
        val nativeHistory=withContext(Dispatchers.Main) {f.native.editor.history}
        val nativeRevision=withContext(Dispatchers.Main) {nativeModel.bodyRevision}
        val oldMap=withContext(Dispatchers.Main) {old.session.document}
        val oldMapHistory=withContext(Dispatchers.Main) {
            old.session.selected="n"
            MapSession::class.java.getDeclaredField("history").apply {isAccessible=true}.get(old.session)
        }
        val bytesA=f.file(f.uri).readBytes()
        val documentB=f.document.update("n") {it.copy(text="B read only by the fresh actor")}
        val bytesB=MapCodec.encode(documentB)
        assertEquals(f.document.id,documentB.id);assertFalse(bytesA.contentEquals(bytesB))
        val nativeSource=f.native.source.readBytes();val nativeTarget=f.native.target.readBytes();val priorMap=f.file(f.other).readBytes()
        val incomingReads=AtomicInteger();val observed=mutableListOf<Pair<Int,String>>()
        f.beforeRead={uri->if(DocumentsContract.getDocumentId(uri)==DocumentsContract.getDocumentId(f.uri)) {
            val read=incomingReads.incrementAndGet()
            if(read==2) f.file(f.uri).writeBytes(bytesB)
            if(read==3 && returnToA) f.file(f.uri).writeBytes(bytesA)
            observed+=read to MapCodec.decode(f.file(f.uri).readBytes()).node("n").text
        }}
        var prepareReached=false;var guards=0;var adopted=false;var result:Boolean?=null
        withContext(Dispatchers.Main) {assertTrue(f.workspace.openPair(f.uri,"read-race",true,
            prepareNative={valid->
                prepareReached=true
                f.native.editor.preparePagedPairOpen(f.native.targetUri,"target.xnote",stillCurrent=valid,
                    guardOwners={_,_->guards++;emptySet()})
            },onPresented={_,_->adopted=true},onFinished={result=it}))}
        f.await {result!=null};f.beforeRead=null
        withContext(Dispatchers.Main) {
            assertEquals(false,result);assertFalse("A fresh actor loaded from a different raw version must not become either half",adopted)
            assertFalse("The fresh-load mismatch is detected before Native preparation",prepareReached)
            assertEquals(0,guards);assertSame(old,f.workspace.current);assertTrue(old.lease.alive)
            assertSame(oldMap,old.session.document);assertEquals("n",old.session.selected)
            assertSame(oldMapHistory,MapSession::class.java.getDeclaredField("history").apply {isAccessible=true}.get(old.session))
            assertSame(nativeModel,f.native.editor.state.document);assertSame(nativeHistory,f.native.editor.history)
            assertEquals(nativeRevision,nativeModel.bodyRevision);assertNull(f.workspace.splitPresentation.layout)
        }
        assertTrue("The race includes initial raw, actual actor load and the following proof read",incomingReads.get()>=3)
        assertEquals("saved",observed.first {it.first==1}.second)
        assertEquals("B read only by the fresh actor",observed.first {it.first==2}.second)
        assertEquals(if(returnToA) "saved" else "B read only by the fresh actor",observed.first {it.first==3}.second)
        assertArrayEquals("The user's external replacement is observed, never rolled back",if(returnToA) bytesA else bytesB,f.file(f.uri).readBytes())
        assertArrayEquals(priorMap,f.file(f.other).readBytes());assertArrayEquals(nativeSource,f.native.source.readBytes());assertArrayEquals(nativeTarget,f.native.target.readBytes())
        assertEquals(0,f.writes.get());assertEquals(0,f.native.writes.get())
    }
    @Test fun changingRawAIntoSameUuidBDuringTheFreshActorLoadCannotBindTheWrongBody()=aRawChangeDuringFreshActorLoad(false)
    @Test fun rawAToBToAStillRejectsTheFreshActorThatActuallyLoadedB()=aRawChangeDuringFreshActorLoad(true)

    @Test fun aReservedOriginalPdfUuidIsRejectedBeforeActorDiscoveryCanRegisterItAsAMap()=scenario {f->
        f.seedNative();val old=f.opened()
        val nativeModel=withContext(Dispatchers.Main) {f.native.editor.state.document}
        val nativeHistory=withContext(Dispatchers.Main) {f.native.editor.history}
        val oldMap=withContext(Dispatchers.Main) {old.session.document}
        val oldHistory=withContext(Dispatchers.Main) {
            old.session.selected="n"
            MapSession::class.java.getDeclaredField("history").apply {isAccessible=true}.get(old.session)
        }
        val registry=f.native.storage.registry;val catalog=OriginalPdfCatalog(registry)
        // Only PDF parsing/pixels are replaced; admission, immutable object, leases, retained
        // identity and permanent page mapping all use the real production authorization chain.
        val access=OriginalPdfAccess(f.native.context,engineFactory={object:OriginalPdfEngine {
            override val pageCount=2
            override fun pageInfo(pdfKey:Int)=OriginalPdfPageInfo(Pt(600.0,800.0),PdfPageGeometry.flipped(800.0))
            override fun renderRegion(pdfKey:Int,pxPerPt:Double,leftPx:Int,topPx:Int,width:Int,height:Int):com.xnotes.platform.AndroidRasterSurface =
                error("Rejecting a map identity must never rasterize the original")
            override fun closeAndAwait() {}
        }},storageOverride=f.native.storage)
        val original=withContext(Dispatchers.IO) {
            val file=File(f.native.root,"reserved-original.pdf").apply {writeText("immutable actual admitted original")}
            val candidate=access.candidate(file,"Reserved original")
            try {(catalog.retain(candidate) as OriginalPdfCatalog.Retention.Retained).observation}
            finally {candidate.closeAndAwait()}
        }
        val record=original.record
        val reader=withContext(Dispatchers.IO) {access.open(MapSourceTarget(record.id,SourceScope.PDF_ORIGINAL,record.pages[1],
            originalPdf=PdfOriginalProjection(record.sha256,1)))}
        try {
            val rawMap=MapCodec.encode(f.document.copy(id=record.id))
            withContext(Dispatchers.IO) {f.file(f.uri).writeBytes(rawMap)}
            val nativeBytes=f.native.source.readBytes();val targetBytes=f.native.target.readBytes();val oldBytes=f.file(f.other).readBytes()
            fun nativeRows()=registry.readableDatabase.rawQuery("SELECT id,kind,name FROM documents WHERE id=?",arrayOf(record.id)).use {c->
                buildList {while(c.moveToNext()) add(listOf(c.getString(0),c.getString(1),c.getString(2)))}
            }
            fun leaseRows()=registry.readableDatabase.rawQuery("SELECT token,resource_id,expires FROM leases WHERE resource_id=? ORDER BY token",arrayOf(record.sha256)).use {c->
                buildList {while(c.moveToNext()) add(listOf(c.getString(0),c.getString(1),c.getLong(2).toString()))}
            }
            val before=withContext(Dispatchers.IO) {
                assertTrue(original.current());assertTrue(reader.current());assertTrue(nativeRows().isEmpty())
                assertNull(registry.expectationAt(f.uri.toString()));assertNull(registry.kindForId(record.id))
                assertEquals(setOf(record.sha256),registry.referenceIds(OriginalPdfCatalog.owner(record.id)))
                val leases=leaseRows();assertTrue("The actual original reader owns a live resource lease",leases.isNotEmpty())
                val resource=File(f.native.storage.pdfResources.directory,"${record.sha256}.pdf")
                leases to resource.readBytes()
            }
            var prepared=false;var result:Boolean?=null
            withContext(Dispatchers.Main) {
                assertTrue(MapDocuments.observations(f.native.context).none {it.key==MapSaves.key(f.uri) || it.document?.id==record.id})
                assertTrue(f.workspace.openPair(f.uri,"reserved identity",true,
                    prepareNative={valid->prepared=true;f.native.editor.preparePagedPairOpen(f.native.targetUri,"target.xnote",stillCurrent=valid)},onFinished={result=it}))
            }
            f.await {result!=null}
            withContext(Dispatchers.Main) {
                assertEquals(false,result);assertFalse(prepared);assertSame(old,f.workspace.current);assertTrue(old.lease.alive)
                assertSame(oldMap,old.session.document);assertEquals("n",old.session.selected)
                assertSame(oldHistory,MapSession::class.java.getDeclaredField("history").apply {isAccessible=true}.get(old.session))
                assertSame(nativeModel,f.native.editor.state.document);assertSame(nativeHistory,f.native.editor.history)
                assertNull(f.workspace.splitPresentation.layout)
                assertTrue("No rejected reserved identity may enter the actor directory",MapDocuments.observations(f.native.context)
                    .none {it.key==MapSaves.key(f.uri) || it.document?.id==record.id})
            }
            withContext(Dispatchers.IO) {
                assertNull("The rejected URI must not gain a native/map location",registry.expectationAt(f.uri.toString()))
                assertNull(registry.kindForId(record.id));assertTrue("Reserved identities never enter documents",nativeRows().isEmpty())
                val after=requireNotNull(catalog.observe(record.id));assertEquals(record,after.record)
                assertEquals(record.pages,after.record.pages);assertTrue(original.current());assertTrue(reader.current())
                val domain=catalog.identityDomains(setOf(record.id))
                assertTrue(domain.supported && domain.current());assertEquals(setOf(record.id),domain.reservedOriginalIds)
                assertTrue(domain.nativeIdentityIds.isEmpty())
                assertEquals(setOf(record.sha256),registry.referenceIds(OriginalPdfCatalog.owner(record.id)))
                assertEquals(before.first,leaseRows())
                assertArrayEquals(before.second,File(f.native.storage.pdfResources.directory,"${record.sha256}.pdf").readBytes())
            }
            assertArrayEquals(rawMap,f.file(f.uri).readBytes());assertArrayEquals(oldBytes,f.file(f.other).readBytes())
            assertArrayEquals(nativeBytes,f.native.source.readBytes());assertArrayEquals(targetBytes,f.native.target.readBytes())
            assertEquals(0,f.writes.get());assertEquals(0,f.native.writes.get())

            // Reuse the same real retained original and active reader to test the Native
            // candidate boundary. The normal Map is allowed its own derived discovery row.
            val normalRaw=MapCodec.encode(f.document)
            val reservedNative=withContext(Dispatchers.IO) {
                f.file(f.uri).writeBytes(normalRaw)
                val note=Document(mutableListOf(Page(500.0,500.0))).apply {
                    identity=identity.copy(documentId=record.id);enableReferenceAnchors()
                }
                f.native.target.outputStream().use {f.native.codec.write(note.fixedWriteSnapshot(),it)}
                f.native.target.readBytes()
            }
            var nativeReached=false;var nativeGuards=0;var nativeResult:Boolean?=null;var nativeAdopted=false
            withContext(Dispatchers.Main) {assertTrue(f.workspace.openPair(f.uri,"native reserved identity",true,
                prepareNative={valid->
                    nativeReached=true
                    f.native.editor.preparePagedPairOpen(f.native.targetUri,"target.xnote",stillCurrent=valid,
                        guardOwners={_,_->nativeGuards++;emptySet()})
                },onPresented={_,_->nativeAdopted=true},onFinished={nativeResult=it}))}
            f.await {nativeResult!=null}
            withContext(Dispatchers.Main) {
                assertEquals(false,nativeResult);assertTrue("The ordinary Map reaches the Native boundary",nativeReached)
                assertEquals(0,nativeGuards);assertFalse(nativeAdopted)
                assertSame(old,f.workspace.current);assertTrue(old.lease.alive);assertSame(oldMap,old.session.document)
                assertSame(oldHistory,MapSession::class.java.getDeclaredField("history").apply {isAccessible=true}.get(old.session))
                assertEquals("n",old.session.selected);assertSame(nativeModel,f.native.editor.state.document);assertSame(nativeHistory,f.native.editor.history)
                assertNull(f.workspace.splitPresentation.layout)
            }
            withContext(Dispatchers.IO) {
                assertEquals(f.document.id,registry.expectationAt(f.uri.toString())?.documentId)
                assertEquals("xmap",registry.kindForId(f.document.id))
                assertEquals(setOf(MapSaves.key(f.uri)),registry.locations(f.document.id).map {MapSaves.key(Uri.parse(it))}.toSet())
                assertNull("Native reserved identity does not gain a target location",registry.expectationAt(f.native.targetUri))
                assertNull(registry.kindForId(record.id));assertTrue(nativeRows().isEmpty())
                assertEquals(record,requireNotNull(catalog.observe(record.id)).record);assertTrue(original.current());assertTrue(reader.current())
                val domain=catalog.identityDomains(setOf(record.id))
                assertTrue(domain.current() && domain.supported);assertEquals(setOf(record.id),domain.reservedOriginalIds)
                assertTrue(domain.nativeIdentityIds.isEmpty())
                assertEquals(setOf(record.sha256),registry.referenceIds(OriginalPdfCatalog.owner(record.id)))
                assertEquals(before.first,leaseRows())
                assertArrayEquals(before.second,File(f.native.storage.pdfResources.directory,"${record.sha256}.pdf").readBytes())
            }
            assertArrayEquals(normalRaw,f.file(f.uri).readBytes());assertArrayEquals(reservedNative,f.native.target.readBytes())
            assertArrayEquals(oldBytes,f.file(f.other).readBytes());assertArrayEquals(nativeBytes,f.native.source.readBytes())
            assertEquals(0,f.writes.get());assertEquals(0,f.native.writes.get())
        } finally {withContext(NonCancellable+Dispatchers.IO) {reader.closeAndAwait()}}
    }

    @Test fun aMapAndNativeCandidateWithTheSameUuidCannotShareOneEditingIdentity()=scenario {f->
        f.seedNative();val old=f.opened()
        val nativeModel=withContext(Dispatchers.Main) {f.native.editor.state.document}
        val nativeHistory=withContext(Dispatchers.Main) {f.native.editor.history}
        val oldMap=withContext(Dispatchers.Main) {old.session.document}
        val oldHistory=withContext(Dispatchers.Main) {
            old.session.selected="n"
            MapSession::class.java.getDeclaredField("history").apply {isAccessible=true}.get(old.session)
        }
        val nativeTarget=withContext(Dispatchers.IO) {
            val note=Document(mutableListOf(Page(500.0,500.0))).apply {
                identity=identity.copy(documentId=f.document.id);enableReferenceAnchors()
            }
            f.native.target.outputStream().use {f.native.codec.write(note.fixedWriteSnapshot(),it)}
            f.native.target.readBytes()
        }
        val rawMap=f.file(f.uri).readBytes();val rawOldMap=f.file(f.other).readBytes();val nativeSource=f.native.source.readBytes()
        val registry=f.native.storage.registry
        withContext(Dispatchers.IO) {assertNull(registry.expectationAt(f.uri.toString()));assertNull(registry.kindForId(f.document.id));assertNull(registry.expectationAt(f.native.targetUri))}
        var nativeReached=false;var guards=0;var adopted=false;var result:Boolean?=null
        withContext(Dispatchers.Main) {assertTrue(f.workspace.openPair(f.uri,"same identity",true,
            prepareNative={valid->
                nativeReached=true
                f.native.editor.preparePagedPairOpen(f.native.targetUri,"target.xnote",stillCurrent=valid,guardOwners={_,_->guards++;emptySet()})
            },onPresented={_,_->adopted=true},onFinished={result=it}))}
        f.await {result!=null}
        withContext(Dispatchers.Main) {
            assertEquals(false,result);assertTrue("The valid Map reaches the Native candidate check",nativeReached)
            assertEquals(0,guards);assertFalse(adopted);assertSame(old,f.workspace.current);assertTrue(old.lease.alive)
            assertSame(oldMap,old.session.document);assertEquals("n",old.session.selected)
            assertSame(oldHistory,MapSession::class.java.getDeclaredField("history").apply {isAccessible=true}.get(old.session))
            assertSame(nativeModel,f.native.editor.state.document);assertSame(nativeHistory,f.native.editor.history)
            assertNull(f.workspace.splitPresentation.layout)
        }
        withContext(Dispatchers.IO) {
            assertEquals("The ordinary Map discovery remains accurate",f.document.id,registry.expectationAt(f.uri.toString())?.documentId)
            assertEquals("xmap",registry.kindForId(f.document.id))
            assertEquals(setOf(MapSaves.key(f.uri)),registry.locations(f.document.id).map {MapSaves.key(Uri.parse(it))}.toSet())
            assertNull("The same-UUID Native never gains a second location",registry.expectationAt(f.native.targetUri))
        }
        assertArrayEquals(rawMap,f.file(f.uri).readBytes());assertArrayEquals(rawOldMap,f.file(f.other).readBytes())
        assertArrayEquals(nativeSource,f.native.source.readBytes());assertArrayEquals(nativeTarget,f.native.target.readBytes())
        assertEquals(0,f.writes.get());assertEquals(0,f.native.writes.get())
    }
}
