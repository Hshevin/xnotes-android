package com.xnotes.format

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** Metadata only. Absence is a header with null fields; null from the channel API means use fallback. */
internal class BundleHeader private constructor(
    val identity:BundleIdentity?,val anchors:AnchorMetadata?,private val rawAnchors:ByteArray?,
) {
    init {require(anchors==null || identity?.documentId==anchors.documentId) {"Anchor metadata has no matching document identity"}}
    fun anchorBytesFor(target:BundleIdentity):ByteArray? = anchors?.let {
        if(identity!!.documentId==target.documentId) requireNotNull(rawAnchors).copyOf() else AnchorMetadata(target.documentId).bytes()
    }
    companion object {
        private const val MAX_BYTES=4096L
        private const val MAX_COMPRESSED=65536L
        private const val U32=0xffffffffL
        fun read(file:File,cancelled:()->Boolean={false}):BundleHeader {
            check(!cancelled()) {"Bundle header cancelled"}
            FileInputStream(file).use {input->read(input.channel,cancelled)?.let {return it}}
            // Standard-library fallback for commented/ZIP64 layouts. Read local metadata too,
            // so a permissive central-directory reader cannot accept conflicting physical entries.
            return ZipFile(file).use {zip->
                val metadata=linkedMapOf<String,Pair<ZipEntry,ByteArray>>()
                for(entry in zip.entries().asSequence()) {
                    check(!cancelled())
                    if(entry.name==BundleIdentity.ENTRY || entry.name==AnchorMetadata.ENTRY) {
                    require(entry.name !in metadata) {"Duplicate bundle metadata"}
                    checkSizes(entry.size,entry.compressedSize)
                    metadata[entry.name]=entry to guarded(zip.getInputStream(entry),cancelled).use {readBytes(it,entry.size,entry.crc)}
                    }
                }
                val anchored=AnchorMetadata.ENTRY in metadata
                if(anchored) for(entry in zip.entries().asSequence()) {
                    check(!cancelled())
                    if(entry.name==BundleCopyPlan.MANIFEST) require(entry.size<0 || entry.size<=AnchorManifest.MAX_BYTES) {"ZIP declared manifest limit exceeded"}
                }
                val seen=hashSetOf<String>()
                ZipInputStream(guarded(file.inputStream(),cancelled)).use {local->
                    var entry=local.nextEntry
                    while(entry!=null) {
                        check(!cancelled())
                        if(entry.name==BundleIdentity.ENTRY || entry.name==AnchorMetadata.ENTRY) {
                            require(seen.add(entry.name)) {"Duplicate local metadata"}
                            val expected=requireNotNull(metadata[entry.name]) {"Local metadata is absent from directory"}
                            // Reading to EOF validates ordinary/ZIP64 descriptors and populates their sizes.
                            val bytes=readBytes(local,expected.first.size,expected.first.crc)
                            require(entry.method==expected.first.method && entry.size==expected.first.size && entry.compressedSize==expected.first.compressedSize && entry.crc==expected.first.crc && bytes.contentEquals(expected.second)) {"Local metadata disagrees with directory"}
                        } else if(anchored && entry.name==BundleCopyPlan.MANIFEST) {
                            require(entry.size<0 || entry.size<=AnchorManifest.MAX_BYTES) {"ZIP declared manifest limit exceeded"}
                            val buffer=ByteArray(65536);var total=0L
                            while(true) {
                                check(!cancelled())
                                val n=local.read(buffer);if(n<0) break
                                total+=n;require(total<=AnchorManifest.MAX_BYTES) {"ZIP actual manifest limit exceeded"}
                            }
                        }
                        local.closeEntry();entry=local.nextEntry
                    }
                }
                require(seen==metadata.keys) {"Directory metadata is absent from local entries"}
                parse(metadata[BundleIdentity.ENTRY]?.second,metadata[AnchorMetadata.ENTRY]?.second)
            }
        }
        /** Does not own or reposition the channel. Unsupported directory layouts require the File API. */
        fun read(channel:FileChannel,cancelled:()->Boolean={false}):BundleHeader? {
            check(!cancelled()) {"Bundle header cancelled"}
            val directory=ZipTail.read(channel) ?: return null
            check(!cancelled())
            val identities=directory.entries.filter {it.name==BundleIdentity.ENTRY}
            val anchors=directory.entries.filter {it.name==AnchorMetadata.ENTRY}
            require(identities.size<=1 && anchors.size<=1) {"Duplicate bundle metadata"}
            if((identities+anchors).any {it.method!=ZipEntry.STORED && it.method!=ZipEntry.DEFLATED}) return null
            for(entry in identities+anchors) {
                check(!cancelled())
                val central=ByteBuffer.wrap(entry.record).order(ByteOrder.LITTLE_ENDIAN)
                if(entry.size==U32 || (central.getInt(20).toLong() and U32)==U32 || entry.localOffset==U32) return null
                val local=ByteBuffer.wrap(readAt(channel,entry.localOffset,30)).order(ByteOrder.LITTLE_ENDIAN)
                if((local.getInt(18).toLong() and U32)==U32 || (local.getInt(22).toLong() and U32)==U32) return null
                val nameSize=local.getShort(26).toInt() and 0xffff
                val extraSize=local.getShort(28).toInt() and 0xffff
                val extra=ByteBuffer.wrap(readAt(channel,entry.localOffset+30+nameSize,extraSize)).order(ByteOrder.LITTLE_ENDIAN)
                while(extra.hasRemaining()) {
                    require(extra.remaining()>=4) {"Invalid metadata extra field"}
                    val tag=extra.short.toInt() and 0xffff;val length=extra.short.toInt() and 0xffff
                    require(length<=extra.remaining()) {"Invalid metadata extra field length"}
                    if(tag==1) return null
                    extra.position(extra.position()+length)
                }
            }
            fun bytes(entry:ZipTail.Entry?):ByteArray? = entry?.let {e->
                check(!cancelled())
                val central=ByteBuffer.wrap(e.record).order(ByteOrder.LITTLE_ENDIAN)
                val compressed=central.getInt(20).toLong() and U32
                val crc=central.getInt(16).toLong() and U32
                require((central.getShort(8).toInt() and 1)==0) {"Encrypted metadata is unsupported"}
                checkSizes(e.size,compressed)
                val local=ByteBuffer.wrap(readAt(channel,e.localOffset,30)).order(ByteOrder.LITTLE_ENDIAN)
                require(local.getInt(0)==0x04034b50) {"Invalid metadata local header"}
                val flags=local.getShort(6).toInt() and 0xffff
                require(flags and 1==0 && (local.getShort(8).toInt() and 0xffff)==e.method) {"Unsupported metadata compression"}
                if(flags and 8==0) {
                    require((local.getInt(14).toLong() and U32)==crc && (local.getInt(18).toLong() and U32)==compressed && (local.getInt(22).toLong() and U32)==e.size) {"Metadata local header disagrees with directory"}
                }
                val nameSize=local.getShort(26).toInt() and 0xffff
                val extraSize=local.getShort(28).toInt() and 0xffff
                require(readAt(channel,e.localOffset+30,nameSize).toString(Charsets.UTF_8)==e.name) {"Metadata local name disagrees with directory"}
                val start=e.localOffset+30+nameSize+extraSize
                require(start>=0 && start+compressed<=directory.offset) {"Metadata region is outside ZIP data"}
                val raw=object:InputStream() {
                    var consumed=0L
                    override fun read():Int {val one=ByteArray(1);return if(read(one,0,1)<0) -1 else one[0].toInt() and 255}
                    override fun read(b:ByteArray,off:Int,len:Int):Int {
                        check(!cancelled())
                        if(len==0) return 0
                        if(consumed==compressed) return -1
                        val n=channel.read(ByteBuffer.wrap(b,off,minOf(len.toLong(),compressed-consumed).toInt()),start+consumed)
                        require(n>0) {"Metadata compressed data is truncated"};consumed+=n;return n
                    }
                    override fun close() {} // the caller owns its descriptor
                }
                val result=if(e.method==ZipEntry.STORED) {
                    require(compressed==e.size) {"Invalid stored metadata length"}
                    readBytes(raw,e.size,crc)
                } else {
                    val inflater=Inflater(true)
                    try {
                        val result=readBytes(InflaterInputStream(raw,inflater,4096),e.size,crc)
                        require(inflater.finished() && inflater.bytesRead==compressed) {"Metadata compressed length mismatch"}
                        result
                    } finally {inflater.end()}
                }
                if(flags and 8!=0) {
                    val at=start+compressed
                    val first=ByteBuffer.wrap(readAt(channel,at,4)).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and U32
                    val prefix=if(first==0x08074b50L) 4 else 0
                    require(at+prefix+12<=directory.offset) {"Truncated metadata descriptor"}
                    val descriptor=ByteBuffer.wrap(readAt(channel,at+prefix,12)).order(ByteOrder.LITTLE_ENDIAN)
                    require((descriptor.int.toLong() and U32)==crc && (descriptor.int.toLong() and U32)==compressed && (descriptor.int.toLong() and U32)==e.size) {"Metadata descriptor disagrees with directory"}
                }
                result
            }
            return parse(bytes(identities.singleOrNull()),bytes(anchors.singleOrNull()))
        }
        private fun checkSizes(size:Long,compressed:Long) {
            require(size in 0..MAX_BYTES && compressed in 0..MAX_COMPRESSED) {"Bundle metadata size limit exceeded"}
        }
        private fun readAt(channel:FileChannel,start:Long,size:Int):ByteArray {
            require(start>=0 && start+size<=channel.size()) {"Metadata header is outside the file"}
            val bytes=ByteArray(size);val buffer=ByteBuffer.wrap(bytes)
            while(buffer.hasRemaining()) require(channel.read(buffer,start+buffer.position())>0) {"Metadata header is truncated"}
            return bytes
        }
        private fun guarded(input:InputStream,cancelled:()->Boolean):InputStream = BundleEntries.cancellable(input,cancelled)
        private fun readBytes(input:InputStream,size:Long,crc:Long):ByteArray {
            val out=ByteArrayOutputStream();val sum=CRC32();val buffer=ByteArray(512)
            while(true) {
                val n=input.read(buffer);if(n<0) break
                require(out.size()+n<=MAX_BYTES) {"Bundle metadata decompression limit exceeded"}
                out.write(buffer,0,n);sum.update(buffer,0,n)
            }
            require(out.size().toLong()==size && sum.value==crc) {"Metadata size or CRC mismatch"}
            return out.toByteArray()
        }
        private fun parse(identity:ByteArray?,anchors:ByteArray?):BundleHeader = BundleHeader(
            identity?.let(::parseIdentity),anchors?.let {AnchorMetadata.read(ByteArrayInputStream(it))},anchors,
        )
        private fun parseIdentity(bytes:ByteArray):BundleIdentity = AnchorManifest.reader(ByteArrayInputStream(bytes)).use {reader->
            val p=JsonPull(reader,strict=true,tokenLimit=MAX_BYTES.toInt());val keys=hashSetOf<String>()
            var version:Int?=null;var id:String?=null;var resource:String?=null
            p.beginObject()
            while(p.hasNext()) {
                val key=p.nextName();require(keys.add(key)) {"Duplicate identity field"}
                when(key) {
                    "version" -> version=p.nextInt().also {require(it in 1..2)}
                    "documentId" -> id=p.nextString()
                    "pdfResource" -> resource=if(p.peek()==JsonPull.Token.NULL) {p.nextNull();null} else p.nextString()
                    else -> throw IllegalArgumentException("Unknown document identity field")
                }
            }
            p.endObject();require(p.peek()==JsonPull.Token.END_DOCUMENT)
            BundleIdentity(requireNotNull(id),resource,requireNotNull(version)==2)
        }
    }
}
