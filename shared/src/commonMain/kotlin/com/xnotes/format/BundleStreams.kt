package com.xnotes.format

import com.xnotes.core.pal.AssetFile
import com.xnotes.core.pal.asAssetFile
import com.xnotes.core.pal.assetPath
import com.xnotes.kio.KFiles
import com.xnotes.kio.Uuids
import okio.*

internal data class BundleEntry(val name: String, val data: BufferedSource, val method: Int) {
    val isDirectory get() = name.endsWith('/')
}

internal interface BundleReader : Closeable { fun next(): BundleEntry? }
internal interface BundleWriter : Closeable {
    fun deflated(name: String, write: (Sink) -> Unit)
    fun stored(name: String, file: AssetFile, checkActive: () -> Unit)
}

internal expect fun bundleReader(source: Source): BundleReader
internal expect fun bundleWriter(sink: Sink): BundleWriter
internal expect fun encodeIdentity(identity: BundleIdentity): ByteArray
internal expect fun decodeIdentity(data: BufferedSource): BundleIdentity
internal expect fun jsonChars(source: Source, strict: Boolean = false): JsonChars
internal expect fun writeJson(sink: Sink, write: (JsonWrite) -> Unit)
expect abstract class DocumentStreamAdapters()
expect abstract class CanvasStreamAdapters()

internal fun BundleWriter.putDeflated(name: String, bytes: ByteArray) = deflated(name) { sink ->
    val data = Buffer().write(bytes)
    sink.write(data, data.size)
}

internal fun readAtMost(source: BufferedSource, limit: Int): ByteArray? =
    if (source.request(limit.toLong() + 1)) null else source.readByteArray()

internal fun temporaryAsset(prefix: String, suffix: String?, directory: AssetFile): AssetFile {
    val path = directory.assetPath() / (prefix + Uuids.random() + (suffix ?: ".tmp"))
    KFiles.system.sink(path, mustCreate = true).close()
    return path.asAssetFile()
}

internal fun AssetFile.receive(source: BufferedSource) = KFiles.system.sink(assetPath()).use { source.readAll(it) }
internal fun AssetFile.remove() = KFiles.system.delete(assetPath())
internal fun AssetFile.readableFile() = KFiles.system.metadataOrNull(assetPath())?.isRegularFile == true &&
    runCatching { KFiles.system.source(assetPath()).close() }.isSuccess
