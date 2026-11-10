package com.xnotes.pen

import android.provider.DocumentsContract
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.MutableState
import com.xnotes.core.geometry.Pt
import com.xnotes.core.history.AddItem
import com.xnotes.core.model.AudioItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.ui.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class PreparedNativeSplitOpenHostTest {
    private fun retry(f:EditorPageCopyFixture)=DocumentsContract.buildDocumentUri(f.authority,"retry").toString()
    private fun add(editor:Editor,x:Double) {
        val page=editor.state.document.pages.first()
        val stroke=Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),listOf(Sample(x,110.0,.5),Sample(x+30,150.0,.8)))
        page.items+=stroke;editor.history.push(AddItem(page,stroke));editor.state.document.dirty=true
    }
    @Test fun twoFreshSourcesWaitForOneCommitBeforeEitherOldSlotIsReplaced()=runParkingScenario {f->
        withContext(Dispatchers.IO) {f.seed();f.seed(f.target);f.seed(f.retry)}
        withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri,"old.xnote");val old=f.editor.state.document
            var guards=0
            val cap=requireNotNull(f.editor.prepareNativePairOpen(NativeSplitSource(f.targetUri),NativeSplitSource(retry(f)),
                guardOwners={owners,current->guards++;assertTrue(current());assertEquals(listOf(f.editor),owners);emptySet()}))
            try {
                assertEquals(1,guards);assertSame(old,f.editor.state.document);assertTrue(f.editor.noteOpen)
                assertFalse(cap.second.noteOpen);assertTrue(cap.current());assertTrue(cap.commit());assertFalse(cap.commit())
                assertTrue(cap.first.noteOpen && cap.second.noteOpen);assertNotSame(old,cap.first.state.document)
                assertNotSame(cap.first.state.document,cap.second.state.document)
                assertEquals(f.targetUri,cap.first.currentUri);assertEquals(retry(f),cap.second.currentUri)
            } finally {cap.close();cap.close()}
        }
        assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }
    @Test fun secondInitialReadFailureLeavesTheOldTextDraftAndDoesNotCallGuard()=runParkingScenario {f->
        withContext(Dispatchers.IO) {f.seed();f.seed(f.target);f.retry.writeText("invalid NOTE")}
        withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri,"old.xnote");f.editor.view.layout(0,0,1000,1000);f.editor.selectTool(Tool.TEXT_BOX)
            parkingPen(f.editor,MotionEvent.ACTION_DOWN,Pt(220.0,220.0));parkingPen(f.editor,MotionEvent.ACTION_UP,Pt(220.0,220.0))
            f.editor.updateEditingText("Still composing old text")
            val model=f.editor.state.document;val revision=model.bodyRevision;val draft=f.editor.controller.activeTextItem()
            var guards=0
            assertNull(f.editor.prepareNativePairOpen(NativeSplitSource(f.targetUri),NativeSplitSource(retry(f)),guardOwners={_,_->guards++;emptySet()}))
            assertEquals(0,guards);assertSame(model,f.editor.state.document);assertEquals(revision,model.bodyRevision)
            assertSame(draft,f.editor.controller.activeTextItem());assertNotNull(f.editor.editingField);f.editor.commitText()
        }
        assertEquals(0,f.writes.get())
    }
    @Test fun twoRetainedOwnersKeepDirtyHistoryAndToolsWhileSavedPrefixesMapToTheirActualSlots()=runParkingScenario {f->
        val secondary=openParkingPanes(f)
        withContext(Dispatchers.Main) {
            add(f.editor,130.0);add(secondary,230.0);f.editor.selectTool(Tool.PEN);secondary.selectTool(Tool.HIGHLIGHTER)
            val models=listOf(f.editor.state.document,secondary.state.document);val undos=listOf(f.editor.history.nextUndo,secondary.history.nextUndo)
            val tokens=listOf(f.editor.penInputOwnerToken,secondary.penInputOwnerToken)
            var guards=0
            val cap=requireNotNull(f.editor.prepareNativePairOpen(NativeSplitSource(f.targetUri),NativeSplitSource(f.sourceUri),
                NativeSplitOpenLayout(.31f,true,false,Pane.SECONDARY,
                    secondary.readingPosition().copy(zoom=1.4,x=13.0,y=23.0),f.editor.readingPosition().copy(zoom=1.8,x=31.0,y=41.0)),
                guardOwners={owners,_->guards++;assertTrue(owners.isEmpty());emptySet()}))
            try {
                assertEquals(1,guards);assertSame(secondary,cap.first);assertSame(f.editor,cap.second)
                assertTrue(cap.commit());assertEquals(Pane.PRIMARY,cap.focusedPane);assertTrue(f.editor.splitReversed)
                assertEquals(.31f,f.editor.splitRatio,0f);assertEquals(Pane.PRIMARY,f.editor.focusedPane)
                assertSame(models[0],f.editor.state.document);assertSame(models[1],secondary.state.document)
                assertTrue(models.all {it.dirty});assertSame(undos[0],f.editor.history.nextUndo);assertSame(undos[1],secondary.history.nextUndo)
                assertSame(tokens[0],f.editor.penInputOwnerToken);assertSame(tokens[1],secondary.penInputOwnerToken)
                assertEquals(Tool.PEN,f.editor.toolbarTool);assertEquals(Tool.HIGHLIGHTER,secondary.toolbarTool)
                assertEquals(1.4,(secondary.state.pendingInitialView as com.xnotes.canvas.InitialView.Restore).zoom,0.0)
                assertEquals(31.0,(f.editor.state.pendingInitialView as com.xnotes.canvas.InitialView.Restore).scrollX,0.0)
            } finally {cap.close()}
        }
        assertEquals(0,f.writes.get())
    }
    @Test fun sameCanonicalSourcesDecodeOnceAndCreateOneOwnerWithTwoViews()=runParkingScenario {f->
        withContext(Dispatchers.IO) {f.seed(f.target)}
        val alias=DocumentsContract.buildDocumentUriUsingTree(DocumentsContract.buildTreeDocumentUri(f.authority,"root"),"target").toString()
        val reads=AtomicInteger();f.beforeTargetRead={reads.incrementAndGet()}
        withContext(Dispatchers.Main) {
            val cap=requireNotNull(f.editor.prepareNativePairOpen(NativeSplitSource(f.targetUri),NativeSplitSource(alias)))
            try {
                assertTrue(cap.commit());assertSame(cap.first.state.document,cap.second.state.document)
                assertSame(cap.first,cap.second.documentOwner);assertEquals(2,reads.get())
                add(cap.first,340.0);assertTrue(cap.second.history.canUndo)
            } finally {cap.close()}
        }
        assertEquals(0,f.writes.get())
    }
    @Test fun replacingAnExistingSharedPairWithAnotherFileReattachesBothFreshViews()=runParkingScenario {f->
        withContext(Dispatchers.IO) {f.seed();f.seed(f.target)}
        val sourceBytes=f.source.readBytes();val targetBytes=f.target.readBytes()
        withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri,"source.xnote")
            val peer=f.editor.secondaryPane();assertTrue(peer.openSharedView(f.editor))
            val previous=f.editor.state.document
            assertSame(previous,peer.state.document);assertTrue(f.editor.noteOpen && peer.noteOpen)
            val cap=requireNotNull(f.editor.prepareNativePairOpen(NativeSplitSource(f.targetUri),NativeSplitSource(f.targetUri),
                NativeSplitOpenLayout(.34f,false,true,Pane.SECONDARY,
                    f.editor.readingPosition().copy(zoom=1.2,x=13.0,y=29.0),
                    peer.readingPosition().copy(zoom=1.6,x=47.0,y=61.0)),
                guardOwners={owners,current->assertEquals(listOf(f.editor),owners);assertTrue(current());emptySet()}))
            try {
                assertSame(previous,f.editor.state.document);assertSame(previous,peer.state.document)
                assertTrue(cap.commit());assertSame(f.editor,cap.first);assertSame(peer,cap.second)
                assertTrue("The newly shared second view must stay open after the old shared owner is replaced",peer.noteOpen)
                assertTrue(f.editor.noteOpen);assertNotSame(previous,f.editor.state.document)
                assertSame(f.editor.state.document,peer.state.document);assertSame(f.editor,peer.documentOwner)
                assertEquals(f.targetUri,f.editor.currentUri);assertEquals(f.targetUri,peer.currentUri)
                assertEquals(Pane.SECONDARY,cap.focusedPane);assertEquals(Pane.SECONDARY,f.editor.focusedPane)
                assertEquals(.34f,f.editor.splitRatio,0f);assertEquals(false,f.editor.splitSideBySide);assertTrue(f.editor.splitReversed)
                val first=f.editor.state.pendingInitialView as com.xnotes.canvas.InitialView.Restore
                val second=peer.state.pendingInitialView as com.xnotes.canvas.InitialView.Restore
                assertEquals(1.2,first.zoom,0.0);assertEquals(13.0,first.scrollX,0.0);assertEquals(29.0,first.scrollY,0.0)
                assertEquals(1.6,second.zoom,0.0);assertEquals(47.0,second.scrollX,0.0);assertEquals(61.0,second.scrollY,0.0)
                add(f.editor,350.0);assertTrue(peer.history.canUndo);assertSame(f.editor.history.nextUndo,peer.history.nextUndo)
            } finally {cap.close()}
        }
        assertArrayEquals(sourceBytes,f.source.readBytes());assertArrayEquals(targetBytes,f.target.readBytes())
        assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }
    @Test fun aSecondarySharedOwnerKeepsBothActualViewsAndNonHalfSavedGeometry()=runParkingScenario {f->
        withContext(Dispatchers.IO) {f.seed(f.target)}
        withContext(Dispatchers.Main) {
            val owner=f.editor.secondaryPane();owner.openAsync(f.targetUri,"saved.xnote");assertTrue(f.editor.openSharedView(owner))
            val model=owner.state.document;val ownerToken=owner.penInputOwnerToken;val peerToken=f.editor.penInputOwnerToken
            add(owner,160.0)
            val cap=requireNotNull(f.editor.prepareNativePairOpen(NativeSplitSource(f.targetUri),NativeSplitSource(f.targetUri),
                NativeSplitOpenLayout(.27f,false,true,Pane.SECONDARY,
                    owner.readingPosition().copy(zoom=1.3,x=12.0,y=22.0),f.editor.readingPosition().copy(zoom=1.6,x=32.0,y=42.0))))
            try {
                assertSame(owner,cap.first);assertSame(f.editor,cap.second);assertTrue(cap.commit())
                assertFalse(f.editor.splitReversed);assertEquals(.27f,f.editor.splitRatio,0f);assertEquals(Pane.PRIMARY,f.editor.focusedPane)
                assertSame(model,owner.state.document);assertSame(model,f.editor.state.document);assertSame(owner,f.editor.documentOwner)
                assertSame(ownerToken,owner.penInputOwnerToken);assertSame(peerToken,f.editor.penInputOwnerToken);assertTrue(model.dirty)
                assertEquals(22.0,(owner.state.pendingInitialView as com.xnotes.canvas.InitialView.Restore).scrollY,0.0)
                assertEquals(42.0,(f.editor.state.pendingInitialView as com.xnotes.canvas.InitialView.Restore).scrollY,0.0)
            } finally {cap.close()}
        }
        assertEquals(0,f.writes.get())
    }
    @Test fun aNewSharedPeerClearsOnlyItsOldSelectionWhileTheRetainedOwnerKeepsItsSelection()=runParkingScenario {f->
        val peer=openParkingPanes(f)
        val sourceBytes=f.source.readBytes();val targetBytes=f.target.readBytes()
        withContext(Dispatchers.Main) {
            add(f.editor,170.0);add(peer,270.0)
            val retainedModel=f.editor.state.document;val retiredModel=peer.state.document
            val retainedStroke=retainedModel.pages.first().items.last() as Stroke
            val retiredStroke=retiredModel.pages.first().items.last() as Stroke
            f.editor.controller.selectPlaced(0,listOf(retainedStroke));peer.controller.selectPlaced(0,listOf(retiredStroke))
            assertTrue(f.editor.hasSelection);assertTrue(peer.hasSelection)
            assertFalse(f.editor.controller.hasPendingContentTransaction);assertFalse(peer.controller.hasPendingContentTransaction)
            val retainedHistory=f.editor.history;val retainedUndo=retainedHistory.nextUndo
            val retiredUndo=peer.history.nextUndo
            val retainedRevision=retainedModel.bodyRevision;val retiredRevision=retiredModel.bodyRevision
            val retainedSamples=retainedStroke.samples.toList();val retiredSamples=retiredStroke.samples.toList()
            val cap=requireNotNull(f.editor.prepareNativePairOpen(NativeSplitSource(f.sourceUri),NativeSplitSource(f.sourceUri),
                guardOwners={owners,current->
                    assertEquals(listOf(peer),owners);assertTrue(current());setOf(peer.discardForOpen())
                }))
            try {
                assertEquals(listOf(retainedStroke),f.editor.controller.selectedItems())
                assertEquals(listOf(retiredStroke),peer.controller.selectedItems())
                assertSame(retiredModel,peer.state.document);assertSame(retiredUndo,peer.history.nextUndo)
                assertTrue(cap.commit());assertSame(f.editor,cap.first);assertSame(peer,cap.second)
                assertTrue(peer.controller.selectedItems().isEmpty());assertFalse(peer.hasSelection);assertNull(peer.selectionMenuRect)
                assertEquals(listOf(retainedStroke),f.editor.controller.selectedItems());assertTrue(f.editor.hasSelection)
                assertSame(retainedModel,f.editor.state.document);assertSame(retainedModel,peer.state.document)
                assertSame(retainedHistory,f.editor.history);assertSame(retainedUndo,f.editor.history.nextUndo)
                assertEquals(retainedRevision,retainedModel.bodyRevision);assertEquals(retiredRevision,retiredModel.bodyRevision)
                assertTrue(retainedModel.dirty);assertTrue(retiredModel.dirty)
                assertEquals(retainedSamples,retainedStroke.samples);assertEquals(retiredSamples,retiredStroke.samples)
                assertTrue(retiredModel.pages.first().items.any {it===retiredStroke})
            } finally {cap.close()}
        }
        assertArrayEquals(sourceBytes,f.source.readBytes());assertArrayEquals(targetBytes,f.target.readBytes())
        assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }
    @Test fun aDiscardedPeersRecordingCannotBePlacedIntoItsNewSharedDocument()=runParkingScenario {f->
        val peer=openParkingPanes(f)
        val sourceBytes=f.source.readBytes();val targetBytes=f.target.readBytes()
        try {
            withContext(Dispatchers.Main) {
                assertNull(AudioRecording.session)
                val retained=f.editor.state.document;val retired=peer.state.document
                val revision=retained.bodyRevision;val retiredRevision=retired.bodyRevision
                val undo=f.editor.history.nextUndo
                val file=File(f.context.cacheDir,"fake-in-progress.aac").apply {writeBytes(byteArrayOf(1,2,3,4))}
                // Use the real RecordOwner/Session and Stop path without asking for a microphone.
                val recording=AudioRecording.Session(peer,peer.freshRecordingId(),"Old peer recording",file).apply {
                    paused=true;accumulated=1500L
                }
                @Suppress("UNCHECKED_CAST")
                val session=AudioRecording::class.java.getDeclaredField("session\$delegate").apply {isAccessible=true}
                    .get(AudioRecording) as MutableState<AudioRecording.Session?>
                session.value=recording
                val cap=f.editor.prepareNativePairOpen(NativeSplitSource(f.sourceUri),NativeSplitSource(f.sourceUri),
                    guardOwners={owners,current->assertEquals(listOf(peer),owners);assertTrue(current());setOf(peer.discardForOpen())})
                if(cap==null) {
                    // Refusing an active recording is also safe, provided nothing was retired.
                    assertSame(retained,f.editor.state.document);assertSame(retired,peer.state.document)
                    assertEquals(retiredRevision,retired.bodyRevision);assertSame(recording,AudioRecording.session)
                } else try {
                    assertTrue(cap.commit());assertSame(retained,peer.state.document)
                    AudioRecording.stop()
                    assertNull(AudioRecording.session)
                    assertTrue("Stopping the old peer's recording must never insert it into the retained document",
                        retained.pages.none {page->page.items.any {it is AudioItem}})
                } finally {cap.close()}
                assertEquals(revision,retained.bodyRevision);assertSame(undo,f.editor.history.nextUndo)
            }
        } finally {
            withContext(NonCancellable+Dispatchers.Main) {AudioRecording.abandon()}
            // Drain the fake ADTS remux before the fixture removes its files.
            withContext(NonCancellable+Dispatchers.IO) {
                val worker=AudioRecording::class.java.getDeclaredField("worker").apply {isAccessible=true}
                    .get(AudioRecording) as ExecutorService
                worker.submit {}.get(5,TimeUnit.SECONDS)
            }
            withContext(NonCancellable) {parkingDispatch()}
        }
        assertArrayEquals(sourceBytes,f.source.readBytes());assertArrayEquals(targetBytes,f.target.readBytes())
        assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }
    @Test fun publicSplitFileOpenKeepsBothRetainedFormalToolsAndTheFocusedOwnersHold()=runParkingScenario {f->
        val peer=openParkingPanes(f)
        val sourceBytes=f.source.readBytes();val targetBytes=f.target.readBytes()
        withContext(Dispatchers.Main) {
            f.editor.selectTool(Tool.PEN);f.editor.focusPane(Pane.SECONDARY);peer.selectTool(Tool.HIGHLIGHTER)
            val down=KeyEvent(1L,2L,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_F1,0)
            val old=f.editor.preferences.penShortcuts
            try {
                assertTrue(f.editor.setPenShortcuts(listOf(PenShortcut("hold",listOf(requireNotNull(PenSignals.key(down))),
                    PenBinding("eraser",PenBindingMode.HOLD)))))
                assertTrue(peer.onRecordedPenKey(down));assertEquals(Tool.ERASER,peer.tool)
                val models=listOf(f.editor.state.document,peer.state.document)
                val histories=listOf(f.editor.history,peer.history)
                val tokens=listOf(f.editor.penInputOwnerToken,peer.penInputOwnerToken)
                val file=File(File(f.context.filesDir,"splits").apply {mkdirs()},"retained.xsplit")
                file.writeText(JSONObject().put("format","xnotes-split").put("version",2)
                    .put("primaryPath",f.sourceUri).put("secondPath",f.targetUri).put("focused",Pane.SECONDARY.name)
                    .put("ratio",.29).put("sideBySide",false).put("reversed",true)
                    .put("primaryZoom",1.25).put("primaryX",17.0).put("primaryY",27.0)
                    .put("zoom",1.75).put("x",47.0).put("y",57.0).toString())
                assertTrue(f.editor.openSplitFile(android.net.Uri.fromFile(file).toString()))
                assertSame(models[0],f.editor.state.document);assertSame(models[1],peer.state.document)
                assertSame(histories[0],f.editor.history);assertSame(histories[1],peer.history)
                assertSame(tokens[0],f.editor.penInputOwnerToken);assertSame(tokens[1],peer.penInputOwnerToken)
                assertEquals(Pane.SECONDARY,f.editor.focusedPane)
                assertEquals(Tool.PEN,f.editor.toolbarTool);assertEquals(Tool.HIGHLIGHTER,peer.toolbarTool)
                assertEquals(Tool.ERASER,peer.tool)
                val saved=JSONObject(File(f.context.filesDir,"split-workspace.json").readText())
                assertEquals(1.25,saved.getDouble("primaryZoom"),0.0)
                assertEquals(17.0,saved.getDouble("primaryX"),0.0);assertEquals(27.0,saved.getDouble("primaryY"),0.0)
                assertEquals(1.75,saved.getDouble("zoom"),0.0)
                assertEquals(47.0,saved.getDouble("x"),0.0);assertEquals(57.0,saved.getDouble("y"),0.0)
                assertEquals(.29,saved.getDouble("ratio"),.000001)
                assertFalse(saved.getBoolean("sideBySide"));assertTrue(saved.getBoolean("reversed"))
                assertEquals(Pane.SECONDARY.name,saved.getString("focused"))
                assertEquals(1.25,(f.editor.state.pendingInitialView as com.xnotes.canvas.InitialView.Restore).zoom,0.0)
                assertEquals(57.0,(peer.state.pendingInitialView as com.xnotes.canvas.InitialView.Restore).scrollY,0.0)
                assertTrue(peer.onRecordedPenKey(KeyEvent.changeAction(down,KeyEvent.ACTION_UP)))
                assertEquals(Tool.HIGHLIGHTER,peer.tool);assertEquals(Tool.PEN,f.editor.tool)
            } finally {f.editor.setPenShortcuts(old)}
        }
        assertArrayEquals(sourceBytes,f.source.readBytes());assertArrayEquals(targetBytes,f.target.readBytes())
        assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }
    @Test fun duplicateIncomingUuidAndMissingExplicitSavedMemberNeverReplaceTheOldWorkspace() {
        for(saved in listOf(false,true)) runParkingScenario {f->
            withContext(Dispatchers.IO) {f.seed();f.seed(f.target);f.target.copyTo(f.retry,overwrite=true)}
            withContext(Dispatchers.Main) {
                f.editor.openAsync(f.sourceUri,"old.xnote");val model=f.editor.state.document;var guards=0
                if(saved) {
                    val file=File(File(f.context.filesDir,"splits").apply {mkdirs()},"broken.xsplit")
                    file.writeText(JSONObject().put("format","xnotes-split").put("version",2)
                        .put("primaryPath",f.targetUri).put("secondPath",DocumentsContract.buildDocumentUri(f.authority,"missing").toString()).toString())
                    assertFalse(f.editor.openSplitFile(android.net.Uri.fromFile(file).toString()))
                } else assertNull(f.editor.prepareNativePairOpen(NativeSplitSource(f.targetUri),NativeSplitSource(retry(f)),guardOwners={_,_->guards++;emptySet()}))
                assertEquals(0,guards);assertSame(model,f.editor.state.document);assertTrue(f.editor.noteOpen);assertNull(f.editor.missingDocument)
                assertFalse(f.editor.secondary?.noteOpen==true)
            }
            assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
        }
    }
}
