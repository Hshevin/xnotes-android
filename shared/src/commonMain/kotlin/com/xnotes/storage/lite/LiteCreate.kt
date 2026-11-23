package com.xnotes.storage.lite

import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Document
import com.xnotes.core.model.enableReferenceAnchors
import com.xnotes.kio.Uuids
import com.xnotes.kio.fileIdentity
import okio.FileSystem
import okio.Path

internal expect fun linkNewFile(stage: Path, target: Path): Boolean

internal fun createLiteDocument(
    store: LiteStore, fs: FileSystem, codec: LiteCodec, documents: Path, directory: Path,
    baseName: String, kind: LiteKind, assets: Path, inDocuments: (Path) -> Boolean, hash: (Path) -> String,
): LiteSession {
    require(baseName.isNotBlank() && !baseName.startsWith('.') && baseName.none { it == '/' || it == '\\' || it.isISOControl() })
    require(fs.metadata(directory).isDirectory)
    val parent = requireNotNull(fileIdentity(directory))
    val body = when (kind) {
        LiteKind.NOTE -> LiteBody.Note(Document.blank().apply { enableReferenceAnchors() })
        LiteKind.CANVAS -> LiteBody.Canvas(InfiniteDocument())
    }
    val staging = documents / ".create-${Uuids.random()}"
    fs.createDirectory(staging, mustCreate = true)
    try {
        val stage = staging / "body.${kind.extension}"
        val digest = codec.write(body.snapshot(), stage)
        val stamp = Stamp(requireNotNull(fileIdentity(stage)), digest)
        val decoded = codec.read(stage, kind, assets)
        body.checkDecoded(decoded.body)
        require(decoded.anchored == (kind == LiteKind.NOTE) && decoded.pdfHash == null && hash(stage) == digest)
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
                require(fs.metadata(stage).isRegularFile && hash(stage) == stamp.hash && fileIdentity(stage) == stamp.identity)
                linked = linkNewFile(stage, path)
                stamp
            }
            if (linked) return LiteSession(store, path, assets, decoded, stamp, null)
        }
    } finally {
        fs.deleteRecursively(staging)
    }
}
