package com.xnotes.mindmap

import android.view.accessibility.AccessibilityNodeInfo
import com.xnotes.R
import com.xnotes.pen.PenTestRunner
import org.junit.Assert.assertTrue

/** Follow the real menu entry; don't open an editor by mutating presentation state. */
internal fun openMapMenuPage(tab:Int) {
    val runner=PenTestRunner.instance
    fun button(id:Int):AccessibilityNodeInfo? {
        val label=runner.targetContext.getString(id)
        return runner.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label)
            ?.firstNotNullOfOrNull {candidate->
                var node:AccessibilityNodeInfo?=candidate
                while(node!=null && !node.isClickable) node=node.parent
                node?.takeIf {it.isVisibleToUser && it.isEnabled}
            }
    }
    fun click(id:Int) {
        repeat(300) {
            runner.waitForIdleSync()
            button(id)?.let {assertTrue(it.performAction(AccessibilityNodeInfo.ACTION_CLICK));runner.waitForIdleSync();return}
            Thread.sleep(10)
        }
        error("Missing map menu action: ${runner.targetContext.getString(id)}")
    }
    if(button(R.string.map_return_to_canvas)==null) click(R.string.map_menu)
    click(tab)
}
