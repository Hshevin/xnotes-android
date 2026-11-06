package com.xnotes.core

import com.xnotes.core.model.*
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.core.history.TransferItems
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue

class ReferenceIdentityTest {
    private fun ink()=Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),listOf(Sample(1.0,2.0,.7)))
    @Test fun identityIsAssignedOnceAndConcurrentReadersSeeOneStableUuid() {
        val identity=ReferenceIdentity();assertNull(identity.id)
        val start=CountDownLatch(1);val ids=ConcurrentLinkedQueue<String>()
        val workers=(0 until 16).map {Thread {start.await();ids+=identity.assign()}.apply {start()}}
        start.countDown();workers.forEach {it.join()}
        assertEquals(16,ids.size)
        assertEquals(1,ids.toSet().size);assertEquals(ids.first(),identity.id)
        identity.restore(identity.id!!)
        try {identity.restore("00000000-0000-0000-0000-000000000001");fail("must not replace")} catch(_:IllegalStateException) {}
    }
    @Test fun validPersistedIdentityCanBeRestoredConcurrentlyButInvalidUuidCannotEnter() {
        val identity=ReferenceIdentity();val id="00000000-0000-0000-0000-000000000001"
        val failures=ConcurrentLinkedQueue<Throwable>()
        val workers=(0 until 16).map {Thread {try {identity.restore(id)} catch(e:Throwable) {failures+=e}}.apply {start()}};workers.forEach {it.join()}
        assertTrue(failures.isEmpty())
        assertEquals(id,identity.id);assertEquals(id,identity.assign())
        try {ReferenceIdentity().restore("bad-id");fail("invalid id")} catch(_:IllegalArgumentException) {}
    }
    @Test fun snapshotKeepsIdsAndMembershipWhileCopyStartsIndependentIdentities() {
        val doc=Document.blank();val page=doc.pages.first();val stroke=ink().also {page.items.add(it)}
        assertNull(stroke.referenceIdentity.id);doc.enableReferenceAnchors()
        val saved=doc.snapshot();val pageId=page.referenceIdentity.id;val itemId=stroke.referenceIdentity.id
        assertTrue(saved.hasReferenceAnchors);assertEquals(pageId,saved.pages.first().referenceIdentity.id)
        assertEquals(itemId,saved.pages.first().referenceIdForWrite(stroke))
        val later=ink().also {page.items.add(it)};assertNull(later.referenceIdentity.id)
        doc.snapshot();assertNotNull(later.referenceIdentity.id);assertEquals(listOf(stroke),saved.pages.first().items)
        val copied=page.deepCopy(FakeTextMeasurer());val independent=Document(pages=mutableListOf(copied));independent.enableReferenceAnchors()
        assertNotEquals(pageId,copied.referenceIdentity.id);assertNotEquals(itemId,copied.items.first().referenceIdentity.id)
        assertTrue(copied.items.map {it.referenceIdentity.id}.toSet().intersect(page.items.map {it.referenceIdentity.id}.toSet()).isEmpty())
    }
    @Test fun transferLayerAndUndoKeepObjectIdentityWhileNewCanvasMembersGetNewIdsAtSnapshot() {
        val doc=Document.blank();val from=doc.pages.first();val to=Page(100.0,100.0).also {doc.pages.add(it)}
        val item=ink().also {from.items.add(it)};doc.enableReferenceAnchors();val id=item.referenceIdentity.id
        to.layers.create("Destination")
        val command=TransferItems(listOf(TransferItems.Transfer(from,to,item,10.0,10.0)))
        command.redo();assertEquals(id,item.referenceIdentity.id);assertTrue(item in to.items)
        command.undo();assertEquals(id,item.referenceIdentity.id);assertTrue(item in from.items)
        val canvas=InfiniteDocument();val a=ink().also(canvas::add);canvas.enableReferenceAnchors()
        val saved=canvas.snapshotForWrite();val b=ink().also(canvas::add);canvas.snapshotForWrite()
        assertNotEquals(a.referenceIdentity.id,b.referenceIdentity.id);assertEquals(listOf(a),saved.items)
        assertEquals(a.referenceIdentity.id,saved.referenceIdForWrite(a))
    }
    @Test fun duplicateObjectOrPageMembershipAndUnknownCopyAreRejected() {
        val item=ink();val first=Page(100.0,100.0,mutableListOf(item));val second=Page(100.0,100.0,mutableListOf(item))
        try {Document(pages=mutableListOf(first,second)).enableReferenceAnchors();fail("same object twice")} catch(_:IllegalArgumentException) {}
        try {Document(pages=mutableListOf(first,first)).enableReferenceAnchors();fail("same page twice")} catch(_:IllegalArgumentException) {}
        val unknown=object:CanvasItem by ink() {}
        try {unknown.deepCopy(FakeTextMeasurer());fail("independent copy cannot alias original")} catch(_:IllegalArgumentException) {}
    }
    @Test fun assignAndDifferentRestoreRaceNeverReplaceAnEstablishedIdentity() {
        repeat(30) {
            val identity=ReferenceIdentity();val given="00000000-0000-0000-0000-000000000001"
            val start=CountDownLatch(1);val assigned=ConcurrentLinkedQueue<String>();val errors=ConcurrentLinkedQueue<Throwable>()
            val a=Thread {start.await();try {assigned+=identity.assign()} catch(e:Throwable) {errors+=e}}
            val b=Thread {start.await();try {identity.restore(given)} catch(e:Throwable) {errors+=e}}
            a.start();b.start();start.countDown();a.join();b.join()
            assertEquals(1,assigned.size);assertEquals(assigned.first(),identity.id)
            assertTrue(errors.all {it is IllegalStateException})
            if(identity.id==given) assertTrue(errors.isEmpty()) else assertEquals(1,errors.size)
        }
    }
    @Test fun snapshotOfSnapshotKeepsFrozenMembershipAcrossLaterLiveLayerChanges() {
        val doc=Document.blank();val page=doc.pages.first();val item=ink().also {page.items.add(it)}
        doc.enableReferenceAnchors();val once=doc.snapshot()
        val layer=page.layers.create("Later");item.layerId=layer.id
        val twice=once.snapshot()
        assertEquals(once.pages.first().layerIdForWrite(item),twice.pages.first().layerIdForWrite(item))
        assertEquals(once.pages.first().referenceIdForWrite(item),twice.pages.first().referenceIdForWrite(item))
        val board=InfiniteDocument();val ink=ink().also(board::add);board.enableReferenceAnchors()
        val first=board.snapshotForWrite();ink.layerId=board.layers.create("Later").id
        val second=first.snapshotForWrite()
        assertEquals(first.layerIdForWrite(ink),second.layerIdForWrite(ink));assertEquals(first.referenceIdForWrite(ink),second.referenceIdForWrite(ink))
    }

}
