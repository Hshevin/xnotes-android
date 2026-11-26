package com.xnotes.storage.lite

import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Document
import com.xnotes.core.model.enableReferenceAnchors
import com.xnotes.core.pal.asAssetFile
import com.xnotes.core.pal.assetPath
import com.xnotes.kio.atomicWrite
import com.xnotes.kio.Uuids
import com.xnotes.kio.fileIdentity
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.use

internal expect fun linkNewFile(stage: Path, target: Path): Boolean

internal fun createLiteDocument(
    store: LiteStore, fs: FileSystem, codec: LiteCodec, documents: Path, directory: Path,
    baseName: String, kind: LiteKind, pdf: Path?, setup: LiteNoteSetup?, assets: Path, inDocuments: (Path) -> Boolean,
): LiteSession {
    require(baseName.isNotBlank() && !baseName.startsWith('.') && baseName.none { it == '/' || it == '\\' || it.isISOControl() })
    require(fs.metadata(directory).isDirectory)
    val parent = requireNotNull(fileIdentity(directory))
    val staging = documents / ".create-${Uuids.random()}"
    fs.createDirectory(staging, mustCreate = true)
    try {
        val stage = staging / "body.${kind.extension}"
        val imported = pdf?.let { source ->
            val copy = assets / "source.pdf"
            val input = try { fs.source(source) } catch (_: IOException) { throw UnreadablePdf() }
            input.use { atomicWrite(fs, copy) { sink -> sink.writeAll(it) } }
            copy.asAssetFile()
        }
        val measured = imported?.let { requireNotNull(fileStamp(it.assetPath())) }
        val body = when (kind) {
            LiteKind.NOTE -> LiteBody.Note(when {
                setup != null -> {
                    val templates = store.templates()
                    setup.newDocument { doc, key -> require(templates.embed(doc, key, assets)) }
                }
                imported != null -> importLitePdf(imported).apply { enableReferenceAnchors() }
                else -> Document.blank().apply { enableReferenceAnchors() }
            })
            LiteKind.CANVAS -> LiteBody.Canvas(InfiniteDocument())
        }
        val decoded = codec.decoded(body)
        codec.write(body, stage)
        require(imported == null || fileStamp(imported.assetPath()) == measured)
        val stamp = requireNotNull(fileStamp(stage))
        var number = 1
        while (true) {
            val stem = if (number == 1) baseName else "$baseName ($number)"
            val path = directory / "$stem.${kind.extension}"
            number++
            require(fileIdentity(directory) == parent && (directory == documents || inDocuments(directory)))
            if (fs.metadataOrNull(path) != null) continue
            var linked = false
            coordinateLiteWrite(path) {
                require(fileIdentity(directory) == parent && (directory == documents || inDocuments(directory)))
                require(fs.metadata(stage).isRegularFile && fileStamp(stage) == stamp)
                linked = linkNewFile(stage, path)
                stamp
            }
            if (linked) return LiteSession(store, path, assets, decoded, stamp, null)
        }
    } finally {
        fs.deleteRecursively(staging)
    }
}
