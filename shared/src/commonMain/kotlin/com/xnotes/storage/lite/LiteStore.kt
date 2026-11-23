package com.xnotes.storage.lite

import com.xnotes.core.pal.ImageCodec
import com.xnotes.core.pal.TextMeasurer
import com.xnotes.core.pal.asAssetFile
import com.xnotes.core.pal.assetPath
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.BundleSnapshot
import com.xnotes.kio.Sha256
import com.xnotes.kio.Uuids
import com.xnotes.kio.fileIdentity
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.use

enum class LiteKind(internal val extension: String) {
    NOTE(DocumentKind.NOTE.extension), CANVAS(DocumentKind.CANVAS.extension);

    internal companion object {
        fun of(path: Path) = entries.find { path.name.endsWith(".${it.extension}", ignoreCase = true) }
    }
}

enum class LiteRefusal { NOT_FOUND, UNSUPPORTED_KIND, FUTURE_VERSION, CORRUPT }

sealed interface LiteOpen {
    data class Opened(val session: LiteSession) : LiteOpen
    data class Refused(val reason: LiteRefusal) : LiteOpen
}

sealed interface LiteSave {
    data object Saved : LiteSave
    data class Failed(val cause: Throwable, val protectedCopy: Path) : LiteSave
    data class Conflict(val protectedCopy: Path) : LiteSave
}

class LiteProtectionException internal constructor(saveFailure: Exception, protectionFailure: Exception) :
    IOException("Document was not saved and its private copy could not be written", protectionFailure) {
    init { addSuppressed(saveFailure) }
}

internal class SourceConflict : Exception()
internal expect fun coordinateLiteWrite(path: Path, publish: () -> Stamp): Stamp

class LiteStore(private val fs: FileSystem, private val documents: Path, private val privateDir: Path,
                imageCodec: ImageCodec, textMeasurer: TextMeasurer) {
    private val codec = LiteCodec(fs, imageCodec, textMeasurer)
    private val recoveries = LiteRecoveryFiles(fs, privateDir / "recoveries", documents)

    init {
        require(documents.isAbsolute && privateDir.isAbsolute)
        fs.createDirectories(privateDir)
        val docs = fs.canonicalize(documents)
        require(generateSequence(fs.canonicalize(privateDir)) { it.parent }.none { it == docs })
    }

    fun open(path: Path): LiteOpen = opening {
        val kind = LiteKind.of(path) ?: return@opening LiteOpen.Refused(LiteRefusal.UNSUPPORTED_KIND)
        if (!inDocuments(path)) return@opening LiteOpen.Refused(LiteRefusal.UNSUPPORTED_KIND)
        val identity = fileIdentity(path) ?: return@opening LiteOpen.Refused(LiteRefusal.NOT_FOUND)
        require(fs.metadata(path).isRegularFile)
        openWithAssets { assets ->
            BundleSnapshot.fromInput(fs.source(path), assets.asAssetFile()).use { snapshot ->
                val decoded = codec.read(snapshot.file.assetPath(), kind, assets)
                val stamp = Stamp(identity, snapshot.sourceHash)
                checkSource(path, stamp)
                LiteSession(this, path, assets, decoded, stamp, null)
            }
        }
    }

    fun pendingRecoveries(): List<LiteRecovery> = recoveries.pending()

    fun restore(recovery: LiteRecovery): LiteOpen = opening {
        val entry = recoveries.lookup(recovery)
        openWithAssets { assets ->
            BundleSnapshot.fromInput(fs.source(entry.body), assets.asAssetFile()).use { snapshot ->
                require(snapshot.sourceHash == entry.hash)
                val decoded = codec.read(snapshot.file.assetPath(), requireNotNull(LiteKind.of(recovery.original)), assets)
                require(decoded.body.documentId == recovery.documentId)
                LiteSession(this, recovery.original, assets, decoded, entry.stamp, recovery)
            }
        }
    }

    fun discard(recovery: LiteRecovery) = recoveries.discard(recovery)

    private inline fun opening(block: () -> LiteOpen): LiteOpen = try {
        block()
    } catch (_: FutureVersion) {
        LiteOpen.Refused(LiteRefusal.FUTURE_VERSION)
    } catch (_: Exception) {
        LiteOpen.Refused(LiteRefusal.CORRUPT)
    }

    private fun openWithAssets(open: (Path) -> LiteSession): LiteOpen.Opened {
        val assets = assets()
        try {
            return LiteOpen.Opened(open(assets))
        } catch (failure: Throwable) {
            release(assets)
            throw failure
        }
    }

    private fun assets() = (privateDir / "assets-${Uuids.random()}").also { fs.createDirectory(it, mustCreate = true) }

    internal fun release(assets: Path) = fs.deleteRecursively(assets)

    private fun inDocuments(path: Path): Boolean {
        if (!path.isAbsolute || path == documents || path != path.normalized()) return false
        var current = path
        while (current != documents) {
            if (current.name.startsWith('.') || fs.metadataOrNull(current)?.symlinkTarget != null) return false
            current = current.parent ?: return false
        }
        return true
    }

    private fun hash(path: Path): String = fs.read(path) {
        val digest = Sha256()
        val buffer = ByteArray(65536)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.hex()
    }

    private fun checkSource(path: Path, stamp: Stamp) {
        if (!inDocuments(path) || fileIdentity(path) != stamp.identity) throw SourceConflict()
        val current = try { hash(path) } catch (failure: IOException) {
            if (fileIdentity(path) != stamp.identity) throw SourceConflict()
            throw failure
        }
        if (current != stamp.hash || fileIdentity(path) != stamp.identity) throw SourceConflict()
    }

    private fun verify(path: Path, body: LiteBody, hash: String, original: Decoded) {
        require(this.hash(path) == hash)
        val assets = assets()
        try {
            val decoded = codec.read(path, body.kind, assets)
            require(decoded.documentId == original.documentId && decoded.pdfHash == original.pdfHash &&
                decoded.anchored == original.anchored)
            body.checkDecoded(decoded.body)
        } finally {
            release(assets)
        }
    }

    internal fun publish(path: Path, base: Stamp, body: LiteBody, original: Decoded, beforePublish: ((Path) -> Unit)?): Stamp {
        checkSource(path, base)
        require(fileIdentity(documents)?.device == base.identity.device)
        val stage = documents / ".${Uuids.random()}.tmp"
        var published = false
        try {
            val expected = codec.write(body, stage)
            val identity = requireNotNull(fileIdentity(stage))
            beforePublish?.invoke(stage)
            require(fs.metadata(stage).isRegularFile && fileIdentity(stage) == identity)
            verify(stage, body, expected, original)
            require(fileIdentity(stage) == identity && fs.metadata(stage).isRegularFile && hash(stage) == expected)
            if (!inDocuments(path) || fileIdentity(path) != base.identity) throw SourceConflict()
            return coordinateLiteWrite(path) {
                checkSource(path, base)
                require(fileIdentity(stage) == identity && fs.metadata(stage).isRegularFile)
                val stamp = Stamp(identity, expected)
                fs.atomicMove(stage, path)
                published = true
                stamp
            }
        } finally {
            if (!published) fs.delete(stage)
        }
    }

    internal fun protect(path: Path, base: Stamp, body: LiteBody, original: Decoded): Protected =
        recoveries.write(path, original.documentId, base, { codec.write(body, it) }) { file, hash -> verify(file, body, hash, original) }
}
