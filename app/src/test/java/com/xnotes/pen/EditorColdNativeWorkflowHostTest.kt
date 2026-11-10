package com.xnotes.pen

import com.xnotes.canvas.ViewOverrides
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Document
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.CanvasCodec
import com.xnotes.core.model.NativeRestoreHint
import com.xnotes.mindmap.MapWorkspace
import com.xnotes.platform.*
import com.xnotes.ui.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual cache adoption and bound departure callbacks; no GPU or process-death claim. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class EditorColdNativeWorkflowHostTest {
    private fun field(editor:Editor,name:String)=Editor::class.java.getDeclaredField(name).apply {isAccessible=true}.get(editor)
    private fun directory(editor:Editor)=field(editor,"sessionDir") as File
    private fun owner(editor:Editor)=field(editor,"sessionOwner") as LegacySessionQueue.Owner
    private fun canvasCodec()=CanvasCodec(AndroidImageCodec(),AndroidTextMeasurer())
    private suspend fun note(f:EditorPageCopyFixture,editor:Editor,path:String?=null):Document {
        owner(editor).awaitAccepted()
        val document=Document.blank().apply {this.path=path;dirty=false}
        withContext(Dispatchers.IO) {
            if(path==f.sourceUri) f.source.outputStream().use {f.codec.write(document,it)}
            val store=SessionStore(directory(editor),f.codec,File(f.root,"pdf").apply {mkdirs()},File(f.root,"assets").apply {mkdirs()})
            assertEquals(LegacySessionWrite.SAVED,store.saveChecked(document,
                SessionStore.Metadata(path,null,false,1.0,0.0,0.0,false,ViewOverrides())))
        }
        return document
    }
    private suspend fun bound(f:EditorPageCopyFixture,block:suspend (ColdPresentationCoordinator)->Unit) {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val maps=withContext(Dispatchers.Main) {MapWorkspace(f.context,scope).also {it.bindHost(Any())}}
        val coordinator=withContext(Dispatchers.Main) {ColdPresentationCoordinator(f.editor,maps,ColdPresentationBootstrap())}
        try {
            withContext(Dispatchers.Main) {
                assertTrue(coordinator.bootstrap())
                f.editor.onPresentationIntent={coordinator.retireIntent()}
                f.editor.preparePresentationDeparture={coordinator.prepareDeparture(it)}
            }
            block(coordinator)
        } finally {withContext(NonCancellable+Dispatchers.Main) {
            f.editor.onPresentationIntent=null;f.editor.preparePresentationDeparture=null
            coordinator.close();maps.dispose().join();scope.cancel()
        }}
    }

    @Test fun boundSaveAsDoesNotMistakeItsOwnWriterForAnExternalOwnerAndPreservesLateC1()=runParkingScenario {f->
        note(f,f.editor,f.sourceUri)
        val original=f.source.readBytes()
        bound(f) {_->
            val model=withContext(Dispatchers.Main) {f.editor.state.document}
            val oldId=model.identity.documentId
            f.beforeTargetWrite={runBlocking {withContext(Dispatchers.Main) {f.addStroke(90.0,recordHistory=true)}}}
            val done=CompletableDeferred<Boolean>()
            withContext(Dispatchers.Main) {f.editor.saveAsToThen(f.targetUri,"Recovered.xnote") {done.complete(it)}}
            assertTrue(done.await())
            withContext(Dispatchers.Main) {
                assertSame(model,f.editor.state.document);assertNotEquals(oldId,model.identity.documentId)
                assertTrue(model.dirty);assertTrue(f.editor.history.canUndo);assertFalse(f.editor.requiresIndependentSave)
            }
            val saved=f.target.inputStream().use {f.codec.read(it)}
            assertTrue(saved.pages.single().items.isEmpty())
            assertTrue(LastPresentationStore(f.context.filesDir).observe().read is LastPresentationRead.Ready)
        }
        assertArrayEquals(original,f.source.readBytes())
    }

    @Test fun aProtectedCanvasCanSaveAnIndependentCopyWithoutWritingItsRememberedSource()=runParkingScenario {f->
        owner(f.editor).awaitAccepted()
        val document=InfiniteDocument(path=f.sourceUri)
        val codec=canvasCodec()
        withContext(Dispatchers.IO) {
            f.source.outputStream().use {codec.write(document,it)}
            assertEquals(LegacySessionWrite.SAVED,CanvasSessionStore(directory(f.editor),codec,File(f.root,"assets").apply {mkdirs()})
                .saveChecked(document,CanvasSessionStore.Metadata(f.sourceUri,null,false)))
        }
        val original=f.source.readBytes()
        bound(f) {_->
            val model=withContext(Dispatchers.Main) {
                assertTrue(f.editor.canvasOpen);assertTrue(f.editor.requiresIndependentSave)
                assertFalse(f.editor.saveCanvasBeforeReplacement());f.editor.infinite.document
            }
            val oldId=model.identity.documentId
            f.beforeTargetWrite={runBlocking {withContext(Dispatchers.Main) {
                model.add(Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),listOf(Sample(20.0,30.0,.5),Sample(40.0,50.0,.7))))
                model.dirty=true
            }}}
            val done=CompletableDeferred<Boolean>()
            withContext(Dispatchers.Main) {f.editor.saveAsToThen(f.targetUri,"Recovered.xcanvas") {done.complete(it)}}
            assertTrue(done.await())
            withContext(Dispatchers.Main) {
                assertSame(model,f.editor.infinite.document);assertNotEquals(oldId,model.identity.documentId)
                assertTrue(model.dirty);assertFalse(f.editor.requiresIndependentSave);assertEquals(f.targetUri,model.path)
            }
            assertTrue(f.target.inputStream().use {codec.read(it)}.items.isEmpty())
        }
        assertArrayEquals(original,f.source.readBytes());assertEquals(1,f.writes.get())
    }

    @Test fun aBadSecondNativeCacheCannotPartiallyAdoptTheFirst()=runParkingScenario {f->
        val second=withContext(Dispatchers.Main) {f.editor.secondaryPane()}
        val first=note(f,f.editor);note(f,second)
        val original=File(directory(f.editor),"document.xnote").readBytes()
        withContext(Dispatchers.Main) {
            val value=LastPresentation.Native(listOf(
                ColdNativeSlot.Note(Pane.PRIMARY,NativeRestoreHint(first.identity.documentId,null,DocumentKind.NOTE)),
                ColdNativeSlot.Note(Pane.SECONDARY,NativeRestoreHint(UUID.randomUUID().toString(),null,DocumentKind.NOTE))),
                ColdNativeLayout(Pane.SECONDARY,.35f,true,true))
            assertNull(f.editor.prepareColdNativePresentation(value) {true})
            assertFalse(f.editor.noteOpen);assertFalse(second.noteOpen)
        }
        assertArrayEquals(original,File(directory(f.editor),"document.xnote").readBytes());assertEquals(0,f.writes.get())
    }

    @Test fun anAbsentLegacySplitWithOnlyMissingPanesCanLeaveForHomeANewNoteOrAnActualFile() {
        for(action in listOf("home","new","open")) runParkingScenario {f->
            owner(f.editor).awaitAccepted()
            val missingId=UUID.randomUUID().toString()
            withContext(Dispatchers.IO) {
                f.seed()
                val snapshot=org.json.JSONObject().put("format","xnotes-split").put("version",2)
                    .put("primaryOpen",true).put("secondOpen",true).put("shared",true)
                    .put("primaryId",missingId).put("secondId",missingId)
                    .put("primaryPath","content://unavailable/missing.xnote").put("secondPath","content://unavailable/missing.xnote")
                assertTrue(JsonStore(File(f.context.filesDir,"split-workspace.json")).writeChecked(snapshot))
            }
            bound(f) {_->
                withContext(Dispatchers.Main) {
                    assertEquals(2,f.editor.openPanes.size)
                    assertTrue(f.editor.openPanes.all {it.missingDocument!=null})
                    assertFalse(File(directory(f.editor),"document.xnote").exists())
                    when(action) {
                        "home"->f.editor.goHomeAll()
                        "new"->f.editor.newNote()
                        else->assertTrue(f.editor.openSingleFromHome(f.sourceUri,"source.xnote"))
                    }
                }
                withTimeout(5000) {
                    while(!withContext(Dispatchers.Main) {
                        if(action=="home") f.editor.openPanes.isEmpty()
                        else f.editor.noteOpen && f.editor.missingDocument==null
                    }) delay(1)
                }
                withContext(Dispatchers.Main) {
                    if(action=="home") assertFalse(File(directory(f.editor),"document.xnote").exists())
                    else assertNotEquals(missingId,f.editor.visibleDocumentId)
                    val marker=LastPresentationStore(f.context.filesDir).observe().read as LastPresentationRead.Ready
                    assertEquals(LastPresentation.Home,marker.value)
                }
            }
            assertEquals(0,f.writes.get())
        }
    }
}
