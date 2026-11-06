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
    private val marker=""" { "version":1, "documentId":"${id.documentId}", "format":"xnotes-object-anchors" } 
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
    @Test fun absenceIsDifferentFromUnsupportedFastReadAndMalformedBinding() = directory {dir->
        val legacy=fixture(dir,withIdentity=false,withMarker=false)
        FileInputStream(legacy).use {val h=BundleHeader.read(it.channel)!!;assertNull(h.identity);assertNull(h.anchors)}
        val commented=fixture(dir,comment=true)
        FileInputStream(commented).use {assertNull(BundleHeader.read(it.channel))}
        assertEquals(id,BundleHeader.read(commented).identity)
        val orphan=fixture(dir,withIdentity=false)
        try {BundleHeader.read(orphan);fail("marker cannot supply a guessed identity")} catch(_:IllegalArgumentException) {}
    }
    @Test fun checksumFailureAndDuplicateMetadataAreErrorsRatherThanMissingIdentity() = directory {dir->
        val source=fixture(dir,stored=true)
        val raw=source.readBytes();var at=0
        while(at<raw.size-46) {
            if(raw[at]==0x50.toByte() && raw[at+1]==0x4b.toByte() && raw[at+2]==1.toByte() && raw[at+3]==2.toByte()) {raw[at+16]=(raw[at+16].toInt() xor 1).toByte();break}
            at++
        }
        source.writeBytes(raw)
        try {BundleHeader.read(source);fail("CRC")} catch(_:IllegalArgumentException) {}
        FileInputStream(source).use {try {BundleHeader.read(it.channel);fail("fast CRC")} catch(_:IllegalArgumentException) {}}
        val duplicate=fixture(dir)
        val tail=File(dir,"extra.zip");ZipOutputStream(tail.outputStream()).use {id.write(it)}
        RandomAccessFile(duplicate,"rw").use {dest->FileInputStream(tail).use {extra->
            val old=ZipTail.read(dest.channel)!!;val next=ZipTail.read(extra.channel)!!
            check(ZipTail.splice(dest.channel,old.offset,old.entries,extra.channel,next)>=0)
        }}
        try {BundleHeader.read(duplicate);fail("duplicate identity")} catch(_:IllegalArgumentException) {}
    }
    @Test fun zip64CountUsesFileFallbackWithoutMistakingMetadataForAbsent() = directory {dir->
        val source=File(dir,"zip64.zip")
        ZipOutputStream(source.outputStream()).use {z->
            id.write(z);AnchorMetadata(id.documentId).write(z)
            repeat(65536) {i->z.putNextEntry(ZipEntry("future/$i").apply {method=ZipEntry.STORED;size=0;compressedSize=0;crc=0});z.closeEntry()}
        }
        FileInputStream(source).use {assertNull(BundleHeader.read(it.channel))}
        assertEquals(id,BundleHeader.read(source).identity)
    }
    private fun changeCentral(source:File,edit:(ByteArray,Int)->Unit) {
        val bytes=source.readBytes();var at=0
        while(at<bytes.size-46 && !(bytes[at]==0x50.toByte() && bytes[at+1]==0x4b.toByte() && bytes[at+2]==1.toByte() && bytes[at+3]==2.toByte())) at++
        assertTrue(at<bytes.size-46);edit(bytes,at);source.writeBytes(bytes)
    }
    private fun rejectsFileAndSupportedChannel(source:File,fastSupported:Boolean=true) {
        try {BundleHeader.read(source);fail("file header must reject conflicting physical metadata")} catch(_:Exception) {}
        if(fastSupported) FileInputStream(source).use {input->try {BundleHeader.read(input.channel);fail("channel header must reject conflicting physical metadata")} catch(_:Exception) {}}
    }
    @Test fun fileFallbackCannotAcceptMismatchedLocalNamesOrUnusedCompressedBytes() = directory {dir->
        for(comment in listOf(false,true)) {
            val source=fixture(dir,comment=comment)
            val raw=source.readBytes();raw[30+1]='X'.code.toByte();source.writeBytes(raw)
            rejectsFileAndSupportedChannel(source,!comment)
            val trailing=fixture(dir,comment=comment)
            changeCentral(trailing) {bytes,at->
                val buffer=java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                buffer.putInt(at+20,buffer.getInt(at+20)+1)
            }
            rejectsFileAndSupportedChannel(trailing,!comment)
        }
    }
    @Test fun centralEncryptionFlagCannotBeIgnoredByChannelReader() = directory {dir->
        val source=fixture(dir)
        changeCentral(source) {bytes,at->bytes[at+8]=(bytes[at+8].toInt() or 1).toByte()}
        rejectsFileAndSupportedChannel(source)
    }

    private fun spliceLocalBytes(source:File,at:Int,remove:Int,insert:ByteArray):ByteArray {
        val directory=FileInputStream(source).use {ZipTail.read(it.channel)!!}
        val original=source.readBytes();val delta=insert.size-remove
        val bytes=original.copyOfRange(0,at)+insert+original.copyOfRange(at+remove,original.size)
        val buffer=java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var cursor=directory.offset.toInt()+delta
        for(entry in directory.entries) {
            buffer.putInt(cursor+42,(if(entry.localOffset>at) entry.localOffset+delta else entry.localOffset).toInt())
            cursor+=entry.record.size
        }
        buffer.putInt(bytes.size-22+16,directory.offset.toInt()+delta)
        source.writeBytes(bytes);return bytes
    }
    @Test fun ordinaryDescriptorIsVerifiedAndUnsignedFormIsAccepted() = directory {dir->
        for(comment in listOf(false,true)) {
            val source=fixture(dir,comment=comment)
            val entry=java.util.zip.ZipFile(source).use {it.getEntry(BundleIdentity.ENTRY)}
            val bytes=source.readBytes();val buffer=java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val end=30+(buffer.getShort(26).toInt() and 65535)+(buffer.getShort(28).toInt() and 65535)+entry.compressedSize.toInt()
            assertEquals(0x08074b50,buffer.getInt(end));bytes[end+4]=(bytes[end+4].toInt() xor 1).toByte();source.writeBytes(bytes)
            rejectsFileAndSupportedChannel(source,!comment)
        }
        val unsigned=fixture(dir)
        val entry=java.util.zip.ZipFile(unsigned).use {it.getEntry(BundleIdentity.ENTRY)}
        val raw=java.nio.ByteBuffer.wrap(unsigned.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val end=30+(raw.getShort(26).toInt() and 65535)+(raw.getShort(28).toInt() and 65535)+entry.compressedSize.toInt()
        spliceLocalBytes(unsigned,end,4,byteArrayOf())
        assertEquals(id,BundleHeader.read(unsigned).identity)
        FileInputStream(unsigned).use {assertEquals(id,BundleHeader.read(it.channel)!!.identity)}
    }
    @Test fun perEntryZip64UsesStandardFallbackEvenWhenTheArchiveDirectoryIsOrdinary() = directory {dir->
        val source=fixture(dir,stored=true)
        val old=java.nio.ByteBuffer.wrap(source.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(0,old.getShort(28).toInt())
        val extra=java.nio.ByteBuffer.allocate(20).order(java.nio.ByteOrder.LITTLE_ENDIAN).putShort(1).putShort(16)
            .putLong(old.getInt(22).toLong() and 0xffffffffL).putLong(old.getInt(18).toLong() and 0xffffffffL).array()
        val bytes=spliceLocalBytes(source,30+(old.getShort(26).toInt() and 65535),0,extra)
        java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {putShort(4,45);putInt(18,-1);putInt(22,-1);putShort(28,20)}
        source.writeBytes(bytes)
        FileInputStream(source).use {assertNotNull(ZipTail.read(it.channel));assertNull(BundleHeader.read(it.channel))}
        assertEquals(id,BundleHeader.read(source).identity)
    }

}
