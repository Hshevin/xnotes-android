package com.xnotes.mindmap

import android.content.Intent
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import com.xnotes.R
import com.xnotes.pen.PenTestRunner
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real Compose dialog/accessibility actions. Compile-only does not establish touch/IME/device acceptance. */
class MapRelationEditingHostTest {
    private val runner get()=PenTestRunner.instance
    private fun <T> main(block:()->T):T {
        var result:Any?=null;var failure:Throwable?=null
        runner.runOnMainSync {try {result=block()} catch(error:Throwable) {failure=error}}
        failure?.let {throw it}
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun waitUntil(check:()->Boolean) {
        repeat(500) {runner.waitForIdleSync();if(check()) return;Thread.sleep(10)}
        fail("Relationship dialog did not settle")
    }
    private fun click(label:String) {
        var node:AccessibilityNodeInfo?=null
        waitUntil {node=runner.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label)?.firstOrNull {it.isClickable && it.isEnabled};node!=null}
        assertTrue(node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK));runner.waitForIdleSync()
    }
    private fun visible(label:String)=runner.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label).orEmpty().isNotEmpty()
    private fun scenario(twoEdges:Boolean=false,block:(MindMapActivity,MapSession)->Unit) {
        val context=runner.targetContext
        val edges=listOf(MapRelation(id="first",from="a",to="b",label="first description"))+
            if(twoEdges) listOf(MapRelation(id="existing",from="a",to="c",kind=RelationKind.DEPENDS_ON,label="keep existing description")) else emptyList()
        val document=MindMapDocument(nodes=listOf(MapNode(id="a",parent=null,text="A"),MapNode(id="b",parent="a",text="B"),MapNode(id="c",parent="a",text="C")),relations=edges)
        val file=File.createTempFile("relation-host-",".xmap",context.cacheDir).apply {writeBytes(MapCodec.encode(document))}
        val activity=runner.startActivitySync(MindMapActivity.intent(context,Uri.fromFile(file).toString(),"Relationship host").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MindMapActivity
        try {
            val session=main {ViewModelProvider(activity)[MapSession::class.java]}
            waitUntil {main {!session.loading && session.document!=null}}
            block(activity,session)
        } finally {main {activity.finish()}}
    }
    private fun node(a:MindMapActivity,index:Int,name:String)=a.getString(R.string.graph_numbered_node,index,name)
    @Test fun cancelNewConnectionThenEditEndpointsAndDirectionAsOneUndoStep()=scenario {activity,s->
        val original=main {s.document!!}
        click(activity.getString(R.string.graph_relations));click(activity.getString(R.string.graph_connect));click(node(activity,3,"C"))
        assertEquals(original,main {s.document});assertFalse(main {s.canUndo})
        click(activity.getString(R.string.cancel));assertEquals(original,main {s.document});assertFalse(main {s.canUndo})
        click(activity.getString(R.string.graph_relations))
        click(activity.getString(R.string.graph_sentence_related,node(activity,1,"A"),node(activity,2,"B")))
        click(activity.getString(R.string.graph_endpoint_to,node(activity,2,"B")));click(node(activity,3,"C"))
        click(activity.getString(R.string.graph_depends));click(activity.getString(R.string.graph_reverse))
        assertEquals(original,main {s.document})
        click(activity.getString(R.string.map_done))
        val changed=main {s.document!!};assertEquals(original.nodes,changed.nodes)
        assertEquals(original.relations.single().copy(from="c",to="a",kind=RelationKind.DEPENDS_ON),changed.relations.single())
        main {s.undo()};assertEquals(original,main {s.document});assertFalse(main {s.canUndo})
        main {s.redo()};assertEquals(changed,main {s.document})
        click(activity.getString(R.string.cancel))
    }
    @Test fun duplicateEditOpensTheExistingLineWithoutCopyingTheDraftOrAddingUndo()=scenario(twoEdges=true) {activity,s->
        val original=main {s.document!!}
        click(activity.getString(R.string.graph_relations))
        click(activity.getString(R.string.graph_sentence_related,node(activity,1,"A"),node(activity,2,"B")))
        click(activity.getString(R.string.graph_endpoint_to,node(activity,2,"B")));click(node(activity,3,"C"))
        click(activity.getString(R.string.graph_depends));click(activity.getString(R.string.map_done))
        waitUntil {visible(activity.getString(R.string.graph_relation_duplicate)) && visible("keep existing description")}
        assertEquals(original,main {s.document});assertFalse(main {s.canUndo})
        click(activity.getString(R.string.map_done));assertEquals(original,main {s.document});assertFalse(main {s.canUndo})
        click(activity.getString(R.string.cancel))
    }
    @Test fun removingAnEndpointWhileTheDialogIsOpenShowsAConflictWithoutCrashing()=scenario {activity,s->
        click(activity.getString(R.string.graph_relations))
        click(activity.getString(R.string.graph_sentence_related,node(activity,1,"A"),node(activity,2,"B")))
        main {s.operate {it.delete("b")}}
        waitUntil {visible(activity.getString(R.string.graph_relation_changed)) && visible(activity.getString(R.string.graph_missing_node))}
        val before=main {s.document}
        val done=runner.uiAutomation.rootInActiveWindow!!.findAccessibilityNodeInfosByText(activity.getString(R.string.map_done)).firstOrNull {it.isClickable}
        assertNotNull(done);assertFalse(done!!.isEnabled)
        click(activity.getString(R.string.cancel));assertEquals(before,main {s.document})
    }
}
