package com.xnotes.canvas

import android.view.InputDevice
import android.view.MotionEvent
import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.history.History
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Stroke
import com.xnotes.core.tools.Tool
import com.xnotes.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class PagedStrokeCaptureHostTest {
    private val pages = mutableListOf(Page(300.0, 300.0), Page(300.0, 300.0))
    private val state = CanvasState(Document(pages), FakeSurfaceFactory(), Palette.DEFAULT).apply {
        viewportW = 800
        viewportH = 900
        relayout()
    }
    private val history = History()
    private val input = InteractionController(state, history, FakeTextMeasurer(), {}).apply { setTool(Tool.PEN) }
    private var time = 10L

    private fun stylus(action: Int, page: Int, local: Pt) {
        val at = state.contentToViewport(state.fromPageSpace(page, local))
        val pointer = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }
        val coords = MotionEvent.PointerCoords().apply { x = at.x.toFloat(); y = at.y.toFloat(); pressure = .5f }
        val event = MotionEvent.obtain(10L, time++, action, 1, arrayOf(pointer), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
        try { input.onTouch(event) } finally { event.recycle() }
    }

    private fun crossPageBoundary() {
        stylus(MotionEvent.ACTION_DOWN, 0, Pt(80.0, 280.0))
        stylus(MotionEvent.ACTION_MOVE, 1, Pt(80.0, 10.0))
        assertTrue((pages[0].items.single() as Stroke).finished)
        assertTrue(pages[1].items.isEmpty())
        assertFalse(history.canUndo)
    }

    @Test fun liftFilesBothSegmentsAsOneEdit() {
        crossPageBoundary()
        stylus(MotionEvent.ACTION_UP, 1, Pt(80.0, 50.0))
        assertTrue((pages[1].items.single() as Stroke).finished)
        history.undo()
        assertTrue(pages.all { it.items.isEmpty() })
        assertFalse(history.canUndo)
        history.redo()
        assertTrue(pages.all { it.items.size == 1 })
    }

    @Test fun cancelKeepsOnlyTheAlreadyFiledSegment() {
        crossPageBoundary()
        stylus(MotionEvent.ACTION_CANCEL, 1, Pt(80.0, 50.0))
        assertEquals(listOf(1, 0), pages.map { it.items.size })
        history.undo()
        assertTrue(pages.all { it.items.isEmpty() })
    }

    @Test fun rulerMaySnapTheFirstSampleOutsideItsHitPage() {
        val edge = Pt(80.0, pages[0].height + 2.0)
        val at = state.contentToViewport(state.fromPageSpace(0, edge))
        input.ruler.apply {
            visible = true
            thicknessPx = 60.0
            center = at + Pt(0.0, thicknessPx / 2.0)
        }
        stylus(MotionEvent.ACTION_DOWN, 0, Pt(edge.x, pages[0].height - 1.0))
        stylus(MotionEvent.ACTION_UP, 0, Pt(edge.x + 30.0, pages[0].height - 1.0))
        val stroke = pages[0].items.single() as Stroke
        assertEquals(edge.y, stroke.yAt(0), 1e-6)
        assertEquals(edge.y, stroke.yAt(stroke.sampleCount - 1), 1e-6)
        assertTrue(pages[1].items.isEmpty())
    }
}
