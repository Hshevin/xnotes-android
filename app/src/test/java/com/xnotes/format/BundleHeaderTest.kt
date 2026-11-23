package com.xnotes.format

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BundleHeaderTest {
    private val id=BundleIdentity("00000000-0000-0000-0000-000000000001")
    private val marker=""" { "version":1, "documentId":"${id.documentId}", "format":"xnotes-page-anchors" }
""".toByteArray()
    private fun directory(block:(File)->Unit) {val dir=Files.createTempDirectory("header-").toFile();try {block(dir)} finally {dir.deleteRecursively()}}
    private fun fixture(dir:File,stored:Boolean=false,comment:Boolean=false,withIdentity:Boolean=true,withMarker:Boolean=true):File = File.createTempFile("header-",".zip",dir).also {file->
        ZipOutputStream(file.outputStream()).use {z->
            if(comment) z.setComment("archive comment")
            fun add(name:String,bytes:ByteArray) {
                z.putNextEntry(ZipEntry(name).apply {if(stored) {method=ZipEntry.STORED;size=bytes.size.toLong();compressedSize=size;crc=CRC32().apply {update(bytes)}.value}})
                z.write(bytes);z.closeEntry()
            }
            if(withIdentity) add(BundleIdentity.ENTRY,id.bytes())
            if(withMarker) add(AnchorMetadata.ENTRY,marker)
            add("future/large.bin",ByteArray(100000) {it.toByte()})
            add("manifest.json","""{"format":"xcanvas","version":1,"items":[]}""".toByteArray())
        }
    }
    @Test fun storedAndDeflatedMetadataAreBoundedAndDoNotConsumeFollowingEntriesOrOwnChannel() = directory {dir->
        for(stored in listOf(false,true)) {
            val source=fixture(dir,stored)
            val file=BundleHeader.read(source)
            assertEquals(id,file.identity);assertEquals(id.documentId,file.anchors!!.documentId);assertArrayEquals(marker,file.anchorBytesFor(id))
            FileInputStream(source).use {input->
                input.channel.position(7)
                val fast=BundleHeader.read(input.channel)!!
                assertEquals(id,fast.identity);assertArrayEquals(marker,fast.anchorBytesFor(id));assertEquals(7L,input.channel.position())
                assertTrue(input.channel.isOpen)
            }
            val other=id.copy(documentId="00000000-0000-0000-0000-000000000002")
            val rebound=file.anchorBytesFor(other)!!
            assertEquals(other.documentId,AnchorMetadata.read(rebound.inputStream()).documentId)
        }
    }
}
