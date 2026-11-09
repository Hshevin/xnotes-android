package com.xnotes.mindmap

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Indexing a graph is neither opening an editing owner nor adopting a new write baseline. */
class MapReadonlyObservationTest {
    @get:Rule val tmp=TemporaryFolder()
    private class Target(var bytes:ByteArray):MapTarget {
        var writes=0
        var denyWrite=false
        override fun read()=bytes.clone()
        override fun write(bytes:ByteArray) {
            writes++
            if(denyWrite) throw IOException("Provider denied graph write")
            this.bytes=bytes.clone()
        }
    }
    private fun document()=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,text="Root"),
        MapNode(id="n",parent="r",text="Saved body")))
    private fun files():Map<String,List<Byte>> = tmp.root.walkTopDown().filter {it.isFile}.associate {
        it.relativeTo(tmp.root).path to it.readBytes().toList()
    }

    @Test fun pendingRecoveryIsObservedAsTheSameWinnerWithoutCheckpointingOrWritingEitherCopy() {
        val a=document();val b=a.update("n") {it.copy(text="Retained newer body")}
        val target=Target(MapCodec.encode(a));val writer=MapStore(tmp.root,"graph",target)
        writer.open();target.denyWrite=true
        assertThrows(IOException::class.java) {writer.save(b)}
        val before=files();val raw=target.bytes.clone();val writes=target.writes
        val observed=MapStore(tmp.root,"graph",target).observe()
        assertEquals(b,observed.document);assertTrue(observed.recovered);assertFalse(observed.conflict)
        assertEquals(before,files());assertArrayEquals(raw,target.bytes);assertEquals(writes,target.writes)
        val actual=MapStore(tmp.root,"graph",target).open()
        assertEquals(actual.document,observed.document);assertEquals(actual.recovered,observed.recovered)
        assertEquals(actual.conflict,observed.conflict)
    }

    @Test fun observingExternalContentCannotLaunderAnExistingWritersOriginalBaseline() {
        val a=document();val target=Target(MapCodec.encode(a));val writer=MapStore(tmp.root,"graph",target)
        writer.open()
        val c=a.update("n") {it.copy(text="External version C")};target.bytes=MapCodec.encode(c)
        assertEquals(c,writer.observe().document)
        assertTrue(tmp.root.listFiles().orEmpty().isEmpty());assertEquals(0,target.writes)
        assertThrows(MapConflict::class.java) {writer.save(a.update("n") {it.copy(text="Old RAM edit D")})}
        assertArrayEquals(MapCodec.encode(c),target.bytes);assertEquals(0,target.writes)
    }

    @Test fun corruptOrAmbiguousKnownJournalsRemainIntactAndDoNotFallBackToAnotherGraph() {
        val a=document();val target=Target(MapCodec.encode(a))
        val first=MapStore(tmp.root,"canonical",target);first.open();target.denyWrite=true
        assertThrows(IOException::class.java) {first.save(a.update("n") {it.copy(text="First retained edit")})}
        val second=MapStore(tmp.root,"old-alias",target);second.open()
        assertThrows(IOException::class.java) {second.save(a.update("n") {it.copy(text="Second retained edit")})}
        val before=files();val raw=target.bytes.clone();val writes=target.writes
        assertThrows(IOException::class.java) {MapStore(tmp.root,"canonical",target,setOf("old-alias")).observe()}
        assertEquals(before,files());assertArrayEquals(raw,target.bytes);assertEquals(writes,target.writes)
        tmp.root.listFiles()!!.first().writeText("broken checkpoint")
        val corrupt=files()
        assertThrows(IOException::class.java) {MapStore(tmp.root,"canonical",target,setOf("old-alias")).observe()}
        assertEquals(corrupt,files());assertArrayEquals(raw,target.bytes)
    }
    @Test fun aFirstReadonlyObservationDoesNotAuthorizeWritingAndCancellationLeavesTheJournalUntouched() {
        val a=document();val target=Target(MapCodec.encode(a));val observer=MapStore(tmp.root,"graph",target)
        assertEquals(a,observer.observe().document)
        assertFalse(observer.journalReady)
        assertThrows(IllegalArgumentException::class.java) {observer.save(a.update("n") {it.copy(text="Cannot publish without open")})}
        assertEquals(0,target.writes);assertArrayEquals(MapCodec.encode(a),target.bytes)
        val before=files()
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            observer.observe({throw java.util.concurrent.CancellationException("Panel retired")})
        }
        assertEquals(before,files());assertEquals(0,target.writes)
    }

    @Test fun anAlreadyWrittenPendingCheckpointIsNotClearedByReadonlyObservation() {
        val a=document();val target=Target(MapCodec.encode(a));val writer=MapStore(tmp.root,"graph",target)
        writer.open();writer.save(a.update("n") {it.copy(text="Actual published B")})
        val checkpoint=tmp.root.listFiles()!!.single {it.extension=="json"}
        val json=org.json.JSONObject(checkpoint.readText()).put("pending",true)
        checkpoint.writeText(json.toString())
        val before=files();val writes=target.writes
        val observed=MapStore(tmp.root,"graph",target).observe()
        assertEquals("Actual published B",observed.document.node("n").text)
        assertFalse(observed.recovered);assertFalse(observed.conflict)
        assertEquals(before,files());assertEquals(writes,target.writes)
        assertTrue(org.json.JSONObject(checkpoint.readText()).getBoolean("pending"))
    }

}
