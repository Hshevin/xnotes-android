package com.xnotes.pen

import org.junit.Assert.*
import org.junit.Test

class PenRecordingTest {
    private val motion = PenTrigger(PenTrigger.Kind.MOTION, 128, "digitizer", 0)
    private val key = PenTrigger(PenTrigger.Kind.KEY, 600, "remote", 257, 22)

    @Test fun requiresMatchingPressAndReleaseAndIgnoresRepeats() {
        val recorder = PenRecording()
        recorder.event(key, false)
        assertNull(recorder.finish())
        recorder.event(key, true)
        recorder.event(key, true)
        assertNull(recorder.finish())
        recorder.event(key, false)
        assertEquals(listOf(key), recorder.finish())
    }

    @Test fun collectsBothDeliveryRoutesOfOnePress() {
        val recorder = PenRecording()
        recorder.event(motion, true)
        recorder.event(key, true)
        recorder.event(motion, false)
        assertNull(recorder.finish())
        recorder.event(key, false)
        assertEquals(setOf(motion, key), recorder.finish()!!.toSet())
    }

    @Test fun canceledRecordingCannotProduceMapping() {
        val recorder = PenRecording()
        recorder.event(key, true)
        recorder.cancel()
        recorder.event(key, false)
        assertNull(recorder.finish())
    }

    @Test fun twoDifferentKeysCannotSilentlyBecomeAliases() {
        val recorder = PenRecording()
        recorder.event(key, true)
        recorder.event(key, false)
        recorder.event(key.copy(code = 601), true)
        recorder.event(key.copy(code = 601), false)
        assertNull(recorder.finish())
    }

    @Test fun twoMotionButtonsCannotSilentlyBecomeAliases() {
        val recorder = PenRecording()
        recorder.event(motion, true)
        recorder.event(motion.copy(code = 64), true)
        recorder.event(motion, false)
        recorder.event(motion.copy(code = 64), false)
        assertNull(recorder.finish())
    }

    @Test fun aliasesUsePrimaryRouteAndDoNotRepeatCommands() {
        val shortcut = PenShortcut("one", listOf(motion, key), PenBinding("undo", PenBindingMode.SELECT))
        val input = PenShortcutState()
        val buttons = ProPenButtons("pen")
        fun send(trigger: PenTrigger, down: Boolean): PenButtonResult {
            input.keyOrButton(trigger, down)
            return buttons.update(input.mask(listOf(shortcut)), input.bindings(listOf(shortcut)))
        }
        assertEquals(listOf("undo"), send(motion, true).commands)
        assertTrue(send(key, true).commands.isEmpty())
        send(motion, false)
        assertTrue(send(key, false).commands.isEmpty())
        assertTrue(send(key, true).commands.isEmpty())
        assertEquals(listOf("undo"), send(motion, true).commands)
    }

    @Test fun deviceAndScanCodeKeepIdenticalKeyCodesSeparate() {
        val mapping = listOf(PenShortcut("one", listOf(key), PenBinding("undo")))
        val input = PenShortcutState()
        input.keyOrButton(key.copy(descriptor = "keyboard"), true)
        input.keyOrButton(key.copy(scanCode = 23), true)
        assertEquals(0, input.mask(mapping))
        input.keyOrButton(key, true)
        assertEquals(1, input.mask(mapping))
    }
}
