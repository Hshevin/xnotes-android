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
import com.xnotes.canvas.InitialView
import com.xnotes.canvas.ViewOverrides
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.fixedWriteSnapshot
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.format.BundleHeader
import com.xnotes.mindmap.*
import com.xnotes.ui.*
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** Actual map actors, native owners and provider bytes cross the arrangement publication boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class MixedSplitPersistenceHostTest {
    private class Fixture(val native:EditorPageCopyFixture) {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val workspace=MapWorkspace(native.context,scope)
        val host=Any()
        val authority="mixed-persistence-${UUID.randomUUID()}"
        val uri=DocumentsContract.buildDocumentUri(authority,"root/pair.xmap")
        val oldUri=DocumentsContract.buildDocumentUri(authority,"root/old.xmap")
        val movedUri=DocumentsContract.buildDocumentUri(authority,"root/moved.xmap")
        val retryUri=DocumentsContract.buildDocumentUri(native.authority,"retry").toString()
        val document=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,text="root"),MapNode(id="n",parent="r",text="saved A")))
        val reads=AtomicInteger();val writes=AtomicInteger();val writeAttempts=AtomicInteger()
        @Volatile var denyWrite=false
        val store=SplitArrangementStore(native.context.filesDir)
        val leases=mutableListOf<MapDocumentLease>()
        var prepareCalls=0;var guardCalls=0
        lateinit var nativeId:String
        fun file(at:Uri)=File(native.root,DocumentsContract.getDocumentId(at).substringAfterLast('/'))
        private val provider=object:ContentProvider() {
            override fun onCreate()=true
            override fun getType(uri:Uri)="application/octet-stream"
            override fun query(uri:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,sort:String?):Cursor =
                MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)).apply {addRow(arrayOf(file(uri).name,file(uri).length()))}
            override fun insert(uri:Uri,values:ContentValues?):Uri?=null
            override fun update(uri:Uri,values:ContentValues?,s:String?,a:Array<out String>?)=0
            override fun delete(uri:Uri,s:String?,a:Array<out String>?):Int=error("An arrangement must never delete a source")
            override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
                if('w' in mode) {
                    writeAttempts.incrementAndGet()
                    if(denyWrite) throw IOException("Controlled Map publication failure")
                    writes.incrementAndGet()
                } else reads.incrementAndGet()
                return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.parseMode(mode))
            }
        }
        init {
            provider.attachInfo(native.context,ProviderInfo().apply {this.authority=this@Fixture.authority})
            ShadowContentResolver.registerProviderInternal(authority,provider)
            file(uri).writeBytes(MapCodec.encode(document))
            file(oldUri).writeBytes(MapCodec.encode(document.copy(id=UUID.randomUUID().toString())))
        }
        suspend fun await(check:()->Boolean)=withTimeout(10000L) {while(!withContext(Dispatchers.Main) {check()}) delay(1L)}
        suspend fun start():MapWorkspace.Entry {
            withContext(Dispatchers.IO) {
                native.seed();native.seed(native.target)
                nativeId=requireNotNull(BundleHeader.read(native.target).identity).documentId
            }
            withContext(Dispatchers.Main) {
                workspace.bindHost(host)
                native.editor.openAsync(native.sourceUri,"source.xnote")
                assertTrue(workspace.open(oldUri,"old.xmap"))
            }
            await {!workspace.opening && workspace.visible && !native.editor.opening && native.editor.noteOpen &&
                native.editor.visibleDocumentUri==native.sourceUri}
            return withContext(Dispatchers.Main) {native.stopDebounce();requireNotNull(workspace.current)}
        }
        fun request(layout:MapSplitLayout=MapSplitLayout(Pane.SECONDARY,Pane.PRIMARY,.37f,false,true),
            position:Editor.ReadingPosition=Editor.ReadingPosition(1.35,21.0,44.0,ViewOverrides())) =
            MixedSplitRequest(SplitSourceRef(document.id,uri.toString(),"pair.xmap"),
                SplitSourceRef(nativeId,native.targetUri,"target.xnote"),layout,null,position)
        suspend fun savedPair(request:MixedSplitRequest=request(),onPresented:(MapWorkspace.Entry,PreparedPagedPairOpen)->Unit={_,_->}):Boolean {
            val finished=CompletableDeferred<Boolean>()
            withContext(Dispatchers.Main) {assertTrue(workspace.openSavedPair(request,
                prepareNative={source,position,valid->
                    prepareCalls++
                    native.editor.preparePagedPairOpen(source,position=position,stillCurrent=valid,
                        guardOwners={_,_->guardCalls++;emptySet()})
                },stillCurrent={workspace.isHostCurrent(host)},onPresented=onPresented,onFinished={finished.complete(it)}))}
            return finished.await()
        }
        suspend fun livePair():MapWorkspace.Entry {
            start()
            val finished=CompletableDeferred<Boolean>()
            withContext(Dispatchers.Main) {assertTrue(workspace.openPair(uri,"pair.xmap",false,
                prepareNative={valid->native.editor.preparePagedPairOpen(native.targetUri,"target.xnote",stillCurrent=valid)},
                stillCurrent={workspace.isHostCurrent(host)},onFinished={finished.complete(it)}))}
            assertTrue(finished.await())
            return withContext(Dispatchers.Main) {
                native.stopDebounce();native.editor.view.layout(0,0,1000,1000)
                requireNotNull(workspace.current)
            }
        }
        fun editMap(entry:MapWorkspace.Entry,text:String) {
            assertTrue(entry.session.operate {it.update("n") {node->node.copy(text=text)}})
            entry.session.cancelPendingAutosave()
        }
        suspend fun editBoth(entry:MapWorkspace.Entry) = withContext(Dispatchers.Main) {
            editMap(entry,"actual Map B")
            native.addStroke(73.0,recordHistory=true);native.stopDebounce()
        }
        suspend fun prepareSave(afterNative:suspend (PreparedMixedNoteArrangementSave)->Unit={}):PreparedMixedArrangementSave? =
            withContext(Dispatchers.Main) {workspace.prepareMixedArrangementSave(
                prepareNative={pane,valid->native.editor.prepareMixedNoteArrangementSave(pane,valid)?.also {afterNative(it)}},
                stillCurrent={workspace.isHostCurrent(host)})}
        suspend fun publish(prepared:PreparedMixedArrangementSave,name:String="Mixed arrangement",beforePublish:suspend ()->Unit={}):File? {
            val staged=withContext(Dispatchers.IO) {requireNotNull(store.prepareNamed(prepared.request,name))}
            try {
                beforePublish()
                return withContext(Dispatchers.Main) {prepared.publish {staged.commit()}}
            } finally {withContext(NonCancellable+Dispatchers.IO) {staged.close()}}
        }
        fun namedFiles()=File(native.context.filesDir,"splits").listFiles()?.filter {it.extension=="xsplit"}.orEmpty()
        suspend fun nativeExternalC():ByteArray=withContext(Dispatchers.IO) {
            val doc=native.target.inputStream().use {native.codec.read(it,File(native.root,"pdf"),File(native.root,"images"))}
            doc.pages.first().items+=Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),listOf(Sample(301.0,302.0,.4),Sample(321.0,322.0,.7)))
            java.io.ByteArrayOutputStream().use {out->native.codec.write(doc.fixedWriteSnapshot(),out);out.toByteArray()}
        }
        suspend fun finish()=withContext(NonCancellable) {
            native.release.countDown();denyWrite=false;native.beforeTargetWrite=null
            val disposal=withContext(Dispatchers.Main) {
                (leases.map {it.session}+listOfNotNull(workspace.current?.session)).distinct().forEach {session->
                    session.inkContact=false;session.textDraft?.let {session.cancelTextDraft(it.token)}
                    session.textRestore?.let {session.completeTextRestore(it.token)}
                }
                workspace.dispose()
            }
            disposal.join()
            withContext(Dispatchers.Main) {leases.map {it.close()}}.forEach {it.join()}
            scope.cancel()
        }
    }
    private fun scenario(block:suspend (Fixture)->Unit)=runParkingScenario {native->
        val fixture=Fixture(native)
        try {block(fixture)} finally {fixture.finish()}
    }
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}

    @Test fun savedMapUuidMismatchFailsBeforeNativePreparationOrSavingTheOldOwners()=scenario {f->
        val old=f.start()
        val oldNative=withContext(Dispatchers.Main) {
            f.editMap(old,"old Map draft");f.native.addStroke(99.0,recordHistory=true);f.native.stopDebounce()
            f.native.editor.state.document
        }
        val beforeMap=f.file(f.oldUri).readBytes();val beforeNative=f.native.source.readBytes()
        val wrong=f.request().copy(map=f.request().map.copy(documentId=UUID.randomUUID().toString()))
        assertFalse(f.savedPair(wrong) {_,_->fail("Wrong saved Map identity cannot present either half")})
        withContext(Dispatchers.Main) {
            assertEquals(0,f.prepareCalls);assertEquals(0,f.guardCalls)
            assertSame(old,f.workspace.current);assertTrue(old.lease.alive);assertTrue(old.session.dirty)
            assertSame(oldNative,f.native.editor.state.document);assertTrue(oldNative.dirty)
            assertNull(f.workspace.splitPresentation.layout)
        }
        assertArrayEquals(beforeMap,f.file(f.oldUri).readBytes());assertArrayEquals(beforeNative,f.native.source.readBytes())
        assertEquals(0,f.writes.get());assertEquals(0,f.native.writes.get())
    }

    @Test fun savedGeometryAndRoleFocusRemapToTheActualRetainedNativeSlotWithRequestedReadingPosition()=scenario {f->
        f.start()
        val retained=withContext(Dispatchers.Main) {f.native.editor.secondaryPane().also {it.openAsync(f.native.targetUri,"target.xnote")}}
        f.await {!retained.opening && retained.noteOpen && retained.visibleDocumentUri==f.native.targetUri}
        val original=withContext(Dispatchers.Main) {retained.state.document}
        val history=withContext(Dispatchers.Main) {retained.history}
        val request=f.request(MapSplitLayout(Pane.SECONDARY,Pane.PRIMARY,.31f,false,true),
            Editor.ReadingPosition(1.6,46.0,87.0,ViewOverrides(rotation=90,freeMovement=true)))
        assertTrue(f.savedPair(request) {_,candidate->
            assertEquals(Pane.SECONDARY,candidate.nativePane);assertSame(retained,candidate.target)
            assertSame(original,retained.state.document);assertSame(history,retained.history)
            val layout=requireNotNull(f.workspace.splitPresentation.layout)
            assertEquals(Pane.PRIMARY,layout.mapPane);assertEquals(Pane.SECONDARY,layout.focusedPane)
            assertEquals(.31f,layout.ratio,0f);assertEquals(false,layout.sideBySide);assertFalse(layout.reversed)
            val pending=retained.state.pendingInitialView as? InitialView.Restore
            assertNotNull("The saved reading position waits for the actual native surface",pending)
            assertEquals(request.nativePosition.zoom,pending!!.zoom,0.0)
            assertEquals(request.nativePosition.x,pending.scrollX,0.0);assertEquals(request.nativePosition.y,pending.scrollY,0.0)
            assertEquals(request.nativePosition.overrides,retained.readingPosition().overrides)
        })
    }

    @Test fun aNamedMixedArrangementUsesBothActualSavesAndReopensTheRealPublishedFile()=scenario {f->
        val entry=f.livePair();f.editBoth(entry)
        val nativeVersion=withContext(Dispatchers.IO) {f.native.storage.openSourceActual(f.native.targetUri).use {it.version}}
        var capturedNative:PreparedMixedNoteArrangementSave?=null
        val prepared=requireNotNull(f.prepareSave {capturedNative=it})
        withContext(Dispatchers.Main) {
            assertTrue(prepared.current());assertFalse(entry.session.dirty);assertFalse(f.native.editor.state.document.dirty)
            assertEquals(f.document.id,prepared.request.map.documentId);assertEquals(f.nativeId,prepared.request.native.documentId)
            assertEquals(f.uri.toString(),prepared.request.map.uri);assertEquals(f.native.targetUri,prepared.request.native.uri)
            assertNotEquals(nativeVersion.sha256,requireNotNull(capturedNative).version.sha256)
        }
        assertEquals("actual Map B",withContext(Dispatchers.IO) {MapCodec.decode(f.file(f.uri).readBytes()).node("n").text})
        val mapWrites=f.writes.get();val nativeWrites=f.native.writes.get()
        assertTrue(mapWrites>0);assertTrue(nativeWrites>0)
        // A clean toolbar flag is not a publication proof for this second explicit save.
        val mapBytesB=withContext(Dispatchers.IO) {f.file(f.uri).readBytes()}
        val actors=withContext(Dispatchers.Main) {entry.session.document to f.native.editor.state.document}
        val mapReads=f.reads.get();var cleanNative:PreparedMixedNoteArrangementSave?=null
        val cleanPrepared=requireNotNull(f.prepareSave {cleanNative=it})
        assertTrue("This clean save must still verify the actual Map source",f.reads.get()>mapReads)
        withContext(Dispatchers.Main) {
            assertTrue(cleanPrepared.current());assertNotSame(capturedNative,cleanNative)
            assertSame(actors.first,entry.session.document);assertSame(actors.second,f.native.editor.state.document)
            val finalNativeCheck=requireNotNull(requireNotNull(cleanNative).verifySource())
            assertTrue("This operation obtains a fresh actual Native source proof",finalNativeCheck())
        }
        val actualNative=withContext(Dispatchers.IO) {f.native.storage.openSourceActual(f.native.targetUri).use {it.version}}
        val capturedVersion=requireNotNull(cleanNative).version
        assertEquals(actualNative.kind,capturedVersion.kind);assertEquals(actualNative.documentId,capturedVersion.documentId)
        assertEquals(actualNative.locationKey,capturedVersion.locationKey);assertEquals(actualNative.sha256,capturedVersion.sha256)
        assertArrayEquals(mapBytesB,withContext(Dispatchers.IO) {f.file(f.uri).readBytes()})
        val published=requireNotNull(f.publish(cleanPrepared))
        val reopened=withContext(Dispatchers.IO) {
            val read=f.store.readManaged(published.toURI().toString()) as SplitArrangementRead.Ready
            (read.request as SplitOpenRequest.Mixed).request
        }
        assertEquals(cleanPrepared.request,reopened);assertEquals(listOf(published.canonicalFile),f.namedFiles().map {it.canonicalFile})
        val oldFinished=CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {assertTrue(f.workspace.open(f.oldUri,"old.xmap",onFinished={oldFinished.complete(it)}))}
        assertTrue(oldFinished.await());assertTrue(f.savedPair(reopened))
        withContext(Dispatchers.Main) {
            assertEquals(f.document.id,f.workspace.current!!.session.document!!.id)
            assertEquals("actual Map B",f.workspace.current!!.session.document!!.node("n").text)
            assertEquals(f.nativeId,f.native.editor.openPanes.single().state.document.identity.documentId)
        }
    }

    @Test fun theActualMapSaveOutcomeCarriesTheWrittenBShaRatherThanAnUnrelatedLaterRead()=scenario {f->
        val entry=f.livePair()
        val lease=withContext(Dispatchers.Main) {MapDocuments.acquire(f.native.context,f.uri).also {f.leases+=it}}
        f.await {!lease.session.loading}
        val outcome=withContext(Dispatchers.Main) {
            assertSame(entry.session,lease.session);f.editMap(entry,"Map publication B")
            entry.session.saveAttempt().await()
        }
        val written=outcome as MapSaveOutcome.Written
        assertTrue(written.latest)
        val bytesB=withContext(Dispatchers.IO) {f.file(f.uri).readBytes()}
        assertEquals(sha(bytesB),written.sourceSha256)
        val bytesC=MapCodec.encode(f.document.update("n") {it.copy(text="same UUID external C")})
        withContext(Dispatchers.IO) {f.file(f.uri).writeBytes(bytesC)}
        assertNotEquals(sha(bytesC),written.sourceSha256);assertEquals(sha(bytesB),written.sourceSha256)
        withContext(Dispatchers.Main) {assertTrue(lease.alive);assertEquals("Map publication B",lease.session.document!!.node("n").text)}
    }

    @Test fun externalSameUuidMapCAfterBothSavesAndStagingCannotPublishAnArrangementForRuntimeB()=scenario {f->
        val entry=f.livePair();f.editBoth(entry)
        val prepared=requireNotNull(f.prepareSave())
        val runtime=withContext(Dispatchers.Main) {entry.session.document to entry.session.contentRevision}
        val bytesC=MapCodec.encode(f.document.update("n") {it.copy(text="external Map C after stage")})
        val result=f.publish(prepared,beforePublish={
            withContext(Dispatchers.IO) {f.file(f.uri).writeBytes(bytesC)}
            withContext(Dispatchers.Main) {
                assertTrue(prepared.current());assertSame(runtime.first,entry.session.document)
                assertEquals(runtime.second,entry.session.contentRevision);assertFalse(entry.session.dirty)
            }
        })
        assertNull(result);assertTrue(f.namedFiles().isEmpty());assertArrayEquals(bytesC,f.file(f.uri).readBytes())
        withContext(Dispatchers.Main) {assertSame(entry,f.workspace.current);assertEquals("actual Map B",entry.session.document!!.node("n").text)}
    }

    @Test fun externalSameUuidNativeCAfterBothSavesAndStagingIsRecheckedWithoutAnyRuntimeChange()=scenario {f->
        val entry=f.livePair();f.editBoth(entry)
        var nativeProof:PreparedMixedNoteArrangementSave?=null
        val prepared=requireNotNull(f.prepareSave {nativeProof=it})
        val runtime=withContext(Dispatchers.Main) {f.native.editor.state.document to f.native.editor.state.document.bodyRevision}
        val bytesC=f.nativeExternalC()
        val result=f.publish(prepared,beforePublish={
            withContext(Dispatchers.IO) {f.native.target.writeBytes(bytesC)}
            withContext(Dispatchers.Main) {
                assertTrue(prepared.current());assertTrue(requireNotNull(nativeProof).current())
                assertSame(runtime.first,f.native.editor.state.document);assertEquals(runtime.second,runtime.first.bodyRevision)
                assertFalse(runtime.first.dirty)
            }
        })
        assertNull(result);assertTrue(f.namedFiles().isEmpty());assertArrayEquals(bytesC,f.native.target.readBytes())
        withContext(Dispatchers.Main) {assertSame(entry,f.workspace.current);assertTrue(entry.lease.alive)}
        assertNotEquals(sha(bytesC),requireNotNull(nativeProof).version.sha256)
    }

    @Test fun aRealMapWriteFailureKeepsTheMixedPairAndDirtyRecoveryWithoutNamedPublication()=scenario {f->
        val entry=f.livePair();f.editBoth(entry)
        val model=withContext(Dispatchers.Main) {f.native.editor.state.document}
        val original=f.file(f.uri).readBytes();f.denyWrite=true
        assertNull(f.prepareSave())
        withContext(Dispatchers.Main) {
            assertSame(entry,f.workspace.current);assertTrue(entry.lease.alive);assertTrue(f.workspace.visible)
            assertTrue(entry.session.dirty);assertTrue(entry.session.recoverySafe)
            assertSame(model,f.native.editor.state.document);assertNotNull(f.workspace.splitPresentation.layout)
        }
        assertTrue(f.writeAttempts.get()>0);assertArrayEquals(original,f.file(f.uri).readBytes());assertTrue(f.namedFiles().isEmpty())
    }

    @Test fun aRealNativeWriteFailureKeepsTheMixedPairAndUnsavedNativeContentWithoutNamedPublication()=scenario {f->
        val entry=f.livePair();f.editBoth(entry)
        val model=withContext(Dispatchers.Main) {f.native.editor.state.document}
        val original=f.native.target.readBytes();var attempts=0
        f.native.beforeTargetWrite={attempts++;throw IOException("Controlled Native publication failure")}
        assertNull(f.prepareSave())
        withContext(Dispatchers.Main) {
            assertSame(entry,f.workspace.current);assertTrue(entry.lease.alive);assertTrue(f.workspace.visible)
            assertSame(model,f.native.editor.state.document);assertTrue(model.dirty)
            assertNotNull(f.workspace.splitPresentation.layout)
        }
        assertTrue(attempts>0);assertArrayEquals(original,f.native.target.readBytes());assertTrue(f.namedFiles().isEmpty())
    }

    @Test fun nativeHomeAndParkedMixedPresentationsCannotStartSourceSaves()=scenario {f->
        val entry=f.livePair();f.editBoth(entry)
        val beforeMap=f.writes.get();val beforeNative=f.native.writes.get();var preparedNative=0
        withContext(Dispatchers.Main) {assertTrue(f.workspace.updateSplitLayout {it.copy(nativeHome=true)})}
        suspend fun rejected()=withContext(Dispatchers.Main) {f.workspace.prepareMixedArrangementSave(
            prepareNative={pane,valid->preparedNative++;f.native.editor.prepareMixedNoteArrangementSave(pane,valid)})}
        assertNull(rejected())
        withContext(Dispatchers.Main) {
            assertTrue(f.workspace.updateSplitLayout {it.copy(nativeHome=false)})
            assertTrue(f.workspace.hide(entry));assertNull(f.workspace.splitPresentation.layout)
            assertNotNull(f.workspace.splitPresentation.returnLayout)
        }
        assertNull(rejected());assertEquals(0,preparedNative)
        assertEquals(beforeMap,f.writes.get());assertEquals(beforeNative,f.native.writes.get());assertTrue(f.namedFiles().isEmpty())
    }

    @Test fun preparedArrangementCannotCommitAfterContentLayoutOrActualHostChanges() {
        for(change in listOf("content","layout","host")) scenario {f->
            val entry=f.livePair();f.editBoth(entry)
            val prepared=requireNotNull(f.prepareSave())
            val result=f.publish(prepared,beforePublish={withContext(Dispatchers.Main) {
                when(change) {
                    "content"->f.editMap(entry,"late content after capture")
                    "layout"->assertTrue(f.workspace.updateSplitLayout {it.copy(ratio=.62f)})
                    else->assertTrue(f.workspace.unbindHost(f.host))
                }
                assertFalse("The $change change retires this publication",prepared.current())
            }})
            assertNull(result);assertTrue(f.namedFiles().isEmpty())
            withContext(Dispatchers.Main) {assertSame(entry,f.workspace.current);assertTrue(entry.lease.alive)}
        }
    }

    @Test fun cancellingTheCallerAfterTheActualLinkKeepsTheCompleteNamedFileAndPublishedFact()=scenario {f->
        val entry=f.livePair();f.editBoth(entry)
        val prepared=requireNotNull(f.prepareSave())
        val staged=withContext(Dispatchers.IO) {requireNotNull(f.store.prepareNamed(prepared.request,"Accepted before cancellation"))}
        val bytesB=withContext(Dispatchers.IO) {f.file(f.uri).readBytes() to f.native.target.readBytes()}
        val runtime=withContext(Dispatchers.Main) {entry.session.document to f.native.editor.state.document}
        val callerScope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        var published:File?=null
        try {
            val caller=callerScope.launch {
                val callerJob=requireNotNull(currentCoroutineContext()[Job])
                prepared.publish {staged.commit().also {published=it;callerJob.cancel()}}
            }
            caller.join()
            assertTrue("The independent caller is cancelled after its link was accepted",caller.isCancelled)
            val actual=requireNotNull(staged.publishedFile ?: published)
            assertEquals(requireNotNull(published).canonicalFile,requireNotNull(staged.publishedFile).canonicalFile)
            withContext(Dispatchers.IO) {staged.close()}
            assertTrue(actual.isFile);assertEquals(listOf(actual.canonicalFile),f.namedFiles().map {it.canonicalFile})
            val reopened=withContext(Dispatchers.IO) {
                val read=f.store.readManaged(actual.toURI().toString()) as SplitArrangementRead.Ready
                (read.request as SplitOpenRequest.Mixed).request
            }
            assertEquals(prepared.request,reopened)
            withContext(Dispatchers.Main) {
                assertSame(entry,f.workspace.current);assertTrue(entry.lease.alive)
                assertSame(runtime.first,entry.session.document);assertSame(runtime.second,f.native.editor.state.document)
                assertFalse(entry.session.dirty);assertFalse(runtime.second.dirty)
            }
            assertArrayEquals(bytesB.first,withContext(Dispatchers.IO) {f.file(f.uri).readBytes()})
            assertArrayEquals(bytesB.second,withContext(Dispatchers.IO) {f.native.target.readBytes()})
        } finally {
            callerScope.cancel()
            withContext(NonCancellable+Dispatchers.IO) {staged.close()}
        }
    }

    @Test fun savedOldHintsFollowBothUniqueRegisteredMovesAndOpenOnlyTheActualNewLocations()=scenario {f->
        f.start();val saved=f.request()
        withContext(Dispatchers.IO) {
            val registry=f.native.storage.registry
            registry.registerMapObserved(f.document.id,"pair.xmap",f.uri.toString())
            registry.registerObserved(BundleHeader.read(f.native.target),"xnote","target.xnote",f.native.targetUri)
            f.file(f.uri).copyTo(f.file(f.movedUri));f.native.target.copyTo(f.native.retry,overwrite=true)
            registry.moved(f.uri.toString(),f.movedUri.toString());registry.moved(f.native.targetUri,f.retryUri)
            // Old hints are still readable, but they now contain unrelated identities.
            f.file(f.uri).writeBytes(MapCodec.encode(f.document.copy(id=UUID.randomUUID().toString())))
            f.native.seed(f.native.target)
            assertEquals(f.movedUri.toString(),registry.resolve(f.document.id));assertEquals(f.retryUri,registry.resolve(f.nativeId))
        }
        assertTrue(f.savedPair(saved))
        withContext(Dispatchers.Main) {
            assertEquals(MapSaves.key(f.movedUri),MapSaves.key(f.workspace.current!!.uri))
            assertEquals(f.document.id,f.workspace.current!!.session.document!!.id)
            assertEquals(f.retryUri,f.native.editor.openPanes.single().visibleDocumentUri)
            assertEquals(f.nativeId,f.native.editor.openPanes.single().state.document.identity.documentId)
        }
    }

    @Test fun duplicateKnownLocationsForEitherSavedRoleRejectBeforeGuardAndPreserveBothOldOwners() {
        for(role in listOf("map","native")) scenario {f->
            val old=f.start()
            val original=withContext(Dispatchers.Main) {
                f.editMap(old,"old Map remains dirty");f.native.addStroke(155.0,recordHistory=true);f.native.stopDebounce()
                f.native.editor.state.document
            }
            withContext(Dispatchers.IO) {
                val registry=f.native.storage.registry
                if(role=="map") {
                    f.file(f.uri).copyTo(f.file(f.movedUri))
                    registry.registerMapObserved(f.document.id,"first",f.uri.toString())
                    registry.registerMapObserved(f.document.id,"duplicate",f.movedUri.toString())
                } else {
                    f.native.target.copyTo(f.native.retry,overwrite=true)
                    registry.registerObserved(BundleHeader.read(f.native.target),"xnote","first",f.native.targetUri)
                    registry.registerObserved(BundleHeader.read(f.native.retry),"xnote","duplicate",f.retryUri)
                }
            }
            assertFalse(f.savedPair())
            withContext(Dispatchers.Main) {
                assertEquals("Duplicate $role must fail before Native preparation",0,f.prepareCalls);assertEquals(0,f.guardCalls)
                assertSame(old,f.workspace.current);assertTrue(old.session.dirty);assertTrue(old.lease.alive)
                assertSame(original,f.native.editor.state.document);assertTrue(original.dirty)
                assertNull(f.workspace.splitPresentation.layout)
            }
            assertEquals(0,f.writes.get());assertEquals(0,f.native.writes.get());assertTrue(f.namedFiles().isEmpty())
        }
    }
}
