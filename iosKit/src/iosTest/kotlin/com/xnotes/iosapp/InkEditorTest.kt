package com.xnotes.iosapp

import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.tools.Tool
import com.xnotes.edit.PageSpace
import com.xnotes.format.DocumentCodec
import com.xnotes.kio.KFiles
import com.xnotes.kio.Uuids
import com.xnotes.pen.pencil.PencilGesture
import com.xnotes.pen.pencil.PencilPreferredAction
import com.xnotes.pen.pencil.PencilSample
import com.xnotes.platform.ios.CgImageCodec
import com.xnotes.platform.ios.CgTextMeasurer
import com.xnotes.storage.lite.LiteOpen
import com.xnotes.storage.lite.LiteStore
import kotlin.test.*

class InkEditorTest {
    private val fs = KFiles.system
    private val root = KFiles.tempDirectory() / "ink-editor-${Uuids.random()}"
    private val documents = root / "Documents"
    private val path = documents / "pages.xnote"
    private lateinit var store: LiteStore
    private lateinit var editor: InkEditor
    private var left = false
    private val space = object : PageSpace {
        override val zoom = 1.0
        override fun pageAt(content: Pt) = if (content.y < 100) 0 else 1
        override fun toPage(page: Int, content: Pt) = content - Pt(0.0, page * 100.0)
        override fun fromPage(page: Int, local: Pt) = local + Pt(0.0, page * 100.0)
        override fun hasPage(page: Int) = page in 0..1
        override fun writable(page: Int) = hasPage(page)
    }
    private fun opened() = assertIs<LiteOpen.Opened>(store.open(path)).session
    private fun sample(y: Double) = PencilSample(22.0, y, y / 1000, 1.0, 1.0, null, false)
    private fun begin(y: Double) = InkGesture(editor, space, pressure = false).also { it.begin(sample(y)) }
    private fun counts() = editor.session.note!!.pages.map { it.items.size }

    @BeforeTest fun prepare() {
        fs.createDirectories(documents)
        store = LiteStore(fs, documents, root / "Private", CgImageCodec, CgTextMeasurer)
        fs.write(path) { DocumentCodec(CgImageCodec, CgTextMeasurer).write(Document(mutableListOf(Page(100.0, 100.0), Page(100.0, 100.0))), this) }
        editor = InkEditor(opened(), SaveWorker.Inline) { left = true }
    }

    @AfterTest fun cleanup() { if (::editor.isInitialized) editor.releaseOwner(); fs.deleteRecursively(root) }

    @Test fun predictedPageAndQueuedToolDisappearOnCancel() {
        val action = begin(90.0)
        action.move(emptyList(), listOf(sample(115.0)))
        val wetPages = mutableSetOf<Int>()
        action.preview { page, _ -> wetPages += page }
        assertEquals(setOf(0, 1), wetPages)
        editor.gesture(PencilGesture.SQUEEZE, PencilPreferredAction.SWITCH_ERASER)
        assertEquals(Tool.PEN, editor.tool)
        action.finish(emptyList(), true)
        assertEquals(Tool.PEN, editor.tool)
        assertEquals(listOf(0, 0), counts())
        assertFalse(editor.session.dirty); assertFalse(editor.canUndo)
    }

    @Test fun backAndBackgroundWaitForPenUpAndSaveAllPagesTogether() {
        begin(10.0).finish(listOf(sample(20.0)), false)
        val action = begin(90.0)
        editor.gesture(PencilGesture.DOUBLE_TAP, PencilPreferredAction.SWITCH_ERASER)
        editor.save(background = true); editor.save(back = true)
        assertFalse(left); assertTrue(editor.session.dirty)
        opened().use { assertEquals(listOf(0, 0), it.note!!.pages.map { page -> page.items.size }) }
        action.finish(listOf(sample(118.0)), false)
        assertTrue(left, editor.warning); assertFalse(editor.session.dirty); assertEquals(Tool.ERASER, editor.tool)
        opened().use { assertEquals(listOf(2, 1), it.note!!.pages.map { page -> page.items.size }) }
        editor.replay(true); assertEquals(listOf(1, 0), counts())
        editor.replay(false); assertEquals(listOf(2, 1), counts())
    }

    @Test fun cancellingEraserBeforeBackgroundSavePreservesOriginalStroke() {
        begin(20.0).finish(listOf(sample(30.0)), false)
        editor.save()
        assertNull(editor.warning)
        val bytes = fs.read(path) { readByteArray() }
        editor.select(Tool.ERASER)
        val action = begin(25.0)
        assertEquals(listOf(0, 0), counts())
        editor.save(background = true)
        action.finish(emptyList(), true)
        assertEquals(listOf(1, 0), counts()); assertFalse(editor.session.dirty)
        assertContentEquals(bytes, fs.read(path) { readByteArray() })
        editor.replay(true); assertEquals(listOf(0, 0), counts())
    }

    @Test fun backgroundConflictOffersOnlyTheCurrentRegisteredCopy() {
        val bytes = fs.read(path) { readByteArray() }
        val replacement = documents / "replacement.xnote"
        fs.write(replacement) { write(bytes) }; fs.atomicMove(replacement, path)
        val action = begin(20.0)
        editor.save(background = true)
        assertTrue(store.pendingRecoveries().isEmpty())
        action.finish(listOf(sample(30.0)), false)
        val copy = assertNotNull(editor.session.registeredRecoveryCopy)
        assertEquals(store.pendingRecoveries().single().record.parent, copy.parent)
        assertTrue(fs.metadata(copy).isRegularFile); assertTrue(editor.warning!!.endsWith(copy.name))
        assertTrue(editor.canLeaveProtected); assertTrue(editor.session.dirty)
        assertContentEquals(bytes, fs.read(path) { readByteArray() })
    }

    private class HeldWorker : SaveWorker {
        val queued = mutableListOf<() -> Unit>()
        override fun <T> launch(work: () -> T, done: (T) -> Unit) { queued += { done(work()) } }
        fun runNext() = queued.removeAt(0)()
    }

    private fun heldEditor() = HeldWorker().also { held ->
        editor.releaseOwner()
        editor = InkEditor(opened(), held) { left = true }
    }

    @Test fun editsDuringAWriteStayUnsavedAndBackWaitsForThem() {
        val held = heldEditor()
        begin(10.0).finish(listOf(sample(20.0)), false)
        editor.save()
        assertTrue(editor.saving)
        begin(40.0).finish(listOf(sample(50.0)), false)
        editor.save(back = true)
        assertEquals(1, held.queued.size)
        held.runNext()
        assertFalse(left); assertTrue(editor.session.dirty); assertNull(editor.warning)
        opened().use { assertEquals(listOf(1, 0), it.note!!.pages.map { page -> page.items.size }) }
        assertTrue(editor.saving)
        held.runNext()
        assertTrue(left); assertFalse(editor.session.dirty); assertFalse(editor.saving)
        opened().use { assertEquals(listOf(2, 0), it.note!!.pages.map { page -> page.items.size }) }
    }

    @Test fun closingDuringAWriteClosesTheDocumentAfterIt() {
        val held = heldEditor()
        begin(10.0).finish(listOf(sample(20.0)), false)
        editor.save()
        editor.releaseOwner()
        editor.session.markEdited()
        held.runNext()
        assertFailsWith<IllegalStateException> { editor.session.markEdited() }
        assertTrue(held.queued.isEmpty())
    }
}
