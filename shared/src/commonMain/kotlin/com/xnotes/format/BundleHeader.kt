package com.xnotes.format

import com.xnotes.core.pal.AssetFile
import com.xnotes.core.pal.assetPath
import com.xnotes.kio.Crc32
import com.xnotes.kio.PositionedFile
import com.xnotes.kio.openRead
import okio.*

/** Metadata only. Absence is a header with null fields; null from the channel API means use fallback. */
class BundleHeader private constructor(
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
        fun read(file:AssetFile,cancelled:()->Boolean={false}):BundleHeader {
            check(!cancelled()) {"Bundle header cancelled"}
            openRead(file.assetPath()).use {input->read(input,cancelled)?.let {header->
                return header
            }}
            // ZIP64 fallback reads the directory and the two metadata entries.
            return bundleDirectory(file).use {zip->
                val metadata=linkedMapOf<String,Pair<DirectoryEntry,ByteArray>>()
                for(entry in zip.entries) {
                    check(!cancelled())
                    if(entry.name==BundleIdentity.ENTRY || entry.name==AnchorMetadata.ENTRY) {
                    require(entry.name !in metadata) {"Duplicate bundle metadata"}
                    checkSizes(entry.size,entry.compressedSize)
                    metadata[entry.name]=entry to entry.open().cancellable(cancelled).buffer().use {readBytes(it,entry.size,entry.crc)}
                    }
                }
                val header=parse(metadata[BundleIdentity.ENTRY]?.second,metadata[AnchorMetadata.ENTRY]?.second)
                val anchored=AnchorMetadata.ENTRY in metadata
                if(anchored) for(entry in zip.entries) {
                    check(!cancelled())
                    if(entry.name=="manifest.json") require(entry.size<0 || entry.size<=AnchorManifest.MAX_BYTES) {"ZIP declared manifest limit exceeded"}
                }
                header
            }
        }
        /** Does not own or reposition the channel. Unsupported directory layouts require the File API. */
        fun read(channel:PositionedFile,cancelled:()->Boolean={false}):BundleHeader? {
            check(!cancelled()) {"Bundle header cancelled"}
            val directory=ZipTail.read(channel, allowComment = true) ?: return null
            check(!cancelled())
            if (directory.entries.any { it.name == AnchorMetadata.ENTRY }) require(directory.entries.none { it.name == "manifest.json" && it.size > AnchorManifest.MAX_BYTES })
            val identities=directory.entries.filter {it.name==BundleIdentity.ENTRY}
            val anchors=directory.entries.filter {it.name==AnchorMetadata.ENTRY}
            require(identities.size<=1 && anchors.size<=1) {"Duplicate bundle metadata"}
            if((identities+anchors).any {it.method!=0 && it.method!=8}) return null
            for(entry in identities+anchors) {
                check(!cancelled())
                val central=entry.record
                if(entry.size==U32 || (central.u32(20))==U32 || entry.localOffset==U32) return null
                val local=readAt(channel,entry.localOffset,30)
                if((local.u32(18))==U32 || (local.u32(22))==U32) return null
                val nameSize=local.u16(26)
                val extraSize=local.u16(28)
                val extra=readAt(channel,entry.localOffset+30+nameSize,extraSize)
                var cursor=0
                while(cursor<extra.size) {
                    require(extra.size-cursor>=4) {"Invalid metadata extra field"}
                    val tag=extra.u16(cursor); val length=extra.u16(cursor+2); cursor+=4
                    require(length<=extra.size-cursor) {"Invalid metadata extra field length"}
                    if(tag==1) return null
                    cursor+=length
                }
            }
            fun bytes(entry:ZipTail.Entry?):ByteArray? = entry?.let {e->
                check(!cancelled())
                val central=e.record
                val compressed=central.u32(20)
                val crc=central.u32(16)
                require((central.u16(8) and 1)==0) {"Encrypted metadata is unsupported"}
                checkSizes(e.size,compressed)
                val local=readAt(channel,e.localOffset,30)
                require(local.u32(0)==0x04034b50L) {"Invalid metadata local header"}
                val flags=local.u16(6)
                require(flags and 1==0 && (local.u16(8))==e.method) {"Unsupported metadata compression"}
                if(flags and 8==0) {
                    require((local.u32(14))==crc && (local.u32(18))==compressed && (local.u32(22))==e.size) {"Metadata local header disagrees with directory"}
                }
                val nameSize=local.u16(26)
                val extraSize=local.u16(28)
                require(readAt(channel,e.localOffset+30,nameSize).decodeToString()==e.name) {"Metadata local name disagrees with directory"}
                val start=e.localOffset+30+nameSize+extraSize
                require(start>=0 && start+compressed<=directory.offset) {"Metadata region is outside ZIP data"}
                val raw=FileRegion(channel,start,compressed).sourceAt(0).cancellable(cancelled).buffer().use { it.readByteArray(compressed) }
                val result=if(e.method==0) {
                    require(compressed==e.size) {"Invalid stored metadata length"}; raw
                } else inflateMetadata(raw,e.size)
                require(Crc32.of(result)==crc) {"Metadata size or CRC mismatch"}
                if(flags and 8!=0) {
                    val at=start+compressed
                    val first=readAt(channel,at,4).u32(0)
                    val prefix=if(first==0x08074b50L) 4 else 0
                    require(at+prefix+12<=directory.offset) {"Truncated metadata descriptor"}
                    val descriptor=readAt(channel,at+prefix,12)
                    require(descriptor.u32(0)==crc && descriptor.u32(4)==compressed && descriptor.u32(8)==e.size) {"Metadata descriptor disagrees with directory"}
                }
                result
            }
            return parse(bytes(identities.singleOrNull()),bytes(anchors.singleOrNull()))
        }
        private fun checkSizes(size:Long,compressed:Long) {
            require(size in 0..MAX_BYTES && compressed in 0..MAX_COMPRESSED) {"Bundle metadata size limit exceeded"}
        }
        private fun readAt(channel:PositionedFile,start:Long,size:Int):ByteArray = requireNotNull(channel.bytesAt(start,size)) {"Metadata header is outside the file"}
        private fun readBytes(input:BufferedSource,size:Long,crc:Long):ByteArray {
            val bytes=requireNotNull(readAtMost(input,MAX_BYTES.toInt())) {"Bundle metadata decompression limit exceeded"}
            require(bytes.size.toLong()==size && Crc32.of(bytes)==crc) {"Metadata size or CRC mismatch"}
            return bytes
        }
        private fun parse(identity:ByteArray?,anchors:ByteArray?):BundleHeader = BundleHeader(
            identity?.let(::parseIdentity),anchors?.let(AnchorMetadata::decode),anchors,
        )
        private fun parseIdentity(bytes:ByteArray):BundleIdentity {
            val p=JsonPull(bytes.decodeToString(throwOnInvalidSequence=true),strict=true,tokenLimit=MAX_BYTES.toInt());val keys=hashSetOf<String>()
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
            return BundleIdentity(requireNotNull(id),resource,requireNotNull(version)==2)
        }
    }
}
