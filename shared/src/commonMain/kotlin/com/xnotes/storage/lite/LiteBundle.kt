package com.xnotes.storage.lite

import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Document
import com.xnotes.core.model.fixedWriteSnapshot
import com.xnotes.core.pal.ImageCodec
import com.xnotes.core.pal.TextMeasurer
import com.xnotes.core.pal.asAssetFile
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.AnchorMetadata
import com.xnotes.format.BundleIdentity
import com.xnotes.format.BundleReferenceInfo
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import com.xnotes.format.JsonPull
import com.xnotes.format.MapJson
import com.xnotes.format.bundleDirectory
import com.xnotes.format.jsonChars
import com.xnotes.kio.atomicWrite
import okio.FileSystem
import okio.HashingSink
import okio.Path
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

    fun checkDecoded(actual: LiteBody) {
        require(actual.documentId == documentId && actual.kind == kind)
        when (this) {
            is Note -> {
                val read = (actual as Note).doc
                require(read.hasReferenceAnchors == doc.hasReferenceAnchors && read.pages.size == doc.pages.size)
                for ((page, decoded) in doc.pages.zip(read.pages)) {
                    require(page.referenceIdentity.id == decoded.referenceIdentity.id && page.pdfPage == decoded.pdfPage)
                    require(page.items.map { it::class } == decoded.items.map { it::class })
                }
            }
            is Canvas -> {
                val read = (actual as Canvas).doc
                require(doc.items.map { it::class } == read.items.map { it::class })
                require(doc.map.state.nodes.keys == read.map.state.nodes.keys)
            }
        }
    }
}

internal class Decoded(val body: LiteBody, val pdfHash: String?, val anchored: Boolean) {
    val documentId = body.documentId
}

internal class LiteCodec(private val fs: FileSystem, images: ImageCodec, text: TextMeasurer) {
    private val notes = DocumentCodec(images, text)
    private val canvases = CanvasCodec(images, text)

    fun read(file: Path, kind: LiteKind, assets: Path): Decoded {
        val asset = file.asAssetFile()
        val info = try {
            BundleReferenceInfo.inspectStrict(asset)
        } catch (failure: Exception) {
            checkVersions(file)
            throw failure
        }
        val identity = requireNotNull(info.header.identity)
        val dir = assets.asAssetFile()
        val body = when (kind) {
            LiteKind.NOTE -> {
                require(info.kind == DocumentKind.NOTE && !identity.externalPdf)
                val doc = fs.source(file).use { notes.readComplete(it, dir, dir, preserveInkSamples = true).document }
                require(info.orderedPageIds == doc.pages.mapNotNull { it.referenceIdentity.id })
                LiteBody.Note(doc)
            }
            LiteKind.CANVAS -> {
                require(info.kind == DocumentKind.CANVAS)
                try {
                    LiteBody.Canvas(fs.source(file).use { canvases.readComplete(it, dir).document })
                } catch (failure: Exception) {
                    checkVersions(file)
                    throw failure
                }
            }
        }
        require(body.documentId == identity.documentId)
        return Decoded(body, info.originalPdfHash, info.header.anchors != null)
    }

    private fun checkVersions(file: Path) {
        bundleDirectory(file.asAssetFile()).use { zip ->
            for ((name, max) in listOf("manifest.json" to DocumentCodec.VERSION, BundleIdentity.ENTRY to 2,
                    AnchorMetadata.ENTRY to 1, MapJson.ENTRY to MapJson.VERSION)) {
                zip.entry(name)?.open()?.use { input ->
                    val parser = JsonPull(jsonChars(input, strict = true), strict = true)
                    parser.beginObject()
                    while (parser.hasNext()) {
                        if (parser.nextName() == "version") {
                            if (parser.nextInt() > max) throw FutureVersion()
                            break
                        }
                        parser.skipValue()
                    }
                }
            }
        }
    }

    fun write(body: LiteBody, path: Path): String {
        lateinit var hash: String
        atomicWrite(fs, path) { sink ->
            val digest = HashingSink.sha256(sink)
            when (body) {
                is LiteBody.Note -> notes.write(body.doc, digest)
                is LiteBody.Canvas -> canvases.write(body.doc, digest)
            }
            hash = digest.hash.hex()
        }
        return hash
    }
}
