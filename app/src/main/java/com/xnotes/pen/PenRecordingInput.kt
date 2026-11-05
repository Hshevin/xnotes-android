package com.xnotes.pen

import android.view.KeyEvent
import android.view.MotionEvent

/** Android recording adapter, shared by the capture View and event contract tests. */
class PenRecordingInput(val recording: PenRecording = PenRecording()) {
    private val motionByDevice = mutableMapOf<Int, Set<PenTrigger>>()
    private val keysDown = mutableSetOf<Pair<Int, PenTrigger>>()

    fun key(event: KeyEvent): Boolean {
        val trigger = PenSignals.key(event) ?: return false
        val identity = event.deviceId to trigger
        if (event.isCanceled) recording.cancel()
        else if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (keysDown.add(identity)) recording.event(trigger, true)
        } else if (event.action == KeyEvent.ACTION_UP && keysDown.remove(identity)) {
            if (keysDown.none { it.second == trigger }) recording.event(trigger, false)
        }
        return true
    }

    fun motion(event: MotionEvent): Boolean {
        if (!PenSignals.isPen(event)) return false
        if (event.actionMasked == MotionEvent.ACTION_CANCEL || event.flags and MotionEvent.FLAG_CANCELED != 0) {
            recording.cancel()
            return true
        }
        // Hover exit often precedes contact; it is not a physical button release.
        if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) return true
        val previous = motionByDevice.values.flatten().toSet()
        val sample = PenSignals.motion(event)
        if (sample.isEmpty()) motionByDevice.remove(event.deviceId)
        else motionByDevice[event.deviceId] = sample
        val current = motionByDevice.values.flatten().toSet()
        (current - previous).forEach { recording.event(it, true) }
        (previous - current).forEach { recording.event(it, false) }
        // Empty samples or key-ups from another runtime device cannot complete this press.
        return true
    }
}
