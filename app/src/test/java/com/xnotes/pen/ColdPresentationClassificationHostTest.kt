package com.xnotes.pen

import com.xnotes.canvas.ViewOverrides
import com.xnotes.core.model.Document
import com.xnotes.core.util.DocumentKind
import com.xnotes.mindmap.MapNativeRestoreHint
import com.xnotes.mindmap.MapWorkspace
import com.xnotes.platform.LegacySessionQueue
import com.xnotes.platform.LegacySessionWrite
import com.xnotes.platform.SessionStore
import com.xnotes.ui.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual cache/marker routing with fresh Editors; this does not simulate Android process death. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class ColdPresentationClassificationHostTest {
    private fun field(editor:Editor,name:String)=Editor::class.java.getDeclaredField(name).apply {isAccessible=true}.get(editor)
    private fun directory(editor:Editor)=field(editor,"sessionDir") as File
    private suspend fun seed(f:EditorPageCopyFixture):Document {
        (field(f.editor,"sessionOwner") as LegacySessionQueue.Owner).awaitAccepted()
        val document=Document.blank().apply {dirty=false}
        withContext(Dispatchers.IO) {
            val store=SessionStore(directory(f.editor),f.codec,File(f.root,"pdf").apply {mkdirs()},File(f.root,"assets").apply {mkdirs()})
            assertEquals(LegacySessionWrite.SAVED,store.saveChecked(document,
                SessionStore.Metadata(null,null,false,1.0,0.0,0.0,false,ViewOverrides())))
        }
        return document
    }
    private suspend fun write(f:EditorPageCopyFixture,value:LastPresentation) {
        val store=LastPresentationStore(f.context.filesDir)
        val attempt=store.begin(store.reserve(),value)
        try {
            assertEquals(LastPresentationPrepare.Ready,attempt.prepare())
            assertEquals(LastPresentationWrite.Written,withContext(Dispatchers.Main) {attempt.commit {true}})
        } finally {attempt.close()}
    }
    private suspend fun newEditor(f:EditorPageCopyFixture) {
        withContext(Dispatchers.Main) {
            val scope=field(f.editor,"autosaveScope") as CoroutineScope
            scope.cancel();scope.coroutineContext[Job]?.join()
        }
        (field(f.editor,"sessionOwner") as LegacySessionQueue.Owner).awaitAccepted()
        withContext(Dispatchers.Main) {f.editor=Editor(f.context)}
    }
    private suspend fun host(f:EditorPageCopyFixture,body:suspend (MapWorkspace,CoroutineScope)->Unit) {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val maps=withContext(Dispatchers.Main) {MapWorkspace(f.context,scope).also {it.bindHost(Any())}}
        try {body(maps,scope)}
        finally {withContext(NonCancellable+Dispatchers.Main) {maps.dispose().join();scope.cancel()}}
    }

    @Test fun absentIsTheOnlyColdClassificationThatReadsTheLegacySession()=runParkingScenario {f->
        val document=seed(f)
        host(f) {maps,_->withContext(Dispatchers.Main) {
            val coordinator=ColdPresentationCoordinator(f.editor,maps,ColdPresentationBootstrap())
            try {
                assertTrue(coordinator.bootstrap())
                assertTrue(f.editor.noteOpen);assertTrue(f.editor.requiresIndependentSave)
                assertEquals(document.identity.documentId,f.editor.visibleDocumentId)
            } finally {coordinator.close()}
        }}
    }

    @Test fun explicitHomeRemainsSafeAcrossAHostRebindEvenWithACompleteLegacyCache()=runParkingScenario {f->
        seed(f);write(f,LastPresentation.Home)
        val bootstrap=ColdPresentationBootstrap()
        host(f) {maps,_->
            withContext(Dispatchers.Main) {
                val first=ColdPresentationCoordinator(f.editor,maps,bootstrap)
                try {assertTrue(first.bootstrap());assertFalse(f.editor.noteOpen)} finally {first.close()}
            }
            newEditor(f)
            withContext(Dispatchers.Main) {
                val rebound=ColdPresentationCoordinator(f.editor,maps,bootstrap)
                try {assertTrue(rebound.bootstrap());assertFalse(f.editor.noteOpen)} finally {rebound.close()}
            }
        }
    }

    @Test fun invalidAndFutureMarkersPreserveTheCacheWithoutLegacyFallbackOnHostRebind() {
        for(raw in listOf("damaged marker","{\"format\":\"xnotes-last-presentation\",\"version\":99,\"state\":\"home\"}"))
            runParkingScenario {f->
                seed(f)
                val marker=File(f.context.filesDir,"last-presentation.json").apply {writeText(raw)}
                val bytes=marker.readBytes();val cached=File(directory(f.editor),"document.xnote").readBytes()
                val bootstrap=ColdPresentationBootstrap()
                host(f) {maps,_->
                    withContext(Dispatchers.Main) {
                        val first=ColdPresentationCoordinator(f.editor,maps,bootstrap)
                        try {
                            assertFalse(first.bootstrap());assertFalse(f.editor.noteOpen)
                            assertEquals(ColdPresentationProblem.INVALID,bootstrap.problem)
                        } finally {first.close()}
                    }
                    newEditor(f)
                    withContext(Dispatchers.Main) {
                        val rebound=ColdPresentationCoordinator(f.editor,maps,bootstrap)
                        try {
                            rebound.bootstrap();assertFalse(f.editor.noteOpen)
                            assertEquals(ColdPresentationProblem.INVALID,bootstrap.problem)
                        } finally {rebound.close()}
                    }
                }
                assertArrayEquals(bytes,marker.readBytes())
                assertArrayEquals(cached,File(directory(f.editor),"document.xnote").readBytes())
            }
    }

    @Test fun failedTypedNativeStaysSafeOnRebindAndCanRetryTheSameEditorWithACorrectHint()=runParkingScenario {f->
        val document=seed(f)
        fun value(id:String)=LastPresentation.Native(listOf(ColdNativeSlot.Note(Pane.PRIMARY,
            MapNativeRestoreHint(id,null,DocumentKind.NOTE))),ColdNativeLayout(Pane.PRIMARY))
        write(f,value(UUID.randomUUID().toString()))
        val bootstrap=ColdPresentationBootstrap()
        host(f) {maps,_->
            withContext(Dispatchers.Main) {
                val first=ColdPresentationCoordinator(f.editor,maps,bootstrap)
                try {
                    assertFalse(first.bootstrap());assertFalse(f.editor.noteOpen)
                    assertEquals(ColdPresentationProblem.UNAVAILABLE,bootstrap.problem)
                } finally {first.close()}
            }
            newEditor(f)
            withContext(Dispatchers.Main) {
                val rebound=ColdPresentationCoordinator(f.editor,maps,bootstrap)
                try {
                    rebound.bootstrap();assertFalse(f.editor.noteOpen)
                    assertEquals(ColdPresentationProblem.UNAVAILABLE,bootstrap.problem)
                    write(f,value(document.identity.documentId))
                    assertTrue(rebound.bootstrap(retry=true))
                    assertTrue(f.editor.noteOpen);assertTrue(f.editor.requiresIndependentSave)
                    assertEquals(document.identity.documentId,f.editor.visibleDocumentId)
                    assertNull(bootstrap.problem)
                } finally {rebound.close()}
            }
        }
        assertEquals(0,f.writes.get())
    }
}
