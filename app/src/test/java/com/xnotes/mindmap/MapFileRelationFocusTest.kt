package com.xnotes.mindmap

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Looper
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

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class MapFileRelationFocusTest {
    private class Fixture(base:Context) {
        val root=File(base.cacheDir,"map-file-relation-${UUID.randomUUID()}").apply {mkdirs()}
        val context=object:ContextWrapper(base) {
            override fun getApplicationContext():Context=this
            override fun getFilesDir()=File(root,"files").apply {mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
        }
        val storage=NotebookStorage(context)
        val fromId=UUID.randomUUID().toString()
        val toId=UUID.randomUUID().toString()
        val from=MapNode(id="from",parent="a",kind=NodeKind.REFERENCE,text="File A",x=-900.0,y=0.0,
            sources=listOf(MapSource(documentId=fromId,fileName="A.xnote")),fileCardId=fromId)
        val to=MapNode(id="to",parent="b",kind=NodeKind.REFERENCE,text="File B",x=1000.0,y=600.0,
            sources=listOf(MapSource(documentId=toId,fileName="B.xnote")),fileCardId=toId)
        val relation=MapRelation(id="edge",from=from.id,to=to.id,kind=RelationKind.DEPENDS_ON,label="Because")
        val original=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,text="Root"),
            MapNode(id="a",parent="r",text="Branch A",collapsed=true),
            MapNode(id="b",parent="r",text="Branch B",collapsed=true),from,to),relations=listOf(relation))
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
            assertTrue("Actual file relation consumer did not settle",job.isCompleted);failure?.let {throw it}
        } finally {scope.cancel();if(job.isCompleted) f.root.deleteRecursively()}
    }
    @Test fun observedRelationSurvivesMovementButNotSemanticAbaOrHistoryReplay()=scenario {f->
        val s=f.session
        fun capture()=MapDocuments.observations(f.context).single().captureFileRelation("edge")!!
        val membership=capture()
        assertTrue(membership.current())
        assertTrue(s.operate {it.update("from") {n->n.copy(x=n.x+75,text="Independent title")}})
        assertTrue(membership.current())
        assertTrue(s.operate {it.copy(relations=it.relations.map {r->r.copy(label="Changed")})})
        assertFalse(membership.current())
        assertTrue(s.operate {it.copy(relations=listOf(f.relation))})
        assertFalse("Restoring the same value cannot reissue an old relation",membership.current())
        val replay=capture();s.undo();s.redo();assertFalse(replay.current())
        val deletion=capture()
        assertTrue(s.operate {it.copy(relations=emptyList())})
        assertTrue(s.operate {it.copy(relations=listOf(f.relation))})
        assertFalse(deletion.current())
    }
    @Test fun endpointRetargetAndDeletedRecreatedCardNeverRevalidateTheOldMembership()=scenario {f->
        val s=f.session
        fun capture()=MapDocuments.observations(f.context).single().captureFileRelation("edge")!!
        val retarget=capture();val different=UUID.randomUUID().toString()
        assertTrue(s.operate {it.update("from") {n->n.copy(fileCardId=different,
            sources=listOf(MapSource(documentId=different,fileName="C.xnote")))}})
        assertFalse(retarget.current())
        assertTrue(s.operate {it.update("from") {f.from}})
        assertFalse(retarget.current())
        val replaced=capture()
        assertTrue(s.operate {it.copy(nodes=it.nodes.filterNot {n->n.id=="from"},relations=emptyList())})
        assertTrue(s.operate {it.copy(nodes=it.nodes+f.from,relations=listOf(f.relation))})
        assertFalse(replaced.current())
    }
    @Test fun exactRelationFocusRevealsBothCollapsedBranchesFramesBothEndsAndEditsTheRealLine()=scenario {f->
        val s=f.session
        val member=MapDocuments.observations(f.context).single().captureFileRelation("edge")!!
        val ticket=s.captureFileRelationFocus(f.original.id,f.relation,f.fromId,f.toId,member)!!
        val before=s.document!!;val bytes=MapCodec.encode(before);val revision=s.contentRevision
        assertTrue(s.applyFileRelationFocus(ticket))
        assertEquals("edge",s.selectedRelation);assertTrue(s.selectedNodes.isEmpty())
        assertTrue(s.effectiveVisible().map {it.id}.containsAll(listOf("from","to")))
        assertEquals(setOf("a","b","r"),s.navigationExpandedAncestors)
        assertSame(before,s.document);assertEquals(revision,s.contentRevision);assertFalse(s.canUndo)
        val state=MapPresentationState();state.synchronize(s)
        assertTrue(state.requestFileRelationFocus(s,ticket))
        val view=f.view();view.updateSelection(null,s.selectedRelation)
        assertFalse(state.applyPendingBacklinkFocus(s,view,cameraReady=false))
        assertNotNull(state.pendingFileRelationFocus)
        assertTrue(state.applyPendingBacklinkFocus(s,view))
        assertNull(state.pendingFileRelationFocus);assertNull(state.sourcesFor)
        assertEquals("edge",s.selectedRelation)
        val viewport=view.viewport();val scale=viewport.zoom*view.resources.displayMetrics.density
        for(node in listOf(f.from,f.to)) {
            val left=node.x*scale+viewport.panX;val top=node.y*scale+viewport.panY
            assertTrue("Both cards fit in the stable real host",left>=0 && top>=0 &&
                left+node.width*scale<=view.width && top+node.height*scale<=view.height)
        }
        assertArrayEquals(bytes,MapCodec.encode(s.document!!))
        assertTrue(s.document!!.node("a").collapsed);assertTrue(s.document!!.node("b").collapsed)
        val edited=f.relation.copy(label="Edited in actual relation panel")
        assertTrue(s.changeRelation(f.relation,edited) is RelationEditResult.Applied)
        assertEquals(edited,s.document!!.relations.single());assertEquals("edge",s.selectedRelation)
        s.undo();assertEquals(f.relation,s.document!!.relations.single())
    }
    @Test fun freshSelectionOrInteractionNeverConsumesAnOldPendingRelationCamera()=scenario {f->
        val s=f.session;val member=MapDocuments.observations(f.context).single().captureFileRelation("edge")!!
        val ticket=s.captureFileRelationFocus(f.original.id,f.relation,f.fromId,f.toId,member)!!
        assertTrue(s.applyFileRelationFocus(ticket))
        val state=MapPresentationState();state.synchronize(s);assertTrue(state.requestFileRelationFocus(s,ticket))
        s.selected="r"
        assertFalse(state.applyPendingBacklinkFocus(s,f.view()));assertNull(state.pendingFileRelationFocus)
        assertEquals("r",s.selected)
    }
}
