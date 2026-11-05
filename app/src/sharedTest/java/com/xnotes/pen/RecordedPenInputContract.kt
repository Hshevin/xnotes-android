package com.xnotes.pen

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.xnotes.core.tools.Tool
import org.junit.Assert.*
import org.junit.Test

/** Same event contracts run on the host and in device instrumentation. */
abstract class RecordedPenInputContract {
    private var tool = Tool.PEN
    private val commands = mutableListOf<String>()
    private var shortcuts = emptyList<PenShortcut>()
    private val input = ProPenInput({ shortcuts }, { tool }, { tool = it }, { commands.add(it) })

    private fun event(action: Int, mask: Int, source: Int = InputDevice.SOURCE_STYLUS,
                      type: Int = MotionEvent.TOOL_TYPE_STYLUS, deviceId: Int = 9999, flags: Int = 0): MotionEvent {
        val p = MotionEvent.PointerProperties().apply { id = 0; toolType = type }
        val c = MotionEvent.PointerCoords().apply { x = 10f; y = 20f; pressure = .5f }
        return MotionEvent.obtain(1, 2, action, 1, arrayOf(p), arrayOf(c), 0, mask,
            1f, 1f, deviceId, 0, source, flags)
    }
    private fun motion(action: Int, mask: Int, source: Int = InputDevice.SOURCE_STYLUS) {
        event(action, mask, source).let { try { input.motion(it) } finally { it.recycle() } }
    }
    private fun touch(action: Int, mask: Int, during: () -> Unit = {}) {
        event(action, mask).let { try { input.touch(it) { during(); true } } finally { it.recycle() } }
    }
    private fun key(action: Int, repeats: Int = 0, flags: Int = 0, code: Int = 600, deviceId: Int = 9999): KeyEvent =
        KeyEvent(1, 2, action, code, repeats, 0, deviceId, 42, flags, InputDevice.SOURCE_KEYBOARD)
    private fun bindMotion(mask: Int, action: String, mode: PenBindingMode = PenBindingMode.HOLD) {
        val e = event(MotionEvent.ACTION_HOVER_MOVE, mask)
        try { shortcuts = listOf(PenShortcut("Button", PenSignals.motion(e).toList(), PenBinding(action, mode))) }
        finally { e.recycle() }
    }
    private fun bindKey(action: String) {
        shortcuts = listOf(PenShortcut("Remote", listOf(PenSignals.key(key(KeyEvent.ACTION_DOWN))!!), PenBinding(action)))
    }

    @Test fun recordedMotionFromTouchscreenSourceAlsoPlaysBack() {
        val e = event(MotionEvent.ACTION_HOVER_MOVE, 128, InputDevice.SOURCE_TOUCHSCREEN)
        try { shortcuts = listOf(PenShortcut("Pen", PenSignals.motion(e).toList(), PenBinding("eraser"))) }
        finally { e.recycle() }
        motion(MotionEvent.ACTION_HOVER_MOVE, 128, InputDevice.SOURCE_TOUCHSCREEN)
        assertEquals(Tool.ERASER, tool)
        motion(MotionEvent.ACTION_HOVER_MOVE, 0, InputDevice.SOURCE_TOUCHSCREEN)
        assertEquals(Tool.PEN, tool)
    }
    @Test fun arbitraryRecordedMaskDeduplicatesIndependentButtonAndHoverEvents() {
        bindMotion(128, "undo")
        motion(MotionEvent.ACTION_HOVER_MOVE, 128)
        motion(MotionEvent.ACTION_BUTTON_PRESS, 128)
        motion(MotionEvent.ACTION_HOVER_MOVE, 128)
        assertEquals(listOf("undo"), commands)
        motion(MotionEvent.ACTION_BUTTON_RELEASE, 0)
        motion(MotionEvent.ACTION_HOVER_MOVE, 128)
        assertEquals(listOf("undo", "undo"), commands)
    }
    @Test fun keyRepeatsDoNotRepeatCommandsAndUnboundKeysPassThrough() {
        bindKey("undo")
        assertTrue(input.key(key(KeyEvent.ACTION_DOWN)))
        input.key(key(KeyEvent.ACTION_DOWN, repeats = 2))
        assertFalse(input.key(key(KeyEvent.ACTION_DOWN, code = 601)))
        assertEquals(listOf("undo"), commands)
        input.key(key(KeyEvent.ACTION_UP))
        input.key(key(KeyEvent.ACTION_DOWN))
        assertEquals(2, commands.size)
    }
    @Test fun keyHoldSurvivesMotionWithZeroButtonsAndRestoresOriginalTool() {
        bindKey("eraser")
        input.key(key(KeyEvent.ACTION_DOWN))
        assertEquals(Tool.ERASER, tool)
        motion(MotionEvent.ACTION_HOVER_MOVE, 0)
        assertEquals(Tool.ERASER, tool)
        input.key(key(KeyEvent.ACTION_UP))
        assertEquals(Tool.PEN, tool)
    }
    @Test fun keyChangesDuringStrokeWaitUntilStrokeCompletes() {
        bindKey("eraser")
        touch(MotionEvent.ACTION_DOWN, 0)
        input.key(key(KeyEvent.ACTION_DOWN))
        assertEquals(Tool.PEN, tool)
        touch(MotionEvent.ACTION_UP, 0) { assertEquals(Tool.PEN, tool) }
        assertEquals(Tool.ERASER, tool)
        input.key(key(KeyEvent.ACTION_UP))
        assertEquals(Tool.PEN, tool)
    }
    @Test fun canceledStrokeDiscardsKeyCommandAndAllowsNextPress() {
        bindKey("undo")
        touch(MotionEvent.ACTION_DOWN, 0)
        input.key(key(KeyEvent.ACTION_DOWN))
        touch(MotionEvent.ACTION_CANCEL, 0)
        assertTrue(commands.isEmpty())
        input.key(key(KeyEvent.ACTION_UP))
        input.key(key(KeyEvent.ACTION_DOWN))
        assertEquals(listOf("undo"), commands)
    }
    @Test fun canceledKeyReleaseDiscardsPendingCommandAndRearmsNextPress() {
        bindKey("undo")
        touch(MotionEvent.ACTION_DOWN, 0)
        input.key(key(KeyEvent.ACTION_DOWN))
        input.key(key(KeyEvent.ACTION_UP, flags = KeyEvent.FLAG_CANCELED))
        touch(MotionEvent.ACTION_UP, 0)
        assertTrue(commands.isEmpty())
        input.key(key(KeyEvent.ACTION_DOWN))
        assertEquals(listOf("undo"), commands)
    }
    @Test fun simultaneousKeyAndMotionAliasesDispatchOneCommand() {
        bindMotion(32, "undo")
        shortcuts = listOf(shortcuts.single().copy(triggers = shortcuts.single().triggers + PenSignals.key(key(KeyEvent.ACTION_DOWN))!!))
        motion(MotionEvent.ACTION_HOVER_MOVE, 32)
        input.key(key(KeyEvent.ACTION_DOWN))
        motion(MotionEvent.ACTION_BUTTON_PRESS, 32)
        motion(MotionEvent.ACTION_BUTTON_RELEASE, 0)
        input.key(key(KeyEvent.ACTION_UP))
        assertEquals(listOf("undo"), commands)
    }
    @Test fun focusResetRestoresHeldToolEvenWithoutKeyUp() {
        bindKey("eraser")
        input.key(key(KeyEvent.ACTION_DOWN))
        input.reset()
        assertEquals(Tool.PEN, tool)
        motion(MotionEvent.ACTION_HOVER_MOVE, 0)
        assertEquals(Tool.PEN, tool)
    }
    @Test fun inheritedMotionCommandDoesNotRepeatInNextPaneButHoldIsRearmed() {
        bindMotion(128, "undo")
        val e = event(MotionEvent.ACTION_HOVER_ENTER, 128)
        try { input.inherit(PenInputSnapshot(PenSignals.motion(e), emptyList(), e.deviceId)); input.motion(e) } finally { e.recycle() }
        assertTrue(commands.isEmpty())
        motion(MotionEvent.ACTION_HOVER_MOVE, 0)
        motion(MotionEvent.ACTION_HOVER_MOVE, 128)
        assertEquals(listOf("undo"), commands)
    }
    @Test fun freshKeyDownAfterFocusLossIsNotSwallowed() {
        bindKey("undo")
        input.key(key(KeyEvent.ACTION_DOWN))
        input.reset() // the system never delivers the first UP
        input.key(key(KeyEvent.ACTION_DOWN))
        assertEquals(listOf("undo", "undo"), commands)
    }
    @Test fun nonOverlappingAliasesDoNotDoubleExecuteOrThrottleNextRealPress() {
        bindMotion(32, "undo")
        shortcuts = listOf(shortcuts.single().copy(triggers = shortcuts.single().triggers + PenSignals.key(key(KeyEvent.ACTION_DOWN))!!))
        motion(MotionEvent.ACTION_HOVER_MOVE, 32)
        motion(MotionEvent.ACTION_HOVER_MOVE, 0)
        input.key(key(KeyEvent.ACTION_DOWN))
        input.key(key(KeyEvent.ACTION_UP))
        assertEquals(listOf("undo"), commands)
        motion(MotionEvent.ACTION_HOVER_MOVE, 32)
        assertEquals(listOf("undo", "undo"), commands)
    }
    @Test fun keyOnlyHoldTransfersToNextPaneAndReleasesThere() {
        bindKey("eraser")
        input.key(key(KeyEvent.ACTION_DOWN))
        val held = input.snapshot
        input.reset()
        var nextTool = tool
        val next = ProPenInput({ shortcuts }, { nextTool }, { nextTool = it }, { commands.add(it) })
        next.inherit(held)
        assertEquals(Tool.PEN, tool)
        assertEquals(Tool.ERASER, nextTool)
        next.key(key(KeyEvent.ACTION_UP))
        assertEquals(Tool.PEN, nextTool)
    }
    @Test fun keyOnlyCommandDoesNotRepeatOnPaneTransfer() {
        bindKey("undo")
        input.key(key(KeyEvent.ACTION_DOWN))
        val held = input.snapshot
        input.reset()
        val next = ProPenInput({ shortcuts }, { Tool.PEN }, {}, { commands.add(it) })
        next.inherit(held)
        assertEquals(listOf("undo"), commands)
        next.key(key(KeyEvent.ACTION_UP))
        next.key(key(KeyEvent.ACTION_DOWN))
        assertEquals(listOf("undo", "undo"), commands)
    }
    @Test fun aliasReleaseAfterHoverExitCannotReviveTemporaryTool() {
        bindMotion(32, "eraser")
        shortcuts = listOf(shortcuts.single().copy(triggers = shortcuts.single().triggers + PenSignals.key(key(KeyEvent.ACTION_DOWN))!!))
        motion(MotionEvent.ACTION_HOVER_MOVE, 32)
        assertEquals(Tool.ERASER, tool)
        motion(MotionEvent.ACTION_HOVER_EXIT, 0)
        assertEquals(Tool.PEN, tool)
        assertTrue(input.key(key(KeyEvent.ACTION_UP)))
        assertEquals(Tool.PEN, tool)
    }
    @Test fun cancelStrokeDiscardsSelectToolChange() {
        bindMotion(32, "highlighter", PenBindingMode.SELECT)
        touch(MotionEvent.ACTION_DOWN, 0)
        touch(MotionEvent.ACTION_MOVE, 32)
        touch(MotionEvent.ACTION_CANCEL, 32)
        assertEquals(Tool.PEN, tool)
        motion(MotionEvent.ACTION_HOVER_MOVE, 32)
        assertEquals(Tool.PEN, tool)
    }
    @Test fun canceledKeyOnlyReleasesItsOwnHold() {
        bindKey("eraser")
        shortcuts = shortcuts + PenShortcut("Other", listOf(PenSignals.key(key(KeyEvent.ACTION_DOWN, code = 601))!!), PenBinding("pan"))
        input.key(key(KeyEvent.ACTION_DOWN))
        input.key(key(KeyEvent.ACTION_DOWN, code = 601))
        assertEquals(Tool.PAN, tool)
        input.key(key(KeyEvent.ACTION_UP, flags = KeyEvent.FLAG_CANCELED, code = 601))
        assertEquals(Tool.ERASER, tool)
        input.key(key(KeyEvent.ACTION_UP))
        assertEquals(Tool.PEN, tool)
    }
    @Test fun canceledKeyKeepsOtherKeysQueuedCommand() {
        bindKey("undo")
        shortcuts = shortcuts + PenShortcut("Other", listOf(PenSignals.key(key(KeyEvent.ACTION_DOWN, code = 601))!!), PenBinding("redo"))
        touch(MotionEvent.ACTION_DOWN, 0)
        input.key(key(KeyEvent.ACTION_DOWN))
        input.key(key(KeyEvent.ACTION_DOWN, code = 601))
        input.key(key(KeyEvent.ACTION_UP, flags = KeyEvent.FLAG_CANCELED, code = 601))
        touch(MotionEvent.ACTION_UP, 0)
        assertEquals(listOf("undo"), commands)
    }
    @Test fun canceledSecondPressKeepsFirstPressOfSameKey() {
        bindKey("undo")
        touch(MotionEvent.ACTION_DOWN, 0)
        input.key(key(KeyEvent.ACTION_DOWN))
        input.key(key(KeyEvent.ACTION_UP))
        input.key(key(KeyEvent.ACTION_DOWN))
        input.key(key(KeyEvent.ACTION_UP, flags = KeyEvent.FLAG_CANCELED))
        touch(MotionEvent.ACTION_UP, 0)
        assertEquals(listOf("undo"), commands)
    }
    @Test fun canceledSelectKeyDoesNotReplacePreviousTool() {
        bindKey("highlighter")
        shortcuts = shortcuts.map { it.copy(binding = it.binding.copy(mode = PenBindingMode.SELECT)) }
        touch(MotionEvent.ACTION_DOWN, 0)
        input.key(key(KeyEvent.ACTION_DOWN))
        input.key(key(KeyEvent.ACTION_UP, flags = KeyEvent.FLAG_CANCELED))
        touch(MotionEvent.ACTION_UP, 0)
        assertEquals(Tool.PEN, tool)
    }
    @Test fun titleFocusTransferMustNotRepeatHeldMotionCommand() {
        bindMotion(32, "undo")
        motion(MotionEvent.ACTION_HOVER_MOVE, 32)
        val held = input.snapshot
        input.reset()
        val next = ProPenInput({ shortcuts }, { Tool.PEN }, {}, { commands.add(it) })
        next.inherit(held)
        val e = event(MotionEvent.ACTION_HOVER_ENTER, 32)
        try { next.motion(e) } finally { e.recycle() }
        assertEquals(listOf("undo"), commands)
    }
    @Test fun canceledUpFlagDiscardsSelectToolChange() {
        bindMotion(32, "highlighter", PenBindingMode.SELECT)
        touch(MotionEvent.ACTION_DOWN, 0)
        touch(MotionEvent.ACTION_MOVE, 32)
        val up = event(MotionEvent.ACTION_UP, 32, flags = MotionEvent.FLAG_CANCELED)
        try { input.touch(up) { true } } finally { up.recycle() }
        assertEquals(Tool.PEN, tool)
        motion(MotionEvent.ACTION_HOVER_MOVE, 32)
        assertEquals(Tool.PEN, tool)
    }
    @Test fun holdPressedAndReleasedDuringStrokeNeverFlashesTool() {
        bindKey("eraser")
        val applied = mutableListOf<Tool>()
        val adapter = ProPenInput({ shortcuts }, { Tool.PEN }, { applied.add(it) }, {})
        val down = event(MotionEvent.ACTION_DOWN, 0)
        val up = event(MotionEvent.ACTION_UP, 0)
        try {
            adapter.touch(down) { true }
            adapter.key(key(KeyEvent.ACTION_DOWN))
            adapter.key(key(KeyEvent.ACTION_UP))
            adapter.touch(up) { true }
        } finally { down.recycle(); up.recycle() }
        assertTrue(applied.isEmpty())
    }
    @Test fun duplicateCanceledReleaseDoesNotEraseCompletedPress() {
        bindKey("undo")
        touch(MotionEvent.ACTION_DOWN, 0)
        input.key(key(KeyEvent.ACTION_DOWN))
        input.key(key(KeyEvent.ACTION_UP))
        input.key(key(KeyEvent.ACTION_UP, flags = KeyEvent.FLAG_CANCELED))
        touch(MotionEvent.ACTION_UP, 0)
        assertEquals(listOf("undo"), commands)
    }
    @Test fun heldSelectDoesNotOverrideManualSelectionOnTransfer() {
        bindMotion(32, "highlighter", PenBindingMode.SELECT)
        motion(MotionEvent.ACTION_HOVER_MOVE, 32)
        input.selected(Tool.PEN)
        tool = Tool.PEN
        val snapshot = input.snapshot
        input.reset()
        var otherTool = tool
        val next = ProPenInput({ shortcuts }, { otherTool }, { otherTool = it }, {})
        next.inherit(snapshot)
        val e = event(MotionEvent.ACTION_HOVER_ENTER, 32)
        try { next.motion(e) } finally { e.recycle() }
        assertEquals(Tool.PEN, otherTool)
    }
    @Test fun overriddenHoldDoesNotReviveOnTransfer() {
        bindMotion(32, "eraser")
        motion(MotionEvent.ACTION_HOVER_MOVE, 32)
        input.selected(Tool.HIGHLIGHTER)
        tool = Tool.HIGHLIGHTER
        val snapshot = input.snapshot
        input.reset()
        var otherTool = tool
        val next = ProPenInput({ shortcuts }, { otherTool }, { otherTool = it }, {})
        next.inherit(snapshot)
        val e = event(MotionEvent.ACTION_HOVER_ENTER, 32)
        try { next.motion(e) } finally { e.recycle() }
        assertEquals(Tool.HIGHLIGHTER, otherTool)
    }
    @Test fun snapshotCannotRelabelAnotherDevicesButtonAsAlreadyPressed() {
        bindMotion(32, "undo")
        val other = shortcuts.single().primary.copy(descriptor = "different-digitizer")
        input.inherit(PenInputSnapshot(setOf(other), emptyList(), 9999))
        motion(MotionEvent.ACTION_HOVER_ENTER, 32)
        assertEquals(listOf("undo"), commands)
    }
    @Test fun overlappingHoldsKeepPressOrderAcrossTransfer() {
        bindKey("eraser")
        shortcuts = shortcuts + PenShortcut("Other", listOf(PenSignals.key(key(KeyEvent.ACTION_DOWN, code = 601))!!), PenBinding("pan"))
        input.key(key(KeyEvent.ACTION_DOWN, code = 601))
        input.key(key(KeyEvent.ACTION_DOWN))
        val snapshot = input.snapshot
        input.reset()
        var otherTool = tool
        val next = ProPenInput({ shortcuts }, { otherTool }, { otherTool = it }, {})
        next.inherit(snapshot)
        assertEquals(Tool.ERASER, otherTool)
        next.key(key(KeyEvent.ACTION_UP))
        assertEquals(Tool.PAN, otherTool)
        next.key(key(KeyEvent.ACTION_UP, code = 601))
        assertEquals(Tool.PEN, otherTool)
    }
    @Test fun surfaceReparentingRetainsHoldWithoutRepeatingCommand() {
        bindMotion(32, "eraser")
        motion(MotionEvent.ACTION_HOVER_MOVE, 32)
        input.surfaceDetached()
        assertEquals(Tool.PEN, tool)
        motion(MotionEvent.ACTION_HOVER_ENTER, 32)
        assertEquals(Tool.ERASER, tool)
        motion(MotionEvent.ACTION_HOVER_MOVE, 0)
        assertEquals(Tool.PEN, tool)
    }
    @Test fun recordingRejectsCanceledTipUp() {
        val capture = PenRecordingInput()
        val down = event(MotionEvent.ACTION_DOWN, 32)
        val up = event(MotionEvent.ACTION_UP, 0, flags = MotionEvent.FLAG_CANCELED)
        try { capture.motion(down); capture.motion(up) } finally { down.recycle(); up.recycle() }
        assertNull(capture.recording.finish())
        assertEquals(PenRecording.Failure.CANCELED, capture.recording.failure)
    }
    @Test fun recordingDoesNotReleaseOnePensButtonOnAnotherPensEmptySample() {
        val capture = PenRecordingInput()
        val down = event(MotionEvent.ACTION_HOVER_MOVE, 32)
        val other = event(MotionEvent.ACTION_HOVER_MOVE, 0, deviceId = 8888)
        val up = event(MotionEvent.ACTION_HOVER_MOVE, 0)
        try {
            capture.motion(down); capture.motion(other)
            assertNull(capture.recording.finish())
            capture.motion(up)
            assertEquals(1, capture.recording.finish()!!.size)
        } finally { down.recycle(); other.recycle(); up.recycle() }
    }
    @Test fun recordingMatchesKeyReleaseToRuntimeDevice() {
        val capture = PenRecordingInput()
        capture.key(key(KeyEvent.ACTION_DOWN))
        capture.key(key(KeyEvent.ACTION_UP, deviceId = 8888))
        assertNull(capture.recording.finish())
        capture.key(key(KeyEvent.ACTION_UP))
        assertEquals(1, capture.recording.finish()!!.size)
    }
    @Test fun recordingDoesNotCaptureRepeatWithoutInitialDown() {
        val capture = PenRecordingInput()
        capture.key(key(KeyEvent.ACTION_DOWN, repeats = 1))
        capture.key(key(KeyEvent.ACTION_UP))
        assertNull(capture.recording.finish())
    }
    @Test fun temporaryHoldKeepsToolbarSelectionThroughHoverContactAndRelease() {
        bindMotion(32, "eraser")
        val selections = mutableListOf<Tool>()
        val adapter = ProPenInput({ shortcuts }, { tool }, { tool = it }, {}, { selections.add(it) })
        fun send(action: Int, mask: Int, contact: Boolean = false) {
            val e = event(action, mask)
            try { if (contact) adapter.touch(e) { true } else adapter.motion(e) } finally { e.recycle() }
        }
        send(MotionEvent.ACTION_HOVER_MOVE, 32)
        assertEquals(Tool.ERASER, tool)
        send(MotionEvent.ACTION_HOVER_EXIT, 0)
        send(MotionEvent.ACTION_DOWN, 32, true)
        assertEquals(Tool.ERASER, tool)
        send(MotionEvent.ACTION_UP, 32, true)
        send(MotionEvent.ACTION_HOVER_MOVE, 0)
        assertEquals(Tool.PEN, tool)
        assertTrue(selections.isNotEmpty())
        assertTrue(selections.all { it == Tool.PEN })
    }
    @Test fun permanentButtonSelectionUpdatesToolbarOnlyAfterStroke() {
        bindMotion(32, "highlighter", PenBindingMode.SELECT)
        var selection = Tool.PEN
        val adapter = ProPenInput({ shortcuts }, { tool }, { tool = it }, {}, { selection = it })
        for ((action, mask) in listOf(MotionEvent.ACTION_DOWN to 0, MotionEvent.ACTION_MOVE to 32)) {
            val e = event(action, mask)
            try { adapter.touch(e) { true } } finally { e.recycle() }
        }
        assertEquals(Tool.PEN, selection)
        val up = event(MotionEvent.ACTION_UP, 32)
        try { adapter.touch(up) { assertEquals(Tool.PEN, selection); true } } finally { up.recycle() }
        assertEquals(Tool.HIGHLIGHTER, selection)
        assertEquals(Tool.HIGHLIGHTER, tool)
    }
    @Test fun explicitlySelectingTheBorrowedToolMakesItPermanentInToolbar() {
        bindKey("eraser")
        var selection = Tool.PEN
        val adapter = ProPenInput({ shortcuts }, { tool }, { tool = it }, {}, { selection = it })
        adapter.key(key(KeyEvent.ACTION_DOWN))
        assertEquals(Tool.PEN, selection)
        adapter.selected(Tool.ERASER) // toolbar tap, even though the effective tool is already eraser
        adapter.key(key(KeyEvent.ACTION_UP))
        assertEquals(Tool.ERASER, selection)
        assertEquals(Tool.ERASER, tool)
    }
    @Test fun tipFingerAndMouseDoNotRecordPenButtons() {
        for ((type, mask) in listOf(MotionEvent.TOOL_TYPE_STYLUS to 0,
                MotionEvent.TOOL_TYPE_FINGER to 32, MotionEvent.TOOL_TYPE_MOUSE to 32)) {
            val e = event(MotionEvent.ACTION_DOWN, mask, type = type)
            try { assertTrue(PenSignals.motion(e).isEmpty()) } finally { e.recycle() }
        }
    }
    @Test fun navigationKeysRemainAvailableWhileRecording() {
        assertNull(PenSignals.key(key(KeyEvent.ACTION_DOWN, code = KeyEvent.KEYCODE_BACK)))
        assertNull(PenSignals.key(key(KeyEvent.ACTION_DOWN, code = KeyEvent.KEYCODE_ESCAPE)))
    }
}
