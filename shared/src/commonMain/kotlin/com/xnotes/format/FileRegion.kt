package com.xnotes.format

import com.xnotes.kio.PositionedFile
import okio.Buffer
import okio.IOException
import okio.Source
import okio.Timeout

class FileRegion internal constructor(private val base: PositionedFile, val start: Long, length: Long) : PositionedFile {
    private var length = length
    init { require(start >= 0 && length in 0..Long.MAX_VALUE - start) }
    override fun size() = length
    override fun read(at: Long, into: ByteArray, offset: Int, length: Int): Int {
        require(at >= 0)
        if (at >= this.length) return -1
        return base.read(start + at, into, offset, minOf(this.length - at, length.toLong()).toInt())
    }
    override fun write(at: Long, bytes: ByteArray, offset: Int, length: Int) {
        require(at in 0..Long.MAX_VALUE - start - length)
        base.write(start + at, bytes, offset, length)
        this.length = maxOf(this.length, at + length)
    }
    override fun truncate(size: Long) {
        require(size >= 0)
        if (size <= length) { base.truncate(start + size); length = size }
    }
}

internal fun PositionedFile.bytesAt(at: Long, length: Int): ByteArray? {
    if (length < 0 || at < 0 || at > size() - length) return null
    val bytes = ByteArray(length)
    var count = 0
    while (count < length) {
        val read = read(at + count, bytes, count, length - count)
        if (read <= 0) return null
        count += read
    }
    return bytes
}

internal fun PositionedFile.copyPrefixTo(dest: PositionedFile, at: Long, length: Long): Long {
    val bytes = ByteArray(65536)
    var copied = 0L
    while (copied < length) {
        val count = read(copied, bytes, 0, minOf(length - copied, bytes.size.toLong()).toInt())
        if (count <= 0) break
        dest.write(at + copied, bytes, 0, count)
        copied += count
    }
    return copied
}

internal fun PositionedFile.sourceAt(start: Long): Source = object : Source {
    private var position = start
    private val bytes = ByteArray(8192)
    override fun timeout() = Timeout.NONE
    override fun close() = Unit
    override fun read(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0)
        if (byteCount == 0L) return 0L
        val count = this@sourceAt.read(position, bytes, 0, minOf(byteCount, bytes.size.toLong()).toInt())
        if (count < 0) return -1
        if (count == 0) throw IOException("short read")
        sink.write(bytes, 0, count); position += count
        return count.toLong()
    }
}
