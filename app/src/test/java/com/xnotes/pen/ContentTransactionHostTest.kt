package com.xnotes.pen

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class ContentTransactionHostTest : ContentTransactionContract() {
    override fun runOnMain(block: () -> Unit) = block()
}
