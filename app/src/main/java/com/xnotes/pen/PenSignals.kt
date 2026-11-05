package com.xnotes.pen

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/** Android translation shared by recording and playback; never modifies the raw event. */
object PenSignals {
    fun isPen(event: MotionEvent): Boolean = (0 until event.pointerCount).any {
        event.getToolType(it) == MotionEvent.TOOL_TYPE_STYLUS || event.getToolType(it) == MotionEvent.TOOL_TYPE_ERASER
    }

    fun motion(event: MotionEvent, buttons: Int = event.buttonState): Set<PenTrigger> {
        if (!isPen(event)) return emptySet()
        return (0..31).map { 1 shl it }.filter { buttons and it != 0 }.map {
            PenTrigger(PenTrigger.Kind.MOTION, it, event.device?.descriptor.orEmpty(), InputDevice.SOURCE_STYLUS)
        }.toSet()
    }

    fun key(event: KeyEvent): PenTrigger? {
        if (event.keyCode <= 0 || event.action !in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) return null
        // System navigation remains available to exit recording. No accessibility service required.
        if (event.keyCode in setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER,
                KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_ESCAPE)) return null
        return PenTrigger(PenTrigger.Kind.KEY, event.keyCode, event.device?.descriptor.orEmpty(), event.source, event.scanCode)
    }
}
