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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** In-memory restoration requests must obey the same contract as persisted descriptions. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class ColdNativeValidationHostTest {
    private fun field(editor:Editor,name:String)=Editor::class.java.getDeclaredField(name).apply {isAccessible=true}.get(editor)

    private suspend fun seed(f:EditorPageCopyFixture):MapNativeRestoreHint {
        (field(f.editor,"sessionOwner") as LegacySessionQueue.Owner).awaitAccepted()
        val document=Document.blank().apply {dirty=false}
        withContext(Dispatchers.IO) {
            val store=SessionStore(field(f.editor,"sessionDir") as File,f.codec,
                File(f.root,"pdf").apply {mkdirs()},File(f.root,"assets").apply {mkdirs()})
            assertEquals(LegacySessionWrite.SAVED,store.saveChecked(document,
                SessionStore.Metadata(null,null,false,1.0,0.0,0.0,false,ViewOverrides())))
        }
        return MapNativeRestoreHint(document.identity.documentId,null,DocumentKind.NOTE)
    }

    private fun rejected(value:LastPresentation.Native) {
        try {LastPresentationCodec.encode(value);fail("The persisted contract must reject this request")}
        catch(expected:IllegalArgumentException) { /* expected */ }
    }

    @Test fun outOfRangeSplitRatioIsRejectedBeforeAllocatingAnotherPane()=runParkingScenario {f->
        val hint=seed(f)
        withContext(Dispatchers.Main) {
            val value=LastPresentation.Native(listOf(ColdNativeSlot.Note(Pane.PRIMARY,hint),
                ColdNativeSlot.Home(Pane.SECONDARY)),ColdNativeLayout(Pane.PRIMARY,.1f))
            rejected(value)
            val prepared=f.editor.prepareColdNativePresentation(value) {true}
            try {
                assertNull("Invalid layout must not prepare a recoverable document",prepared)
                assertNull("Validation must precede allocating a new Editor",f.editor.secondary)
                assertFalse(f.editor.noteOpen)
            } finally {prepared?.close()}
        }
    }

    @Test fun nonFiniteReadingPositionCannotReachNativeAdoption()=runParkingScenario {f->
        val hint=seed(f)
        withContext(Dispatchers.Main) {
            val invalid=ColdNativeView(Editor.ReadingPosition(1.0,Double.NaN,0.0,ViewOverrides()),false)
            val value=LastPresentation.Native(listOf(ColdNativeSlot.Note(Pane.PRIMARY,hint,invalid)),ColdNativeLayout(Pane.PRIMARY))
            rejected(value)
            val prepared=f.editor.prepareColdNativePresentation(value) {true}
            try {assertNull("Non-finite view geometry must be rejected before adoption",prepared)}
            finally {prepared?.close()}
            assertFalse(f.editor.noteOpen)
        }
    }

    @Test fun validBoundaryLayoutsStillPrepareWithoutGrantingOriginalWriteAuthority()=runParkingScenario {f->
        val hint=seed(f)
        withContext(Dispatchers.Main) {
            for(ratio in listOf(.18f,.82f)) {
                val value=LastPresentation.Native(listOf(ColdNativeSlot.Note(Pane.PRIMARY,hint)),ColdNativeLayout(Pane.PRIMARY,ratio))
                assertEquals(value,LastPresentationCodec.parse(LastPresentationCodec.encode(value)))
                val prepared=requireNotNull(f.editor.prepareColdNativePresentation(value) {true})
                try {assertTrue(prepared.verify());assertFalse(f.editor.noteOpen)} finally {prepared.close()}
            }
        }
        assertEquals(0,f.writes.get())
    }
}
