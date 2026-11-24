package com.xnotes.core.template

import com.xnotes.core.model.Document
import com.xnotes.core.model.PageTemplates
import com.xnotes.core.pal.AssetFile
import com.xnotes.core.pal.asAssetFile
import com.xnotes.core.pal.assetPath
import com.xnotes.format.TemplateFormatException
import com.xnotes.format.TemplatePackage
import com.xnotes.format.TemplateReader
import com.xnotes.format.readAtMost
import com.xnotes.format.temporaryAsset
import com.xnotes.kio.KFiles
import com.xnotes.kio.Sha256
import okio.HashingSink
import okio.HashingSource
import okio.Path
import okio.blackholeSink
import okio.use

/** A snapshot of the templates offered in a notes folder. Package images stay on disk. */
class TemplateCatalog(entries: Collection<Entry>) {
    enum class Source { BUILT_IN, BUNDLED, IMPORTED, NOTE }

    data class Entry(val key: String, val template: Template, val text: String, val source: Source, val file: AssetFile? = null)

    private val byKey = entries.sortedWith(compareBy<Entry> { it.source.ordinal }
        .thenBy { if (it.source == Source.BUILT_IN) PageTemplates.BUILT_IN.indexOf(it.key) else 0 }
        .thenBy { it.template.name.lowercase() }.thenBy { it.key })
        .distinctBy { it.key }.associateBy { it.key }

    fun all(): List<Entry> = byKey.values.toList()
    fun entry(key: String): Entry? = byKey[key]

    /** Packages get a document-owned copy in [assets]. Without a directory, the caller owns the
     * temporary file returned in doc.templatePackages until the document is saved or discarded. */
    fun embed(doc: Document, key: String?, assets: Path? = KFiles.tempDirectory()): Boolean {
        if (key == null || key == PageTemplates.NONE || PageTemplates.isBuiltIn(key)) return true
        if (key in doc.templates || key in doc.templatePackages) return true
        val entry = byKey[key] ?: return false
        val packageFile = entry.file
        if (packageFile == null) {
            doc.templates = doc.templates + (key to entry.text)
            return true
        }
        if (assets == null) return false
        val fs = KFiles.system
        val size = fs.metadataOrNull(packageFile.assetPath())?.size ?: return false
        if (size !in 0..TemplatePackage.MAX_BYTES) return false
        val copy = temporaryAsset("tpl", EXT, assets.asAssetFile())
        try {
            val copiedKey = HashingSource.sha256(fs.source(packageFile.assetPath())).use { input ->
                fs.write(copy.assetPath()) { write(input, size) }
                input.hash.hex().take(16)
            }
            if (copiedKey != key) return false
            doc.templatePackages = doc.templatePackages + (key to copy)
            return true
        } finally {
            if (doc.templatePackages[key] != copy) fs.delete(copy.assetPath())
        }
    }

    companion object {
        const val EXT = ".xtemplate"

        fun keyOf(text: String): String = Sha256.hex(text.encodeToByteArray()).take(16)

        fun keyOf(path: Path): String = HashingSink.sha256(blackholeSink()).use { digest ->
            KFiles.system.read(path) { readAll(digest) }
            digest.hash.hex().take(16)
        }

        fun plain(text: String, source: Source, rulingId: String? = null): Entry {
            require(if (source == Source.BUILT_IN) PageTemplates.isBuiltIn(rulingId) else rulingId == null)
            return Entry(rulingId ?: keyOf(text), TemplateReader.read(text), text, source)
        }

        /** [knownKey] can be supplied for a previously hashed, immutable bundled package. */
        fun packaged(path: Path, source: Source, knownKey: String? = null): Entry {
            val file = path.asAssetFile()
            val text = TemplatePackage.readJson(file)
            return Entry(knownKey ?: keyOf(path), TemplateReader.read(text), text, source, file)
        }

        fun read(path: Path, source: Source, rulingId: String? = null): Entry {
            if (rulingId == null && TemplatePackage.isPackage(path.asAssetFile())) return packaged(path, source)
            val bytes = KFiles.system.read(path) { readAtMost(this, TemplateReader.MAX_BYTES) }
                ?: throw TemplateFormatException("template is larger than 1 MiB")
            return plain(bytes.decodeToString(), source, rulingId)
        }

        /** The resource folder has the same layout on both platforms: three rulings and examples/. */
        fun bundled(directory: Path): List<Entry> =
            PageTemplates.BUILT_IN.mapNotNull { id ->
                runCatching { read(directory / (id + EXT), Source.BUILT_IN, id) }.getOrNull()
            } + files(directory / "examples", Source.BUNDLED)

        fun imported(documents: Path): List<Entry> {
            val config = documents / ".xnote"
            if (KFiles.system.metadataOrNull(config)?.isDirectory != true) return emptyList()
            return files(config / "templates", Source.IMPORTED)
        }

        private fun files(directory: Path, source: Source): List<Entry> {
            val fs = KFiles.system
            if (fs.metadataOrNull(directory)?.isDirectory != true) return emptyList()
            return fs.listOrNull(directory).orEmpty().filter { it.name.endsWith(EXT) && fs.metadataOrNull(it)?.isRegularFile == true }
                .mapNotNull { runCatching { read(it, source) }.getOrNull() }
        }
    }
}
