package com.xnotes.mindmap

import android.content.Context
import com.xnotes.pen.PenTestRunner

class MapInputTest : MapInputContract() {
    override fun ui(block: (Context) -> Unit) {
        val runner = PenTestRunner.instance
        var failure: Throwable? = null
        runner.runOnMainSync { try { block(runner.targetContext) } catch (t: Throwable) { failure = t } }
        failure?.let { throw it }
    }
}
