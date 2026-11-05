package com.xnotes.pen

import org.junit.Assert.*
import org.junit.Test

class ProPenButtonsTest {
    private val bindings = mapOf(
        0x20 to PenBinding("eraser", PenBindingMode.HOLD),
        0x40 to PenBinding("lasso", PenBindingMode.SELECT),
        0x04 to PenBinding("undo", PenBindingMode.SELECT),
    )

    @Test fun allThreeMasksAreIndependentAndRepeatedMotionIsDeduplicated() {
        val p = ProPenButtons("pen")
        assertEquals("eraser", p.update(0x20, bindings).tool)
        assertEquals("pen", p.update(0, bindings).tool)
        assertEquals("lasso", p.update(0x40, bindings).tool)
        assertEquals("lasso", p.update(0, bindings).tool)
        assertEquals(listOf("undo"), p.update(0x04, bindings).commands)
        assertTrue(p.update(0x04, bindings).commands.isEmpty())
        p.update(0, bindings)
        assertEquals(listOf("undo"), p.update(0x04, bindings).commands)
    }

    @Test fun selectAndExplicitSelectionSupersedeOlderHold() {
        val p = ProPenButtons("pen")
        p.update(0x20, bindings)
        assertEquals("lasso", p.update(0x60, bindings).tool)
        assertEquals("lasso", p.update(0x40, bindings).tool)
        p.update(0, bindings)
        p.update(0x20, bindings)
        p.select("highlighter")
        assertEquals("highlighter", p.update(0, bindings).tool)
    }

    @Test fun overlappingHoldsRestoreBaseRegardlessOfReleaseOrder() {
        val b = bindings + (0x40 to PenBinding("pan", PenBindingMode.HOLD))
        val p = ProPenButtons("pen")
        p.update(0x20, b)
        assertEquals("pan", p.update(0x60, b).tool)
        assertEquals("pan", p.update(0x40, b).tool)
        assertEquals("pen", p.update(0, b).tool)
        p.update(0x20, b)
        p.update(0x60, b)
        assertEquals("eraser", p.update(0x20, b).tool)
        assertEquals("pen", p.update(0, b).tool)
    }

    @Test fun resetRestoresBaseAndDoesNotRetriggerAStillHeldButton() {
        val p = ProPenButtons("pen")
        p.update(0x20, bindings)
        assertEquals("pen", p.reset().tool)
        assertEquals("pen", p.update(0x20, bindings).tool)
        p.update(0, bindings)
        assertEquals("eraser", p.update(0x20, bindings).tool)
    }

    @Test fun hoverExitRestoresToolButContactDoesNotRepeatCommand() {
        val p = ProPenButtons("pen")
        p.update(0x20, bindings)
        assertEquals("pen", p.suspend().tool)
        assertEquals("eraser", p.update(0x20, bindings).tool)
        p.update(0, bindings)
        p.update(0x04, bindings)
        p.suspend()
        assertTrue(p.update(0x04, bindings).commands.isEmpty())
    }

    private fun press(p: ProPenButtons, b: Map<Int, PenBinding>, at: Long): PenButtonResult =
        p.update(0x20, b, at).also { p.update(0, b, at + 50) }

    @Test fun doublePressRunsOnlyTheDoubleAction() {
        val b = mapOf(0x20 to PenBinding("undo", PenBindingMode.SELECT, double = "redo"))
        val p = ProPenButtons("pen") { 300L }
        assertTrue(press(p, b, 1000).commands.isEmpty())
        assertEquals(PenButtonResult("pen", listOf("redo")), press(p, b, 1200))
        // The pair is spent: a third press starts a new wait, and runs undo once the window closes.
        assertTrue(press(p, b, 1400).commands.isEmpty())
        assertEquals(1700L, p.nextDeadline)
        assertTrue(p.expire(1700).commands.isEmpty())
        assertEquals(listOf("undo"), p.expire(1701).commands)
        assertNull(p.nextDeadline)
    }

    @Test fun slowSecondPressRunsTheSingleActionFirst() {
        val b = mapOf(0x20 to PenBinding("undo", PenBindingMode.SELECT, double = "redo"))
        val p = ProPenButtons("pen") { 300L }
        press(p, b, 1000)
        // The next edge closes the old window before it starts its own.
        assertEquals(listOf("undo"), p.update(0x20, b, 1400).commands)
        p.update(0, b, 1450)
        assertEquals(listOf("redo"), press(p, b, 1600).commands)
    }

    @Test fun holdStartsAtOnceAndASecondPressRunsTheDoubleInstead() {
        val b = mapOf(0x20 to PenBinding("eraser", PenBindingMode.HOLD, double = "toggle_previous"))
        val p = ProPenButtons("pen") { 300L }
        assertEquals("eraser", p.update(0x20, b, 1000).tool)
        assertEquals("pen", p.update(0, b, 1100).tool)
        assertEquals(PenButtonResult("pen", listOf("toggle_previous")), p.update(0x20, b, 1200))
        assertNull(p.nextDeadline) // nothing is held back for a hold
        p.update(0, b, 1250)
        assertEquals("eraser", p.update(0x20, b, 2000).tool)
    }

    @Test fun pickedToolWaitsAndDoubleCanPickAnother() {
        val b = mapOf(0x40 to PenBinding("lasso", PenBindingMode.SELECT, double = "highlighter"))
        val p = ProPenButtons("pen") { 300L }
        assertEquals("pen", p.update(0x40, b, 10).tool)
        p.update(0, b, 50)
        assertEquals("lasso", p.expire(400).tool)
        p.update(0x40, b, 500); p.update(0, b, 550)
        assertEquals("highlighter", p.update(0x40, b, 600).tool)
    }

    @Test fun resetForgetsAPressWaitingForItsSecond() {
        val b = mapOf(0x20 to PenBinding("undo", PenBindingMode.SELECT, double = "redo"))
        val p = ProPenButtons("pen") { 300L }
        press(p, b, 1000)
        p.reset()
        assertNull(p.nextDeadline)
        assertTrue(p.expire(5000).commands.isEmpty())
    }

    @Test fun buttonsWithoutADoubleActionStillActImmediately() {
        val b = mapOf(0x20 to PenBinding("toggle_previous", PenBindingMode.SELECT))
        val p = ProPenButtons("pen") { 300L }
        assertEquals(listOf("toggle_previous"), press(p, b, 1000).commands)
        assertEquals(listOf("toggle_previous"), press(p, b, 1100).commands)
    }

    @Test fun unknownBitsAndUnboundButtonsHaveNoAction() {
        val p = ProPenButtons("pen")
        assertEquals(PenButtonResult("pen"), p.update(0x100, bindings))
        assertEquals(PenButtonResult("pen"), p.update(0x64, emptyMap()))
    }
}
