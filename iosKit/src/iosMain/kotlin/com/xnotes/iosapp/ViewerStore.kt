package com.xnotes.iosapp

import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Document
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.*
import com.xnotes.kio.KFiles
import com.xnotes.kio.Uuids
import com.xnotes.platform.ios.CgImageCodec
import com.xnotes.platform.ios.CgTextMeasurer
import com.xnotes.storage.ios.IosStorageLocations
import okio.Path
import okio.use

internal enum class LiteRefusal { NOT_FOUND, UNSUPPORTED_KIND, FUTURE_VERSION, CORRUPT }
internal sealed interface LiteOpen {
    class Opened(val session: ViewerSession) : LiteOpen
    class Refused(val reason: LiteRefusal) : LiteOpen
}

internal class ViewerSession(val path: Path, val note: Document?, val canvas: InfiniteDocument?, val assets: Path) {
    fun close() = KFiles.system.deleteRecursively(assets)
}

internal fun interface ViewerStore { fun open(path: Path): LiteOpen }

/** Temporary read-only adapter until W3-C's LiteStore is merged. Never acquires write access to the source. */
internal object ReadOnlyLiteStore : ViewerStore {
    override fun open(path: Path): LiteOpen {
        val fs = KFiles.system
        val kind = when (path.name.substringAfterLast('.').lowercase()) {
            "xnote" -> DocumentKind.NOTE
            "xcanvas" -> DocumentKind.CANVAS
            else -> return LiteOpen.Refused(LiteRefusal.UNSUPPORTED_KIND)
        }
        var owned: Path? = null
        return try {
            val meta = fs.metadataOrNull(path) ?: return LiteOpen.Refused(LiteRefusal.NOT_FOUND)
            require(meta.isRegularFile && meta.symlinkTarget == null)
            val assets = IosStorageLocations.cache / Uuids.random()
            fs.createDirectory(assets, mustCreate = true)
            owned = assets
            val snapshot = assets / "bundle"
            fs.copy(path, snapshot)
            if (newerIosBundle(snapshot)) return LiteOpen.Refused(LiteRefusal.FUTURE_VERSION)
            require(BundleReferenceInfo.inspectStrict(snapshot).kind == kind)
            val session = fs.source(snapshot).use { source ->
                if (kind == DocumentKind.NOTE) {
                    val note = DocumentCodec(CgImageCodec, CgTextMeasurer).readComplete(source, assets, assets, preserveInkSamples = true).document
                    require(note.pages.isNotEmpty() && (note.pages.none { it.pdfPage != null } || note.pdfFile != null))
                    ViewerSession(path, note, null, assets)
                } else ViewerSession(path, null, CanvasCodec(CgImageCodec, CgTextMeasurer).readComplete(source, assets).document, assets)
            }
            fs.delete(snapshot)
            owned = null
            LiteOpen.Opened(session)
        } catch (_: Exception) { LiteOpen.Refused(LiteRefusal.CORRUPT) }
        finally { owned?.let { fs.deleteRecursively(it) } }
    }
}
