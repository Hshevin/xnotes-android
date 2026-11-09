package com.xnotes.pen

import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import com.xnotes.canvas.CanvasState
import com.xnotes.canvas.InteractionController
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.history.History
import com.xnotes.core.model.Document
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Page
import com.xnotes.core.tools.Tool
import com.xnotes.platform.AndroidSurfaceFactory
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.ui.theme.Palette
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

/** Controller events only; no APK, GL surface or physical pen. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class DocumentResetHostTest : DocumentResetContract() {
    override fun runOnMain(block: () -> Unit) = block()

    @Test fun resetRetiresTheLongPressBorrowWithoutReplayingItOnClearSelection() {
        val item = ImageItem(ImageData(File("reset-long-press.png"), 10, 10), Rect(100.0, 100.0, 200.0, 120.0))
        val old = Document(mutableListOf(Page(500.0, 500.0, mutableListOf(item))))
        val next = Document(mutableListOf(Page(500.0, 500.0)))
        val state = CanvasState(old, AndroidSurfaceFactory(), Palette.DEFAULT).apply {
            viewportW = 1000; viewportH = 1000; zoom = 1.0; relayout()
        }
        val changes = ArrayList<Tool>()
        val input = InteractionController(state, History(), AndroidTextMeasurer(), {}, onToolChanged = { changes += it })
        input.setTool(Tool.PEN)
        changes.clear()
        val at = state.contentToViewport(state.fromPageSpace(0, Pt(200.0, 160.0)))
        val pointer = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val coordinates = MotionEvent.PointerCoords().apply { x = at.x.toFloat(); y = at.y.toFloat(); pressure = .5f }
        val event = MotionEvent.obtain(1L, 1L, MotionEvent.ACTION_DOWN, 1, arrayOf(pointer), arrayOf(coordinates),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { input.onTouch(event) } finally { event.recycle() }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(InteractionController.LONG_PRESS_MS + 1L))
        assertEquals(Tool.SELECT, input.tool)
        assertEquals(listOf(Tool.SELECT), changes)
        assertTrue(input.hasSelection)

        state.document = next
        state.relayout()
        input.resetGestureState()
        val resetTool = input.tool
        val callbacks = changes.toList()
        input.clearSelection()
        assertEquals(resetTool, input.tool)
        assertEquals(callbacks, changes)
        assertFalse(input.hasSelection)
        assertSame(item, old.pages.single().items.single())
        assertTrue(next.pages.single().items.isEmpty())
    }

    @Test fun resetStopsQueuedRoomAutoscrollFromReenteringTheNewDocument() {
        val old = Document(mutableListOf(Page(500.0, 1500.0)))
        val next = Document(mutableListOf(Page(500.0, 1500.0)))
        val state = CanvasState(old, AndroidSurfaceFactory(), Palette.DEFAULT).apply {
            viewportW = 1000; viewportH = 1000; zoom = 1.0; relayout()
        }
        val history = History()
        val input = InteractionController(state, history, AndroidTextMeasurer(), {})
        input.setTool(Tool.INSERT_SPACE)
        var time = 10L
        fun send(action: Int, at: Pt) {
            val pointer = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }
            val coordinates = MotionEvent.PointerCoords().apply { x = at.x.toFloat(); y = at.y.toFloat(); pressure = .5f }
            val event = MotionEvent.obtain(1L, time++, action, 1, arrayOf(pointer), arrayOf(coordinates),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
            try { input.onTouch(event) } finally { event.recycle() }
        }
        val from = state.contentToViewport(state.fromPageSpace(0, Pt(100.0, 250.0)))
        val edge = Pt(from.x, 990.0)
        send(MotionEvent.ACTION_DOWN, from)
        send(MotionEvent.ACTION_MOVE, edge)
        assertNotNull(state.roomView)
        assertTrue(state.roomView!!.live)

        state.document = next
        state.relayout()
        input.resetGestureState()
        val scroll = state.scrollY
        val revision = next.bodyRevision
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(96L))
        assertNull(state.roomView)
        assertEquals(scroll, state.scrollY, 0.0)
        assertEquals(revision, next.bodyRevision)
        assertTrue(next.pages.single().rooms.isEmpty())
        assertTrue(old.pages.single().rooms.isEmpty())
        assertFalse(history.canUndo)
        send(MotionEvent.ACTION_MOVE, edge)
        send(MotionEvent.ACTION_UP, edge)
        assertNull(state.roomView)
        assertFalse(next.dirty)
        assertFalse(old.dirty)
        assertFalse(history.canUndo)
    }
}
