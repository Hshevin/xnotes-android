package com.xnotes.mindmap

import android.net.Uri
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class NodeSelectionSessionTest {
    private fun await(condition:()->Boolean) {
        repeat(300) {shadowOf(Looper.getMainLooper()).idle();if(condition()) return;Thread.sleep(10)}
        fail("Timed out waiting for map IO")
    }
    private class Target(doc:MindMapDocument):MapTarget {
        var bytes=MapCodec.encode(doc)
        var fail=false
        override fun read()=bytes
        override fun write(bytes:ByteArray) {if(fail) throw java.io.IOException("test failure");this.bytes=bytes}
    }
    private fun graph()=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null),
        MapNode(id="a",parent="r",x=300.0),MapNode(id="b",parent="a",x=600.0),
        MapNode(id="f",parent=null,free=true,x=900.0,y=200.0),
        MapNode(id="g",parent=null,free=true,x=1300.0,y=500.0)),
        relations=listOf(MapRelation(id="e",from="a",to="f")))
    private fun opened():Pair<MapSession,Target> {
        val context=RuntimeEnvironment.getApplication();val target=Target(graph())
        val file=File.createTempFile("node-selection-",".xmap",context.cacheDir)
        val session=MapSession(MapSourceAccess::inspect) {target}
        session.open(context,Uri.fromFile(file));await {!session.loading};assertTrue(session.setNodeSelectionMode(true))
        return session to target
    }
    @Test fun runtimeSelectionHasNoHistoryAndIsMutuallyExclusiveWithRelationSelection() {
        val (s,target)=opened();val original=s.document;val bytes=target.bytes.copyOf()
        assertTrue(s.selectNodes(setOf("f","g")));assertNull(s.selected);assertEquals(setOf("f","g"),s.selectedNodes)
        assertTrue(s.selectNodes(emptySet()));assertNull(s.selected);assertFalse(s.canUndo);assertFalse(s.dirty)
        assertTrue(s.selectNodes(setOf("a")));assertEquals("a",s.selected)
        assertFalse(s.selectNodes(setOf("missing")));assertEquals(setOf("a"),s.selectedNodes)
        assertTrue(s.selectRelation("e"));assertNull(s.selected);assertTrue(s.selectedNodes.isEmpty());assertFalse(s.nodeSelectionMode)
        assertTrue(s.setNodeSelectionMode(true));assertNull(s.selectedRelation)
        assertSame(original,s.document);assertArrayEquals(bytes,target.bytes);assertFalse(s.canUndo)
    }
    @Test fun groupMoveDeleteAndAlignmentCommitOneOperationAndSaveReopensPositions() {
        val (s,target)=opened();val original=s.document!!
        assertTrue(s.selectNodes(setOf("a","b","f")))
        assertTrue(s.moveNodes(s.captureNodes(original,s.selectedNodes)!!,25.0,35.0))
        assertEquals(625.0,s.document!!.node("b").x,0.0);assertEquals(original.node("b").parent,s.document!!.node("b").parent)
        s.undo();assertSame(original,s.document);assertFalse(s.canUndo)
        s.redo();val moved=s.document!!
        assertTrue(s.selectNodes(setOf("f","g")))
        assertTrue(s.alignNodes(s.captureNodes(moved,s.selectedNodes)!!,MapAlignment.TOP))
        val aligned=s.document!!;assertEquals(aligned.node("f").y,aligned.node("g").y,0.0)
        s.undo();assertSame(moved,s.document);s.redo();assertSame(aligned,s.document)
        s.save();await {!s.saving};assertEquals(aligned,MapCodec.decode(target.bytes))
        assertTrue(s.selectNodes(setOf("r","a")))
        assertTrue(s.deleteNodes(s.captureNodes(aligned,s.selectedNodes)!!))
        assertEquals(listOf("r","f","g"),s.document!!.nodes.map {it.id});assertTrue(s.document!!.relations.isEmpty())
        assertEquals(setOf("r"),s.selectedNodes)
        s.undo();assertSame(aligned,s.document);assertEquals(setOf("r"),s.selectedNodes)
    }
    @Test fun captureRejectsContentABASelectionABAAnotherOwnerAndAFileReload() {
        val (s,_)=opened();val doc=s.document!!;s.selectNodes(setOf("f","g"))
        val content=s.captureNodes(doc,s.selectedNodes)!!
        s.operate {it.update("r") {n->n.copy(text="change")}};s.undo();assertSame(doc,s.document)
        assertFalse(s.deleteNodes(content));assertEquals(doc,s.document)
        assertFalse(s.selectNodes(content,emptySet()));assertEquals(setOf("f","g"),s.selectedNodes)
        val selection=s.captureNodes(doc,s.selectedNodes)!!
        s.selectNodes(setOf("a"));s.selectNodes(setOf("f","g"))
        assertFalse(s.moveNodes(selection,10.0,10.0))
        assertFalse(s.selectNodes(selection,emptySet()));assertEquals(setOf("f","g"),s.selectedNodes)
        val other=opened().first
        assertFalse(other.alignNodes(s.captureNodes(doc,s.selectedNodes)!!,MapAlignment.LEFT))
        val reload=s.captureNodes(doc,s.selectedNodes)!!
        s.useFileVersion();await {!s.replacingDocument}
        assertFalse(s.nodeSelectionMode);assertFalse(s.deleteNodes(reload))
    }
    @Test fun hiddenSelectionsArePrunedAndUndoDoesNotRestoreSelectionHistory() {
        val (s,_)=opened();s.selectNodes(setOf("b","f"))
        s.operate {it.update("a") {n->n.copy(collapsed=true)}}
        assertEquals(setOf("f"),s.selectedNodes);s.undo();assertEquals(setOf("f"),s.selectedNodes)
        s.selectNodes(emptySet());s.operate {it.update("r") {n->n.copy(text="new")}}
        assertTrue(s.selectedNodes.isEmpty());assertNull(s.selected)
    }
    @Test fun busyInvalidAndNoOpOperationsNeverConsumeHistoryOrLeavePartialGeometry() {
        val (s,_)=opened();val doc=s.document!!;s.selectNodes(setOf("f","g"))
        val capture=s.captureNodes(doc,s.selectedNodes)!!
        s.inkContact=true
        assertFalse(s.setNodeSelectionMode(false));assertNull(s.captureNodes(doc,s.selectedNodes))
        assertFalse(s.moveNodes(capture,10.0,10.0));assertFalse(s.deleteNodes(capture))
        s.inkContact=false
        assertTrue(s.moveNodes(capture,0.0,0.0));assertFalse(s.canUndo)
        assertFalse(s.moveNodes(s.captureNodes(doc,s.selectedNodes)!!,MindMapDocument.MAX_COORD,0.0))
        assertSame(doc,s.document);assertFalse(s.canUndo)
        s.selectNodes(setOf("a","f"))
        assertFalse(s.alignNodes(s.captureNodes(doc,s.selectedNodes)!!,MapAlignment.LEFT));assertSame(doc,s.document)
    }
    @Test fun failedSavingKeepsTheBatchEditAndRecoverableMapWithoutTouchingTheTarget() {
        val (s,target)=opened();val bytes=target.bytes.copyOf();target.fail=true;s.selectNodes(setOf("a","f"))
        assertTrue(s.deleteNodes(s.captureNodes(s.document!!,s.selectedNodes)!!));val deleted=s.document
        s.save();await {!s.saving};assertTrue(s.saveFailed);assertTrue(s.dirty);assertTrue(s.recoverySafe)
        assertSame(deleted,s.document);assertArrayEquals(bytes,target.bytes)
        s.undo();assertEquals(graph().nodes,s.document!!.nodes);assertEquals(listOf("e"),s.document!!.relations.map {it.id})
    }
    @Test fun aBlankTapOrMarqueeCannotOverwriteANewerSessionSelectionBeforeViewRecomposition() {
        val context=RuntimeEnvironment.getApplication()
        for(marquee in listOf(false,true)) {
            val (s,_)=opened();s.selectNodes(setOf("f"))
            val view=MindMapView(context).apply {
                document=s.document!!;nodeSelectionMode=true;setSelectedNodes(s.selectedNodes)
                onCaptureNodes=s::captureNodes;onMoveNodes=s::moveNodes
                onSelectNodes={capture,ids->s.selectNodes(capture,ids)}
                onInkContact={s.inkContact=it};layout(0,0,1000,700);fit()
            }
            fun event(action:Int,x:Float,y:Float) {
                val e=android.view.MotionEvent.obtain(100,101,action,x,y,0)
                try {view.onTouchEvent(e)} finally {e.recycle()}
            }
            event(android.view.MotionEvent.ACTION_DOWN,10f,10f)
            if(marquee) event(android.view.MotionEvent.ACTION_MOVE,700f,500f)
            s.selectNodes(setOf("g")) // A real owner change not yet delivered to AndroidView.update.
            event(android.view.MotionEvent.ACTION_UP,if(marquee) 700f else 10f,if(marquee) 500f else 10f)
            assertEquals(setOf("g"),s.selectedNodes);assertFalse(s.canUndo)
        }
    }
    @Test fun aStaleViewSelectionCannotObtainANewTicketForItsOldToggleOrGroup() {
        val context=RuntimeEnvironment.getApplication()
        for(drag in listOf(false,true)) {
            val (s,_)=opened();s.selectNodes(setOf("f"));val original=s.document
            val view=MindMapView(context).apply {
                document=s.document!!;nodeSelectionMode=true;setSelectedNodes(s.selectedNodes)
                onCaptureNodes=s::captureNodes;onMoveNodes=s::moveNodes;onSelectNodes={capture,ids->s.selectNodes(capture,ids)}
                onInkContact={s.inkContact=it};layout(0,0,1000,700);fit()
            }
            s.selectNodes(setOf("g")) // The local View still uses f for toggle/group decisions at DOWN.
            val p=view.screenPoint(view.document.node(if(drag) "f" else "a").bounds.center)
            fun event(action:Int,dx:Float) {
                val e=android.view.MotionEvent.obtain(100,101,action,p.x.toFloat()+dx,p.y.toFloat(),0)
                try {view.onTouchEvent(e)} finally {e.recycle()}
            }
            event(android.view.MotionEvent.ACTION_DOWN,0f)
            if(drag) event(android.view.MotionEvent.ACTION_MOVE,60f)
            event(android.view.MotionEvent.ACTION_UP,if(drag) 60f else 0f)
            assertEquals(setOf("g"),s.selectedNodes);assertSame(original,s.document);assertFalse(s.canUndo)
        }
    }
}
