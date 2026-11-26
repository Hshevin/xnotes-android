package com.xnotes.storage.lite

import com.xnotes.core.pal.ImageCodec
import com.xnotes.core.pal.TextMeasurer
import com.xnotes.core.pal.asAssetFile
import com.xnotes.core.template.TemplateCatalog
import com.xnotes.core.util.DocumentKind
import com.xnotes.core.util.CoreLock
import com.xnotes.core.util.locked
import com.xnotes.format.BundleMigration
import com.xnotes.kio.Uuids
import okio.FileSystem
import okio.IOException
import okio.Path

enum class LiteKind(internal val extension: String) {
    NOTE(DocumentKind.NOTE.extension), CANVAS(DocumentKind.CANVAS.extension);

    internal companion object {
        fun of(path: Path) = entries.find { path.name.endsWith(".${it.extension}", ignoreCase = true) }
    }
}

enum class LiteRefusal { NOT_FOUND, UNSUPPORTED_KIND, FUTURE_VERSION, CORRUPT, PDF_UNREADABLE }

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
internal expect fun bundledTemplateDirectory(): Path?

// Stores opened by separate windows may refer to the same private recovery directory.
private val recoveryLock = CoreLock()

class LiteStore(private val fs: FileSystem, private val documents: Path, private val privateDir: Path,
                imageCodec: ImageCodec, textMeasurer: TextMeasurer, bundledTemplates: Path? = bundledTemplateDirectory()) {
    private val codec = LiteCodec(fs, imageCodec, textMeasurer)
    private val recoveries = LiteRecoveryFiles(fs, privateDir / "recoveries", documents)
    private val bundled by lazy { bundledTemplates?.let(TemplateCatalog::bundled).orEmpty() }

    init {
        require(documents.isAbsolute && privateDir.isAbsolute)
        fs.createDirectories(privateDir)
        val docs = fs.canonicalize(documents)
        require(generateSequence(fs.canonicalize(privateDir)) { it.parent }.none { it == docs })
    }

    fun create(directory: Path, baseName: String, kind: LiteKind): LiteOpen =
        create(directory, baseName, kind, pdf = null)

    /** Snapshot the bundled and current top-level imported templates. Call on an IO thread. */
    fun templates(): TemplateCatalog = TemplateCatalog(bundled + TemplateCatalog.imported(documents))

    fun create(directory: Path, baseName: String, setup: LiteNoteSetup): LiteOpen =
        create(directory, baseName, LiteKind.NOTE, pdf = null, setup = setup)

    /** Imports the selected PDF into a self-contained, editable note. Call on an IO thread. */
    fun importPdf(directory: Path, baseName: String, pdf: Path): LiteOpen =
        create(directory, baseName, LiteKind.NOTE, pdf)

    private fun create(directory: Path, baseName: String, kind: LiteKind, pdf: Path?, setup: LiteNoteSetup? = null): LiteOpen = opening {
        require(directory == documents || inDocuments(directory))
        openWithAssets { assets ->
            createLiteDocument(this, fs, codec, documents, directory, baseName, kind, pdf, setup, assets, ::inDocuments)
        }
    }

    fun open(path: Path): LiteOpen = opening {
        val kind = LiteKind.of(path) ?: return@opening LiteOpen.Refused(LiteRefusal.UNSUPPORTED_KIND)
        if (!inDocuments(path)) return@opening LiteOpen.Refused(LiteRefusal.UNSUPPORTED_KIND)
        val stamp = fileStamp(path) ?: return@opening LiteOpen.Refused(LiteRefusal.NOT_FOUND)
        require(fs.metadata(path).isRegularFile)
        openWithAssets { assets ->
            val decoded = codec.read(path, kind, assets)
            checkSource(path, stamp)
            LiteSession(this, path, assets, decoded, stamp, null)
        }
    }

    fun pendingRecoveries(): List<LiteRecovery> = recoveries.pending()

    internal fun manages(fileSystem: FileSystem, root: Path) = fs === fileSystem && documents == root

    /** A library rename must see a complete recovery index, stable through the filesystem move. */
    internal fun <T> withRecoveryIndex(action: (List<LiteRecovery>) -> T): T = recoveryLock.locked {
        action(recoveries.pending(requireComplete = true))
    }

    fun restore(recovery: LiteRecovery): LiteOpen = opening {
        val entry = recoveries.lookup(recovery)
        openWithAssets { assets ->
            entry.legacyBodyHash?.let { require(BundleMigration.hash(entry.body.asAssetFile()) == it) }
            val decoded = codec.read(entry.body, requireNotNull(LiteKind.of(recovery.original)), assets)
            require(decoded.documentId == recovery.documentId)
            var stamp = entry.stamp
            if (entry.legacySourceHash != null && inDocuments(recovery.original)) {
                val current = fileStamp(recovery.original)
                if (current?.identity == stamp.identity && runCatching {
                    BundleMigration.hash(recovery.original.asAssetFile()) == entry.legacySourceHash &&
                        fileStamp(recovery.original) == current
                }.getOrDefault(false)) stamp = current
            }
            LiteSession(this, recovery.original, assets, decoded, stamp, recovery)
        }
    }

    fun discard(recovery: LiteRecovery) = recoveryLock.locked { recoveries.discard(recovery) }

    private inline fun opening(block: () -> LiteOpen): LiteOpen = try {
        block()
    } catch (_: UnreadablePdf) {
        LiteOpen.Refused(LiteRefusal.PDF_UNREADABLE)
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

    private fun checkSource(path: Path, stamp: Stamp) {
        if (!inDocuments(path) || fileStamp(path) != stamp) throw SourceConflict()
    }

    internal fun publish(path: Path, base: Stamp, body: LiteBody, original: Decoded,
                         previous: List<StoredAsset>, beforePublish: ((Path) -> Unit)?): Pair<Stamp, List<StoredAsset>> {
        checkSource(path, base)
        original.checkPdf()
        val current = codec.stored(body)
        val probe = beforePublish?.let { privateDir / "candidate-${Uuids.random()}" }
        try {
            if (probe != null) {
                codec.write(body, probe)
                val stamp = requireNotNull(fileStamp(probe))
                beforePublish(probe)
                require(fileStamp(probe) == stamp)
            }
            val receipt = coordinateLiteWrite(path) {
                checkSource(path, base)
                val patched = current == previous && try { codec.splice(path, privateDir, body, current) } catch (_: Exception) { false }
                if (!patched) codec.write(body, path)
                requireNotNull(fileStamp(path))
            }
            return receipt to current
        } finally { probe?.let { fs.delete(it) } }
    }

    internal fun protect(path: Path, base: Stamp, body: LiteBody, original: Decoded): Protected = recoveryLock.locked {
        recoveries.write(path, original.documentId, base, { codec.write(body, it) }, original::checkPdf)
    }
}
