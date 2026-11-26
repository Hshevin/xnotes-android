package com.xnotes.pen

import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import com.xnotes.canvas.InitialView
import com.xnotes.canvas.ViewOverrides
import com.xnotes.core.infinite.Waypoint
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.fixedWriteSnapshot
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.core.stroke.Sample
import com.xnotes.format.MapBundleFixture
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

/** Cold relaunch of clean workspace-backed notes/canvases reopens from disk without close prompts. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class CleanColdReopenHostTest {
    private fun field(editor: Editor, name: String) =
        Editor::class.java.getDeclaredField(name).apply { isAccessible = true }.get(editor)

    private fun slotDir(editor: Editor) = field(editor, "sessionDir") as File

    private fun runWorkspaceScenario(block: suspend (EditorPageCopyFixture, String) -> Unit) {
        initializeParkingEglHandles()
        val base = RuntimeEnvironment.getApplication()
        val root = File(base.cacheDir, "clean-cold-reopen-${UUID.randomUUID()}").apply { mkdirs() }
        val f = EditorPageCopyFixture(base, root, namespaced = true)
        val treeUri = DocumentsContract.buildTreeDocumentUri(f.authority, "root").toString()
        val singleton = NotebookStorage::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = singleton.get(null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var failure: Throwable? = null
        var initialized = false
        val job = scope.launch {
            try {
                withContext(Dispatchers.Main) {
                    singleton.set(null, f.storage)
                    f.editor = Editor(f.context)
                    f.editor.updateBrowseRoot(treeUri)
                    initialized = true
                }
                withTimeout(20000L) { block(f, treeUri) }
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
                        SplitRecentWriter.forFilesDir(f.context.filesDir).awaitAccepted()
                    }
                    singleton.set(null, previous)
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
            assertTrue("Clean cold reopen scenario did not settle", job.isCompleted)
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
        withContext(Dispatchers.Main) { f.editor = Editor(f.context) }
    }

    private suspend fun checkpointAndClose(editor: Editor): ColdPresentationBootstrap {
        val bootstrap = ColdPresentationBootstrap()
        val coordinator = ColdPresentationCoordinator(editor, bootstrap)
        try {
            coordinator.retireIntent()
            assertTrue(coordinator.checkpoint())
        } finally {
            coordinator.close()
        }
        return bootstrap
    }

    @Test fun cleanWorkspaceNoteCheckpointsForDirectReopenAndRestoresWithoutClosePrompt() = runWorkspaceScenario { f, _ ->
        withContext(Dispatchers.IO) { f.seed() }
        val customView = ViewOverrides(contrast = 132)
        withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri, f.sourceDisplayName)
            assertTrue(f.editor.noteOpen)
            assertEquals(f.sourceUri, f.editor.autosaveUri)
            f.editor.view.layout(0, 0, 1000, 800)
            f.editor.updateViewOverrides(customView)
            f.editor.toggleZoomLock()
            f.editor.state.pendingInitialView = InitialView.Restore(1.4, 24.0, 52.0, false)
            checkpointAndClose(f.editor)
        }
        val marker = LastPresentationStore(f.context.filesDir).observe().read as LastPresentationRead.Ready
        val native = marker.value as LastPresentation.Native
        val slot = native.slots.single() as ColdNativeSlot.Note
        assertTrue("Clean workspace note should checkpoint with reopenFromFile=true", slot.reopenFromFile)

        restartEditor(f)

        withContext(Dispatchers.Main) {
            val bootstrap = ColdPresentationBootstrap()
            val coordinator = ColdPresentationCoordinator(f.editor, bootstrap)
            try {
                assertTrue(coordinator.bootstrap())
                assertNull(bootstrap.problem)
                assertTrue(f.editor.noteOpen)
                assertFalse(f.editor.canvasOpen)
                assertFalse("Clean disk reopen must not fall back to protected copy", f.editor.requiresIndependentSave)
                assertEquals(f.sourceUri, f.editor.autosaveUri)
                assertFalse("Exiting a cleanly reopened workspace note must not prompt to save", requiresNativeClosePrompt(f.editor))
                assertEquals(customView, f.editor.viewOverrides)
                assertTrue(f.editor.zoomLocked)
                val restoredView = f.editor.state.pendingInitialView as? InitialView.Restore
                assertNotNull(restoredView)
                assertEquals(1.4, restoredView!!.zoom, 1e-6)
                assertEquals(24.0, restoredView.scrollX, 1e-6)
                assertEquals(52.0, restoredView.scrollY, 1e-6)
            } finally {
                coordinator.close()
            }
        }
        assertEquals(0, f.writes.get())
    }

    @Test fun pauseFlushTurnsDirtyWorkspaceNoteIntoCleanDirectReopen() = runWorkspaceScenario { f, _ ->
        withContext(Dispatchers.IO) { f.seed() }
        withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri, f.sourceDisplayName)
            f.editor.view.layout(0, 0, 1000, 800)
            f.editor.selectTool(Tool.PEN)
            f.addStroke(140.0, recordHistory = true)
            f.stopDebounce()
            assertTrue(f.editor.state.document.dirty)

            val dirtyCheckpoint = requireNotNull(f.editor.prepareNativePresentationCheckpoint(emptySet()) { true })
            assertFalse((dirtyCheckpoint.value.slots.single() as ColdNativeSlot.Note).reopenFromFile)
            val dirtyVersion = f.editor.presentationVersion

            // Simulate Activity.onPause(): flush autosave, await write, then checkpoint presentation.
            f.editor.flushAutosaveAll()
            f.editor.awaitAutosaveWrites()
            assertFalse("Flushed workspace note should now be clean", f.editor.state.document.dirty)
            assertTrue(f.editor.presentationVersion > dirtyVersion)
            assertFalse(dirtyCheckpoint.current())
            checkpointAndClose(f.editor)
        }
        assertEquals(1, f.writes.get())
        val marker = LastPresentationStore(f.context.filesDir).observe().read as LastPresentationRead.Ready
        val slot = (marker.value as LastPresentation.Native).slots.single() as ColdNativeSlot.Note
        assertTrue(slot.reopenFromFile)

        restartEditor(f)

        withContext(Dispatchers.Main) {
            val coordinator = ColdPresentationCoordinator(f.editor, ColdPresentationBootstrap())
            try {
                assertTrue(coordinator.bootstrap())
                assertTrue(f.editor.noteOpen)
                assertFalse(f.editor.requiresIndependentSave)
                assertEquals(f.sourceUri, f.editor.autosaveUri)
                assertFalse(requiresNativeClosePrompt(f.editor))
                assertEquals(1, f.editor.state.document.pages.first().items.filterIsInstance<Stroke>().size)
                f.addStroke(195.0, recordHistory = true)
                f.editor.flushAutosaveAll()
                f.editor.awaitAutosaveWrites()
                assertFalse(f.editor.state.document.dirty)
            } finally {
                coordinator.close()
            }
        }
        assertEquals(2, f.writes.get())
        val saved = withContext(Dispatchers.IO) { f.source.inputStream().use { f.codec.read(it) } }
        assertEquals(2, saved.pages.first().items.filterIsInstance<Stroke>().size)
    }

    @Test fun cleanWorkspaceCanvasReopensFromDiskWithMapAndWithoutClosePrompt() = runWorkspaceScenario { f, _ ->
        f.sourceDisplayName = "tree.xcanvas"
        val camera = Waypoint("", 176.0, -251.0, 1.5)
        withContext(Dispatchers.IO) { MapBundleFixture.write(f.source) }
        withContext(Dispatchers.Main) {
            assertTrue(f.editor.openCanvasAsync(f.sourceUri, f.sourceDisplayName))
            assertTrue(f.editor.canvasOpen)
            assertEquals(MapBundleFixture.state, f.editor.infinite.document.map.state)
            f.editor.infinite.document.lastView = camera
            checkpointAndClose(f.editor)
        }
        val marker = LastPresentationStore(f.context.filesDir).observe().read as LastPresentationRead.Ready
        val slot = (marker.value as LastPresentation.Native).slots.single() as ColdNativeSlot.Canvas
        assertTrue(slot.reopenFromFile)

        restartEditor(f)

        withContext(Dispatchers.Main) {
            val coordinator = ColdPresentationCoordinator(f.editor, ColdPresentationBootstrap())
            try {
                assertTrue(coordinator.bootstrap())
                assertTrue(f.editor.noteOpen)
                assertTrue(f.editor.canvasOpen)
                assertFalse(f.editor.requiresIndependentSave)
                assertFalse(requiresNativeClosePrompt(f.editor))
                assertEquals(MapBundleFixture.state, f.editor.infinite.document.map.state)
                assertEquals(camera, f.editor.infinite.document.lastView)
                assertEquals(f.sourceUri, f.editor.canvasAutosaveUri)
                assertEquals(0, f.writes.get())
                f.editor.infinite.document.waypoints += camera.copy(name = "Saved after reopen")
                f.editor.infinite.document.dirty = true
                f.editor.flushAutosaveAll()
                f.editor.awaitAutosaveWrites()
                assertFalse(f.editor.infinite.document.dirty)
            } finally {
                coordinator.close()
            }
        }
        val codec = field(f.editor, "canvasCodec") as com.xnotes.format.CanvasCodec
        val saved = withContext(Dispatchers.IO) { f.source.inputStream().use { codec.read(it) } }
        assertEquals("Saved after reopen", saved.waypoints.single().name)
        assertEquals(1, f.writes.get())
    }

    @Test fun cleanReopenReadsLatestDiskVersionWhenExternallyUpdatedWithSameDocumentId() = runWorkspaceScenario { f, _ ->
        withContext(Dispatchers.IO) { f.seed() }
        withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri, f.sourceDisplayName)
            checkpointAndClose(f.editor)
        }
        // External update to the same document (same documentId, one new stroke) while app is closed.
        withContext(Dispatchers.IO) {
            val onDisk = f.source.inputStream().use { f.codec.read(it) }
            onDisk.pages.first().items += Stroke(
                Tool.PEN,
                ToolDefaults.configFor(Tool.PEN),
                listOf(Sample(10.0, 20.0, 0.5), Sample(50.0, 60.0, 0.8)),
            )
            f.source.outputStream().use { f.codec.write(onDisk.fixedWriteSnapshot(), it) }
        }
        val externalBytes = f.source.readBytes()

        restartEditor(f)

        withContext(Dispatchers.Main) {
            val coordinator = ColdPresentationCoordinator(f.editor, ColdPresentationBootstrap())
            try {
                assertTrue(coordinator.bootstrap())
                assertTrue(f.editor.noteOpen)
                assertFalse(f.editor.requiresIndependentSave)
                assertFalse(requiresNativeClosePrompt(f.editor))
                assertEquals(
                    "Clean disk reopen must read the latest file on disk rather than stale cache",
                    1,
                    f.editor.state.document.pages.first().items.filterIsInstance<Stroke>().size,
                )
            } finally {
                coordinator.close()
            }
        }
        assertArrayEquals(externalBytes, f.source.readBytes())
        assertEquals(0, f.writes.get())
    }

    /** Pause-only recovery: a saved note keeps no working copy, so a replaced or missing file means Home. */
    @Test fun cleanReopenRejectsReplacedDocumentIdAndMissingDiskFileAndKeepsNoStaleBackup() = runWorkspaceScenario { f, _ ->
        withContext(Dispatchers.IO) { f.seed() }
        withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri, f.sourceDisplayName)
            checkpointAndClose(f.editor)
        }
        val backupBody = File(slotDir(f.editor), "document.xnote")
        assertFalse("A saved note's file is its only copy", backupBody.exists())

        // Subcase 1: File at the path was replaced by a different document (different UUID).
        withContext(Dispatchers.IO) { f.seed(f.source) }
        restartEditor(f)
        withContext(Dispatchers.Main) {
            val bootstrap = ColdPresentationBootstrap()
            val coordinator = ColdPresentationCoordinator(f.editor, bootstrap)
            try {
                assertFalse(coordinator.bootstrap())
                assertNull(bootstrap.problem)
                assertSame(LastPresentation.Home, bootstrap.presentation)
                assertFalse(f.editor.noteOpen)
            } finally {
                coordinator.close()
            }
        }
        assertFalse(backupBody.exists())

        // Subcase 2: File on disk was deleted.
        assertTrue(f.source.delete())
        restartEditor(f)
        withContext(Dispatchers.Main) {
            val bootstrap = ColdPresentationBootstrap()
            val coordinator = ColdPresentationCoordinator(f.editor, bootstrap)
            try {
                assertFalse(coordinator.bootstrap())
                assertNull(bootstrap.problem)
                assertSame(LastPresentation.Home, bootstrap.presentation)
                assertFalse(f.editor.noteOpen)
            } finally {
                coordinator.close()
            }
        }
        assertFalse(backupBody.exists())
    }

    @Test fun dirtyWorkspaceNoteStillRestoresAsProtectedSessionCopy() = runWorkspaceScenario { f, _ ->
        withContext(Dispatchers.IO) { f.seed() }
        withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri, f.sourceDisplayName)
            f.editor.view.layout(0, 0, 1000, 800)
            f.addStroke(90.0, recordHistory = true)
            f.stopDebounce()
            assertTrue(f.editor.state.document.dirty)
            checkpointAndClose(f.editor)
        }
        val marker = LastPresentationStore(f.context.filesDir).observe().read as LastPresentationRead.Ready
        val slot = (marker.value as LastPresentation.Native).slots.single() as ColdNativeSlot.Note
        assertFalse("Dirty note must NOT be marked reopenFromFile=true", slot.reopenFromFile)

        restartEditor(f)

        withContext(Dispatchers.Main) {
            val coordinator = ColdPresentationCoordinator(f.editor, ColdPresentationBootstrap())
            try {
                assertTrue(coordinator.bootstrap())
                assertTrue(f.editor.noteOpen)
                assertTrue("Dirty recovery copy must remain protected", f.editor.requiresIndependentSave)
                assertTrue(requiresNativeClosePrompt(f.editor))
                assertEquals(1, f.editor.state.document.pages.first().items.filterIsInstance<Stroke>().size)
            } finally {
                coordinator.close()
            }
        }
        assertEquals(0, f.writes.get())
    }

    @Test fun splitWithCleanSharedReaderRestoresBothPanesWithoutClosePrompt() = runWorkspaceScenario { f, _ ->
        withContext(Dispatchers.IO) { f.seed() }
        val primaryView = ViewOverrides(contrast = 118)
        val secondaryView = ViewOverrides(contrast = 148, scrollbar = false)
        withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri, f.sourceDisplayName)
            f.editor.updateViewOverrides(primaryView)
            val second = f.editor.secondaryPane()
            assertTrue(second.openSharedView(f.editor))
            second.updateViewOverrides(secondaryView)
            second.toggleZoomLock()
            checkpointAndClose(f.editor)
        }
        val marker = LastPresentationStore(f.context.filesDir).observe().read as LastPresentationRead.Ready
        val slots = (marker.value as LastPresentation.Native).slots
        assertTrue((slots[0] as ColdNativeSlot.Note).reopenFromFile)
        assertTrue(slots[1] is ColdNativeSlot.Shared)

        restartEditor(f)

        withContext(Dispatchers.Main) {
            val coordinator = ColdPresentationCoordinator(f.editor, ColdPresentationBootstrap())
            try {
                assertTrue(coordinator.bootstrap())
                val second = requireNotNull(f.editor.secondary)
                assertTrue(f.editor.noteOpen && second.noteOpen)
                assertSame(f.editor.state.document, second.state.document)
                assertFalse(f.editor.requiresIndependentSave)
                assertFalse(second.requiresIndependentSave)
                assertFalse(requiresNativeClosePrompt(f.editor))
                assertFalse(requiresNativeClosePrompt(second))
                assertEquals(f.sourceUri, f.editor.autosaveUri)
                assertEquals(f.sourceUri, second.autosaveUri)
                assertFalse(f.editor.zoomLocked)
                assertTrue(second.zoomLocked)
                assertEquals(primaryView, f.editor.viewOverrides)
                assertEquals(secondaryView, second.viewOverrides)
            } finally {
                coordinator.close()
            }
        }
        assertEquals(0, f.writes.get())
    }

    @Test fun cleanMarkerReopensTheOriginalEvenIfASessionBodyExists() = runWorkspaceScenario { f, _ ->
        withContext(Dispatchers.IO) { f.seed() }
        withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri, f.sourceDisplayName)
            checkpointAndClose(f.editor)
            f.addStroke(100.0, recordHistory = true)
            f.stopDebounce()
            val next = requireNotNull(f.editor.prepareNativePresentationCheckpoint(emptySet()) { true })
            assertFalse((next.value.slots.single() as ColdNativeSlot.Note).reopenFromFile)
        }
        val published = LastPresentationStore(f.context.filesDir).observe().read as LastPresentationRead.Ready
        assertTrue(((published.value as LastPresentation.Native).slots.single() as ColdNativeSlot.Note).reopenFromFile)
        restartEditor(f)
        withContext(Dispatchers.Main) {
            val coordinator = ColdPresentationCoordinator(f.editor, ColdPresentationBootstrap())
            try {
                assertTrue(coordinator.bootstrap())
                assertFalse(f.editor.requiresIndependentSave)
                assertEquals(f.sourceUri,f.editor.autosaveUri)
                assertTrue(f.editor.state.document.pages.first().items.filterIsInstance<Stroke>().isEmpty())
                f.editor.flushAutosaveAll()
                f.editor.awaitAutosaveWrites()
            } finally { coordinator.close() }
        }
        assertEquals(0, f.writes.get())
    }

    @Test fun changedDiskBytesBeforeVerificationAndNativeIntentBeforeClaimBothPreventAdoption() = runWorkspaceScenario { f, _ ->
        withContext(Dispatchers.IO) { f.seed() }
        withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri, f.sourceDisplayName)
            checkpointAndClose(f.editor)
        }
        val read = LastPresentationStore(f.context.filesDir).observe().read as LastPresentationRead.Ready
        val marker = read.value as LastPresentation.Native
        restartEditor(f)
        withContext(Dispatchers.Main) {
            val first = requireNotNull(f.editor.prepareColdNativePresentation(marker) { true })
            try {
                withContext(Dispatchers.IO) {
                    val doc = f.source.inputStream().use { f.codec.read(it) }
                    doc.pages.removeAt(1)
                    f.source.outputStream().use { f.codec.write(doc.fixedWriteSnapshot(), it) }
                }
                assertFalse(first.verify())
                assertFalse(first.claimForAdoption())
            } finally { first.close() }
            var current = true
            val second = requireNotNull(f.editor.prepareColdNativePresentation(marker) { current })
            try {
                assertTrue(second.verify())
                current = false
                assertFalse(second.claimForAdoption())
                assertFalse(f.editor.noteOpen)
            } finally { second.close() }
        }
        assertEquals(0, f.writes.get())
    }
}
