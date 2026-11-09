package com.xnotes.format

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class BundleSnapshotTest {
    private fun directory(block:(File)->Unit) {val dir=Files.createTempDirectory("bundle-snapshot-").toFile();try {block(dir)} finally {dir.deleteRecursively()}}
    @Test fun snapshotRetainsTheVerifiedVersionAndCloseDeletesOnlyItsOwnedFile() = directory {dir->
        val source=File(dir,"source").apply {writeText("original")};val hash=BundleMigration.hash(source)
        val snapshot=BundleSnapshot.take(source,dir)
        val owned=snapshot.file;assertEquals(hash,snapshot.sourceHash);assertEquals("original",owned.readText())
        source.writeText("changed");assertEquals("original",owned.readText())
        snapshot.close();assertFalse(owned.exists());assertEquals("changed",source.readText())
    }
    @Test fun cancellationAndConcurrentSourceChangeCleanOwnedTemporaryBytes() = directory {dir->
        val source=File(dir,"source").apply {writeBytes(ByteArray(200000) {it.toByte()})};val original=source.readBytes()
        try {BundleSnapshot.take(source,dir,cancelled={true});fail("cancel")} catch(_:IllegalStateException) {}
        assertArrayEquals(original,source.readBytes());assertEquals(1,dir.list()!!.size)
        var changed=false
        try {BundleSnapshot.take(source,dir,cancelled={
            if(!changed && dir.listFiles()!!.any {it.name.startsWith("bundle-source-")}) {changed=true;source.writeText("external")};false
        });fail("changed source")} catch(_:IllegalStateException) {}
        assertTrue(changed);assertEquals("external",source.readText());assertEquals(1,dir.list()!!.size)
    }
    @Test fun streamingSnapshotOwnsAndClosesInputEvenWhenCancelled() = directory {dir->
        var closes=0
        val input=object:ByteArrayInputStream("data".toByteArray()) {override fun close() {closes++;super.close()}}
        try {BundleSnapshot.fromInput(input,dir,cancelled={true});fail("cancel")} catch(_:IllegalStateException) {}
        assertEquals(1,closes);assertTrue(dir.list()!!.isEmpty())
    }
    @Test fun ownedFileMustMatchItsStreamDigestBeforeItCanBecomeAnImmutableSnapshot() = directory {dir->
        val original="immutable input".toByteArray();var changed=false
        try {
            BundleSnapshot.fromInput(ByteArrayInputStream(original),dir,cancelled={
                val owned=dir.listFiles()!!.singleOrNull()
                if(!changed && owned?.length()==original.size.toLong()) {changed=true;owned.appendText("corrupt")}
                false
            })
            fail("mutated owned file must not be returned with the input digest")
        } catch(_:IllegalStateException) {}
        assertTrue(changed);assertTrue(dir.list()!!.isEmpty())
    }

}
