package com.xnotes.pen

import org.junit.Assert.*
import org.junit.Test
import android.view.InputDevice
import android.view.MotionEvent
import com.xnotes.core.tools.Tool

class ProPenInputTest {
    private var tool = Tool.PEN
    private val commands = mutableListOf<String>()
    private val bindings = mapOf(
        0x20 to PenBinding("eraser", PenBindingMode.HOLD),
        0x40 to PenBinding("highlighter", PenBindingMode.SELECT),
        0x04 to PenBinding("undo", PenBindingMode.SELECT),
    )
    private fun shortcuts(bindings: Map<Int, PenBinding>) = bindings.map { (mask, binding) ->
        PenShortcut("Button $mask", listOf(PenTrigger(PenTrigger.Kind.MOTION, mask, "", InputDevice.SOURCE_STYLUS)), binding)
    }
    private val input = ProPenInput({ shortcuts(bindings) }, { tool }, { tool = it }, { commands.add(it) })

    private fun event(action: Int, buttons: Int, type: Int = MotionEvent.TOOL_TYPE_STYLUS): MotionEvent {
        val property = MotionEvent.PointerProperties().apply { id = 0; toolType = type }
        val coords = MotionEvent.PointerCoords().apply { x = 100f; y = 100f; pressure = 0.4f }
        val source = when (type) {
            MotionEvent.TOOL_TYPE_FINGER -> InputDevice.SOURCE_TOUCHSCREEN
            MotionEvent.TOOL_TYPE_MOUSE -> InputDevice.SOURCE_MOUSE
            else -> InputDevice.SOURCE_STYLUS
        }
        return MotionEvent.obtain(1, 2, action, 1, arrayOf(property), arrayOf(coords),
            0, buttons, 1f, 1f, 19, 0, source, 0)
    }

    private fun inherit(adapter: ProPenInput, e: MotionEvent, mask: Int) {
        val signals = PenSignals.motion(e, mask)
        val holds = signals.filter { it.code == 0x20 }
        adapter.inherit(PenInputSnapshot(signals, holds, e.deviceId))
    }

    private fun motion(action: Int, buttons: Int, type: Int = MotionEvent.TOOL_TYPE_STYLUS) {
        val e = event(action, buttons, type)
        try { input.motion(e) } finally { e.recycle() }
    }

    private fun touch(action: Int, buttons: Int, during: () -> Unit = {}) {
        val e = event(action, buttons)
        try { input.touch(e) { during(); true } } finally { e.recycle() }
    }

    @Test fun testHoverAndIndependentButtonEventsAreDeduplicated() {
        motion(MotionEvent.ACTION_HOVER_MOVE, 4)
        motion(MotionEvent.ACTION_BUTTON_PRESS, 4)
        motion(MotionEvent.ACTION_HOVER_MOVE, 4)
        assertEquals(listOf("undo"), commands)
        motion(MotionEvent.ACTION_BUTTON_RELEASE, 0)
        motion(MotionEvent.ACTION_HOVER_MOVE, 4)
        assertEquals(2, commands.size)
    }

    @Test fun testTouchKeepsCurrentStrokeToolUntilAfterUp() {
        touch(MotionEvent.ACTION_DOWN, 0)
        touch(MotionEvent.ACTION_MOVE, 0x40) { assertEquals(Tool.PEN, tool) }
        touch(MotionEvent.ACTION_UP, 0x40) { assertEquals(Tool.PEN, tool) }
        assertEquals(Tool.HIGHLIGHTER, tool)
    }

    @Test fun testCommandDuringStrokeRunsAfterCommitAndCancelDiscardsIt() {
        touch(MotionEvent.ACTION_DOWN, 0)
        touch(MotionEvent.ACTION_MOVE, 4) { assertTrue(commands.isEmpty()) }
        touch(MotionEvent.ACTION_UP, 4) { assertTrue(commands.isEmpty()) }
        assertEquals(1, commands.size)
        touch(MotionEvent.ACTION_DOWN, 0)
        touch(MotionEvent.ACTION_MOVE, 4)
        touch(MotionEvent.ACTION_CANCEL, 4)
        assertEquals(1, commands.size)
    }

    @Test fun testFingerAndMouseNeverTriggerBindings() {
        motion(MotionEvent.ACTION_HOVER_MOVE, 4, MotionEvent.TOOL_TYPE_MOUSE)
        motion(MotionEvent.ACTION_HOVER_MOVE, 0x20, MotionEvent.TOOL_TYPE_FINGER)
        assertEquals(Tool.PEN, tool)
        assertTrue(commands.isEmpty())
    }

    @Test fun testHoverExitBeforeDownDoesNotRepeatUndo() {
        motion(MotionEvent.ACTION_HOVER_MOVE, 4)
        motion(MotionEvent.ACTION_HOVER_EXIT, 4)
        touch(MotionEvent.ACTION_DOWN, 4)
        touch(MotionEvent.ACTION_UP, 4)
        assertEquals(1, commands.size)
    }

    @Test fun testFocusResetRestoresHold() {
        motion(MotionEvent.ACTION_HOVER_MOVE, 0x20)
        assertEquals(Tool.ERASER, tool)
        input.reset()
        assertEquals(Tool.PEN, tool)
    }
    @Test fun crossingWithHeldCommandDoesNotRepeatItButNextPressWorks() {
        val e = event(MotionEvent.ACTION_HOVER_ENTER, 4)
        try { inherit(input, e, 4); input.motion(e) } finally { e.recycle() }
        assertTrue(commands.isEmpty())
        motion(MotionEvent.ACTION_HOVER_MOVE, 4)
        assertTrue(commands.isEmpty())
        motion(MotionEvent.ACTION_HOVER_MOVE, 0)
        motion(MotionEvent.ACTION_HOVER_MOVE, 4)
        assertEquals(listOf("undo"), commands)
    }

    @Test fun crossingWithHeldEraserRestoresPenOnRelease() {
        val e = event(MotionEvent.ACTION_HOVER_ENTER, 0x20)
        try { inherit(input, e, 0x20); input.motion(e) } finally { e.recycle() }
        assertEquals(Tool.ERASER, tool)
        input.reset()
        assertEquals(Tool.PEN, tool)
        val returned = event(MotionEvent.ACTION_HOVER_ENTER, 0x20)
        try { inherit(input, returned, 0x20); input.motion(returned) } finally { returned.recycle() }
        assertEquals(Tool.ERASER, tool)
        motion(MotionEvent.ACTION_HOVER_MOVE, 0)
        assertEquals(Tool.PEN, tool)
    }

    @Test fun sameCommandOnANewPhysicalButtonStillRunsAcrossPanes() {
        val seen = mutableListOf<String>()
        val adapter = ProPenInput({ shortcuts(mapOf(0x20 to PenBinding("undo"), 0x40 to PenBinding("undo"))) },
            { Tool.PEN }, {}, { seen.add(it) })
        val e = event(MotionEvent.ACTION_HOVER_ENTER, 0x60)
        try { inherit(adapter, e, 0x20); adapter.motion(e) } finally { e.recycle() }
        assertEquals(listOf("undo"), seen)
    }

    @Test fun releaseInOtherPaneThenNewPressOnReturnIsNotSwallowed() {
        motion(MotionEvent.ACTION_HOVER_ENTER, 4)
        input.reset()
        // The other pane observed release; this pane has not seen that sample.
        val returned = event(MotionEvent.ACTION_HOVER_ENTER, 4)
        try { inherit(input, returned, 0); input.motion(returned) } finally { returned.recycle() }
        assertEquals(listOf("undo", "undo"), commands)
    }

}
