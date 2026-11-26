package com.xnotes.format

import com.xnotes.core.pal.AssetFile
import com.xnotes.core.pal.assetPath
import com.xnotes.kio.KFiles
import com.xnotes.kio.openWritable
import okio.Buffer
import okio.Closeable
import okio.ForwardingSource
import okio.Source
import okio.Sink
import okio.buffer
import okio.use

internal class DirectoryEntry(val name: String, val method: Int, val size: Long, val compressedSize: Long, val crc: Long, val time: Long, val open: () -> Source)

internal interface BundleDirectory : Closeable {
    val entries: List<DirectoryEntry>
    val comment: String?
    fun entry(name: String): DirectoryEntry? = entries.lastOrNull { it.name == name }
}

internal expect fun bundleDirectory(file: AssetFile): BundleDirectory
internal expect fun inflateMetadata(bytes: ByteArray, size: Long): ByteArray
internal expect fun isBundleZipError(error: Exception): Boolean

internal fun BundleDirectory.open(name: String) = requireNotNull(entry(name)) { "Missing bundle entry: $name" }.open()
internal fun AssetFile.input() = KFiles.system.source(assetPath())
internal inline fun <T> AssetFile.durable(write: (Sink) -> T): T = openWritable(assetPath(), false).use { file ->
    file.resize(0); val result = file.sink().use(write); file.flush(); result
}
internal fun readBundleIdentity(file: AssetFile): BundleIdentity? = bundleDirectory(file).use { archive ->
    archive.entries.filter { it.name == BundleIdentity.ENTRY }.also { require(it.size <= 1) { "Duplicate identity metadata" } }
        .singleOrNull()?.open()?.buffer()?.use(::decodeIdentity)
}
internal fun BundleWriter.putOriginal(entry: DirectoryEntry, replace: Boolean = false, write: (Sink) -> Unit) =
    entry(entry.name, entry.time, entry.size.takeIf { !replace && entry.method == 0 }, entry.crc, write)
internal fun Sink.putBytes(bytes: ByteArray) = write(Buffer().write(bytes),bytes.size.toLong())

internal fun Source.cancellable(cancelled: () -> Boolean): Source = object : ForwardingSource(this) {
    override fun read(sink: Buffer, byteCount: Long): Long {
        check(!cancelled())
        return super.read(sink, byteCount)
    }
}

internal fun ByteArray.u16(at: Int): Int = (this[at].toInt() and 255) or ((this[at + 1].toInt() and 255) shl 8)
internal fun ByteArray.u32(at: Int): Long = u16(at).toLong() or (u16(at + 2).toLong() shl 16)

/** Borrows the directory. Closing the current entry never drains the rest of its payload. */
internal fun BundleDirectory.reader(): BundleReader = object : BundleReader {
    init { require(entries.map { it.name }.toSet().size == entries.size) { "Duplicate ZIP entries" } }
    private val iterator = entries.iterator()
    private var current: okio.BufferedSource? = null

    private var isPdf = false

    override fun next(): BundleEntry? {
        if (!isPdf) current?.readAll(okio.blackholeSink())
        close()
        if (!iterator.hasNext()) return null
        val entry = iterator.next()
        isPdf = entry.name == BundlePdf.ENTRY
        // On a miss the cache verifies the PDF as it writes it. On a hit it needs no payload bytes.
        val source = if (isPdf) entry.open().buffer() else entry.checkedSource()
        current = source
        return BundleEntry(entry.name, source, entry.method, entry.size) {
            EntrySizes(entry.size, entry.compressedSize, entry.crc)
        }
    }

    override fun close() { current?.close(); current = null }
}

private fun DirectoryEntry.checkedSource(): okio.BufferedSource {
    val raw = open().buffer()
    val sum = com.xnotes.kio.Crc32()
    return object : Source by raw {
        private val bytes = ByteArray(8192)
        private var count = 0L
        override fun read(sink: Buffer, byteCount: Long): Long {
            val n = raw.read(bytes, 0, minOf(bytes.size.toLong(), byteCount).toInt())
            if (n < 0) {
                require(count == size && sum.value == crc) { "Corrupt ZIP entry: $name" }
                return -1
            }
            count += n
            require(count <= size)
            sum.update(bytes, 0, n)
            sink.write(bytes, 0, n)
            return n.toLong()
        }
    }.buffer()
}
