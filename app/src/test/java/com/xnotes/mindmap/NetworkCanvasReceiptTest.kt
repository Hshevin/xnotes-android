package com.xnotes.mindmap

import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual controller receipts survive ordinary C1, but never role swaps or undo/redo ABA. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class NetworkCanvasReceiptTest {
    private fun seeds()=listOf(
        MapFileCardSeed(UUID.randomUUID().toString(),"A.xnote",120.0,180.0),
        MapFileCardSeed(UUID.randomUUID().toString(),"B.xnote",620.0,180.0))
    private suspend fun await(check:()->Boolean)=withContext(Dispatchers.Default) {
        withTimeout(15000) {while(!withContext(Dispatchers.Main) {check()}) delay(1)}
    }
    @Test fun aRetainedExistingImportCannotRetryOrViewAfterRolesSwapOrUndoRedoRestoresTheSameFileSet()=MapBacklinkLibraryTest().scenario {f->
        val original=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,text="A")))
        val file=f.graph("existing-receipt.xmap",original);val held=f.acquire(f.uri(file))
        val external=original.update("r") {it.copy(text="External C")};file.writeBytes(MapCodec.encode(external))
        val externalBytes=file.readBytes();val seeds=seeds()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val flow=withContext(Dispatchers.Main) {NetworkToCanvasController(f.context,NetworkCanvasSelection(seeds) {true},scope)}
        try {
            withContext(Dispatchers.Main) {assertTrue(flow.chooseExisting(f.uri(file)));assertTrue(flow.confirm())}
            await {flow.step==NetworkCanvasStep.SAVE_FAILED && !flow.busy}
            val pending=f.journalSnapshot();assertTrue(pending.isNotEmpty())
            withContext(Dispatchers.Main) {
                assertSame(held.session,flow.targetSession);assertTrue(flow.saveOutcome is MapSaveOutcome.Conflict)
                assertTrue(flow.canRetrySave);assertTrue(flow.canView)
                val open=flow.captureOpen()!!;assertTrue(open.current())
                val session=flow.targetSession!!;val imported=session.document!!
                val cards=flow.nodeIds.map {imported.node(it)}.sortedBy {it.fileCardId}
                assertEquals(2,cards.size)
                val first=cards[0];val second=cards[1]
                val swapped=imported.copy(nodes=imported.nodes.map {node->
                    val uuid=when(node.id) {first.id->second.fileCardId;second.id->first.fileCardId;else->null}
                    if(uuid==null) node else node.copy(fileCardId=uuid,sources=node.sources.map {it.copy(documentId=uuid)})
                })
                swapped.validate();assertTrue(session.operate {swapped});session.cancelPendingAutosave()
                assertFalse(open.current());open.finished(false)
                fun rejected() {
                    assertFalse(flow.canRetrySave);assertFalse(flow.canView);assertNull(flow.captureOpen());assertFalse(flow.retrySave())
                }
                rejected()
                session.undo();session.cancelPendingAutosave();assertEquals(imported,session.document);rejected()
                session.redo();session.cancelPendingAutosave();assertEquals(swapped,session.document);rejected()
                session.undo();session.cancelPendingAutosave();assertEquals(imported,session.document);rejected()
                assertEquals(seeds.map {it.documentId}.toSet(),session.document!!.nodes.mapNotNull {it.fileCardId}.toSet())
            }
            assertArrayEquals(externalBytes,file.readBytes());assertEquals(pending,f.journalSnapshot())
            assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
        } finally {
            withContext(Dispatchers.Main) {flow.close()}?.join();scope.cancel()
        }
    }
    @Test fun aNewCompleteCanvasReceiptCannotViewAfterContentUndoRedoEvenWhenEveryCardNodeAndUuidIsUnchanged()=MapBacklinkLibraryTest().scenario {f->
        val target=File(f.notes,"new-receipt.xmap").apply {writeBytes(byteArrayOf())};val seeds=seeds()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val flow=withContext(Dispatchers.Main) {NetworkToCanvasController(f.context,NetworkCanvasSelection(seeds) {true},scope)}
        try {
            withContext(Dispatchers.Main) {assertTrue(flow.chooseNew(f.uri(target)));assertTrue(flow.confirm())}
            await {flow.step==NetworkCanvasStep.ADDED && !flow.busy}
            val bytes=target.readBytes();val saved=MapCodec.decode(bytes)
            assertEquals(MapOrganization.RELATION_CANVAS,saved.organization);assertEquals(2,saved.nodes.size)
            assertEquals(seeds.map {it.documentId}.toSet(),saved.nodes.mapNotNull {it.fileCardId}.toSet())
            withContext(Dispatchers.Main) {
                val session=flow.targetSession!!;assertFalse(session.canUndo);assertTrue(flow.canView)
                val open=flow.captureOpen()!!;assertTrue(open.current())
                val ids=flow.nodeIds.toSet();val roles=session.document!!.nodes.associate {it.id to it.fileCardId}
                val edited=session.document!!.update(ids.first()) {it.copy(text="Ordinary C1 title")}
                assertTrue(session.operate {edited});session.cancelPendingAutosave()
                assertTrue("An ordinary latest-body edit does not retire the receipt",open.current())
                session.undo();session.cancelPendingAutosave();assertEquals(saved,session.document);assertFalse(open.current())
                session.redo();session.cancelPendingAutosave();assertEquals(edited,session.document);assertFalse(open.current())
                assertEquals(ids,flow.nodeIds);assertEquals(roles,session.document!!.nodes.associate {it.id to it.fileCardId})
                open.finished(false)
                assertFalse(flow.canView);assertNull(flow.captureOpen());assertFalse(flow.canRetrySave)
            }
            assertArrayEquals(bytes,target.readBytes());assertEquals(1,f.writes.get());assertEquals(0,f.deletes.get())
        } finally {
            withContext(Dispatchers.Main) {flow.close()}?.join();scope.cancel()
        }
    }
}
