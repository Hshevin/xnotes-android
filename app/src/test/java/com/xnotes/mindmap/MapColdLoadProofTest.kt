package com.xnotes.mindmap

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MapColdLoadProofTest {
    @get:Rule val tmp=TemporaryFolder()
    private class Target(var bytes:ByteArray):MapTarget {
        var unavailable=false
        var failWrite=false
        var beforeRead:()->Unit={}
        var afterRead:()->Unit={}
        override fun read():ByteArray {beforeRead();if(unavailable) throw IOException("Unavailable");return bytes.clone().also {afterRead()}}
        override fun write(bytes:ByteArray) {if(failWrite) throw IOException("Unwritable");this.bytes=bytes.clone()}
    }
    private fun document()=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,text="base")))
    private fun file(key:String)=File(tmp.root,MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        .joinToString("") {"%02x".format(it)}+".json")
    private fun journal(doc:MindMapDocument,base:ByteArray,pending:Boolean)=JSONObject().put("baseHash",
        MessageDigest.getInstance("SHA-256").digest(base).joinToString("") {"%02x".format(it)})
        .put("pending",pending).put("document",MapCodec.toJson(doc)).toString().toByteArray()

    @Test fun proofUsesTheActualRawAndJournalAndRejectsLaterSameLengthBytes() {
        val doc=document();val target=Target(MapCodec.encode(doc));val store=MapStore(tmp.root,"map",target)
        store.open(doc.id)
        val proof=requireNotNull(store.openedRecoveryProof)
        assertTrue(store.verifyOpenedRecovery(proof))
        target.bytes=MapCodec.encode(doc.update("r") {it.copy(text="swap")})
        assertFalse(store.verifyOpenedRecovery(proof))
    }

    @Test fun ownPendingJournalCanPrepareWhileRawIsUnavailable() {
        val doc=document();val target=Target(MapCodec.encode(doc));val writer=MapStore(tmp.root,"map",target)
        writer.open();target.failWrite=true
        val edit=doc.update("r") {it.copy(text="retained")}
        assertThrows(IOException::class.java) {writer.save(edit)}
        target.unavailable=true
        val store=MapStore(tmp.root,"map",target)
        assertEquals(edit,store.open(doc.id).document)
        assertTrue(store.verifyOpenedRecovery(requireNotNull(store.openedRecoveryProof)))
        target.unavailable=false
        assertFalse(store.verifyOpenedRecovery(requireNotNull(store.openedRecoveryProof)))
    }

    @Test fun foreignPublishedCheckpointHiddenByOwnPendingIsRejectedBeforePromotion() {
        val doc=document();val raw=MapCodec.encode(doc);val target=Target(raw)
        val published=file("map");val pending=File(published.path+".pending")
        val foreign=journal(doc.copy(id=UUID.randomUUID().toString()),raw,false)
        val own=journal(doc.update("r") {it.copy(text="mine")},raw,true)
        published.writeBytes(foreign);pending.writeBytes(own)
        assertThrows(IOException::class.java) {MapStore(tmp.root,"map",target).open(doc.id)}
        assertArrayEquals(foreign,published.readBytes());assertArrayEquals(own,pending.readBytes())
    }

    @Test fun readableForeignRawCannotBeHiddenByOwnRecovery() {
        val doc=document();val raw=MapCodec.encode(doc);val target=Target(MapCodec.encode(doc.copy(id=UUID.randomUUID().toString())))
        val bytes=journal(doc,raw,true);file("map").writeBytes(bytes)
        assertThrows(IOException::class.java) {MapStore(tmp.root,"map",target).open(doc.id)}
        assertArrayEquals(bytes,file("map").readBytes())
    }

    @Test fun ownCompleteStageIsPromotedAndTheProofTracksOnlyThatKnownMove() {
        val doc=document();val raw=MapCodec.encode(doc);val target=Target(raw)
        val stage=File(file("map").path+".pending")
        val edit=doc.update("r") {it.copy(text="retained")};val bytes=journal(edit,raw,true)
        stage.writeBytes(bytes)
        val store=MapStore(tmp.root,"map",target)
        assertEquals(edit,store.open(doc.id).document)
        assertFalse(stage.exists());assertArrayEquals(bytes,file("map").readBytes())
        val proof=requireNotNull(store.openedRecoveryProof)
        assertTrue(store.verifyOpenedRecovery(proof))
        file("map").writeBytes(journal(doc,raw,true))
        assertFalse(store.verifyOpenedRecovery(proof))
    }

    @Test fun normalizingAnAlreadyWrittenPendingCannotRebindToLaterRawBytes() {
        val doc=document();val raw=MapCodec.encode(doc);val target=Target(raw)
        file("map").writeBytes(journal(doc,raw,true))
        var first=true
        target.afterRead={if(first) {first=false;target.bytes=MapCodec.encode(doc.update("r") {it.copy(text="next")})}}
        val store=MapStore(tmp.root,"map",target)
        assertEquals(doc,store.open(doc.id).document)
        val proof=requireNotNull(store.openedRecoveryProof)
        assertFalse(JSONObject(file("map").readText()).getBoolean("pending"))
        assertFalse(store.verifyOpenedRecovery(proof))
    }

    @Test fun incompleteStageCleanupAndAlreadyWrittenCheckpointBothHaveUsableDerivedProofs() {
        val doc=document();val raw=MapCodec.encode(doc);val target=Target(raw)
        val incomplete=File(file("map").path+".pending").apply {writeText("partial")}
        val cleaned=MapStore(tmp.root,"map",target)
        cleaned.open(doc.id)
        assertFalse(incomplete.exists());assertTrue(cleaned.verifyOpenedRecovery(requireNotNull(cleaned.openedRecoveryProof)))
        file("map").writeBytes(journal(doc,raw,true))
        val normalized=MapStore(tmp.root,"map",target)
        normalized.open(doc.id)
        assertFalse(JSONObject(file("map").readText()).getBoolean("pending"))
        assertTrue(normalized.verifyOpenedRecovery(requireNotNull(normalized.openedRecoveryProof)))
    }

    @Test fun aNormalActorCannotLaunderTheForeignPublishedUuidItAlreadyPromotedOver() {
        val doc=document();val raw=MapCodec.encode(doc);val target=Target(raw)
        file("map").writeBytes(journal(doc.copy(id=UUID.randomUUID().toString()),raw,false))
        File(file("map").path+".pending").writeBytes(journal(doc,raw,true))
        val store=MapStore(tmp.root,"map",target)
        assertEquals(doc,store.open().document)
        assertNull("Cold preparation cannot forget the first inspect's foreign valid checkpoint",store.openedRecoveryProof)
    }

    @Test fun laterNonwinningCheckpointAndSameByteReplacementBothInvalidateTheProof() {
        val doc=document();val raw=MapCodec.encode(doc);val target=Target(raw)
        file("map").writeBytes(journal(doc,raw,false))
        val store=MapStore(tmp.root,"map",target,setOf("alias"))
        store.open(doc.id);val proof=requireNotNull(store.openedRecoveryProof)
        file("alias").writeBytes(journal(doc,raw,false))
        assertFalse(store.verifyOpenedRecovery(proof));assertTrue(file("alias").delete())
        val replacement=File(tmp.root,"replacement").apply {writeBytes(file("map").readBytes())}
        java.nio.file.Files.move(replacement.toPath(),file("map").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        assertFalse(store.verifyOpenedRecovery(proof))
    }

    @Test fun cancellationOfTheFinalReadonlyProofLeavesAllRecoveryBytesAlone() {
        val doc=document();val target=Target(MapCodec.encode(doc));file("map").writeBytes(journal(doc,target.bytes,true))
        val store=MapStore(tmp.root,"map",target);store.open(doc.id)
        val before=tmp.root.listFiles()!!.associate {it.name to it.readBytes().toList()}
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            store.verifyOpenedRecovery(requireNotNull(store.openedRecoveryProof)) {throw java.util.concurrent.CancellationException("Retired")}
        }
        assertEquals(before,tmp.root.listFiles()!!.associate {it.name to it.readBytes().toList()})
    }
}
