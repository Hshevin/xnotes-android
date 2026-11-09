package com.xnotes.mindmap

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException

class MapCreationStoreTest {
    @get:Rule val tmp=TemporaryFolder()
    private fun doc(text:String="copy")=MindMapDocument(nodes=listOf(MapNode(parent=null,text=text,kind=NodeKind.INK,
        ink=listOf(MapInk(listOf(InkPoint(12.5,20.0,.75,100.0)))))))
    private class Target(var bytes:ByteArray=byteArrayOf()):MapTarget {
        var reads=0;var writes=0;var denied=false;var failBefore=false;var failAfter=false
        var afterRead:((Int)->Unit)?=null
        override fun read():ByteArray {
            reads++;if(denied) throw IOException("permission lost")
            val result=bytes.clone();afterRead?.invoke(reads);return result
        }
        override fun write(bytes:ByteArray) {
            writes++;if(failBefore) throw IOException("not written")
            this.bytes=bytes.clone();if(failAfter) throw IOException("written but provider failed")
        }
    }
    @Test fun creationRequiresReadableEmptyDestinationAndRechecksAfterItsDurableJournal() {
        val copy=doc()
        for(initial in listOf(MapCodec.encode(copy),byteArrayOf(9,8,7))) {
            val dir=tmp.newFolder();val target=Target(initial)
            assertThrows(IOException::class.java) {MapStore(dir,"destination",target).create(copy)}
            assertEquals(0,target.writes);assertArrayEquals(initial,target.bytes);assertTrue(dir.listFiles()!!.isEmpty())
        }
        val unreadableDir=tmp.newFolder();val unreadable=Target().apply {denied=true}
        assertThrows(IOException::class.java) {MapStore(unreadableDir,"destination",unreadable).create(copy)}
        assertEquals(0,unreadable.writes);assertTrue(unreadableDir.listFiles()!!.isEmpty())

        val dir=tmp.newFolder();val external=MapCodec.encode(doc("appeared externally"))
        val raced=Target().apply {afterRead={if(it==1) bytes=external.clone()}}
        val store=MapStore(dir,"destination",raced)
        assertThrows(IOException::class.java) {store.create(copy)}
        assertEquals(0,raced.writes);assertArrayEquals(external,raced.bytes);assertTrue(store.journalReady)
        val pending=dir.listFiles()!!.single();val recovery=JSONObject(pending.readText())
        assertTrue(recovery.getBoolean("pending"));assertEquals(copy,MapCodec.fromJson(recovery.getJSONObject("document")))
    }
    @Test fun failedCreationRetainsItsExactCandidateAndReconstructionCompletesItWithoutReplacingOrRewriting() {
        val dir=tmp.newFolder();val copy=doc();val target=Target().apply {failBefore=true}
        val store=MapStore(dir,"before-write",target)
        assertThrows(IOException::class.java) {store.create(copy)}
        assertTrue(store.journalReady);assertTrue(target.bytes.isEmpty())
        val journal=dir.listFiles()!!.single();val retained=journal.readBytes()
        assertThrows(IOException::class.java) {MapStore(dir,"before-write",target).create(doc("new request"))}
        assertArrayEquals(retained,journal.readBytes());assertEquals(1,target.writes)
        target.failBefore=false
        val recoveredStore=MapStore(dir,"before-write",target);val recovered=recoveredStore.open()
        assertTrue(recovered.recovered);assertFalse(recovered.conflict);assertEquals(copy,recovered.document)
        recoveredStore.save(recovered.document)
        assertEquals(copy,MapCodec.decode(target.bytes));assertEquals(2,target.writes)
        val clean=journal.readBytes();target.bytes=byteArrayOf()
        assertThrows(IOException::class.java) {MapStore(dir,"before-write",target).create(doc("replace clean history"))}
        assertArrayEquals(clean,journal.readBytes());assertEquals(2,target.writes)
        journal.writeText("damaged recovery journal");val damaged=journal.readBytes()
        assertThrows(IOException::class.java) {MapStore(dir,"before-write",target).create(doc("replace damaged history"))}
        assertArrayEquals(damaged,journal.readBytes());assertEquals(2,target.writes)

        val written=Target().apply {failAfter=true};val already=doc("already written")
        assertThrows(IOException::class.java) {MapStore(dir,"after-write",written).create(already)}
        val bytes=written.bytes.clone();assertEquals(1,written.writes)
        val reopened=MapStore(dir,"after-write",written).open()
        assertEquals(already,reopened.document);assertFalse(reopened.recovered);assertFalse(reopened.conflict)
        assertEquals(1,written.writes);assertArrayEquals(bytes,written.bytes)
    }
    @Test fun completeOrphanStagingIsRetainedAndCancellationAfterTheDurableJournalResumesTheSameCandidate() {
        val dir=tmp.newFolder();val key="orphan"
        val name=MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") {"%02x".format(it)}+".json.pending"
        val base=MessageDigest.getInstance("SHA-256").digest(byteArrayOf()).joinToString("") {"%02x".format(it)}
        val checkpoint=JSONObject().put("baseHash",base).put("pending",true)
            .put("document",MapCodec.toJson(doc("fsynced old checkpoint")))
        val orphan=File(dir,name).apply {writeText(checkpoint.toString())};val original=orphan.readBytes()
        val empty=Target()
        assertThrows(IOException::class.java) {MapStore(dir,key,empty).create(doc())}
        assertEquals(0,empty.writes);assertArrayEquals(original,orphan.readBytes())
        val candidate=doc("cancelled copy")
        val store=MapStore(dir,"cancel",empty)
        assertThrows(CancellationException::class.java) {
            store.create(candidate) {if(dir.listFiles()!!.any {it.name.endsWith(".json")}) throw CancellationException("after durable checkpoint")}
        }
        assertTrue(store.journalReady);assertEquals(0,empty.writes);assertTrue(empty.bytes.isEmpty())
        val restored=MapStore(dir,"cancel",empty);val recovered=restored.open()
        assertTrue(recovered.recovered);assertFalse(recovered.conflict);assertEquals(candidate,recovered.document)
        restored.save(recovered.document)
        assertEquals(candidate,MapCodec.decode(empty.bytes));assertEquals(1,empty.writes)
        assertArrayEquals(original,orphan.readBytes())
    }
    @Test fun cancellationDuringAnOwnedPartialCheckpointAllowsTheSameInitialCreationToBeRetried() {
        val dir=tmp.newFolder();val empty=Target();val candidate=doc("x".repeat(20000))
        val store=MapStore(dir,"partial-checkpoint",empty);var observedPartial=false
        assertThrows(CancellationException::class.java) {
            store.create(candidate) {
                if(dir.listFiles()!!.any {it.name.endsWith(".pending") && it.length()>=8192}) {
                    observedPartial=true;throw CancellationException("partial checkpoint")
                }
            }
        }
        assertTrue(observedPartial);assertFalse(store.journalReady)
        assertTrue(dir.listFiles()!!.isEmpty());assertEquals(0,empty.writes);assertTrue(empty.bytes.isEmpty())
        store.create(candidate)
        assertEquals(candidate,MapCodec.decode(empty.bytes));assertEquals(1,empty.writes)
    }
}
