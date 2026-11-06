package com.xnotes.mindmap

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.ViewModelProvider
import com.xnotes.R
import com.xnotes.pen.PenTestRunner
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Runs the actual Compose field and Android InputConnection. Compiling this is not IME/device acceptance. */
class MapTextEditingHostTest {
    private val runner get()=PenTestRunner.instance
    private fun <T> main(block:()->T):T {
        var result:Any?=null;var failure:Throwable?=null
        runner.runOnMainSync {try {result=block()} catch(t:Throwable) {failure=t}}
        failure?.let {throw it}
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun views(root:View):List<View> = listOf(root)+(if(root is ViewGroup) (0 until root.childCount).flatMap {views(root.getChildAt(it))} else emptyList())
    private fun waitUntil(condition:()->Boolean) {
        repeat(500) {runner.waitForIdleSync();if(condition()) return;Thread.sleep(10)}
        fail("Text editing host did not settle")
    }
    private fun graph(activity:Activity)=views(activity.window.decorView).filterIsInstance<MindMapView>().single()
    private fun open():MindMapActivity {
        val context=runner.targetContext
        val doc=MindMapDocument(nodes=listOf(MapNode(id="root",parent=null,text="Original"),
            MapNode(id="far",parent=null,free=true,text="Far away",x=100000.0)))
        val file=File.createTempFile("text-host-",".xmap",context.cacheDir).apply {writeBytes(MapCodec.encode(doc))}
        val activity=runner.startActivitySync(MindMapActivity.intent(context,Uri.fromFile(file).toString(),"Text host").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MindMapActivity
        val session=main {ViewModelProvider(activity)[MapSession::class.java]}
        waitUntil {main {!session.loading && views(activity.window.decorView).any {it is MindMapView && it.height>0}}}
        return activity
    }
    private fun click(label:String) {
        var node:AccessibilityNodeInfo?=null
        waitUntil {node=runner.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label)?.firstOrNull {it.isClickable};node!=null}
        assertTrue(node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun stableCamera(g:MindMapView) {
        var previous=main {g.viewport()};var stable=0
        waitUntil {
            val current=main {g.viewport()}
            stable=if(current==previous) stable+1 else 0;previous=current
            stable>=5
        }
    }
    private fun assertRestored(g:MindMapView,draft:MapTextDraft,session:MapSession) = main {
        val camera=g.viewport();val scale=g.resources.displayMetrics.density*camera.zoom
        val oldScale=draft.viewDensity*draft.viewport.zoom
        assertEquals(draft.viewport.zoom,camera.zoom,0f)
        assertEquals((draft.viewWidth/2f-draft.viewport.panX)/oldScale,(g.width/2f-camera.panX)/scale,.001f)
        assertEquals((draft.viewHeight/2f-draft.viewport.panY)/oldScale,(g.height/2f-camera.panY)/scale,.001f)
        assertEquals(draft.previousSelection,session.selected)
    }
    @Test fun realComposingFieldKeepsDraftAcrossResizeThenDoneIsOneUndoAndBackCancels() {
        val activity=open()
        try {
            val session=main {ViewModelProvider(activity)[MapSession::class.java]};val g=main {graph(activity)}
            main {g.onEdit("root")}
            val imm=activity.getSystemService(InputMethodManager::class.java)
            waitUntil {main {session.textDraft!=null && imm.isAcceptingText && activity.currentFocus!=null}}
            val entry=main {session.textDraft!!};val token=entry.token
            val connection=main {activity.currentFocus!!.onCreateInputConnection(EditorInfo())}
            assertNotNull(connection)
            // The editor initially selects the end; replace all content through the platform connection.
            main {connection!!.setSelection(0,8);connection.setComposingText("中文",1)}
            waitUntil {main {session.textDraft?.value?.text=="中文"}}
            assertEquals(TextRange(0,2),main {session.textDraft!!.value.composition})
            main {connection!!.finishComposingText();connection.commitText(" 🖊\nsecond line",1)}
            waitUntil {main {session.textDraft?.value?.text=="中文 🖊\nsecond line"}}
            assertEquals("Original",main {session.document!!.root.text});assertFalse(main {session.canUndo})
            // Only host geometry changes; text, selection and camera initially stay fixed.
            // This isolates the IME-resize callback without assuming a particular installed keyboard.
            stableCamera(g)
            val camera=main {g.viewport()};val draft=main {session.textDraft!!.value}
            main {g.layout(g.left,g.top,g.right,g.top+(64*g.resources.displayMetrics.density).toInt())}
            waitUntil {main {g.viewport()!=camera && g.editingCaretBounds?.let {it.top>=0 && it.bottom<=g.height && it.left>=0 && it.right<=g.width}==true}}
            assertEquals(draft,main {session.textDraft!!.value});assertEquals(token,main {session.textDraft!!.token})
            main {g.requestLayout()}
            runner.waitForIdleSync()
            click(activity.getString(R.string.map_done))
            waitUntil {main {session.textDraft==null && session.textRestore==null}}
            assertEquals("中文 🖊\nsecond line",main {session.document!!.root.text})
            assertRestored(g,entry,session)
            main {session.undo()};assertEquals("Original",main {session.document!!.root.text})
            main {g.onEdit("root")};waitUntil {main {session.textDraft!=null}}
            val original=main {session.document}
            main {activity.onBackPressedDispatcher.onBackPressed()}
            waitUntil {main {session.textDraft==null && session.textRestore==null}}
            assertEquals(original,main {session.document})
            // Retired InputConnection callbacks must not re-open or mutate the transaction.
            main {connection!!.commitText("late",1)};runner.waitForIdleSync()
            assertNull(main {session.textDraft});assertEquals(original,main {session.document})
        } finally {main {activity.finish()}}
    }
    @Test fun recreatedGraphKeepsImeCompositionAndReadableScaleWithoutReplacingTransaction() {
        var activity=open()
        try {
            val session=main {ViewModelProvider(activity)[MapSession::class.java]};val original=main {session.document}
            main {graph(activity).onEdit("root")}
            waitUntil {main {session.textDraft!=null && graph(activity).viewport().zoom>=1f}}
            val draft=main {session.textDraft!!}
            val value=androidx.compose.ui.text.input.TextFieldValue("待组合",TextRange(3),TextRange(0,3))
            main {session.updateTextDraft(draft.token,value)}
            val monitor=runner.addMonitor(MindMapActivity::class.java.name,null,false)
            main {activity.recreate()}
            val recreated=runner.waitForMonitorWithTimeout(monitor,5000) as? MindMapActivity
            runner.removeMonitor(monitor)
            assertNotNull(recreated);activity=recreated!!
            waitUntil {main {views(activity.window.decorView).any {it is MindMapView && it.height>0} && graph(activity).viewport().zoom>=1f}}
            assertSame(session,main {ViewModelProvider(activity)[MapSession::class.java]})
            assertEquals(draft.token,main {session.textDraft!!.token});assertEquals(value,main {session.textDraft!!.value})
            assertEquals(draft.viewport,main {session.textDraft!!.viewport})
            main {activity.onBackPressedDispatcher.onBackPressed()}
            waitUntil {main {session.textDraft==null && session.textRestore==null}}
            assertEquals(original,main {session.document});assertFalse(main {session.canUndo})
            assertRestored(main {graph(activity)},draft,session)
        } finally {main {activity.finish()}}
    }
}
