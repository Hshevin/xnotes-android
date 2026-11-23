package com.xnotes.format

import com.xnotes.kio.PositionedFile
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import okio.buffer

internal fun FileChannel.borrow(): PositionedFile = object : PositionedFile {
    override fun size() = this@borrow.size()
    override fun read(at: Long, into: ByteArray, offset: Int, length: Int) =
        this@borrow.read(ByteBuffer.wrap(into, offset, length), at)
    override fun write(at: Long, bytes: ByteArray, offset: Int, length: Int) {
        val buffer = ByteBuffer.wrap(bytes, offset, length)
        while (buffer.hasRemaining()) {
            if (this@borrow.write(buffer, at + (buffer.position() - offset)) <= 0) throw IOException("short write")
        }
    }
    override fun truncate(size: Long) { this@borrow.truncate(size) }
}

internal fun ZipTail.read(channel: FileChannel) = read(channel.borrow())
internal fun ZipTail.splice(dest: FileChannel, tailStart: Long, keep: List<ZipTail.Entry>, tail: FileChannel, tailDir: ZipTail.Directory) =
    splice(dest.borrow(), tailStart, keep, tail.borrow(), tailDir)
internal fun <T> ZipTail.readEntry(channel: FileChannel, name: String, reader: (InputStream) -> T) =
    readEntry(channel.borrow(), name) { reader(it.buffer().inputStream()) }

internal fun XnotesPdf.inspect(channel: FileChannel) = inspect(channel.borrow())
internal fun XnotesPdf.stamp(channel: FileChannel) = stamp(channel.borrow())
internal fun XnotesPdf.embedded(channel: FileChannel, ours: XnotesPdf.Inspection.Ours) =
    embedded(channel.borrow(), ours)?.let { FileWindow(channel, it.start, it.size()) }
internal fun XnotesPdf.appendTail(channel: FileChannel, zip: FileChannel) = appendTail(channel.borrow(), zip.borrow())
internal fun XnotesPdf.rewriteTail(channel: FileChannel, tail: XnotesPdf.Tail, zip: FileChannel) = rewriteTail(channel.borrow(), tail, zip.borrow())
internal fun XnotesPdf.ending(channel: FileChannel) = ending(channel.borrow())
internal fun XnotesPdf.closeTail(channel: FileChannel, tail: XnotesPdf.Tail, zipLength: Long) = closeTail(channel.borrow(), tail, zipLength)
internal fun XnotesPdf.openTail(channel: FileChannel, tail: XnotesPdf.Tail) =
    openTail(channel.borrow(), tail).let { FileWindow(channel, it.start, it.size()) }
internal fun XnotesPdf.Tail.window(channel: FileChannel) = FileWindow(channel, zipStart, zipLength)

internal fun BundleHeader.Companion.read(channel: FileChannel, cancelled: () -> Boolean = { false }) = read(channel.borrow(), cancelled)
