@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.xnotes.iosapp

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.tools.Tool
import com.xnotes.kio.KFiles
import com.xnotes.kio.Uuids
import com.xnotes.pen.pencil.PencilSample
import com.xnotes.platform.ios.CgImageCodec
import com.xnotes.platform.ios.CgTextMeasurer
import com.xnotes.storage.lite.*
import platform.Foundation.NSMakeRange
import kotlin.test.*

class EditorIntegrationTest {
    private val origin = Pt(180.0, 180.0)
    private val canvasSpace = object : InkSpace {
        override val origin = Pt.ZERO
        override val zoom = 1.0
        override fun night(page: Int) = false
        override fun paper(page: Int): Rect? = null
        override fun hasPage(page: Int) = page == 0
        override fun writable(page: Int) = hasPage(page)
        override fun pageAt(content: Pt) = 0
        override fun toPage(page: Int, content: Pt) = content
        override fun fromPage(page: Int, local: Pt) = local
    }

    private fun withEditor(kind: LiteKind, test: (InkEditor) -> Unit) {
        val fs = KFiles.system
        val root = KFiles.tempDirectory() / Uuids.random()
        val docs = (root / "Documents").also(fs::createDirectories)
        try {
            val store = LiteStore(fs, docs, root / "Private", CgImageCodec, CgTextMeasurer)
            val editor = InkEditor(assertIs<LiteOpen.Opened>(store.create(docs, "Integration", kind)).session, SaveWorker.Inline) {}
            try { test(editor) } finally { editor.releaseOwner() }
        } finally { fs.deleteRecursively(root) }
    }

    @Test fun deletingComposingPageRefreshesHitTestingAndInvalidatesOldKeyboardRanges() = withEditor(LiteKind.NOTE) { editor ->
        val note = editor.session.note!!
        val first = note.pages.single()
        editor.panels.add(false)
        editor.select(Tool.TEXT)
        val paged = PagedView(editor, false) { fail("page rendering failed") }
        try {
            val surface = assertNotNull(editor.textSurface)
            val secondPoint = PagePlacement(note).places.last().center
            assertTrue(surface.start(origin, false))
            val keyboard = surface.view
            keyboard.setMarkedText("中", NSMakeRange(1u, 0u))
            val oldRange = assertNotNull(keyboard.markedTextRange())
            editor.panels.deletePage(first)
            assertEquals(1, note.pages.size); assertNull(surface.target)
            keyboard.insertText("late"); assertEquals("中", note.flow.plainText())
            assertFalse(surface.start(secondPoint, false)); assertTrue(surface.start(origin, false))
            keyboard.replaceRange(oldRange, "late"); assertEquals("中", note.flow.plainText())
            editor.replay(true); assertSame(first, note.pages.first()); assertNull(surface.target)
            assertTrue(surface.start(secondPoint, false)); assertEquals("中", note.flow.plainText())
            editor.replay(true); assertEquals("", note.flow.plainText())
            editor.replay(false); editor.replay(false); assertEquals(1, note.pages.size); assertEquals("中", note.flow.plainText())
        } finally { paged.close() }
    }

    @Test fun pageReorderRebuildsTheCachedFlowFrameBeforeTheNextTextHit() = withEditor(LiteKind.NOTE) { editor ->
        val note = editor.session.note!!
        val cover = note.pages.single().apply { cover = true }
        editor.panels.add(false); val body = note.pages.last()
        editor.select(Tool.TEXT)
        val paged = PagedView(editor, false) { fail("page rendering failed") }
        try {
            val surface = assertNotNull(editor.textSurface)
            fun caretPage() = assertNotNull(surface.target).let { assertNotNull(it.caret(it.input.selection.end)).page }
            assertTrue(surface.start(PagePlacement(note).places.last().center, false)); surface.view.insertText("字")
            assertEquals(1, caretPage())
            editor.panels.movePage(cover, body); assertNull(surface.target)
            assertTrue(surface.start(origin, false)); assertEquals(0, caretPage())
            editor.replay(true); assertTrue(surface.start(PagePlacement(note).places.last().center, false)); assertEquals(1, caretPage())
        } finally { paged.close() }
    }

    @Test fun deletingLayerDuringMoveRestoresOriginalLabelGeometryAndIndexBeforeRemoval() = withEditor(LiteKind.CANVAS) { editor ->
        val model = editor.session.canvas!!
        val panels = editor.panels
        panels.edit { panels.layers.create("Text") }
        val selection = ItemSelection(editor, canvasSpace) {}; editor.selection = selection
        editor.select(Tool.TEXT)
        val surface = TextSurface(editor, canvasSpace) { false }.apply { configure() }
        assertTrue(surface.start(origin, true)); surface.view.setMarkedText("笔", NSMakeRange(1u, 0u))
        editor.select(Tool.SELECT); surface.view.insertText("late")
        val shape = assertIs<ShapeItem>(model.items.single()); assertEquals("笔", shape.label!!.text)
        val bounds = shape.bounds(); val center = bounds.center
        selection.begin(bounds.topLeft - Pt(20.0, 20.0)); selection.finish(Pt(bounds.right + 20, bounds.bottom + 20))
        assertSame(shape, selection.members.single().item); selection.copy(); assertTrue(selection.canPaste)
        val away = center + Pt(3000.0, 1000.0)
        selection.begin(center); selection.move(away); assertNotEquals(bounds, shape.bounds())
        panels.deleteLayer(panels.layers.activeId); selection.finish(away)
        assertFalse(editor.active); assertTrue(selection.members.isEmpty()); assertFalse(selection.canPaste); assertEquals(bounds, shape.bounds())
        assertTrue(model.items.isEmpty()); assertTrue(model.itemsIn(bounds).isEmpty())
        editor.replay(true); assertSame(shape, model.itemsIn(bounds).single())
        assertTrue(model.itemsIn(Rect(away.x, away.y, 1.0, 1.0)).isEmpty())
        editor.replay(true); assertTrue(model.items.isEmpty())
    }

    @Test fun layerVisibilityLockAndOrderSettleBoxBeforeTheirOwnUndoStep() {
        val changes: List<(DocumentPanels, String) -> Unit> = listOf(
            { panels, id -> panels.layers.update(id) { it.copy(visible = false) } },
            { panels, id -> panels.layers.update(id) { it.copy(locked = true) } },
            { panels, id -> panels.layers.move(id, -1) },
        )
        for (change in changes) withEditor(LiteKind.CANVAS) { editor ->
            val panels = editor.panels; panels.edit { panels.layers.create("Text") }
            val id = panels.layers.activeId
            editor.select(Tool.TEXT)
            val surface = TextSurface(editor, canvasSpace) { false }.apply { configure() }
            assertTrue(surface.start(origin, true)); surface.view.setMarkedText("字", NSMakeRange(1u, 0u))
            panels.edit { assertNull(surface.target); change(panels, id) }
            surface.view.insertText("late")
            val shape = assertIs<ShapeItem>(editor.session.canvas!!.items.single()); assertEquals("字", shape.label!!.text)
            editor.replay(true); assertTrue(surface.start(origin, false)); assertSame(shape, assertIs<BoxText>(surface.target).shape)
            editor.replay(true); assertTrue(editor.session.canvas!!.items.isEmpty())
        }
    }

    @Test fun switchingToolsAbortsInkAndEraserAndIgnoresTheirLateEvents() = withEditor(LiteKind.CANVAS) { editor ->
        val model = editor.session.canvas!!
        fun sample(x: Double) = PencilSample(x, 30.0, x / 1000, 1.0, 1.0, null, false)
        fun begin() = InkGesture(editor, canvasSpace, false).also { it.begin(sample(20.0)) }
        for (tool in listOf(Tool.PEN, Tool.HIGHLIGHTER)) {
            editor.select(tool); val action = begin()
            editor.select(Tool.LASSO); action.move(listOf(sample(24.0)), emptyList()); action.finish(listOf(sample(30.0)), false)
            assertEquals(Tool.LASSO, editor.tool); assertFalse(editor.active); assertFalse(editor.canUndo); assertTrue(model.items.isEmpty())
        }
        editor.select(Tool.PEN); begin().finish(listOf(sample(30.0)), false)
        val original = model.items.single(); editor.select(Tool.ERASER); val erasing = begin(); assertTrue(model.items.isEmpty())
        editor.select(Tool.TEXT); erasing.finish(listOf(sample(30.0)), false); assertSame(original, model.items.single())
        editor.replay(true); assertFalse(editor.canUndo); assertTrue(model.items.isEmpty())
    }
}
