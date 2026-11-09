package com.xnotes.mindmap

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.xnotes.R
import com.xnotes.core.geometry.Pt
import com.xnotes.pen.PenTestRunner
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual Compose actions and native marquee events. Compilation is not device acceptance. */
class MapNodeSelectionEditingHostTest {
    private val runner get()=PenTestRunner.instance
    private fun <T> main(block:()->T):T {
        var result:Any?=null;var failure:Throwable?=null
        runner.runOnMainSync {try {result=block()} catch(error:Throwable) {failure=error}}
        failure?.let {throw it}
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun waitUntil(check:()->Boolean) {
        repeat(500) {runner.waitForIdleSync();if(check()) return;Thread.sleep(10)}
        fail("Node selection host did not settle")
    }
    private fun views(root:View):List<View> = listOf(root)+
        if(root is ViewGroup) (0 until root.childCount).flatMap {views(root.getChildAt(it))} else emptyList()
    private fun graph(activity:MindMapActivity)=views(activity.window.decorView).filterIsInstance<MindMapView>().single()
    private fun visible(label:String)=runner.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label).orEmpty().isNotEmpty()
    private fun click(label:String) {
        var node:AccessibilityNodeInfo?=null
        waitUntil {node=runner.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label)?.firstOrNull {it.isClickable && it.isEnabled};node!=null}
        assertTrue(node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK));runner.waitForIdleSync()
    }
    private fun composeSettled(activity:MindMapActivity) {
        val frames=CountDownLatch(1)
        main {activity.window.decorView.postOnAnimation {activity.window.decorView.postOnAnimation {frames.countDown()}}}
        assertTrue("Composition did not reach two frames",frames.await(5,TimeUnit.SECONDS))
        runner.waitForIdleSync()
    }
    private fun scenario(block:(MindMapActivity,MapSession,File,ByteArray)->Unit) {
        val context=runner.targetContext
        val source=MindMapDocument()
        val sourceBytes=MapCodec.encode(source)
        val sourceFile=File.createTempFile("selection-source-",".xmap",context.cacheDir).apply {writeBytes(sourceBytes)}
        val document=MindMapDocument(nodes=listOf(
            MapNode(id="root",parent=null,text="Root",x=-700.0,y=-120.0,width=180.0,height=100.0,
                sources=listOf(MapSource(documentId=source.id,fileName=sourceFile.name))),
            MapNode(id="f",parent=null,free=true,text="First free",collapsed=true,x=-100.0,y=-80.0,width=140.0,height=80.0,
                sources=listOf(MapSource(documentId=source.id,fileName=sourceFile.name))),
            MapNode(id="fc",parent="f",text="Hidden child",x=-100.0,y=20.0),
            MapNode(id="g",parent=null,free=true,text="Second free",x=180.0,y=80.0,width=160.0,height=100.0),
            MapNode(id="h",parent=null,free=true,text="Untouched free",x=520.0,y=-100.0,width=160.0,height=90.0)),
            relations=listOf(MapRelation(id="selected-edge",from="f",to="g"),MapRelation(id="kept-edge",from="root",to="h")))
        val file=File.createTempFile("selection-host-",".xmap",context.cacheDir).apply {writeBytes(MapCodec.encode(document))}
        val activity=runner.startActivitySync(MindMapActivity.intent(context,Uri.fromFile(file).toString(),"Node selection host")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MindMapActivity
        val session=main {activity.documentSession}
        try {
            waitUntil {main {!session.loading && session.document!=null && graph(activity).width>0 && graph(activity).height>0}}
            block(activity,session,sourceFile,sourceBytes)
        } finally {
            main {activity.finish()};runner.waitForIdleSync()
            waitUntil {main {!session.saving}}
            file.delete();sourceFile.delete()
        }
    }
    private fun selectFreePair(activity:MindMapActivity,session:MapSession) {
        click(activity.getString(R.string.graph_node_selection))
        waitUntil {main {session.nodeSelectionMode && graph(activity).nodeSelectionMode}}
        main {
            val view=graph(activity);view.fit()
            val bounds=view.document.node("f").bounds.union(view.document.node("g").bounds).outset(20.0)
            val start=view.screenPoint(bounds.topLeft);val end=view.screenPoint(Pt(bounds.right,bounds.bottom))
            assertTrue(start.x>=0 && start.y>=0 && end.x<view.width && end.y<view.height)
            val down=SystemClock.uptimeMillis()
            for((index,entry) in listOf(MotionEvent.ACTION_DOWN to start,MotionEvent.ACTION_MOVE to end,MotionEvent.ACTION_UP to end).withIndex()) {
                val properties=MotionEvent.PointerProperties().apply {id=9;toolType=MotionEvent.TOOL_TYPE_FINGER}
                val coordinates=MotionEvent.PointerCoords().apply {x=entry.second.x.toFloat();y=entry.second.y.toFloat();pressure=1f}
                val event=MotionEvent.obtain(down,down+index*16,entry.first,1,arrayOf(properties),arrayOf(coordinates),
                    0,0,1f,1f,888,0,InputDevice.SOURCE_TOUCHSCREEN,0)
                try {view.onTouchEvent(event)} finally {event.recycle()}
            }
        }
        waitUntil {main {session.selectedNodes==setOf("f","g")}}
        composeSettled(activity)
    }
    private fun confirmDelete(activity:MindMapActivity) {
        waitUntil {visible(activity.getString(R.string.graph_delete_nodes_confirm))}
        click(activity.getString(R.string.delete))
        waitUntil {!visible(activity.getString(R.string.graph_delete_nodes_confirm))}
    }

    @Test fun realMarqueeAlignDeleteCancelAndConfirmHaveOneUndoEachAndKeepRootAndSource()=scenario {activity,s,source,sourceBytes->
        val original=main {s.document!!}
        selectFreePair(activity,s)
        assertSame(original,main {s.document});assertFalse(main {s.canUndo})
        click(activity.getString(R.string.graph_align_top))
        waitUntil {main {s.document!==original}}
        val aligned=main {s.document!!}
        assertEquals(aligned.node("f").y,aligned.node("g").y,0.0)
        assertSame(original.root,aligned.root);assertSame(original.node("f").sources,aligned.node("f").sources)
        assertSame(original.node("h"),aligned.node("h"))
        click(activity.getString(R.string.delete));waitUntil {visible(activity.getString(R.string.graph_delete_nodes_confirm))}
        click(activity.getString(R.string.cancel));assertSame(aligned,main {s.document})
        click(activity.getString(R.string.undo));assertSame(original,main {s.document});assertFalse(main {s.canUndo})
        click(activity.getString(R.string.redo));assertSame(aligned,main {s.document})
        composeSettled(activity)
        click(activity.getString(R.string.delete));confirmDelete(activity)
        val deleted=main {s.document!!}
        assertEquals(listOf("root","h"),deleted.nodes.map {it.id});assertEquals(listOf("kept-edge"),deleted.relations.map {it.id})
        assertSame(original.root,deleted.root);assertArrayEquals(sourceBytes,source.readBytes())
        click(activity.getString(R.string.undo));assertSame(aligned,main {s.document})
        click(activity.getString(R.string.undo));assertSame(original,main {s.document});assertFalse(main {s.canUndo})
        click(activity.getString(R.string.redo));assertSame(aligned,main {s.document})
        click(activity.getString(R.string.redo));assertSame(deleted,main {s.document})
        assertArrayEquals(sourceBytes,source.readBytes())
    }

    /** Obtain the actual composed accessibility action, retaining its closure across a Main-turn change. */
    private fun composedClick(activity:MindMapActivity,label:String):()->Boolean {
        fun nodes(node:SemanticsNode):List<SemanticsNode> = listOf(node)+node.children.flatMap(::nodes)
        val owners=views(activity.window.decorView).mapNotNull {view->
            // AndroidComposeView's class is internal; its public SemanticsOwner getter is also used by a11y.
            view.javaClass.methods.firstOrNull {it.name=="getSemanticsOwner" && it.parameterCount==0}?.invoke(view) as? SemanticsOwner
        }
        return owners.flatMap {nodes(it.rootSemanticsNode)}.firstNotNullOfOrNull {node->
            val config=node.config
            if(config.getOrNull(SemanticsProperties.Text)?.any {it.text==label}==true && !config.contains(SemanticsProperties.Disabled))
                config.getOrNull(SemanticsActions.OnClick)?.action else null
        } ?: error("No composed action: $label")
    }
    @Test fun oldCompositionActionsAndOpenDeleteDialogCannotOverwriteNewSelectionOrUndoABA()=scenario {activity,s,source,sourceBytes->
        val original=main {s.document!!};selectFreePair(activity,s)
        val oldAlign=main {composedClick(activity,activity.getString(R.string.graph_align_top))}
        main {
            assertTrue(s.selectNodes(setOf("f","h")))
            assertTrue(oldAlign()) // No frame can recompose between the owner change and this old closure.
            assertSame(original,s.document);assertEquals(setOf("f","h"),s.selectedNodes);assertFalse(s.canUndo)
        }
        main {assertTrue(s.selectNodes(setOf("f","g")))};composeSettled(activity)
        val oldDelete=main {composedClick(activity,activity.getString(R.string.delete))}
        main {assertTrue(s.selectNodes(setOf("f","h")));assertTrue(oldDelete())}
        confirmDelete(activity)
        assertSame(original,main {s.document});assertEquals(setOf("f","h"),main {s.selectedNodes});assertFalse(main {s.canUndo})
        main {assertTrue(s.selectNodes(setOf("f","g")))};composeSettled(activity)
        click(activity.getString(R.string.delete));waitUntil {visible(activity.getString(R.string.graph_delete_nodes_confirm))}
        main {
            assertTrue(s.operate {it.update("root") {node->node.copy(text="Temporary owner edit")}})
            s.undo();assertSame(original,s.document);assertEquals(setOf("f","g"),s.selectedNodes)
        }
        confirmDelete(activity)
        assertSame(original,main {s.document});assertFalse(main {s.canUndo});assertTrue(main {s.canRedo})
        assertEquals(setOf("f","g"),main {s.selectedNodes});assertArrayEquals(sourceBytes,source.readBytes())
    }
}
