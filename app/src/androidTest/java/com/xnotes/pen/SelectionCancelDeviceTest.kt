package com.xnotes.pen

/** Runs the shared controller events on Android; target-device rendering remains separate. */
class SelectionCancelDeviceTest : SelectionCancelContract() {
    override fun runOnMain(block: () -> Unit) {
        var failure: Throwable? = null
        PenTestRunner.instance.runOnMainSync {
            try { block() } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }
}
