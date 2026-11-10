package com.xnotes.pen

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.provider.DocumentsContract
import android.view.MotionEvent
import com.xnotes.core.geometry.Pt
import com.xnotes.core.tools.Tool
import com.xnotes.mindmap.*
import com.xnotes.ui.*
import androidx.compose.ui.text.input.TextFieldValue
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** The actual explicit saved-split entry must prepare both files before changing live owners. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class NativeSplitReplacementHostTest {
    private class Fixture(val native:EditorPageCopyFixture) {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val maps=MapWorkspace(native.context,scope)
        val authority="native-split-map-${UUID.randomUUID()}"
        val uri=DocumentsContract.buildDocumentUri(authority,"root/A.xmap")
        val other=DocumentsContract.buildDocumentUri(authority,"root/B.xmap")
        val retryUri=DocumentsContract.buildDocumentUri(native.authority,"retry").toString()
        val secondDocument=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null),MapNode(id="n",parent="r",text="B saved")))
        val link=MapSource(documentId=secondDocument.id,fileName="B.xmap")
        val document=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null),MapNode(id="n",parent="r",text="A saved",sources=listOf(link),primarySource=link.id)))
        val writes=AtomicInteger();val writeAttempts=AtomicInteger();val reads=AtomicInteger()
        @Volatile var denyWrite=false
        @Volatile var beforeRead:((Uri)->Unit)?=null
        val extra=mutableListOf<MapDocumentLease>()
        var guardCalls=0;var uiGuards=0
        fun file(at:Uri)=File(native.root,DocumentsContract.getDocumentId(at).substringAfterLast('/'))
        private val provider=object:ContentProvider() {
            override fun onCreate()=true
            override fun getType(uri:Uri)="application/octet-stream"
            override fun query(uri:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,sort:String?):Cursor =
                MatrixCursor(arrayOf(android.provider.OpenableColumns.DISPLAY_NAME,android.provider.OpenableColumns.SIZE)).apply {addRow(arrayOf(file(uri).name,file(uri).length()))}
            override fun insert(uri:Uri,values:ContentValues?):Uri?=null
            override fun update(uri:Uri,values:ContentValues?,s:String?,a:Array<out String>?)=0
            override fun delete(uri:Uri,s:String?,a:Array<out String>?):Int=error("Replacing a workspace must never delete a Map")
            override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
                if('w' in mode) {writeAttempts.incrementAndGet();if(denyWrite) throw IOException("Controlled Map save failure");writes.incrementAndGet()}
                else {reads.incrementAndGet();beforeRead?.invoke(uri)}
                return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.parseMode(mode))
            }
        }
        init {
            provider.attachInfo(native.context,ProviderInfo().apply {this.authority=this@Fixture.authority})
            ShadowContentResolver.registerProviderInternal(authority,provider)
            file(uri).writeBytes(MapCodec.encode(document));file(other).writeBytes(MapCodec.encode(secondDocument))
        }
        suspend fun await(check:()->Boolean)=withTimeout(10000L) {while(!withContext(Dispatchers.Main) {check()}) delay(1)}
        suspend fun start():MapWorkspace.Entry {
            withContext(Dispatchers.IO) {native.seed();native.seed(native.target);native.seed(native.retry)}
            withContext(Dispatchers.Main) {native.editor.openAsync(native.sourceUri,"source.xnote");native.stopDebounce();assertTrue(maps.open(uri,"A"))}
            await {!maps.opening && maps.visible};return withContext(Dispatchers.Main) {requireNotNull(maps.current)}
        }
        suspend fun acquire(at:Uri=uri):MapDocumentLease {
            val lease=withContext(Dispatchers.Main) {MapDocuments.acquire(native.context,at).also(extra::add)}
            await {!lease.session.loading};return lease
        }
        fun edit(entry:MapWorkspace.Entry,text:String) {assertTrue(entry.session.operate {it.update("n") {node->node.copy(text=text)}});entry.session.cancelPendingAutosave()}
        suspend fun beginReplacement(hostCurrent:()->Boolean={true},settle:Boolean=true,
            afterReady:suspend (PreparedNativeSplitOpen)->Unit={},onPresented:(PreparedNativeSplitOpen)->Unit={}):CompletableDeferred<Boolean> {
            val done=CompletableDeferred<Boolean>()
            withContext(Dispatchers.Main) {assertTrue(maps.replaceWithNativeSplit(
                prepareNative={valid,settleMaps->
                    native.editor.prepareNativePairOpen(NativeSplitSource(native.targetUri,"target.xnote"),NativeSplitSource(retryUri,"retry.xnote"),
                        stillCurrent=valid,guardOwners={_,_->
                            guardCalls++
                            if(!settle || settleMaps()) {uiGuards++;emptySet()} else null
                        })?.also {afterReady(it)}
                },stillCurrent=hostCurrent,onPresented=onPresented,onFinished={done.complete(it)}))}
            return done
        }
        suspend fun replace(hostCurrent:()->Boolean={true},settle:Boolean=true,
            afterReady:suspend (PreparedNativeSplitOpen)->Unit={},onPresented:(PreparedNativeSplitOpen)->Unit={}):Boolean =
            beginReplacement(hostCurrent,settle,afterReady,onPresented).await()
        suspend fun finish()=withContext(NonCancellable) {
            native.release.countDown();beforeRead=null;denyWrite=false
            val release=withContext(Dispatchers.Main) {
                (extra.map {it.session}+listOfNotNull(maps.current?.session)).distinct().forEach {s->
                    s.inkContact=false;s.textDraft?.let {s.cancelTextDraft(it.token)};s.textRestore?.let {s.completeTextRestore(it.token)}
                }
                maps.dispose()
            }
            release.join();withContext(Dispatchers.Main) {extra.map {it.close()}}.forEach {it.join()};scope.cancel()
        }
    }
    private fun scenario(block:suspend (Fixture)->Unit)=runParkingScenario {native->
        val f=Fixture(native);try {block(f)} finally {f.finish()}
    }
    @Test fun aBadSecondFileLeavesTheOriginalNativeModelHistoryAndTextDraftUntouched()=runParkingScenario {f->
        withContext(Dispatchers.IO) {
            f.seed();f.seed(f.target);f.retry.writeText("Deliberately not an XNotes bundle")
        }
        withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri,"source.xnote");f.stopDebounce()
            assertTrue(f.editor.noteOpen);assertEquals(f.sourceUri,f.editor.visibleDocumentUri)
            f.editor.view.layout(0,0,1000,1000);f.editor.selectTool(Tool.TEXT_BOX)
            parkingPen(f.editor,MotionEvent.ACTION_DOWN,Pt(100.0,100.0))
            parkingPen(f.editor,MotionEvent.ACTION_UP,Pt(100.0,100.0))
            f.editor.updateEditingText("Old source text remains an unconfirmed draft")
            f.stopDebounce();assertNotNull(f.editor.controller.activeTextItem())
        }
        val original=withContext(Dispatchers.Main) {f.editor.state.document}
        val history=withContext(Dispatchers.Main) {f.editor.history}
        val undo=withContext(Dispatchers.Main) {history.nextUndo}
        val draft=withContext(Dispatchers.Main) {requireNotNull(f.editor.controller.activeTextItem())}
        val revision=withContext(Dispatchers.Main) {original.bodyRevision}
        val sourceBytes=f.source.readBytes();val firstBytes=f.target.readBytes();val secondBytes=f.retry.readBytes()
        val retryUri=DocumentsContract.buildDocumentUri(f.authority,"retry").toString()
        val arrangement=withContext(Dispatchers.IO) {
            File(File(f.context.filesDir,"splits").apply {mkdirs()},"bad-second.xsplit").apply {
                writeText(JSONObject().put("format","xnotes-split").put("version",2)
                    .put("primaryPath",f.targetUri).put("secondPath",retryUri)
                    .put("ratio",.37).put("reversed",true).put("sideBySide",false).toString())
            }
        }
        val arrangementBytes=arrangement.readBytes()
        val opened=withContext(Dispatchers.Main) {f.editor.openSplitFile(Uri.fromFile(arrangement).toString())}
        withContext(Dispatchers.Main) {
            assertFalse("An explicitly opened split with an invalid second file is a failed open",opened)
            assertTrue(f.editor.noteOpen);assertSame(original,f.editor.state.document)
            assertEquals(f.sourceUri,f.editor.visibleDocumentUri);assertSame(history,f.editor.history);assertSame(undo,history.nextUndo)
            assertEquals(revision,original.bodyRevision);assertSame(draft,f.editor.controller.activeTextItem())
            assertEquals("Old source text remains an unconfirmed draft",draft.text)
            assertNull(f.editor.missingDocument);assertFalse(f.editor.inSplit)
        }
        assertArrayEquals("The failed initial reads do not save or blur the old draft",sourceBytes,f.source.readBytes())
        assertArrayEquals(firstBytes,f.target.readBytes());assertArrayEquals(secondBytes,f.retry.readBytes())
        assertArrayEquals(arrangementBytes,arrangement.readBytes());assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }

    @Test fun freshNativeSplitAndMapRetirementAreVisibleTogetherInTheOneSuccessCallback()=scenario {f->
        val old=f.start();val before=withContext(Dispatchers.Main) {f.native.editor.state.document}
        val ids=withContext(Dispatchers.IO) {
            listOf(f.native.targetUri,f.retryUri).map {uri->f.native.storage.openSourceActual(uri).use {it.version.documentId}}
        }
        var callback=false
        assertTrue(f.replace(onPresented={candidate->
            callback=true;assertNotSame(before,candidate.first.state.document)
            assertEquals(ids[0],candidate.first.state.document.identity.documentId);assertEquals(ids[1],candidate.second.state.document.identity.documentId)
            assertTrue(candidate.first.noteOpen && candidate.second.noteOpen);assertTrue(f.native.editor.inSplit)
            assertNull(f.maps.current);assertFalse(f.maps.visible);assertFalse(f.maps.canReturnSource)
            assertNull(f.maps.splitPresentation.layout);assertNull(f.maps.splitPresentation.returnLayout);assertFalse(old.lease.alive)
        }))
        withContext(Dispatchers.Main) {assertTrue(callback);assertEquals(1,f.guardCalls);assertEquals(1,f.uiGuards);assertNull(f.maps.current)}
    }

    @Test fun invalidSecondInitialReadDoesNotSettleDirtyMapsOrBlurTheOldNativeDraft()=scenario {f->
        val old=f.start()
        withContext(Dispatchers.Main) {
            f.edit(old,"A unpersisted old Map")
            f.native.editor.view.layout(0,0,1000,1000);f.native.editor.selectTool(Tool.TEXT_BOX)
            parkingPen(f.native.editor,MotionEvent.ACTION_DOWN,Pt(100.0,100.0));parkingPen(f.native.editor,MotionEvent.ACTION_UP,Pt(100.0,100.0))
            f.native.editor.updateEditingText("Still the old native draft");f.native.stopDebounce()
        }
        val original=withContext(Dispatchers.Main) {f.native.editor.state.document};val history=withContext(Dispatchers.Main) {f.native.editor.history}
        val draft=withContext(Dispatchers.Main) {f.native.editor.controller.activeTextItem()};val mapBody=withContext(Dispatchers.Main) {old.session.document}
        val mapBytes=f.file(f.uri).readBytes();val nativeBytes=f.native.source.readBytes()
        withContext(Dispatchers.IO) {f.native.retry.writeText("invalid second candidate")}
        assertFalse(f.replace())
        withContext(Dispatchers.Main) {
            assertEquals(0,f.guardCalls);assertEquals(0,f.uiGuards);assertSame(old,f.maps.current);assertTrue(old.lease.alive)
            assertSame(mapBody,old.session.document);assertTrue(old.session.dirty)
            assertSame(original,f.native.editor.state.document);assertSame(history,f.native.editor.history);assertSame(draft,f.native.editor.controller.activeTextItem())
            assertEquals("Still the old native draft",draft!!.text)
        }
        assertArrayEquals(mapBytes,f.file(f.uri).readBytes());assertArrayEquals(nativeBytes,f.native.source.readBytes())
        assertEquals(0,f.writeAttempts.get());assertEquals(0,f.native.writes.get())
    }

    @Test fun allVisitedMapsAreActuallySavedBeforeTheirLastLeasesRetire()=scenario {f->
        val a=f.start();val incoming=f.acquire(f.other)
        withContext(Dispatchers.Main) {
            a.presentation.synchronize(a.session);val ticket=a.session.captureSourceNavigation("n",f.link.id,f.link.target)!!
            assertEquals(SourcePushResult.APPLIED,f.maps.pushSource(a,incoming,"B",a.session.captureSourceReturn(ticket)!!,
                a.presentation.captureSourceDeparture(a.session)) {true})
            f.edit(a,"A written before native split");f.edit(requireNotNull(f.maps.current),"B written before native split")
            assertTrue(f.maps.canReturnSource)
        }
        val b=withContext(Dispatchers.Main) {f.maps.current!!}
        assertTrue(f.replace(onPresented={_->
            assertNull(f.maps.current);assertFalse(f.maps.canReturnSource);assertFalse(a.lease.alive);assertFalse(b.lease.alive)
            assertTrue(f.native.editor.inSplit)
        }))
        assertEquals("A written before native split",MapCodec.decode(f.file(f.uri).readBytes()).node("n").text)
        assertEquals("B written before native split",MapCodec.decode(f.file(f.other).readBytes()).node("n").text)
        assertEquals(2,f.writes.get());assertEquals(1,f.uiGuards)
    }

    @Test fun aFailedLastLeaseMapSaveKeepsTheRouteAndRecoveryAndNeverReplacesNative()=scenario {f->
        val old=f.start();val original=withContext(Dispatchers.Main) {f.native.editor.state.document}
        val nativeHistory=withContext(Dispatchers.Main) {f.native.editor.history};val raw=f.file(f.uri).readBytes()
        withContext(Dispatchers.Main) {f.edit(old,"Map recovery must survive")};f.denyWrite=true
        assertFalse(f.replace())
        withContext(Dispatchers.Main) {
            assertSame(old,f.maps.current);assertTrue(old.lease.alive);assertTrue(f.maps.visible);assertTrue(old.session.dirty);assertTrue(old.session.recoverySafe)
            assertSame(original,f.native.editor.state.document);assertSame(nativeHistory,f.native.editor.history)
            assertEquals(1,f.guardCalls);assertEquals(0,f.uiGuards);assertFalse(f.native.editor.inSplit)
        }
        assertArrayEquals(raw,f.file(f.uri).readBytes());assertEquals(0,f.writes.get());assertTrue(f.writeAttempts.get()>0)
        val recovery=withContext(Dispatchers.IO) {File(f.native.context.noBackupFilesDir,"mind-map-recovery").walkTopDown().filter {it.isFile && it.extension=="json"}
            .map {JSONObject(it.readText())}.single {it.getJSONObject("document").getString("id")==old.session.document!!.id}}
        assertTrue(recovery.getBoolean("pending"));assertEquals("Map recovery must survive",MapCodec.fromJson(recovery.getJSONObject("document")).node("n").text)
    }

    @Test fun sharedMapTextIsNotConfirmedOrCancelledToMakeReplacementPossible()=scenario {f->
        val old=f.start();val reader=f.acquire();val s=reader.session
        val native=withContext(Dispatchers.Main) {f.native.editor.state.document}
        val draft=withContext(Dispatchers.Main) {
            assertTrue(s.beginText("n",MindMapView.Viewport(1f,0f,0f),1000,700));assertTrue(s.updateTextDraft(s.textDraft!!.token,TextFieldValue("Other owner's draft")))
            s.textDraft!!
        }
        val pending=f.beginReplacement();f.await {f.guardCalls>0};parkingDispatch()
        withContext(Dispatchers.Main) {
            assertSame(old,f.maps.current);assertSame(native,f.native.editor.state.document);assertSame(draft,s.textDraft);assertTrue(reader.alive)
            f.maps.cancelOpen();assertSame(draft,s.textDraft)
        }
        assertFalse(pending.await())
        withContext(Dispatchers.Main) {assertTrue(s.finishTextDraft(draft.token));s.textRestore?.let {s.completeTextRestore(it.token)};s.cancelPendingAutosave()}
        assertTrue(f.replace())
        withContext(Dispatchers.Main) {assertTrue(reader.alive);assertEquals("Other owner's draft",s.document!!.node("n").text);assertTrue(s.canUndo);assertFalse(old.lease.alive)}
    }

    @Test fun aRealSharedMapStrokeSurvivesCancellationThenTheNextReplacementCanProceed()=scenario {f->
        val old=f.start();val reader=f.acquire();val s=reader.session
        val view=withContext(Dispatchers.Main) {
            assertTrue(s.operate {it.copy(nodes=it.nodes+MapNode(id="ink",parent="r",kind=NodeKind.INK,x=400.0,y=120.0,width=320.0,height=220.0))});s.cancelPendingAutosave()
            MindMapView(f.native.context).apply {
                document=s.document!!;documentEpoch=s.interactionEpoch;documentRevision=s.contentRevision
                onSelect={s.selected=it};onInkContact={s.inkContact=it}
                onInk={id,before,after->s.commitInk(id,before,after).also {if(it) document=s.document!!}}
                layout(0,0,1000,800);fit()
            }
        }
        val start=SystemClock.uptimeMillis();var tick=0L
        fun event(action:Int,point:Pt) {
            val p=view.screenPoint(point);val prop=MotionEvent.PointerProperties().apply {id=9;toolType=MotionEvent.TOOL_TYPE_STYLUS}
            val at=MotionEvent.PointerCoords().apply {x=p.x.toFloat();y=p.y.toFloat();pressure=.6f}
            val raw=MotionEvent.obtain(start,start+tick++,action,1,arrayOf(prop),arrayOf(at),0,0,1f,1f,0,0,InputDevice.SOURCE_STYLUS,0)
            try {assertTrue(view.onTouchEvent(raw))} finally {raw.recycle()}
        }
        withContext(Dispatchers.Main) {event(MotionEvent.ACTION_DOWN,Pt(480.0,200.0));event(MotionEvent.ACTION_MOVE,Pt(515.0,225.0))}
        val pending=f.beginReplacement();f.await {f.guardCalls>0};parkingDispatch()
        withContext(Dispatchers.Main) {
            assertSame(old,f.maps.current);assertTrue(s.inkContact);assertTrue(view.inkInput.active)
            f.maps.cancelOpen();assertTrue(s.inkContact);assertTrue(view.inkInput.active)
        }
        assertFalse(pending.await())
        withContext(Dispatchers.Main) {
            event(MotionEvent.ACTION_UP,Pt(550.0,245.0));assertFalse(s.inkContact);assertFalse(view.inkInput.active)
            assertEquals(1,s.document!!.node("ink").ink.size);s.cancelPendingAutosave()
        }
        assertTrue(f.replace());withContext(Dispatchers.Main) {assertTrue(reader.alive);assertEquals(1,s.document!!.node("ink").ink.size)}
    }

    @Test fun releasingThisMapRouteKeepsAnotherOwnersActualAutosaveAndHistory()=scenario {f->
        val old=f.start();val reader=f.acquire();val s=reader.session
        val debounce=withContext(Dispatchers.Main) {
            assertTrue(s.operate {it.update("n") {node->node.copy(text="Shared owner will autosave")}})
            MapSession::class.java.getDeclaredField("debounce").apply {isAccessible=true}.get(s) as Job
        }
        val document=withContext(Dispatchers.Main) {s.document}
        assertTrue(f.replace())
        withContext(Dispatchers.Main) {
            assertFalse(old.lease.alive);assertTrue(reader.alive);assertSame(document,s.document);assertTrue(s.canUndo);assertFalse(s.closing)
            assertFalse("Route retirement cannot cancel another owner's accepted autosave",debounce.isCancelled)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(400,java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        f.await {!s.saving && f.writes.get()>0}
        assertEquals("Shared owner will autosave",MapCodec.decode(f.file(f.uri).readBytes()).node("n").text)
    }

    @Test fun bothAMapHomeAndAParkedMixedLayoutAreClearedOnlyAfterSuccessfulNativeAdoption() {
        for(home in listOf(true,false)) scenario {f->
            val old=f.start();val layout=MapSplitLayout(Pane.SECONDARY,Pane.SECONDARY,.31f,false,true)
            withContext(Dispatchers.Main) {assertTrue(f.maps.showSplit(old,layout))}
            if(home) withContext(Dispatchers.Main) {requireNotNull(f.maps.closeToHome(old))}.join()
            else withContext(Dispatchers.Main) {assertTrue(f.maps.hide(old))}
            withContext(Dispatchers.Main) {
                if(home) {assertNull(f.maps.current);assertEquals(layout,f.maps.splitPresentation.layout)}
                else {assertSame(old,f.maps.current);assertEquals(layout,f.maps.splitPresentation.returnLayout);assertFalse(f.maps.visible)}
            }
            assertTrue(f.replace(onPresented={_->
                assertTrue(f.native.editor.inSplit);assertNull(f.maps.current);assertNull(f.maps.splitPresentation.layout);assertNull(f.maps.splitPresentation.returnLayout)
            }))
        }
    }

    @Test fun aNewMapEditAfterNativeFinalPreparationInvalidatesRetirementWithoutRollingItBack()=scenario {f->
        val old=f.start();val native=withContext(Dispatchers.Main) {f.native.editor.state.document}
        assertFalse(f.replace(afterReady={candidate->
            assertTrue(candidate.current());f.edit(old,"Late C1 remains in the old Map")
        }))
        withContext(Dispatchers.Main) {
            assertSame(old,f.maps.current);assertTrue(old.lease.alive);assertTrue(old.session.dirty)
            assertEquals("Late C1 remains in the old Map",old.session.document!!.node("n").text)
            assertSame(native,f.native.editor.state.document);assertFalse(f.native.editor.inSplit)
        }
    }

    @Test fun aRetiredHostAndAnImmediatelySupersededAttemptCannotRetireTheWrongMapRoute()=scenario {f->
        val old=f.start();val native=withContext(Dispatchers.Main) {f.native.editor.state.document};var host=true
        assertFalse(f.replace(hostCurrent={host},afterReady={candidate->assertTrue(candidate.current());host=false}))
        withContext(Dispatchers.Main) {assertSame(old,f.maps.current);assertSame(native,f.native.editor.state.document)}
        host=true
        val ready=CompletableDeferred<PreparedNativeSplitOpen>();val wait=CompletableDeferred<Unit>()
        val first=f.beginReplacement(hostCurrent={host},afterReady={candidate->
            ready.complete(candidate)
            try {wait.await()} catch(cancelled:CancellationException) {withContext(NonCancellable) {candidate.close()};throw cancelled}
        })
        val candidate=ready.await();val second=f.beginReplacement(hostCurrent={host})
        assertFalse(first.await());assertTrue(second.await())
        withContext(Dispatchers.Main) {assertNull(f.maps.current);assertTrue(f.native.editor.inSplit);assertFalse(candidate.current());assertFalse(candidate.commit());assertFalse(f.maps.opening)}
    }

    @Test fun aPreparedNativeCandidateThatNeverObtainedTheMapSettleProofIsRefused()=scenario {f->
        val old=f.start();val native=withContext(Dispatchers.Main) {f.native.editor.state.document}
        assertFalse(f.replace(settle=false))
        withContext(Dispatchers.Main) {assertSame(old,f.maps.current);assertTrue(old.lease.alive);assertSame(native,f.native.editor.state.document);assertFalse(f.native.editor.inSplit)}
    }
}
