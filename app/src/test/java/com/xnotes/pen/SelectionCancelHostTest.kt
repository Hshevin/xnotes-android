package com.xnotes.pen

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Host event semantics; no APK, compositor, target GPU or physical pen is exercised. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class SelectionCancelHostTest : SelectionCancelContract() {
    override fun runOnMain(block: () -> Unit) = block()
}
