package com.xnotes.mindmap

import android.content.Context
import com.xnotes.pen.PenTestRunner

class MapNodeSelectionDeviceTest:MapNodeSelectionContract() {
    override fun ui(block:(Context)->Unit) {
        val runner=PenTestRunner.instance;var failure:Throwable?=null
        runner.runOnMainSync {try {block(runner.targetContext)} catch(error:Throwable) {failure=error}}
        failure?.let {throw it}
    }
}
