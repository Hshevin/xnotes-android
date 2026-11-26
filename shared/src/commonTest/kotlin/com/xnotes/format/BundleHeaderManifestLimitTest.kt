package com.xnotes.format

import com.xnotes.core.pal.*
import com.xnotes.kio.*
import okio.*
import kotlin.test.assertFailsWith

import com.xnotes.core.testkit.*
import kotlin.test.Test

class BundleHeaderManifestLimitTest : BundleTestFiles() {
    @Test fun fallbackChecksDeclaredAndActualAnchoredManifestSizeBeforeSkippingIt() {
        val dir=testDirectory("header-manifest-limit-")
        try {
            val source=dir.child("source.zip");val identity=BundleIdentity()
            bundleWriter(source.fileSink(),level=1,comment="force standard-library fallback").use {z->
                z.deflated("manifest.json") {sink ->
                val block=ByteArray(65536) { 32 }
                repeat((AnchorManifest.MAX_BYTES/block.size).toInt()+1) {sink.putBytes(block)}
                };z.addIdentity(identity);z.addMarker(AnchorMetadata(identity.documentId))
            }
            var checks=0
            // The declared size is already known. A bounded scan must reject before inflating it;
            // the guard stops an old unbounded implementation without expanding the whole bomb.
            assertFailsWith<IllegalArgumentException> {BundleHeader.read(source,cancelled={++checks>30})}
            val bytes=source.fileBytes();var at=0;var found=false
            while(at<bytes.size-46) {
                if(bytes[at]==0x50.toByte() && bytes[at+1]==0x4b.toByte() && bytes[at+2]==1.toByte() && bytes[at+3]==2.toByte()) {
                    val length=(bytes[at+28].toInt() and 255)+((bytes[at+29].toInt() and 255) shl 8)
                    if(bytes.decodeToString(at+46,at+46+length)=="manifest.json") {repeat(4) {bytes[at+24+it]=0};bytes[at+24]=1;found=true;break}
                }
                at++
            }
            assertTrue(found);source.setBytes(bytes)
            // A lying central size must not bypass the actual decompressed byte limit either.
            assertEquals(identity, BundleHeader.read(source).identity)
            bundleDirectory(source).use { directory ->
                assertFailsWith<IllegalArgumentException> {
                    AnchorManifest.inspectSource({ directory.open("manifest.json") }, true)
                }
            }
            assertArrayEquals(bytes,source.fileBytes())
        } finally {dir.deleteTree()}
    }
}
