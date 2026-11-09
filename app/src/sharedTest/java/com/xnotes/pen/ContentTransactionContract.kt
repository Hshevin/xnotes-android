package com.xnotes.pen

import android.view.InputDevice
import android.view.MotionEvent
import com.xnotes.canvas.CanvasState
import com.xnotes.canvas.FlowTextController
import com.xnotes.canvas.InteractionController
import com.xnotes.core.geometry.Pt
import com.xnotes.core.history.History
import com.xnotes.core.infinite.CanvasViewport
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.text.FlowPos
import com.xnotes.core.text.FlowRange
import com.xnotes.core.text.FlowEditor
import com.xnotes.core.text.Paragraph
import com.xnotes.core.text.Run
import com.xnotes.core.tools.Tool
import com.xnotes.platform.AndroidSurfaceFactory
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.ui.InfiniteInteraction
import com.xnotes.ui.theme.Palette
import org.junit.Assert.*
import org.junit.Test

/** Stable capture waits for uncommitted content, not an open caret or view navigation. */
abstract class ContentTransactionContract {
    protected abstract fun runOnMain(block: () -> Unit)
    private var time = 1L
    private val gray = Rgba(128, 128, 128, 255)

    private fun send(touch: (MotionEvent) -> Boolean, action: Int, at: Pt, stylus: Boolean = true) {
        val pointer = MotionEvent.PointerProperties().apply {
            id = 0; toolType = if (stylus) MotionEvent.TOOL_TYPE_STYLUS else MotionEvent.TOOL_TYPE_FINGER
        }
        val coords = MotionEvent.PointerCoords().apply { x = at.x.toFloat(); y = at.y.toFloat(); pressure = .5f }
        val event = MotionEvent.obtain(1L, time++, action, 1, arrayOf(pointer), arrayOf(coords), 0, 0,
            1f, 1f, 0, 0, if (stylus) InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { assertTrue(touch(event)) } finally { event.recycle() }
    }

    private fun state() = CanvasState(Document(mutableListOf(Page(500.0, 500.0))),
        AndroidSurfaceFactory(), Palette.DEFAULT).apply { viewportW = 1000; viewportH = 1000; relayout() }

    private fun shape() = ShapeItem(ShapeKind.RECTANGLE, Pt(100.0, 100.0), Pt(260.0, 240.0),
        Rgba(0, 0, 0, 255), fillRgba = Rgba(64, 64, 64, 255))

    @Test fun pagedStrokeSignalsItsSettledBoundaryAndNavigationDoesNotBlockCapture() = runOnMain {
        val state = state()
        val input = InteractionController(state, History(), AndroidTextMeasurer(), {})
        input.setTool(Tool.PEN)
        val seen = ArrayList<Boolean>()
        input.onContentTransactionChanged = { seen += input.hasPendingContentTransaction }
        val from = state.contentToViewport(state.fromPageSpace(0, Pt(100.0, 100.0)))
        send(input::onTouch, MotionEvent.ACTION_DOWN, from)
        assertTrue(input.hasPendingContentTransaction)
        send(input::onTouch, MotionEvent.ACTION_MOVE, from + Pt(80.0, 50.0))
        send(input::onTouch, MotionEvent.ACTION_UP, from + Pt(120.0, 80.0))
        assertFalse(input.hasPendingContentTransaction)
        assertEquals(false, seen.last())
        input.setTool(Tool.PAN)
        send(input::onTouch, MotionEvent.ACTION_DOWN, from, stylus = false)
        assertFalse(input.hasPendingContentTransaction)
        send(input::onTouch, MotionEvent.ACTION_CANCEL, from, stylus = false)
    }

    @Test fun canvasStrokeCancelSignalsTheStableBoundary() = runOnMain {
        val input = InfiniteInteraction(CanvasViewport().apply { widthPx = 1000; heightPx = 1000 }, {}).apply { tool = Tool.PEN }
        val seen = ArrayList<Boolean>()
        input.onContentTransactionChanged = { seen += input.hasPendingContentTransaction }
        send(input::onTouch, MotionEvent.ACTION_DOWN, Pt(100.0, 100.0))
        assertTrue(input.hasPendingContentTransaction)
        send(input::onTouch, MotionEvent.ACTION_CANCEL, Pt(100.0, 100.0))
        assertFalse(input.hasPendingContentTransaction)
        assertEquals(false, seen.last())
        input.tool = Tool.PAN
        send(input::onTouch, MotionEvent.ACTION_DOWN, Pt(100.0, 100.0), stylus = false)
        assertFalse(input.hasPendingContentTransaction)
        send(input::onTouch, MotionEvent.ACTION_CANCEL, Pt(100.0, 100.0), stylus = false)
    }

    @Test fun pagedUnchangedLabelDoesNotBlockButActualDraftDoesUntilConfirmed() = runOnMain {
        val state = state()
        val shape = shape()
        state.document.pages.single().items += shape
        val input = InteractionController(state, History(), AndroidTextMeasurer(), {})
        input.selectPlaced(0, listOf(shape))
        input.labelSelection()
        assertTrue(input.isEditingLabel)
        assertFalse(input.hasPendingContentTransaction)
        val seen = ArrayList<Boolean>()
        input.onContentTransactionChanged = { seen += input.hasPendingContentTransaction }
        input.updateEditingText("draft")
        assertTrue(input.hasPendingContentTransaction)
        input.updateEditingText("")
        assertFalse(input.hasPendingContentTransaction)
        input.updateEditingText("confirmed")
        input.commitTextEdit()
        assertFalse(input.hasPendingContentTransaction)
        assertEquals(false, seen.last())
        assertEquals("confirmed", shape.label!!.text)
    }

    @Test fun pagedClearingSelectionSettlesFillPreviewWithoutChangingItsColor() = runOnMain {
        val state = state()
        val shape = shape()
        val original = shape.fillRgba
        state.document.pages.single().items += shape
        val history = History()
        val input = InteractionController(state, history, AndroidTextMeasurer(), {})
        input.selectPlaced(0, listOf(shape))
        input.refillSelection(gray, preview = true)
        assertTrue(input.hasPendingContentTransaction)
        input.clearSelection()
        assertFalse(input.hasPendingContentTransaction)
        assertEquals(gray, shape.fillRgba)
        assertTrue(history.canUndo)
        history.undo()
        assertEquals(original, shape.fillRgba)
        assertFalse(history.canUndo)
        history.redo()
        assertEquals(gray, shape.fillRgba)
    }

    @Test fun flowOnlyBlocksForItsActualBurstAndSignalsNoOpFlushes() = runOnMain {
        val state = state()
        state.document.flow.paragraphs += Paragraph()
        val history = History()
        val flow = FlowTextController(state, history, { state.document.flow }, { null }, { 0.0 },
            {}, {}, {}, {}, {})
        val seen = ArrayList<Boolean>()
        flow.onTransactionChanged = { seen += flow.hasPendingBurst }
        flow.startSession(FlowRange.caret(FlowPos.START))
        assertTrue(flow.active)
        assertFalse(flow.hasPendingBurst)
        flow.applyReplace(FlowRange.caret(FlowPos.START), "")
        assertTrue(flow.hasPendingBurst)
        flow.flushBurst()
        assertFalse(flow.hasPendingBurst)
        assertFalse(history.canUndo)
        assertEquals(false, seen.last())
        flow.applyReplace(FlowRange.caret(FlowPos.START), "a")
        assertTrue(flow.hasPendingBurst)
        flow.flushBurst()
        assertFalse(flow.hasPendingBurst)
        assertTrue(history.canUndo)
        assertEquals(false, seen.last())
        flow.endSession()
    }

    @Test fun flowExplicitFlushSettlesAnAppliedPrefixWithoutClosingOrMovingTheCaret() = runOnMain {
        val state = state()
        val paragraph = Paragraph(mutableListOf(Run("/h")))
        state.document.flow.paragraphs += paragraph
        val history = History()
        val input = FlowTextController(state, history, { state.document.flow }, { null }, { 0.0 },
            {}, {}, {}, {}, {})
        input.startSession(FlowRange.caret(FlowPos(0, 2)))
        val (prefix, caret) = FlowEditor(state.document.flow).replaceRange(
            FlowRange(FlowPos.START, FlowPos(0, 2)), "")
        assertNotNull(prefix)
        input.placeCaret(caret)
        input.pendingPrefix = prefix
        val selection = input.selection
        val seen = ArrayList<Boolean>()
        input.onTransactionChanged = { seen += input.hasPendingBurst }
        assertTrue(input.hasPendingBurst)
        input.flushPendingEdits()
        assertFalse(input.hasPendingBurst)
        assertTrue(input.active)
        assertEquals(selection, input.selection)
        assertEquals(false, seen.last())
        assertTrue(history.canUndo)
        history.undo()
        assertEquals("/h", paragraph.runs.joinToString("") { it.text })
        assertFalse(history.canUndo)
        history.redo()
        assertEquals("", paragraph.runs.joinToString("") { it.text })
        input.endSession()
    }
}
