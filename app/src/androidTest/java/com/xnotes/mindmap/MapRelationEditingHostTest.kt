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
    private fun scenario(twoEdges:Boolean=false,multiline:Boolean=false,overlap:Boolean=false,block:(MindMapActivity,MapSession)->Unit) {
        val context=runner.targetContext
        val edges=listOf(MapRelation(id="first",from="a",to="b",label="first description"))+
            if(twoEdges || overlap) listOf(MapRelation(id="existing",from="a",to=if(overlap) "b" else "c",kind=RelationKind.DEPENDS_ON,label="keep existing description")) else emptyList()
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
    private fun enterText(value:String) {
        fun editable(node:AccessibilityNodeInfo):AccessibilityNodeInfo? {
            if(node.isEditable) return node
            for(i in 0 until node.childCount) node.getChild(i)?.let {child->editable(child)?.let {return it}}
            return null
        }
        var field:AccessibilityNodeInfo?=null
        waitUntil {field=runner.uiAutomation.rootInActiveWindow?.let(::editable);field!=null}
        assertTrue(field!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)
        }));runner.waitForIdleSync()
    }
    private fun blank(activity:MindMapActivity) = main {
        val v=graph(activity);val x=v.width/2f;val y=v.height-v.resources.displayMetrics.density
        for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
            val properties=MotionEvent.PointerProperties().apply {id=9;toolType=MotionEvent.TOOL_TYPE_FINGER}
            val coordinates=MotionEvent.PointerCoords().apply {this.x=x;this.y=y;pressure=1f}
            val event=MotionEvent.obtain(100,200,action,1,arrayOf(properties),arrayOf(coordinates),0,0,1f,1f,888,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0)
            try {v.onTouchEvent(event)} finally {event.recycle()}
        }
    }
    @Test fun blankTextCreationConfirmsNodeAndLineAsOneUndoAndKeepsTheDropCenter()=scenario {activity,s->
        val original=main {s.document!!};click(activity.getString(R.string.graph_connect_canvas));blank(activity)
        waitUntil {main {s.connectedDraft!=null}}
        val draft=main {s.connectedDraft!!};assertEquals(original,main {s.document});assertFalse(main {s.canUndo})
        click(activity.getString(R.string.map_text_node));enterText("New connected text")
        waitUntil {main {s.connectedDraft?.node?.text=="New connected text"}}
        click(activity.getString(R.string.map_done));waitUntil {main {s.connectedDraft==null}}
        val changed=main {s.document!!};val node=changed.node(draft.nodeId)
        assertEquals(draft.center,node.bounds.center);assertEquals("New connected text",node.text);assertTrue(node.free)
        assertEquals(original.nodes,changed.nodes.filter {it.id!=node.id})
        assertTrue(changed.relations.any {it.from==draft.from && it.to==node.id && !it.directed})
        main {s.undo()};assertEquals(original,main {s.document});assertFalse(main {s.canUndo})
        main {s.redo()};assertEquals(changed,main {s.document})
    }
    @Test fun cancellingInkAndReferenceDraftsLeavesNoNodeLineOrHistory()=scenario {activity,s->
        val original=main {s.document!!}
        for(kind in listOf(R.string.map_ink_node,R.string.graph_reference)) {
            click(activity.getString(R.string.graph_connect_canvas));blank(activity)
            waitUntil {main {s.connectedDraft!=null}};click(activity.getString(kind))
            click(activity.getString(R.string.cancel));waitUntil {main {s.connectedDraft==null}}
            assertEquals(original,main {s.document});assertFalse(main {s.canUndo})
        }
    }
    @Test fun inspectedFileReferenceStillNeedsConfirmationAndKeepsItsIndependentTitle()=scenario {activity,s->
        val original=main {s.document!!};val file=File.createTempFile("connected-source-",".xmap",activity.cacheDir)
        val bytes=MapCodec.encode(MindMapDocument());file.writeBytes(bytes)
        try {
            click(activity.getString(R.string.graph_connect_canvas));blank(activity)
            waitUntil {main {s.connectedDraft!=null}};click(activity.getString(R.string.graph_reference))
            val draft=main {s.connectedDraft!!};enterText("Independent title")
            main {val request=s.beginConnectedSource(draft.token)!!;assertTrue(s.receiveConnectedSource(request.id,Uri.fromFile(file)))}
            waitUntil {main {s.connectedDraft?.node?.sources?.isNotEmpty()==true && s.connectedDraft?.loadingSource==false}}
            assertEquals(original,main {s.document});assertFalse(main {s.canUndo})
            click(activity.getString(R.string.map_done));waitUntil {main {s.connectedDraft==null}}
            val added=main {s.document!!.node(draft.nodeId)};assertEquals("Independent title",added.text)
            assertEquals(file.name,added.sources.single().fileName);assertEquals(SourceScope.FILE,added.sources.single().scope)
            assertArrayEquals(bytes,file.readBytes());main {s.undo()};assertEquals(original,main {s.document})
        } finally {file.delete()}
    }
    @Test fun recreatedCreationDialogKeepsItsSessionTextButCancelStillAddsNothing()=scenario {originalActivity,s->
        var activity=originalActivity
        try {
            val original=main {s.document!!};click(activity.getString(R.string.graph_connect_canvas));blank(activity)
            waitUntil {main {s.connectedDraft!=null}};click(activity.getString(R.string.map_text_node));enterText("Draft survives recreation")
            waitUntil {main {s.connectedDraft?.node?.text=="Draft survives recreation"}}
            val token=main {s.connectedDraft!!.token}
            val monitor=runner.addMonitor(MindMapActivity::class.java.name,null,false)
            main {activity.recreate()}
            val recreated=runner.waitForMonitorWithTimeout(monitor,5000) as? MindMapActivity;runner.removeMonitor(monitor)
            assertNotNull(recreated);activity=recreated!!
            assertSame(s,main {ViewModelProvider(activity)[MapSession::class.java]})
            waitUntil {visible("Draft survives recreation")}
            assertEquals(token,main {s.connectedDraft!!.token});assertEquals(original,main {s.document});assertFalse(main {s.canUndo})
            click(activity.getString(R.string.cancel));waitUntil {main {s.connectedDraft==null}}
            assertEquals(original,main {s.document});assertFalse(main {s.canUndo})
        } finally {if(activity!==originalActivity) main {activity.finish()}}
    }
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
    private fun clickScrollingDialog(label:String) {
        fun scroll(node:AccessibilityNodeInfo):Boolean {
            if(node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
            for(i in 0 until node.childCount) node.getChild(i)?.let {if(scroll(it)) return true}
            return false
        }
        repeat(20) {
            val root=runner.uiAutomation.rootInActiveWindow
            val target=root?.findAccessibilityNodeInfosByText(label)?.firstOrNull {it.isClickable && it.isEnabled}
            if(target!=null) {assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK));runner.waitForIdleSync();return}
            if(root!=null) scroll(root)
            runner.waitForIdleSync();Thread.sleep(20)
        }
        fail("Dialog action was not reachable: $label")
    }
    private fun tapRelationMiddle(activity:MindMapActivity,s:MapSession,expected:String) {
        main {s.selected=null}
        waitUntil {main {graph(activity).let {it.width>0 && it.height>0 && it.selected==null && it.selectedRelation==null}}}
        main {
            val v=graph(activity);val edge=v.document.relations.first();val a=v.document.node(edge.from).bounds.center;val b=v.document.node(edge.to).bounds.center
            val p=v.screenPoint(com.xnotes.core.geometry.Pt((a.x+b.x)/2,(a.y+b.y)/2))
            val down=android.os.SystemClock.uptimeMillis()
            for((index,action) in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP).withIndex()) {
                val props=MotionEvent.PointerProperties().apply {id=9;toolType=MotionEvent.TOOL_TYPE_FINGER}
                val coords=MotionEvent.PointerCoords().apply {x=p.x.toFloat();y=p.y.toFloat();pressure=1f}
                val event=MotionEvent.obtain(down,down+index,action,1,arrayOf(props),arrayOf(coords),0,0,1f,1f,888,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0)
                try {v.onTouchEvent(event)} finally {event.recycle()}
            }
        }
        waitUntil {main {s.selectedRelation==expected && s.selected==null}}
    }
    @Test fun canvasSelectedExistingRelationCancelsOrEditsOneHistoryEntryAndKeepsItsIdentity()=scenario {activity,s->
        val original=main {s.document!!}
        tapRelationMiddle(activity,s,"first")
        assertEquals(original,main {s.document});assertFalse(main {s.canUndo})
        click(activity.getString(R.string.graph_edit_relation));waitUntil {visible("first description")}
        enterText("cancelled description");click(activity.getString(R.string.cancel))
        assertEquals(original,main {s.document});assertFalse(main {s.canUndo});assertEquals("first",main {s.selectedRelation})
        click(activity.getString(R.string.graph_edit_relation));enterText("canvas edit")
        click(activity.getString(R.string.map_done))
        val changed=main {s.document!!}
        assertEquals(original.nodes,changed.nodes);assertEquals(original.relations.single().copy(label="canvas edit"),changed.relations.single())
        assertEquals("first",main {s.selectedRelation});assertNull(main {s.selected})
        click(activity.getString(R.string.cancel))
        main {s.undo()};assertEquals(original,main {s.document});assertFalse(main {s.canUndo});assertEquals("first",main {s.selectedRelation})
        main {s.redo()};assertEquals(changed,main {s.document});assertEquals("first",main {s.selectedRelation});assertNull(main {s.selected})
    }
    @Test fun completelyOverlappingRelationsRemainIndividuallySelectableEditableAndDeletableInTheList()=scenario(overlap=true) {activity,s->
        val original=main {s.document!!}
        tapRelationMiddle(activity,s,"existing")
        click(activity.getString(R.string.graph_edit_relation));waitUntil {visible("keep existing description")}
        clickScrollingDialog(activity.getString(R.string.graph_relation_list))
        click(activity.getString(R.string.graph_sentence_related,node(activity,1,"A"),node(activity,2,"B")))
        waitUntil {main {s.selectedRelation=="first" && s.selected==null}}
        enterText("first changed from list");click(activity.getString(R.string.map_done))
        val changed=main {s.document!!}
        assertEquals(original.nodes,changed.nodes);assertEquals("keep existing description",changed.relations.first {it.id=="existing"}.label)
        assertEquals("first changed from list",changed.relations.first {it.id=="first"}.label)
        click(activity.getString(R.string.graph_sentence_depends,node(activity,1,"A"),node(activity,2,"B")))
        waitUntil {main {s.selectedRelation=="existing" && s.selected==null}}
        clickScrollingDialog(activity.getString(R.string.delete))
        val deleted=main {s.document!!}
        assertEquals(original.nodes,deleted.nodes);assertEquals(listOf(changed.relations.first {it.id=="first"}),deleted.relations)
        click(activity.getString(R.string.cancel))
        main {s.undo()};assertEquals(changed,main {s.document})
        main {s.undo()};assertEquals(original,main {s.document});assertFalse(main {s.canUndo})
        main {s.redo();s.redo()};assertEquals(deleted,main {s.document})
        main {s.selected=null};click(activity.getString(R.string.graph_relations))
        click(activity.getString(R.string.graph_sentence_related,node(activity,1,"A"),node(activity,2,"B")))
        waitUntil {visible("first changed from list") && main {s.selectedRelation=="first"}}
        click(activity.getString(R.string.cancel))
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
