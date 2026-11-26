package com.xnotes.storage.lite

import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Document
import com.xnotes.core.model.fixedWriteSnapshot
import com.xnotes.core.pal.ImageCodec
import com.xnotes.core.pal.TextMeasurer
import com.xnotes.core.pal.asAssetFile
import com.xnotes.core.pal.assetPath
import com.xnotes.core.util.CoreLock
import com.xnotes.core.util.locked
import com.xnotes.format.*
import com.xnotes.kio.Uuids
import com.xnotes.kio.atomicWrite
import com.xnotes.kio.openRead
import com.xnotes.kio.openReadWrite
import okio.FileSystem
import okio.Path
import okio.Source
import okio.buffer
import okio.use

internal class FutureVersion : Exception()

internal sealed class LiteBody {
    abstract val documentId: String
    abstract val kind: LiteKind
    abstract fun snapshot(): LiteBody

    class Note(val doc: Document) : LiteBody() {
        override val documentId get() = doc.identity.documentId
        override val kind get() = LiteKind.NOTE
        override fun snapshot() = Note(doc.fixedWriteSnapshot())
    }

    class Canvas(val doc: InfiniteDocument) : LiteBody() {
        override val documentId get() = doc.identity.documentId
        override val kind get() = LiteKind.CANVAS
        override fun snapshot() = Canvas(doc.snapshotForWrite(fixedItems = true))
    }
}

internal data class StoredAsset(val name: String, val file: Path, val stamp: Stamp)
internal class Decoded(val body: LiteBody, val assets: List<StoredAsset>) {
    val documentId = body.documentId
    val pdf = assets.singleOrNull { it.name == BundlePdf.ENTRY }
    fun checkPdf() {
        require(pdf == null || fileStamp(pdf.file) == pdf.stamp) { "Embedded PDF changed" }
    }
}

// Hard links in session directories keep open assets alive when old cache entries are evicted.
private val pdfCacheLock = CoreLock()
internal expect fun pdfCacheDirectory(privateDir: Path): Path

internal class LiteCodec(private val fs: FileSystem, images: ImageCodec, text: TextMeasurer) {
    private val notes = DocumentCodec(images, text)
    private val canvases = CanvasCodec(images, text)

    fun read(file: Path, kind: LiteKind, assets: Path): Decoded {
        checkVersions(file)
        val header = BundleHeader.read(file.asAssetFile())
        val identity = requireNotNull(header.identity)
        val dir = assets.asAssetFile()
        val body = when (kind) {
            LiteKind.NOTE -> {
                require(!identity.externalPdf)
                bundleDirectory(file.asAssetFile()).use { directory ->
                    val pdf = directory.entry(BundlePdf.ENTRY)
                    val note = if (pdf != null) {
                        notes.readNote(directory, dir) { input ->
                            cachedPdf(identity.documentId, pdf, assets, input).asAssetFile()
                        }
                    } else fs.source(file).use { notes.readComplete(it, dir, dir, preserveInkSamples = true).document }
                    require(note.hasReferenceAnchors == (header.anchors != null))
                    LiteBody.Note(note)
                }
            }
            LiteKind.CANVAS -> LiteBody.Canvas(fs.source(file).use { canvases.readComplete(it, dir).document })
        }
        require(body.documentId == identity.documentId)
        return decoded(body)
    }

    private fun cachedPdf(id: String, entry: DirectoryEntry, assets: Path, input: Source): Path = pdfCacheLock.locked {
        val cache = pdfCacheDirectory(assets.parent!!)
        fs.createDirectories(cache)
        val file = cache / "$id-${entry.crc}-${entry.size}.pdf"
        val tag = cache / "${file.name}.stamp"
        val stamp = fileStamp(file)
        val valid = stamp?.size == entry.size && runCatching {
            fs.read(tag) { readLong() == stamp.identity.device && readLong() == stamp.identity.inode && readLong() == stamp.modifiedNanos }
        }.getOrDefault(false)
        if (!valid) {
            atomicWrite(fs, file) { out ->
                val crc = com.xnotes.kio.Crc32()
                val bytes = ByteArray(65536)
                val source = input.buffer()
                var size = 0L
                while (true) {
                    val n = source.read(bytes)
                    if (n < 0) break
                    size += n
                    require(size <= entry.size)
                    crc.update(bytes, 0, n)
                    out.write(bytes, 0, n)
                }
                require(size == entry.size && crc.value == entry.crc)
            }
            val written = requireNotNull(fileStamp(file))
            atomicWrite(fs, tag) { it.writeLong(written.identity.device).writeLong(written.identity.inode).writeLong(written.modifiedNanos) }
        }
        val sessionFile = assets / "source.pdf"
        check(linkNewFile(file, sessionFile))
        val others = fs.list(cache).filter { it != file && it.name.endsWith(".pdf") }.sortedByDescending { fs.metadata(it).lastModifiedAtMillis }
        others.drop(7).forEach { fs.delete(it); fs.delete(cache / "${it.name}.stamp") }
        sessionFile
    }

    fun decoded(body: LiteBody) = Decoded(body, stored(body))

    fun stored(body: LiteBody): List<StoredAsset> {
        val doc = (body as? LiteBody.Note)?.doc ?: return emptyList()
        return notes.storedEntries(doc, notes.imageAssets(doc)).map { (name, file) ->
            val path = file.assetPath()
            StoredAsset(name, path, requireNotNull(fileStamp(path)))
        }
    }

    fun splice(path: Path, privateDir: Path, body: LiteBody, current: List<StoredAsset>): Boolean {
        val doc = (body as? LiteBody.Note)?.doc ?: return false
        if (current.isEmpty()) return false
        openReadWrite(path).use { dest ->
            val entries = ZipTail.read(dest)?.entries?.sortedBy { it.localOffset } ?: return false
            if (entries.size <= current.size) return false
            val keep = entries.take(current.size)
            if (!keep.zip(current).all { (e, a) -> e.method == 0 && e.name == a.name && e.size == a.stamp.size }) return false
            val suffix = entries.drop(current.size)
            val start = suffix.first().localOffset
            if (start < 1024L * 1024 || suffix.any { it.method == 0 }) return false
            val tail = privateDir / "tail-${Uuids.random()}.zip"
            try {
                fs.sink(tail, mustCreate = true).use { sink ->
                    bundleWriter(sink).use { notes.writeTail(it, doc, notes.imageAssets(doc)) }
                }
                return openRead(tail).use { extra ->
                    val directory = ZipTail.read(extra) ?: return false
                    if (ZipTail.splice(dest, start, keep, extra, directory) < 0) return false
                    dest.sync()
                    true
                }
            } finally { fs.delete(tail) }
        }
    }

    private fun checkVersions(file: Path) {
        val versions = mapOf("manifest.json" to DocumentCodec.VERSION, BundleIdentity.ENTRY to 2,
            AnchorMetadata.ENTRY to 1, MapJson.ENTRY to MapJson.VERSION)
        fun checkVersion(input: Source, max: Int) {
            val parser = JsonPull(jsonChars(input, strict = true), strict = true)
            parser.beginObject()
            while (parser.hasNext()) {
                if (parser.nextName() == "version") {
                    val version = parser.nextInt()
                    if (version > max) throw FutureVersion()
                    require(version >= 1)
                    return
                }
                parser.skipValue()
            }
            error("Missing entry version")
        }
        openRead(file).use { handle ->
            val directory = ZipTail.read(handle, allowComment = true)
            if (directory != null) {
                for (entry in directory.entries) {
                    val max = versions[entry.name] ?: continue
                    requireNotNull(ZipTail.readEntry(handle, entry) { checkVersion(it, max) })
                }
                return
            }
        }
        // ZIP64 archives remain readable through the platform directory reader.
        bundleDirectory(file.asAssetFile()).use { directory ->
            for ((name, max) in versions) directory.entry(name)?.open()?.use { checkVersion(it, max) }
        }
    }

    fun write(body: LiteBody, path: Path) = atomicWrite(fs, path) { sink ->
        when (body) {
            is LiteBody.Note -> notes.write(body.doc, sink)
            is LiteBody.Canvas -> canvases.write(body.doc, sink)
        }
    }
}
