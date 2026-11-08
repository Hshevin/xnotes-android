package com.xnotes.mindmap

import android.content.Intent
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
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
    private fun scenario(twoEdges:Boolean=false,multiline:Boolean=false,block:(MindMapActivity,MapSession)->Unit) {
        val context=runner.targetContext
        val edges=listOf(MapRelation(id="first",from="a",to="b",label="first description"))+
            if(twoEdges) listOf(MapRelation(id="existing",from="a",to="c",kind=RelationKind.DEPENDS_ON,label="keep existing description")) else emptyList()
        val document=MindMapDocument(nodes=listOf(MapNode(id="a",parent=null,text="A"),MapNode(id="b",parent="a",text="B"),
            MapNode(id="c",parent="a",text=if(multiline) "C\n第二行\nthird line" else "C")),relations=edges)
        val file=File.createTempFile("relation-host-",".xmap",context.cacheDir).apply {writeBytes(MapCodec.encode(MapLayout.arrange(document)))}
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
    private fun views(root:View):List<View> = listOf(root)+(if(root is ViewGroup) (0 until root.childCount).flatMap {views(root.getChildAt(it))} else emptyList())
    private fun graph(activity:MindMapActivity)=views(activity.window.decorView).filterIsInstance<MindMapView>().single()
    private fun target(activity:MindMapActivity,id:String) = main {
        val v=graph(activity);val point=v.screenPoint(v.document.node(id).bounds.center)
        for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
            val properties=MotionEvent.PointerProperties().apply {this.id=9;toolType=MotionEvent.TOOL_TYPE_FINGER}
            val coordinates=MotionEvent.PointerCoords().apply {x=point.x.toFloat();y=point.y.toFloat();pressure=1f}
            val e=MotionEvent.obtain(100,200,action,1,arrayOf(properties),arrayOf(coordinates),0,0,1f,1f,888,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0)
            try {v.onTouchEvent(e)} finally {e.recycle()}
        }
    }
    @Test fun canvasConnectionSurvivesHostLayoutThenCancelAndConfirmUseTheSameTransaction()=scenario {activity,s->
        waitUntil {main {graph(activity).width>0 && graph(activity).height>0}}
        val original=main {s.document!!};val view=main {graph(activity)}
        val size=main {view.width to view.height};val camera=main {view.viewport()}
        click(activity.getString(R.string.graph_connect_canvas))
        waitUntil {main {view.connectionFrom=="a"} && visible(activity.getString(R.string.graph_connect_hint))}
        // Let composition and layout settle: the HUD must not resize and immediately cancel the mode.
        repeat(5) {runner.waitForIdleSync();Thread.sleep(20)}
        main {assertSame(view,graph(activity));assertEquals(size,view.width to view.height);assertEquals(camera,view.viewport());assertEquals("a",view.connectionFrom)}
        target(activity,"c");waitUntil {visible(activity.getString(R.string.graph_endpoint_to,node(activity,3,"C")))}
        assertEquals(original,main {s.document});assertFalse(main {s.canUndo})
        click(activity.getString(R.string.cancel));assertEquals(original,main {s.document});assertFalse(main {s.canUndo})
        click(activity.getString(R.string.graph_connect_canvas));target(activity,"c")
        waitUntil {visible(activity.getString(R.string.graph_endpoint_to,node(activity,3,"C")))}
        click(activity.getString(R.string.map_done))
        val changed=main {s.document!!};assertEquals(2,changed.relations.size);assertEquals(original.nodes,changed.nodes)
        assertTrue(changed.relations.any {it.from=="a" && it.to=="c" && it.kind==RelationKind.RELATED})
        main {s.undo()};assertEquals(original,main {s.document});assertFalse(main {s.canUndo})
        click(activity.getString(R.string.cancel))
    }
    @Test fun keyboardTargetWithAMultilineTitleKeepsTheHostSizeAndLockedSource()=scenario(multiline=true) {activity,s->
        runner.setInTouchMode(true)
        waitUntil {main {graph(activity).width>0 && graph(activity).height>0}}
        val original=main {s.document!!};val view=main {graph(activity)};val size=main {view.width to view.height}
        click(activity.getString(R.string.graph_connect_canvas));waitUntil {main {view.connectionFrom=="a"}}
        assertTrue(main {view.hasFocus() && view.isFocusableInTouchMode})
        main {
            repeat(2) {
                activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_DPAD_DOWN))
                activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,android.view.KeyEvent.KEYCODE_DPAD_DOWN))
            }
        }
        waitUntil {main {s.selected=="c"}}
        repeat(5) {runner.waitForIdleSync();Thread.sleep(20)}
        main {assertSame(view,graph(activity));assertEquals(size,view.width to view.height);assertEquals("a",view.connectionFrom)}
        main {
            activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_ENTER))
            activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,android.view.KeyEvent.KEYCODE_ENTER))
        }
        waitUntil {visible(activity.getString(R.string.graph_description))}
        assertEquals(original,main {s.document});click(activity.getString(R.string.map_done))
        assertTrue(main {s.document!!.relations.any {it.from=="a" && it.to=="c"}})
        main {s.undo()};assertEquals(original,main {s.document});click(activity.getString(R.string.cancel))
    }
}
