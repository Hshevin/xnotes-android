package com.xnotes.format

import com.xnotes.core.pal.asAssetFile
import com.xnotes.core.pal.assetPath
import com.xnotes.kio.KFiles
import com.xnotes.kio.Uuids
import okio.*
import com.xnotes.core.testkit.*
import kotlin.test.Test

class BundleSnapshotTest {
    private val fs = KFiles.system
    private fun Path.writeText(value: String) = fs.write(this) { writeUtf8(value); Unit }
    private fun Path.readText() = fs.read(this) { readUtf8() }
    private fun directory(block: (Path) -> Unit) {
        val dir = KFiles.tempDirectory() / Uuids.random(); fs.createDirectory(dir)
        try { block(dir) } finally { fs.deleteRecursively(dir) }
    }
    @Test fun snapshotRetainsTheVerifiedVersionAndCloseDeletesOnlyItsOwnedFile() = directory {dir->
        val source=(dir / "source").apply {writeText("original")};val hash=BundleSnapshot.hash(source.asAssetFile())
        val snapshot=BundleSnapshot.take(source.asAssetFile(),dir.asAssetFile())
        val owned=snapshot.file.assetPath();assertEquals(hash,snapshot.sourceHash);assertEquals("original",owned.readText())
        source.writeText("changed");assertEquals("original",owned.readText())
        snapshot.close();assertFalse(fs.exists(owned));assertEquals("changed",source.readText())
    }
    @Test fun cancellationAndConcurrentSourceChangeCleanOwnedTemporaryBytes() = directory {dir->
        val source=(dir / "source").apply {fs.write(this) {write(ByteArray(200000) {it.toByte()})}};val original=fs.read(source) {readByteArray()}
        try {BundleSnapshot.take(source.asAssetFile(),dir.asAssetFile(),cancelled={true});fail("cancel")} catch(_:IllegalStateException) {}
        assertArrayEquals(original,fs.read(source) {readByteArray()});assertEquals(1,fs.list(dir).size)
        var changed=false
        try {BundleSnapshot.take(source.asAssetFile(),dir.asAssetFile(),cancelled={
            if(!changed && fs.list(dir).any {it.name.startsWith("bundle-source-")}) {changed=true;source.writeText("external")};false
        });fail("changed source")} catch(_:IllegalStateException) {}
        assertTrue(changed);assertEquals("external",source.readText());assertEquals(1,fs.list(dir).size)
    }
    @Test fun streamingSnapshotOwnsAndClosesInputEvenWhenCancelled() = directory {dir->
        var closes=0
        val input=object:ForwardingSource(Buffer().writeUtf8("data")) {override fun close() {closes++;super.close()}}
        try {BundleSnapshot.fromInput(input,dir.asAssetFile(),cancelled={true});fail("cancel")} catch(_:IllegalStateException) {}
        assertEquals(1,closes);assertTrue(fs.list(dir).isEmpty())
    }
    @Test fun ownedFileMustMatchItsStreamDigestBeforeItCanBecomeAnImmutableSnapshot() = directory {dir->
        val original="immutable input".encodeToByteArray();var changed=false
        try {
            BundleSnapshot.fromInput(Buffer().write(original),dir.asAssetFile(),cancelled={
                val owned=fs.list(dir).singleOrNull()
                if(!changed && owned!=null && fs.metadata(owned).size==original.size.toLong()) {changed=true;fs.appendingSink(owned).buffer().use {it.writeUtf8("corrupt")}}
                false
            })
            fail("mutated owned file must not be returned with the input digest")
        } catch(_:IllegalStateException) {}
        assertTrue(changed);assertTrue(fs.list(dir).isEmpty())
    }

}
