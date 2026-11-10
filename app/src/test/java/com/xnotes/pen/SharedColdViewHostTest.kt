package com.xnotes.pen

import com.xnotes.canvas.ViewOverrides
import com.xnotes.core.model.Document
import com.xnotes.core.util.DocumentKind
import com.xnotes.mindmap.MapNativeRestoreHint
import com.xnotes.platform.LegacySessionQueue
import com.xnotes.platform.LegacySessionWrite
import com.xnotes.platform.SessionStore
import com.xnotes.ui.*
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Two readers of one document retain independent view state, without writing the original. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class SharedColdViewHostTest {
    private fun field(editor:Editor,name:String)=Editor::class.java.getDeclaredField(name).apply {isAccessible=true}.get(editor)
    private fun owner(editor:Editor)=field(editor,"sessionOwner") as LegacySessionQueue.Owner

    private suspend fun openShared(f:EditorPageCopyFixture,storedLock:Boolean=false,describedLock:Boolean?=null) {
        owner(f.editor).awaitAccepted()
        val document=Document.blank().apply {dirty=false}
        withContext(Dispatchers.IO) {
            val store=SessionStore(field(f.editor,"sessionDir") as File,f.codec,
                File(f.root,"pdf").apply {mkdirs()},File(f.root,"assets").apply {mkdirs()})
            assertEquals(LegacySessionWrite.SAVED,store.saveChecked(document,
                SessionStore.Metadata(null,null,false,1.0,0.0,0.0,storedLock,ViewOverrides())))
        }
        val description=LastPresentation.Native(listOf(
            ColdNativeSlot.Note(Pane.PRIMARY,MapNativeRestoreHint(document.identity.documentId,null,DocumentKind.NOTE),
                ColdNativeView(Editor.ReadingPosition(1.0,0.0,0.0,ViewOverrides()),false,describedLock)),
            ColdNativeSlot.Shared(Pane.SECONDARY,Pane.PRIMARY)),ColdNativeLayout(Pane.SECONDARY))
        adopt(f,requireNotNull(LastPresentationCodec.parse(LastPresentationCodec.encode(description))) as LastPresentation.Native)
    }

    private suspend fun adopt(f:EditorPageCopyFixture,value:LastPresentation.Native)=withContext(Dispatchers.Main) {
        val prepared=requireNotNull(f.editor.prepareColdNativePresentation(value) {true})
        try {
            assertTrue(prepared.verify());assertTrue(prepared.claimForAdoption());prepared.applyAdoption()
            assertSame(f.editor.state.document,requireNotNull(f.editor.secondary).state.document)
        } finally {prepared.close()}
    }

    private suspend fun retire(f:EditorPageCopyFixture) {
        val panes=withContext(Dispatchers.Main) {f.editor.openPanes.toList()}
        withContext(Dispatchers.Main) {
            for(pane in panes) {
                val scope=field(pane,"autosaveScope") as CoroutineScope
                scope.cancel();scope.coroutineContext[Job]?.join()
            }
        }
        for(pane in panes) owner(pane).awaitAccepted()
        withContext(Dispatchers.Main) {f.editor=Editor(f.context)}
    }

    @Test fun anOldOwnerViewUsesSessionLockWithoutApplyingItToTheSharedReader()=runParkingScenario {f->
        openShared(f,storedLock=true)
        withContext(Dispatchers.Main) {
            assertTrue(f.editor.zoomLocked);assertTrue(f.editor.state.zoomLocked)
            assertFalse(requireNotNull(f.editor.secondary).zoomLocked)
            assertFalse(requireNotNull(f.editor.secondary).state.zoomLocked)
        }
    }

    @Test fun anExplicitFalseLockOverridesOlderTrueSessionMetadata()=runParkingScenario {f->
        openShared(f,storedLock=true,describedLock=false)
        withContext(Dispatchers.Main) {
            assertFalse(f.editor.zoomLocked);assertFalse(f.editor.state.zoomLocked)
        }
    }

    @Test fun aSharedReadersZoomLockChangeInvalidatesTheWholePresentationProof()=runParkingScenario {f->
        openShared(f)
        withContext(Dispatchers.Main) {
            val checkpoint=requireNotNull(f.editor.prepareNativePresentationCheckpoint(emptySet()) {true})
            assertTrue(checkpoint.current())
            requireNotNull(f.editor.secondary).toggleZoomLock()
            assertFalse("A shared reader's independent setting belongs to the presentation proof",checkpoint.current())
            assertFalse(f.editor.zoomLocked)
        }
        assertEquals(0,f.writes.get())
    }

    @Test fun sharedReadersRestoreDifferentZoomLocksAndViewOverrides()=runParkingScenario {f->
        openShared(f)
        val primaryView=ViewOverrides(contrast=121)
        val secondaryView=ViewOverrides(contrast=157,scrollbar=false)
        val value=withContext(Dispatchers.Main) {
            f.editor.updateViewOverrides(primaryView)
            requireNotNull(f.editor.secondary).apply {updateViewOverrides(secondaryView);toggleZoomLock()}
            val checkpoint=requireNotNull(f.editor.prepareNativePresentationCheckpoint(emptySet()) {true})
            requireNotNull(LastPresentationCodec.parse(LastPresentationCodec.encode(checkpoint.value))) as LastPresentation.Native
        }
        retire(f);adopt(f,value)
        withContext(Dispatchers.Main) {
            val second=requireNotNull(f.editor.secondary)
            assertFalse(f.editor.zoomLocked)
            assertTrue("The second reader's lock must survive a serialized cold restoration",second.zoomLocked)
            assertTrue(second.state.zoomLocked)
            assertEquals(primaryView,f.editor.viewOverrides);assertEquals(secondaryView,second.viewOverrides)
            assertSame(f.editor.state.document,second.state.document)
            assertTrue(f.editor.requiresIndependentSave)
        }
        assertEquals(0,f.writes.get())
    }
}
