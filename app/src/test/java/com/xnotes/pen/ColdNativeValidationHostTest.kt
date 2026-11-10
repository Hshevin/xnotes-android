package com.xnotes.pen

import com.xnotes.canvas.ViewOverrides
import com.xnotes.core.model.Document
import com.xnotes.core.util.DocumentKind
import com.xnotes.core.model.NativeRestoreHint
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

    private suspend fun seed(f:EditorPageCopyFixture,editor:Editor=f.editor):NativeRestoreHint {
        (field(editor,"sessionOwner") as LegacySessionQueue.Owner).awaitAccepted()
        val document=Document.blank().apply {dirty=false}
        withContext(Dispatchers.IO) {
            val store=SessionStore(field(editor,"sessionDir") as File,f.codec,
                File(f.root,"pdf").apply {mkdirs()},File(f.root,"assets").apply {mkdirs()})
            assertEquals(LegacySessionWrite.SAVED,store.saveChecked(document,
                SessionStore.Metadata(null,null,false,1.0,0.0,0.0,false,ViewOverrides())))
        }
        return NativeRestoreHint(document.identity.documentId,null,DocumentKind.NOTE)
    }

    private fun rejected(value:LastPresentation.Native) {
        try {LastPresentationCodec.encode(value);fail("The persisted contract must reject this request")}
        catch(expected:IllegalArgumentException) { /* expected */ }
    }

    @Test fun retiringARequestAfterVerificationCannotAdoptItsPrivateModel()=runParkingScenario {f->
        val hint=seed(f)
        withContext(Dispatchers.Main) {
            var current=true
            val value=LastPresentation.Native(listOf(ColdNativeSlot.Note(Pane.PRIMARY,hint)),ColdNativeLayout(Pane.PRIMARY))
            val prepared=requireNotNull(f.editor.prepareColdNativePresentation(value) {current})
            try {
                assertTrue(prepared.verify())
                current=false
                assertFalse(prepared.claimForAdoption())
                assertFalse(f.editor.noteOpen)
            } finally {prepared.close()}
        }
        assertEquals(0,f.writes.get())
    }

    @Test fun closingAnUnadoptedPreparationPreservesTheCacheForTheNextAttempt()=runParkingScenario {f->
        val hint=seed(f)
        val body=File(field(f.editor,"sessionDir") as File,"document.xnote")
        val original=body.readBytes()
        withContext(Dispatchers.Main) {
            val value=LastPresentation.Native(listOf(ColdNativeSlot.Note(Pane.PRIMARY,hint)),ColdNativeLayout(Pane.PRIMARY))
            val abandoned=requireNotNull(f.editor.prepareColdNativePresentation(value) {true})
            abandoned.close();abandoned.close()
            assertFalse(abandoned.verify());assertFalse(abandoned.claimForAdoption())
            val next=requireNotNull(f.editor.prepareColdNativePresentation(value) {true})
            try {
                assertTrue(next.verify());assertTrue(next.claimForAdoption());next.applyAdoption()
                assertFalse(next.claimForAdoption())
                assertEquals(hint.documentId,f.editor.visibleDocumentId)
                assertTrue(f.editor.requiresIndependentSave)
            } finally {next.close()}
        }
        assertArrayEquals(original,body.readBytes());assertEquals(0,f.writes.get())
    }

    @Test fun changingTheSecondCacheAfterPreparationCannotAdoptEitherPane()=runParkingScenario {f->
        val second=withContext(Dispatchers.Main) {f.editor.secondaryPane()}
        val firstHint=seed(f);val secondHint=seed(f,second)
        val firstBody=File(field(f.editor,"sessionDir") as File,"document.xnote")
        val secondBody=File(field(second,"sessionDir") as File,"document.xnote")
        val original=firstBody.readBytes()
        withContext(Dispatchers.Main) {
            val value=LastPresentation.Native(listOf(ColdNativeSlot.Note(Pane.PRIMARY,firstHint),
                ColdNativeSlot.Note(Pane.SECONDARY,secondHint)),ColdNativeLayout(Pane.SECONDARY))
            val prepared=requireNotNull(f.editor.prepareColdNativePresentation(value) {true})
            try {
                withContext(Dispatchers.IO) {secondBody.writeText("changed after preparation")}
                assertFalse(prepared.verify())
                assertFalse(f.editor.noteOpen);assertFalse(second.noteOpen)
            } finally {prepared.close()}
        }
        assertArrayEquals(original,firstBody.readBytes())
        assertEquals("changed after preparation",secondBody.readText());assertEquals(0,f.writes.get())
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
