@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.xnotes.format

import com.xnotes.core.pal.AssetFile
import com.xnotes.core.pal.assetPath
import com.xnotes.kio.Crc32
import com.xnotes.kio.KFiles
import kotlinx.cinterop.*
import okio.*
import platform.zlib.*

private const val LOCAL = 0x04034b50
private const val CENTRAL = 0x02014b50
private const val DESCRIPTOR = 0x08074b50
private const val END = 0x06054b50
private const val UINT = 0xffffffffL
private fun BufferedSource.short() = readShortLe().toInt() and 65535
private fun BufferedSource.word() = readIntLe().toLong() and UINT

private class ZipPayload(val input: BufferedSource, val method: Int, val length: Long, val descriptor: Boolean) : Source {
    val crc = Crc32()
    var packed = 0L
    var unpacked = 0L
    private val output = ByteArray(16384)
    private val encoded = ByteArray(16384)
    private var ended = false
    private var closed = false
    private val stream = if (method == 8) nativeHeap.alloc<z_stream_s>().also {
        it.zalloc = null; it.zfree = null; it.opaque = null
        if (inflateInit2(it.ptr, -15) != Z_OK) { nativeHeap.free(it.ptr); throw IOException("Cannot initialize ZIP inflater") }
    } else null

    override fun timeout() = input.timeout()
    override fun read(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0)
        if (byteCount == 0L) return 0
        if (ended) return -1
        val count = minOf(byteCount, output.size.toLong()).toInt()
        val size = if (stream == null) {
            if (packed == length) return -1
            input.read(output, 0, minOf(count.toLong(), length-packed).toInt()).also {
                if (it < 0) throw EOFException("Truncated stored ZIP entry")
                packed += it
            }
        } else {
            var produced: Int
            do {
                if (!input.request(1)) throw EOFException("Truncated deflated ZIP entry")
                val available = minOf(encoded.size.toLong(), input.buffer.size, if (descriptor) Long.MAX_VALUE else length - packed).toInt()
                require(available >= 0)
                input.peek().readFully(encoded, 0, available)
                val status = encoded.usePinned { source -> output.usePinned { target ->
                    stream.next_in = source.addressOf(0).reinterpret(); stream.avail_in = available.toUInt()
                    stream.next_out = target.addressOf(0).reinterpret(); stream.avail_out = count.toUInt()
                    inflate(stream.ptr, Z_NO_FLUSH)
                } }
                if (status != Z_OK && status != Z_STREAM_END) throw IOException("Invalid ZIP deflate payload ($status)")
                val consumed = available - stream.avail_in.toInt()
                input.skip(consumed.toLong()); packed += consumed
                produced = count - stream.avail_out.toInt()
                ended = status == Z_STREAM_END
                if (!ended && produced == 0 && consumed == 0) throw IOException("Invalid ZIP entry length")
            } while (!ended && produced == 0)
            if (produced == 0) return -1
            produced
        }
        crc.update(output, 0, size); unpacked += size; sink.write(output, 0, size)
        return size.toLong()
    }
    override fun close() {
        if (!closed && stream != null) { inflateEnd(stream.ptr); nativeHeap.free(stream.ptr) }
        closed = true
    }
}

internal actual fun bundleReader(source: Source): BundleReader = object : BundleReader {
    val input = source.buffer()
    var payload: ZipPayload? = null
    var body: BufferedSource? = null
    var crc = 0L
    var packed = 0L
    var unpacked = 0L
    var wide = false
    fun finish() {
        val active = payload ?: return
        try {
            body!!.readAll(blackholeSink())
            if (active.descriptor) {
                val first = input.word()
                crc = if (first == DESCRIPTOR.toLong()) input.word() else first
                packed = if (wide || active.packed >= UINT || active.unpacked >= UINT) input.readLongLe() else input.word()
                unpacked = if (wide || active.packed >= UINT || active.unpacked >= UINT) input.readLongLe() else input.word()
            }
            if (crc != active.crc.value || packed != active.packed || unpacked != active.unpacked) throw IOException("Corrupt ZIP entry")
        } finally { active.close(); payload = null; body = null }
    }
    override fun next(): BundleEntry? {
        finish()
        if (!input.request(4) || input.readIntLe() != LOCAL) return null
        input.skip(2); val flags = input.short(); val method = input.short(); input.skip(4)
        crc = input.word(); packed = input.word(); unpacked = input.word()
        val nameLength = input.short(); val extraLength = input.short()
        val name = input.readByteArray(nameLength.toLong()).decodeToString(throwOnInvalidSequence = true)
        val extra = Buffer().write(input.readByteArray(extraLength.toLong()))
        wide = packed == UINT || unpacked == UINT
        if (wide) {
            while (extra.size >= 4) {
                val type = extra.short(); val size = extra.short()
                if (type == 1) {
                    val data = Buffer().write(extra.readByteArray(size.toLong()))
                    if (unpacked == UINT) unpacked = data.readLongLe()
                    if (packed == UINT) packed = data.readLongLe()
                    break
                }
                extra.skip(size.toLong())
            }
        }
        val descriptor = flags and 8 != 0
        if (flags and 1 != 0 || method !in listOf(0,8) || (descriptor && method == 0) || packed < 0 || unpacked < 0) throw IOException("Unsupported ZIP entry")
        val entry = ZipPayload(input, method, packed, descriptor)
        payload = entry; body = entry.buffer()
        val nonClosing = object : Source by body!! { override fun close() = Unit }
        return BundleEntry(name, nonClosing.buffer(), method)
    }
    override fun close() { try { payload?.close() } finally { input.close() } }
}

private class ZipOutput(sink: Sink) : BundleWriter {
    private val output = sink.buffer()
    private val directory = Buffer()
    private val names = HashSet<String>()
    private var offset = 0L
    private val scratch = ByteArray(65536)
    private fun emit(buffer: Buffer) { offset += buffer.size; output.writeAll(buffer) }

    private fun header(name: String, method: Int, crc: Long, size: Long): Long {
        require(names.add(name)) { "Duplicate ZIP entry: $name" }
        val bytes = name.encodeToByteArray(); require(bytes.size <= 65535)
        val start = offset
        val extra = Buffer()
        if (size >= UINT) extra.writeShortLe(1).writeShortLe(16).writeLongLe(size).writeLongLe(size)
        emit(Buffer().writeIntLe(LOCAL).writeShortLe(if (size >= UINT) 45 else 20).writeShortLe(if (method == 8) 0x808 else 0x800)
            .writeShortLe(method).writeIntLe(0x00210000).writeIntLe(crc.toInt()).writeIntLe(minOf(UINT,size).toInt())
            .writeIntLe(minOf(UINT,size).toInt()).writeShortLe(bytes.size).writeShortLe(extra.size.toInt()).write(bytes).apply { writeAll(extra) })
        return start
    }
    private fun record(name: String, method: Int, start: Long, crc: Long, size: Long, packed: Long) {
        val fields = Buffer()
        if (size >= UINT) fields.writeLongLe(size)
        if (packed >= UINT) fields.writeLongLe(packed)
        if (start >= UINT) fields.writeLongLe(start)
        val extra = Buffer()
        if (fields.size > 0) { extra.writeShortLe(1).writeShortLe(fields.size.toInt()); extra.writeAll(fields) }
        val bytes = name.encodeToByteArray(); val version = if (extra.size > 0) 45 else 20
        directory.writeIntLe(CENTRAL).writeShortLe(version).writeShortLe(version).writeShortLe(if (method == 8) 0x808 else 0x800)
            .writeShortLe(method).writeIntLe(0x00210000).writeIntLe(crc.toInt()).writeIntLe(minOf(UINT,packed).toInt())
            .writeIntLe(minOf(UINT,size).toInt()).writeShortLe(bytes.size).writeShortLe(extra.size.toInt()).writeShortLe(0)
            .writeShortLe(0).writeShortLe(0).writeIntLe(0).writeIntLe(minOf(start,UINT).toInt()).write(bytes).writeAll(extra)
    }
    override fun deflated(name: String, write: (Sink) -> Unit) {
        val start = header(name, 8, 0, 0); val dataStart = offset
        val crc = Crc32(); var size = 0L
        val counting = object : Sink by output {
            override fun close() = Unit
            override fun write(source: Buffer, byteCount: Long) { offset += byteCount; output.write(source,byteCount) }
        }
        DeflaterSink(counting, Deflater(1,true)).use { compressed ->
            write(object : Sink by compressed {
                override fun write(source: Buffer, byteCount: Long) {
                    val view = source.peek(); var left = byteCount
                    while (left > 0) { val count = minOf(left,scratch.size.toLong()).toInt(); view.readFully(scratch,0,count); crc.update(scratch,0,count); left -= count }
                    size += byteCount; compressed.write(source,byteCount)
                }
            })
        }
        val packed = offset - dataStart
        emit(Buffer().writeIntLe(DESCRIPTOR).writeIntLe(crc.value.toInt()).apply {
            if (size >= UINT || packed >= UINT) { writeLongLe(packed);writeLongLe(size) } else { writeIntLe(packed.toInt());writeIntLe(size.toInt()) }
        })
        record(name, 8, start, crc.value, size, packed)
    }
    override fun stored(name: String, file: AssetFile, checkActive: () -> Unit) {
        val path = file.assetPath()
        fun copy(sink: BufferedSink?): Pair<Long,Long> {
            val crc = Crc32(); var size = 0L
            KFiles.system.read(path) {
                while (true) { checkActive(); val n = read(scratch); if (n < 0) break; crc.update(scratch,0,n); size += n; sink?.write(scratch,0,n) }
            }
            return size to crc.value
        }
        val (size,crc) = copy(null); val start = header(name,0,crc,size)
        check(copy(output) == size to crc) { "ZIP asset changed during save" }
        offset += size; record(name,0,start,crc,size,size)
    }
    override fun close() {
        try {
            val start = offset; val length = directory.size; val count = names.size.toLong(); emit(directory)
            if (start >= UINT || length >= UINT || count >= 65535) {
                val end = offset
                emit(Buffer().writeIntLe(0x06064b50).writeLongLe(44).writeShortLe(45).writeShortLe(45).writeIntLe(0).writeIntLe(0)
                    .writeLongLe(count).writeLongLe(count).writeLongLe(length).writeLongLe(start).writeIntLe(0x07064b50).writeIntLe(0).writeLongLe(end).writeIntLe(1))
            }
            emit(Buffer().writeIntLe(END).writeIntLe(0).writeShortLe(minOf(count,65535L).toInt()).writeShortLe(minOf(count,65535L).toInt())
                .writeIntLe(minOf(length,UINT).toInt()).writeIntLe(minOf(start,UINT).toInt()).writeShortLe(0))
        } finally { output.close() }
    }
}

private fun BufferedSource.readFully(bytes: ByteArray, offset: Int, length: Int) {
    var done = 0
    while (done < length) { val count = read(bytes, offset+done, length-done); if (count == -1) throw EOFException(); done += count }
}
internal actual fun bundleWriter(sink: Sink): BundleWriter = ZipOutput(sink)
