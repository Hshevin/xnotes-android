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
