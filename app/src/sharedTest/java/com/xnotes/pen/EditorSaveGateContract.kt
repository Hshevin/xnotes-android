package com.xnotes.pen

import android.view.InputDevice
import android.view.MotionEvent
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.fixedWriteSnapshot
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.text.FlowTable
import com.xnotes.core.text.Paragraph
import com.xnotes.core.tools.Tool
import com.xnotes.ui.Editor
import com.xnotes.canvas.ViewOverrides
import com.xnotes.format.DocumentCodec
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.platform.LegacySessionWrite
import com.xnotes.platform.SessionStore
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

/** Actual Editor save preparation; the host executes its real Main dispatcher without replacing it. */
abstract class EditorSaveGateContract {
    protected abstract fun runEditorScenario(block: suspend (Editor) -> Unit)
    private var time = 10L

    private fun ready(editor: Editor) {
        editor.newNote()
        editor.view.layout(0, 0, 1000, 1000)
    }

    private fun at(editor: Editor, point: Pt) =
        editor.state.contentToViewport(editor.state.fromPageSpace(0, point))

    private suspend fun letMainCaptureCheckRun() {
        delay(1L)
        // The posted boundary resumes through Main too. Yield once more so that continuation
        // reaches its busy check before this test can send the completing input event.
        yield()
    }

    private fun send(editor: Editor, action: Int, at: Pt) {
        val pointer = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }
        val coords = MotionEvent.PointerCoords().apply { x = at.x.toFloat(); y = at.y.toFloat(); pressure = .6f }
        val event = MotionEvent.obtain(1L, time++, action, 1, arrayOf(pointer), arrayOf(coords), 0, 0,
            1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
        try { assertTrue(editor.view.onTouchEvent(event)) } finally { event.recycle() }
    }

    private fun sessionStore(editor: Editor, second: Boolean = false): SessionStore {
        val files = editor.view.context.applicationContext.filesDir
        return SessionStore(File(files, if (second) "session-b" else "session"),
            DocumentCodec(AndroidImageCodec(), AndroidTextMeasurer()), File(files, "pdfsrc"), File(files, "noteimg"))
    }

    private suspend fun seedRecovery(editor: Editor, second: Boolean = false, dirty: Boolean = false): Pair<ByteArray, ByteArray> = withContext(Dispatchers.IO) {
        val seed = Document(mutableListOf(Page(500.0, 500.0))).apply {
            displayName = "previous recovery"
            this.dirty = dirty
        }
        val result = sessionStore(editor, second).saveChecked(seed.fixedWriteSnapshot(),
            SessionStore.Metadata(null, seed.displayName, dirty, 1.0, 0.0, 0.0, false, ViewOverrides()))
        assertEquals(LegacySessionWrite.SAVED, result)
        val directory = File(editor.view.context.applicationContext.filesDir, if (second) "session-b" else "session")
        File(directory, "document.xnote").readBytes() to File(directory, "session.json").readBytes()
    }

    private fun assertRecoveryUnchanged(editor: Editor, bytes: Pair<ByteArray, ByteArray>, second: Boolean = false) {
        val directory = File(editor.view.context.applicationContext.filesDir, if (second) "session-b" else "session")
        assertArrayEquals(bytes.first, File(directory, "document.xnote").readBytes())
        assertArrayEquals(bytes.second, File(directory, "session.json").readBytes())
    }

    private suspend fun savedCurrentSession(editor: Editor): SessionStore.Snapshot = withTimeout(5000L) {
        val id = editor.state.document.identity.documentId
        val name = editor.state.document.displayName
        var found: SessionStore.Snapshot? = null
        while (found == null) {
            val loaded = withContext(Dispatchers.IO) { sessionStore(editor).load() }
            if (loaded?.document?.identity?.documentId == id && loaded.document.displayName == name) found = loaded
            else delay(5L)
        }
        found
    }

    @Test fun savePreparationWaitsForThePenToCancelWithoutCancellingItItself() = runEditorScenario { editor ->
        ready(editor)
        editor.selectTool(Tool.PEN)
        val from = at(editor, Pt(100.0, 100.0))
        send(editor, MotionEvent.ACTION_DOWN, from)
        assertTrue(editor.stylusInContact)
        coroutineScope {
            val saving = async(start = CoroutineStart.UNDISPATCHED) { editor.awaitStableForSave() }
            letMainCaptureCheckRun()
            assertFalse(saving.isCompleted)
            assertTrue(editor.stylusInContact)
            assertTrue(editor.controller.hasPendingContentTransaction)
            send(editor, MotionEvent.ACTION_CANCEL, from)
            assertTrue(withTimeout(5000L) { saving.await() })
        }
        assertFalse(editor.stylusInContact)
        assertFalse(editor.controller.hasPendingContentTransaction)
    }

    @Test fun savePreparationWaitsForNormalPenUpAndKeepsItsCommittedStroke() = runEditorScenario { editor ->
        ready(editor)
        editor.selectTool(Tool.PEN)
        val from = at(editor, Pt(100.0, 100.0))
        send(editor, MotionEvent.ACTION_DOWN, from)
        send(editor, MotionEvent.ACTION_MOVE, from + Pt(80.0, 30.0))
        coroutineScope {
            val saving = async(start = CoroutineStart.UNDISPATCHED) { editor.awaitStableForSave() }
            letMainCaptureCheckRun()
            assertFalse(saving.isCompleted)
            send(editor, MotionEvent.ACTION_UP, from + Pt(120.0, 50.0))
            assertTrue(withTimeout(5000L) { saving.await() })
        }
        assertTrue(editor.state.document.pages.single().items.single() is Stroke)
        assertTrue(editor.history.canUndo)
    }

    @Test fun savePreparationWaitsForTheActualFillPreviewToFinish() = runEditorScenario { editor ->
        ready(editor)
        val original = Rgba(64, 64, 64, 255)
        val shown = Rgba(128, 128, 128, 255)
        val shape = ShapeItem(ShapeKind.RECTANGLE, Pt(100.0, 100.0), Pt(260.0, 240.0),
            Rgba(0, 0, 0, 255), fillRgba = original)
        editor.state.document.pages.single().items += shape
        editor.controller.selectPlaced(0, listOf(shape))
        editor.controller.refillSelection(shown, preview = true)
        coroutineScope {
            val saving = async(start = CoroutineStart.UNDISPATCHED) { editor.awaitStableForSave() }
            letMainCaptureCheckRun()
            assertFalse(saving.isCompleted)
            assertEquals(shown, shape.fillRgba)
            editor.controller.clearSelection()
            assertTrue(withTimeout(5000L) { saving.await() })
        }
        assertEquals(shown, shape.fillRgba)
        editor.history.undo()
        assertEquals(original, shape.fillRgba)
    }

    @Test fun tablePreviewCancellationAndNoOpCompletionReleaseSavePreparation() = runEditorScenario { editor ->
        ready(editor)
        val table = FlowTable(listOf(1.0))
        editor.state.document.flow.paragraphs += Paragraph(table = table, cellStart = true)
        val token = requireNotNull(editor.beginTablePreview(table))
        editor.tablePreview(token, token.before.copy(width = .8))
        assertEquals(.8, table.width, 0.0)
        coroutineScope {
            val saving = async(start = CoroutineStart.UNDISPATCHED) { editor.awaitStableForSave() }
            letMainCaptureCheckRun()
            assertFalse(saving.isCompleted)
            editor.finishTablePreview(token, commit = false)
            assertTrue(withTimeout(5000L) { saving.await() })
        }
        assertEquals(token.before, table.snapshot())
        val noOp = requireNotNull(editor.beginTablePreview(table))
        editor.finishTablePreview(noOp, commit = true)
        assertTrue(withTimeout(5000L) { editor.awaitStableForSave() })
    }

    @Test fun retiredTableTokenCannotMutateTheReplacementAndItsSaveRequestReturnsFalse() = runEditorScenario { editor ->
        ready(editor)
        val old = FlowTable(listOf(1.0))
        editor.state.document.flow.paragraphs += Paragraph(table = old, cellStart = true)
        val oldToken = requireNotNull(editor.beginTablePreview(old))
        editor.tablePreview(oldToken, oldToken.before.copy(width = .8))
        val oldShown = old.snapshot()
        val fresh = FlowTable(listOf(1.0), width = .9)
        val next = Document(mutableListOf(Page(500.0, 500.0))).apply {
            flow.paragraphs += Paragraph(table = fresh, cellStart = true)
        }
        val freshBefore = fresh.snapshot()
        coroutineScope {
            val saving = async(start = CoroutineStart.UNDISPATCHED) { editor.awaitStableForSave() }
            letMainCaptureCheckRun()
            assertFalse(saving.isCompleted)
            editor.state.document = next
            editor.state.relayout()
            editor.tablePreview(oldToken, oldToken.before.copy(width = .6))
            editor.finishTablePreview(oldToken, commit = true)
            assertFalse(withTimeout(5000L) { saving.await() })
        }
        assertEquals(freshBefore, fresh.snapshot())
        assertEquals(oldShown, old.snapshot())
        assertFalse(next.dirty)
        assertFalse(editor.history.canUndo)
        assertTrue(withTimeout(5000L) { editor.awaitStableForSave() })
    }

    @Test fun persistKeepsThePreviousRecoveryWhileThePenIsDownThenStoresNormalPenUp() = persistWithPen(cancel = false)

    @Test fun persistKeepsThePreviousRecoveryWhileThePenIsDownThenStoresCancellation() = persistWithPen(cancel = true)

    private fun persistWithPen(cancel: Boolean) = runEditorScenario { editor ->
        ready(editor)
        editor.state.document.displayName = "current pen working session"
        val oldBytes = seedRecovery(editor)
        editor.selectTool(Tool.PEN)
        val from = at(editor, Pt(100.0, 100.0))
        send(editor, MotionEvent.ACTION_DOWN, from)
        send(editor, MotionEvent.ACTION_MOVE, from + Pt(80.0, 30.0))
        editor.persist()
        letMainCaptureCheckRun()
        assertTrue(editor.controller.hasPendingContentTransaction)
        assertRecoveryUnchanged(editor, oldBytes)
        send(editor, if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, from + Pt(120.0, 50.0))
        val saved = savedCurrentSession(editor)
        val items = saved.document.pages.first().items
        if (cancel) assertTrue(items.isEmpty()) else assertTrue(items.single() is Stroke)
        assertEquals(editor.state.document.identity.documentId, saved.document.identity.documentId)
    }

    @Test fun persistKeepsThePreviousRecoveryUntilTheFillPreviewIsConfirmed() = runEditorScenario { editor ->
        ready(editor)
        editor.state.document.displayName = "current fill working session"
        val oldBytes = seedRecovery(editor)
        val shown = Rgba(128, 128, 128, 255)
        val shape = ShapeItem(ShapeKind.RECTANGLE, Pt(100.0, 100.0), Pt(260.0, 240.0),
            Rgba(0, 0, 0, 255), fillRgba = Rgba(64, 64, 64, 255))
        editor.state.document.pages.first().items += shape
        editor.controller.selectPlaced(0, listOf(shape))
        editor.controller.refillSelection(shown, preview = true)
        editor.persist()
        letMainCaptureCheckRun()
        assertTrue(editor.controller.hasPendingContentTransaction)
        assertRecoveryUnchanged(editor, oldBytes)
        editor.controller.clearSelection()
        val saved = savedCurrentSession(editor)
        assertEquals(shown, (saved.document.pages.first().items.single() as ShapeItem).fillRgba)
        assertEquals(shown, shape.fillRgba)
    }

    @Test fun unadoptedSecondaryDoesNotClearOrReplaceThePreviousRecoverySlot() = runEditorScenario { editor ->
        ready(editor)
        val oldBytes = seedRecovery(editor, second = true)
        val second = editor.secondaryPane()
        assertFalse(second.noteOpen)
        second.persist()
        letMainCaptureCheckRun()
        assertRecoveryUnchanged(editor, oldBytes, second = true)
        assertFalse(second.noteOpen)
    }

    @Test fun undoSettlesTheTableWidthPreviewBeforeUndoingAndReleasingSave() = runEditorScenario { editor ->
        ready(editor)
        val table = FlowTable(listOf(1.0))
        editor.state.document.flow.paragraphs += Paragraph(table = table, cellStart = true)
        val original = table.snapshot()
        val token = requireNotNull(editor.beginTablePreview(table))
        editor.tablePreview(token, token.before.copy(width = .8))
        assertEquals(.8, table.width, 0.0)
        editor.undo()
        assertEquals(original, table.snapshot())
        assertFalse(editor.history.canUndo)
        assertTrue(editor.history.canRedo)
        assertTrue(withTimeout(5000L) { editor.awaitStableForSave() })
        editor.redo()
        assertEquals(.8, table.width, 0.0)
        assertTrue(withTimeout(5000L) { editor.awaitStableForSave() })
    }

    @Test fun closingAllSharedPanesClosesBothActualEditorsAfterMainSettles() = runEditorScenario { editor ->
        ready(editor)
        val second = editor.secondaryPane()
        assertTrue(second.openSharedView(editor))
        assertTrue(editor.noteOpen)
        assertTrue(second.noteOpen)
        assertSame(editor.state.document, second.state.document)
        editor.goHomeAll()
        withTimeout(5000L) {
            while (editor.noteOpen || second.noteOpen) delay(5L)
        }
        assertFalse(editor.noteOpen)
        assertFalse(second.noteOpen)
    }

    @Test fun explicitDiscardStartsANewNoteWithoutCarryingTheUnsavedBodyOrHistory() = runEditorScenario { editor ->
        ready(editor)
        editor.selectTool(Tool.PEN)
        val from = at(editor, Pt(100.0, 100.0))
        send(editor, MotionEvent.ACTION_DOWN, from)
        send(editor, MotionEvent.ACTION_MOVE, from + Pt(80.0, 30.0))
        send(editor, MotionEvent.ACTION_UP, from + Pt(120.0, 50.0))
        val old = editor.state.document
        assertTrue(old.dirty)
        assertNull(old.path)
        assertTrue(editor.history.canUndo)
        val proof = editor.discardForOpen()
        editor.newNote(setOf(proof))
        withTimeout(5000L) { while (editor.state.document === old) delay(5L) }
        assertTrue(editor.noteOpen)
        assertTrue(editor.state.document.pages.all { it.items.isEmpty() })
        assertFalse(editor.history.canUndo)
        assertFalse(editor.state.document.dirty)
    }

    @Test fun explicitSaveDoesNotShowAModalWhileTheFillPreviewStillNeedsUserInput() = runEditorScenario { editor ->
        ready(editor)
        val shape = ShapeItem(ShapeKind.RECTANGLE, Pt(100.0, 100.0), Pt(260.0, 240.0),
            Rgba(0, 0, 0, 255), fillRgba = Rgba(64, 64, 64, 255))
        editor.state.document.pages.first().items += shape
        editor.controller.selectPlaced(0, listOf(shape))
        editor.controller.refillSelection(Rgba(128, 128, 128, 255), preview = true)
        var result: Boolean? = null
        editor.saveToThen("content://unused-save-gate/document/unavailable.xnote") { result = it }
        delay(180L)
        yield()
        assertNull(result)
        assertTrue(editor.controller.hasPendingContentTransaction)
        assertFalse(editor.savingNote)
        editor.controller.clearSelection()
        withTimeout(5000L) { while (result == null) delay(5L) }
        assertEquals(false, result)
        assertFalse(editor.savingNote)
        assertFalse(editor.controller.hasPendingContentTransaction)
    }

    @Test fun restoredDirtyNoteIsVisibleAndNewNoteCannotSilentlyReplaceIt() = runEditorScenario { editor ->
        assertFalse(editor.noteOpen)
        val blank = editor.state.document
        val oldBytes = seedRecovery(editor, dirty = true)
        val onDisk = withContext(Dispatchers.IO) { requireNotNull(sessionStore(editor).load()) }
        withTimeout(5000L) { editor.restoreSession() }
        val restored = editor.state.document
        assertTrue(editor.noteOpen)
        assertNotSame(blank, restored)
        assertEquals(onDisk.document.identity.documentId, restored.identity.documentId)
        assertEquals("previous recovery", restored.displayName)
        assertTrue(restored.dirty)
        editor.newNote()
        letMainCaptureCheckRun()
        assertSame(restored, editor.state.document)
        assertTrue(editor.noteOpen)
        assertTrue(restored.dirty)
        assertRecoveryUnchanged(editor, oldBytes)
    }

    @Test fun aNewEditorRetiresTheOldSaveWaitWithoutEndingItsContentPreview() = runEditorScenario { editor ->
        ready(editor)
        val shown = Rgba(128, 128, 128, 255)
        val shape = ShapeItem(ShapeKind.RECTANGLE, Pt(100.0, 100.0), Pt(260.0, 240.0),
            Rgba(0, 0, 0, 255), fillRgba = Rgba(64, 64, 64, 255))
        val old = editor.state.document
        old.pages.first().items += shape
        editor.controller.selectPlaced(0, listOf(shape))
        editor.controller.refillSelection(shown, preview = true)
        val revision = old.bodyRevision
        var replacement: Editor? = null
        try {
            coroutineScope {
                val saving = async(start = CoroutineStart.UNDISPATCHED) { editor.awaitStableForSave() }
                letMainCaptureCheckRun()
                assertFalse(saving.isCompleted)
                replacement = Editor(editor.view.context)
                assertFalse(withTimeout(5000L) { saving.await() })
            }
            assertSame(old, editor.state.document)
            assertSame(shape, old.pages.first().items.single())
            assertEquals(shown, shape.fillRgba)
            assertEquals(revision, old.bodyRevision)
            assertTrue(editor.controller.hasPendingContentTransaction)
            assertFalse(editor.history.canUndo)
        } finally {
            replacement?.let {
                (Editor::class.java.getDeclaredField("autosaveScope").apply { isAccessible = true }.get(it) as CoroutineScope).cancel()
            }
        }
    }
}
