package com.xnotes.pen

import android.view.InputDevice
import android.view.MotionEvent
import com.xnotes.canvas.CanvasState
import com.xnotes.canvas.InteractionController
import com.xnotes.canvas.PdfTextController
import com.xnotes.canvas.PdfTextSource
import com.xnotes.core.geometry.Pt
import com.xnotes.core.history.History
import com.xnotes.core.infinite.CanvasViewport
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.pdf.PageText
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.shapes.ShapePresets
import com.xnotes.core.tools.ShapeConfig
import com.xnotes.core.tools.Tool
import com.xnotes.platform.AndroidSurfaceFactory
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.ui.InfiniteInteraction
import com.xnotes.ui.theme.Palette
import org.junit.Assert.*
import org.junit.Test

/** A model replacement drops the outgoing draft and tap sequence without editing either model. */
abstract class DocumentResetContract {
    protected abstract fun runOnMain(block: () -> Unit)
    private var clock = 1000L

    private fun send(touch: (MotionEvent) -> Boolean, action: Int, at: Pt, stylus: Boolean = false) {
        val property = MotionEvent.PointerProperties().apply {
            id = 0; toolType = if (stylus) MotionEvent.TOOL_TYPE_STYLUS else MotionEvent.TOOL_TYPE_FINGER
        }
        val coordinates = MotionEvent.PointerCoords().apply {
            x = at.x.toFloat(); y = at.y.toFloat(); pressure = .6f; size = .1f
        }
        val source = if (stylus) InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN
        val event = MotionEvent.obtain(1L, clock, action, 1, arrayOf(property), arrayOf(coordinates),
            0, 0, 1f, 1f, 0, 0, source, 0)
        clock += 10L
        try { assertTrue(touch(event)) } finally { event.recycle() }
    }

    private fun tap(touch: (MotionEvent) -> Boolean, at: Pt, stylus: Boolean = false) {
        send(touch, MotionEvent.ACTION_DOWN, at, stylus)
        send(touch, MotionEvent.ACTION_UP, at, stylus)
    }

    private fun state(document: Document) = CanvasState(document, AndroidSurfaceFactory(), Palette.DEFAULT).apply {
        viewportW = 1000; viewportH = 1000; zoom = 1.0; relayout()
    }

    private fun emptyNote() = Document(mutableListOf(Page(500.0, 500.0)))

    @Test fun pagedResetDropsOldPolygonBeforeChangingToolsOrWritingTheNewDocument() = runOnMain {
        val old = emptyNote()
        val next = emptyNote()
        val state = state(old)
        val history = History()
        var commits = 0
        val input = InteractionController(state, history, AndroidTextMeasurer(), {}, onContentChanged = { commits++ })
        input.shapeConfig = ShapeConfig(preset = ShapePresets.FREE_POLYGON)
        input.setTool(Tool.SHAPE)
        fun at(local: Pt) = state.contentToViewport(state.fromPageSpace(0, local))
        tap(input::onTouch, at(Pt(100.0, 100.0)), stylus = true)
        tap(input::onTouch, at(Pt(240.0, 180.0)), stylus = true)
        assertTrue(old.pages.single().items.isEmpty())
        assertFalse(history.canUndo)
        assertEquals(0, commits)

        state.document = next
        state.relayout()
        history.clear()
        input.resetGestureState()
        input.setTool(Tool.PEN)
        assertTrue(old.pages.single().items.isEmpty())
        assertTrue(next.pages.single().items.isEmpty())
        assertFalse(old.dirty)
        assertFalse(next.dirty)
        assertFalse(history.canUndo)
        assertEquals(0, commits)

        send(input::onTouch, MotionEvent.ACTION_DOWN, at(Pt(120.0, 280.0)), stylus = true)
        send(input::onTouch, MotionEvent.ACTION_MOVE, at(Pt(200.0, 310.0)), stylus = true)
        send(input::onTouch, MotionEvent.ACTION_UP, at(Pt(270.0, 330.0)), stylus = true)
        assertTrue(old.pages.single().items.isEmpty())
        assertTrue(next.pages.single().items.single() is Stroke)
        assertEquals(1, commits)
        history.undo()
        assertTrue(next.pages.single().items.isEmpty())
    }

    @Test fun canvasResetDropsOldPolygonBeforeChangingToolsOrWritingTheNewDocument() = runOnMain {
        val old = InfiniteDocument()
        val next = InfiniteDocument()
        var current = old
        var commits = 0
        var pending: CanvasItem? = null
        val viewport = CanvasViewport().apply { widthPx = 1000; heightPx = 1000 }
        val input = InfiniteInteraction(viewport, {},
            shapeConfig = { ShapeConfig(preset = ShapePresets.FREE_POLYGON) },
            onPendingShape = { pending = it },
            onCommitShape = { current.add(it); commits++ },
            onCommitStroke = { current.add(it); commits++ }).apply { tool = Tool.SHAPE }
        tap(input::onTouch, Pt(100.0, 100.0), stylus = true)
        tap(input::onTouch, Pt(240.0, 180.0), stylus = true)
        assertNotNull(pending)
        assertTrue(old.items.isEmpty())
        assertEquals(0, commits)

        current = next
        input.resetGestureState()
        input.tool = Tool.PEN
        assertNull(pending)
        assertTrue(old.items.isEmpty())
        assertTrue(next.items.isEmpty())
        assertFalse(old.dirty)
        assertFalse(next.dirty)
        assertEquals(0, commits)

        send(input::onTouch, MotionEvent.ACTION_DOWN, Pt(120.0, 280.0), stylus = true)
        send(input::onTouch, MotionEvent.ACTION_MOVE, Pt(200.0, 310.0), stylus = true)
        send(input::onTouch, MotionEvent.ACTION_UP, Pt(270.0, 330.0), stylus = true)
        assertTrue(old.items.isEmpty())
        assertTrue(next.items.single() is Stroke)
        assertEquals(1, commits)
    }

    @Test fun canvasResetSeparatesTapSequencesButStillAcceptsNewDocumentDoubleTaps() = runOnMain {
        var doubleTaps = 0
        val input = InfiniteInteraction(CanvasViewport().apply { widthPx = 1000; heightPx = 1000 }, {},
            onDoubleTap = { _, _, _ -> doubleTaps++ }).apply { tool = Tool.PAN }
        val at = Pt(200.0, 200.0)
        tap(input::onTouch, at)
        input.resetGestureState()
        tap(input::onTouch, at)
        assertEquals(0, doubleTaps)
        clock += InteractionController.DOUBLE_TAP_MS + 50L
        tap(input::onTouch, at)
        tap(input::onTouch, at)
        assertEquals(1, doubleTaps)
    }

    @Test fun pagedResetDoesNotTurnFirstNewDocumentTapIntoShapeLabelEditing() = runOnMain {
        val old = emptyNote()
        val next = emptyNote()
        next.pages.single().items += ShapeItem(ShapeKind.RECTANGLE, Pt(100.0, 100.0),
            Pt(300.0, 250.0), Rgba(0, 0, 0, 255))
        val state = state(old)
        var labelsOpened = 0
        val input = InteractionController(state, History(), AndroidTextMeasurer(), {},
            onTextEditStart = { if (it != null) labelsOpened++ })
        // With finger drawing off, PEN lets fingers pan without selecting the shape. That makes
        // a normal two-tap label edit observable through the public text-editor state.
        input.setTool(Tool.PEN)
        fun at() = state.contentToViewport(state.fromPageSpace(0, Pt(200.0, 180.0)))
        tap(input::onTouch, at())
        state.document = next
        state.relayout()
        input.resetGestureState()
        tap(input::onTouch, at())
        assertEquals(0, labelsOpened)
        assertFalse(input.isEditingText)
        assertFalse(input.hasSelection)
        clock += InteractionController.DOUBLE_TAP_MS + 50L
        tap(input::onTouch, at())
        tap(input::onTouch, at())
        assertEquals(1, labelsOpened)
        assertTrue(input.isEditingText)
        assertTrue(old.pages.single().items.isEmpty())
    }

    @Test fun pagedResetInvalidatesDelayedPdfWordAndMarkupRequests() = runOnMain {
        val text = PageText(intArrayOf('a'.code, 'b'.code, 'c'.code),
            floatArrayOf(10f, 20f, 16f, 32f, 16f, 20f, 22f, 32f, 22f, 20f, 28f, 32f), ByteArray(3), null)
        for (mark in listOf(false, true)) {
            val old = Document(mutableListOf(Page(500.0, 500.0, pdfPage = 0)), dpi = 72)
            val next = Document(mutableListOf(Page(500.0, 500.0, pdfPage = 0)), dpi = 72)
            val state = state(old)
            var held: PageText? = null
            var late: ((PageText?) -> Unit)? = null
            var effects = 0
            val source = object : PdfTextSource {
                override fun peek(page: Int) = held
                override fun prefetch(page: Int) = Unit
                override fun request(page: Int, onReady: (PageText?) -> Unit) { late = onReady }
            }
            val pdf = PdfTextController(state, source, {}, {}).apply {
                onSettled = { effects++ }; onMarked = { effects++ }; onHaptic = { effects++ }
            }
            val input = InteractionController(state, History(), AndroidTextMeasurer(), {}).apply { pdfText = pdf }
            if (mark) pdf.beginToolDrag(0, Pt(12.0, 26.0), mark = true)
            else pdf.longPress(0, Pt(12.0, 26.0)) { effects++ }
            assertNotNull(late)
            assertNull(pdf.selection)
            state.document = next
            state.relayout()
            input.resetGestureState()
            held = text
            late!!.invoke(text)
            pdf.release()
            assertNull(pdf.selection)
            assertFalse(pdf.marking)
            assertEquals(0, effects)
            assertTrue(old.pages.single().markups.isEmpty())
            assertTrue(next.pages.single().markups.isEmpty())
            // This source and location really can select a word after the reset.
            pdf.longPress(0, Pt(12.0, 26.0)) { fail("fresh PDF word was not found") }
            assertNotNull(pdf.selection)
            assertFalse(pdf.selection!!.isEmpty)
            pdf.clear()
        }
    }

    @Test fun pagedResetDropsOldRoomPreviewAndLateEventsBeforeFreshRoomEditing() = runOnMain {
        val old = Document(mutableListOf(Page(500.0, 1500.0)))
        val next = Document(mutableListOf(Page(500.0, 1500.0)))
        val state = state(old)
        val history = History()
        var commits = 0
        val input = InteractionController(state, history, AndroidTextMeasurer(), {}, onContentChanged = { commits++ })
        input.setTool(Tool.INSERT_SPACE)
        fun at(local: Pt) = state.contentToViewport(state.fromPageSpace(0, local))
        val from = at(Pt(100.0, 250.0))
        val edge = Pt(from.x, 990.0) // starts the real bottom-edge autoscroll path
        send(input::onTouch, MotionEvent.ACTION_DOWN, from, stylus = true)
        send(input::onTouch, MotionEvent.ACTION_MOVE, edge, stylus = true)
        assertNotNull(state.roomView)
        assertTrue(state.roomView!!.live)
        assertSame(old.pages.single(), state.roomView!!.page)
        assertTrue(old.pages.single().rooms.isEmpty())

        state.document = next
        state.relayout()
        input.resetGestureState()
        assertNull(state.roomView)
        val scroll = state.scrollY
        send(input::onTouch, MotionEvent.ACTION_MOVE, edge, stylus = true)
        send(input::onTouch, MotionEvent.ACTION_UP, edge, stylus = true)
        assertNull(state.roomView)
        assertEquals(scroll, state.scrollY, 0.0)
        assertTrue(old.pages.single().rooms.isEmpty())
        assertTrue(next.pages.single().rooms.isEmpty())
        assertFalse(old.dirty)
        assertFalse(next.dirty)
        assertFalse(history.canUndo)
        assertEquals(0, commits)

        send(input::onTouch, MotionEvent.ACTION_DOWN, at(Pt(100.0, 300.0)), stylus = true)
        send(input::onTouch, MotionEvent.ACTION_MOVE, at(Pt(100.0, 420.0)), stylus = true)
        assertNotNull(state.roomView)
        assertSame(next.pages.single(), state.roomView!!.page)
        send(input::onTouch, MotionEvent.ACTION_UP, at(Pt(100.0, 420.0)), stylus = true)
        assertTrue(next.pages.single().rooms.isNotEmpty())
        assertTrue(old.pages.single().rooms.isEmpty())
        assertEquals(1, commits)
        assertTrue(history.canUndo)
    }
}
