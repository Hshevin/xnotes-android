package com.xnotes.pen

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.fixedWriteSnapshot
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.BundleMigration
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.platform.LegacySessionQueue
import com.xnotes.storage.MigrationPublication
import com.xnotes.storage.NotebookStorage
import com.xnotes.ui.Editor
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Real Editor reopen/save/adopt paths, with actual ZIP bytes behind a file-backed target. */
abstract class EditorSourceReopenContract {
    /** The scenario runs off Main; the host keeps the real Main dispatcher moving. */
    protected abstract fun runSourceScenario(block: suspend (Context, File) -> Unit)

    private class Fixture(val context: Context, val root: File) {
        val uri = "content://source-reopen/document/a"
        val alias = "content://source-reopen/tree/root/document/a"
        val otherUri = "content://source-reopen/document/other"
        val file = File(root, "current.bundle")
        val otherFile = File(root, "other.xnote")
        val noteCodec = DocumentCodec(AndroidImageCodec(), AndroidTextMeasurer())
        val canvasCodec = CanvasCodec(AndroidImageCodec(), AndroidTextMeasurer())
        val editors = ArrayList<Editor>()
        var writes = 0
        @Volatile var beforeRead: (() -> Unit)? = null
        @Volatile var validation: (() -> Unit)? = null
        val storage = NotebookStorage(context, targetOverride = { targetUri ->
            val target = when (targetUri) {
                uri, alias -> file
                otherUri -> otherFile
                else -> error("Unexpected fixture target: $targetUri")
            }
            object : MigrationPublication.Target {
                override fun read(): java.io.InputStream {
                    beforeRead?.invoke()
                    return target.inputStream()
                }
                override fun write(source: File) {
                    writes++
                    source.copyTo(target, overwrite = true)
                }
            }
        }, sourceValidationHook = { validation?.invoke() })
        lateinit var editor: Editor

        fun seedNote(target: File = file) {
            target.outputStream().use { noteCodec.write(Document(mutableListOf(Page(500.0, 500.0))), it) }
        }

        fun seedCanvas() {
            file.outputStream().use { canvasCodec.write(InfiniteDocument(), it) }
        }

        fun stroke() = Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN),
            listOf(Sample(120.0, 130.0, .5), Sample(160.0, 170.0, .75)))

        fun addNoteStroke(target: Editor): Document {
            val model = target.state.document
            model.pages.single().items += stroke()
            model.dirty = true
            return model
        }

        fun assertStroke(strokes: List<Stroke>) {
            assertEquals("the newly saved stroke must survive adoption", 1, strokes.size)
            val samples = strokes.single().samples
            assertEquals(2, samples.size)
            assertEquals(120.0, samples.first().x, .0001)
            assertEquals(130.0, samples.first().y, .0001)
            assertEquals(.5, samples.first().pressure, .0001)
            assertEquals(160.0, samples.last().x, .0001)
            assertEquals(170.0, samples.last().y, .0001)
            assertEquals(.75, samples.last().pressure, .0001)
        }

        fun readNote() = file.inputStream().use { noteCodec.read(it) }
        fun readCanvas() = file.inputStream().use { canvasCodec.read(it) }
        fun assetDirectories(): Set<String> = File(context.filesDir, "noteimg").listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("native-open-") }.mapTo(linkedSetOf()) { it.name }

        suspend fun assertNoteAdoptsSavedB(hashA: String, outgoing: Document) {
            val hashB = withContext(Dispatchers.IO) { BundleMigration.hash(file) }
            assertNotEquals("settling the outgoing model must really write B", hashA, hashB)
            assertTrue("the real native publication must run", writes > 0)
            val disk = withContext(Dispatchers.IO) { readNote() }
            assertStroke(disk.pages.single().items.filterIsInstance<Stroke>())
            withContext(Dispatchers.Main) {
                assertNotSame("reopen must adopt actual decoded bytes", outgoing, editor.state.document)
                assertStroke(editor.state.document.pages.single().items.filterIsInstance<Stroke>())
                assertEquals(disk.identity.documentId, editor.state.document.identity.documentId)
                val version = requireNotNull(editor.lastReadSourceVersion())
                assertEquals(DocumentKind.NOTE, version.kind)
                assertEquals(storage.registry.locationKey(uri), version.locationKey)
                assertEquals(hashB, version.sha256)
                assertFalse(editor.state.document.dirty)
            }
        }
    }

    private suspend fun scenario(context: Context, root: File, block: suspend (Fixture) -> Unit) {
        val fixture = Fixture(context, root)
        val singleton = NotebookStorage::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = singleton.get(null)
        try {
            withContext(Dispatchers.Main) {
                singleton.set(null, fixture.storage)
                fixture.editor = Editor(context).also { fixture.editors += it }
            }
            withTimeout(15000L) { block(fixture) }
        } finally {
            withContext(NonCancellable) {
                withContext(Dispatchers.Main) {
                    val scopes = fixture.editors.map {
                        Editor::class.java.getDeclaredField("autosaveScope").apply { isAccessible = true }.get(it) as CoroutineScope
                    }
                    scopes.forEach { it.cancel() }
                    scopes.forEach { it.coroutineContext[Job]?.join() }
                    // Flush notifications from completed jobs before observing each queue prefix.
                    suspendCancellableCoroutine<Unit> { waiter ->
                        Handler(Looper.getMainLooper()).post { waiter.resumeWith(Result.success(Unit)) }
                    }
                }
                for (editor in fixture.editors) {
                    val owner = Editor::class.java.getDeclaredField("sessionOwner").apply { isAccessible = true }
                        .get(editor) as LegacySessionQueue.Owner
                    owner.awaitAccepted()
                }
                singleton.set(null, previous)
                withContext(Dispatchers.IO) { fixture.storage.registry.close() }
            }
        }
    }

    @Test fun reopeningTheSameNoteAdoptsTheVersionWrittenWhileSettling() = runSourceScenario { context, root ->
        scenario(context, root) { f ->
            withContext(Dispatchers.IO) { f.seedNote() }
            val hashA = withContext(Dispatchers.IO) { BundleMigration.hash(f.file) }
            val outgoing = withContext(Dispatchers.Main) {
                f.editor.openAsync(f.uri, "same.xnote")
                assertTrue(f.editor.noteOpen)
                assertEquals(hashA, f.editor.lastReadSourceVersion()?.sha256)
                f.addNoteStroke(f.editor).also { f.editor.openAsync(f.uri, "same.xnote") }
            }
            f.assertNoteAdoptsSavedB(hashA, outgoing)
        }
    }

    @Test fun reopeningTheSameCanvasAdoptsTheVersionWrittenWhileSettling() = runSourceScenario { context, root ->
        scenario(context, root) { f ->
            withContext(Dispatchers.IO) { f.seedCanvas() }
            val hashA = withContext(Dispatchers.IO) { BundleMigration.hash(f.file) }
            val outgoing = withContext(Dispatchers.Main) {
                assertTrue(f.editor.openCanvasAsync(f.uri, "same.xcanvas"))
                assertEquals(hashA, f.editor.lastReadSourceVersion()?.sha256)
                f.editor.infinite.document.also { doc ->
                    doc.add(f.stroke())
                    doc.dirty = true
                    assertTrue(f.editor.openCanvasAsync(f.uri, "same.xcanvas"))
                }
            }
            val hashB = withContext(Dispatchers.IO) { BundleMigration.hash(f.file) }
            assertNotEquals(hashA, hashB)
            assertTrue(f.writes > 0)
            val disk = withContext(Dispatchers.IO) { f.readCanvas() }
            f.assertStroke(disk.items.filterIsInstance<Stroke>())
            withContext(Dispatchers.Main) {
                assertNotSame(outgoing, f.editor.infinite.document)
                f.assertStroke(f.editor.infinite.document.items.filterIsInstance<Stroke>())
                assertEquals(disk.identity.documentId, f.editor.infinite.document.identity.documentId)
                val version = requireNotNull(f.editor.lastReadSourceVersion())
                assertEquals(DocumentKind.CANVAS, version.kind)
                assertEquals(hashB, version.sha256)
                assertFalse(f.editor.infinite.document.dirty)
            }
        }
    }

    @Test fun reopeningACanonicalAliasAdoptsTheSavedNoteRatherThanItsEarlierSnapshot() = runSourceScenario { context, root ->
        scenario(context, root) { f ->
            withContext(Dispatchers.IO) { f.seedNote() }
            assertEquals(f.storage.registry.locationKey(f.uri), f.storage.registry.locationKey(f.alias))
            val hashA = withContext(Dispatchers.IO) { BundleMigration.hash(f.file) }
            val outgoing = withContext(Dispatchers.Main) {
                f.editor.openAsync(f.uri, "same.xnote")
                assertTrue(f.editor.noteOpen)
                f.addNoteStroke(f.editor).also { f.editor.openAsync(f.alias, "same.xnote") }
            }
            f.assertNoteAdoptsSavedB(hashA, outgoing)
            withContext(Dispatchers.Main) { assertEquals(f.alias, f.editor.state.document.path) }
        }
    }

    @Test fun homeReopenSavesTheOtherPaneBeforeAdoptingItsActualNewVersion() = runSourceScenario { context, root ->
        scenario(context, root) { f ->
            withContext(Dispatchers.IO) { f.seedNote(); f.seedNote(f.otherFile) }
            val hashA = withContext(Dispatchers.IO) { BundleMigration.hash(f.file) }
            val outgoing = withContext(Dispatchers.Main) {
                f.editor.openAsync(f.otherUri, "other.xnote")
                assertTrue(f.editor.noteOpen)
                val second = f.editor.secondaryPane().also { f.editors += it }
                second.openAsync(f.uri, "same.xnote")
                assertTrue(f.editor.inSplit)
                assertEquals(hashA, second.lastReadSourceVersion()?.sha256)
                f.addNoteStroke(second).also {
                    assertTrue(f.editor.openSingleFromHome(f.uri, "same.xnote"))
                    assertFalse(f.editor.inSplit)
                }
            }
            f.assertNoteAdoptsSavedB(hashA, outgoing)
        }
    }

    @Test fun anIdentityReplacedBeforeFinalVerificationCannotReplaceTheCurrentWorkspace() = runSourceScenario { context, root ->
        scenario(context, root) { f ->
            withContext(Dispatchers.IO) { f.seedNote() }
            val old = withContext(Dispatchers.Main) {
                f.editor.openAsync(f.uri, "same.xnote")
                assertTrue(f.editor.noteOpen)
                f.editor.state.document
            }
            val originalVersion = withContext(Dispatchers.Main) { requireNotNull(f.editor.lastReadSourceVersion()) }
            val originalAssets = f.assetDirectories()
            val validations = AtomicInteger()
            val replacementId = AtomicReference<String?>()
            // The first hook belongs to the initial preparation of this reopen. Replace the real
            // target only when its subsequent final-verification read is about to acquire bytes.
            f.validation = {
                if (validations.incrementAndGet() == 1) f.beforeRead = {
                    f.beforeRead = null
                    f.seedNote()
                    replacementId.set(f.readNote().identity.documentId)
                }
            }
            try {
                withContext(Dispatchers.Main) { f.editor.openAsync(f.uri, "same.xnote") }
                val foreignId = requireNotNull(replacementId.get()) { "final verification did not reread the actual target" }
                assertNotEquals(old.identity.documentId, foreignId)
                withContext(Dispatchers.Main) {
                    assertSame(old, f.editor.state.document)
                    assertTrue(f.editor.noteOpen)
                    assertSame(originalVersion, f.editor.lastReadSourceVersion())
                    assertFalse(old.dirty)
                }
                assertEquals(foreignId, withContext(Dispatchers.IO) { f.readNote().identity.documentId })
                assertEquals(0, f.writes)
                assertEquals(originalAssets, f.assetDirectories())
            } finally { f.validation = null; f.beforeRead = null }
        }
    }

    @Test fun aNewEditSavedCleanDuringVerificationStillInvalidatesTheOldPreparedModel() = runSourceScenario { context, root ->
        scenario(context, root) { f ->
            withContext(Dispatchers.IO) { f.seedNote() }
            val old = withContext(Dispatchers.Main) {
                f.editor.openAsync(f.uri, "same.xnote")
                assertTrue(f.editor.noteOpen)
                f.editor.state.document
            }
            val hashA = withContext(Dispatchers.IO) { BundleMigration.hash(f.file) }
            val assetsA = f.assetDirectories()
            val validations = AtomicInteger()
            val entered = CountDownLatch(1)
            val candidateReady = CountDownLatch(1)
            val leaveValidation = CountDownLatch(1)
            val candidate = AtomicReference<File?>()
            val published = CompletableDeferred<Unit>()
            f.validation = {
                if (validations.incrementAndGet() == 2) {
                    entered.countDown()
                    try {
                        check(candidateReady.await(5, TimeUnit.SECONDS)) { "test did not provide candidate C" }
                        // openSource holds the storage monitor here. Publish on this same IO thread,
                        // whose monitor acquisition is reentrant; never block Main on that monitor.
                        f.storage.publishNote(f.uri, requireNotNull(candidate.get()), DocumentKind.NOTE, "same.xnote")
                        published.complete(Unit)
                        check(leaveValidation.await(5, TimeUnit.SECONDS)) { "test did not finish the clean-C transition" }
                    } catch (error: Throwable) {
                        published.completeExceptionally(error)
                        throw error
                    }
                }
            }
            coroutineScope {
                val opening = async(Dispatchers.Main) { f.editor.openAsync(f.uri, "same.xnote") }
                try {
                    assertTrue("reopen must reach its post-settle verification", withContext(Dispatchers.IO) {
                        entered.await(5, TimeUnit.SECONDS)
                    })
                    val snapshot = withContext(Dispatchers.Main) {
                        assertSame(old, f.editor.state.document)
                        f.addNoteStroke(f.editor)
                        old.fixedWriteSnapshot()
                    }
                    val encoded = withContext(Dispatchers.IO) {
                        File(root, "candidate-C.xnote").also { file ->
                            file.outputStream().use { f.noteCodec.write(snapshot, it) }
                        }
                    }
                    candidate.set(encoded)
                    candidateReady.countDown()
                    withTimeout(5000L) { published.await() }
                    withContext(Dispatchers.Main) {
                        assertSame(old, f.editor.state.document)
                        old.dirty = false // the actual file now contains this edited model C
                        assertFalse(old.dirty)
                    }
                    leaveValidation.countDown()
                    opening.await()
                    val hashC = withContext(Dispatchers.IO) { BundleMigration.hash(f.file) }
                    assertNotEquals(hashA, hashC)
                    assertEquals(1, f.writes)
                    f.assertStroke(withContext(Dispatchers.IO) { f.readNote() }.pages.single().items.filterIsInstance<Stroke>())
                    withContext(Dispatchers.Main) {
                        assertSame("a clean flag cannot authorize the old prepared A", old, f.editor.state.document)
                        f.assertStroke(old.pages.single().items.filterIsInstance<Stroke>())
                        assertFalse(old.dirty)
                        assertEquals(hashA, f.editor.lastReadSourceVersion()?.sha256)
                    }
                    assertEquals(assetsA, f.assetDirectories())
                } finally {
                    candidateReady.countDown()
                    leaveValidation.countDown()
                    opening.cancelAndJoin()
                    f.validation = null
                    candidate.get()?.delete()
                }
            }
        }
    }

    @Test fun unchangedVerificationRetainsTheActualPreparedAssetDirectoryInsteadOfDecodingAgain() = runSourceScenario { context, root ->
        scenario(context, root) { f ->
            withContext(Dispatchers.IO) { f.seedNote() }
            withContext(Dispatchers.Main) { f.editor.openAsync(f.uri, "same.xnote"); assertTrue(f.editor.noteOpen) }
            val adoptedAssets = f.assetDirectories()
            val validations = AtomicInteger()
            val preparedAssets = AtomicReference<Set<String>?>()
            f.validation = {
                if (validations.incrementAndGet() == 2) preparedAssets.set(f.assetDirectories() - adoptedAssets)
            }
            try {
                withContext(Dispatchers.Main) { f.editor.openAsync(f.uri, "same.xnote") }
                val decoded = requireNotNull(preparedAssets.get()) { "final source verification was not reached" }
                assertEquals("initial preparation must own exactly one fresh decoded directory", 1, decoded.size)
                assertEquals("same-SHA verification must keep that exact preparation", decoded, f.assetDirectories() - adoptedAssets)
                withContext(Dispatchers.Main) {
                    assertEquals(withContext(Dispatchers.IO) { BundleMigration.hash(f.file) }, f.editor.lastReadSourceVersion()?.sha256)
                }
                assertEquals(0, f.writes)
            } finally { f.validation = null }
        }
    }

    @Test fun cancellingFinalVerificationPreservesTheSavedModelAndCleansTheUnadoptedPreparation() = runSourceScenario { context, root ->
        scenario(context, root) { f ->
            withContext(Dispatchers.IO) { f.seedNote() }
            val old = withContext(Dispatchers.Main) {
                f.editor.openAsync(f.uri, "same.xnote")
                assertTrue(f.editor.noteOpen)
                f.addNoteStroke(f.editor)
            }
            val assetsA = f.assetDirectories()
            val validations = AtomicInteger()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.validation = {
                if (validations.incrementAndGet() == 2) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS)) { "test did not release cancelled source verification" }
                }
            }
            coroutineScope {
                val opening = async(Dispatchers.Main) { f.editor.openAsync(f.uri, "same.xnote") }
                try {
                    assertTrue("post-settle verification must be held", withContext(Dispatchers.IO) {
                        entered.await(5, TimeUnit.SECONDS)
                    })
                    withContext(Dispatchers.Main) { f.editor.cancelOpenInProgress() }
                    release.countDown()
                    opening.await()
                    f.assertStroke(withContext(Dispatchers.IO) { f.readNote() }.pages.single().items.filterIsInstance<Stroke>())
                    withContext(Dispatchers.Main) {
                        assertSame(old, f.editor.state.document)
                        f.assertStroke(old.pages.single().items.filterIsInstance<Stroke>())
                        assertFalse(old.dirty)
                    }
                    assertTrue(f.writes > 0)
                    assertEquals(assetsA, f.assetDirectories())
                } finally {
                    release.countDown()
                    opening.cancelAndJoin()
                    f.validation = null
                }
            }
        }
    }
}
