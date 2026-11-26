@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.xnotes.iosapp

import com.xnotes.core.geometry.*
import com.xnotes.core.infinite.Waypoint
import com.xnotes.core.model.*
import com.xnotes.core.pal.*
import com.xnotes.core.text.FlowLayout
import com.xnotes.core.tools.Tool
import com.xnotes.format.DocumentCodec
import com.xnotes.kio.*
import com.xnotes.platform.ios.*
import com.xnotes.storage.lite.*
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSMakeRange
import kotlin.math.abs
import kotlin.test.*

class EditingViewportTest {
    private fun withEditor(kind: LiteKind, test: (InkEditor) -> Unit) {
        val root = KFiles.tempDirectory() / Uuids.random()
        val docs = (root / "Documents").also(KFiles.system::createDirectories)
        try {
            val store = LiteStore(KFiles.system, docs, root / "Private", CgImageCodec, CgTextMeasurer)
            val editor = InkEditor(assertIs<LiteOpen.Opened>(store.create(docs, "Viewport", kind)).session, SaveWorker.Inline) {}
            try { editor.select(Tool.TEXT); test(editor) } finally { editor.releaseOwner() }
        } finally { KFiles.system.deleteRecursively(root) }
    }

    private fun TextSurface.caret() = target!!.let { assertNotNull(it.caret(it.input.selection.end)) }

    @Test fun tappingBelowFlowFillsToThirdPageAndUndoesOnlyTheFill() = withEditor(LiteKind.NOTE) { editor ->
        val note = editor.session.note!!
        editor.panels.add(false); editor.panels.add(false)
        val host = PagedView(editor, false) { fail("page rendering failed") }
        try {
            val surface = editor.textSurface!!
            assertTrue(surface.start(PagePlacement(note).places.first().center, false)); surface.view.insertText("first")
            val mark = surface.caret(); val original = note.flow.paragraphs.size
            val point = PagePlacement(note).places.last().center
            surface.hit(point); surface.drag(point)
            assertEquals(original, note.flow.paragraphs.size)
            assertTrue(surface.start(point, true))
            assertEquals(2, surface.caret().page)
            assertTrue(abs(surface.screen(surface.caret()).centerY - point.y) < FlowLayout(CgTextMeasurer).defaultSlotHeight(note.flow))
            val padded = note.flow.plainText()
            assertTrue(surface.start(surface.screen(mark).center, true)); assertEquals(padded, note.flow.plainText())
            editor.replay(true); assertEquals("first", note.flow.plainText()); assertEquals(3, note.pages.size)
        } finally { host.close() }
    }

    @Test fun overflowingEditAndAutoPagesReplayTogether() = withEditor(LiteKind.NOTE) { editor ->
        val note = editor.session.note!!
        val host = PagedView(editor, false) { fail("page rendering failed") }
        try {
            val surface = editor.textSurface!!
            assertTrue(surface.start(PagePlacement(note).places.single().center, false))
            val text = List(140) { "row" }.joinToString("\n")
            surface.view.insertText(text)
            val last = surface.caret().page
            assertTrue(last > 0); assertEquals(last + 1, note.pages.size)
            val pages = note.pages.toList()
            editor.replay(true); assertEquals(1, note.pages.size); assertEquals("", note.flow.plainText()); assertFalse(editor.canUndo)
            editor.replay(false); assertEquals(pages, note.pages); assertEquals(text, note.flow.plainText())
        } finally { host.close() }
    }

    @Test fun pagedCaretFollowsTypingAndViewportResizeWithoutZoom() = withEditor(LiteKind.NOTE) { editor ->
        val host = PagedView(editor, false) { fail("page rendering failed") }
        try {
            host.setFrame(CGRectMake(0.0, 0.0, 600.0, 300.0)); host.layoutSubviews()
            val zoom = host.zoomScale; val surface = editor.textSurface!!
            assertTrue(surface.start(Pt(70.0, 75.0), false))
            surface.view.insertText("line\n".repeat(24)); host.layoutSubviews()
            assertTrue(surface.screen(surface.caret()).bottom <= 277)
            host.setFrame(CGRectMake(0.0, 0.0, 600.0, 125.0)); host.layoutIfNeeded(); surface.view.layoutIfNeeded()
            assertTrue(surface.screen(surface.caret()).bottom <= 102)
            val visible = surface.screen(surface.caret()); surface.format("bold"); assertEquals(visible, surface.screen(surface.caret()))
            host.setContentOffset(CGPointMake(0.0, 0.0)); assertTrue(surface.screen(surface.caret()).bottom > 125)
            surface.format("italic"); assertTrue(surface.screen(surface.caret()).bottom <= 102)
            assertEquals(zoom, host.zoomScale)
        } finally { host.close() }
    }

    @Test fun composingAcrossLastPageShowsCaretButDoesNotPublishPreviewPages() = withEditor(LiteKind.NOTE) { editor ->
        val host = PagedView(editor, false) { fail("page rendering failed") }
        try {
            host.setFrame(CGRectMake(0.0, 0.0, 600.0, 360.0)); host.layoutSubviews()
            val surface = editor.textSurface!!; val note = editor.session.note!!
            assertTrue(surface.start(Pt(70.0, 75.0), false)); surface.view.insertText("saved")
            val composition = "\nmore".repeat(140)
            surface.view.setMarkedText(composition, NSMakeRange(composition.length.toULong(), 0u))
            surface.viewportChanged(Rect(0.0, 190.0, 600.0, 170.0))
            assertTrue(surface.caret().page > 0); assertTrue(surface.screen(surface.caret()).bottom <= 167)
            assertEquals(1, note.pages.size); editor.save(background = true)
            val stored = KFiles.system.read(editor.session.path) { DocumentCodec(CgImageCodec, CgTextMeasurer).read(this) }
            assertEquals("saved", stored.flow.plainText()); assertEquals(1, stored.pages.size)
            surface.view.unmarkText(); assertEquals(surface.caret().page + 1, note.pages.size)
            editor.replay(true); assertEquals("saved", note.flow.plainText()); assertEquals(1, note.pages.size)
        } finally { host.close() }
    }

    @Test fun keyboardFrameChangesRevealCaretAndRespectFloatingKeyboardPosition() = withEditor(LiteKind.CANVAS) { editor ->
        val host = CanvasView(editor, false) { fail("canvas rendering failed") }
        try {
            host.setFrame(CGRectMake(0.0, 0.0, 650.0, 430.0)); host.layoutSubviews()
            val surface = editor.textSurface!!
            assertTrue(surface.start(Pt(330.0, 350.0), true)); val first = surface.screen(surface.caret())
            val floating = Rect(first.right + 60.0, 150.0, 120.0, 220.0)
            surface.viewportChanged(floating); assertEquals(first, surface.screen(surface.caret()))
            for (top in listOf(260.0, 170.0)) {
                val keyboard = Rect(0.0, top, 650.0, 430.0 - top); surface.viewportChanged(keyboard)
                val visible = surface.screen(surface.caret()); assertEquals(top - 24, visible.bottom, 0.01)
                surface.viewportChanged(keyboard); assertEquals(visible, surface.screen(surface.caret()))
                surface.view.insertText("字"); surface.view.insertText("\n"); assertTrue(surface.screen(surface.caret()).bottom <= top - 23.9)
                surface.view.deleteBackward(); assertTrue(surface.screen(surface.caret()).bottom <= top - 23.9)
            }
        } finally { host.close() }
    }

    @Test fun canvasCaretFollowsMarkedAndCommittedTextWithoutZoom() = withEditor(LiteKind.CANVAS) { editor ->
        val host = CanvasView(editor, false) { fail("canvas rendering failed") }
        try {
            host.setFrame(CGRectMake(0.0, 0.0, 600.0, 240.0)); host.layoutSubviews()
            val surface = editor.textSurface!!
            val ruler = TextMark(0, Rect(0.0, 0.0, 100.0, 100.0)); val width = surface.screen(ruler).w
            assertTrue(surface.start(Pt(150.0, 215.0), true))
            assertTrue(surface.screen(surface.caret()).bottom <= 217)
            surface.view.setMarkedText("abc\ndef", NSMakeRange(7u, 0u)); assertTrue(surface.screen(surface.caret()).bottom <= 217)
            surface.view.insertText("word\n"); surface.view.deleteBackward(); assertTrue(surface.screen(surface.caret()).bottom <= 217)
            assertEquals(width, surface.screen(ruler).w)
        } finally { host.close() }
    }

    @Test fun pictureRendererAdaptsExactlyBlackIncludingAlphaAndGlyphs() {
        val rect = Rect(3.0, 2.0, 53.0, 30.0); val font = FontSpec(18.0)
        val operations = listOf<(Renderer, Rgba) -> Unit>(
            { renderer, color -> renderer.fillRect(rect, color) },
            { renderer, color -> renderer.strokeRect(rect, Pen(color, 3.0)) },
            { renderer, color -> renderer.drawText("Aa", rect, font, color) },
            { renderer, color -> renderer.drawTextTurned("Aa", rect, font, color, TextFlags(), 0.1) },
            { renderer, color -> renderer.drawTextRun("Aa", 4.0, 23.0, font, color) },
        )
        fun pixels(night: Boolean, color: Rgba, op: (Renderer, Rgba) -> Unit): List<Rgba> {
            val bitmap = CgSurfaceFactory.create(64, 40)
            return try {
                bitmap.fill(Rgba(0, 0, 0, 0)); op(bitmap.renderer(nightInk = night), color)
                List(64 * 40) { bitmap.pixel(it % 64, it / 64) }
            } finally { bitmap.recycle() }
        }
        for (op in operations) {
            val expected = pixels(false, white.withAlpha(95), op)
            assertTrue(expected.any { it.a > 0 })
            assertTrue(expected == pixels(true, black.withAlpha(95), op), "night picture differs from translucent white")
            val near = Rgba(1, 1, 1); assertTrue(pixels(false, near, op) == pixels(true, near, op))
        }
    }

    @Test fun canvasRecreationRetainsOriginAndZoomInsteadOfCenteringOrFittingAgain() = withEditor(LiteKind.CANVAS) { editor ->
        val doc = editor.session.canvas!!
        doc.lastView = Waypoint("", 423.0, -137.0, 2.5)
        val ruler = TextMark(0, Rect(0.0, 0.0, 100.0, 100.0))
        fun host(dark: Boolean, width: Double) = CanvasView(editor, dark) { fail("rendering failed") }.apply {
            setFrame(CGRectMake(0.0, 0.0, width, 300.0)); layoutSubviews()
        }
        val first = host(false, 430.0)
        val live = try {
            val surface = editor.textSurface!!; val before = surface.screen(ruler)
            assertEquals(250.0 / first.contentScaleFactor, before.w, 0.0001)
            assertTrue(surface.start(Pt(390.0, 285.0), true))
            surface.screen(ruler).also { assertNotEquals(before.topLeft, it.topLeft) }
        } finally { first.close() }
        for (dark in listOf(true, false)) {
            val next = host(dark, 510.0)
            try { assertEquals(live, editor.textSurface!!.screen(ruler)); assertFalse(editor.session.dirty) } finally { next.close() }
        }
    }
}
