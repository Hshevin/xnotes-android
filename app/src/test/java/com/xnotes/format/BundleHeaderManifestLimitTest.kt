package com.xnotes.format

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class BundleHeaderManifestLimitTest {
    @Test fun fallbackChecksDeclaredAndActualAnchoredManifestSizeBeforeSkippingIt() {
        val dir=Files.createTempDirectory("header-manifest-limit-").toFile()
        try {
            val source=File(dir,"source.zip");val identity=BundleIdentity()
            ZipOutputStream(source.outputStream()).use {z->
                z.setLevel(1);z.setComment("force standard-library fallback")
                z.putNextEntry(ZipEntry("manifest.json"))
                val block=ByteArray(65536)
                repeat((AnchorManifest.MAX_BYTES/block.size).toInt()+1) {z.write(block)}
                z.closeEntry();identity.write(z);AnchorMetadata(identity.documentId).write(z)
            }
            var checks=0
            // The declared size is already known. A bounded scan must reject before inflating it;
            // the guard stops an old unbounded implementation without expanding the whole bomb.
            assertThrows(IllegalArgumentException::class.java) {BundleHeader.read(source,cancelled={++checks>30})}
            val bytes=source.readBytes();var at=0;var found=false
            while(at<bytes.size-46) {
                if(bytes[at]==0x50.toByte() && bytes[at+1]==0x4b.toByte() && bytes[at+2]==1.toByte() && bytes[at+3]==2.toByte()) {
                    val length=(bytes[at+28].toInt() and 255)+((bytes[at+29].toInt() and 255) shl 8)
                    if(String(bytes,at+46,length)=="manifest.json") {repeat(4) {bytes[at+24+it]=0};bytes[at+24]=1;found=true;break}
                }
                at++
            }
            assertTrue(found);source.writeBytes(bytes)
            // A lying central size must not bypass the actual decompressed byte limit either.
            assertThrows(IllegalArgumentException::class.java) {BundleHeader.read(source)}
            assertArrayEquals(bytes,source.readBytes())
        } finally {dir.deleteRecursively()}
    }
}
