package com.xnotes.pen.pencil

import com.xnotes.pen.pencil.PencilGesture.DOUBLE_TAP
import com.xnotes.pen.pencil.PencilGesture.SQUEEZE
import com.xnotes.pen.pencil.PencilPreferredAction.IGNORE
import com.xnotes.pen.pencil.PencilPreferredAction.RUN_SYSTEM_SHORTCUT
import com.xnotes.pen.pencil.PencilPreferredAction.SHOW_COLOR_PALETTE
import com.xnotes.pen.pencil.PencilPreferredAction.SHOW_CONTEXTUAL_PALETTE
import com.xnotes.pen.pencil.PencilPreferredAction.SHOW_INK_ATTRIBUTES
import com.xnotes.pen.pencil.PencilPreferredAction.SWITCH_ERASER
import com.xnotes.pen.pencil.PencilPreferredAction.SWITCH_PREVIOUS
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Contract for Apple Pencil double-tap and squeeze (docs/movinkpad/process/ipados-pencil-input-contract-2026-11-23.md).
 * The host calls [PencilGestures.onGesture] once per double tap and once per squeeze that reached `.ended`.
 */
class PencilGesturesTest {

    private val follow = PencilBinding.FollowSystem

    @Test
    fun followSystemSwitchEraserTogglesBetweenEraserAndTheToolBefore() {
        val g = PencilGestures("pen")
        assertEquals(PencilOutcome("eraser"), g.onGesture(DOUBLE_TAP, SWITCH_ERASER, follow, strokeActive = false))
        assertEquals(PencilOutcome("pen"), g.onGesture(DOUBLE_TAP, SWITCH_ERASER, follow, strokeActive = false))
    }

    @Test
    fun followSystemSwitchPreviousReturnsToTheLastManuallyChosenTool() {
        val g = PencilGestures("pen")
        g.manualSelect("highlighter")
        assertEquals(PencilOutcome("pen"), g.onGesture(DOUBLE_TAP, SWITCH_PREVIOUS, follow, false))
        assertEquals(PencilOutcome("highlighter"), g.onGesture(DOUBLE_TAP, SWITCH_PREVIOUS, follow, false))
    }

    @Test
    fun switchPreviousWithNoHistoryKeepsTheTool() {
        val g = PencilGestures("pen")
        assertEquals(PencilOutcome("pen"), g.onGesture(DOUBLE_TAP, SWITCH_PREVIOUS, follow, false))
    }

    @Test
    fun paletteActionsOpenTheToolPalette() {
        for (preferred in listOf(SHOW_COLOR_PALETTE, SHOW_INK_ATTRIBUTES, SHOW_CONTEXTUAL_PALETTE)) {
            val g = PencilGestures("pen")
            assertEquals(
                PencilOutcome("pen", listOf(PencilGestures.SHOW_TOOL_PALETTE)),
                g.onGesture(SQUEEZE, preferred, follow, false),
                preferred.name,
            )
        }
    }

    @Test
    fun ignoreAndSystemShortcutDoNothing() {
        val g = PencilGestures("pen")
        assertEquals(PencilOutcome("pen"), g.onGesture(DOUBLE_TAP, IGNORE, follow, false))
        assertEquals(PencilOutcome("pen"), g.onGesture(SQUEEZE, RUN_SYSTEM_SHORTCUT, follow, false))
    }

    @Test
    fun userBindingOverridesTheSystemPreference() {
        val g = PencilGestures("pen")
        assertEquals(PencilOutcome("lasso"), g.onGesture(SQUEEZE, SWITCH_ERASER, PencilBinding.Tool("lasso"), false))
        assertEquals(PencilOutcome("lasso", listOf("undo")), g.onGesture(DOUBLE_TAP, SWITCH_ERASER, PencilBinding.Command("undo"), false))
        assertEquals(PencilOutcome("lasso"), g.onGesture(DOUBLE_TAP, SWITCH_ERASER, PencilBinding.None, false))
        // Binding a tool makes it the formal selection, so "previous" now returns to the pen.
        assertEquals(PencilOutcome("pen"), g.onGesture(DOUBLE_TAP, SWITCH_PREVIOUS, follow, false))
    }

    @Test
    fun toggleCommandsChangeToolsRatherThanReachingTheHost() {
        val g = PencilGestures("pen")
        assertEquals(PencilOutcome("eraser"), g.onGesture(DOUBLE_TAP, IGNORE, PencilBinding.Command("toggle_eraser"), false))
        assertEquals(PencilOutcome("pen"), g.onGesture(DOUBLE_TAP, IGNORE, PencilBinding.Command("toggle_previous"), false))
        assertEquals(PencilOutcome("pan"), g.onGesture(DOUBLE_TAP, IGNORE, PencilBinding.Command("toggle_pan"), false))
        assertEquals(PencilOutcome("pen"), g.onGesture(DOUBLE_TAP, IGNORE, PencilBinding.Command("toggle_pan"), false))
    }

    @Test
    fun gesturesDuringAStrokeWaitForANormalPenUp() {
        val g = PencilGestures("pen")
        assertEquals(PencilOutcome("pen"), g.onGesture(DOUBLE_TAP, SWITCH_ERASER, follow, strokeActive = true))
        assertEquals(PencilOutcome("pen"), g.onGesture(DOUBLE_TAP, IGNORE, PencilBinding.Command("undo"), strokeActive = true))
        assertEquals("pen", g.tool, "no tool switch mid-stroke")
        assertEquals(PencilOutcome("eraser", listOf("undo")), g.onStrokeEnd(cancelled = false))
        assertEquals(PencilOutcome("eraser"), g.onStrokeEnd(cancelled = false), "queue is applied once")
    }

    @Test
    fun aCancelledStrokeDropsEveryQueuedGesture() {
        val g = PencilGestures("pen")
        g.onGesture(DOUBLE_TAP, SWITCH_ERASER, follow, strokeActive = true)
        g.onGesture(SQUEEZE, SHOW_COLOR_PALETTE, follow, strokeActive = true)
        assertEquals(PencilOutcome("pen"), g.onStrokeEnd(cancelled = true))
        assertEquals("pen", g.tool)
    }

    @Test
    fun manualSelectionWhileQueuedStillAppliesQueueOnTopOfIt() {
        val g = PencilGestures("pen")
        g.onGesture(DOUBLE_TAP, SWITCH_ERASER, follow, strokeActive = true)
        g.manualSelect("highlighter")
        assertEquals(PencilOutcome("eraser"), g.onStrokeEnd(cancelled = false))
        assertEquals(PencilOutcome("highlighter"), g.onGesture(DOUBLE_TAP, SWITCH_ERASER, follow, false))
    }

    @Test
    fun unknownToolOrCommandIdsAreIgnored() {
        val g = PencilGestures("pen")
        assertEquals(PencilOutcome("pen"), g.onGesture(DOUBLE_TAP, IGNORE, PencilBinding.Tool("no-such-tool"), false))
        assertEquals(PencilOutcome("pen"), g.onGesture(DOUBLE_TAP, IGNORE, PencilBinding.Command("format_disk"), false))
        assertEquals(setOf("pen", "highlighter", "eraser", "pan", "select", "lasso", "screenshot"), PencilGestures.TOOLS)
        assertEquals(setOf("undo", "redo", "toggle_previous", "toggle_eraser", "toggle_pan"), PencilGestures.COMMANDS)
    }
}
