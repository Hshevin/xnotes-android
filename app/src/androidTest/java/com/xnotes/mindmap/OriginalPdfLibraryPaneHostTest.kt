package com.xnotes.mindmap

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import com.xnotes.MainActivity
import com.xnotes.R
import com.xnotes.pen.PenTestRunner
import org.junit.Assert.*
import org.junit.Test

/** Run in the isolated instrumentation profile; source compilation is not visual acceptance. */
class OriginalPdfLibraryPaneHostTest {
    private val runner get()=PenTestRunner.instance
    private fun <T> main(block:()->T):T {
        var result:Result<T>?=null
        runner.runOnMainSync {result=runCatching(block)}
        return requireNotNull(result).getOrThrow()
    }
    private fun nodes(n:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(n==null) emptyList() else
        listOf(n)+(0 until n.childCount).flatMap {nodes(n.getChild(it))}
    private fun await(label:String,check:()->Boolean) {
        repeat(600) {runner.waitForIdleSync();if(check()) return;Thread.sleep(20)}
        fail(label)
    }
    private fun shown(label:String)=nodes(runner.uiAutomation.rootInActiveWindow).any {
        it.isVisibleToUser && (it.text?.toString()==label || it.contentDescription?.toString()==label)
    }
    private fun click(label:String) {
        var target:AccessibilityNodeInfo?=null
        await(label) {
            target=nodes(runner.uiAutomation.rootInActiveWindow).firstOrNull {
                it.isVisibleToUser && (it.text?.toString()==label || it.contentDescription?.toString()==label)
            }
            target!=null
        }
        var at=requireNotNull(target)
        while(!at.isClickable) at=requireNotNull(at.parent)
        assertTrue(at.isEnabled);assertTrue(at.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    @Test fun libraryKeepsHomeNavigationAndLeavingForAiOrBackReleasesItsPresentation() {
        val activity=runner.startActivitySync(Intent(runner.targetContext,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            await("Home navigation ready") {shown(activity.getString(R.string.original_pdf_library_title))}
            val editor=main {activity.documentEditor}
            click(activity.getString(R.string.original_pdf_library_title))
            await("Library open") {main {activity.originalLibrary?.visible==true}}
            await("Home sidebar remains reachable") {shown(activity.getString(R.string.ai_label))}
            assertSame(editor,main {activity.documentEditor})
            assertTrue(shown(activity.getString(R.string.original_pdf_refresh)))
            click(activity.getString(R.string.ai_label))
            await("Switching to AI closes library") {main {activity.originalLibrary?.visible==false}}
            click(activity.getString(R.string.original_pdf_library_title))
            await("Library reopened") {main {activity.originalLibrary?.visible==true}}
            runner.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            await("Back returns to Home") {main {activity.originalLibrary?.visible==false}}
            assertFalse(activity.isFinishing)
            assertSame(editor,main {activity.documentEditor})
        } finally {main {activity.originalLibrary?.close();activity.finish()}}
    }
}
