package com.xnotes.mindmap

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

abstract class MapPendingRecoveryReviewContract {
    @get:Rule val tmp=TemporaryFolder()
    private val a=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,text="Original",
        kind=NodeKind.INK,ink=listOf(MapInk(listOf(InkPoint(3.0,4.0,.7,9.0)))))))
    private val b=a.update("r") {it.copy(text="Unsaved newer content")}
    private class Target(var bytes:ByteArray):MapTarget {
        var writes=0
        override fun read()=bytes.clone()
        override fun write(bytes:ByteArray) {writes++;this.bytes=bytes.clone()}
    }
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
    private fun journal(key:String="uri")=File(tmp.root,hash(key.toByteArray())+".json")
    private fun staging(key:String="uri")=File(tmp.root,journal(key).name+".pending")
    private fun envelope(doc:MindMapDocument,base:ByteArray,pending:Boolean)=JSONObject().put("baseHash",hash(base))
        .put("pending",pending).put("document",MapCodec.toJson(doc)).toString().toByteArray()

    @Test fun aHalfWrittenStagingFileDoesNotPreventOpeningAndSavingTheIntactOriginal() {
        val original=MapCodec.encode(a);val target=Target(original)
        val bytes=envelope(b,original,true);staging().writeBytes(bytes.copyOf(bytes.size/2))
        val before=staging().readBytes()
        assertEquals(a,MapStore(tmp.root,"uri",target).observe().document)
        assertArrayEquals(before,staging().readBytes()) // A read-only index never cleans another owner's files.
        val writer=MapStore(tmp.root,"uri",target);assertEquals(a,writer.open().document)
        assertFalse(staging().exists());assertEquals(0,target.writes)
        writer.save(b);assertEquals(b,MapCodec.decode(target.bytes));assertEquals(1,target.writes)
    }
    @Test fun aCompleteSyncedStagingFileIsRecoveredWithoutWritingTheDestination() {
        val original=MapCodec.encode(a);val target=Target(original);val bytes=envelope(b,original,true)
        staging().writeBytes(bytes)
        val observed=MapStore(tmp.root,"uri",target).observe()
        assertEquals(b,observed.document);assertTrue(observed.recovered);assertFalse(observed.conflict)
        assertArrayEquals(bytes,staging().readBytes());assertFalse(journal().exists())
        val writer=MapStore(tmp.root,"uri",target);assertEquals(b,writer.open().document)
        assertArrayEquals(original,target.bytes);assertEquals(0,target.writes)
        writer.save(b);assertEquals(b,MapCodec.decode(target.bytes));assertFalse(staging().exists())
    }
    @Test fun stagingAfterAnOlderJournalWinsAsTheNextAtomicCheckpoint() {
        val original=MapCodec.encode(a);val target=Target(original)
        journal().writeBytes(envelope(a,original,false));staging().writeBytes(envelope(b,original,true))
        val writer=MapStore(tmp.root,"uri",target);val opened=writer.open()
        assertEquals(b,opened.document);assertTrue(opened.recovered);assertFalse(opened.conflict)
        assertArrayEquals(original,target.bytes);writer.save(b)
        assertEquals(b,MapStore(tmp.root,"uri",target).open().document)
    }
    @Test fun aPostWriteStagingCheckpointKeepsTheAlreadyWrittenVersion() {
        val original=MapCodec.encode(a);val actual=MapCodec.encode(b);val target=Target(actual)
        journal().writeBytes(envelope(b,original,true));staging().writeBytes(envelope(b,actual,false))
        val writer=MapStore(tmp.root,"uri",target);val opened=writer.open()
        assertEquals(b,opened.document);assertFalse(opened.recovered);assertFalse(opened.conflict)
        assertEquals(0,target.writes);writer.save(b);assertEquals(b,MapCodec.decode(target.bytes))
    }
    @Test fun choosingTheReadableFileExplicitlyDiscardsAllKnownJournalAndStagingCopies() {
        val original=MapCodec.encode(a);val target=Target(original)
        journal().writeBytes(envelope(b,original,true));staging().writeBytes(envelope(b,original,true))
        staging("alias").writeBytes(envelope(b,original,true))
        val writer=MapStore(tmp.root,"uri",target,setOf("alias"))
        assertEquals(a,writer.discardRecovery().document)
        assertTrue(tmp.root.listFiles()!!.isEmpty());assertEquals(0,target.writes)
        writer.save(b);assertEquals(b,MapCodec.decode(target.bytes))
    }
    @Test fun creatingCanRemoveOnlyIncompleteStagingAndCannotReplaceARecoverableCandidate() {
        val target=Target(byteArrayOf());staging().writeText("half-written{")
        MapStore(tmp.root,"uri",target).create(a)
        assertEquals(a,MapCodec.decode(target.bytes))
        val other=Target(byteArrayOf());staging("other").writeBytes(envelope(b,other.bytes,true))
        val retained=staging("other").readBytes()
        assertThrows(IOException::class.java) {MapStore(tmp.root,"other",other).create(a)}
        assertArrayEquals(retained,staging("other").readBytes());assertEquals(0,other.writes)
    }
}
