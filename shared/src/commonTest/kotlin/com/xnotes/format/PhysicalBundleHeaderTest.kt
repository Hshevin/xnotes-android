package com.xnotes.format

import com.xnotes.core.pal.*
import com.xnotes.kio.*
import okio.*
import kotlin.test.assertFailsWith

import com.xnotes.core.testkit.*
import kotlin.test.Test

class PhysicalBundleHeaderTest : BundleTestFiles() {
    private val id=BundleIdentity("00000000-0000-0000-0000-000000000001")
    private val marker=""" { "version":1, "documentId":"${id.documentId}", "format":"xnotes-page-anchors" }
""".encodeToByteArray()
    private fun directory(block:(AssetFile)->Unit) {val dir=testDirectory("header-");try {block(dir)} finally {dir.deleteTree()}}
    private fun fixture(dir:AssetFile,stored:Boolean=false,comment:Boolean=false,withIdentity:Boolean=true,withMarker:Boolean=true):AssetFile = temporaryAsset("header-",".zip",dir).also {file->
        bundleWriter(file.fileSink(),comment="archive comment".takeIf {comment}).use {z->
            fun add(name:String,bytes:ByteArray) {
                z.entry(name,null,bytes.size.toLong().takeIf {stored},Crc32.of(bytes)) {it.putBytes(bytes)}
            }
            if(withIdentity) add(BundleIdentity.ENTRY,encodeIdentity(id))
            if(withMarker) add(AnchorMetadata.ENTRY,marker)
            add("future/large.bin",ByteArray(100000) {it.toByte()})
            add("manifest.json","""{"format":"xcanvas","version":1,"items":[]}""".encodeToByteArray())
        }
    }
    @Test fun absenceIsDifferentFromUnsupportedFastReadAndMalformedBinding() = directory {dir->
        val legacy=fixture(dir,withIdentity=false,withMarker=false)
        openRead(legacy.assetPath()).use {val h=BundleHeader.read(it)!!;assertNull(h.identity);assertNull(h.anchors)}
        val commented=fixture(dir,comment=true)
        openRead(commented.assetPath()).use {
            assertNull(ZipTail.read(it))
            assertEquals(id, BundleHeader.read(it)!!.identity)
        }
        assertEquals(id,BundleHeader.read(commented).identity)
        val orphan=fixture(dir,withIdentity=false)
        try {BundleHeader.read(orphan);fail("marker cannot supply a guessed identity")} catch(_:IllegalArgumentException) {}
    }
    @Test fun checksumFailureAndDuplicateMetadataAreErrorsRatherThanMissingIdentity() = directory {dir->
        val source=fixture(dir,stored=true)
        val raw=source.fileBytes();var at=0
        while(at<raw.size-46) {
            if(raw[at]==0x50.toByte() && raw[at+1]==0x4b.toByte() && raw[at+2]==1.toByte() && raw[at+3]==2.toByte()) {raw[at+16]=(raw[at+16].toInt() xor 1).toByte();break}
            at++
        }
        source.setBytes(raw)
        try {BundleHeader.read(source);fail("CRC")} catch(_:IllegalArgumentException) {}
        openRead(source.assetPath()).use {try {BundleHeader.read(it);fail("fast CRC")} catch(_:IllegalArgumentException) {}}
        val duplicate=fixture(dir)
        val tail=dir.child("extra.zip");bundleWriter(tail.fileSink()).use {it.addIdentity(id)}
        openReadWrite(duplicate.assetPath()).use {dest->openRead(tail.assetPath()).use {extra->
            val old=ZipTail.read(dest)!!;val next=ZipTail.read(extra)!!
            check(ZipTail.splice(dest,old.offset,old.entries,extra,next)>=0)
        }}
        try {BundleHeader.read(duplicate);fail("duplicate identity")} catch(_:IllegalArgumentException) {}
    }
    @Test fun zip64CountUsesFileFallbackWithoutMistakingMetadataForAbsent() = directory {dir->
        val source=dir.child("zip64.zip")
        bundleWriter(source.fileSink()).use {z->
            z.addIdentity(id);z.addMarker(AnchorMetadata(id.documentId))
            repeat(65536) {i->z.entry("future/$i",null,0L,0L) {}}
        }
        openRead(source.assetPath()).use {assertNull(BundleHeader.read(it))}
        assertEquals(id,BundleHeader.read(source).identity)
    }
    private fun changeCentral(source:AssetFile,edit:(ByteArray,Int)->Unit) {
        val bytes=source.fileBytes();var at=0
        while(at<bytes.size-46 && !(bytes[at]==0x50.toByte() && bytes[at+1]==0x4b.toByte() && bytes[at+2]==1.toByte() && bytes[at+3]==2.toByte())) at++
        assertTrue(at<bytes.size-46);edit(bytes,at);source.setBytes(bytes)
    }
    private fun rejectsFileAndSupportedChannel(source:AssetFile,fastSupported:Boolean=true) {
        try {BundleHeader.read(source);fail("file header must reject conflicting physical metadata")} catch(_:Exception) {}
        if(fastSupported) openRead(source.assetPath()).use {input->try {BundleHeader.read(input);fail("channel header must reject conflicting physical metadata")} catch(_:Exception) {}}
    }
    @Test fun fileFallbackCannotAcceptMismatchedLocalNamesOrUnusedCompressedBytes() = directory {dir->
        for(comment in listOf(false,true)) {
            val source=fixture(dir,comment=comment)
            val raw=source.fileBytes();raw[30+1]='X'.code.toByte();source.setBytes(raw)
            rejectsFileAndSupportedChannel(source,!comment)
            val trailing=fixture(dir,comment=comment)
            changeCentral(trailing) {bytes,at->
                val buffer=bytes
                buffer.putInt(at+20,buffer.u32(at+20).toInt()+1)
            }
            rejectsFileAndSupportedChannel(trailing,!comment)
        }
    }
    @Test fun centralEncryptionFlagCannotBeIgnoredByChannelReader() = directory {dir->
        val source=fixture(dir)
        changeCentral(source) {bytes,at->bytes[at+8]=(bytes[at+8].toInt() or 1).toByte()}
        rejectsFileAndSupportedChannel(source)
    }

    private fun ByteArray.putShort(index:Int,value:Int) { for (byte in 0..1) this[index+byte]=(value ushr (8*byte)).toByte() }
    private fun ByteArray.putInt(index:Int,value:Int) { for (byte in 0..3) this[index+byte]=(value ushr (8*byte)).toByte() }

    private fun spliceLocalBytes(source:AssetFile,at:Int,remove:Int,insert:ByteArray):ByteArray {
        val directory=openRead(source.assetPath()).use {ZipTail.read(it)!!}
        val original=source.fileBytes();val delta=insert.size-remove
        val bytes=original.copyOfRange(0,at)+insert+original.copyOfRange(at+remove,original.size)
        val buffer=bytes
        var cursor=directory.offset.toInt()+delta
        for(entry in directory.entries) {
            buffer.putInt(cursor+42,(if(entry.localOffset>at) entry.localOffset+delta else entry.localOffset).toInt())
            cursor+=entry.record.size
        }
        buffer.putInt(bytes.size-22+16,directory.offset.toInt()+delta)
        source.setBytes(bytes);return bytes
    }
    @Test fun ordinaryDescriptorIsVerifiedAndUnsignedFormIsAccepted() = directory {dir->
        for(comment in listOf(false,true)) {
            val source=fixture(dir,comment=comment)
            val entry=bundleDirectory(source).use {it.entry(BundleIdentity.ENTRY)!!}
            val bytes=source.fileBytes();val buffer=bytes
            val end=30+(buffer.u16(26).toInt() and 65535)+(buffer.u16(28).toInt() and 65535)+entry.compressedSize.toInt()
            assertEquals(0x08074b50,buffer.u32(end).toInt());bytes[end+4]=(bytes[end+4].toInt() xor 1).toByte();source.setBytes(bytes)
            rejectsFileAndSupportedChannel(source,!comment)
        }
        val unsigned=fixture(dir)
        val entry=bundleDirectory(unsigned).use {it.entry(BundleIdentity.ENTRY)!!}
        val raw=unsigned.fileBytes()
        val end=30+(raw.u16(26).toInt() and 65535)+(raw.u16(28).toInt() and 65535)+entry.compressedSize.toInt()
        spliceLocalBytes(unsigned,end,4,byteArrayOf())
        assertEquals(id,BundleHeader.read(unsigned).identity)
        openRead(unsigned.assetPath()).use {assertEquals(id,BundleHeader.read(it)!!.identity)}
    }
    @Test fun perEntryZip64UsesStandardFallbackEvenWhenTheArchiveDirectoryIsOrdinary() = directory {dir->
        val source=fixture(dir,stored=true)
        val old=source.fileBytes()
        assertEquals(0,old.u16(28).toInt())
        val extra=Buffer().writeShortLe(1).writeShortLe(16)
            .writeLongLe(old.u32(22).toInt().toLong() and 0xffffffffL).writeLongLe(old.u32(18).toInt().toLong() and 0xffffffffL).readByteArray()
        val bytes=spliceLocalBytes(source,30+(old.u16(26).toInt() and 65535),0,extra)
        bytes.apply {putShort(4,45);putInt(18,-1);putInt(22,-1);putShort(28,20)}
        source.setBytes(bytes)
        openRead(source.assetPath()).use {assertNotNull(ZipTail.read(it));assertNull(BundleHeader.read(it))}
        assertEquals(id,BundleHeader.read(source).identity)
    }

}
