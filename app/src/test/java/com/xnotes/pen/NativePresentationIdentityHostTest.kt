package com.xnotes.pen

import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.format.CanvasCodec
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.platform.LegacySessionQueue
import com.xnotes.ui.Editor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Same document identity is insufficient to prove that the original physical owners survived. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class NativePresentationIdentityHostTest {
    @Test fun contentAndReadingChangesKeepTheSameActualOwnerButARegularReopenDoesNot()=runParkingScenario {f->
        openParkingPanes(f)
        withContext(Dispatchers.Main) {
            val ticket=f.editor.captureNativePresentationIdentity()
            f.addStroke(160.0,recordHistory=true);f.editor.state.scrollX=67.0
            assertTrue(f.editor.matchesNativePresentationIdentity(ticket))
            val old=f.editor.state.document;val id=old.identity.documentId
            f.editor.openAsync(f.sourceUri,"source.xnote")
            assertNotSame(old,f.editor.state.document);assertEquals(id,f.editor.state.document.identity.documentId)
            assertFalse(f.editor.matchesNativePresentationIdentity(ticket))
            assertTrue(f.editor.matchesNativePresentationIdentity(f.editor.captureNativePresentationIdentity()))
        }
    }

    @Test fun directCanvasReplacementWithTheSameUuidRetiresTheOldPureIdentity()=runParkingScenario {f->
        withContext(Dispatchers.IO) {
            f.target.outputStream().use {CanvasCodec(AndroidImageCodec(),AndroidTextMeasurer()).write(InfiniteDocument(),it)}
        }
        withContext(Dispatchers.Main) {
            assertTrue(f.editor.openCanvasAsync(f.targetUri,"Canvas.xcanvas"))
            val model=f.editor.infinite.document;val ticket=f.editor.captureNativePresentationIdentity()
            val replacement=InfiniteDocument().apply {identity=model.identity;path=model.path}
            f.editor.infinite.replaceDocument(replacement)
            assertEquals(model.identity.documentId,replacement.identity.documentId)
            assertFalse(f.editor.matchesNativePresentationIdentity(ticket))
            assertTrue(f.editor.matchesNativePresentationIdentity(f.editor.captureNativePresentationIdentity()))
        }
    }

    @Test fun aSharedViewClosingAndRejoiningCannotReanimateItsOldOwnerBindingTicket()=runParkingScenario {f->
        withContext(Dispatchers.IO) {f.seed()}
        withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri,"source.xnote")
            val peer=f.editor.secondaryPane();assertTrue(peer.openSharedView(f.editor))
            val model=f.editor.state.document;val ticket=f.editor.captureNativePresentationIdentity()
            assertTrue(f.editor.matchesNativePresentationIdentity(ticket))
            assertNotNull(f.editor.detachMixedPane(peer))
            assertFalse(f.editor.matchesNativePresentationIdentity(ticket))
            // Slot retirement is synchronous; the accepted recovery clear must finish before a new adoption.
            (Editor::class.java.getDeclaredField("sessionWriteJob").apply {isAccessible=true}.get(peer) as? Job)?.join()
            (Editor::class.java.getDeclaredField("sessionOwner").apply {isAccessible=true}.get(peer) as LegacySessionQueue.Owner).awaitAccepted()
            assertTrue(peer.openSharedView(f.editor));assertSame(model,peer.state.document)
            assertFalse(f.editor.matchesNativePresentationIdentity(ticket))
        }
    }

    @Test fun aNewEditorForTheSameFileMatchesItsRestoreHintButNeverTheOldRuntimeOwnerTicket()=runParkingScenario {f->
        withContext(Dispatchers.IO) {f.seed()}
        withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri,"source.xnote")
            val old=f.editor;val ticket=old.captureNativePresentationIdentity();val hint=requireNotNull(old.nativeRestoreHint())
            (Editor::class.java.getDeclaredField("autosaveScope").apply {isAccessible=true}.get(old) as CoroutineScope).cancel()
            f.editor=Editor(f.context);f.editor.openAsync(f.sourceUri,"source.xnote")
            assertTrue(f.editor.matchesNativeRestoreHint(hint));assertFalse(f.editor.matchesNativePresentationIdentity(ticket))
            assertFalse(f.editor.matchesNativeRestoreHint(hint.copy(path=f.targetUri)))
            assertFalse(f.editor.matchesNativeRestoreHint(hint.copy(documentId=java.util.UUID.randomUUID().toString())))
        }
    }
}
