package com.xnotes.pen

class DocumentResetDeviceTest : DocumentResetContract() {
    override fun runOnMain(block: () -> Unit) {
        var failure: Throwable? = null
        PenTestRunner.instance.runOnMainSync {
            try { block() } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }
}
