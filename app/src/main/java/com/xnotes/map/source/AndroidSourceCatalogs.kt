package com.xnotes.map.source

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import com.xnotes.format.AnchorManifest
import com.xnotes.format.AnchorMetadata
import com.xnotes.format.BundleIdentity
import com.xnotes.format.CanvasCodec
import com.xnotes.format.JsonPull
import com.xnotes.format.ZipTail
import com.xnotes.storage.NotebookStorage
import com.xnotes.storage.zipFilePath
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.channels.FileChannel
import java.util.zip.ZipInputStream
import java.nio.file.NoSuchFileException
import java.util.zip.ZipFile

/**
 * [SourceCatalogs] over the app's document registry and files. Read-only: it never enables page
 * ids, repairs, migrates or registers anything, and keeps no file open after [read] returns.
 *
 * - [locations]: the registry's available locations for the id, in its order.
 * - [read]: the file's bundle header and page anchors: NOTE with ordered page ids, NOTE with null ids when the note has none yet, CANVAS
 *   with null; anything else OTHER. A file that is not there → null; unreadable or corrupt → throws.
 *
 * Called off the main thread.
 */
class AndroidSourceCatalogs(context: Context) : SourceCatalogs {
    private val app = context.applicationContext
    private val storage = NotebookStorage.get(app)

    override fun locations(documentId: String): List<String> = storage.registry.locations(documentId)

    override fun read(location: String): SourceCatalog? {
        val uri = Uri.parse(location)
        if (uri.scheme == "content") return readContent(uri)
        val file = when (uri.scheme) {
            null -> File(location).also { require(it.isAbsolute) }
            "file" -> File(requireNotNull(uri.path))
            else -> throw IOException("Unsupported source URI")
        }
        val channel = try { FileChannel.open(file.toPath()) } catch (_: NoSuchFileException) { return null }
        channel.use { readDirectory(it, file.name)?.let { result -> return result } }
        return readZipFile(file, file.name)
    }

    private fun readZipFile(file: File, name: String): SourceCatalog =
        ZipFile(file).use { zip ->
            val id = zip.getEntry(BundleIdentity.ENTRY)?.let { zip.getInputStream(it).use(BundleIdentity::read) }
            val marker = zip.getEntry(AnchorMetadata.ENTRY)?.let { zip.getInputStream(it).use(AnchorMetadata::read) }
            val manifest = requireNotNull(zip.getEntry(MANIFEST))
            zip.getInputStream(manifest).use { catalog(name, requireNotNull(id), marker, it) }
        }

    private fun readContent(uri: Uri): SourceCatalog? {
        val provider = app.contentResolver.acquireUnstableContentProviderClient(uri)
            ?: throw IOException("Source provider is unavailable")
        provider.use {
            val asset = try {
                provider.openAssetFile(uri, "r") ?: throw IOException("Source provider returned no file")
            } catch (error: FileNotFoundException) {
                val cause = error.cause as? ErrnoException
                if (cause != null && cause.errno != OsConstants.ENOENT && cause.errno != OsConstants.ENOTDIR) throw error
                // Some providers report an unreadable but existing document as FileNotFoundException.
                // Our DocumentsProvider synthesizes a metadata row even for an absent path.
                if (cause == null && uri.authority != app.packageName + ".documents" &&
                    provider.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { it.moveToFirst() } == true
                ) throw error
                return null
            }
            return asset.use {
                val name = provider.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                    ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')
                asset.createInputStream().use { input ->
                    if (asset.startOffset == 0L && asset.parcelFileDescriptor.statSize >= 0 &&
                        (asset.declaredLength < 0 || asset.declaredLength == asset.parcelFileDescriptor.statSize)) {
                        return readDirectory(input.channel, name) ?: readZipFile(asset.parcelFileDescriptor.zipFilePath(), name)
                    }
                    readSequential(input, name)
                }
            }
        }
    }

    private fun readDirectory(channel: FileChannel, name: String): SourceCatalog? {
        val directory = ZipTail.read(channel, allowComment = true) ?: return null
        fun entry(key: String): ZipTail.Entry? {
            val matches = directory.entries.filter { it.name == key }
            require(matches.size <= 1) { "Duplicate $key" }
            return matches.singleOrNull()
        }
        val identity = requireNotNull(ZipTail.readEntry(channel, requireNotNull(entry(BundleIdentity.ENTRY)), BundleIdentity::read))
        val marker = entry(AnchorMetadata.ENTRY)?.let { requireNotNull(ZipTail.readEntry(channel, it, AnchorMetadata::read)) }
        return requireNotNull(ZipTail.readEntry(channel, requireNotNull(entry(MANIFEST))) { catalog(name, identity, marker, it) })
    }

    // A pipe can deliver the anchor marker after the manifest. Retain only the manifest in that case.
    private fun readSequential(input: InputStream, name: String): SourceCatalog {
        var identity: BundleIdentity? = null
        var marker: AnchorMetadata? = null
        var manifest: File? = null
        try {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    when ((zip.nextEntry ?: break).name) {
                        BundleIdentity.ENTRY -> { require(identity == null); identity = BundleIdentity.read(zip) }
                        AnchorMetadata.ENTRY -> { require(marker == null); marker = AnchorMetadata.read(zip) }
                        MANIFEST -> {
                            require(manifest == null)
                            val file = File.createTempFile("source-manifest-", ".json", app.cacheDir)
                            manifest = file
                            file.outputStream().use { out ->
                                val buffer = ByteArray(65536)
                                var count = 0L
                                while (true) {
                                    val n = zip.read(buffer)
                                    if (n < 0) break
                                    count += n
                                    require(count <= AnchorManifest.MAX_BYTES)
                                    out.write(buffer, 0, n)
                                }
                            }
                        }
                    }
                }
            }
            return requireNotNull(manifest).inputStream().use { catalog(name, requireNotNull(identity), marker, it) }
        } finally { manifest?.delete() }
    }

    private fun catalog(name: String, identity: BundleIdentity, marker: AnchorMetadata?, input: InputStream): SourceCatalog {
        if (marker != null) {
            require(marker.documentId == identity.documentId)
            val pages = AnchorManifest.inspect({ input }, anchored = true).orderedPageIds
            return SourceCatalog(identity.documentId, SourceCatalog.Kind.NOTE, name, pages)
        }
        val json = JsonPull(AnchorManifest.reader(input), strict = true, tokenLimit = Int.MAX_VALUE)
        json.beginObject()
        while (json.hasNext()) {
            if (json.nextName() == "format") {
                val format = json.nextString()
                val kind = when {
                    format == "xnote" -> SourceCatalog.Kind.NOTE
                    CanvasCodec.isCanvasFormat(format) -> SourceCatalog.Kind.CANVAS
                    else -> SourceCatalog.Kind.OTHER
                }
                return SourceCatalog(identity.documentId, kind, name, null)
            }
            json.skipValue()
        }
        throw IOException("Missing manifest format")
    }

    private companion object { const val MANIFEST = "manifest.json" }
}
