package com.xnotes.pen

import android.view.KeyEvent
import android.view.MotionEvent
import com.xnotes.canvas.InitialView
import com.xnotes.canvas.ViewOverrides
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.TextItem
import com.xnotes.core.model.fixedWriteSnapshot
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.format.BundleMigration
import com.xnotes.format.BundleReferenceInfo
import com.xnotes.platform.LegacySessionQueue
import com.xnotes.platform.LegacySessionWrite
import com.xnotes.platform.SessionStore
import com.xnotes.ui.*
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Actual native files, saves, models and input, with no Map or arrangement-writer substitute. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class MixedNoteArrangementHostTest {
    private fun ready(value:PreparedMixedNoteArrangementSave?):PreparedMixedNoteArrangementSave {
        assertNotNull("A stable NOTE with an actual source baseline must produce a saved arrangement proof",value)
        return value!!
    }
    private suspend fun open(f:EditorPageCopyFixture) {
        withContext(Dispatchers.IO) {f.seed();f.seed(f.target)}
        withContext(Dispatchers.Main) {f.editor.openAsync(f.targetUri,"target.xnote");f.editor.view.layout(0,0,1000,1000)}
    }
    private fun job(editor:Editor,name:String)=Editor::class.java.getDeclaredField(name).apply {isAccessible=true}.get(editor) as? Job
    private suspend fun settleSession(editor:Editor) {
        parkingDispatch()
        withContext(Dispatchers.Main) {
            job(editor,"sessionWriteJob")?.join()
            (Editor::class.java.getDeclaredField("sessionOwner").apply {isAccessible=true}.get(editor) as LegacySessionQueue.Owner).awaitAccepted()
        }
    }
    private suspend fun reachedWrite(entered:CompletableDeferred<Unit>,preparing:Deferred<*>) {
        select<Unit> {
            entered.onAwait { }
            preparing.onAwait {fail("A dirty real NOTE must reach its fixed publication before preparation returns")}
        }
    }

    @Test fun anExpectedIdentityMismatchIsRejectedBeforeOutgoingGuards()=runParkingScenario {f->
        openParkingPanes(f)
        withContext(Dispatchers.IO) {f.seed(f.retry)}
        withContext(Dispatchers.Main) {
            val model=f.editor.state.document;val peer=f.editor.secondary!!;val other=peer.state.document
            var guards=0
            val cap=f.editor.preparePagedPairOpen(NativeSplitSource(
                android.provider.DocumentsContract.buildDocumentUri(f.authority,"retry").toString(),expectedId=UUID.randomUUID().toString()),
                guardOwners={_,_->guards++;emptySet()})
            try {
                assertNull("A saved UUID must be checked before guarding any outgoing owner",cap)
                assertEquals(0,guards);assertSame(model,f.editor.state.document);assertSame(other,peer.state.document)
            } finally {cap?.close()}
        }
        assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }

    @Test fun freshAndRetainedSavedPositionsAreAppliedOnlyByTheSynchronousCommit() {
        for(retained in listOf(false,true)) runParkingScenario {f->
            val peer=openParkingPanes(f)
            val uri=if(retained) f.sourceUri else android.provider.DocumentsContract.buildDocumentUri(f.authority,"retry").toString()
            val id=withContext(Dispatchers.IO) {
                if(!retained) f.seed(f.retry)
                BundleReferenceInfo.inspectStrict(if(retained) f.source else f.retry).header.identity!!.documentId
            }
            withContext(Dispatchers.Main) {
                val model=f.editor.state.document;val other=peer.state.document
                val undo=if(retained) {f.addStroke(150.0,true);f.editor.history.nextUndo} else null
                val position=Editor.ReadingPosition(1.75,37.0,73.0,ViewOverrides(verticalScroll=false))
                val before=f.editor.state.pendingInitialView
                val cap=requireNotNull(f.editor.preparePagedPairOpen(NativeSplitSource(uri,expectedId=id),Pane.SECONDARY,position))
                try {
                    assertSame(model,f.editor.state.document);assertSame(other,peer.state.document)
                    assertSame(before,f.editor.state.pendingInitialView)
                    assertTrue(cap.commit())
                    val restored=cap.target.state.pendingInitialView as? InitialView.Restore
                    assertNotNull("The saved reading position belongs to this same adoption",restored)
                    assertEquals(position.zoom,restored!!.zoom,0.0);assertEquals(position.x,restored.scrollX,0.0)
                    assertEquals(position.y,restored.scrollY,0.0);assertEquals(position.overrides,cap.target.viewOverrides)
                    if(retained) {assertSame(f.editor,cap.target);assertSame(model,cap.target.state.document);assertSame(undo,cap.target.history.nextUndo);assertTrue(model.dirty)}
                    else assertSame(peer,cap.target)
                } finally {cap.close()}
            }
        }
    }

    @Test fun aChangedExpectedIdentityOnTheFinalSourceReadKeepsBothOldModels()=runParkingScenario {f->
        withContext(Dispatchers.IO) {f.seed();f.seed(f.target)}
        val id=BundleReferenceInfo.inspectStrict(f.target).header.identity!!.documentId
        val reads=java.util.concurrent.atomic.AtomicInteger()
        f.beforeTargetRead={if(reads.incrementAndGet()==2) f.seed(f.target)}
        withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri,"source.xnote");val model=f.editor.state.document
            assertNull(f.editor.preparePagedPairOpen(NativeSplitSource(f.targetUri,expectedId=id),Pane.SECONDARY))
            assertSame(model,f.editor.state.document);assertTrue(f.editor.noteOpen);assertFalse(f.editor.secondary?.noteOpen==true)
        }
        assertEquals(0,f.writes.get())
    }

    @Test fun aFrozenNativeRequestIsConsumedWithoutReadingTheArrangementAgain()=runParkingScenario {f->
        withContext(Dispatchers.IO) {f.seed();f.seed(f.target)}
        val request=NativeSplitOpenParser.parse(JSONObject().put("format","xnotes-split").put("version",2)
            .put("primaryPath",f.sourceUri).put("secondPath",f.targetUri).toString().toByteArray())!!
        File(File(f.context.filesDir,"splits").apply {mkdirs()},"removed-after-read.xsplit").writeText("broken now")
        withContext(Dispatchers.Main) {
            val cap=requireNotNull(f.editor.prepareNativeSplitRequest(request))
            try {assertTrue(cap.commit());assertTrue(cap.first.noteOpen && cap.second.noteOpen)} finally {cap.close()}
        }
    }

    @Test fun dirtySaveReturnsTheActualPublishedBAndPendingReadingPositionInsteadOfTheOldReadBase()=runParkingScenario {f->
        open(f)
        val hashA=BundleMigration.hash(f.target)
        withContext(Dispatchers.Main) {
            val model=f.editor.state.document;f.addStroke(110.0,true);val undo=f.editor.history.nextUndo
            val position=Editor.ReadingPosition(1.6,31.0,61.0,ViewOverrides(rotation=90))
            f.editor.restoreReadingPosition(position)
            val saved=ready(f.editor.prepareMixedNoteArrangementSave(Pane.PRIMARY))
            assertSame(model,f.editor.state.document);assertSame(undo,f.editor.history.nextUndo);assertFalse(model.dirty)
            assertEquals(Pane.PRIMARY,saved.nativePane);assertEquals(f.targetUri,saved.source.uri)
            assertEquals(model.identity.documentId,saved.source.documentId);assertEquals(position,saved.position)
            assertEquals(hashA,f.editor.lastReadSourceVersion()!!.sha256)
            assertNotEquals(hashA,saved.version.sha256);assertEquals(BundleMigration.hash(f.target),saved.version.sha256)
            assertTrue(saved.current());assertTrue(requireNotNull(saved.verifySource()).invoke())
        }
        assertEquals(1,f.writes.get());assertEquals(0,f.deletes.get())
    }

    @Test fun lateC1StaysDirtyAndCannotReceiveASuccessTicketForWrittenB()=runParkingScenario {f->
        open(f);withContext(Dispatchers.Main) {f.addStroke(110.0,true)}
        val entered=CompletableDeferred<Unit>()
        f.beforeTargetWrite={entered.complete(Unit);check(f.release.await(10,TimeUnit.SECONDS))}
        coroutineScope {
            val preparing=async(Dispatchers.Main) {f.editor.prepareMixedNoteArrangementSave(Pane.PRIMARY)}
            try {
                reachedWrite(entered,preparing)
                withContext(Dispatchers.Main) {f.addStroke(290.0,true)}
            } finally {f.release.countDown()}
            assertNull(preparing.await())
        }
        val written=f.target.inputStream().use {f.codec.read(it)}
        assertEquals(listOf(110.0),written.pages.first().items.filterIsInstance<Stroke>().map {it.samples.first().x})
        f.beforeTargetWrite=null
        withContext(Dispatchers.Main) {
            assertTrue(f.editor.state.document.dirty)
            val latest=ready(f.editor.prepareMixedNoteArrangementSave(Pane.PRIMARY))
            assertFalse(f.editor.state.document.dirty);assertEquals(BundleMigration.hash(f.target),latest.version.sha256)
        }
        assertEquals(2,f.writes.get())
    }

    @Test fun aFailedPublicationPreservesTheOldFileAndDirtyActualModel()=runParkingScenario {f->
        open(f);val original=f.target.readBytes()
        withContext(Dispatchers.Main) {
            val model=f.editor.state.document;f.addStroke(110.0,true);val undo=f.editor.history.nextUndo
            f.beforeTargetWrite={throw IOException("Controlled publication failure")}
            assertNull(f.editor.prepareMixedNoteArrangementSave(Pane.PRIMARY))
            assertSame(model,f.editor.state.document);assertSame(undo,f.editor.history.nextUndo);assertTrue(model.dirty)
        }
        assertArrayEquals(original,f.target.readBytes());assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }

    @Test fun cancellingTheCallerDoesNotLoseTheActualAcceptedPublicationOfB()=runParkingScenario {f->
        open(f);val hashA=BundleMigration.hash(f.target)
        withContext(Dispatchers.Main) {f.addStroke(110.0,true)}
        val entered=CompletableDeferred<Unit>()
        f.beforeTargetWrite={entered.complete(Unit);check(f.release.await(10,TimeUnit.SECONDS))}
        coroutineScope {
            val preparing=async(Dispatchers.Main) {f.editor.prepareMixedNoteArrangementSave(Pane.PRIMARY)}
            var accepted:Job?=null
            try {
                reachedWrite(entered,preparing)
                withContext(Dispatchers.Main) {accepted=job(f.editor,"noteWriteJob");assertNotNull(accepted);preparing.cancel()}
            } finally {f.release.countDown()}
            preparing.join();accepted?.join()
        }
        f.beforeTargetWrite=null
        withContext(Dispatchers.Main) {
            assertNotEquals(hashA,BundleMigration.hash(f.target));assertFalse(f.editor.state.document.dirty)
            val next=ready(f.editor.prepareMixedNoteArrangementSave(Pane.PRIMARY))
            assertEquals(BundleMigration.hash(f.target),next.version.sha256);assertTrue(next.current())
        }
        assertEquals(1,f.writes.get())
    }

    @Test fun aCleanRestoredSessionWithoutASourceObservationCannotBecomeAReusableArrangement()=runParkingScenario {f->
        withContext(Dispatchers.IO) {
            f.seed(f.target)
            val cached=f.target.inputStream().use {f.codec.read(it)}
            val files=f.context.filesDir
            val session=SessionStore(File(files,"session"),f.codec,File(files,"pdfsrc"),File(files,"noteimg"))
            assertEquals(LegacySessionWrite.SAVED,session.saveChecked(cached.fixedWriteSnapshot(),
                SessionStore.Metadata(f.targetUri,"target.xnote",false,1.0,0.0,0.0,false,ViewOverrides())))
        }
        val original=f.target.readBytes()
        withContext(Dispatchers.Main) {
            f.editor.restoreSession();val model=f.editor.state.document
            assertTrue(f.editor.noteOpen);assertFalse(model.dirty);assertNull(f.editor.lastReadSourceVersion())
            assertNull(f.editor.prepareMixedNoteArrangementSave(Pane.PRIMARY));assertSame(model,f.editor.state.document)
        }
        assertArrayEquals(original,f.target.readBytes());assertEquals(0,f.writes.get())
    }

    @Test fun aStylePreviewWaitsWithoutConfirmationAndCancellingTheRequestPreservesIt()=runParkingScenario {f->
        open(f)
        withContext(Dispatchers.Main) {
            f.addStroke(110.0,true);f.editor.controller.selectAll();f.editor.controller.restyleSelection(null,10.0,preview=true)
        }
        val selected=withContext(Dispatchers.Main) {f.editor.controller.selectedItems()}
        val revision=f.editor.state.document.bodyRevision;val undo=f.editor.history.nextUndo
        val configs=selected.filterIsInstance<Stroke>().map {it.config}
        coroutineScope {
            val preparing=async(Dispatchers.Main) {f.editor.prepareMixedNoteArrangementSave(Pane.PRIMARY)}
            try {
                repeat(2) {parkingDispatch()}
                assertFalse("The save must wait for the user's unconfirmed style preview",preparing.isCompleted)
                preparing.cancelAndJoin()
                withContext(Dispatchers.Main) {
                    assertTrue(f.editor.controller.hasPendingContentTransaction);assertEquals(selected,f.editor.controller.selectedItems())
                    assertEquals(revision,f.editor.state.document.bodyRevision);assertSame(undo,f.editor.history.nextUndo)
                    assertEquals(configs,selected.filterIsInstance<Stroke>().map {it.config})
                }
            } finally {preparing.cancelAndJoin()}
        }
        assertEquals(0,f.writes.get())
    }

    @Test fun explicitTextSavingCommitsTheActualDraftBeforeSigningTheSavedBody()=runParkingScenario {f->
        open(f)
        withContext(Dispatchers.Main) {
            f.editor.selectTool(Tool.TEXT_BOX)
            parkingPen(f.editor,MotionEvent.ACTION_DOWN,Pt(220.0,220.0));parkingPen(f.editor,MotionEvent.ACTION_UP,Pt(220.0,220.0))
            f.editor.updateEditingText("Explicit arrangement save text")
            assertTrue(f.editor.controller.isEditingText)
            val saved=ready(f.editor.prepareMixedNoteArrangementSave(Pane.PRIMARY))
            assertFalse(f.editor.controller.isEditingText);assertFalse(f.editor.state.document.dirty)
            assertEquals(BundleMigration.hash(f.target),saved.version.sha256)
        }
        val stored=f.target.inputStream().use {f.codec.read(it)}
        assertTrue(stored.pages.any {it.items.any {item->item is TextItem}})
    }

    @Test fun runtimeProofRejectsLateEditsPathsModelAbaJobsAndPhysicalInput() {
        for(change in listOf("edit","path","model","job","input","host")) runParkingScenario {f->
            open(f)
            withContext(Dispatchers.Main) {
                var hostCurrent=true
                val saved=ready(f.editor.prepareMixedNoteArrangementSave(Pane.PRIMARY) {hostCurrent})
                assertTrue(saved.current())
                val oldJob=job(f.editor,"canvasWriteJob");var blocking:CompletableJob?=null
                try {
                    when(change) {
                        "edit"->f.addStroke(110.0,true)
                        "path"->f.editor.state.document.path=f.sourceUri
                        "model"->{
                            val old=f.editor.state.document
                            val replacement=Document(mutableListOf(Page(500.0,500.0))).apply {identity=old.identity;path=old.path}
                            ReflectionHelpers.callInstanceMethod<Unit>(f.editor,"replaceDocument",
                                ReflectionHelpers.ClassParameter.from(Document::class.java,replacement))
                        }
                        "job"->{blocking=Job();Editor::class.java.getDeclaredField("canvasWriteJob").apply {isAccessible=true}.set(f.editor,blocking)}
                        "input"->parkingPen(f.editor,MotionEvent.ACTION_DOWN,Pt(170.0,170.0))
                        else->hostCurrent=false
                    }
                    assertFalse("Late $change must retire the saved arrangement proof",saved.current())
                } finally {
                    blocking?.complete();if(change=="job") Editor::class.java.getDeclaredField("canvasWriteJob").apply {isAccessible=true}.set(f.editor,oldJob)
                    if(change=="input") parkingPen(f.editor,MotionEvent.ACTION_CANCEL,Pt(170.0,170.0))
                }
            }
        }
    }

    @Test fun finalSourceVerificationRejectsExternalCWithTheSameUuidAndUnchangedRuntime()=runParkingScenario {f->
        open(f)
        val saved=withContext(Dispatchers.Main) {
            f.addStroke(110.0,true);ready(f.editor.prepareMixedNoteArrangementSave(Pane.PRIMARY))
        }
        val model=f.editor.state.document;val revision=model.bodyRevision;val hashB=saved.version.sha256
        withContext(Dispatchers.IO) {
            val external=f.target.inputStream().use {f.codec.read(it)}
            external.pages.first().items+=Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),listOf(Sample(900.0,100.0,.5),Sample(930.0,120.0,.8)))
            f.target.outputStream().use {f.codec.write(external.fixedWriteSnapshot(),it)}
            assertEquals(model.identity.documentId,external.identity.documentId)
        }
        withContext(Dispatchers.Main) {
            assertSame(model,f.editor.state.document);assertEquals(revision,model.bodyRevision);assertFalse(model.dirty)
            assertTrue("An external change is deliberately invisible to runtime-only proof",saved.current())
            assertNotEquals(hashB,BundleMigration.hash(f.target));assertNull(saved.verifySource())
            assertEquals(hashB,saved.version.sha256);assertEquals(revision,model.bodyRevision);assertFalse(model.dirty)
        }
        assertEquals(1,f.writes.get())
    }

    @Test fun mixedPurePersistenceKeepsHoldAndWritesOnlyItsOwnWorkingCopyAndPendingPosition()=runParkingScenario {f->
        open(f)
        val original=f.target.readBytes();var old:List<PenShortcut>?=null
        val down=KeyEvent(1L,2L,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_F1,0)
        try {
            withContext(Dispatchers.Main) {
                old=f.editor.preferences.penShortcuts
                assertTrue(f.editor.setPenShortcuts(listOf(PenShortcut("hold",listOf(requireNotNull(PenSignals.key(down))),PenBinding("eraser",PenBindingMode.HOLD)))))
                f.editor.selectTool(Tool.PEN);assertTrue(f.editor.onRecordedPenKey(down))
                f.editor.mixedPresentationActive=true
                f.editor.restoreReadingPosition(Editor.ReadingPosition(1.75,37.0,73.0,ViewOverrides()))
                f.editor.persistMixedNotePresentation(Pane.PRIMARY)
                assertEquals(Tool.PEN,f.editor.toolbarTool);assertEquals(Tool.ERASER,f.editor.tool)
            }
            settleSession(f.editor)
            val metadataFile=File(f.context.filesDir,"session/session.json")
            assertTrue("Mixed success must persist its actual NOTE working copy",metadataFile.isFile)
            val metadata=JSONObject(metadataFile.readText())
            assertTrue(File(f.context.filesDir,"session/document.xnote").isFile)
            assertEquals(1.75,metadata.getDouble("zoom"),0.0);assertEquals(37.0,metadata.getDouble("scrollX"),0.0)
            assertEquals(73.0,metadata.getDouble("scrollY"),0.0)
            assertFalse(File(f.context.filesDir,"saved-split.json").exists());assertFalse(File(f.context.filesDir,"split-workspace.json").exists())
            withContext(Dispatchers.Main) {
                assertTrue(f.editor.onRecordedPenKey(KeyEvent.changeAction(down,KeyEvent.ACTION_UP)));assertEquals(Tool.PEN,f.editor.tool)
            }
        } finally {withContext(Dispatchers.Main) {old?.let(f.editor::setPenShortcuts)}}
        assertArrayEquals(original,f.target.readBytes());assertEquals(0,f.writes.get())
    }

    @Test fun nativeExplicitSaveKeepsFormalToolsAndHoldAndCannotReserveOverALaterRecentIntent()=runParkingScenario {f->
        val peer=openParkingPanes(f)
        val writer=SplitRecentWriter.forFilesDir(f.context.filesDir);writer.initialization.await()
        val entered=CompletableDeferred<Unit>()
        val down=KeyEvent(1L,2L,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_F1,0)
        var old:List<PenShortcut>?=null
        try {
            withContext(Dispatchers.Main) {
                f.editor.selectTool(Tool.PEN);f.editor.focusPane(Pane.SECONDARY);peer.selectTool(Tool.HIGHLIGHTER)
                old=f.editor.preferences.penShortcuts
                assertTrue(f.editor.setPenShortcuts(listOf(PenShortcut("hold",listOf(requireNotNull(PenSignals.key(down))),PenBinding("eraser",PenBindingMode.HOLD)))))
                assertTrue(peer.onRecordedPenKey(down))
                val page=peer.state.document.pages.first()
                val stroke=Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),listOf(Sample(110.0,100.0,.5),Sample(140.0,130.0,.8)))
                page.items+=stroke;peer.history.push(com.xnotes.core.history.AddItem(page,stroke));peer.state.document.dirty=true
            }
            f.beforeTargetWrite={entered.complete(Unit);check(f.release.await(10,TimeUnit.SECONDS))}
            coroutineScope {
                val saving=async(Dispatchers.Main) {f.editor.saveCurrentSplit()}
                var later:SplitRecentWriter.Ticket?=null
                var request:SplitOpenRequest.Native?=null
                try {
                    reachedWrite(entered,saving)
                    withContext(Dispatchers.Main) {
                        // The later UI intent has reserved but has not submitted its descriptor yet.
                        later=writer.reserve(explicit=true)
                        request=SplitOpenRequest.Native(NativeSplitOpenRequest(
                            NativeSplitSource(f.sourceUri,expectedId=f.editor.state.document.identity.documentId),
                            NativeSplitSource(f.targetUri,expectedId=peer.state.document.identity.documentId),
                            NativeSplitOpenLayout(.61f,false,true,Pane.PRIMARY,f.editor.readingPosition(),peer.readingPosition())))
                    }
                } finally {f.release.countDown()}
                assertTrue(saving.await())
                withContext(Dispatchers.Main) {
                    assertEquals(Tool.PEN,f.editor.toolbarTool);assertEquals(Tool.HIGHLIGHTER,peer.toolbarTool)
                    assertEquals(Tool.ERASER,peer.tool)
                }
                assertSame("The older save's success tail must not reserve a newer automatic ticket",
                    SplitRecentWrite.Written,writer.submit(requireNotNull(later),requireNotNull(request)).await())
                val stored=SplitArrangementStore(f.context.filesDir).readRecent() as SplitArrangementRead.Ready
                assertEquals(request,stored.request)
            }
            withContext(Dispatchers.Main) {
                assertTrue(peer.onRecordedPenKey(KeyEvent.changeAction(down,KeyEvent.ACTION_UP)))
                assertEquals(Tool.HIGHLIGHTER,peer.tool);assertEquals(Tool.PEN,f.editor.tool)
            }
        } finally {
            f.release.countDown();f.beforeTargetWrite=null
            withContext(Dispatchers.Main) {old?.let(f.editor::setPenShortcuts)}
        }
        assertEquals(1,f.writes.get());assertEquals(0,f.deletes.get())
    }
}
