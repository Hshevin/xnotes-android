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
            object : NotebookStorage.Target {
                override fun read(): java.io.InputStream {
                    beforeRead?.invoke()
                    return target.inputStream()
                }
                override fun write(source: File) {
                    writes++
                    source.copyTo(target, overwrite = true)
                }
            }
        })
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
                f.addNoteStroke(second).also {
                    assertTrue(f.editor.openSingleFromHome(f.uri, "same.xnote"))
                    assertFalse(f.editor.inSplit)
                }
            }
            f.assertNoteAdoptsSavedB(hashA, outgoing)
        }
    }

}
