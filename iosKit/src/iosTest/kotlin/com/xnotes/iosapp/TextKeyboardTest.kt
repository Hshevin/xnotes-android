@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.xnotes.iosapp

import com.xnotes.core.geometry.*
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.*
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.text.ListKind
import com.xnotes.core.tools.Tool
import com.xnotes.format.*
import com.xnotes.kio.*
import com.xnotes.platform.ios.*
import com.xnotes.storage.lite.*
import kotlinx.cinterop.useContents
import platform.Foundation.NSMakeRange
import kotlin.test.*

class TextKeyboardTest {
    private val space = object : InkSpace {
        override val zoom = 1.0
        override val origin = Pt.ZERO
        override fun paper(page: Int): Rect? = null
        override fun night(page: Int) = false
        override fun hasPage(page: Int) = page == 0
        override fun writable(page: Int) = true
        override fun pageAt(content: Pt) = 0
        override fun toPage(page: Int, content: Pt) = content
        override fun fromPage(page: Int, local: Pt) = local
    }

    private fun runEditor(canvas: Boolean = false, test: (InkEditor, TextSurface, () -> LiteSession) -> Unit) {
        val root = KFiles.tempDirectory() / "text-input-${Uuids.random()}"
        val fs = KFiles.system
        val docs = root / "Documents"; fs.createDirectories(docs)
        try {
            val path = docs / if (canvas) "box.xcanvas" else "flow.xnote"
            fs.write(path) {
                if (canvas) CanvasCodec(CgImageCodec, CgTextMeasurer).write(InfiniteDocument(), this)
                else DocumentCodec(CgImageCodec, CgTextMeasurer).write(Document(mutableListOf(Page(1000.0, 1200.0))), this)
            }
            val store = LiteStore(fs, docs, root / "Private", CgImageCodec, CgTextMeasurer)
            fun open() = assertIs<LiteOpen.Opened>(store.open(path)).session
            val editor = InkEditor(open()) {}
            val images = editor.session.note?.let { PageImages(it, false) }
            val surface = TextSurface(editor, space, images) { false }
            try {
                editor.invalidate = { images?.invalidateFlow() }
                editor.select(Tool.TEXT); surface.configure()
                assertTrue(surface.start(Pt(98.0, 98.0), true))
                test(editor, surface, ::open)
            } finally { surface.close(); images?.close(); editor.releaseOwner() }
        } finally { fs.deleteRecursively(root) }
    }

    @Test fun markedRangeDoesNotSaveUnchosenPinyinOrLoseFormatting() = runEditor { editor, surface, open ->
        val keyboard = surface.view
        keyboard.insertText("a😀")
        keyboard.deleteBackward()
        surface.format("bold")
        keyboard.setMarkedText("zhong", NSMakeRange(5u, 0u))
        assertEquals("zhong", keyboard.textInRange(assertNotNull(keyboard.markedTextRange())))
        editor.save(background = true)
        open().use { assertEquals("a", it.note!!.flow.plainText()) }
        assertEquals("azhong", editor.session.note!!.flow.plainText())
        keyboard.insertText("中")
        val flow = editor.session.note!!.flow
        assertEquals("a中", flow.plainText()); assertTrue(flow.paragraphs.first().runs.last().style.bold)
        assertNull(keyboard.markedTextRange()); editor.replay(true); assertEquals("a", flow.plainText())
        editor.replay(false); assertEquals("a中", flow.plainText())
        val caret = keyboard.caretRectForPosition(keyboard.endOfDocument())
        assertTrue(caret.useContents { size.height > 0 && size.width > 0 })
        keyboard.selectAll(null); surface.format("italic"); surface.format("list")
        assertTrue(flow.paragraphs.first().runs.all { it.style.italic }); assertEquals(ListKind.BULLET, flow.paragraphs.first().list)
        keyboard.replaceRange(assertNotNull(keyboard.selectedTextRange()), "one\ntwo")
        editor.save(); open().use { assertEquals("one\ntwo", it.note!!.flow.plainText()) }
    }

    @Test fun boxEditsJoinDocumentHistoryAndSurviveReopen() = runEditor(true) { editor, surface, open ->
        val keyboard = surface.view
        assertTrue(editor.session.canvas!!.items.isEmpty())
        keyboard.setMarkedText("ni", NSMakeRange(2u, 0u)); editor.save(background = true)
        open().use { assertTrue(it.canvas!!.items.isEmpty()) }
        keyboard.setMarkedText("", NSMakeRange(0u, 0u)); keyboard.unmarkText(); assertFalse(editor.canUndo)
        keyboard.insertText("Notes")
        val box = assertIs<ShapeItem>(editor.session.canvas!!.items.single())
        assertTrue(surface.start(box.box.topLeft, true)); keyboard.insertText("My ")
        assertEquals("My Notes", box.label!!.text)
        keyboard.setMarkedText("bi", NSMakeRange(2u, 0u)); assertEquals("My biNotes", box.label!!.text)
        editor.save(); assertNull(editor.warning)
        open().use { assertEquals("My Notes", (it.canvas!!.items.single() as ShapeItem).label!!.text) }
        keyboard.setMarkedText("笔", NSMakeRange(1u, 0u)); keyboard.unmarkText()
        editor.replay(true); assertEquals("My Notes", box.label!!.text)
        editor.replay(false); assertEquals("My 笔Notes", box.label!!.text)
        editor.save(); assertNull(editor.warning)
        val androidBox = ShapeItem(ShapeKind.RECTANGLE, box.box.topLeft, Pt(box.box.right, box.box.bottom), Rgba(0, 0, 0, 0), 0.0)
        androidBox.label = ShapeLabel("My 笔Notes", TextStyle(black, TextItem.DEFAULT_POINT_SIZE, TextItem.DEFAULT_FACE), CgTextMeasurer)
        val decoded = KFiles.system.read(editor.session.path) { CanvasCodec(CgImageCodec, CgTextMeasurer).read(this) }
        val restored = assertIs<ShapeItem>(decoded.items.single())
        assertEquals(androidBox.shape, restored.shape); assertEquals(androidBox.box, restored.box)
        assertEquals(androidBox.strokeRgba, restored.strokeRgba); assertEquals(androidBox.strokeWidth, restored.strokeWidth)
        assertEquals(androidBox.fillRgba, restored.fillRgba); assertTrue(androidBox.label!!.sameAs(restored.label))
        assertTrue(BoxText.matches(restored)); assertTrue(BoxText.matches(androidBox))
        val oldRange = assertNotNull(keyboard.selectedTextRange())
        assertTrue(surface.start(Pt(2000.0, 2000.0), true)); keyboard.replaceRange(oldRange, "wrong box")
        assertEquals(1, editor.session.canvas!!.items.size); assertNull(keyboard.textInRange(oldRange))
        assertTrue(surface.start(box.box.topLeft, true))
        keyboard.selectAll(null); keyboard.deleteBackward(); assertTrue(editor.session.canvas!!.items.isEmpty())
        editor.replay(true); assertTrue(androidBox.label!!.sameAs((editor.session.canvas!!.items.single() as ShapeItem).label))
    }
}
