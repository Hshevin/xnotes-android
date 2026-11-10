package com.xnotes.mindmap

import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class MapStorePublicationTest {
    private class Target(var bytes:ByteArray):MapTarget {
        var deny=false
        var corruptReadBack=false
        var writes=0
        override fun read()=bytes.copyOf()
        override fun write(bytes:ByteArray) {
            if(deny) throw IOException("controlled map failure")
            writes++;this.bytes=bytes.copyOf()
            if(corruptReadBack) this.bytes=byteArrayOf(1,2,3)
        }
    }
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
    private fun scenario(block:(MapStore,Target,MindMapDocument)->Unit) {
        val root=Files.createTempDirectory("map-publication-").toFile()
        val initial=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,text="A")))
        val target=Target(MapCodec.encode(initial));val store=MapStore(root,"content://map/publication",target)
        try {assertEquals(initial,store.open().document);block(store,target,initial)} finally {root.deleteRecursively()}
    }
    @Test fun receiptBComesFromTheActualSuccessfulWriteAndReadBack()=scenario {store,target,initial->
        val b=initial.copy(nodes=initial.nodes.map {it.copy(text="B")})
        val receipt=store.saveAndVerify(b)
        assertEquals(hash(target.read()),receipt);assertEquals("B",MapCodec.decode(target.read()).nodes.single().text)
        assertEquals(1,target.writes);assertTrue(store.journalReady)
        // The proof stays B when a later writer C arrives; it is not a late getter of current bytes.
        val c=b.copy(nodes=b.nodes.map {it.copy(text="external C")});target.bytes=MapCodec.encode(c)
        assertNotEquals(hash(target.read()),receipt)
    }
    @Test fun alreadyIdenticalActualBytesProduceAReceiptWithoutWritingAgain()=scenario {store,target,initial->
        val receipt=store.saveAndVerify(initial)
        assertEquals(hash(target.read()),receipt);assertEquals(0,target.writes)
    }
    @Test fun actualReadBackFailureAndExternalConflictCannotProduceSuccessReceipts()=scenario {store,target,initial->
        val b=initial.copy(nodes=initial.nodes.map {it.copy(text="B")})
        target.corruptReadBack=true
        try {store.saveAndVerify(b);fail("Bad actual read-back must not sign a receipt")} catch(_:IOException) {}
        assertTrue(store.journalReady)
        target.corruptReadBack=false
        val c=initial.copy(nodes=initial.nodes.map {it.copy(text="external C")});val bytesC=MapCodec.encode(c)
        target.bytes=bytesC
        try {store.saveAndVerify(b);fail("External C is a conflict")} catch(_:MapConflict) {}
        assertArrayEquals(bytesC,target.read())
    }
}
