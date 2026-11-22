package com.xnotes.pen

import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import com.xnotes.core.infinite.AddCanvasItem
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.enableReferenceAnchors
import com.xnotes.core.model.fixedWriteSnapshot
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.format.CanvasCodec
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.platform.LegacySessionQueue
import com.xnotes.requiresNativeClosePrompt
import com.xnotes.storage.NotebookStorage
import com.xnotes.ui.*
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Save simplification (docs/movinkpad/save-slim-2026-11-22.md).
 *
 * Only a file save encodes a document. The recovery working copy is either reused (same model and
 * revision), copied from the verified clean file, deferred to a pending bound file save, or — only
 * when none of those apply — encoded. A checkpoint overtaken by newer state retries silently; only
 * an actual write failure reports "Could not update the recovery session".
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class SaveSlimHostTest {
    private fun field(editor: Editor, name: String): Any? =
        Editor::class.java.getDeclaredField(name).apply { isAccessible = true }.get(editor)
    private fun call(editor: Editor, name: String) {
        Editor::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(editor)
    }
    private fun slotDir(editor: Editor) = field(editor, "sessionDir") as File
    private fun sessionOwner(editor: Editor) = field(editor, "sessionOwner") as LegacySessionQueue.Owner
    private fun canvasCodec() = CanvasCodec(AndroidImageCodec(), AndroidTextMeasurer())
    private fun stats(editor: Editor) = editor.saveStats
    private fun recoveryError(f: EditorPageCopyFixture) = f.context.getString(com.xnotes.R.string.err_save_recovery_session)

    private fun runScenario(workspace: Boolean = true, block: suspend (EditorPageCopyFixture) -> Unit) {
        initializeParkingEglHandles()
        val base = RuntimeEnvironment.getApplication()
        val root = File(base.cacheDir, "save-slim-${UUID.randomUUID()}").apply { mkdirs() }
        val f = EditorPageCopyFixture(base, root, namespaced = true)
        val treeUri = DocumentsContract.buildTreeDocumentUri(f.authority, "root").toString()
        val singleton = NotebookStorage::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = singleton.get(null)
        // The browse root lives in process-wide LiveSettings; isolate it so an earlier workspace
        // scenario cannot leave this one bound. Null forces a reload from this fixture's own files.
        val live = com.xnotes.settings.LiveSettings::class.java.getDeclaredField("value").apply { isAccessible = true }
        val previousSettings = live.get(com.xnotes.settings.LiveSettings)
        live.set(com.xnotes.settings.LiveSettings, null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var failure: Throwable? = null
        var initialized = false
        val job = scope.launch {
            try {
                withContext(Dispatchers.Main) {
                    singleton.set(null, f.storage)
                    f.editor = Editor(f.context)
                    if (workspace) f.editor.updateBrowseRoot(treeUri)
                    initialized = true
                }
                withTimeout(20000L) { block(f) }
            } catch (error: Throwable) {
                failure = error
            } finally {
                withContext(NonCancellable) {
                    f.release.countDown()
                    if (initialized) {
                        val panes = withContext(Dispatchers.Main) { listOfNotNull(f.editor, f.editor.secondary) }
                        withContext(Dispatchers.Main) {
                            for (pane in panes) {
                                pane.controller.resetGestureState()
                                val auto = field(pane, "autosaveScope") as CoroutineScope
                                auto.cancel(); auto.coroutineContext[Job]?.join()
                            }
                            suspendCancellableCoroutine<Unit> { waiter ->
                                Handler(Looper.getMainLooper()).post { waiter.resumeWith(Result.success(Unit)) }
                            }
                        }
                        for (pane in panes) sessionOwner(pane).awaitAccepted()
                        SplitRecentWriter.forFilesDir(f.context.filesDir).awaitAccepted()
                    }
                    singleton.set(null, previous)
                    live.set(com.xnotes.settings.LiveSettings, previousSettings)
                    withContext(Dispatchers.IO) { f.storage.registry.close() }
                }
            }
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30L)
            while (!job.isCompleted && System.nanoTime() < deadline) {
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                shadowOf(Looper.getMainLooper()).idle()
                Thread.yield()
            }
            assertTrue("Save-slim scenario did not settle", job.isCompleted)
            failure?.let { throw it }
        } finally {
            f.release.countDown()
            scope.cancel()
            if (job.isCompleted) root.deleteRecursively()
        }
    }

    private suspend fun restartEditor(f: EditorPageCopyFixture) {
        val panes = withContext(Dispatchers.Main) { listOfNotNull(f.editor, f.editor.secondary) }
        withContext(Dispatchers.Main) {
            for (pane in panes) {
                pane.controller.resetGestureState()
                val auto = field(pane, "autosaveScope") as CoroutineScope
                auto.cancel(); auto.coroutineContext[Job]?.join()
            }
        }
        for (pane in panes) sessionOwner(pane).awaitAccepted()
        withContext(Dispatchers.Main) { f.editor = Editor(f.context) }
    }

    /** One coordinator checkpoint, as the observer and Activity.onPause() run it. */
    private suspend fun checkpoint(editor: Editor): Boolean {
        val coordinator = ColdPresentationCoordinator(editor, ColdPresentationBootstrap())
        try {
            coordinator.retireIntent()
            return coordinator.checkpoint()
        } finally {
            coordinator.close()
        }
    }

    private fun marker(f: EditorPageCopyFixture): LastPresentation.Native {
        val read = LastPresentationStore(f.context.filesDir).observe().read as LastPresentationRead.Ready
        return read.value as LastPresentation.Native
    }

    private suspend fun seedCanvas(f: EditorPageCopyFixture) {
        f.sourceDisplayName = "sketch.xcanvas"
        withContext(Dispatchers.IO) { f.source.outputStream().use { canvasCodec().write(InfiniteDocument(), it) } }
    }

    private fun addCanvasStroke(editor: Editor, x: Double): Stroke {
        val doc = editor.infinite.document
        val stroke = Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), listOf(Sample(x, 110.0, .5), Sample(x + 30, 150.0, .8)))
        doc.add(stroke); editor.infinite.history.push(AddCanvasItem(doc, stroke)); doc.dirty = true
        return stroke
    }
    private fun canvasStrokes(editor: Editor) = editor.infinite.document.items.filterIsInstance<Stroke>().size
    private fun noteStrokes(editor: Editor) = editor.state.document.pages.first().items.filterIsInstance<Stroke>().size

    /** What a canvas edit does: arm the 1.2 s file-save debounce. */
    private fun armCanvasSave(editor: Editor) = call(editor, "scheduleCanvasAutosave")
    private fun armNoteSave(editor: Editor) = call(editor, "scheduleAutosave")
    private fun stopCanvasDebounce(editor: Editor) { (field(editor, "canvasDebounceJob") as? Job)?.cancel() }

    // --- exit: the file save is the only encode ---

    @Test fun pausingADirtyWorkspaceCanvasEncodesOnceAndCopiesTheSavedFileIntoTheBackup() = runScenario { f ->
        seedCanvas(f)
        withContext(Dispatchers.Main) {
            assertTrue(f.editor.openCanvasAsync(f.sourceUri, f.sourceDisplayName))
            assertEquals(f.sourceUri, f.editor.canvasAutosaveUri)
            addCanvasStroke(f.editor, 120.0); armCanvasSave(f.editor)
            // Activity.onPause(): flush bound saves, await them, then checkpoint.
            f.editor.flushAutosaveAll()
            f.editor.awaitAutosaveWrites()
            assertFalse(f.editor.infinite.document.dirty)
            assertTrue(checkpoint(f.editor))
            assertNull(f.editor.message)
        }
        assertEquals(1, f.writes.get())
        assertEquals("Only the file save encodes", 1, stats(f.editor).fileEncodes.get())
        assertEquals("The backup must not be re-encoded", 0, stats(f.editor).workingEncodes.get())
        assertEquals(1, stats(f.editor).workingFromSource.get())
        assertArrayEquals("The kept backup is the saved file, byte for byte",
            f.source.readBytes(), File(slotDir(f.editor), "canvas.xcanvas").readBytes())
        assertTrue((marker(f).slots.single() as ColdNativeSlot.Canvas).reopenFromFile)

        restartEditor(f)
        withContext(Dispatchers.Main) {
            val coordinator = ColdPresentationCoordinator(f.editor, ColdPresentationBootstrap())
            try {
                assertTrue(coordinator.bootstrap())
                assertTrue(f.editor.canvasOpen)
                assertFalse(f.editor.requiresIndependentSave)
                assertFalse(requiresNativeClosePrompt(f.editor))
                assertEquals(1, canvasStrokes(f.editor))
            } finally { coordinator.close() }
        }
    }

    @Test fun pausingADirtyWorkspaceNoteEncodesOnceAndCopiesTheSavedFileIntoTheBackup() = runScenario { f ->
        withContext(Dispatchers.IO) { f.seed() }
        withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri, f.sourceDisplayName)
            assertEquals(f.sourceUri, f.editor.autosaveUri)
            f.editor.view.layout(0, 0, 1000, 800)
            f.addStroke(140.0, recordHistory = true); armNoteSave(f.editor)
            f.editor.flushAutosaveAll()
            f.editor.awaitAutosaveWrites()
            assertFalse(f.editor.state.document.dirty)
            assertTrue(checkpoint(f.editor))
            assertNull(f.editor.message)
        }
        assertEquals(1, f.writes.get())
        assertEquals(1, stats(f.editor).fileEncodes.get())
        assertEquals(0, stats(f.editor).workingEncodes.get())
        assertEquals(1, stats(f.editor).workingFromSource.get())
        assertArrayEquals(f.source.readBytes(), File(slotDir(f.editor), "document.xnote").readBytes())
        assertTrue((marker(f).slots.single() as ColdNativeSlot.Note).reopenFromFile)

        restartEditor(f)
        withContext(Dispatchers.Main) {
            val coordinator = ColdPresentationCoordinator(f.editor, ColdPresentationBootstrap())
            try {
                assertTrue(coordinator.bootstrap())
                assertFalse(f.editor.requiresIndependentSave)
                assertEquals(1, noteStrokes(f.editor))
            } finally { coordinator.close() }
        }
    }

    @Test fun closingACanvasToHomeEncodesOnlyForTheFileSave() = runScenario { f ->
        seedCanvas(f)
        withContext(Dispatchers.Main) {
            assertTrue(f.editor.openCanvasAsync(f.sourceUri, f.sourceDisplayName))
            addCanvasStroke(f.editor, 80.0); armCanvasSave(f.editor)
            // The detach path: settle the file, then the departure checkpoint.
            assertTrue(f.editor.flushSplitDocument())
            val result = f.editor.prepareNativePresentationCheckpointResult(emptySet()) { true }
            assertTrue("Departure checkpoint must be ready: $result", result is NativeCheckpointResult.Ready)
            assertTrue(((result as NativeCheckpointResult.Ready).checkpoint.value.slots.single() as ColdNativeSlot.Canvas).reopenFromFile)
        }
        assertEquals(1, stats(f.editor).fileEncodes.get())
        assertEquals(0, stats(f.editor).workingEncodes.get())
        assertEquals(1, stats(f.editor).workingFromSource.get())
    }

    @Test fun aFreshlyOpenedCleanCanvasBacksUpByCopyNotByEncoding() = runScenario { f ->
        seedCanvas(f)
        withContext(Dispatchers.Main) {
            assertTrue(f.editor.openCanvasAsync(f.sourceUri, f.sourceDisplayName))
            assertTrue(checkpoint(f.editor))
        }
        assertEquals(0, f.writes.get())
        assertEquals(0, stats(f.editor).fileEncodes.get())
        assertEquals(0, stats(f.editor).workingEncodes.get())
        assertEquals(1, stats(f.editor).workingFromSource.get())
        assertArrayEquals(f.source.readBytes(), File(slotDir(f.editor), "canvas.xcanvas").readBytes())
        // A second checkpoint of the same revision reuses the body: metadata only.
        withContext(Dispatchers.Main) { assertTrue(checkpoint(f.editor)) }
        assertEquals(1, stats(f.editor).workingFromSource.get())
        assertEquals(0, stats(f.editor).workingEncodes.get())
    }

    @Test fun aPdfNoteBackupIsStillEncodedBecauseItsFileReferencesTheSharedPdf() = runScenario { f ->
        val input = File(f.root, "original.pdf").apply { writeBytes(minimalPdf()) }
        withContext(Dispatchers.IO) {
            val portable = Document(mutableListOf(Page(500.0, 500.0)), pdfFile = input).apply { enableReferenceAnchors() }
            f.source.outputStream().use { f.codec.write(portable.fixedWriteSnapshot(), it, embedPdf = true) }
        }
        withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri, f.sourceDisplayName)
            assertTrue(f.editor.noteOpen)
            assertNotNull(f.editor.state.document.pdfFile)
            f.editor.view.layout(0, 0, 1000, 800)
            f.addStroke(60.0, recordHistory = true); armNoteSave(f.editor)
            f.editor.flushAutosaveAll()
            f.editor.awaitAutosaveWrites()
            assertFalse("The PDF note's file save must land: ${f.editor.message}", f.editor.state.document.dirty)
            assertTrue(checkpoint(f.editor))
        }
        assertEquals(0, stats(f.editor).workingFromSource.get())
        assertEquals(1, stats(f.editor).workingEncodes.get())
        assertTrue(File(slotDir(f.editor), "document.xnote").isFile)
    }

    // --- while writing: defer to the pending file save ---

    @Test fun aDirtyBoundCanvasDefersItsCheckpointToThePendingFileSaveWithoutAnError() = runScenario { f ->
        seedCanvas(f)
        withContext(Dispatchers.Main) {
            assertTrue(f.editor.openCanvasAsync(f.sourceUri, f.sourceDisplayName))
            addCanvasStroke(f.editor, 100.0); armCanvasSave(f.editor)
            assertEquals(NativeCheckpointResult.Deferred, f.editor.prepareNativePresentationCheckpointResult(emptySet()) { true })
            assertFalse(checkpoint(f.editor))
            assertNull("Deferring is not a failure", f.editor.message)
            assertEquals(0, stats(f.editor).workingEncodes.get())
            assertTrue(stats(f.editor).checkpointDeferred.get() >= 1)
            assertEquals(0, stats(f.editor).checkpointFailed.get())

            val before = f.editor.presentationVersion
            f.editor.flushAutosaveAll()
            f.editor.awaitAutosaveWrites()
            assertTrue("The finished save must wake the presentation observer", f.editor.presentationVersion > before)
            val ready = f.editor.prepareNativePresentationCheckpointResult(emptySet()) { true }
            assertTrue("$ready", ready is NativeCheckpointResult.Ready)
            assertTrue(((ready as NativeCheckpointResult.Ready).checkpoint.value.slots.single() as ColdNativeSlot.Canvas).reopenFromFile)
        }
        assertEquals(1, stats(f.editor).fileEncodes.get())
        assertEquals(0, stats(f.editor).workingEncodes.get())
        assertEquals(1, stats(f.editor).workingFromSource.get())
    }

    @Test fun aDirtyBoundNoteDefersToo() = runScenario { f ->
        withContext(Dispatchers.IO) { f.seed() }
        withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri, f.sourceDisplayName)
            f.editor.view.layout(0, 0, 1000, 800)
            f.addStroke(150.0, recordHistory = true); armNoteSave(f.editor)
            assertEquals(NativeCheckpointResult.Deferred, f.editor.prepareNativePresentationCheckpointResult(emptySet()) { true })
            assertNull(f.editor.message)
        }
        assertEquals(0, stats(f.editor).workingEncodes.get())
    }

    @Test fun aFailedFileSaveStopsDeferringAndTheBackupIsEncodedAndProtected() = runScenario { f ->
        seedCanvas(f)
        withContext(Dispatchers.Main) {
            assertTrue(f.editor.openCanvasAsync(f.sourceUri, f.sourceDisplayName))
            f.failSourceWrites = true
            addCanvasStroke(f.editor, 90.0); armCanvasSave(f.editor)
            val before = f.editor.presentationVersion
            f.editor.flushAutosaveAll()
            f.editor.awaitAutosaveWrites()
            assertTrue("The save did not land", f.editor.infinite.document.dirty)
            assertTrue("A failed save must also wake the observer", f.editor.presentationVersion > before)
            val result = f.editor.prepareNativePresentationCheckpointResult(emptySet()) { true }
            assertTrue("After a failed save the backup must not keep waiting: $result", result is NativeCheckpointResult.Ready)
            assertFalse(((result as NativeCheckpointResult.Ready).checkpoint.value.slots.single() as ColdNativeSlot.Canvas).reopenFromFile)
            assertTrue(checkpoint(f.editor))
        }
        assertEquals(1, stats(f.editor).workingEncodes.get())
        assertEquals(0, stats(f.editor).workingFromSource.get())

        f.failSourceWrites = false
        restartEditor(f)
        withContext(Dispatchers.Main) {
            val coordinator = ColdPresentationCoordinator(f.editor, ColdPresentationBootstrap())
            try {
                assertTrue(coordinator.bootstrap())
                assertTrue(f.editor.canvasOpen)
                assertTrue("An unsaved edit restores as a protected working copy", f.editor.requiresIndependentSave)
                assertEquals(1, canvasStrokes(f.editor))
            } finally { coordinator.close() }
        }
    }

    @Test fun anUnboundCanvasHasNoFileSaveToWaitForAndEncodesItsBackup() = runScenario(workspace = false) { f ->
        seedCanvas(f)
        withContext(Dispatchers.Main) {
            assertTrue(f.editor.openCanvasAsync(f.sourceUri, f.sourceDisplayName))
            assertNull(f.editor.canvasAutosaveUri)
            addCanvasStroke(f.editor, 70.0); armCanvasSave(f.editor)
            val result = f.editor.prepareNativePresentationCheckpointResult(emptySet()) { true }
            assertTrue("$result", result is NativeCheckpointResult.Ready)
        }
        assertEquals(0, f.writes.get())
        assertEquals(1, stats(f.editor).workingEncodes.get())
        assertEquals(0, stats(f.editor).checkpointDeferred.get())
    }

    // --- errors: only real write failures are reported ---

    @Test fun aCheckpointOvertakenByANewStrokeRetriesSilently() = runScenario { f ->
        seedCanvas(f)
        withContext(Dispatchers.Main) {
            assertTrue(f.editor.openCanvasAsync(f.sourceUri, f.sourceDisplayName))
            addCanvasStroke(f.editor, 110.0); stopCanvasDebounce(f.editor)
            // A stroke lands while the backup is being written (no presentation bump, as on device).
            f.editor.checkpointBeforeWriteHook = {
                f.editor.checkpointBeforeWriteHook = null
                addCanvasStroke(f.editor, 210.0)
            }
            assertFalse(checkpoint(f.editor))
            assertNull("Being overtaken is not a failure", f.editor.message)
            assertEquals(1, stats(f.editor).checkpointSuperseded.get())
            assertEquals(0, stats(f.editor).checkpointFailed.get())
            stopCanvasDebounce(f.editor)
            assertTrue("The retry publishes the newer state", checkpoint(f.editor))
            assertNull(f.editor.message)
        }
        restartEditor(f)
        withContext(Dispatchers.Main) {
            val coordinator = ColdPresentationCoordinator(f.editor, ColdPresentationBootstrap())
            try {
                assertTrue(coordinator.bootstrap())
                assertEquals(2, canvasStrokes(f.editor))
            } finally { coordinator.close() }
        }
    }

    @Test fun aCleanSourceThatChangesBeforeTheCopyFallsBackToEncodingWithoutAnError() = runScenario { f ->
        seedCanvas(f)
        withContext(Dispatchers.Main) {
            assertTrue(f.editor.openCanvasAsync(f.sourceUri, f.sourceDisplayName))
            f.editor.checkpointBeforeWriteHook = {
                f.editor.checkpointBeforeWriteHook = null
                withContext(Dispatchers.IO) {
                    val other = InfiniteDocument()
                    other.add(Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), listOf(Sample(1.0, 2.0, .5), Sample(3.0, 4.0, .5))))
                    f.source.outputStream().use { canvasCodec().write(other, it) }
                }
            }
            val first = checkpoint(f.editor)
            assertNull("A changed clean source is not a write failure", f.editor.message)
            if (!first) assertTrue("The retry must succeed", checkpoint(f.editor))
            assertNull(f.editor.message)
        }
        assertEquals(0, stats(f.editor).workingFromSource.get())
        assertEquals(1, stats(f.editor).workingEncodes.get())
        assertEquals(0, stats(f.editor).checkpointFailed.get())
        val backup = File(slotDir(f.editor), "canvas.xcanvas")
        val restored = withContext(Dispatchers.IO) { backup.inputStream().use { canvasCodec().read(it, File(f.root, "img").apply { mkdirs() }) } }
        assertEquals("The backup holds the open model, not the externally changed file", 0, restored.items.size)
    }

    @Test fun anActualWorkingCopyWriteFailureIsStillReported() = runScenario { f ->
        seedCanvas(f)
        withContext(Dispatchers.Main) {
            assertTrue(f.editor.openCanvasAsync(f.sourceUri, f.sourceDisplayName))
            addCanvasStroke(f.editor, 130.0); stopCanvasDebounce(f.editor)
            sessionOwner(f.editor).awaitAccepted()
            assertTrue(File(slotDir(f.editor), "canvas.xcanvas").mkdirs())
            assertFalse(checkpoint(f.editor))
            assertEquals(recoveryError(f), f.editor.message)
        }
        assertEquals(1, stats(f.editor).checkpointFailed.get())
        assertEquals(0, stats(f.editor).checkpointSuperseded.get())
    }

    // --- pacing ---

    @Test fun onlyContentChangesAdvanceTheContentVersionThatSelectsTheLongSettle() = runScenario { f ->
        seedCanvas(f)
        withContext(Dispatchers.Main) {
            assertTrue(f.editor.openCanvasAsync(f.sourceUri, f.sourceDisplayName))
            val content = f.editor.presentationContentVersion
            val presentation = f.editor.presentationVersion
            f.editor.infinite.viewport.panByContent(40.0, 25.0)
            call(f.editor, "presentationChanged")
            assertTrue("A pan is a presentation change", f.editor.presentationVersion > presentation)
            assertEquals("A pan is not a content change", content, f.editor.presentationContentVersion)
            addCanvasStroke(f.editor, 50.0)
            call(f.editor, "presentationChanged")
            assertTrue("A stroke is a content change", f.editor.presentationContentVersion > content)
        }
    }

    private fun minimalPdf(): ByteArray {
        val out = java.io.ByteArrayOutputStream(); fun ascii(value: String) { out.write(value.toByteArray(Charsets.US_ASCII)) }
        ascii("%PDF-1.4\n"); val objects = listOf("<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Resources << >> /Contents 4 0 R >>", "<< /Length 4 >>\nstream\nq\nQ\nendstream")
        val offsets = IntArray(objects.size + 1)
        objects.forEachIndexed { at, body -> offsets[at + 1] = out.size(); ascii("${at + 1} 0 obj\n$body\nendobj\n") }
        val xref = out.size(); ascii("xref\n0 ${offsets.size}\n0000000000 65535 f \n")
        for (at in 1 until offsets.size) ascii(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", offsets[at]))
        ascii("trailer\n<< /Size ${offsets.size} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n"); return out.toByteArray()
    }
}
