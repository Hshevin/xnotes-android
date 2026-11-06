package com.xnotes.mindmap

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException

class MapStorageTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun doc() = MindMapDocument(nodes = listOf(MapNode(parent = null, kind = NodeKind.INK,
        text = "中心 🖊", ink = listOf(MapInk(listOf(InkPoint(1.25, 2.5, .67, 123.0), InkPoint(2.0, 4.0, .8, 124.0)))))))
    private class Target(var bytes: ByteArray) : MapTarget {
        var failWrite = false
        override fun read() = bytes
        override fun write(bytes: ByteArray) {
            if (failWrite) { this.bytes = byteArrayOf(1); throw IOException("disk full") }
            this.bytes = bytes
        }
    }
    @Test fun independentFormatRoundTripKeepsEditableInkAndIdentity() {
        val original = doc()
        assertEquals(original, MapCodec.decode(MapCodec.encode(original)))
    }
    @Test fun unknownVersionsMalformedNumbersAndTreesFailClosed() {
        val good = String(MapCodec.encode(doc()), Charsets.UTF_8)
        for (bad in listOf(good.replace("\"version\":1", "\"version\":99"),
            good.replace("\"pressure\":0.67", "\"pressure\":\"0.67\""),
            good.replace("\"parent\":null", "\"parent\":\"missing\""),
            good.replace("\"kind\":\"INK\"", "\"kind\":\"UNKNOWN\""))) {
            assertNotEquals(good, bad)
            assertThrows(Exception::class.java) { MapCodec.decode(bad.toByteArray()) }
        }
    }
    @Test fun inputSizeIsBoundedBeforeParsing() {
        assertThrows(Exception::class.java) { MapCodec.read(ByteArrayInputStream(ByteArray(MapCodec.MAX_BYTES + 1))) }
    }
    @Test fun failedDestinationWriteRetainsLatestRecoverableInk() {
        val target = Target(MapCodec.encode(doc()))
        val store = MapStore(tmp.root, "uri", target)
        val opened = store.open()
        val edit = opened.document.update(opened.document.root.id) { it.copy(text = "edited") }
        target.failWrite = true
        assertThrows(IOException::class.java) { store.save(edit) }
        val recovery = MapStore(tmp.root, "uri", target).open()
        assertEquals(edit, recovery.document)
        assertTrue(recovery.recovered)
        assertTrue(recovery.conflict)
    }
    @Test fun detectsExternalChangesBeforeOverwriteAndCanSaveACopy() {
        val target = Target(MapCodec.encode(doc()))
        val store = MapStore(tmp.root, "uri", target)
        val opened = store.open()
        val edit = opened.document.update(opened.document.root.id) { it.copy(text = "edit") }
        val external = MapCodec.encode(opened.document.update(opened.document.root.id) { it.copy(text = "external") })
        target.bytes = external
        assertThrows(MapConflict::class.java) { store.save(edit) }
        assertArrayEquals(external, target.bytes)
        assertEquals(edit, MapStore(tmp.root, "uri", target).open().document)
    }
    @Test fun cleanJournalDoesNotMaskExternalChangesAndDifferentUrisHaveDifferentJournals() {
        val target = Target(MapCodec.encode(doc()))
        val store = MapStore(tmp.root, "one", target)
        val opened = store.open()
        store.save(opened.document)
        val external = opened.document.update(opened.document.root.id) { it.copy(text = "new") }
        target.bytes = MapCodec.encode(external)
        assertEquals(external, MapStore(tmp.root, "one", target).open().document)
        assertEquals(external, MapStore(tmp.root, "two", target).open().document)
    }
    @Test fun failedLatestCheckpointMustNotClaimOlderJournalIsSafeToClose() {
        val directory = tmp.newFolder("journal")
        val target = Target(MapCodec.encode(doc()))
        val store = MapStore(directory, "uri", target)
        val opened = store.open()
        store.save(opened.document)
        directory.listFiles()!!.forEach { it.delete() }
        directory.delete()
        directory.writeText("blocked")
        val edit = opened.document.update(opened.document.root.id) { it.copy(text = "latest") }
        assertThrows(Exception::class.java) { store.save(edit) }
        assertFalse(store.journalReady)
        assertEquals(opened.document, MapCodec.decode(target.bytes))
    }
    @Test fun cleanJournalCanRecoverADamagedOriginalWithoutOverwritingIt() {
        val target = Target(MapCodec.encode(doc()))
        val store = MapStore(tmp.root, "uri", target)
        val opened = store.open()
        store.save(opened.document)
        target.bytes = byteArrayOf(1)
        val recovery = MapStore(tmp.root, "uri", target).open()
        assertEquals(opened.document, recovery.document)
        assertTrue(recovery.recovered)
        assertTrue(recovery.conflict)
    }

}
