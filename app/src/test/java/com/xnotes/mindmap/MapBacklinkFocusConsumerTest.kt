package com.xnotes.mindmap

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.text.input.TextFieldValue
import com.xnotes.core.geometry.Rect
import com.xnotes.storage.NotebookStorage
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Actual canonical actor, immutable history and native event consumers. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class MapBacklinkFocusConsumerTest {
    private class Fixture(base:Context) {
        val root=File(base.cacheDir,"map-backlink-focus-${UUID.randomUUID()}").apply {mkdirs()}
        val context=object:ContextWrapper(base) {
            override fun getApplicationContext():Context=this
            override fun getFilesDir()=File(root,"files").apply {mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
        }
        val storage=NotebookStorage(context)
        val one=MapSource(documentId=UUID.randomUUID().toString(),fileName="Source.xnote",scope=SourceScope.PAGE,
            pageId=UUID.randomUUID().toString(),lastKnownPage=2,note="First source")
        val two=one.copy(id=UUID.randomUUID().toString(),pageId=UUID.randomUUID().toString(),lastKnownPage=1,note="Second source")
        val original=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,text="Root",x=0.0,y=0.0),
            MapNode(id="a",parent="r",text="Stored folded branch",collapsed=true,x=280.0,y=0.0),
            MapNode(id="n",parent="a",text="Independent body",x=560.0,y=0.0,sources=listOf(one,two),primarySource=two.id),
            MapNode(id="f",parent=null,free=true,text="Free",x=280.0,y=260.0)))
        val file=File(root,"graph.xmap").apply {writeBytes(MapCodec.encode(original))}
        val uri=Uri.fromFile(file)
        val leases=mutableListOf<MapDocumentLease>()
        lateinit var session:MapSession
        suspend fun acquire():MapDocumentLease {
            val lease=MapDocuments.acquire(context,uri).also {leases+=it}
            await {!lease.session.loading};assertFalse(lease.session.loadFailed);session=lease.session
            return lease
        }
        suspend fun await(condition:()->Boolean)=withContext(Dispatchers.Default) {
            withTimeout(10000) {while(!withContext(Dispatchers.Main) {condition()}) delay(1)}
        }
        fun view()=MindMapView(context).apply {
            document=session.document!!;documentEpoch=session.interactionEpoch;documentRevision=session.contentRevision
            expandedAncestors=session.navigationExpandedAncestors;visibilityRevision=session.visibilityRevision
            onSelect={session.selected=it};onInkContact={session.inkContact=it};onChange={session.commit(it)}
            onCaptureNodes=session::captureNodes
            onSelectNodes={capture,ids->session.selectNodes(capture,ids)}
            onMoveNodes={capture,dx,dy->session.moveNodes(capture,dx,dy).also {if(it) document=session.document!!}}
            layout(0,0,1200,700);fit();setSelectedNodes(session.selectedNodes)
        }
        suspend fun close() {
            if(::session.isInitialized) {
                session.textDraft?.let {session.cancelTextDraft(it.token)}
                session.textRestore?.let {session.completeTextRestore(it.token)}
                session.saveAttempt().await()
            }
            leases.filter {it.alive}.forEach {it.close().join()}
        }
    }
    private fun scenario(block:suspend (Fixture)->Unit) {
        val f=Fixture(RuntimeEnvironment.getApplication());var failure:Throwable?=null
        val instance=NotebookStorage::class.java.getDeclaredField("instance").apply {isAccessible=true}
        val previous=instance.get(null)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val job=scope.launch {
            try {withContext(Dispatchers.Main) {instance.set(null,f.storage);f.acquire();withTimeout(20000) {block(f)}}}
            catch(error:Throwable) {failure=error}
            finally {withContext(NonCancellable+Dispatchers.Main) {f.close();instance.set(null,previous)}}
        }
        try {
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
            while(!job.isCompleted && System.nanoTime()<deadline) {
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications();shadowOf(Looper.getMainLooper()).idle();Thread.sleep(1)
            }
            assertTrue("Actual backlink consumer did not settle",job.isCompleted);failure?.let {throw it}
        } finally {scope.cancel();if(job.isCompleted) f.root.deleteRecursively()}
    }
    private var clock=100L
    private fun send(view:MindMapView,action:Int,x:Float,y:Float) {
        val props=arrayOf(MotionEvent.PointerProperties().apply {id=7;toolType=MotionEvent.TOOL_TYPE_FINGER})
        val points=arrayOf(MotionEvent.PointerCoords().apply {this.x=x;this.y=y;pressure=.8f})
        val event=MotionEvent.obtain(100,clock++,action,1,props,points,0,0,1f,1f,888,0,InputDevice.SOURCE_TOUCHSCREEN,0)
        try {assertTrue(view.onTouchEvent(event))} finally {event.recycle()}
    }
    @Test fun observationsIncludeRetainedZeroLeaseActorsAndMembershipSurvivesOnlyMetadataC1()=scenario {f->
        val s=f.session;val bytes=f.file.readBytes()
        val observation=MapDocuments.observations(f.context).single()
        assertSame(s,observation.session);assertSame(s.document,observation.document);assertTrue(observation.current())
        val member=observation.captureMembership("n",f.two.id,f.two.target)!!
        assertTrue(s.beginText("n",MindMapView.Viewport(1f,0f,0f),1200,700))
        f.leases.single().close().join()
        val retained=MapDocuments.observations(f.context).single();assertSame(s,retained.session)
        val panel=SourceListEditor(s,s.sourceEditSnapshot("n")!!)
        try {assertTrue(panel.note(f.two.id,"C1 note"));assertTrue(panel.move(f.two.id,-1))} finally {panel.close()}
        assertFalse(observation.current())
        val removal=s.captureBacklinkRemoval(f.original.id,"n",f.two.id,f.two.target,member)
        assertNotNull("A committed note/sort edit must not retire the source member",removal)
        assertArrayEquals(bytes,f.file.readBytes())
        val reacquired=f.acquire();assertSame(s,reacquired.session)
        s.cancelTextDraft(s.textDraft!!.token);s.completeTextRestore(s.textRestore!!.token)
    }
    @Test fun focusRevealsTheFoldedTargetAndRealNodeSelectionMovesItWithoutChangingStoredFoldOrNavigationHistory()=scenario {f->
        val s=f.session
        assertTrue(s.operate {it.update("f") {node->node.copy(text="Temporary")}});s.undo();assertTrue(s.canRedo)
        val before=s.document!!;val encoded=MapCodec.encode(before);val revision=s.contentRevision
        val ticket=s.captureBacklinkFocus(before.id,"n",f.two.id,f.two.target)!!
        assertTrue(s.applyBacklinkFocus(ticket));assertEquals("n",s.selected)
        assertEquals("n",s.navigationRevealTarget);assertTrue("a" in s.navigationExpandedAncestors)
        assertTrue(s.effectiveVisible().any {it.id=="n"});assertTrue(before.node("a").collapsed)
        assertSame(before,s.document);assertArrayEquals(encoded,MapCodec.encode(s.document!!));assertEquals(revision,s.contentRevision);assertTrue(s.canRedo)
        s.selected=null;assertTrue(s.setNodeSelectionMode(true))
        val view=f.view();view.nodeSelectionMode=true
        val p=view.screenPoint(before.node("n").bounds.center)
        send(view,MotionEvent.ACTION_DOWN,p.x.toFloat(),p.y.toFloat());send(view,MotionEvent.ACTION_UP,p.x.toFloat(),p.y.toFloat())
        assertEquals(setOf("n"),s.selectedNodes)
        val old=s.document!!.node("n").x
        send(view,MotionEvent.ACTION_DOWN,p.x.toFloat(),p.y.toFloat())
        send(view,MotionEvent.ACTION_MOVE,p.x.toFloat()+80,p.y.toFloat()+20)
        send(view,MotionEvent.ACTION_UP,p.x.toFloat()+80,p.y.toFloat()+20)
        assertNotEquals(old,s.document!!.node("n").x);assertTrue(s.document!!.node("a").collapsed)
        s.undo();assertEquals(old,s.document!!.node("n").x,0.0);assertTrue(s.canRedo)
        s.redo();assertNotEquals(old,s.document!!.node("n").x)
    }
    @Test fun temporaryFoldUsesNoHistoryNormalFoldUsesOneAndVisibilityChangesRetireSelectionTickets()=scenario {f->
        val s=f.session
        assertTrue(s.applyBacklinkFocus(s.captureBacklinkFocus(f.original.id,"n",f.two.id,f.two.target)!!))
        s.selected="a";val capture=s.captureNodes(s.document!!,setOf("a"))!!
        val before=s.document!!;val revision=s.contentRevision;val visible=s.visibilityRevision
        assertFalse(s.isEffectivelyCollapsed("a"));assertTrue(s.toggleCollapse("a"))
        assertTrue(s.isEffectivelyCollapsed("a"));assertEquals("a",s.navigationRevealTarget)
        assertSame(before,s.document);assertEquals(revision,s.contentRevision);assertFalse(s.canUndo)
        assertTrue(s.visibilityRevision>visible);assertFalse(s.moveNodes(capture,10.0,10.0))
        assertFalse(s.effectiveVisible().any {it.id=="n"})
        assertTrue(s.toggleCollapse("r"));assertTrue(s.document!!.node("r").collapsed);assertTrue(s.canUndo)
        assertEquals("r",s.navigationRevealTarget)
        s.undo();assertFalse(s.document!!.node("r").collapsed);assertFalse(s.canUndo);assertTrue(s.canRedo)
    }
    @Test fun focusAndRemovalTicketsKeepLatestMetadataButNeverReviveDeletedTargetOrReloadedMemberships()=scenario {f->
        val s=f.session;val member=MapDocuments.observations(f.context).single().captureMembership("n",f.two.id,f.two.target)!!
        val focus=s.captureBacklinkFocus(f.original.id,"n",f.two.id,f.two.target,member)!!
        val removal=s.captureBacklinkRemoval(f.original.id,"n",f.two.id,f.two.target,member)!!
        val panel=SourceListEditor(s,s.sourceEditSnapshot("n")!!)
        try {assertTrue(panel.note(f.two.id,"New note"));assertTrue(panel.move(f.two.id,-1))} finally {panel.close()}
        assertTrue(s.backlinkFocusCurrent(focus));assertTrue(s.backlinkRemovalCurrent(removal))
        assertTrue(s.operate {it.update("n") {node->node.copy(sources=node.sources.map {if(it.id==f.two.id) it.copy(region=Rect(1.0,2.0,30.0,40.0)) else it})}})
        s.undo();assertFalse(s.backlinkFocusCurrent(focus));assertFalse(s.backlinkRemovalCurrent(removal))
        assertNull(s.captureBacklinkFocus(f.original.id,"n",f.two.id,f.two.target,member))
        val fresh=s.captureBacklinkFocus(f.original.id,"n",f.two.id,f.two.target)!!
        s.useFileVersion();f.await {!s.replacingDocument}
        assertFalse(s.backlinkFocusCurrent(fresh));assertNull(s.navigationRevealTarget)
    }
    @Test fun removingOnlyTheConfirmedSourceMergesIntoAnActiveTextDraftAndIsExactlyOneUndo()=scenario {f->
        val s=f.session;val ticket=s.captureBacklinkRemoval(f.original.id,"n",f.two.id,f.two.target)!!
        assertTrue(s.beginText("n",MindMapView.Viewport(1f,0f,0f),1200,700))
        val textToken=s.textDraft!!.token;assertTrue(s.updateTextDraft(textToken,TextFieldValue("Unconfirmed text survives")))
        val bytes=f.file.readBytes();val other=s.document!!.node("n").sources.single {it.id==f.one.id}
        assertEquals(MapBacklinkRemovalResult.Applied,s.removeBacklinkSource(ticket))
        assertEquals(listOf(other),s.document!!.node("n").sources);assertNull(s.document!!.node("n").primarySource)
        assertEquals("Independent body",s.document!!.node("n").text)
        assertEquals(textToken,s.textDraft!!.token);assertEquals("Unconfirmed text survives",s.textDraft!!.value.text)
        assertArrayEquals(bytes,f.file.readBytes());assertTrue(s.canUndo)
        assertEquals(MapBacklinkRemovalResult.Changed,s.removeBacklinkSource(ticket))
        s.undo();assertEquals(listOf(f.one,f.two),s.document!!.node("n").sources);assertEquals(f.two.id,s.document!!.node("n").primarySource)
        assertFalse(s.canUndo);assertTrue(s.canRedo);assertFalse(s.backlinkRemovalCurrent(ticket))
        assertEquals(textToken,s.textDraft!!.token);assertEquals("Unconfirmed text survives",s.textDraft!!.value.text)
    }
    @Test fun anAppliedRemovalCanFocusOnlyItsOriginalNodeAndUndoRedoNeverRevivesThatReceipt()=scenario {f->
        val s=f.session;val removal=s.captureBacklinkRemoval(f.original.id,"n",f.two.id,f.two.target)!!
        assertNull(s.captureRemovedBacklinkFocus(removal))
        assertEquals(MapBacklinkRemovalResult.Applied,s.removeBacklinkSource(removal))
        val focus=s.captureRemovedBacklinkFocus(removal)!!;assertFalse(focus.opensSource)
        val before=s.document!!;val revision=s.contentRevision
        assertTrue(s.applyBacklinkFocus(focus));assertEquals("n",s.selected)
        assertSame(before,s.document);assertEquals(revision,s.contentRevision)
        val state=MapPresentationState();state.synchronize(s)
        assertTrue(state.requestBacklinkFocus(s,focus))
        assertTrue(state.applyPendingBacklinkFocus(s,f.view()))
        assertNull(state.sourcesFor);assertNull(state.sourceFocus)
        s.undo();assertFalse(s.backlinkFocusCurrent(focus));assertNull(s.captureRemovedBacklinkFocus(removal))
        s.redo();assertFalse(s.backlinkFocusCurrent(focus));assertNull(s.captureRemovedBacklinkFocus(removal))
        val again=s.captureBacklinkRemoval(f.original.id,"n",f.one.id,f.one.target)!!
        assertEquals(MapBacklinkRemovalResult.Applied,s.removeBacklinkSource(again))
        val after=s.captureRemovedBacklinkFocus(again)!!
        s.useFileVersion();f.await {!s.replacingDocument}
        assertFalse(s.backlinkFocusCurrent(after));assertNull(s.captureRemovedBacklinkFocus(again))
    }

}
