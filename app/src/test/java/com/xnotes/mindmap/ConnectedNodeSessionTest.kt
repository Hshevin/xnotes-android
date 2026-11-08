package com.xnotes.mindmap

import android.net.Uri
import android.os.Looper
import com.xnotes.core.geometry.Pt
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class ConnectedNodeSessionTest {
    private val source=MapSource(documentId="00000000-0000-0000-0000-000000000001",fileName="Original")
    private fun await(condition:()->Boolean) {
        repeat(300) {shadowOf(Looper.getMainLooper()).idle();if(condition()) return;Thread.sleep(10)}
        fail("Timed out waiting for connected-node work")
    }
    private fun session(reader:(android.content.Context,Uri,()->Unit)->MapSource={_,_,check->check();source}):Pair<MapSession,File> {
        val context=RuntimeEnvironment.getApplication()
        val file=File.createTempFile("connected-",".xmap",context.cacheDir).apply {
            writeBytes(MapCodec.encode(MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,kind=NodeKind.INK),MapNode(id="other",parent="r")))))
        }
        return MapSession(reader).also {it.open(context,Uri.fromFile(file));await {!it.loading}} to file
    }
    private fun start(s:MapSession,kind:NodeKind):ConnectedNodeDraft {
        assertTrue(s.beginConnectedNode("r",Pt(600.0,400.0)));val draft=s.connectedDraft!!
        assertTrue(s.chooseConnectedKind(draft.token,kind));return s.connectedDraft!!
    }
    @Test fun confirmationUsesLatestContentAndSizeKeepsDropCenterAndOneUndoThenSaves() {
        val (s,file)=session();val draft=start(s,NodeKind.TEXT);val node=draft.node!!
        assertTrue(s.editConnectedNode(draft.token,node.copy(text="Draft",width=400.0,height=160.0)))
        val stroke=MapInk(listOf(InkPoint(20.0,30.0,.8,10.0)))
        s.operate {it.update("r") {n->n.copy(ink=listOf(stroke),sources=listOf(source))}}
        val before=s.document;val selected=s.selected
        assertEquals(selected,s.selected);assertFalse(s.document!!.nodes.any {it.id==draft.nodeId})
        assertTrue(s.finishConnectedNode(draft.token));val saved=s.document!!;val added=saved.node(draft.nodeId)
        assertEquals(Pt(600.0,400.0),added.bounds.center);assertEquals("Draft",added.text)
        assertEquals(before!!.root,saved.root);assertEquals(draft.nodeId,s.selected);assertNull(s.connectedDraft)
        assertFalse(s.finishConnectedNode(draft.token));s.undo();assertEquals(before,s.document)
        s.redo();assertEquals(saved,s.document);s.save();await {!s.saving};assertEquals(saved,MapCodec.decode(file.readBytes()))
    }
    @Test fun cancelAndOldInputCallbacksCannotAffectAnotherDraftOrAddHistory() {
        val (s,_)=session();val before=s.document;val draft=start(s,NodeKind.INK)
        assertTrue(s.editConnectedNode(draft.token,draft.node!!.copy(text="kept in session",width=500.0)))
        assertEquals("kept in session",s.connectedDraft!!.node!!.text)
        s.cancelConnectedNode(draft.token);assertEquals(before,s.document);assertFalse(s.canUndo)
        val next=start(s,NodeKind.TEXT)
        assertFalse(s.editConnectedNode(draft.token,draft.node));assertFalse(s.finishConnectedNode(draft.token))
        s.cancelConnectedNode(draft.token);assertEquals(next,s.connectedDraft)
    }
    @Test fun deletedStartNodeAndLimitsDoNotPartiallyCommitAndDraftCanBeCancelled() {
        val (s,_)=session();assertTrue(s.beginConnectedNode("other",Pt(0.0,0.0)));val token=s.connectedDraft!!.token
        s.chooseConnectedKind(token,NodeKind.TEXT);s.operate {it.delete("other")};val before=s.document
        assertFalse(s.finishConnectedNode(token));assertEquals(ConnectedDraftProblem.CHANGED,s.connectedDraft!!.problem)
        assertEquals(before,s.document);s.cancelConnectedNode(token)
        val next=start(s,NodeKind.TEXT)
        assertFalse(s.finishConnectedNode(next.token,next.node!!.copy(width=5000.0)));assertEquals(before,s.document)
        assertEquals(ConnectedDraftProblem.LIMIT,s.connectedDraft!!.problem)
    }
    @Test fun cancelledPickerAndStaleAttemptNeverCreateOrReplaceAReferenceDraft() {
        val (s,_)=session();val before=s.document;val draft=start(s,NodeKind.REFERENCE)
        assertFalse(s.finishConnectedNode(draft.token));val old=s.beginConnectedSource(draft.token)!!
        assertNull(s.beginConnectedSource(draft.token));assertTrue(s.receiveConnectedSource(old.id,null))
        val fresh=s.beginConnectedSource(draft.token)!!
        assertFalse(s.receiveConnectedSource(old.id,Uri.parse("file:///stale.xnote")))
        assertEquals(fresh,s.connectedSourceRequest);assertEquals(before,s.document)
        assertTrue(s.receiveConnectedSource(fresh.id,Uri.parse("file:///source.xnote")));await {s.connectedDraft?.loadingSource==false}
        assertEquals(listOf(source),s.connectedDraft!!.node!!.sources);assertEquals(before,s.document);assertFalse(s.canUndo)
        assertTrue(s.finishConnectedNode(draft.token));assertEquals(listOf(source),s.document!!.node(draft.nodeId).sources)
        s.undo();assertEquals(before,s.document);assertFalse(s.canUndo)
    }
    @Test fun lateInspectorAfterCancellationDoesNotReviveItsNodeOrOverwriteAnotherDraft() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val (s,_)=session {_,_,_->entered.countDown();check(release.await(5,TimeUnit.SECONDS));source}
        val before=s.document;val draft=start(s,NodeKind.REFERENCE);val request=s.beginConnectedSource(draft.token)!!
        val beforeJobs=s.viewModelScope.coroutineContext[Job]!!.children.toSet()
        s.receiveConnectedSource(request.id,Uri.parse("file:///slow.xnote"));assertTrue(entered.await(5,TimeUnit.SECONDS))
        val worker=s.viewModelScope.coroutineContext[Job]!!.children.first {it !in beforeJobs}
        s.cancelConnectedNode(draft.token);val next=start(s,NodeKind.TEXT);release.countDown()
        await {worker.isCompleted}
        assertEquals(next,s.connectedDraft);assertEquals(before,s.document);assertFalse(s.canUndo)
    }
    @Test fun reloadAndCloseRetireDraftsBeforeTheirAsyncWorkAndLateResultsAreRejected() {
        for(close in listOf(false,true)) {
            val (s,_)=session();val draft=start(s,NodeKind.REFERENCE);val request=s.beginConnectedSource(draft.token)!!
            if(close) s.save({}) else s.useFileVersion()
            assertNull(s.connectedDraft);assertFalse(s.receiveConnectedSource(request.id,Uri.parse("file:///late.xnote")))
            await {!s.saving};assertFalse(s.finishConnectedNode(draft.token))
        }
    }
    @Test fun aPausedReloadCannotAdmitDraftsOrContentChangesBoundToItsOldDocument() {
        val context=RuntimeEnvironment.getApplication();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        var bytes=MapCodec.encode(MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,text="old"))))
        val reload=java.util.concurrent.atomic.AtomicBoolean(false)
        val target=object:MapTarget {
            override fun read():ByteArray {if(reload.get()) {entered.countDown();check(release.await(5,TimeUnit.SECONDS))};return bytes}
            override fun write(value:ByteArray) {bytes=value}
        }
        val file=File.createTempFile("connected-reload-",".xmap",context.cacheDir)
        val s=MapSession({_,_,_->source},{target});s.open(context,Uri.fromFile(file));await {!s.loading}
        val draft=start(s,NodeKind.REFERENCE);val request=s.beginConnectedSource(draft.token)!!
        val original=s.document!!;bytes=MapCodec.encode(original.update("r") {it.copy(text="new file")});reload.set(true)
        s.useFileVersion();assertTrue(s.replacingDocument);assertTrue(entered.await(5,TimeUnit.SECONDS))
        try {
            assertFalse(s.beginConnectedNode("r",Pt(1.0,2.0)));assertFalse(s.operate {it.update("r") {n->n.copy(text="late")}})
            assertFalse(s.receiveConnectedSource(request.id,Uri.parse("file:///late.xnote")));assertEquals(original,s.document)
        } finally {release.countDown()}
        await {!s.replacingDocument};assertNull(s.connectedDraft);assertEquals("new file",s.document!!.root.text)
        assertTrue(s.beginConnectedNode("r",Pt(10.0,20.0)))
    }
    @Test fun sourceFailureAndLaunchFailureKeepAnUncommittedDraftAndCanRetry() {
        val attempts=java.util.concurrent.atomic.AtomicInteger()
        val (s,_)=session {_,_,check->check();if(attempts.incrementAndGet()==1) throw java.io.IOException("unreadable");source}
        val before=s.document;val draft=start(s,NodeKind.REFERENCE)
        val failedLaunch=s.beginConnectedSource(draft.token)!!;s.failConnectedSource(failedLaunch.id)
        assertEquals(ConnectedDraftProblem.SOURCE,s.connectedDraft!!.problem);assertEquals(0,attempts.get())
        val failedRead=s.beginConnectedSource(draft.token)!!;s.receiveConnectedSource(failedRead.id,Uri.parse("file:///missing.xnote"))
        await {s.connectedDraft?.loadingSource==false};assertEquals(ConnectedDraftProblem.SOURCE,s.connectedDraft!!.problem)
        assertFalse(s.finishConnectedNode(draft.token));assertEquals(before,s.document);assertFalse(s.canUndo)
        val success=s.beginConnectedSource(draft.token)!!;s.receiveConnectedSource(success.id,Uri.parse("file:///source.xnote"))
        await {s.connectedDraft?.loadingSource==false};assertNull(s.connectedDraft!!.problem)
        assertTrue(s.finishConnectedNode(draft.token));assertEquals(2,attempts.get())
    }
    @Test fun titleEditsDuringSourceInspectionAndStaleBodyConfirmationPreserveTheActualSource() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val (s,_)=session {_,_,check->entered.countDown();kotlin.check(release.await(5,TimeUnit.SECONDS));check();source}
        val draft=start(s,NodeKind.REFERENCE);val request=s.beginConnectedSource(draft.token)!!
        s.receiveConnectedSource(request.id,Uri.parse("file:///source.xnote"));assertTrue(entered.await(5,TimeUnit.SECONDS))
        try {assertTrue(s.editConnectedNode(draft.token,draft.node!!.copy(text="Title while checking")))} finally {release.countDown()}
        await {s.connectedDraft?.loadingSource==false}
        assertEquals("Title while checking",s.connectedDraft!!.node!!.text);assertEquals(listOf(source),s.connectedDraft!!.node!!.sources)
        assertTrue(s.finishConnectedNode(draft.token,draft.node!!.copy(text="Confirmed title")))
        val node=s.document!!.node(draft.nodeId);assertEquals("Confirmed title",node.text);assertEquals(listOf(source),node.sources)
        assertEquals(source.id,node.primarySource)
    }
    @Test fun clearingTheViewModelRetiresItsDraftAndPickerTicket() {
        val (s,_)=session();val draft=start(s,NodeKind.REFERENCE);val request=s.beginConnectedSource(draft.token)!!
        val store=androidx.lifecycle.ViewModelStore();store.put("map",s);store.clear()
        assertNull(s.connectedDraft);assertNull(s.connectedSourceRequest)
        assertFalse(s.receiveConnectedSource(request.id,Uri.parse("file:///late.xnote")))
    }
    @Test fun ordinarySourcePickerFromBeforeReloadCannotBindItsResultToTheNewVersion() {
        val (s,_)=session();val before=s.document;val old=s.beginExistingSource("r")!!
        s.useFileVersion();await {!s.replacingDocument}
        val fresh=s.beginExistingSource("r")!!
        assertFalse(s.receiveExistingSource(old,Uri.parse("file:///late.xnote")))
        assertNull(s.beginExistingSource("r"));assertTrue(s.receiveExistingSource(fresh,null))
        assertEquals(before,s.document);assertFalse(s.canUndo)
    }
    @Test fun ordinarySourceIoReleasedAfterReloadCannotAddHistoryOrOverwriteTheNewFile() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val (s,file)=session {_,_,_->entered.countDown();kotlin.check(release.await(5,TimeUnit.SECONDS));source}
        val beforeJobs=s.viewModelScope.coroutineContext[Job]!!.children.toSet()
        val request=s.beginExistingSource("r")!!;s.receiveExistingSource(request,Uri.parse("file:///slow.xnote"))
        assertTrue(entered.await(5,TimeUnit.SECONDS));val worker=s.viewModelScope.coroutineContext[Job]!!.children.first {it !in beforeJobs}
        val remote=s.document!!.update("r") {it.copy(text="replacement")};val bytes=MapCodec.encode(remote);file.writeBytes(bytes)
        try {s.useFileVersion();await {!s.replacingDocument};assertEquals(remote,s.document)} finally {release.countDown()}
        await {worker.isCompleted};assertEquals(remote,s.document);assertFalse(s.canUndo);assertFalse(s.dirty);assertFalse(s.sourceFailed)
        assertArrayEquals(bytes,file.readBytes())
    }
    @Test fun sourceControlsPreserveConcurrentBodyAndRejectAStaleListOrReplacedEpoch() {
        val (s,_)=session();s.operate {it.update("r") {n->n.copy(sources=listOf(source))}}
        val edit=s.sourceEditSnapshot("r")!!
        val ink=MapInk(listOf(InkPoint(10.0,20.0,.6,30.0)))
        s.operate {it.update("r") {n->n.copy(text="latest body",ink=listOf(ink))}}
        assertTrue(s.editSources(edit,edit.node.copy(sources=listOf(source.copy(note="note")))))
        assertEquals("latest body",s.document!!.root.text);assertEquals(listOf(ink),s.document!!.root.ink)
        val stale=s.sourceEditSnapshot("r")!!;val another=source.copy(id="another")
        s.operate {it.update("r") {n->n.copy(sources=n.sources+another)}};val current=s.document
        assertFalse(s.editSources(stale,stale.node.copy(sources=emptyList())));assertEquals(current,s.document)
        s.save();await {!s.saving};val old=s.sourceEditSnapshot("r")!!;s.useFileVersion();await {!s.replacingDocument}
        assertFalse(s.editSources(old,old.node.copy(primarySource=source.id)))
    }
    @Test fun ordinarySourceWaitsForPenThenMergesWithTheLatestInkAsOneSourceOperation() {
        val read=CountDownLatch(1)
        val (s,file)=session {_,_,check->check();read.countDown();source}
        val beforeJobs=s.viewModelScope.coroutineContext[Job]!!.children.toSet();val request=s.beginExistingSource("r")!!
        s.inkContact=true;s.receiveExistingSource(request,Uri.parse("file:///source.xnote"));assertTrue(read.await(5,TimeUnit.SECONDS))
        val worker=s.viewModelScope.coroutineContext[Job]!!.children.first {it !in beforeJobs}
        shadowOf(Looper.getMainLooper()).idle();assertTrue(s.document!!.root.sources.isEmpty())
        val stroke=MapInk(listOf(InkPoint(10.0,20.0,.6,30.0)));assertTrue(s.commitInk("r",emptyList(),listOf(stroke)))
        val beforeSource=s.document
        // No Composition runs here to send global snapshot notifications to the waiting flow.
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {s.inkContact=false}
        await {worker.isCompleted}
        assertEquals(listOf(source),s.document!!.root.sources);assertEquals(listOf(stroke),s.document!!.root.ink)
        val saved=s.document;s.undo();assertEquals(beforeSource,s.document);s.redo();assertEquals(saved,s.document)
        s.save();await {!s.saving};assertEquals(saved,MapCodec.decode(file.readBytes()))
    }
    @Test fun consecutiveSourceCallbacksAdvanceTheirOwnSnapshotWithoutOverwritingOtherRows() {
        val (s,_)=session();val other=source.copy(id="second")
        s.operate {it.update("r") {n->n.copy(sources=listOf(source,other))}}
        val initial=s.sourceEditSnapshot("r")!!;val editor=SourceListEditor(s,initial)
        val first={text:String->editor.note(source.id,text)};val second={text:String->editor.note(other.id,text)}
        assertTrue(first("a"));editor.sync(initial);assertTrue(first("ab"));assertTrue(second("second note"));assertTrue(first("abc"))
        assertEquals(listOf("abc","second note"),s.document!!.root.sources.map {it.note});assertFalse(editor.conflict)
        val newSource=source.copy(id="external")
        s.operate {it.update("r") {n->n.copy(sources=n.sources+newSource)}};val before=s.document
        assertFalse(first("must retry"));assertTrue(editor.conflict);assertEquals(before,s.document)
        editor.sync(s.sourceEditSnapshot("r")!!);assertTrue(first("abcd"))
        assertEquals(listOf("abcd","second note",""),s.document!!.root.sources.map {it.note})
        editor.close();val closed=s.document;assertFalse(first("late"));assertEquals(closed,s.document)
    }
}
